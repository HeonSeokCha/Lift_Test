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
    private var peakSlope = 0f
    private var impulseConfirmed = false

    fun onAccelerometer(timestampNs: Long, x: Float, y: Float, z: Float) {
        motion.onAccel(timestampNs, x, y, z)
        vertical.onAccel(timestampNs, x, y, z)
        evaluateMotionGate()
    }

    fun onStep(timestampNs: Long) {
        motion.onStep(timestampNs)
        evaluateMotionGate()
    }

    fun onPressure(timestampNs: Long, hPa: Float) {
        pressure.onPressure(timestampNs, hPa)
        if (!pressure.isReady) return
        val slope = pressure.slopeHpaPerSec
        val p = pressure.filtered

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
                setPhase(Phase.CANDIDATE)
            }

            Phase.CANDIDATE -> {
                val sameSign = (slope < 0) == (direction == Direction.UP)
                if (abs(slope) < config.rideEndSlope || !sameSign) {
                    // 문 여닫힘/환기 등 순간 기압 변동 → 지속되지 않으면 기각
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
                peakSlope = maxOf(peakSlope, abs(slope))
                if (abs(slope) < config.rideEndSlope) {
                    if (calmSinceNs < 0) calmSinceNs = timestampNs
                    if (timestampNs - calmSinceNs >= config.endHoldNs) finishRide(calmSinceNs, p)
                } else {
                    calmSinceNs = -1L
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
            listener.onRideFinished(
                ElevatorRide(
                    direction = if (dh > 0) Direction.UP else Direction.DOWN,
                    startNs = candidateStartNs,
                    endNs = endNs,
                    altitudeChangeM = dh,
                    estimatedFloors = (abs(dh) / config.floorHeightM).roundToIntSafe(),
                    peakVerticalSpeedMps = peakSpeed,
                    confidence = confidence(peakSpeed),
                )
            )
        } else {
            listener.onRideCancelled("altitude change too small: ${"%.2f".format(dh)} m")
        }
        setPhase(nextIdlePhase())
    }

    private fun confidence(peakSpeed: Float): Float {
        var c = 0.6f
        if (impulseConfirmed) c += 0.25f
        if (peakSpeed in 0.8f..6f) c += 0.15f // 일반 엘리베이터 1~3 m/s, 고속 ~6 m/s
        return c.coerceAtMost(1f)
    }

    /**
     * 걸음 게이트. RIDING 중에는 몇 걸음(자리 이동) 정도는 허용하고, 기압 추세로만 종료를 판단한다.
     * 하차 후 걷기 시작하면 기압 기울기가 0이 되므로 자연스럽게 종료된다.
     */
    private fun evaluateMotionGate() {
        val m = motion.state
        when (phase) {
            Phase.GATED_WALKING -> if (m == MotionClassifier.State.STATIONARY) setPhase(Phase.MONITORING)
            Phase.MONITORING, Phase.CANDIDATE -> if (m == MotionClassifier.State.WALKING) {
                setPhase(Phase.GATED_WALKING) // 계단 이동 등은 여기서 걸러짐
            }
            Phase.RIDING -> Unit
        }
    }

    private fun nextIdlePhase() =
        if (motion.state == MotionClassifier.State.WALKING) Phase.GATED_WALKING else Phase.MONITORING

    private fun setPhase(p: Phase) {
        if (phase == p) return
        phase = p
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
        if (times.size < 10 || t - times.first() < c.motionWindowNs / 2) return
        val n = values.size
        val mean = sum / n
        val std = sqrt(maxOf(0.0, sumSq / n - mean * mean)).toFloat()
        while (steps.isNotEmpty() && t - steps.first() > c.stepWindowNs) steps.removeFirst()

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
 * (TYPE_GRAVITY/TYPE_LINEAR_ACCELERATION 퓨전 센서가 있으면 그걸 써도 된다.)
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
        val a = dt / (c.gravityTauSec + dt)
        gx += a * (x - gx); gy += a * (y - gy); gz += a * (z - gz)
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
}

/** 기압 EMA + 슬라이딩 윈도우 최소제곱 기울기 */
internal class PressureTrend(private val c: DetectorConfig) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Float>()

    var filtered = Float.NaN
        private set
    var slopeHpaPerSec = 0f
        private set
    val isReady: Boolean
        get() = times.size >= 5 && times.last() - times.first() >= c.slopeWindowNs * 3 / 4
    val windowStartNs: Long get() = times.first()
    val windowStartPressure: Float get() = values.first()

    fun onPressure(t: Long, hPa: Float) {
        filtered = if (filtered.isNaN()) hPa else filtered + c.pressureEmaAlpha * (hPa - filtered)
        times.addLast(t); values.addLast(filtered)
        while (t - times.first() > c.slopeWindowNs) { times.removeFirst(); values.removeFirst() }
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
