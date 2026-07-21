package com.topout.cam.ui.screens

import android.content.Context
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
    var draft by remember(initial) { mutableStateOf(initial.normalized()) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showOpenSourceLicenses by remember { mutableStateOf(false) }

    BackHandler(enabled = showPrivacyPolicy || showOpenSourceLicenses) {
        when {
            showPrivacyPolicy -> showPrivacyPolicy = false
            showOpenSourceLicenses -> showOpenSourceLicenses = false
        }
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
            when {
                showPrivacyPolicy -> showPrivacyPolicy = false
                showOpenSourceLicenses -> showOpenSourceLicenses = false
                else -> onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(TopOutColors.SettingsBackground)) {
            if (showPrivacyPolicy) {
                PrivacyPolicyScreen(onBack = { showPrivacyPolicy = false })
            } else if (showOpenSourceLicenses) {
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
                                onClick = { showPrivacyPolicy = true }
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
private fun PrivacyPolicyScreen(onBack: () -> Unit) {
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
                "개인정보 처리방침",
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
                "시행일: 2026년 7월 19일",
                color = TopOutColors.SecondaryText,
                fontSize = 12.sp
            )

            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(13.dp))
                    .padding(14.dp)
            ) {
                Text(
                    "TopOut은 별도의 회원가입 기능을 제공하지 않으며, 사용자의 이름, 이메일 주소, 위치정보, 기기 식별자 등의 개인정보를 수집하거나 외부 서버로 전송하지 않습니다.",
                    color = Color.Black,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "카메라와 마이크는 영상 및 음성 녹화에 사용되며, 신체 포즈 분석과 촬영 데이터 처리는 사용자의 기기 내에서 이루어집니다.",
                    color = TopOutColors.SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }

            PrivacyPolicySection("1. 수집하는 개인정보") {
                PrivacyParagraph(
                    "TopOut은 이름, 이메일 주소, 전화번호, 주소, 위치정보, 연락처, 기기 식별자, 광고 식별자 또는 앱 이용 기록 등 사용자를 직접 식별하기 위한 개인정보를 수집하지 않습니다."
                )
                PrivacyParagraph(
                    "또한 회원가입, 로그인, 사용자 계정 또는 클라우드 저장 기능을 제공하지 않습니다."
                )
            }

            PrivacyPolicySection("2. 카메라 및 마이크 사용") {
                PrivacyParagraph(
                    "카메라 권한은 영상 촬영과 자동 촬영 구간 감지에 사용되며, 마이크 권한은 촬영 영상에 포함되는 음성과 주변 소리를 녹음하기 위해 사용됩니다."
                )
                PrivacyParagraph(
                    "카메라와 마이크는 사용자가 권한을 허용하고 촬영 기능을 사용하는 동안에만 접근합니다. 촬영된 영상과 음성은 사용자의 기기에서 처리되며 개발자 또는 외부 서버로 전송되지 않습니다."
                )
            }

            PrivacyPolicySection("3. 신체 포즈 및 움직임 분석") {
                PrivacyParagraph(
                    "TopOut은 클라이밍 시작과 종료 시점을 감지하고 자동 촬영 기능을 제공하기 위해 카메라 영상에서 사용자의 신체 포즈와 매트 주변 움직임을 분석할 수 있습니다."
                )
                PrivacyParagraph(
                    "신체 포즈 분석은 MoveNet SinglePose Thunder FP16과 TensorFlow Lite를 이용해 기기 내에서 수행됩니다. 분석 과정에서 생성되는 관절 좌표와 중간 분석 결과는 외부 서버로 전송되지 않으며 사용자를 식별하는 프로필로 저장되지 않습니다."
                )
            }

            PrivacyPolicySection("4. 영상 저장 및 사진 앱 접근") {
                PrivacyParagraph(
                    "TopOut은 앱에서 새로 촬영한 영상을 사용자의 기기 사진 앱 또는 미디어 저장소에 저장합니다."
                )
                PrivacyParagraph(
                    "TopOut은 사용자의 기존 사진이나 영상을 임의로 열람, 수집, 분석 또는 외부로 전송하지 않습니다. 운영체제 버전에 따라 영상 저장을 위한 권한 요청 방식이 달라질 수 있습니다."
                )
            }

            PrivacyPolicySection("5. 제3자 제공 및 외부 전송") {
                PrivacyParagraph(
                    "TopOut은 사용자의 개인정보나 촬영 데이터를 제3자에게 판매, 제공 또는 공유하지 않습니다. 사용자 데이터를 광고 사업자, 데이터 분석 서비스 또는 외부 서버에 전송하지 않으며 국외로 이전하지 않습니다."
                )
            }

            PrivacyPolicySection("6. 광고 및 분석 도구") {
                PrivacyParagraph(
                    "TopOut은 현재 맞춤형 광고, 광고 식별자 기반 추적 또는 사용자 행동 분석을 위한 외부 광고·분석 서비스를 사용하지 않습니다."
                )
                PrivacyParagraph(
                    "향후 광고 또는 분석 서비스가 추가되어 개인정보 처리 방식이 변경되는 경우, 본 개인정보 처리방침을 수정하고 필요한 내용을 안내합니다."
                )
            }

            PrivacyPolicySection("7. 데이터 보관 및 삭제") {
                PrivacyParagraph(
                    "촬영된 영상과 음성은 사용자의 기기에만 저장되며 TopOut은 별도의 서버에 촬영 콘텐츠를 보관하지 않습니다. 사용자는 기기의 사진 앱 또는 파일 관리 앱에서 촬영 콘텐츠를 직접 확인하고 삭제할 수 있습니다."
                )
                PrivacyParagraph(
                    "앱을 삭제하면 앱 내부 설정과 임시 데이터는 운영체제의 앱 삭제 절차에 따라 제거됩니다. 기기의 사진 앱이나 미디어 저장소에 저장된 영상은 앱 삭제 후에도 남을 수 있으므로 사용자가 직접 삭제해야 합니다."
                )
            }

            PrivacyPolicySection("8. 개인정보의 안전한 처리") {
                PrivacyParagraph(
                    "TopOut은 운영체제가 제공하는 권한 관리 기능과 앱 샌드박스 환경을 사용합니다. 카메라와 마이크는 사용자가 권한을 허용한 경우에만 이용할 수 있습니다."
                )
            }

            PrivacyPolicySection("9. 권한 변경 및 철회") {
                PrivacyParagraph(
                    "사용자는 기기의 설정 → 애플리케이션 → TopOut → 권한에서 카메라와 마이크 권한을 언제든지 변경하거나 철회할 수 있습니다."
                )
                PrivacyParagraph(
                    "카메라 또는 마이크 권한을 철회하면 영상 촬영이나 음성 녹음 등 일부 기능이 정상적으로 작동하지 않을 수 있습니다."
                )
            }

            PrivacyPolicySection("10. 아동의 개인정보") {
                PrivacyParagraph(
                    "TopOut은 아동을 포함한 사용자로부터 개인정보를 의도적으로 수집하지 않습니다. 개인정보가 의도하지 않게 수집되었다고 판단되는 경우 아래 문의 이메일로 연락해 주세요."
                )
            }

            PrivacyPolicySection("11. 개인정보 처리방침의 변경") {
                PrivacyParagraph(
                    "앱 기능, 적용 기술 또는 관련 법령과 정책의 변경에 따라 본 개인정보 처리방침이 수정될 수 있습니다. 내용이 변경되는 경우 시행일을 갱신하고 앱에서 변경된 내용을 안내합니다."
                )
            }

            PrivacyPolicySection("12. 문의") {
                PrivacyParagraph("이메일: byeonghyeon383@gmail.com")
            }

            Text(
                "© 2026 TopOut. All rights reserved.",
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                color = TopOutColors.SecondaryText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PrivacyPolicySection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSection(title = title, content = content)
}

@Composable
private fun PrivacyParagraph(text: String) {
    Text(
        text,
        color = TopOutColors.SecondaryText,
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
}

@Composable
private fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notices = remember(context) {
        readAssetText(context, "licenses/THIRD_PARTY_NOTICES.txt")
    }
    val apacheLicense = remember(context) {
        readAssetText(context, "licenses/Apache-2.0.txt")
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

            SettingsSection("제3자 구성요소") {
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
