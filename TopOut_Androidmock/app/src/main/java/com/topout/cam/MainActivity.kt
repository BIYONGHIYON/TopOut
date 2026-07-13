package com.topout.cam

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.topout.cam.ui.screens.TopOutApp
import com.topout.cam.ui.theme.TopOutTheme
import kotlinx.coroutines.delay

/** Test-only launcher: camera and microphone permissions are intentionally not requested. */
class MainActivity : ComponentActivity() {
    private val viewModel: TopOutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        setContent {
            TopOutTheme {
                var showBrandLaunch by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(BRAND_LAUNCH_DURATION_MILLIS)
                    showBrandLaunch = false
                }
                if (showBrandLaunch) TopOutBrandLaunchScreen() else TopOutApp(viewModel)
            }
        }
    }

    private companion object {
        const val BRAND_LAUNCH_DURATION_MILLIS = 1_000L
    }
}

@Composable
private fun TopOutBrandLaunchScreen() {
    val isDark = isSystemInDarkTheme()
    val background = if (isDark) Color(0xFF1D242C) else Color(0xFFF4F6F8)
    val image = if (isDark) R.drawable.launch_dark else R.drawable.launch_light

    Box(
        modifier = Modifier.fillMaxSize().background(background),
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
