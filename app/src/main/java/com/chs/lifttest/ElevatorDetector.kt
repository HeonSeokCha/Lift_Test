package com.chs.lifttest

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class ElevatorDetector(
    private val config: DetectorConfig = DetectorConfig(),
    private val listener: Listener,
) {
    enum class Phase { GATED_WALKING, MONITORING, CANDIDATE, RIDING }
    enum class Direction { UP, DOWN }

    data class ElevatorRide(
        val direction: Direction,
        val startNs: Long,
        val endNs: Long,
        val altitudeChangeM: Float,
        val estimatedFloors: Int,
        val peakVerticalSpeedMps: Float,
        /** 0.0~1.0. 출발 임펄스 확인 여부, 속도 범위 등을 반영한 휴리스틱 점수 */
        val confidence: Float,
    ) {
        val durationSec: Float get() = (endNs - startNs) / 1e9f
    }

    interface Listener {
        fun onPhaseChanged(phase: Phase) {}
        fun onRideStarted(direction: Direction, timestampNs: Long) {}
        fun onRideFinished(ride: ElevatorRide) {}
        fun onRideCancelled(reason: String) {}
        /** 튜닝용 디버그 로그. 상태 전이, 기울기, 탑승 판정 근거가 전달된다. */
        fun onDebug(message: String) {}
    }

    private val motion = MotionClassifier(config)
    private val vertical = VerticalAccel(config)
    private val pressure = PressureTrend(config)

    var phase: Phase = Phase.GATED_WALKING
        private set

    // 탑승 추적용
    private var candidateStartNs = 0L   // 소급된 출발 시점 (기울기 창 시작)
    private var candidateTriggerNs = 0L // 실제로 임계값을 넘은 시점
    private var startPressure = 0f
    private var direction = Direction.UP
    private var calmSinceNs = -1L
    private var calmPressure = 0f       // 정차 감지 시점의 기압
    private var walkSinceNs = -1L       // RIDING 중 걷기 시작 시점 (하차 감지용)
    private var peakSlope = 0f
    private var impulseConfirmed = false
    private var lastDebugNs = 0L

    fun onAccelerometer(timestampNs: Long, x: Float, y: Float, z: Float) {
        motion.onAccel(timestampNs, x, y, z)
        vertical.onAccel(timestampNs, x, y, z)
        evaluateMotionGate(timestampNs)
    }

    fun onStep(timestampNs: Long) {
        motion.onStep(timestampNs)
        evaluateMotionGate(timestampNs)
    }

    fun onPressure(timestampNs: Long, hPa: Float) {
        pressure.onPressure(timestampNs, hPa)
        if (!pressure.isReady) return
        val slope = pressure.slopeHpaPerSec
        val p = pressure.filtered

        if (phase != Phase.GATED_WALKING && timestampNs - lastDebugNs >= config.debugIntervalNs) {
            lastDebugNs = timestampNs
            listener.onDebug(
                "phase=$phase slope=${"%.4f".format(slope)}hPa/s p=${"%.3f".format(p)} " +
                        "motion=${motion.state} impulse=$impulseConfirmed"
            )
        }

        when (phase) {
            Phase.GATED_WALKING -> Unit
            Phase.MONITORING -> if (abs(slope) >= config.rideStartSlope) {
                // 기울기 창의 시작점을 출발 시점으로 소급
                candidateStartNs = pressure.windowStartNs
                candidateTriggerNs = timestampNs
                startPressure = pressure.windowStartPressure
                direction = if (slope < 0) Direction.UP else Direction.DOWN // 기압↓ = 상승
                peakSlope = abs(slope)
                calmSinceNs = -1L
                impulseConfirmed = vertical.hadImpulse(
                    sinceNs = candidateStartNs - config.impulseLookbackNs,
                    upward = direction == Direction.UP,
                )
                listener.onDebug("candidate: dir=$direction slope=${"%.4f".format(slope)} impulse=$impulseConfirmed")
                setPhase(Phase.CANDIDATE)
            }

            Phase.CANDIDATE -> {
                val sameSign = (slope < 0) == (direction == Direction.UP)
                if (abs(slope) < config.rideEndSlope || !sameSign) {
                    // 문 여닫힘/환기 등 순간 기압 변동 → 지속되지 않으면 기각
                    listener.onDebug("candidate rejected: slope=${"%.4f".format(slope)} sameSign=$sameSign")
                    setPhase(Phase.MONITORING)
                    return
                }
                peakSlope = maxOf(peakSlope, abs(slope))
                if (!impulseConfirmed) {
                    impulseConfirmed = vertical.hadImpulse(
                        candidateStartNs - config.impulseLookbackNs, direction == Direction.UP,
                    )
                }
                // 소급 시점이 아닌 '트리거 이후' 지속 시간 + 누적 고도 변화로 확정 → 문 여닫힘 돌풍 배제
                val sustained = timestampNs - candidateTriggerNs >= config.minSustainNs
                val movedEnough = abs(altitudeDelta(startPressure, p)) >= config.minCandidateAltitudeM
                if (sustained && movedEnough) {
                    setPhase(Phase.RIDING)
                    listener.onRideStarted(direction, candidateStartNs)
                }
            }

            Phase.RIDING -> {
                val calm = abs(slope) < config.rideEndSlope
                if (calm) {
                    if (calmSinceNs < 0) { calmSinceNs = timestampNs; calmPressure = p }
                    // 병합 대기 시간 동안 재출발이 없으면 정차 시점 기준으로 확정
                    if (timestampNs - calmSinceNs >= config.stopMergeWindowNs) {
                        finishRide(calmSinceNs, calmPressure)
                    }
                } else {
                    val sameDirection = (slope < 0) == (direction == Direction.UP)
                    if (calmSinceNs >= 0 && !sameDirection) {
                        // 반대 방향 재출발 → 이전 탑승은 정차 시점에 끝난 것으로 확정
                        finishRide(calmSinceNs, calmPressure)
                    } else {
                        calmSinceNs = -1L // 같은 방향 재출발 → 같은 탑승으로 이어감
                        peakSlope = maxOf(peakSlope, abs(slope))
                    }
                }
                if (phase == Phase.RIDING && timestampNs - candidateStartNs > config.maxRideNs) {
                    listener.onRideCancelled("timeout")
                    setPhase(nextIdlePhase())
                }
            }
        }
    }

    private fun finishRide(endNs: Long, endPressure: Float) {
        val dh = altitudeDelta(startPressure, endPressure)
        if (abs(dh) >= config.minAltitudeChangeM) {
            val peakSpeed = peakSlope * config.metersPerHpa
            val conf = confidence(peakSpeed, abs(dh))
            listener.onDebug(
                "ride finished: dh=${"%.2f".format(dh)}m peakSpeed=${"%.2f".format(peakSpeed)}m/s " +
                        "impulse=$impulseConfirmed confidence=${"%.2f".format(conf)}"
            )
            listener.onRideFinished(
                ElevatorRide(
                    direction = if (dh > 0) Direction.UP else Direction.DOWN,
                    startNs = candidateStartNs,
                    endNs = endNs,
                    altitudeChangeM = dh,
                    estimatedFloors = (abs(dh) / config.floorHeightM).roundToIntSafe(),
                    peakVerticalSpeedMps = peakSpeed,
                    confidence = conf,
                )
            )
        } else {
            listener.onRideCancelled("altitude change too small: ${"%.2f".format(dh)} m")
        }
        calmSinceNs = -1L
        setPhase(nextIdlePhase())
    }

    /** 기본 점수를 낮추고(0.4), 출발 임펄스·속도 범위가 맞을 때만 높은 점수를 준다. */
    private fun confidence(peakSpeed: Float, absAltitudeM: Float): Float {
        var c = 0.4f
        if (impulseConfirmed) c += 0.35f
        if (peakSpeed in 0.8f..6f) c += 0.15f // 일반 엘리베이터 1~3 m/s, 고속 ~6 m/s
        if (absAltitudeM >= config.floorHeightM * 2) c += 0.10f
        return c.coerceAtMost(1f)
    }

    /**
     * 걸음 게이트.
     * - RIDING 중 몇 걸음(자리 이동)은 허용하지만, 걷기가 [DetectorConfig.walkEndNs] 이상 이어지면
     *   하차한 것으로 보고 즉시 종료한다.
     * - 기압 정차 판정(calm)이 아직 안 났더라도(저주기 기압계 등) 기울기가 출발 임계값 미만이면
     *   '움직이지 않는 중'으로 보고 걷기 종료를 인정한다.
     */
    private fun evaluateMotionGate(nowNs: Long) {
        val m = motion.state
        when (phase) {
            Phase.GATED_WALKING -> if (m == MotionClassifier.State.STATIONARY) setPhase(Phase.MONITORING)
            Phase.MONITORING, Phase.CANDIDATE -> if (m == MotionClassifier.State.WALKING) {
                setPhase(Phase.GATED_WALKING) // 계단 이동 등은 여기서 걸러짐
            }
            Phase.RIDING -> {
                val notMoving = calmSinceNs >= 0 ||
                        (pressure.isReady && abs(pressure.slopeHpaPerSec) < config.rideStartSlope)
                if (m == MotionClassifier.State.WALKING && notMoving) {
                    if (walkSinceNs < 0) walkSinceNs = nowNs
                    if (nowNs - walkSinceNs >= config.walkEndNs) {
                        if (calmSinceNs >= 0) finishRide(calmSinceNs, calmPressure)
                        else finishRide(walkSinceNs, pressure.filtered)
                    }
                } else {
                    walkSinceNs = -1L
                }
            }
        }
    }

    private fun nextIdlePhase() =
        if (motion.state == MotionClassifier.State.WALKING) Phase.GATED_WALKING else Phase.MONITORING

    private fun setPhase(p: Phase) {
        if (phase == p) return
        listener.onDebug("phase $phase -> $p")
        phase = p
        if (p == Phase.RIDING) walkSinceNs = -1L
        listener.onPhaseChanged(p)
    }

    companion object {
        /** 국제 표준 대기식 기반 상대 고도차(m). SensorManager.getAltitude 와 동일한 식. */
        fun altitudeDelta(p0: Float, p1: Float): Float {
            fun alt(p: Float) = 44330.0 * (1.0 - (p / 1013.25).toDouble().pow(1.0 / 5.255))
            return (alt(p1) - alt(p0)).toFloat()
        }

        private fun Float.roundToIntSafe(): Int = if (isNaN()) 0 else kotlin.math.round(this).toInt()
    }
}

/** 가속도 크기 분산 + 걸음 이벤트로 WALKING/STATIONARY 판별 */
internal class MotionClassifier(private val c: DetectorConfig) {
    enum class State { UNKNOWN, WALKING, STATIONARY }

    var state = State.UNKNOWN
        private set

    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Double>()
    private var sum = 0.0
    private var sumSq = 0.0
    private val steps = ArrayDeque<Long>()

    fun onAccel(t: Long, x: Float, y: Float, z: Float) {
        val m = sqrt((x * x + y * y + z * z).toDouble())
        times.addLast(t); values.addLast(m); sum += m; sumSq += m * m
        while (times.isNotEmpty() && t - times.first() > c.motionWindowNs) {
            times.removeFirst(); val v = values.removeFirst(); sum -= v; sumSq -= v * v
        }
        // 초기 구간에서도 오래된 걸음이 쌓이지 않도록 early return 이전에 정리
        while (steps.isNotEmpty() && t - steps.first() > c.stepWindowNs) steps.removeFirst()
        if (times.size < 10 || t - times.first() < c.motionWindowNs / 2) return
        val n = values.size
        val mean = sum / n
        val std = sqrt(maxOf(0.0, sumSq / n - mean * mean)).toFloat()

        state = when {
            std >= c.walkingAccelStd || steps.size >= c.stepsForWalking -> State.WALKING
            std <= c.stationaryAccelStd && steps.size < c.stepsForWalking -> State.STATIONARY
            else -> if (state == State.UNKNOWN) State.WALKING else state
        }
    }

    fun onStep(t: Long) {
        steps.addLast(t)
        while (steps.isNotEmpty() && t - steps.first() > c.stepWindowNs) steps.removeFirst()
        if (steps.size >= c.stepsForWalking) state = State.WALKING
    }
}

/**
 * 중력 벡터를 느린 LPF로 추정하고, 가속도를 중력 방향으로 투영해 수직 선형가속도를 구한다.
 * 양수 = 위쪽 가속. 폰 방향과 무관하게 동작.
 * |a|가 9.81에서 크게 벗어나는 구간(흔들림, 엘리베이터 가속)은 중력 추정을 동결해 오염을 막는다.
 */
internal class VerticalAccel(private val c: DetectorConfig) {
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var lastT = -1L
    private var smoothed = 0f
    private var lastUpImpulseNs = Long.MIN_VALUE
    private var lastDownImpulseNs = Long.MIN_VALUE

    fun onAccel(t: Long, x: Float, y: Float, z: Float) {
        if (lastT < 0) { gx = x; gy = y; gz = z; lastT = t; return }
        val dt = ((t - lastT) / 1e9f).coerceIn(0.001f, 0.2f)
        lastT = t

        val norm = sqrt(x * x + y * y + z * z)
        if (abs(norm - GRAVITY) <= c.gravityUpdateTolerance) {
            val a = dt / (c.gravityTauSec + dt)
            gx += a * (x - gx); gy += a * (y - gy); gz += a * (z - gz)
        }
        val gNorm = sqrt(gx * gx + gy * gy + gz * gz)
        if (gNorm < 1e-3f) return
        val v = (x * gx + y * gy + z * gz) / gNorm - gNorm
        val b = dt / (c.verticalSmoothTauSec + dt)
        smoothed += b * (v - smoothed)
        if (smoothed >= c.verticalImpulse) lastUpImpulseNs = t
        if (smoothed <= -c.verticalImpulse) lastDownImpulseNs = t
    }

    /** 상승 출발 = 위쪽 가속 임펄스, 하강 출발 = 아래쪽 가속 임펄스 */
    fun hadImpulse(sinceNs: Long, upward: Boolean): Boolean =
        (if (upward) lastUpImpulseNs else lastDownImpulseNs) >= sinceNs

    private companion object {
        const val GRAVITY = 9.80665f
    }
}

/**
 * 기압 EMA(시간 기반) + 슬라이딩 윈도우 최소제곱 기울기.
 * 기압계 실제 주기가 낮아도(1~5Hz) 창에 최소 [DetectorConfig.slopeMinSamples]개를 유지해 동작한다.
 */
internal class PressureTrend(private val c: DetectorConfig) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Float>()
    private var lastT = -1L

    var filtered = Float.NaN
        private set
    var slopeHpaPerSec = 0f
        private set
    val isReady: Boolean
        get() = times.size >= c.slopeMinSamples &&
                times.last() - times.first() >= c.slopeWindowNs * 3 / 4
    val windowStartNs: Long get() = times.first()
    val windowStartPressure: Float get() = values.first()

    fun onPressure(t: Long, hPa: Float) {
        filtered = if (filtered.isNaN() || lastT < 0) {
            hPa
        } else {
            val dt = ((t - lastT) / 1e9f).coerceIn(0.001f, 5f)
            val a = dt / (c.pressureSmoothTauSec + dt)
            filtered + a * (hPa - filtered)
        }
        lastT = t
        times.addLast(t); values.addLast(filtered)
        // 기본 창을 넘은 샘플은 제거하되, 최소 샘플 수는 유지 (단, 최대 창은 초과 불가)
        while (times.size > c.slopeMinSamples && t - times.first() > c.slopeWindowNs) {
            times.removeFirst(); values.removeFirst()
        }
        while (times.size > 1 && t - times.first() > c.slopeMaxWindowNs) {
            times.removeFirst(); values.removeFirst()
        }
        slopeHpaPerSec = leastSquaresSlope()
    }

    private fun leastSquaresSlope(): Float {
        val n = times.size
        if (n < 2) return 0f
        val t0 = times.first()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val x = (times[i] - t0) / 1e9
            val y = values[i].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val d = n * sxx - sx * sx
        return if (abs(d) < 1e-12) 0f else ((n * sxy - sx * sy) / d).toFloat()
    }
}

