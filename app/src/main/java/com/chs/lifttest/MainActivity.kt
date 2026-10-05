package com.chs.lifttest

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiftTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ElevatorScreen(
                        modifier = Modifier
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}


@Composable
private fun ElevatorScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val direction by ElevatorDetectionService.events.direction.collectAsStateWithLifecycle()
    val phase by ElevatorDetectionService.events.phase.collectAsStateWithLifecycle()
    val rides by ElevatorDetectionService.events.rides.collectAsStateWithLifecycle()
    val running by ElevatorDetectionService.running.collectAsStateWithLifecycle()
    var permissionDenied by remember { mutableStateOf(false) }
    var isIgnoring by remember { mutableStateOf(PermissionUtil.isBatteryOptimizationsIgnored(context)) }

    LaunchedEffect(phase) {
        Log.e("CHS_123", phase.toString())
    }
    LaunchedEffect(rides) {
        Log.e("CHS_123", rides.toString())
    }
    LaunchedEffect(running) {
        Log.e("CHS_123", running.toString())
    }

    val batteryIgnoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isIgnoring = PermissionUtil.isBatteryOptimizationsIgnored(context)
        permissionDenied = !isIgnoring
    }


    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (PermissionUtil.hasActivityRecognition(context)) {
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
         rides != null -> "엘리베이터 탑승 중"
        else -> "걸음 감지 대기 중"
    }
    val detail = when {
        direction != null ->
            if (direction == ElevatorDetector.Direction.UP) "▲ 상승" else "▼ 하강"
        phase == ElevatorDetector.Phase.MONITORING -> "걸음이 멈춰 기압/가속 센서로 확인하고 있어요"
        running -> "걸음 센서만 저전력으로 대기 중이에요"
        else -> "시작 버튼을 눌러 감지를 켜세요"
    }

    val cardColor by animateColorAsState(
        targetValue = when {
            rides != null -> MaterialTheme.colorScheme.primaryContainer
            phase == ElevatorDetector.Phase.MONITORING -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "cardColor"
    )

    Scaffold { padding ->
        Column(
            modifier = modifier
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
                        detail.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (running) {
                        ElevatorDetectionService.stop(context)
                    } else {
                        val needed = PermissionUtil.missingPermissions(context)
                        if (needed.isEmpty()) ElevatorDetectionService.start(context)
                        else permissionLauncher.launch(needed)
                    }
                }
            ) {
                Text(if (running) "감지 중지" else "감지 시작")
            }

            if (permissionDenied) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "활동 인식 권한이 필요합니다.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = {
                        PermissionUtil.openAppSettings(context)
                    }
                ) {
                    Text("설정에서 허용하기")
                }
            }
        }
    }
}