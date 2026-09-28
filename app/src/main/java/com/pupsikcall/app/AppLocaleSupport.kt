package com.pupsikcall.app

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

internal fun selectedLanguageIndex(
    supportedTags: List<String>,
    applicationLocaleTags: String,
    systemLocaleTag: String,
): Int {
    val candidates = (applicationLocaleTags.split(',') + systemLocaleTag)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { Locale.forLanguageTag(it).language }
    return candidates.firstNotNullOfOrNull { language ->
        supportedTags.indexOfFirst { it.equals(language, ignoreCase = true) }.takeIf { it >= 0 }
    } ?: supportedTags.indexOf("en").takeIf { it >= 0 } ?: 0
}

internal fun applyApplicationLanguage(languageTag: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
}