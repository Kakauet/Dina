package com.kakauet.dina.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * What decides the audio of a sentence: the expanded text and the voice's [signature] (model, style, steps,
 * guidance, speed, noise seed, trimming). Supertonic's noise has a fixed seed, so the same key is the same audio.
 */
class SpeechKey(val text: String, val signature: String) {
    val id: String by lazy {
        MessageDigest.getInstance("SHA-256").digest("$signature\u0000$text".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    }
}

/** Sentences kept as 16-bit PCM (half the memory of floats; the rounding is 90 dB under the speech). */
interface SpeechCache {
    data class Stats(val hits: Int = 0, val misses: Int = 0, val memoryBytes: Long = 0, val diskBytes: Long = 0, val lastHitMs: Double = 0.0)

    fun get(key: SpeechKey): SpeechAudio?
    /** Whether [key] is stored, without counting it as a lookup. */
    fun contains(key: SpeechKey): Boolean
    fun put(key: SpeechKey, audio: SpeechAudio)
    fun stats(): Stats
}

/**
 * Least-recently-used cache in two levels: a few MB in memory and a larger folder on disk
 * (`directory`, null = memory only). The disk part survives restarts; both are bounded.
 */
class LruSpeechCache(
    private val memoryBudget: Long = 3L * 1024 * 1024,
    private val directory: File? = null,
    private val diskBudget: Long = 24L * 1024 * 1024,
) : SpeechCache {
    private class Entry(val pcm: ShortArray, val sampleRate: Int) {
        val bytes get() = pcm.size * 2L
    }

    private val memory = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {}
    private var memoryBytes = 0L
    /** Disk index in least-recently-used order: id → file size. */
    private val disk = LinkedHashMap<String, Long>()
    private var diskBytes = 0L
    private var hits = 0
    private var misses = 0
    private var lastHitMs = 0.0

    init {
        directory?.let { dir ->
            dir.mkdirs()
            dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }?.sortedBy { it.lastModified() }?.forEach {
                disk[it.name.removeSuffix(EXTENSION)] = it.length(); diskBytes += it.length()
            }
            trimDisk()
        }
    }

    @Synchronized
    override fun get(key: SpeechKey): SpeechAudio? {
        val started = System.nanoTime()
        val entry = memory[key.id] ?: readDisk(key.id)?.also { remember(key.id, it) }
        if (entry == null) { misses++; return null }
        hits++
        lastHitMs = (System.nanoTime() - started) / 1_000_000.0
        return SpeechAudio(
            FloatArray(entry.pcm.size) { entry.pcm[it] / 32768f }, entry.sampleRate, lastHitMs, cached = true,
        )
    }

    @Synchronized
    override fun contains(key: SpeechKey) = key.id in memory || (directory != null && key.id in disk)

    @Synchronized
    override fun put(key: SpeechKey, audio: SpeechAudio) {
        if (audio.samples.isEmpty()) return
        val entry = Entry(ShortArray(audio.samples.size) { (audio.samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }, audio.sampleRate)
        remember(key.id, entry)
        writeDisk(key.id, entry)
    }

    @Synchronized
    override fun stats() = SpeechCache.Stats(hits, misses, memoryBytes, diskBytes, lastHitMs)

    private fun remember(id: String, entry: Entry) {
        if (entry.bytes > memoryBudget) return
        memory.put(id, entry)?.let { memoryBytes -= it.bytes }
        memoryBytes += entry.bytes
        val iterator = memory.entries.iterator()
        while (memoryBytes > memoryBudget && iterator.hasNext()) {
            val oldest = iterator.next()
            if (oldest.key == id) continue
            memoryBytes -= oldest.value.bytes
            iterator.remove()
        }
    }

    private fun file(id: String) = File(directory, id + EXTENSION)

    private fun readDisk(id: String): Entry? {
        if (directory == null || id !in disk) return null
        return runCatching {
            val bytes = file(id).readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(buffer.int == MAGIC)
            val rate = buffer.int
            val pcm = ShortArray(buffer.int)
            buffer.asShortBuffer().get(pcm)
            disk[id] = disk.remove(id)!! // most recently used
            file(id).setLastModified(System.currentTimeMillis())
            Entry(pcm, rate)
        }.getOrElse { forget(id); null }
    }

    private fun writeDisk(id: String, entry: Entry) {
        if (directory == null) return
        runCatching {
            val buffer = ByteBuffer.allocate(12 + entry.pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(MAGIC).putInt(entry.sampleRate).putInt(entry.pcm.size)
            buffer.asShortBuffer().put(entry.pcm)
            val target = file(id)
            val partial = File(directory, "$id.partial")
            partial.writeBytes(buffer.array())
            if (!partial.renameTo(target)) { target.delete(); partial.renameTo(target) }
            diskBytes -= disk.remove(id) ?: 0L
            disk[id] = target.length(); diskBytes += target.length()
            trimDisk()
        }
    }

    private fun trimDisk() {
        val iterator = disk.entries.iterator()
        while (diskBytes > diskBudget && iterator.hasNext()) {
            val oldest = iterator.next()
            diskBytes -= oldest.value
            iterator.remove()
            file(oldest.key).delete()
        }
    }

    private fun forget(id: String) {
        diskBytes -= disk.remove(id) ?: 0L
        file(id).delete()
    }

    private companion object {
        const val EXTENSION = ".pcm"
        const val MAGIC = 0x44535031 // "DSP1"
    }
}
