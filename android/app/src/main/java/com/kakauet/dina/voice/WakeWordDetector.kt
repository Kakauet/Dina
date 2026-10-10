package com.kakauet.dina.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.kakauet.dina.models.DinaModelFiles
import java.nio.FloatBuffer
import java.util.Collections

/**
 * The «Dina» detector: openWakeWord's frozen front-end (mel + embedding models) and Dina's head, streamed.
 *
 * Every 80 ms the mel model adds 8 frames, the embedding model turns the last 76 frames into one embedding and the
 * head scores the last 16 embeddings (~2 s of audio): three small ONNX runs per step. [WakeGate] skips them while
 * the room is silent. `scripts/wakeword/` trains and measures the head on exactly these features.
 */
class WakeWordDetector(files: DinaModelFiles) : AutoCloseable {
    companion object { const val CHUNK_SAMPLES = 1_280 }

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val melSession = env.createSession(files.wakeMel.absolutePath, options)
    private val embeddingSession = env.createSession(files.wakeEmbedding.absolutePath, options)
    private val headSession = env.createSession(files.wakeHead.absolutePath, options)
    private val gate = WakeGate()
    private val chunk = ShortArray(CHUNK_SAMPLES)
    private var filled = 0
    private val raw = FloatArray(1_760)
    private val melHistory = FloatArray(76 * 32)
    private val embeddingHistory = FloatArray(16 * 96)
    private var embeddingCount = 0
    private var closed = false

    init { reset() }

    @Synchronized fun reset() {
        gate.reset()
        filled = 0
        resetStream()
    }

    /** Feeds microphone samples; [onScore] gets the score of every completed 80 ms step (0 while asleep). */
    @Synchronized fun accept(pcm: ShortArray, count: Int, onScore: (Float) -> Unit) {
        var offset = 0
        while (offset < count && !closed) {
            val copied = minOf(count - offset, CHUNK_SAMPLES - filled)
            System.arraycopy(pcm, offset, chunk, filled, copied)
            filled += copied
            offset += copied
            if (filled < CHUNK_SAMPLES) break
            filled = 0
            val score = when (gate.accept(chunk)) {
                WakeGate.Step.SLEEP -> 0f
                WakeGate.Step.RUN -> step(chunk)
                WakeGate.Step.WAKE -> {
                    resetStream()
                    var last = 0f
                    gate.replay { last = step(it) }
                    last
                }
            }
            onScore(score)
        }
    }

    private fun resetStream() {
        raw.fill(0f); melHistory.fill(1f); embeddingHistory.fill(0f)
        embeddingCount = 0
    }

    private fun step(pcm: ShortArray): Float {
        System.arraycopy(raw, CHUNK_SAMPLES, raw, 0, raw.size - CHUNK_SAMPLES)
        for (i in 0 until CHUNK_SAMPLES) raw[raw.size - CHUNK_SAMPLES + i] = pcm[i].toFloat()

        val mel = run(melSession, "input", raw, longArrayOf(1, raw.size.toLong()))
        val frames = mel.size / 32
        // openWakeWord advances one 8-frame mel block for every 1,280 samples.
        for (frame in frames - 8 until frames) appendMel(mel, frame * 32)

        val embedding = run(embeddingSession, "input_1", melHistory, longArrayOf(1, 76, 32, 1))
        appendEmbedding(embedding, embedding.size - 96)
        if (embeddingCount < 16) return 0f
        return run(headSession, "features", embeddingHistory, longArrayOf(1, 1_536))[0]
    }

    private fun appendMel(values: FloatArray, offset: Int) {
        System.arraycopy(melHistory, 32, melHistory, 0, melHistory.size - 32)
        for (i in 0 until 32) melHistory[melHistory.size - 32 + i] = values[offset + i] / 10f + 2f
    }

    private fun appendEmbedding(values: FloatArray, offset: Int) {
        System.arraycopy(embeddingHistory, 96, embeddingHistory, 0, embeddingHistory.size - 96)
        System.arraycopy(values, offset, embeddingHistory, embeddingHistory.size - 96, 96)
        embeddingCount = minOf(16, embeddingCount + 1)
    }

    private fun run(session: OrtSession, name: String, input: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(Collections.singletonMap(name, tensor)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        melSession.close(); embeddingSession.close(); headSession.close(); options.close()
    }
}
