package com.kakauet.dina.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PolishedVoiceTest {
    private val rate = 44_100

    /** Half a second of silence, half a second of tone, 0.7 s of silence: what the real voice does around a sentence. */
    private class FakeVoice(private val rate: Int) : SpeechVoice {
        var calls = mutableListOf<String>()
        override var signature = "fake|steps=4"
        override val label = "fake"
        override val sampleRate = rate
        override val initMs = 0.0
        override val warmupMs = 0.0
        override fun synthesize(text: String): SpeechAudio {
            calls += text
            val lead = rate / 2
            val tone = rate / 2
            val tail = rate * 7 / 10
            val samples = FloatArray(lead + tone + tail) { i -> if (i in lead until lead + tone) 0.3f * sin(2 * PI * 220 * i / rate).toFloat() else 0f }
            return SpeechAudio(samples, rate, 120.0)
        }
        override fun close() = Unit
    }

    @Test
    fun cutsTheSilenceAndReportsHowMuchLeadItCut() {
        val voice = PolishedVoice(FakeVoice(rate))
        val audio = voice.synthesize("Hecho.")
        assertTrue("trimmed to ${audio.durationSeconds} s", audio.durationSeconds < 0.7)
        assertEquals(485.0, audio.trimmedLeadMs, 10.0)
    }

    @Test
    fun aRepeatedSentenceCostsAReadNotASynthesis() {
        val inner = FakeVoice(rate)
        val voice = PolishedVoice(inner, cache = LruSpeechCache())
        val first = voice.synthesize("Hecho.")
        val second = voice.synthesize("Hecho.")
        assertEquals(listOf("Hecho."), inner.calls)
        assertFalse(first.cached)
        assertTrue(second.cached)
        assertEquals(first.samples.size, second.samples.size)
        assertEquals(1, voice.cacheStats!!.hits)
    }

    @Test
    fun theSameSentenceWithOtherSettingsIsAnotherEntry() {
        val inner = FakeVoice(rate)
        val voice = PolishedVoice(inner, cache = LruSpeechCache())
        voice.synthesize("Hecho.")
        inner.signature = "fake|steps=8"
        assertFalse(voice.synthesize("Hecho.").cached)
        assertEquals(2, inner.calls.size)
    }

    @Test
    fun digitsAndWordsShareAnEntry() {
        val inner = FakeVoice(rate)
        val voice = PolishedVoice(inner, cache = LruSpeechCache())
        voice.synthesize("Son las 7:15.")
        assertTrue(voice.synthesize("Son las siete y cuarto.").cached)
        assertEquals(listOf("Son las siete y cuarto."), inner.calls)
    }

    @Test
    fun withoutTrimmingTheAudioIsAsTheVoiceMadeIt() {
        val voice = PolishedVoice(FakeVoice(rate), trim = null)
        assertEquals(1.7, voice.synthesize("Hecho.").durationSeconds, 0.001)
    }

    @Test
    fun prewarmMakesWhatIsMissingAndStopsWhenAskedTo() {
        val inner = FakeVoice(rate)
        val voice = PolishedVoice(inner, cache = LruSpeechCache())
        val phrases = listOf("Hecho.", "Vale. ¿Qué necesitas?", "Dime.")
        assertEquals(4, voice.prewarm(phrases) { false }) // Vale. + ¿Qué necesitas? are two pieces
        assertEquals(0, voice.prewarm(phrases) { false })
        assertTrue(voice.synthesize("¿Qué necesitas?").cached)

        val other = PolishedVoice(FakeVoice(rate), cache = LruSpeechCache())
        var asked = 0
        assertEquals(2, other.prewarm(phrases) { asked++ >= 2 })
    }

    @Test
    fun withoutACachePrewarmDoesNothing() {
        assertEquals(0, PolishedVoice(FakeVoice(rate)).prewarm(listOf("Hecho.")) { false })
    }
}
