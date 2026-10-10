package com.kakauet.dina.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Classifier-free guidance for Supertonic 3, done by the caller (scripts/tts/supertonic_cfg.py explains the split).
 * The estimator graph takes N rows and returns the velocity of each:
 *
 *   guided step     N = 2   rows [conditioned, unconditioned]    v = W·cond − (W−1)·uncond     (W = [SCALE], the original)
 *   cond-only step  N = 1   row  [conditioned]                   v = cond                      (about half the work)
 *
 * and the latent moves  x ← (x + v / total_steps) · mask.  [SupertonicVoice.guidedSteps] says how many of the
 * first steps are guided. One ONNX session and one set of weights serve both kinds of step.
 */
class SupertonicGuidance(
    private val textToken: FloatArray,
    private val keyCond: FloatArray,
    private val keyUncond: FloatArray,
    private val valueUncond: FloatArray,
) {
    init {
        require(textToken.size == TEXT_CHANNELS && keyCond.size == STYLE_SIZE && keyUncond.size == STYLE_SIZE && valueUncond.size == STYLE_SIZE) {
            "guidance.bin: unexpected sizes"
        }
    }

    /** The rows a step feeds the graph. [batch] is 2 (guided) or 1. */
    class Rows(val batch: Int, val textEmb: FloatArray, val styleValue: FloatArray, val styleKey: FloatArray)

    /**
     * Rows for a sentence: [textEmb] is the encoder's [256, T] output (flat, one row), [styleValue] the voice's style_ttl [50, 256].
     * Guided: the conditioned row followed by the unconditioned one (the text token repeated at every position).
     */
    fun rows(textEmb: FloatArray, styleValue: FloatArray, guided: Boolean): Rows {
        if (!guided) return Rows(1, textEmb, styleValue, keyCond)
        val length = textEmb.size / TEXT_CHANNELS
        val text = FloatArray(textEmb.size * 2)
        textEmb.copyInto(text)
        for (channel in 0 until TEXT_CHANNELS) {
            val base = textEmb.size + channel * length
            text.fill(textToken[channel], base, base + length)
        }
        return Rows(2, text, styleValue + valueUncond, keyCond + keyUncond)
    }

    /**
     * One step: moves [latent] ([channels·frames], row-major) with the [velocity] rows the graph returned
     * (the conditioned row, then the unconditioned one when [guided]). Frames from [maskedFrames] on stay 0 (the latent mask).
     */
    fun advance(latent: FloatArray, velocity: FloatArray, guided: Boolean, frames: Int, maskedFrames: Int, totalSteps: Int) {
        val size = latent.size
        val rest = 1f / totalSteps
        for (i in 0 until size) {
            val cond = velocity[i]
            val v = if (guided) SCALE * cond - (SCALE - 1f) * velocity[size + i] else cond
            latent[i] = if (i % frames < maskedFrames) latent[i] + v * rest else 0f
        }
    }

    companion object {
        /** The original's  v = 4·cond − 3·uncond. */
        const val SCALE = 4f
        const val TEXT_CHANNELS = 256
        const val STYLE_TOKENS = 50
        const val STYLE_CHANNELS = 256
        const val STYLE_SIZE = STYLE_TOKENS * STYLE_CHANNELS

        /** True when step [step] (0-based) is guided: the first [guidedSteps] are. */
        fun isGuided(step: Int, guidedSteps: Int) = step < guidedSteps

        /** `onnx/guidance.bin`: float32 little endian, text token, keys (conditioned), keys (unconditioned), values (unconditioned). */
        fun load(file: File): SupertonicGuidance {
            val floats = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            check(floats.remaining() == TEXT_CHANNELS + 3 * STYLE_SIZE) { "guidance.bin: ${floats.remaining()} floats" }
            fun take(n: Int) = FloatArray(n).also { floats.get(it) }
            return SupertonicGuidance(take(TEXT_CHANNELS), take(STYLE_SIZE), take(STYLE_SIZE), take(STYLE_SIZE))
        }
    }
}
