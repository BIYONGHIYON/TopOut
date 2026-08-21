package com.topout.cam.localization

enum class AppLanguage(
    val storageValue: String,
    val languageTag: String?,
    val nativeName: String
) {
    AUTOMATIC("auto", null, "Automatic"),
    ENGLISH("en", "en", "English"),
    KOREAN("ko", "ko", "한국어"),
    JAPANESE("ja", "ja", "日本語"),
    CHINESE_SIMPLIFIED("zh-Hans", "zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "zh-Hant", "繁體中文"),
    FRENCH("fr", "fr", "Français"),
    GERMAN("de", "de", "Deutsch"),
    SPANISH("es", "es", "Español"),
    PORTUGUESE("pt", "pt", "Português");

    companion object {
        fun fromStorageValue(value: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == value } ?: AUTOMATIC
    }
}
