package com.kakauet.dina.config

import android.content.Context
import com.kakauet.dina.voice.OrtProvider
import com.kakauet.dina.voice.SupertonicVoice
import com.kakauet.dina.voice.VoiceChoice
import com.kakauet.dina.voice.WakeSensitivity

class DinaSettings(context: Context) {
    private val prefs = context.getSharedPreferences("dina_settings", Context.MODE_PRIVATE)

    init {
        // Keys of previous detectors: only the sensitivity level is stored.
        prefs.edit().remove("wake_threshold").remove("wake_threshold_v2_migrated").remove("wake_threshold_v3_candidate")
            .remove("wake_threshold_v3_candidate_migrated").remove("wake_v3_candidate_enabled").apply()
    }

    var wakeSensitivity: WakeSensitivity
        get() = prefs.getString("wake_sensitivity", null)?.let { name -> WakeSensitivity.entries.find { it.name == name } }
            ?: WakeSensitivity.NORMAL
        set(value) = prefs.edit().putString("wake_sensitivity", value.name).apply()

    var wakeDiagnosticEnabled: Boolean
        get() = prefs.getBoolean("wake_diagnostic_enabled", false)
        set(value) = prefs.edit().putBoolean("wake_diagnostic_enabled", value).apply()

    var followUpTimeoutMs: Long
        get() = prefs.getLong("follow_up_timeout_ms", 6_000L)
        set(value) = prefs.edit().putLong("follow_up_timeout_ms", value.coerceIn(3_000L, 12_000L)).apply()

    var voiceVolume: Float
        get() = prefs.getFloat("voice_volume", 0.72f)
        set(value) = prefs.edit().putFloat("voice_volume", value.coerceIn(0f, 1f)).apply()

    var dinaActive: Boolean
        get() = prefs.getBoolean("dina_active", true)
        set(value) = prefs.edit().putBoolean("dina_active", value).apply()

    var backgroundListening: Boolean
        get() = prefs.getBoolean("background_listening", false)
        set(value) = prefs.edit().putBoolean("background_listening", value).apply()

    var listenScreenOff: Boolean
        get() = prefs.getBoolean("listen_screen_off", true)
        set(value) = prefs.edit().putBoolean("listen_screen_off", value).apply()

    var respondByVoice: Boolean
        get() = prefs.getBoolean("respond_by_voice", true)
        set(value) = prefs.edit().putBoolean("respond_by_voice", value).apply()

    /** Android's on-device recognizer is the default; Moonshine runs inside Dina instead (Ajustes › Escucha). */
    var moonshineStt: Boolean
        get() = prefs.getBoolean("legacy_stt_enabled", false)
        set(value) = prefs.edit().putBoolean("legacy_stt_enabled", value).apply()

    var showTimestamps: Boolean
        get() = prefs.getBoolean("show_timestamps", false)
        set(value) = prefs.edit().putBoolean("show_timestamps", value).apply()

    /** Selected [VoiceChoice] (Ajustes › Voz); null means the default, Supertonic F2. */
    var voiceId: String?
        get() = prefs.getString("voice_id", null)
        set(value) = prefs.edit().putString("voice_id", value).apply()

    val voice: VoiceChoice get() = VoiceChoice.byId(voiceId)

    /** Supertonic denoising steps: fewer is faster, more is closer to the converged voice. */
    var supertonicSteps: Int
        get() = AppVariant.allowedSteps(prefs.getInt("supertonic_steps", AppVariant.defaultSteps), AppVariant.isLite)
        set(value) = prefs.edit().putInt("supertonic_steps", AppVariant.allowedSteps(value, AppVariant.isLite)).apply()

    /** ONNX Runtime threads for Supertonic (Diagnóstico); applied when the voice reloads. */
    var voiceThreads: Int
        get() = prefs.getInt("voice_threads", AppVariant.defaultVoiceThreads(AppVariant.fastCores)).coerceIn(1, 8)
        set(value) = prefs.edit().putInt("voice_threads", value.coerceIn(1, 8)).apply()

    var voiceProvider: OrtProvider
        get() = runCatching { OrtProvider.valueOf(prefs.getString("voice_provider", null) ?: "CPU") }.getOrDefault(OrtProvider.CPU)
        set(value) = prefs.edit().putString("voice_provider", value.name).apply()

    /** llama.cpp threads (Diagnóstico); the default is 6 for the S24's X4 and A720 cores, fewer for Lite ([AppVariant]). Applied without reloading. */
    var llmThreads: Int
        get() = prefs.getInt("llm_threads", AppVariant.defaultLlmThreads(AppVariant.fastCores)).coerceIn(2, 8)
        set(value) = prefs.edit().putInt("llm_threads", value.coerceIn(2, 8)).apply()
}
