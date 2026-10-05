package com.capsopasme.assistant

import android.content.Context
import android.content.SharedPreferences
import com.capsopasme.assistant.asr.SpeechModel
import com.capsopasme.assistant.llm.LlmClient

/** All settings, in one private SharedPreferences file */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** the one model used for every request; no automatic switching to another one */
    var provider: LlmClient.Kind
        get() = LlmClient.Kind.fromName(sp.getString("llm_provider", null))
        set(v) = sp.edit().putString("llm_provider", v.name).apply()

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

    var glmKey: String
        get() = sp.getString("glm_key", "") ?: ""
        set(v) = sp.edit().putString("glm_key", v.trim()).apply()

    var glmModel: String
        get() = sp.getString("glm_model", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_GLM_MODEL
        set(v) = sp.edit().putString("glm_model", v.trim()).apply()

    /** the selected provider with its key and model, null if its key is empty */
    fun currentProvider(): LlmClient.Provider? {
        val kind = provider
        val (key, model) = when (kind) {
            LlmClient.Kind.DeepSeek -> deepseekKey to deepseekModel
            LlmClient.Kind.Glm -> glmKey to glmModel
            LlmClient.Kind.Gemini -> geminiKey to geminiModel
        }
        return if (key.isEmpty()) null else LlmClient.Provider(kind, key, model)
    }

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

    /** the media stream is muted by an open assistant sheet ([com.capsopasme.assistant.ui.MediaSilencer]) */
    var musicMutedByAssistant: Boolean
        get() = sp.getBoolean("music_muted_by_assistant", false)
        set(v) {
            sp.edit().putBoolean("music_muted_by_assistant", v).apply()
        }

    var mirrorPrefix: String
        get() = sp.getString("mirror_prefix", "") ?: ""
        set(v) = sp.edit().putString("mirror_prefix", v.trim()).apply()

    // ---------------------------------------------------------------------------------------------
    // voice call

    /** silence that ends what the user says in a call (people pause longer when talking) */
    var callSilenceMs: Int
        get() = sp.getInt("call_silence_ms", 800)
        set(v) = sp.edit().putInt("call_silence_ms", v).apply()

    /** hang up after this long without anyone speaking; 0 = never */
    var callIdleSeconds: Int
        get() = sp.getInt("call_idle_s", 180)
        set(v) = sp.edit().putInt("call_idle_s", v).apply()

    /** a short tone when it's the user's turn, while the call screen isn't in view */
    var callCue: Boolean
        get() = sp.getBoolean("call_cue", true)
        set(v) = sp.edit().putBoolean("call_cue", v).apply()

    /**
     * with a Bluetooth headset connected, talk through its microphone (call audio, like a phone
     * call) instead of the phone's microphone with media-quality playback
     */
    var callHeadsetMic: Boolean
        get() = sp.getBoolean("call_headset_mic", false)
        set(v) = sp.edit().putBoolean("call_headset_mic", v).apply()

    /** show what both sides said on the call screen */
    var callCaptions: Boolean
        get() = sp.getBoolean("call_captions", true)
        set(v) = sp.edit().putBoolean("call_captions", v).apply()

    /**
     * 噜噜 remembers the user across calls: transcripts are distilled after each call
     * ([com.capsopasme.assistant.memory.MemoryStore])
     */
    var memoryEnabled: Boolean
        get() = sp.getBoolean("memory_enabled", true)
        set(v) = sp.edit().putBoolean("memory_enabled", v).apply()

    // ---------------------------------------------------------------------------------------------
    // looks

    /** the time-stop transition for the sheet and the call ([com.capsopasme.assistant.fx.TimeStopView]) */
    var timeStopFx: Boolean
        get() = sp.getBoolean("time_stop_fx", false)
        set(v) = sp.edit().putBoolean("time_stop_fx", v).apply()

    companion object {
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        /** free tier on open.bigmodel.cn */
        const val DEFAULT_GLM_MODEL = "glm-4.7-flash"
    }
}
