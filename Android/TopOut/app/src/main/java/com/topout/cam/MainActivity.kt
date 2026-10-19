package com.topout.cam

import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.topout.cam.ui.components.TopOutCapsuleButton
import com.topout.cam.ui.screens.LaunchOverlay
import com.topout.cam.ui.screens.TopOutApp
import com.topout.cam.ui.theme.TopOutTheme
import com.topout.cam.localization.tr

class MainActivity : AppCompatActivity() {
    private val viewModel: TopOutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 전체 화면 콘텐츠를 위해 제스처 영역을 투명하게 유지한다.
            window.isNavigationBarContrastEnforced = false
        }

        // 시작 상태를 먼저 준비하고 MobileSAM은 카메라 스트리밍 뒤에 불러온다.
        val startupViewModel = viewModel

        setContent {
            TopOutTheme {
                // iOS와 같이 고정 시간 없이 권한 요청과 카메라 준비가 끝날 때까지 시작 화면을 유지한다.
                PermissionGate(
                    permissionsGranted = ::hasPermissions,
                    openSettings = ::openAppSettings
                ) {
                    TopOutApp(startupViewModel)
                }
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val storageGranted = android.os.Build.VERSION.SDK_INT > 28 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && storageGranted
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    companion object {
        fun requiredPermissions(): Array<String> = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}

@Composable
private fun PermissionGate(
    permissionsGranted: () -> Boolean,
    openSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    var granted by remember { mutableStateOf(permissionsGranted()) }
    var requested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        granted = permissionsGranted()
        requested = true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = permissionsGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(MainActivity.requiredPermissions())
    }

    if (granted) {
        content()
    } else if (!requested) {
        // 권한 응답 전에는 시작 화면 위에 시스템 권한 요청을 표시한다.
        LaunchOverlay()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                if (android.os.Build.VERSION.SDK_INT <= 28) {
                    tr("permission.camera_storage_title")
                } else {
                    tr("permission.camera_title")
                },
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (android.os.Build.VERSION.SDK_INT <= 28) {
                    tr("permission.camera_storage_message")
                } else {
                    tr("permission.camera_message")
                },
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )
            TopOutCapsuleButton(
                text = if (requested) tr("permission.open_settings") else tr("permission.request"),
                onClick = {
                    if (requested) openSettings() else launcher.launch(MainActivity.requiredPermissions())
                },
                primary = true
            )
        }
    }
}
