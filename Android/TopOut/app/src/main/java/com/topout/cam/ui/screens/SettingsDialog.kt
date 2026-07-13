package com.topout.cam.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.topout.cam.BuildConfig
import com.topout.cam.model.AppSettings
import com.topout.cam.model.CameraCapabilities
import com.topout.cam.model.RecordingAspectRatio
import com.topout.cam.model.RecordingFrameRate
import com.topout.cam.model.RecordingResolution
import com.topout.cam.ui.components.SegmentControl
import com.topout.cam.ui.theme.TopOutColors

@Composable
fun SettingsDialog(
    initial: AppSettings,
    capabilities: CameraCapabilities,
    isLandscape: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    var draft by remember(initial) { mutableStateOf(initial.normalized()) }
    var showOpenSourceLicenses by remember { mutableStateOf(false) }

    BackHandler(enabled = showOpenSourceLicenses) {
        showOpenSourceLicenses = false
    }

    fun preferredFrameRate(resolution: RecordingResolution, current: RecordingFrameRate): RecordingFrameRate {
        val supported = capabilities.supportedFrameRates[resolution].orEmpty()
        return when {
            current in supported -> current
            RecordingFrameRate.FPS30 in supported -> RecordingFrameRate.FPS30
            RecordingFrameRate.FPS60 in supported -> RecordingFrameRate.FPS60
            else -> supported.firstOrNull() ?: RecordingFrameRate.FPS30
        }
    }

    fun setResolution(value: RecordingResolution) {
        draft = draft.copy(
            resolution = value,
            frameRate = preferredFrameRate(value, draft.frameRate)
        )
    }

    fun setAutoClip(value: Boolean) {
        draft = draft.copy(autoClipDetectionEnabled = value)
    }

    val output = draft.aspectRatio.outputSize(draft.resolution, isLandscape)

    Dialog(
        onDismissRequest = {
            if (showOpenSourceLicenses) showOpenSourceLicenses = false else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(TopOutColors.SettingsBackground)) {
            if (showOpenSourceLicenses) {
                OpenSourceLicensesScreen(onBack = { showOpenSourceLicenses = false })
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = TopOutColors.Blue
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                        ) { Text("닫기", fontSize = 16.sp) }
                        Text(
                            "설정",
                            modifier = Modifier.weight(1f),
                            color = Color.Black,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { onSave(draft.normalized()) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = TopOutColors.Blue
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                        ) { Text("저장", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        SettingsSection("영상 포맷") {
                            SettingLabel("해상도")
                            SegmentControl(
                                entries = RecordingResolution.entries.map { it.title },
                                selectedIndex = draft.resolution.ordinal,
                                onSelected = { setResolution(RecordingResolution.entries[it]) },
                                enabled = {
                                    capabilities.supportedFrameRates[RecordingResolution.entries[it]]
                                        .orEmpty()
                                        .isNotEmpty()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            SettingLabel("프레임")
                            SegmentControl(
                                entries = RecordingFrameRate.entries.map { it.title },
                                selectedIndex = draft.frameRate.ordinal,
                                onSelected = { draft = draft.copy(frameRate = RecordingFrameRate.entries[it]) },
                                enabled = {
                                    capabilities.supports(
                                        draft.resolution,
                                        RecordingFrameRate.entries[it]
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (
                                draft.resolution == RecordingResolution.UHD &&
                                draft.frameRate == RecordingFrameRate.FPS60
                            ) {
                                Text(
                                    "4K 60fps는 배터리와 저장공간을 빠르게 소모할 수 있어요.",
                                    modifier = Modifier.padding(top = 4.dp),
                                    color = TopOutColors.Orange,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        SettingsSection("화면 비율") {
                            SegmentControl(
                                entries = RecordingAspectRatio.entries.map { it.title(isLandscape) },
                                selectedIndex = draft.aspectRatio.ordinal,
                                onSelected = {
                                    draft = draft.copy(aspectRatio = RecordingAspectRatio.entries[it])
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${draft.aspectRatio.subtitle} · 저장 크기 ${output.width}×${output.height}",
                                modifier = Modifier.padding(top = 4.dp),
                                color = TopOutColors.SecondaryText,
                                fontSize = 12.sp
                            )
                        }

                        SettingsSection("자동 구간 탐지") {
                            SettingSwitch(
                                text = "비활성화 시 배터리가 절약되지만 수동으로 구간 선택",
                                checked = draft.autoClipDetectionEnabled,
                                onChecked = ::setAutoClip
                            )
                        }

                        SettingsSection("탑홀드 마스킹") {
                            SettingSwitch(
                                text = "결과 영상에 선택 영역을 마스크로 표시해 저장 가능",
                                checked = draft.topHoldMaskingEnabled,
                                onChecked = { draft = draft.copy(topHoldMaskingEnabled = it) }
                            )
                        }

                        SettingsSection("도움말") {
                            SettingSwitch(
                                text = "촬영 안내 문구와 Tip 표시",
                                checked = draft.helpEnabled,
                                onChecked = { draft = draft.copy(helpEnabled = it) }
                            )
                        }

                        SettingsSection("녹화중 실시간 영상 표시") {
                            SettingSwitch(
                                text = "비활성화 시 배터리 절약을 위해 녹화 중 검은 화면 출력",
                                checked = draft.livePreviewDuringRecordingEnabled,
                                onChecked = {
                                    draft = draft.copy(livePreviewDuringRecordingEnabled = it)
                                }
                            )
                        }

                        SettingsSection("앱 정보") {
                            SettingsNavigationRow(
                                title = "개인정보 처리방침",
                                onClick = {
                                    openExternalUrl(
                                        context = context,
                                        url = BuildConfig.PRIVACY_POLICY_URL
                                    )
                                }
                            )
                            HorizontalDivider(color = Color(0xFFE5E5EA))
                            SettingsNavigationRow(
                                title = "오픈소스 라이선스",
                                onClick = { showOpenSourceLicenses = true }
                            )
                            HorizontalDivider(color = Color(0xFFE5E5EA))
                            Text(
                                "버전 ${BuildConfig.VERSION_NAME}",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                color = TopOutColors.SecondaryText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "카메라와 마이크는 영상·음성 녹화에만 사용되며, 촬영 데이터는 외부 서버로 전송되지 않습니다.",
                                modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                                color = TopOutColors.SecondaryText,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notices = remember(context) {
        readAssetText(context, "licenses/THIRD_PARTY_NOTICES.txt")
    }
    val apacheLicense = remember(context) {
        readAssetText(context, "licenses/MobileSAM-LICENSE.txt")
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.White)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    tint = TopOutColors.Blue
                )
            }
            Text(
                "오픈소스 라이선스",
                modifier = Modifier.weight(1f),
                color = Color.Black,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(48.dp))
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "TopOut에 포함된 제3자 오픈소스 구성요소와 라이선스를 확인할 수 있습니다.",
                color = TopOutColors.SecondaryText,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            SettingsSection("MobileSAM") {
                Text(
                    notices,
                    color = TopOutColors.SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            SettingsSection("Apache License 2.0") {
                Text(
                    apacheLicense,
                    color = TopOutColors.SecondaryText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(
                context,
                "웹 브라우저를 열 수 없습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }
}

private fun readAssetText(context: Context, path: String): String = runCatching {
    context.assets.open(path).bufferedReader().use { it.readText() }
}.getOrElse {
    "라이선스 문서를 불러오지 못했습니다."
}

@Composable
private fun SettingsNavigationRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = Color.Black,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TopOutColors.SecondaryText
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp),
            color = Color.Black,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Column(
            modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(13.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text,
        color = TopOutColors.SecondaryText,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SettingSwitch(
    text: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            color = TopOutColors.SecondaryText.copy(alpha = if (enabled) 1f else 0.55f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 15.sp
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF34C759),
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.82f),
                disabledCheckedTrackColor = Color(0xFF84AA8D),
                disabledCheckedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFD1D1D6),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
