package com.kakauet.dina.voice

import kotlin.math.max
import kotlin.math.sqrt

/** 20 ms adaptive energy VAD. It only gates utterances; Moonshine remains the recognizer. */
class FastVad(
    private val sampleRate: Int = 16_000,
    private val preRollMs: Int = 320,
    private val endpointMs: Int = 480,
    private val maxSpeechMs: Int = 15_000,
) {
    sealed interface Event {
        data object None : Event
        data object SpeechStarted : Event
        data class SpeechEnded(
            val pcm: ShortArray,
            /** Estimated end of the last voiced frame, before endpointing silence. */
            val speechEndNs: Long,
            /** Moment at which the VAD emitted the endpoint event. */
            val detectedNs: Long,
        ) : Event
    }

    private val maxPreRollSamples = sampleRate * preRollMs / 1000
    private val maxSpeechSamples = sampleRate * maxSpeechMs / 1000
    private val preRoll = ShortArray(maxPreRollSamples.coerceAtLeast(1))
    private val speech = ShortArray(maxSpeechSamples + maxPreRollSamples)
    private var preWrite = 0
    private var preCount = 0
    private var speechSize = 0
    private var noiseRms = 220.0
    private var voicedFrames = 0
    private var silentFrames = 0
    var speaking = false
        private set

    fun reset() {
        preWrite = 0; preCount = 0; speechSize = 0; voicedFrames = 0; silentFrames = 0; speaking = false
    }

    fun accept(frame: ShortArray, count: Int, nowNs: Long = System.nanoTime()): Event {
        var sum = 0.0
        for (i in 0 until count) sum += frame[i].toDouble() * frame[i]
        val rms = sqrt(sum / max(1, count))
        val threshold = max(520.0, noiseRms * 3.1)
        val voiced = rms > threshold

        if (!speaking) {
            if (!voiced) noiseRms = noiseRms * 0.985 + rms * 0.015
            for (i in 0 until count) {
                preRoll[preWrite] = frame[i]
                preWrite = (preWrite + 1) % preRoll.size
                preCount = minOf(preRoll.size, preCount + 1)
            }
            voicedFrames = if (voiced) voicedFrames + 1 else 0
            if (voicedFrames >= 3) {
                speaking = true
                val oldest = (preWrite - preCount + preRoll.size) % preRoll.size
                val first = minOf(preCount, preRoll.size - oldest)
                System.arraycopy(preRoll, oldest, speech, 0, first)
                if (first < preCount) System.arraycopy(preRoll, 0, speech, first, preCount - first)
                speechSize = preCount
                preCount = 0
                silentFrames = 0
                return Event.SpeechStarted
            }
            return Event.None
        }

        val append = minOf(count, speech.size - speechSize)
        if (append > 0) {
            System.arraycopy(frame, 0, speech, speechSize, append)
            speechSize += append
        }
        silentFrames = if (voiced) 0 else silentFrames + 1
        val frameMs = count * 1000 / sampleRate
        val reachedEnd = silentFrames * frameMs >= endpointMs
        val reachedMax = speechSize >= maxSpeechSamples
        if (!reachedEnd && !reachedMax) return Event.None

        val trim = if (reachedEnd) minOf(speechSize, sampleRate * endpointMs / 2000) else 0
        val result = speech.copyOfRange(0, speechSize - trim)
        val endpointDelayNs = if (reachedEnd) silentFrames.toLong() * frameMs * 1_000_000L else 0L
        reset()
        return Event.SpeechEnded(result, nowNs - endpointDelayNs, nowNs)
    }
}
