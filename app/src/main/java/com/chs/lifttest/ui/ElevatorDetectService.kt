package com.chs.lifttest.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chs.lifttest.ElevatorDetector
import com.chs.lifttest.ElevatorEvent
import com.chs.lifttest.ElevatorState
import com.chs.lifttest.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.java

class ElevatorDetectService : Service() {

    companion object {
        const val ACTION_STOP = "com.chs.lifttest.STOP"
        private const val CHANNEL_ID = "elevator_detect"
        private const val NOTI_ID = 1

        private val _events = MutableStateFlow<ElevatorEvent?>(null)
        val events: StateFlow<ElevatorEvent?> = _events

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error
    }

    private var detector: ElevatorDetector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "엘리베이터 감지", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (detector != null) return START_STICKY

        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        try {
            ServiceCompat.startForeground(this, NOTI_ID, buildNotification("엘리베이터 감지 대기 중"), type)
        } catch (e: SecurityException) {
            // ACTIVITY_RECOGNITION 권한이 없을 때 (Android 14+)
            _error.value = "활동 인식 권한이 필요합니다."
            stopSelf()
            return START_NOT_STICKY
        }

        val d = ElevatorDetector(this) { event ->
            _events.value = event
            val text = when {
                event.state == ElevatorState.RIDING && event.direction > 0 -> "엘리베이터 탑승 중 (상승)"
                event.state == ElevatorState.RIDING -> "엘리베이터 탑승 중 (하강)"
                else -> "엘리베이터 감지 대기 중"
            }
            getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(text))
        }

        if (!d.isSupported) {
            _error.value = "이 기기는 기압계 또는 필요한 센서가 없어 지원되지 않습니다."
            stopSelf()
            return START_NOT_STICKY
        }

        _error.value = null
        detector = d
        d.start()
        _running.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        detector?.stop()
        detector = null
        _running.value = false
        super.onDestroy()
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ElevatorDetectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Elevator Detector")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, "중지", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
