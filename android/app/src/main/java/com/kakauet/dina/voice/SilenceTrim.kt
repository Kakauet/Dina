package com.kakauet.dina.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Cuts the silence a voice leaves before and after a sentence. Supertonic 3 puts about 0.55 s before
 * and 0.7 s after every sentence (measured on 17 answers, any threshold from -40 to -60 dBFS): untrimmed, it
 * delays the first sound and the follow-up and leaves ~1.4 s between sentences.
 *
 * The audible part is where the 5 ms RMS is above [Config.thresholdDb]; a small margin of silence is
 * kept on both sides and the ends get a short fade so nothing clicks. The pause between sentences is then
 * the planner's ([SpeechPlanner]), not whatever the model left.
 */
object SilenceTrim {
    data class Config(
        /** RMS of a 5 ms window above this is sound. -50 dBFS is 40 dB under the quietest peak of the 17 answers measured. */
        val thresholdDb: Double = -50.0,
        /** Silence kept before the first sound. */
        val leadMarginMs: Int = 15,
        /** Silence kept after the last sound (the natural fall-off of the last word). */
        val tailMarginMs: Int = 70,
        val fadeInMs: Int = 4,
        val fadeOutMs: Int = 40,
        val windowMs: Int = 5,
    ) {
        /** Part of the cache key: a different trim is a different audio. */
        val signature get() = "trim:$thresholdDb/$leadMarginMs/$tailMarginMs/$fadeInMs/$fadeOutMs/$windowMs"
    }

    /** The audible span of [samples] (sample indices), or null when nothing is above the threshold. */
    fun audible(samples: FloatArray, sampleRate: Int, config: Config = Config()): IntRange? {
        val window = (sampleRate * config.windowMs / 1_000).coerceAtLeast(1)
        val limit = 10.0.pow(config.thresholdDb / 10.0) // mean power of the window: RMS² > 10^(dB/10)
        var first = -1
        var last = -1
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + window, samples.size)
            var sum = 0.0
            for (i in start until end) sum += samples[i].toDouble() * samples[i]
            if (sum / (end - start) > limit) {
                if (first < 0) first = start
                last = end - 1
            }
            start = end
        }
        return if (first < 0) null else first..last
    }

    /** [samples] without the silence at both ends (a copy), or an empty array when it is all silence. */
    fun trim(samples: FloatArray, sampleRate: Int, config: Config = Config()): FloatArray {
        val span = audible(samples, sampleRate, config) ?: return FloatArray(0)
        val from = (span.first - sampleRate * config.leadMarginMs / 1_000).coerceAtLeast(0)
        val to = (span.last + 1 + sampleRate * config.tailMarginMs / 1_000).coerceAtMost(samples.size)
        val out = samples.copyOfRange(from, to)
        fade(out, 0, minOf(sampleRate * config.fadeInMs / 1_000, out.size), rising = true)
        val fadeOut = minOf(sampleRate * config.fadeOutMs / 1_000, out.size)
        fade(out, out.size - fadeOut, out.size, rising = false)
        return out
    }

    /** Milliseconds of silence [trim] removes before the first sound (the latency report counts them as saved). */
    fun leadingSilenceMs(samples: FloatArray, sampleRate: Int, config: Config = Config()): Double {
        val span = audible(samples, sampleRate, config) ?: return 0.0
        return (span.first - sampleRate * config.leadMarginMs / 1_000).coerceAtLeast(0) * 1_000.0 / sampleRate
    }

    /** Raised-cosine ramp over samples[from, to): up when [rising], down otherwise. */
    private fun fade(samples: FloatArray, from: Int, to: Int, rising: Boolean) {
        val length = to - from
        if (length <= 0) return
        for (i in 0 until length) {
            val t = (i + 0.5) / length
            val gain = 0.5 - 0.5 * cos(PI * (if (rising) t else 1.0 - t))
            samples[from + i] = (samples[from + i] * gain).toFloat()
        }
    }
}
