package com.topout.cam.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topout.cam.R
import com.topout.cam.localization.TopOutStrings
import com.topout.cam.localization.tr
import com.topout.cam.ui.components.TopOutCapsuleButton
import com.topout.cam.ui.theme.TopOutColors
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun PreviewControls(
    zoom: Float,
    showTip: Boolean,
    isLandscape: Boolean,
    readyTitle: String,
    enabled: Boolean,
    onReady: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(
            horizontal = if (isLandscape) 10.dp else 18.dp,
            vertical = if (isLandscape) 6.dp else 10.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showTip && !isLandscape) {
            CameraTipBanner(tr("camera.tip_rotation"))
            Spacer(Modifier.height(6.dp))
        }

        if (isLandscape) {
            Row(
                modifier = Modifier.widthIn(max = 330.dp).height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 58.dp, height = 40.dp)
                        .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        String.format(TopOutStrings.currentLocale(), "%.1fx", zoom),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                TopOutCapsuleButton(
                    text = readyTitle,
                    onClick = onReady,
                    primary = true,
                    enabled = enabled,
                    modifier = Modifier.size(width = 164.dp, height = 46.dp)
                )
                IconButton(
                    onClick = onSettings,
                    enabled = enabled,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(999.dp))
                ) {
                    Icon(Icons.Rounded.Settings, tr("common.settings"), tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().widthIn(max = 620.dp).height(56.dp)) {
                Text(
                    String.format(TopOutStrings.currentLocale(), "%.1fx", zoom),
                    modifier = Modifier.align(Alignment.CenterStart),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TopOutCapsuleButton(
                    text = readyTitle,
                    onClick = onReady,
                    primary = true,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.Center).size(width = 180.dp, height = 52.dp)
                )
                IconButton(
                    onClick = onSettings,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.CenterEnd).size(42.dp)
                ) { Icon(Icons.Rounded.Settings, tr("common.settings"), tint = Color.White) }
            }
        }
    }
}

@Composable
internal fun SelectionControls(
    canRecord: Boolean,
    enabled: Boolean,
    showTip: Boolean,
    tipText: String,
    isLandscape: Boolean,
    onRetake: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(
            horizontal = if (isLandscape) 14.dp else 18.dp,
            vertical = if (isLandscape) 7.dp else 10.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showTip) {
            CameraTipBanner(tipText)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth(if (isLandscape) 0.72f else 1f)
                .widthIn(max = 560.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TopOutCapsuleButton(
                tr("camera.retake"),
                onRetake,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            TopOutCapsuleButton(
                tr("camera.start_recording"),
                onRecord,
                primary = true,
                enabled = canRecord,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CameraTipBanner(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .background(TopOutColors.TipBackground, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 5.dp),
        color = Color.White,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 8.5.sp,
            maxFontSize = 12.sp,
            stepSize = 0.25.sp
        ),
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
    )
}

@Composable
internal fun RecordingControls(
    elapsedSeconds: Double,
    isLandscape: Boolean,
    onTogglePreview: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth(if (isLandscape) 0.72f else 1f)
            .widthIn(max = 560.dp)
            .navigationBarsPadding()
            .padding(horizontal = if (isLandscape) 12.dp else 20.dp, vertical = if (isLandscape) 8.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        TopOutCapsuleButton(
            tr("camera.hide_live_preview"),
            onTogglePreview,
            modifier = Modifier.weight(1f)
        )
        RecordingStopCapsuleButton(
            elapsed = formatElapsed(elapsedSeconds),
            onClick = onStop,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RecordingStopCapsuleButton(
    elapsed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TopOutColors.Red,
            contentColor = Color.White
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        IosInlineStopIcon(Modifier.size(24.dp))
        Spacer(Modifier.size(7.dp))
        Text(
            elapsed,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 14.sp,
                maxFontSize = 16.sp,
                stepSize = 0.5.sp
            ),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun IosInlineStopIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = min(size.width, size.height)
        drawCircle(color = Color.White, radius = side * 0.44f, center = center)
        val square = side * 0.36f
        drawRoundRect(
            color = TopOutColors.Red,
            topLeft = Offset(center.x - square / 2f, center.y - square / 2f),
            size = Size(square, square),
            cornerRadius = CornerRadius(square * 0.1f, square * 0.1f)
        )
    }
}

@Composable
internal fun RecordingCountdownOverlay(countdown: Int?, isLandscape: Boolean) {
    countdown ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            countdown.toString(),
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = if (isLandscape) 120.sp else 150.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun PowerSavingRecordingOverlay(
    elapsedSeconds: Double,
    recordingStarted: Boolean,
    onTogglePreview: () -> Unit,
    onStop: () -> Unit
) {
    // 화면 크기를 기준으로 iOS 절전 화면과 같은 비율을 유지한다.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val recordIconSide = if (landscape) {
            minOf(maxHeight * 0.44f, maxWidth * 0.22f)
        } else {
            minOf(maxWidth * 0.42f, maxHeight * 0.34f)
        }

        if (recordingStarted) {
            val toggleHeight = 42.dp
            val toggleWidth = minOf(maxWidth - 72.dp, if (landscape) 176.dp else 198.dp)
            val toggleGap = 22.dp
            val elapsedHeight = if (landscape) 64.dp else 86.dp
            val elapsedGap = if (landscape) 16.dp else 28.dp
            val toggleCenterOffset = -(recordIconSide / 2 + toggleGap + toggleHeight / 2)
            val elapsedCenterOffset = recordIconSide / 2 + elapsedGap + elapsedHeight / 2

            TopOutCapsuleButton(
                text = tr("camera.show_live_preview"),
                onClick = onTogglePreview,
                modifier = Modifier.align(Alignment.Center)
                    .offset(y = toggleCenterOffset)
                    .size(width = toggleWidth, height = toggleHeight)
            )
            IconButton(
                onClick = onStop,
                modifier = Modifier.align(Alignment.Center).size(recordIconSide)
            ) {
                IosPowerSavingStopIcon(Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .offset(y = elapsedCenterOffset)
                    .size(
                        width = minOf(maxWidth - 48.dp, if (landscape) 360.dp else maxWidth - 48.dp),
                        height = elapsedHeight
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    formatElapsed(elapsedSeconds),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 62.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun IosPowerSavingStopIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = min(size.width, size.height)
        val lineWidth = max(2.5.dp.toPx(), side * 0.065f)
        val circleInset = lineWidth * 0.5f + side * 0.04f
        val radius = (side - circleInset * 2f) * 0.5f

        drawCircle(color = Color.White, radius = radius, center = center, style = Stroke(width = lineWidth))

        val squareSide = side * 0.42f
        val squareTopLeft = Offset(
            center.x - squareSide * 0.5f,
            center.y - squareSide * 0.5f
        )
        drawRoundRect(
            color = TopOutColors.Red,
            topLeft = squareTopLeft,
            size = Size(squareSide, squareSide),
            cornerRadius = CornerRadius(squareSide * 0.12f, squareSide * 0.12f)
        )
    }
}

@Composable
internal fun LaunchOverlay() {
    val isDark = isSystemInDarkTheme()
    val background = if (isDark) Color(0xFF1D242C) else Color(0xFFF4F6F8)
    val launchImage = if (isDark) R.drawable.launch_dark else R.drawable.launch_light

    Box(
        modifier = Modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(launchImage),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

private fun formatElapsed(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
