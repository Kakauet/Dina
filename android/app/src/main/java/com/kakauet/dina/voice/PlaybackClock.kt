package com.kakauet.dina.voice

/**
 * When has everything written to an AudioTrack really been heard?
 *
 * `playbackHeadPosition` counts the frames the mixer has taken from the track; the loudspeaker is still
 * ~20-40 ms behind it. `AudioTrack.getTimestamp` says which frame was *presented* at which instant, so
 * the frames heard now are that frame plus the time since. Without a timestamp the head position and a
 * fixed latency are used. Pure arithmetic (the clock reads happen in [AudioOutput]).
 */
object PlaybackClock {
    /** Typical mixer-to-loudspeaker delay when the device gives no timestamp. */
    const val FALLBACK_LATENCY_MS = 30.0

    /** Frames heard at [nowNs], given that frame [presentedFrames] was presented at [presentedAtNs]. */
    fun heardFrames(presentedFrames: Long, presentedAtNs: Long, nowNs: Long, sampleRate: Int): Long =
        presentedFrames + ((nowNs - presentedAtNs).coerceAtLeast(0L) * sampleRate / 1_000_000_000L)

    /** Milliseconds until [written] frames have been heard; 0 when they already have. */
    fun remainingMs(written: Long, heard: Long, sampleRate: Int): Double =
        ((written - heard).coerceAtLeast(0L) * 1_000.0 / sampleRate)

    /** The head position of an AudioTrack is a 32-bit counter that wraps; as an unsigned long. */
    fun unwrap(headPosition: Int): Long = headPosition.toLong() and 0xFFFF_FFFFL

    /**
     * Milliseconds until the mixer has taken [written] frames (the head position alone, no timestamp
     * available). The caller adds [FALLBACK_LATENCY_MS] once when this reaches 0.
     */
    fun mixerRemainingMs(written: Long, headPosition: Int, sampleRate: Int): Double =
        remainingMs(written, unwrap(headPosition), sampleRate)
}
