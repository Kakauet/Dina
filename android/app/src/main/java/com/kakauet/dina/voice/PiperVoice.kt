package com.kakauet.dina.voice

import ai.moonshine.voice.TextToSpeech
import ai.moonshine.voice.TranscriberOption
import android.content.Context
import com.kakauet.dina.models.DinaModelFiles

/** Piper Sharvard Medium through Moonshine's runtime (its own G2P reads numbers): the light alternative voice. */
class PiperVoice(context: Context, files: DinaModelFiles) : SpeechVoice {
    override val label = "Piper Sharvard Medium"
    private val tts: TextToSpeech
    override val sampleRate: Int
    override val initMs: Double
    override val warmupMs: Double

    init {
        var local: TextToSpeech? = null
        try {
            var started = System.nanoTime()
            local = TextToSpeech(context)
                .language("es_es")
                .voice("piper_es_ES-sharvard-medium")
                .modelsFrom(files.ttsRoot)
                .options(
                    listOf(
                        TranscriberOption("piper_onnx", files.ttsModel.absolutePath),
                        TranscriberOption("piper_onnx_json", files.ttsConfig.absolutePath),
                        TranscriberOption("g2p_root", files.ttsRoot.absolutePath),
                    ),
                )
            local.load()
            initMs = (System.nanoTime() - started) / 1_000_000.0
            started = System.nanoTime()
            sampleRate = local.synthesize("Hola").sampleRateHz
            warmupMs = (System.nanoTime() - started) / 1_000_000.0
            tts = local
        } catch (error: Throwable) {
            runCatching { local?.close() }
            throw error
        }
    }

    override fun synthesize(text: String): SpeechAudio {
        val started = System.nanoTime()
        val result = tts.synthesize(text)
        return SpeechAudio(result.samples, result.sampleRateHz, (System.nanoTime() - started) / 1_000_000.0)
    }

    override fun close() = tts.close()
}
