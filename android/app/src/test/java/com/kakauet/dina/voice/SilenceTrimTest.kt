package com.kakauet.dina.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SilenceTrimTest {
    private val rate = 44_100

    /** [leadMs] of quiet noise, [toneMs] of a 220 Hz tone at [amplitude], [tailMs] of quiet noise. */
    private fun signal(leadMs: Int, toneMs: Int, tailMs: Int, amplitude: Float = 0.3f, noise: Float = 0.0005f): FloatArray {
        val lead = rate * leadMs / 1000
        val tone = rate * toneMs / 1000
        val tail = rate * tailMs / 1000
        return FloatArray(lead + tone + tail) { i ->
            val hiss = noise * if (i % 2 == 0) 1f else -1f
            if (i in lead until lead + tone) amplitude * sin(2 * PI * 220 * i / rate).toFloat() else hiss
        }
    }

    @Test
    fun cutsTheSilenceAroundASentenceAndKeepsASmallMargin() {
        val raw = signal(550, 1_000, 700)
        val trimmed = SilenceTrim.trim(raw, rate)
        val expected = rate * (1_000 + 15 + 70) / 1000
        assertTrue("length ${trimmed.size} vs $expected", abs(trimmed.size - expected) < rate * 12 / 1000)
        // The sound itself is untouched in the middle. The first sound is found per 5 ms window, so it starts at the window the tone begins in.
        val window = rate * 5 / 1000
        val offset = (550 * rate / 1000) / window * window - 15 * rate / 1000
        assertEquals(raw[offset + 20_000], trimmed[20_000], 1e-6f)
        assertEquals(raw[offset + 30_000], trimmed[30_000], 1e-6f)
    }

    @Test
    fun theEndsFadeSoNothingClicks() {
        val trimmed = SilenceTrim.trim(signal(300, 500, 300), rate)
        assertTrue(abs(trimmed.first()) < 1e-3f)
        assertTrue(abs(trimmed.last()) < 1e-3f)
    }

    @Test
    fun allSilenceBecomesEmpty() {
        assertEquals(0, SilenceTrim.trim(FloatArray(rate), rate).size)
        assertEquals(0, SilenceTrim.trim(signal(500, 0, 500), rate).size)
        assertNull(SilenceTrim.audible(FloatArray(10), rate))
    }

    @Test
    fun theThresholdSeparatesBreathFromSpeech() {
        // Noise at -40 dBFS is above the default -50 dBFS threshold: it is kept as sound; at -60 dBFS it is cut.
        val loud = signal(400, 300, 400, noise = 0.01f)
        assertTrue(SilenceTrim.trim(loud, rate).size > rate)
        val quiet = signal(400, 300, 400, noise = 0.001f)
        assertTrue(SilenceTrim.trim(quiet, rate).size < rate / 2)
    }

    @Test
    fun reportsTheLeadingSilenceItRemoves() {
        val ms = SilenceTrim.leadingSilenceMs(signal(550, 500, 500), rate)
        assertTrue("lead $ms ms", abs(ms - 535.0) < 10.0)
        assertEquals(0.0, SilenceTrim.leadingSilenceMs(FloatArray(100), rate), 0.0)
    }

    @Test
    fun aSoundWithoutSilenceIsKeptWhole() {
        val raw = signal(0, 500, 0)
        assertEquals(raw.size, SilenceTrim.trim(raw, rate).size)
    }

    @Test
    fun theConfigIsPartOfTheCacheKey() {
        assertNotEquals(SilenceTrim.Config().signature, SilenceTrim.Config(thresholdDb = -45.0).signature)
    }
}
