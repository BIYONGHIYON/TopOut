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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import com.topout.cam.localization.AppLanguage
import com.topout.cam.localization.AppLanguageManager
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
private fun settingsTr(key: String, vararg arguments: Any?): String =
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

@Composable
private fun languageDisplayName(language: AppLanguage): String = when (language) {
    AppLanguage.AUTOMATIC -> settingsTr(
        "language.automatic_with_value",
        AppLanguageManager.automaticLanguage().nativeName
    )
    else -> language.nativeName
}

@Composable
private fun aspectRatioSubtitle(aspectRatio: RecordingAspectRatio): String = when (aspectRatio) {
    RecordingAspectRatio.STORY_9_16 -> settingsTr("settings.story_reels")
    RecordingAspectRatio.FEED_4_5 -> settingsTr("settings.instagram_feed")
    RecordingAspectRatio.PORTRAIT_3_4 -> settingsTr("settings.portrait_post")
    RecordingAspectRatio.SQUARE_1_1 -> settingsTr("settings.square_post")
}

@Composable
private fun LanguageSelectionScreen(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.White)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = settingsTr("common.back"),
                    tint = TopOutColors.Blue
                )
            }
            Text(
                settingsTr("language.title"),
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppLanguage.entries.forEach { language ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(13.dp))
                        .clickable {
                            onSelected(language)
                            onBack()
                        }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        languageDisplayName(language),
                        modifier = Modifier.weight(1f),
                        color = Color.Black,
                        fontSize = 15.sp,
                        fontWeight = if (language == selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (language == selected) {
                        Text("✓", color = TopOutColors.Blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                    contentDescription = settingsTr("common.back"),
                    tint = TopOutColors.Blue
                )
            }
            Text(
                settingsTr("privacy.title"),
                modifier = Modifier.weight(1f),
                color = Color.Black,
                fontSize = 16.sp,
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
                settingsTr("privacy.effective_date"),
                color = TopOutColors.SecondaryText,
                fontSize = 11.sp
            )

            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(13.dp))
                    .padding(14.dp)
            ) {
                Text(
                    settingsTr("privacy.summary_1"),
                    color = Color.Black,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    settingsTr("privacy.summary_2"),
                    color = TopOutColors.SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_1")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_1_p1")
                )
                PrivacyParagraph(
                    settingsTr("privacy.section_1_p2")
                )
                PrivacyParagraph(
                    settingsTr("privacy.section_1_p3")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_2")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_2_p1")
                )
                PrivacyParagraph(
                    settingsTr("privacy.section_2_p2")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_3")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_3_p1")
                )
                PrivacyParagraph(
                    settingsTr("privacy.section_3_p2")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_4")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_4_p1")
                )
                PrivacyParagraph(
                    settingsTr("privacy.section_4_p2")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_5")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_5_p1")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_6")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_6_p1")
                )
                PrivacyParagraph(
                    settingsTr("privacy.section_6_p2")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_7")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_7_p1")
                )
                PrivacyParagraph(
                    settingsTr("privacy.section_7_p2")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_8")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_8_p1")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_9")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_9_p1")
                )
                PrivacyParagraph(
                    settingsTr("privacy.section_9_p2")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_10")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_10_p1")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_11")) {
                PrivacyParagraph(
                    settingsTr("privacy.section_11_p1")
                )
            }

            PrivacyPolicySection(settingsTr("privacy.section_12")) {
                PrivacyParagraph(settingsTr("privacy.contact_email"))
            }

            Text(
                settingsTr("common.copyright"),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                color = TopOutColors.SecondaryText,
                fontSize = 10.sp,
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
        fontSize = 11.sp,
        lineHeight = 17.sp
    )
}

@Composable
private fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val loadFailedText = settingsTr("licenses.load_failed")
    val notices = remember(context, loadFailedText) {
        readAssetText(context, "licenses/THIRD_PARTY_NOTICES.txt", loadFailedText)
    }
    val apacheLicense = remember(context, loadFailedText) {
        readAssetText(context, "licenses/Apache-2.0.txt", loadFailedText)
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
                    contentDescription = settingsTr("common.back"),
                    tint = TopOutColors.Blue
                )
            }
            Text(
                settingsTr("licenses.title"),
                modifier = Modifier.weight(1f),
                color = Color.Black,
                fontSize = 16.sp,
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
                settingsTr("licenses.description"),
                color = TopOutColors.SecondaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            SettingsSection(settingsTr("licenses.third_party")) {
                Text(
                    notices,
                    color = TopOutColors.SecondaryText,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            SettingsSection("Apache License 2.0") {
                Text(
                    apacheLicense,
                    color = TopOutColors.SecondaryText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun readAssetText(context: Context, path: String, fallback: String): String = runCatching {
    context.assets.open(path).bufferedReader().use { it.readText() }
}.getOrElse {
    fallback
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
            lineHeight = 16.sp
        )
        Switch(
            modifier = Modifier.width(52.dp),
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
