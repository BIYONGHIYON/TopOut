package com.topout.cam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object TopOutColors {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Blue = Color(0xFF0A84FF)
    val Red = Color(0xFFFF3B30)
    val Orange = Color(0xFFFF9F0A)
    val GrayButton = Color(0xFF3A3A3C)
    val GrayDisabled = Color(0xFF55555A)
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

object TopOutTypography {
    val Hint = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val Tip = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val Button = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val SmallButton = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val Monospace = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun TopOutTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
