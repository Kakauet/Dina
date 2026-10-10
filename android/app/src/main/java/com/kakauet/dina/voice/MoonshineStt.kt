package com.kakauet.dina.voice

import ai.moonshine.voice.Transcriber
import com.kakauet.dina.models.DinaModelFiles

/** Moonshine Spanish Base, the optional speech recognizer inside Dina (Ajustes › Escucha). */
class MoonshineStt(files: DinaModelFiles) : AutoCloseable {
    private val stt = Transcriber()
    val initMs: Double

    init {
        val started = System.nanoTime()
        try {
            stt.loadFromFiles(files.stt.absolutePath, 1) // Moonshine Base
        } catch (error: Throwable) {
            runCatching { stt.close() }
            throw error
        }
        initMs = (System.nanoTime() - started) / 1_000_000.0
    }

    fun transcribe(pcm: ShortArray): Pair<String, Double> {
        val audio = FloatArray(pcm.size)
        for (i in pcm.indices) audio[i] = pcm[i] / 32768f
        val started = System.nanoTime()
        val text = stt.transcribeWithoutStreaming(audio, 16_000).text().trim()
        return text to (System.nanoTime() - started) / 1_000_000.0
    }

    override fun close() = stt.close()
}
