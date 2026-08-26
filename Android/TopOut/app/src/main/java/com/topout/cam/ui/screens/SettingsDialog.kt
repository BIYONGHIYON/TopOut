package com.topout.cam.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.topout.cam.BuildConfig
import com.topout.cam.localization.AppLanguage
import com.topout.cam.localization.TopOutStrings
import com.topout.cam.model.AppSettings
import com.topout.cam.model.CameraCapabilities
import com.topout.cam.model.RecordingAspectRatio
import com.topout.cam.model.RecordingFrameRate
import com.topout.cam.model.RecordingResolution
import com.topout.cam.ui.components.SegmentControl
import com.topout.cam.ui.theme.TopOutColors

private val LocalSettingsLanguage = compositionLocalOf { AppLanguage.AUTOMATIC }

@Composable
internal fun settingsTr(key: String, vararg arguments: Any?): String =
    TopOutStrings.text(LocalSettingsLanguage.current, key, *arguments)

@Composable
fun SettingsDialog(
    initial: AppSettings,
    initialLanguage: AppLanguage,
    capabilities: CameraCapabilities,
    isLandscape: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppSettings, AppLanguage) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial.normalized()) }
    var draftLanguage by remember(initialLanguage) { mutableStateOf(initialLanguage) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showOpenSourceLicenses by remember { mutableStateOf(false) }
    var showLanguageSelection by remember { mutableStateOf(false) }

    BackHandler(enabled = showPrivacyPolicy || showOpenSourceLicenses || showLanguageSelection) {
        when {
            showPrivacyPolicy -> showPrivacyPolicy = false
            showOpenSourceLicenses -> showOpenSourceLicenses = false
            showLanguageSelection -> showLanguageSelection = false
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

    CompositionLocalProvider(LocalSettingsLanguage provides draftLanguage) {
        Dialog(
            onDismissRequest = {
                when {
                    showPrivacyPolicy -> showPrivacyPolicy = false
                    showOpenSourceLicenses -> showOpenSourceLicenses = false
                    showLanguageSelection -> showLanguageSelection = false
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
            } else if (showLanguageSelection) {
                LanguageSelectionScreen(
                    selected = draftLanguage,
                    onSelected = { draftLanguage = it },
                    onBack = { showLanguageSelection = false }
                )
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
                        ) { Text(settingsTr("common.close"), fontSize = 16.sp) }
                        Text(
                            settingsTr("common.settings"),
                            modifier = Modifier.weight(1f),
                            color = Color.Black,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { onSave(draft.normalized(), draftLanguage) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = TopOutColors.Blue
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                        ) { Text(settingsTr("common.save"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        SettingsSection(settingsTr("settings.video_format")) {
                            SettingLabel(settingsTr("settings.resolution"))
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
                            SettingLabel(settingsTr("settings.frame_rate"))
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
                                    settingsTr("settings.battery_warning"),
                                    modifier = Modifier.padding(top = 4.dp),
                                    color = TopOutColors.Orange,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        SettingsSection(settingsTr("settings.aspect_ratio")) {
                            SegmentControl(
                                entries = RecordingAspectRatio.entries.map { it.title(isLandscape) },
                                selectedIndex = draft.aspectRatio.ordinal,
                                onSelected = {
                                    draft = draft.copy(aspectRatio = RecordingAspectRatio.entries[it])
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                settingsTr(
                                    "settings.output_size",
                                    aspectRatioSubtitle(draft.aspectRatio),
                                    output.width,
                                    output.height
                                ),
                                modifier = Modifier.padding(top = 4.dp),
                                color = TopOutColors.SecondaryText,
                                fontSize = 12.sp
                            )
                        }

                        SettingsSection(settingsTr("settings.auto_clip")) {
                            SettingSwitch(
                                text = settingsTr("settings.auto_clip_description"),
                                checked = draft.autoClipDetectionEnabled,
                                onChecked = ::setAutoClip
                            )
                        }

                        SettingsSection(settingsTr("settings.top_hold_masking")) {
                            SettingSwitch(
                                text = settingsTr("settings.top_hold_masking_description"),
                                checked = draft.topHoldMaskingEnabled,
                                onChecked = { draft = draft.copy(topHoldMaskingEnabled = it) }
                            )
                        }

                        SettingsSection(settingsTr("settings.help")) {
                            SettingSwitch(
                                text = settingsTr("settings.help_description"),
                                checked = draft.helpEnabled,
                                onChecked = { draft = draft.copy(helpEnabled = it) }
                            )
                        }

                        SettingsSection(settingsTr("settings.live_preview")) {
                            SettingSwitch(
                                text = settingsTr("settings.live_preview_description"),
                                checked = draft.livePreviewDuringRecordingEnabled,
                                onChecked = {
                                    draft = draft.copy(livePreviewDuringRecordingEnabled = it)
                                }
                            )
                        }

                        SettingsSection(settingsTr("language.title")) {
                            SettingsNavigationRow(
                                title = languageDisplayName(draftLanguage),
                                onClick = { showLanguageSelection = true }
                            )
                        }

                        SettingsSection(settingsTr("settings.app_info")) {
                            SettingsNavigationRow(
                                title = settingsTr("privacy.title"),
                                onClick = { showPrivacyPolicy = true }
                            )
                            HorizontalDivider(color = Color(0xFFE5E5EA))
                            SettingsNavigationRow(
                                title = settingsTr("licenses.title"),
                                onClick = { showOpenSourceLicenses = true }
                            )
                            HorizontalDivider(color = Color(0xFFE5E5EA))
                            Text(
                                settingsTr("settings.version", BuildConfig.VERSION_NAME),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                color = TopOutColors.SecondaryText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                settingsTr("privacy.short_notice"),
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
}
