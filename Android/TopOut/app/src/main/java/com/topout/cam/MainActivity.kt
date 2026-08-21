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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import com.topout.cam.ui.screens.TopOutApp
import com.topout.cam.ui.theme.TopOutTheme
import com.topout.cam.localization.tr
import kotlinx.coroutines.delay

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
            // Keep the gesture-navigation area transparent when content intentionally draws
            // edge-to-edge. Insets are applied by the individual Compose controls.
            window.isNavigationBarContrastEnforced = false
        }

        // Resolve the ViewModel before composing the one-second brand thumbnail so launch state is
        // ready. Heavy MobileSAM sessions are prepared only after CameraX is already streaming.
        val startupViewModel = viewModel

        setContent {
            TopOutTheme {
                var showBrandLaunch by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(BRAND_LAUNCH_DURATION_MILLIS)
                    showBrandLaunch = false
                }

                if (showBrandLaunch) {
                    TopOutBrandLaunchScreen()
                } else {
                    PermissionGate(
                        permissionsGranted = ::hasPermissions,
                        openSettings = ::openAppSettings
                    ) {
                        TopOutApp(startupViewModel)
                    }
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
        private const val BRAND_LAUNCH_DURATION_MILLIS = 1_000L

        fun requiredPermissions(): Array<String> = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}

@Composable
private fun TopOutBrandLaunchScreen() {
    val isDark = isSystemInDarkTheme()
    val background = if (isDark) Color(0xFF1D242C) else Color(0xFFF4F6F8)
    val image = if (isDark) R.drawable.launch_dark else R.drawable.launch_light

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(image),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
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
