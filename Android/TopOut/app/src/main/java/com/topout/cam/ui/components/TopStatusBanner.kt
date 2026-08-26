package com.topout.cam.ui.components

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** iOS-style guidance text without a background panel. */
@Composable
fun TopStatusBanner(
    text: String,
    isLandscape: Boolean = false,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .offset(y = if (isLandscape) (-12).dp else 0.dp)
            .padding(
                top = if (isLandscape) 0.dp else 2.dp,
                start = if (isLandscape) 6.dp else 10.dp,
                end = if (isLandscape) 6.dp else 10.dp
            ),
        color = Color.White,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 8.5.sp,
            maxFontSize = 14.5.sp,
            stepSize = 0.25.sp
        ),
        fontWeight = FontWeight.SemiBold,
        style = TextStyle(
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.75f),
                offset = Offset(0f, 1f),
                blurRadius = 3f
            )
        ),
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
    )
}
