package com.kakauet.dina.voice

import kotlin.math.sqrt

/**
 * Lets the «Dina» detector sleep while the room is silent, so a quiet house costs almost no CPU.
 *
 * The models stop once every 80 ms chunk of the last [HOLD_CHUNKS] (2 s, everything the head sees) stayed under a
 * level a little above the room's noise floor. When a louder chunk arrives, the detector replays the chunks kept here
 * and scores exactly as if it had never stopped. The level never exceeds [MAX_LEVEL], far below a whisper at 1 m
 * (RMS ~300 on the S24), so in a noisy room the detector just keeps running.
 * `scripts/wakeword/wakeword_core.py` simulates the same arithmetic for training and evaluation.
 */
class WakeGate {
    enum class Step { SLEEP, RUN, WAKE }

    private val history = Array(REPLAY_CHUNKS) { ShortArray(WakeWordDetector.CHUNK_SAMPLES) }
    private var stored = 0
    private var floor = FLOOR_START
    private var quiet = HOLD_CHUNKS

    fun reset() {
        stored = 0
        floor = FLOOR_START
        quiet = HOLD_CHUNKS
    }

    /** Classifies one complete chunk: [Step.WAKE] means "replay [replay] (this chunk included) first". */
    fun accept(chunk: ShortArray): Step {
        var sum = 0.0
        for (sample in chunk) sum += sample.toDouble() * sample
        val rms = sqrt(sum / chunk.size)
        val level = (floor * RATIO).coerceIn(MIN_LEVEL, MAX_LEVEL)
        val wasAwake = quiet < HOLD_CHUNKS
        quiet = if (rms >= level) 0 else minOf(quiet + 1, HOLD_CHUNKS)
        floor = if (rms < floor) rms else floor * FLOOR_RISE
        chunk.copyInto(history[stored % REPLAY_CHUNKS])
        stored++
        return when {
            quiet >= HOLD_CHUNKS -> Step.SLEEP
            wasAwake -> Step.RUN
            else -> Step.WAKE
        }
    }

    /** The kept chunks, oldest first, ending with the last accepted one. */
    fun replay(each: (ShortArray) -> Unit) {
        val count = minOf(stored, REPLAY_CHUNKS)
        for (i in stored - count until stored) each(history[i % REPLAY_CHUNKS])
    }

    companion object {
        const val MIN_LEVEL = 40.0
        const val MAX_LEVEL = 150.0
        const val RATIO = 3.0
        const val HOLD_CHUNKS = 26
        const val REPLAY_CHUNKS = HOLD_CHUNKS + 2
        const val FLOOR_START = 50.0
        const val FLOOR_RISE = 1.01
    }
}
