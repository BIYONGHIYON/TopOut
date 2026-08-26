package com.topout.cam.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topout.cam.localization.AppLanguage
import com.topout.cam.localization.AppLanguageManager
import com.topout.cam.model.RecordingAspectRatio
import com.topout.cam.ui.theme.TopOutColors

@Composable
internal fun languageDisplayName(language: AppLanguage): String = when (language) {
    AppLanguage.AUTOMATIC -> settingsTr(
        "language.automatic_with_value",
        AppLanguageManager.automaticLanguage().nativeName
    )
    else -> language.nativeName
}

@Composable
internal fun aspectRatioSubtitle(aspectRatio: RecordingAspectRatio): String = when (aspectRatio) {
    RecordingAspectRatio.STORY_9_16 -> settingsTr("settings.story_reels")
    RecordingAspectRatio.FEED_4_5 -> settingsTr("settings.instagram_feed")
    RecordingAspectRatio.PORTRAIT_3_4 -> settingsTr("settings.portrait_post")
    RecordingAspectRatio.SQUARE_1_1 -> settingsTr("settings.square_post")
}

@Composable
internal fun LanguageSelectionScreen(
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
internal fun PrivacyPolicyScreen(onBack: () -> Unit) {
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
internal fun OpenSourceLicensesScreen(onBack: () -> Unit) {
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
internal fun SettingsNavigationRow(title: String, onClick: () -> Unit) {
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
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
internal fun SettingLabel(text: String) {
    Text(
        text,
        color = TopOutColors.SecondaryText,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
internal fun SettingSwitch(
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
