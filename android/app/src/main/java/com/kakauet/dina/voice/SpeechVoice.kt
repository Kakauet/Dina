package com.kakauet.dina.voice

data class SpeechAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    /** Milliseconds spent producing it (a cache hit: reading it back). */
    val synthesisMs: Double,
    /** Silence to leave after it when another one follows ([SpeechPlanner]). */
    val pauseAfterMs: Int = 0,
    val cached: Boolean = false,
    /** Silence the voice put before the first sound and [SilenceTrim] cut (not measured again on a cache hit). */
    val trimmedLeadMs: Double = 0.0,
    /** Supertonic's time per stage for this sentence (null for other voices and cache hits). */
    val stages: SupertonicVoice.Stages? = null,
) {
    val durationSeconds: Double get() = samples.size.toDouble() / sampleRate
}

/** A text-to-speech engine. Dina speaks one sentence at a time, so the first plays while the next is synthesized. */
interface SpeechVoice : AutoCloseable {
    val label: String
    val sampleRate: Int
    val initMs: Double
    val warmupMs: Double

    /** Everything that changes the audio of a sentence besides its text (model, style, steps, guidance, speed): the cache key. */
    val signature: String get() = label

    /** For Diagnóstico: the voice and its current settings. */
    val description: String get() = label

    fun synthesize(text: String): SpeechAudio
}

/** The voices the user can pick in Ajustes › Voz. */
enum class VoiceChoice(val id: String, val label: String, val detail: String) {
    SUPERTONIC_F2("supertonic3-f2", "Supertonic F2", "Supertonic 3 · voz F2 · más natural"),
    PIPER_SHARVARD("piper-sharvard", "Piper Sharvard", "Piper Sharvard Medium · más ligera");

    companion object {
        val default = SUPERTONIC_F2
        fun byId(id: String?): VoiceChoice = entries.firstOrNull { it.id == id } ?: default
    }
}

/** Where ONNX Runtime runs Supertonic (Diagnóstico); CPU unless the phone shows another is faster. */
enum class OrtProvider(val label: String) { CPU("CPU"), XNNPACK("XNNPACK"), NNAPI("NNAPI") }
