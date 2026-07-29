package com.alarmcontrol.ui.settings

import androidx.annotation.StringRes
import androidx.core.os.LocaleListCompat
import com.alarmcontrol.R
import java.util.Locale

/** Languages with complete AlarmControl string resources. */
enum class AppLanguage(
    val languageTag: String,
    @StringRes val labelRes: Int,
) {
    SYSTEM("", R.string.settings_language_system),
    ENGLISH("en", R.string.settings_language_english),
    KOREAN("ko", R.string.settings_language_korean),
    ;

    internal fun toLocaleList(): LocaleListCompat =
        if (this == SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }

    companion object {
        internal fun fromLanguageTags(languageTags: String): AppLanguage {
            val primaryLanguage =
                languageTags
                    .substringBefore(',')
                    .substringBefore('-')
                    .lowercase(Locale.ROOT)
            return entries.firstOrNull { it.languageTag == primaryLanguage } ?: SYSTEM
        }
    }
}
