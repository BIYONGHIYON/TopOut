package com.topout.cam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topout.cam.ui.theme.TopOutColors

@Composable
fun TopOutCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false
) {
    val background = when {
        destructive -> Color(0xFFE8423A)
        primary -> TopOutColors.Blue
        else -> TopOutColors.GrayButton
    }
    Button(
        onClick = onClick,
        modifier = modifier.height(if (compact) 44.dp else 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = Color.White,
            disabledContainerColor = background.copy(alpha = 0.38f),
            disabledContentColor = Color.White.copy(alpha = 0.55f)
        ),
        contentPadding = if (compact) {
            androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 2.dp)
        } else {
            androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        }
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) 14.dp else 18.dp))
            Spacer(Modifier.width(if (compact) 2.dp else 4.dp))
        }
        Text(
            text = text,
            autoSize = TextAutoSize.StepBased(
                minFontSize = if (compact) 11.5.sp else 12.sp,
                maxFontSize = if (compact) 14.sp else 16.sp,
                stepSize = 0.5.sp
            ),
            fontWeight = FontWeight.SemiBold,
            lineHeight = if (compact) 15.sp else 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
fun SegmentControl(
    entries: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    enabled: (Int) -> Boolean = { true },
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xFFE9E9EB), RoundedCornerShape(9.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        entries.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val isEnabled = enabled(index)
            Button(
                onClick = { onSelected(index) },
                enabled = isEnabled,
                modifier = Modifier.weight(1f).height(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Color.White else Color.Transparent,
                    contentColor = Color.Black,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = Color.Gray.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(7.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = if (selected) 1.dp else 0.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    text = label,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 10.5.sp,
                        maxFontSize = 13.sp,
                        stepSize = 0.5.sp
                    ),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}
