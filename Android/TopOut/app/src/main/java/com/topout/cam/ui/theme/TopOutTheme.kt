package com.topout.cam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color

object TopOutColors {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Blue = Color(0xFF0A84FF)
    val Red = Color(0xFFFF3B30)
    val Orange = Color(0xFFFF9F0A)
    val GrayButton = Color(0xFF3A3A3C)
    val TipBackground = Color(0x59000000)
    val BubbleBackground = Color(0x94000000)
    val SettingsBackground = Color(0xFFF2F2F7)
    val SecondaryText = Color(0xFF8E8E93)
}

private val scheme = darkColorScheme(
    primary = TopOutColors.Blue,
    onPrimary = TopOutColors.White,
    background = TopOutColors.Black,
    onBackground = TopOutColors.White,
    surface = Color(0xFF1C1C1E),
    onSurface = TopOutColors.White,
    error = TopOutColors.Red
)

@Composable
fun TopOutTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    // 기기 글자 확대 설정으로 고정 카메라 조작부가 잘리지 않도록 글자 배율만 제한한다.
    val stableDensity = Density(density.density, fontScale = density.fontScale.coerceAtMost(1f))
    CompositionLocalProvider(LocalDensity provides stableDensity) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
