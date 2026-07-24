package com.topout.cam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
    MaterialTheme(colorScheme = scheme, content = content)
}
