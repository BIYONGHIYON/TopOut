package com.topout.cam.localization

import android.content.Context
import org.json.JSONObject
import java.util.Locale

object TopOutStrings {
    private const val CATALOG_FILE = "topout_strings.json"
    private var catalog: JSONObject? = null
    @Volatile
    private var activeLanguageCode = AppLanguage.ENGLISH.storageValue

    fun initialize(context: Context) {
        if (catalog == null) {
            catalog = JSONObject(
                context.assets.open(CATALOG_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            )
        }
        refreshLanguage()
    }

    fun refreshLanguage() {
        activeLanguageCode = AppLanguageManager.resolvedLanguage().storageValue
    }

    fun text(key: String, vararg arguments: Any?): String {
        return textForLanguageCode(activeLanguageCode, key, arguments)
    }

    fun text(language: AppLanguage, key: String, vararg arguments: Any?): String {
        val resolved = language.takeUnless { it == AppLanguage.AUTOMATIC }
            ?: AppLanguageManager.automaticLanguage()
        return textForLanguageCode(resolved.storageValue, key, arguments)
    }

    private fun textForLanguageCode(
        languageCode: String,
        key: String,
        arguments: Array<out Any?>
    ): String {
        val entry = catalog?.optJSONObject(key)
        val template = entry?.optString(languageCode)?.takeIf(String::isNotEmpty)
            ?: entry?.optString(AppLanguage.ENGLISH.storageValue)?.takeIf(String::isNotEmpty)
            ?: key
        return arguments.foldIndexed(template) { index, result, value ->
            result.replace("{$index}", value?.toString().orEmpty())
        }
    }

    fun decimal(value: Double, fractionDigits: Int = 1): String =
        String.format(currentLocale(), "%.${fractionDigits}f", value)

    fun currentLocale(): Locale = Locale.forLanguageTag(
        AppLanguageManager.resolvedLanguage().languageTag ?: "en"
    )
}

fun tr(key: String, vararg arguments: Any?): String = TopOutStrings.text(key, *arguments)
