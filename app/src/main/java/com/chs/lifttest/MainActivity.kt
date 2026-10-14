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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiftTestTheme {
                ElevatorScreen()
            }
        }
    }
}

@Composable
private fun ElevatorScreen() {
    val context = LocalContext.current
    val events = ElevatorDetectionService.events
    val direction by events.direction.collectAsStateWithLifecycle()
    val phase by events.phase.collectAsStateWithLifecycle()
    val lastRide by events.lastRide.collectAsStateWithLifecycle()
    val running by events.running.collectAsStateWithLifecycle()

    var permissionDenied by remember { mutableStateOf(false) }
    var noBarometer by remember { mutableStateOf(false) }
    var isIgnoring by remember { mutableStateOf(PermissionUtil.isBatteryOptimizationsIgnored(context)) }

    // '탑승 중'은 오직 phase == RIDING 으로만 판단한다. (lastRide는 과거 기록일 뿐)
    val riding = running && phase == ElevatorDetector.Phase.RIDING

    val batteryIgnoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isIgnoring = PermissionUtil.isBatteryOptimizationsIgnored(context)
        if (isIgnoring) {
            ElevatorDetectionService.start(context)
            permissionDenied = false
        } else {
            permissionDenied = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (PermissionUtil.hasActivityRecognition(context)) {
            isIgnoring = PermissionUtil.isBatteryOptimizationsIgnored(context)
            if (isIgnoring) {
                ElevatorDetectionService.start(context)
                permissionDenied = false
            } else {
                batteryIgnoreLauncher.launch(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        "package:${context.packageName}".toUri()
                    )
                )
            }
        } else {
            permissionDenied = true
        }
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
                val dir = if (ride.direction == ElevatorDetector.Direction.UP) "▲" else "▼"
                Text(
                    "마지막 탑승: $dir ${"%.1f".format(abs(ride.altitudeChangeM))}m " +
                            "(~${ride.estimatedFloors}층, ${"%.0f".format(ride.durationSec)}초)",
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
                    val needed = PermissionUtil.missingPermissions(context)
                    when {
                        needed.isNotEmpty() -> permissionLauncher.launch(needed)
                        !isIgnoring -> batteryIgnoreLauncher.launch(
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

            if (permissionDenied) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "활동 인식 권한과 배터리 최적화 제외가 필요합니다.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { PermissionUtil.openAppSettings(context) }) {
                    Text("설정에서 허용하기")
                }
            }
        }
    }
}
