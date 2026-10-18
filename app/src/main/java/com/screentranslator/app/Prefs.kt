package com.screentranslator.app

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

/** 원문 언어와 온라인 번역 설정. 원문 언어 값은 [AUTO] 또는 ML Kit 언어 코드(en, ja, zh)다. */
object Prefs {
    const val AUTO = "auto"

    /** 번역할 수 있는 원문 언어와 화면에 보일 이름. */
    val SOURCE_LANGUAGES = linkedMapOf(
        TranslateLanguage.ENGLISH to "영어",
        TranslateLanguage.JAPANESE to "일본어",
        TranslateLanguage.CHINESE to "중국어",
    )

    private const val FILE = "settings"
    private const val KEY_SOURCE = "source_language"
    private const val KEY_GEMINI = "gemini_api_key"
    private const val KEY_GEMINI_MODEL = "gemini_model"
    private const val KEY_CLOUD = "cloud_translation_api_key"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
    private const val KEY_CLOUD_MONTH = "cloud_usage_month"
    private const val KEY_CLOUD_CHARS = "cloud_usage_chars"

    /** Cloud Translation 무료 사용량은 매달 50만 자다. 앱에서 센 글자 수가 조금 어긋날 수 있어 여유를 둔다. */
    const val CLOUD_FREE_CHARS = 490_000L

    data class Settings(
        val sourceLanguage: String,
        val geminiKey: String,
        val geminiModel: String,
        val cloudKey: String,
    )

    fun settings(context: Context): Settings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Settings(
            sourceLanguage = prefs.getString(KEY_SOURCE, AUTO) ?: AUTO,
            geminiKey = prefs.getString(KEY_GEMINI, "").orEmpty(),
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_GEMINI_MODEL,
            cloudKey = prefs.getString(KEY_CLOUD, "").orEmpty(),
        )
    }

    fun setOnline(context: Context, geminiKey: String, geminiModel: String, cloudKey: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GEMINI, geminiKey.trim())
            .putString(KEY_GEMINI_MODEL, geminiModel.trim())
            .putString(KEY_CLOUD, cloudKey.trim())
            .apply()
    }

    fun sourceLanguage(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE, AUTO) ?: AUTO

    fun setSourceLanguage(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE, value)
            .apply()
    }

    /** 구글 결제는 미국 태평양 시간 기준 달마다 끊는다. */
    private fun billingMonth(): String {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
        return "%04d-%02d".format(calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH) + 1)
    }

    /** 이번 달 Cloud Translation으로 보낸 글자 수. */
    fun cloudCharsThisMonth(context: Context): Long {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_CLOUD_MONTH, null) != billingMonth()) return 0
        return prefs.getLong(KEY_CLOUD_CHARS, 0)
    }

    @Synchronized
    fun addCloudChars(context: Context, chars: Long) {
        val used = cloudCharsThisMonth(context)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLOUD_MONTH, billingMonth())
            .putLong(KEY_CLOUD_CHARS, used + chars)
            .apply()
    }
}
