package com.capsopasme.assistant

import android.content.Context
import android.content.SharedPreferences
import com.capsopasme.assistant.asr.SpeechModel

/** All settings, in one private SharedPreferences file */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var deepseekKey: String
        get() = sp.getString("deepseek_key", "") ?: ""
        set(v) = sp.edit().putString("deepseek_key", v.trim()).apply()

    var deepseekModel: String
        get() = sp.getString("deepseek_model", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_DEEPSEEK_MODEL
        set(v) = sp.edit().putString("deepseek_model", v.trim()).apply()

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) = sp.edit().putString("gemini_key", v.trim()).apply()

    var geminiModel: String
        get() = sp.getString("gemini_model", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_GEMINI_MODEL
        set(v) = sp.edit().putString("gemini_model", v.trim()).apply()

    /** city used by the weather tool when none is given */
    var defaultCity: String
        get() = sp.getString("default_city", "") ?: ""
        set(v) = sp.edit().putString("default_city", v.trim()).apply()

    var speechModel: SpeechModel
        get() = SpeechModel.fromName(sp.getString("speech_model", null))
        set(v) = sp.edit().putString("speech_model", v.name).apply()

    /** silence that ends a command */
    var silenceMs: Int
        get() = sp.getInt("silence_ms", 700)
        set(v) = sp.edit().putInt("silence_ms", v).apply()

    /** keep the speech model in the (frozen, cached) recognizer process after use */
    var keepModelLoaded: Boolean
        get() = sp.getBoolean("keep_model_loaded", true)
        set(v) = sp.edit().putBoolean("keep_model_loaded", v).apply()

    var mirrorPrefix: String
        get() = sp.getString("mirror_prefix", "") ?: ""
        set(v) = sp.edit().putString("mirror_prefix", v.trim()).apply()

    companion object {
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
    }
}
