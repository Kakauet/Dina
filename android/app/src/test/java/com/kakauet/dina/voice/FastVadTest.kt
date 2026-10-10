package com.kakauet.dina.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastVadTest {
    @Test
    fun reportsRealSpeechEndBeforeEndpointSilence() {
        val vad = FastVad(endpointMs = 480)
        val voiced = ShortArray(320) { 4_000 }
        val silence = ShortArray(320)
        var now = 1_000_000_000L

        repeat(3) {
            now += 20_000_000L
            vad.accept(voiced, voiced.size, now)
        }
        assertTrue(vad.speaking)
        repeat(5) {
            now += 20_000_000L
            vad.accept(voiced, voiced.size, now)
        }

        var ended: FastVad.Event.SpeechEnded? = null
        repeat(24) {
            now += 20_000_000L
            val event = vad.accept(silence, silence.size, now)
            if (event is FastVad.Event.SpeechEnded) ended = event
        }
        val result = requireNotNull(ended)
        assertEquals(480.0, (result.detectedNs - result.speechEndNs) / 1_000_000.0, 0.01)
        assertTrue(result.pcm.isNotEmpty())
        assertTrue(!vad.speaking)
    }
}
