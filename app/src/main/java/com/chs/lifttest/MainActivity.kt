package com.chs.lifttest

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chs.lifttest.ui.theme.LiftTestTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiftTestTheme {
                ElevatorScreen(lifecycle)
            }
        }
    }
}

@Composable
private fun ElevatorScreen(lifecycle: Lifecycle) {
    val context = LocalContext.current
    val events = ElevatorDetectionService.events
    val direction by events.direction.collectAsStateWithLifecycle()
    val phase by events.phase.collectAsStateWithLifecycle()
    val lastRide by events.lastRide.collectAsStateWithLifecycle()
    val running by events.running.collectAsStateWithLifecycle()

    var noBarometer by remember { mutableStateOf(false) }
    /** 시작이 막힌 이유. null이면 정상 */
    var blockedReason by remember { mutableStateOf<String?>(null) }

    // 권한/배터리 상태: 설정 앱에서 바꾸고 돌아올 때(ON_RESUME) 다시 읽는다.
    var isIgnoring by remember { mutableStateOf(PermissionUtil.isBatteryOptimizationsIgnored(context)) }
    var recognitionOn by remember { mutableStateOf(PermissionUtil.hasActivityRecognition(context)) }
    var notificationOn by remember { mutableStateOf(PermissionUtil.hasNotificationPermission(context)) }

    fun refreshPermissionState() {
        isIgnoring = PermissionUtil.isBatteryOptimizationsIgnored(context)
        recognitionOn = PermissionUtil.hasActivityRecognition(context)
        notificationOn = PermissionUtil.hasNotificationPermission(context)
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissionState()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // 탑승 기록 (파일 저장본). 서비스는 '저장 → 이벤트 발행' 순서이므로 이벤트마다 다시 읽는다.
    val repo = remember { RideRepository(context) }
    var history by remember { mutableStateOf<List<RideRepository.RideRecord>>(emptyList()) }
    LaunchedEffect(Unit) {
        history = withContext(Dispatchers.IO) { repo.load() }
        events.rideFinished.collect {
            history = withContext(Dispatchers.IO) { repo.load() }
        }
    }

    // '탑승 중'은 오직 phase == RIDING 으로만 판단한다. (lastRide는 과거 기록일 뿐)
    val riding = running && phase == ElevatorDetector.Phase.RIDING

    val batteryIgnoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPermissionState()
        if (isIgnoring) {
            blockedReason = null
            ElevatorDetectionService.start(context)
        } else {
            blockedReason = "배터리 최적화 제외가 필요합니다. 화면이 꺼져도 감지하려면 허용해 주세요."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionState()
        when {
            // 활동 인식은 필수 (걸음 감지 + Android 14 FGS health 타입 요건)
            !PermissionUtil.hasActivityRecognition(context) ->
                blockedReason = "활동 인식 권한이 필요합니다. 걸음 감지와 백그라운드 실행에 사용돼요."
            PermissionUtil.isBatteryOptimizationsIgnored(context) -> {
                blockedReason = null
                ElevatorDetectionService.start(context)
            }
            else -> batteryIgnoreLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:${context.packageName}".toUri()
                )
            )
        }
        // 알림 권한은 거부돼도 시작은 허용 — 대신 아래 경고 문구로 안내한다.
    }

    val title = when {
        !running -> "감지가 꺼져 있습니다"
        riding -> "엘리베이터 탑승 중"
        phase == ElevatorDetector.Phase.MONITORING -> "움직임 확인 중"
        phase == ElevatorDetector.Phase.CANDIDATE -> "탑승 여부 확인 중"
        else -> "걸음 감지 대기 중"
    }
    val detail = when {
        !running -> "시작 버튼을 눌러 감지를 켜세요"
        riding -> when (direction) {
            ElevatorDetector.Direction.UP -> "▲ 상승"
            ElevatorDetector.Direction.DOWN -> "▼ 하강"
            null -> "이동 방향 확인 중"
        }
        phase == ElevatorDetector.Phase.MONITORING -> "걸음이 멈춰 기압/가속 센서로 확인하고 있어요"
        phase == ElevatorDetector.Phase.CANDIDATE -> "기압 변화를 분석하고 있어요"
        else -> "걷는 중에는 감지를 쉬고 있어요"
    }

    val cardColor by animateColorAsState(
        targetValue = when {
            riding -> MaterialTheme.colorScheme.primaryContainer
            running && phase != ElevatorDetector.Phase.GATED_WALKING -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "cardColor"
    )

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🛗", style = MaterialTheme.typography.displayLarge)
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }

            lastRide?.let { ride ->
                Spacer(Modifier.height(16.dp))
                Text(
                    "마지막 탑승: " + formatRide(ride),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (running) {
                        ElevatorDetectionService.stop(context)
                        return@Button
                    }
                    if (!PermissionUtil.hasBarometer(context)) {
                        noBarometer = true
                        return@Button
                    }
                    noBarometer = false
                    blockedReason = null
                    refreshPermissionState()
                    val needed = PermissionUtil.missingPermissions(context)
                    when {
                        needed.isNotEmpty() -> permissionLauncher.launch(needed)
                        !PermissionUtil.isBatteryOptimizationsIgnored(context) -> batteryIgnoreLauncher.launch(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                "package:${context.packageName}".toUri()
                            )
                        )
                        else -> ElevatorDetectionService.start(context)
                    }
                }
            ) {
                Text(if (running) "감지 중지" else "감지 시작")
            }

            if (noBarometer) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "이 기기에는 기압 센서가 없어 엘리베이터 감지를 사용할 수 없어요.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            blockedReason?.let { reason ->
                Spacer(Modifier.height(12.dp))
                Text(
                    reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = { PermissionUtil.openAppSettings(context) }) {
                    Text("설정에서 허용하기")
                }
            }

            // 알림 권한이 꺼져 있으면 FGS 알림이 안 보여 실행 여부를 알기 어렵다 → 경고만 표시
            if (!notificationOn) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "알림 권한이 꺼져 있어 감지 중 알림이 표시되지 않아요. 감지는 정상 동작하지만 실행 상태를 확인하기 어렵습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = { PermissionUtil.openAppSettings(context) }) {
                    Text("알림 허용하기")
                }
            }

            if (history.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("최근 탑승 기록", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val fmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
                history.take(10).forEach { rec ->
                    Text(
                        "${fmt.format(Date(rec.wallTimeMs))}  ${formatRide(rec.ride)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                TextButton(onClick = {
                    repo.clear()
                    history = emptyList()
                }) {
                    Text("기록 삭제")
                }
            }
        }
    }
}

private fun formatRide(ride: ElevatorDetector.ElevatorRide): String {
    val dir = if (ride.direction == ElevatorDetector.Direction.UP) "▲" else "▼"
    return "$dir ${"%.1f".format(abs(ride.altitudeChangeM))}m " +
            "(~${ride.estimatedFloors}층, ${"%.0f".format(ride.durationSec)}초, " +
            "신뢰도 ${"%.0f".format(ride.confidence * 100)}%)"
}
