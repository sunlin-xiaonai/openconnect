package com.openconnect.android

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage(
    val preferenceValue: String,
    @StringRes val labelResId: Int,
    val languageTags: String,
) {
    System(
        preferenceValue = "system",
        labelResId = R.string.language_option_system,
        languageTags = "",
    ),
    SimplifiedChinese(
        preferenceValue = "zh-CN",
        labelResId = R.string.language_option_chinese,
        languageTags = "zh-CN",
    ),
    English(
        preferenceValue = "en",
        labelResId = R.string.language_option_english,
        languageTags = "en",
    ),
    ;

    companion object {
        fun fromPreference(value: String?): AppLanguage =
            entries.firstOrNull { it.preferenceValue == value } ?: System
    }
}

fun AppLanguage.label(context: Context): String = context.getString(labelResId)

object AppLanguageManager {
    private const val PREFS_NAME = "openconnect_app_settings"
    private const val KEY_APP_LANGUAGE = "app_language"

    fun load(context: Context): AppLanguage {
        val preferenceValue = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LANGUAGE, null)
        return AppLanguage.fromPreference(preferenceValue)
    }

    fun applyStored(context: Context) {
        apply(load(context))
    }

    fun update(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, language.preferenceValue)
            .apply()
        apply(language)
    }

    private fun apply(language: AppLanguage) {
        val locales = if (language.languageTags.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.languageTags)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
