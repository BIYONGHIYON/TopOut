package com.topout.cam.localization

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguageManager {
    private const val PREFERENCES_NAME = "topout_language"
    private const val LANGUAGE_KEY = "selected_language"

    fun selectedLanguage(context: Context): AppLanguage = AppLanguage.fromStorageValue(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, AppLanguage.AUTOMATIC.storageValue)
    )

    fun select(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_KEY, language.storageValue)
            .apply()
        applyLocales(language)
        TopOutStrings.refreshLanguage()
    }

    fun restoreManualSelection(context: Context) {
        selectedLanguage(context)
            .takeUnless { it == AppLanguage.AUTOMATIC }
            ?.let(::applyLocales)
        TopOutStrings.refreshLanguage()
    }

    fun resolvedLanguage(): AppLanguage {
        val locales = AppCompatDelegate.getApplicationLocales()
        val tag = if (!locales.isEmpty) {
            locales[0]?.toLanguageTag()
        } else {
            LocaleListCompat.getAdjustedDefault()[0]?.toLanguageTag()
        }
        return supportedLanguage(tag)
    }

    fun automaticLanguage(): AppLanguage {
        val systemLocale = Resources.getSystem().configuration.locales[0]
        return supportedLanguage(systemLocale?.toLanguageTag())
    }

    private fun supportedLanguage(tag: String?): AppLanguage {
        val normalized = tag.orEmpty().replace('_', '-')
        return when {
            normalized.startsWith("zh-Hant", ignoreCase = true) ||
                normalized.startsWith("zh-TW", ignoreCase = true) ||
                normalized.startsWith("zh-HK", ignoreCase = true) ||
                normalized.startsWith("zh-MO", ignoreCase = true) -> AppLanguage.CHINESE_TRADITIONAL
            normalized.startsWith("zh", ignoreCase = true) -> AppLanguage.CHINESE_SIMPLIFIED
            else -> AppLanguage.entries.firstOrNull { language ->
                language.languageTag?.let { normalized.startsWith(it, ignoreCase = true) } == true
            } ?: AppLanguage.ENGLISH
        }
    }

    private fun applyLocales(language: AppLanguage) {
        val locales = language.languageTag
            ?.let(LocaleListCompat::forLanguageTags)
            ?: LocaleListCompat.getEmptyLocaleList()
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
