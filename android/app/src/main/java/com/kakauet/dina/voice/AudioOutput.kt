package com.kakauet.dina.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Plays synthesized speech through one reusable streaming AudioTrack. */
class AudioOutput : AutoCloseable {
    data class Playback(
        /** When the first sentence started playing. */
        val firstAudioNs: Long,
        val initMs: Double,
        /** When the first sentence was handed over (synthesized). */
        val firstReadyNs: Long,
        /** Silence heard between pieces beyond their planned pauses, in total: the next piece was late. */
        val gapMs: Double = 0.0,
        /** When the last frame had really been heard (the follow-up listening opens here). */
        val endNs: Long = 0L,
        /** How the end was detected: AudioTrack timestamps ("timestamp") or the mixer's head position plus a fixed latency ("head"). */
        val endClock: String = "",
    )

    private var player: AudioTrack? = null

    /**
     * Plays [sentences] in order as they arrive, so the next one is synthesized while the previous
     * one plays, with the pause each one asks for ([SpeechAudio.pauseAfterMs]) between them; if a piece arrives late,
     * the silence already heard counts as (part of) its pause. Returns when everything has been heard, or null
     * if there was nothing to play.
     */
    suspend fun play(sentences: ReceiveChannel<SpeechAudio>, volume: Float): Playback? = withContext(Dispatchers.IO) {
        var track: AudioTrack? = null
        var playback: Playback? = null
        var written = 0L
        var pauseBefore = 0
        var gapMs = 0.0
        var playingSinceNs = 0L // + written / rate = when the buffered audio runs out, if nothing is late
        for (audio in sentences) {
            if (audio.samples.isEmpty()) continue
            val current = track
            if (current == null) {
                val readyNs = System.nanoTime()
                val opened = open(audio.sampleRate)
                val initMs = (System.nanoTime() - readyNs) / 1_000_000.0
                opened.pause(); opened.flush(); opened.setVolume(volume)
                val firstChunk = minOf(audio.samples.size, 2_048)
                write(opened, audio.samples, 0, firstChunk)
                opened.play()
                playingSinceNs = System.nanoTime()
                playback = Playback(playingSinceNs, initMs, readyNs)
                write(opened, audio.samples, firstChunk, audio.samples.size)
                track = opened
            } else {
                if (audio.sampleRate != current.sampleRate) continue // one voice per answer
                val dryAtNs = playingSinceNs + written * 1_000_000_000L / current.sampleRate
                val lateMs = ((System.nanoTime() - dryAtNs) / 1_000_000.0).coerceAtLeast(0.0)
                if (lateMs > 0.0) {
                    gapMs += (lateMs - pauseBefore).coerceAtLeast(0.0)
                    playingSinceNs += (lateMs * 1_000_000.0).toLong() // the buffer was empty: the clock restarts here
                }
                val pause = FloatArray(current.sampleRate * (pauseBefore - lateMs).toInt().coerceAtLeast(0) / 1_000)
                write(current, pause, 0, pause.size)
                written += pause.size
                write(current, audio.samples, 0, audio.samples.size)
            }
            written += audio.samples.size
            pauseBefore = audio.pauseAfterMs
        }
        track?.let { played ->
            val clock = awaitEnd(played, written)
            val endNs = System.nanoTime()
            played.pause(); played.flush()
            playback = playback?.copy(gapMs = gapMs, endNs = endNs, endClock = clock)
        }
        playback
    }

    /** Waits until [written] frames have really been heard; returns the clock that said so. */
    private suspend fun awaitEnd(track: AudioTrack, written: Long): String {
        val timestamp = AudioTimestamp()
        var clock = "head"
        while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            // A timestamp that claims more frames than were written is a stale one from before the flush.
            val remaining = if (track.getTimestamp(timestamp) && timestamp.framePosition <= written) {
                clock = "timestamp"
                val heard = PlaybackClock.heardFrames(timestamp.framePosition, timestamp.nanoTime, System.nanoTime(), track.sampleRate)
                PlaybackClock.remainingMs(written, heard, track.sampleRate)
            } else {
                val mixer = PlaybackClock.mixerRemainingMs(written, track.playbackHeadPosition, track.sampleRate)
                if (mixer > 0.0) mixer else { delay(PlaybackClock.FALLBACK_LATENCY_MS.toLong()); return clock }
            }
            if (remaining <= 0.0) break
            // Timestamps refresh every few tens of ms: sleep most of the way, then look again.
            delay(remaining.toLong().coerceIn(1L, 20L))
        }
        return clock
    }

    private fun open(sampleRate: Int): AudioTrack {
        val existing = player
        if (existing != null && existing.sampleRate == sampleRate && existing.state == AudioTrack.STATE_INITIALIZED) return existing
        runCatching { existing?.release() }
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(maxOf(minimum, 16_384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { player = it }
    }

    private fun write(track: AudioTrack, samples: FloatArray, from: Int, to: Int) {
        var offset = from
        while (offset < to) {
            val count = track.write(samples, offset, to - offset, AudioTrack.WRITE_BLOCKING)
            check(count > 0) { "AudioTrack.write = $count" }
            offset += count
        }
    }

    override fun close() {
        player?.runCatching { release() }
        player = null
    }
}
