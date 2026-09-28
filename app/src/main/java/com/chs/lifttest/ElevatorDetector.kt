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
import kotlin.math.sqrt

class ElevatorDetector(
    context: Context,
    private val onEvent: (ElevatorEvent) -> Unit
) : SensorEventListener {

    // ---- 튜닝 상수 ----
    private val enterSpeed = 0.7f          // m/s 이상이면 후보 (에스컬레이터 ~0.5 배제)
    private val exitSpeed = 0.15f          // m/s 이하로 떨어지면 정지
    private val enterHoldMs = 1500L        // 후보 속도가 이 시간 이상 유지
    private val exitHoldMs = 1000L
    private val accelPeak = 0.25f          // m/s², 수직 가속 피크 최소값
    private val accelWindowMs = 5000L      // 가속 피크가 이 시간 내에 있어야 함
    private val stepQuietMs = 1500L        // 마지막 걸음 이후 이 시간 이상 정적이어야 탑승 판정
    private val speedWindowMs = 2000L      // 수직 속도 회귀 창
    private val minSpeedSpanMs = 1000L     // 속도 계산에 필요한 최소 데이터 길이

    private val sensingDelayMs = 1000L     // 걸음이 멈춘 뒤 센서를 켜기까지의 대기
    private val sensingWindowMs = 60_000L  // 센서를 켜 두는 최대 시간 (탑승 없으면 절전)

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())

    private val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val linAcc = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    // wake-up 버전: 화면이 꺼지고 CPU가 잠들어도 걸음 이벤트를 전달한다.
    private val step: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val isSupported: Boolean get() = baro != null && linAcc != null && gravity != null

    /** 걸음 센서가 없으면 게이팅이 불가능하므로 센서를 상시 켠다. */
    private val gated: Boolean get() = step != null

    private val sensingLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "elevator:sensing")
        .apply { setReferenceCounted(false) }
    private val stepLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "elevator:step")
        .apply { setReferenceCounted(false) }

    private val g = FloatArray(3)
    private var lastAccelPeakMs = 0L
    private var lastStepMs = 0L

    private val altTimes = ArrayDeque<Long>()
    private val altValues = ArrayDeque<Float>()
    private var altEma = Float.NaN

    private var state = ElevatorState.IDLE
    private var sensing = false
    private var candidateSinceMs = 0L
    private var stillSinceMs = 0L

    private val startSensingRunnable = Runnable {
        if (state == ElevatorState.IDLE && !sensing) startSensing()
    }
    private val sensingTimeoutRunnable = Runnable {
        if (state == ElevatorState.IDLE && sensing) stopSensing()
    }

    // ---------------- 수명주기 ----------------

    fun start() {
        step?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        startSensing() // 시작 시점에 정지 상태일 수 있으므로 감지 구간을 먼저 연다
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        sm.unregisterListener(this)
        sensing = false
        state = ElevatorState.IDLE
        resetBuffers()
        if (sensingLock.isHeld) sensingLock.release()
        if (stepLock.isHeld) stepLock.release()
    }

    // ---------------- 센서 게이팅 ----------------

    private fun startSensing() {
        if (sensing) return
        sensing = true
        resetBuffers()
        val us = 100_000 // 10Hz
        gravity?.let { sm.registerListener(this, it, us) }
        linAcc?.let { sm.registerListener(this, it, us / 2) }
        baro?.let { sm.registerListener(this, it, us) }
        armSensingWindow()
    }

    private fun stopSensing() {
        if (!sensing) return
        sensing = false
        handler.removeCallbacks(sensingTimeoutRunnable)
        gravity?.let { sm.unregisterListener(this, it) }
        linAcc?.let { sm.unregisterListener(this, it) }
        baro?.let { sm.unregisterListener(this, it) }
        if (sensingLock.isHeld) sensingLock.release()
        resetBuffers()
    }

    /** 감지 구간 타이머를 (재)시작한다. 걸음 센서가 없으면 타이머 없이 상시 유지. */
    private fun armSensingWindow() {
        handler.removeCallbacks(sensingTimeoutRunnable)
        if (gated) {
            sensingLock.acquire(sensingWindowMs + 30_000L) // 안전 타임아웃
            handler.postDelayed(sensingTimeoutRunnable, sensingWindowMs)
        } else {
            sensingLock.acquire()
        }
    }

    private fun resetBuffers() {
        altTimes.clear(); altValues.clear()
        altEma = Float.NaN
        candidateSinceMs = 0L
        stillSinceMs = 0L
        lastAccelPeakMs = 0L
        g.fill(0f) // 오래된 중력 벡터로 계산하지 않도록 초기화
    }

    // ---------------- 센서 콜백 ----------------

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR) { onStep(now); return }
        if (!sensing) return // 끈 직후 큐에 남은 이벤트 무시

        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> System.arraycopy(e.values, 0, g, 0, 3)

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val gn = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                if (gn < 1f) return
                // 중력 반대 방향(위쪽) 성분
                val up = -(e.values[0] * g[0] + e.values[1] * g[1] + e.values[2] * g[2]) / gn
                if (abs(up) > accelPeak) lastAccelPeakMs = now
            }

            Sensor.TYPE_PRESSURE -> handlePressure(e.values[0], now)
        }
    }

    private fun onStep(now: Long) {
        lastStepMs = now
        handler.removeCallbacks(startSensingRunnable)

        // 걷기 시작 → 탑승 중이 아니면 즉시 센서 OFF (탑승 중이면 종료 판정 후 OFF)
        if (state == ElevatorState.IDLE && sensing) stopSensing()

        // 걸음이 멈추면 sensingDelayMs 뒤에 센서 ON. Handler가 CPU 절전 중 지연되지 않도록 짧게 WakeLock.
        stepLock.acquire(sensingDelayMs + 500L)
        handler.postDelayed(startSensingRunnable, sensingDelayMs)
    }

    private fun handlePressure(hPa: Float, now: Long) {
        val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa)
        altEma = if (altEma.isNaN()) alt else altEma + 0.25f * (alt - altEma)

        altTimes.addLast(now); altValues.addLast(altEma)
        while (altTimes.isNotEmpty() && now - altTimes.first() > speedWindowMs) {
            altTimes.removeFirst(); altValues.removeFirst()
        }
        // 센서를 막 켠 직후에는 데이터가 짧아 속도가 부정확하므로 대기
        if (altTimes.size < 5 || now - altTimes.first() < minSpeedSpanMs) return

        val vs = slope()
        val walking = now - lastStepMs < stepQuietMs
        val recentAccel = now - lastAccelPeakMs < accelWindowMs

        when (state) {
            ElevatorState.IDLE -> {
                val candidate = abs(vs) >= enterSpeed && !walking && recentAccel
                if (!candidate) { candidateSinceMs = 0L; return }
                if (candidateSinceMs == 0L) candidateSinceMs = now
                if (now - candidateSinceMs >= enterHoldMs) {
                    state = ElevatorState.RIDING
                    stillSinceMs = 0L
                    handler.removeCallbacks(sensingTimeoutRunnable) // 탑승 중에는 절전 타임아웃 중지
                    if (gated) sensingLock.acquire(5 * 60_000L)
                    onEvent(ElevatorEvent(state, if (vs > 0) 1 else -1, vs, now))
                }
            }

            ElevatorState.RIDING -> {
                val stopped = abs(vs) <= exitSpeed || walking
                if (!stopped) { stillSinceMs = 0L; return }
                if (stillSinceMs == 0L) stillSinceMs = now
                if (now - stillSinceMs >= exitHoldMs) {
                    state = ElevatorState.IDLE
                    candidateSinceMs = 0L
                    onEvent(ElevatorEvent(state, 0, vs, now))

                    if (walking) {
                        // 내려서 걷는 중 → 센서 OFF, 걸음이 멈추면 다시 ON
                        stopSensing()
                        handler.removeCallbacks(startSensingRunnable)
                        handler.postDelayed(startSensingRunnable, sensingDelayMs)
                    } else {
                        armSensingWindow() // 연속 탑승 대비 감지 구간 재시작
                    }
                }
            }
        }
    }

    /** 최근 창에서 고도-시간 최소제곱 기울기 = 수직 속도(m/s) */
    private fun slope(): Float {
        val n = altTimes.size
        val t0 = altTimes.first()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val x = (altTimes[i] - t0) / 1000.0
            val y = altValues[i].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val d = n * sxx - sx * sx
        return if (d == 0.0) 0f else ((n * sxy - sx * sy) / d).toFloat()
    }
}

