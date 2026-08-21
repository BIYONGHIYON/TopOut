import Foundation

enum AppLanguage: String, CaseIterable {
    case automatic = "auto"
    case english = "en"
    case korean = "ko"
    case japanese = "ja"
    case chineseSimplified = "zh-Hans"
    case chineseTraditional = "zh-Hant"
    case french = "fr"
    case german = "de"
    case spanish = "es"
    case portuguese = "pt"

    var languageTag: String? {
        self == .automatic ? nil : rawValue
    }

    var nativeName: String {
        switch self {
        case .automatic: return L("language.automatic")
        case .english: return "English"
        case .korean: return "한국어"
        case .japanese: return "日本語"
        case .chineseSimplified: return "简体中文"
        case .chineseTraditional: return "繁體中文"
        case .french: return "Français"
        case .german: return "Deutsch"
        case .spanish: return "Español"
        case .portuguese: return "Português"
        }
    }
}

enum AppLanguageManager {
    private static let defaultsKey = "topout.app.language"
    private static let supportedTags = AppLanguage.allCases.compactMap(\.languageTag)

    static var selected: AppLanguage {
        AppLanguage(rawValue: UserDefaults.standard.string(forKey: defaultsKey) ?? "auto") ?? .automatic
    }

    static var resolved: AppLanguage {
        resolved(selected)
    }

    static func resolved(_ language: AppLanguage) -> AppLanguage {
        language == .automatic ? automatic : language
    }

    static var automatic: AppLanguage {
        let preferences = Bundle.main.preferredLocalizations + Locale.preferredLanguages
        let preferred = Bundle.preferredLocalizations(
            from: supportedTags,
            forPreferences: preferences
        ).first ?? AppLanguage.english.rawValue
        return language(matching: preferred)
    }

    static var locale: Locale {
        Locale(identifier: resolved.rawValue)
    }

    static func select(_ language: AppLanguage) {
        UserDefaults.standard.set(language.rawValue, forKey: defaultsKey)
    }

    static func displayName(
        for language: AppLanguage,
        localizedUsing displayLanguage: AppLanguage? = nil
    ) -> String {
        language == .automatic
            ? L10n.text(
                "language.automatic_with_value",
                [automatic.nativeName],
                language: displayLanguage
            )
            : language.nativeName
    }

    private static func language(matching tag: String) -> AppLanguage {
        let normalized = tag.replacingOccurrences(of: "_", with: "-")
        if normalized.hasPrefix("zh-Hant") || normalized.hasPrefix("zh-TW") ||
            normalized.hasPrefix("zh-HK") || normalized.hasPrefix("zh-MO") {
            return .chineseTraditional
        }
        if normalized.hasPrefix("zh") { return .chineseSimplified }
        return AppLanguage.allCases.first(where: {
            guard let languageTag = $0.languageTag else { return false }
            return normalized.lowercased().hasPrefix(languageTag.lowercased())
        }) ?? .english
    }
}

enum L10n {
    private static let catalog: [String: [String: String]] = {
        guard let url = Bundle.main.url(forResource: "topout_strings", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([String: [String: String]].self, from: data) else {
            assertionFailure("Missing or invalid topout_strings.json")
            return [:]
        }
        return decoded
    }()

    static func text(
        _ key: String,
        _ arguments: [Any],
        language: AppLanguage? = nil
    ) -> String {
        let languageCode = language.map { AppLanguageManager.resolved($0) }?.rawValue
            ?? AppLanguageManager.resolved.rawValue
        let template = catalog[key]?[languageCode] ?? catalog[key]?[AppLanguage.english.rawValue] ?? key
        return arguments.enumerated().reduce(template) { result, item in
            result.replacingOccurrences(of: "{\(item.offset)}", with: String(describing: item.element))
        }
    }
}

func L(_ key: String, _ arguments: Any...) -> String {
    L10n.text(key, arguments)
}
