package com.chs.lifttest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ElevatorDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "elevator_detection"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_TEXT = "감지 대기 중"
        private const val ACCEL_PERIOD_US = 20_000   // 50 Hz (200Hz 초과 시 HIGH_SAMPLING_RATE_SENSORS 필요)
        private const val PRESSURE_PERIOD_US = 40_000 // 25 Hz 요청, 기기에 따라 실제 1~25Hz
        val events = ElevatorEvents


        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ElevatorDetectionService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, ElevatorDetectionService::class.java))
    }

    private val detector = ElevatorDetector(DetectorConfig(), object : ElevatorDetector.Listener {
        override fun onPhaseChanged(phase: ElevatorDetector.Phase) {
            events._phase.value = phase
        }

        override fun onRideStarted(direction: ElevatorDetector.Direction, timestampNs: Long) {
            events._direction.value = direction
            updateNotification("엘리베이터 탑승 중 (${if (direction == ElevatorDetector.Direction.UP) "↑" else "↓"})")
        }

        override fun onRideFinished(ride: ElevatorDetector.ElevatorRide) {
            events._rides.tryEmit(ride)
            updateNotification(
                "하차: ${ride.direction} ${"%.1f".format(ride.altitudeChangeM)}m (~${ride.estimatedFloors}층)"
            )
        }

        override fun onRideCancelled(reason: String) {
            updateNotification(DEFAULT_TEXT)
        }
    })

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        if (sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) == null) {
            // 기압계 없는 기기는 신뢰할 만한 감지가 불가능 → 서비스 종료 (가속도만으로는 오탐이 큼)
            stopSelf()
            return
        }

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(DEFAULT_TEXT),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0,
        )

        sensorThread = HandlerThread("elevator-sensors").apply { start() }
        sensorHandler = Handler(sensorThread.looper)
        acquireWakeLock()
        registerSensors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _running.value = true
        return START_STICKY
    }

    private fun registerSensors() {
        // 콜백은 모두 sensorHandler(단일 스레드)에서 → detector는 동기화 불필요
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, ACCEL_PERIOD_US, sensorHandler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, PRESSURE_PERIOD_US, sensorHandler)
        }
        val canUseSteps = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        if (canUseSteps) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            }
        }
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
     * FGS만으로는 CPU가 깨어있지 않으므로 PARTIAL_WAKE_LOCK이 필요하다. (배터리 비용 있음 → README 참고)
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
        _running.value = false
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
