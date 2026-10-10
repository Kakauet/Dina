package com.kakauet.dina.voice

import java.text.Normalizer
import kotlin.math.floor

/**
 * Supertonic 3's text front end and tensor shapes, ported from the reference package
 * (`pip install supertonic` 1.3.1, `core.py`: `UnicodeProcessor`, `sample_noisy_latent`).
 * Pure Kotlin so it is tested on the JVM against values from the Python reference.
 */
object SupertonicText {
    /** Text as the model reads it: NFKD, symbols normalized, final punctuation and `<es>…</es>`. */
    fun preprocess(text: String, lang: String = "es"): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
        t = EMOJI.replace(t, "")
        for ((from, to) in SYMBOLS) t = t.replace(from, to)
        t = SPECIAL.replace(t, "")
        for ((from, to) in ABBREVIATIONS) t = t.replace(from, to)
        for ((from, to) in PUNCTUATION_SPACING) t = t.replace(from, to)
        t = DUPLICATE_QUOTES.replace(t, "$1")
        t = WHITESPACE.replace(t, " ").trim()
        if (!ENDING_PUNCTUATION.containsMatchIn(t)) t += "."
        return "<$lang>$t</$lang>"
    }

    /**
     * Model input ids: each UTF-16 unit through `unicode_indexer.json` (−1 = not supported).
     * Unsupported characters are dropped instead of failing the whole answer.
     */
    fun ids(text: String, indexer: IntArray, lang: String = "es"): LongArray {
        val supported = preprocess(text, lang).filter { it.code < indexer.size && indexer[it.code] >= 0 }
        return LongArray(supported.length) { indexer[supported[it].code].toLong() }
    }

    /** Characters of [text] the model cannot read (after preprocessing). */
    fun unsupported(text: String, indexer: IntArray): Set<Char> =
        preprocess(text).filterNot { it.code < indexer.size && indexer[it.code] >= 0 }.toSet()

    data class Shapes(
        /** Samples of speech the duration predictor asked for. */
        val samples: Int,
        /** Frames of the noisy latent ([1, latentChannels, frames]). */
        val frames: Int,
        /** Frames inside `latent_mask` (1.0), the rest are 0.0. */
        val maskedFrames: Int,
        val latentChannels: Int,
    )

    /** `sample_noisy_latent` for one sentence of [seconds] (already divided by the speed); float32 like NumPy. */
    fun shapes(seconds: Float, config: Config): Shapes {
        val chunk = config.baseChunkSize * config.chunkCompressFactor
        val wav: Float = seconds * config.sampleRate
        val frames = floor((wav + chunk - 1) / chunk).toInt()
        val samples = wav.toLong()
        val masked = ((samples + chunk - 1) / chunk).toInt()
        return Shapes(samples.toInt(), frames.coerceAtLeast(1), masked.coerceAtMost(frames).coerceAtLeast(1), config.latentDim * config.chunkCompressFactor)
    }

    /** The parts of `tts.json` the pipeline uses. */
    data class Config(val sampleRate: Int = 44_100, val baseChunkSize: Int = 512, val chunkCompressFactor: Int = 6, val latentDim: Int = 24)

    private val EMOJI = Regex(
        "[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}\\x{1F780}-\\x{1F7FF}" +
            "\\x{1F800}-\\x{1F8FF}\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}\\x{1FA70}-\\x{1FAFF}\\x{2600}-\\x{26FF}" +
            "\\x{2700}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}]+",
    )
    private val SYMBOLS = listOf(
        "–" to "-", "‑" to "-", "—" to "-", "¯" to " ", "_" to " ", "“" to "\"", "”" to "\"",
        "‘" to "'", "’" to "'", "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ", "/" to " ",
        "#" to " ", "→" to " ", "←" to " ",
    )
    private val SPECIAL = Regex("[♥☆♡©\\\\]")
    private val ABBREVIATIONS = listOf("@" to " at ", "e.g.," to "for example, ", "i.e.," to "that is, ")
    private val PUNCTUATION_SPACING = listOf(" ," to ",", " ." to ".", " !" to "!", " ?" to "?", " ;" to ";", " :" to ":", " '" to "'")
    private val DUPLICATE_QUOTES = Regex("([\"'`])\\1+")
    private val WHITESPACE = Regex("\\s+")
    private val ENDING_PUNCTUATION = Regex("[.!?;:,'\"')\\]}…。」』】〉》›»]$")
}
