package com.kakauet.dina.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpeechCacheTest {
    private val directory: File = Files.createTempDirectory("speech-cache").toFile()

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun audio(seconds: Double = 0.1, value: Float = 0.25f, rate: Int = 1_000) =
        SpeechAudio(FloatArray((seconds * rate).toInt()) { value * (it % 7) / 7f }, rate, 50.0)

    private fun key(text: String, signature: String = "voice") = SpeechKey(text, signature)

    @Test
    fun theKeyChangesWithTheTextAndWithAnythingThatChangesTheAudio() {
        assertEquals(key("Hecho.").id, key("Hecho.").id)
        assertEquals(32, key("Hecho.").id.length)
        assertNotEquals(key("Hecho.").id, key("Vale.").id)
        assertNotEquals(key("Hecho.").id, key("Hecho.", "voice|steps=4").id)
    }

    @Test
    fun aSentenceComesBackAsHeardBeforeToWithin16Bits() {
        val cache = LruSpeechCache()
        val original = audio()
        cache.put(key("a"), original)
        val back = cache.get(key("a"))!!
        assertTrue(back.cached)
        assertEquals(original.sampleRate, back.sampleRate)
        assertEquals(original.samples.size, back.samples.size)
        original.samples.indices.forEach { assertEquals(original.samples[it], back.samples[it], 1f / 16_000) }
    }

    @Test
    fun missesAndHitsAreCounted() {
        val cache = LruSpeechCache()
        assertNull(cache.get(key("a")))
        cache.put(key("a"), audio())
        assertNotNull(cache.get(key("a")))
        val stats = cache.stats()
        assertEquals(1, stats.hits)
        assertEquals(1, stats.misses)
        assertFalse(cache.contains(key("b")))
        assertEquals(1, cache.stats().misses) // contains() is not a lookup
    }

    @Test
    fun emptyAudioIsNotStored() {
        val cache = LruSpeechCache()
        cache.put(key("a"), SpeechAudio(FloatArray(0), 1_000, 0.0))
        assertFalse(cache.contains(key("a")))
    }

    @Test
    fun theMemoryLevelDropsTheLeastRecentlyUsed() {
        // 100 samples = 200 bytes each; room for two.
        val cache = LruSpeechCache(memoryBudget = 450)
        cache.put(key("a"), audio(0.1)); cache.put(key("b"), audio(0.1))
        cache.get(key("a")) // a is now the most recent
        cache.put(key("c"), audio(0.1))
        assertTrue(cache.contains(key("a")))
        assertFalse(cache.contains(key("b")))
        assertTrue(cache.contains(key("c")))
        assertTrue(cache.stats().memoryBytes <= 450)
    }

    @Test
    fun theDiskLevelKeepsWhatMemoryDroppedAndSurvivesARestart() {
        val first = LruSpeechCache(memoryBudget = 250, directory = directory)
        first.put(key("a"), audio(0.1)); first.put(key("b"), audio(0.1))
        assertTrue(first.stats().memoryBytes <= 250)
        assertNotNull(first.get(key("a"))) // read back from disk
        val restarted = LruSpeechCache(memoryBudget = 250, directory = directory)
        assertTrue(restarted.contains(key("a")))
        assertTrue(restarted.contains(key("b")))
        val back = restarted.get(key("b"))!!
        assertEquals(100, back.samples.size)
    }

    @Test
    fun theDiskLevelIsBounded() {
        val cache = LruSpeechCache(memoryBudget = 0, directory = directory, diskBudget = 700)
        listOf("a", "b", "c", "d").forEach { cache.put(key(it), audio(0.1)) } // 212 bytes each with the header
        assertTrue(cache.stats().diskBytes <= 700)
        assertFalse(cache.contains(key("a")))
        assertTrue(cache.contains(key("d")))
        assertEquals(cache.stats().diskBytes, directory.listFiles()!!.filter { it.name.endsWith(".pcm") }.sumOf { it.length() })
    }

    @Test
    fun aCorruptFileIsForgottenNotFatal() {
        val cache = LruSpeechCache(memoryBudget = 0, directory = directory)
        cache.put(key("a"), audio())
        File(directory, key("a").id + ".pcm").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.get(key("a")))
        assertFalse(cache.contains(key("a")))
    }
}
