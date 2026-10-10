package com.kakauet.dina.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SpeechStreamTest {
    /** Synthesizes instantly, except that it waits for [release] before the second sentence. */
    private class FakeVoice(private val release: CompletableDeferred<Unit>) : SpeechVoice {
        val spoken = CopyOnWriteArrayList<String>()
        override val label = "fake"
        override val sampleRate = 100
        override val initMs = 0.0
        override val warmupMs = 0.0
        override fun synthesize(text: String): SpeechAudio {
            if (spoken.isNotEmpty()) runBlocking { release.await() }
            spoken += text
            return SpeechAudio(FloatArray(text.length), sampleRate, 1.0)
        }
        override fun close() = Unit
    }

    @Test
    fun theFirstSentenceIsReadyBeforeTheRestIsSynthesized() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val voice = FakeVoice(release)
        val channel = speechSentences(voice, "Hecho. Tienes tres cosas. ¿Algo más?", Dispatchers.Default)
        val first = withTimeout(5_000) { channel.receive() }
        assertEquals("Hecho.".length, first.samples.size)
        assertEquals(listOf("Hecho."), voice.spoken.toList())
        release.complete(Unit)
        val rest = withTimeout(5_000) { listOf(channel.receive(), channel.receive()) }
        assertEquals(listOf("Tienes tres cosas.".length, "¿Algo más?".length), rest.map { it.samples.size })
        assertEquals(listOf("Hecho.", "Tienes tres cosas.", "¿Algo más?"), voice.spoken.toList())
    }

    @Test
    fun eachPieceCarriesThePauseItsPunctuationAsksFor() = runBlocking {
        val voice = FakeVoice(CompletableDeferred(Unit))
        val channel = speechSentences(voice, "Hecho. ¿Algo más?", Dispatchers.Default)
        val pauses = withTimeout(5_000) { listOf(channel.receive(), channel.receive()).map { it.pauseAfterMs } }
        assertEquals(listOf(SpeechPlanner.Config().pauseSentenceMs, 0), pauses)
    }

    @Test
    fun aLongFirstSentenceStartsWithItsFirstHalf() = runBlocking {
        val voice = FakeVoice(CompletableDeferred(Unit))
        val text = "Tienes tres alarmas: una a las siete y media, otra a las ocho menos cuarto y la última a las nueve de la mañana."
        val channel = speechSentences(voice, text, Dispatchers.Default)
        val first = withTimeout(5_000) { channel.receive() }
        assertEquals("Tienes tres alarmas: una a las siete y media,".length, first.samples.size)
    }
}
