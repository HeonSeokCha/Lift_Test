package com.chs.lifttest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ElevatorDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private lateinit var rideRepository: RideRepository
    private var wakeLock: PowerManager.WakeLock? = null

    /** onCreate에서 정상 초기화가 끝났는지 */
    private var initialized = false

    /** 디버그 빌드에서만 상세 로그 출력 (logcat 태그: ElevatorDetector) */
    private val debugLog by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    companion object {
        private const val TAG = "ElevatorDetector"
        private const val CHANNEL_ID = "elevator_detection"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_TEXT = "감지 대기 중"
        private const val ACCEL_PERIOD_US = 20_000   // 50 Hz
        private const val PRESSURE_PERIOD_US = 40_000 // 25 Hz 요청, 기기에 따라 실제 1~25Hz
        val events = ElevatorEvents

        val running: StateFlow<Boolean> get() = ElevatorEvents.running

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ElevatorDetectionService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, ElevatorDetectionService::class.java))
    }

    private val detector = ElevatorDetector(DetectorConfig(), object : ElevatorDetector.Listener {
        override fun onPhaseChanged(phase: ElevatorDetector.Phase) {
            events._phase.value = phase
            // RIDING이 아니게 되는 순간 방향 표시는 항상 해제
            if (phase != ElevatorDetector.Phase.RIDING) events._direction.value = null
        }

        override fun onRideStarted(direction: ElevatorDetector.Direction, timestampNs: Long) {
            events._direction.value = direction
            updateNotification("엘리베이터 탑승 중 (${if (direction == ElevatorDetector.Direction.UP) "↑" else "↓"})")
        }

        override fun onRideFinished(ride: ElevatorDetector.ElevatorRide) {
            events._direction.value = null
            events._lastRide.value = ride
            rideRepository.append(ride)          // 먼저 저장 → 그 다음 이벤트 발행 (UI가 바로 load 해도 최신 반영)
            events._rideFinished.tryEmit(ride)
            updateNotification(
                "하차: ${ride.direction} ${"%.1f".format(kotlin.math.abs(ride.altitudeChangeM))}m (~${ride.estimatedFloors}층)"
            )
        }

        override fun onRideCancelled(reason: String) {
            events._direction.value = null
            updateNotification(DEFAULT_TEXT)
            if (debugLog) Log.d(TAG, "ride cancelled: $reason")
        }

        override fun onDebug(message: String) {
            if (debugLog) Log.d(TAG, message)
        }
    })

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // startForegroundService() 후 5초 내 startForeground 필수 → 무조건 먼저 호출
        createChannel()
        if (!startForegroundSafely()) {
            // 권한 철회/백그라운드 시작 제한 등. 크래시 대신 조용히 종료
            stopSelf()
            return
        }

        if (sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) == null) {
            // 기압계 없는 기기는 신뢰할 만한 감지 불가 → 종료 (UI에서도 사전 차단)
            stopSelf()
            return
        }

        rideRepository = RideRepository(this)
        sensorThread = HandlerThread("elevator-sensors").apply { start() }
        sensorHandler = Handler(sensorThread.looper)
        acquireWakeLock()
        registerSensors()
        initialized = true
        // onStartCommand 이전에 바로 true → 재시작 직후 UI가 '꺼짐'으로 잠깐 보이는 구간 제거
        events._running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) return START_NOT_STICKY
        return START_STICKY
    }

    /** Android 14+ health 타입 FGS는 권한이 없으면 SecurityException. 예외를 삼키고 실패를 알린다. */
    private fun startForegroundSafely(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(DEFAULT_TEXT),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0,
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        false
    }

    private fun registerSensors() {
        // 콜백은 모두 sensorHandler(단일 스레드)에서 → detector는 동기화 불필요
        register(Sensor.TYPE_ACCELEROMETER, ACCEL_PERIOD_US)
        register(Sensor.TYPE_PRESSURE, PRESSURE_PERIOD_US)
        val canUseSteps = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        if (canUseSteps) register(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun register(type: Int, periodUs: Int) {
        val sensor = sensorManager.getDefaultSensor(type) ?: run {
            Log.w(TAG, "sensor type $type not available")
            return
        }
        val ok = sensorManager.registerListener(this, sensor, periodUs, sensorHandler)
        if (!ok) Log.w(TAG, "registerListener failed for ${sensor.name}")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> detector.onAccelerometer(event.timestamp, v[0], v[1], v[2])
            Sensor.TYPE_PRESSURE -> detector.onPressure(event.timestamp, v[0])
            Sensor.TYPE_STEP_DETECTOR -> detector.onStep(event.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * 화면이 꺼지면 AP가 suspend 되어 non-wakeup 센서 이벤트가 전달되지 않는다.
     * FGS만으로는 CPU가 깨어있지 않으므로 PARTIAL_WAKE_LOCK이 필요하다. (배터리 비용 있음)
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "elevator:detector").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        if (::sensorThread.isInitialized) sensorThread.quitSafely()
        wakeLock?.takeIf { it.isHeld }?.release()
        events._running.value = false
        events.resetLiveState() // 종료 후 UI에 이전 방향/단계가 남지 않도록
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Notification ---
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "엘리베이터 감지", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("엘리베이터 감지")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
