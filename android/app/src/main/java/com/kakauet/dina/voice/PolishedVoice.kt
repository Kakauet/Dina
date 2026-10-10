package com.kakauet.dina.voice

/**
 * A voice with the finishing the app wants on every sentence: the silence the model leaves around it is cut
 * ([SilenceTrim]) and the result is kept in a [SpeechCache], so a sentence Dina has said before costs a
 * read instead of a synthesis. Both stages are optional (null) to compare against the bare voice.
 *
 * One sentence at a time, like the voices it wraps: [synthesize] and [prewarm] share a lock.
 */
class PolishedVoice(
    val inner: SpeechVoice,
    private val trim: SilenceTrim.Config? = SilenceTrim.Config(),
    private val cache: SpeechCache? = null,
) : SpeechVoice {
    override val label get() = inner.label
    override val sampleRate get() = inner.sampleRate
    override val initMs get() = inner.initMs
    override val warmupMs get() = inner.warmupMs
    override val description get() = inner.description + if (trim == null) "" else " · recorte" + if (cache == null) "" else " · caché"
    override val signature get() = inner.signature + "|" + (trim?.signature ?: "untrimmed")

    private val lock = Any()

    val cacheStats get() = cache?.stats()

    override fun synthesize(text: String): SpeechAudio = synchronized(lock) {
        val spoken = SpanishSpeech.expand(text).trim()
        val key = SpeechKey(spoken, signature)
        cache?.get(key)?.let { return it }
        val audio = inner.synthesize(spoken)
        val finished = if (trim == null || audio.samples.isEmpty()) audio else audio.copy(
            samples = SilenceTrim.trim(audio.samples, audio.sampleRate, trim),
            trimmedLeadMs = SilenceTrim.leadingSilenceMs(audio.samples, audio.sampleRate, trim),
        )
        cache?.put(key, finished)
        finished
    }

    /**
     * Synthesizes the pieces of [phrases] ([SpeechPlanner], as they would be spoken) that are not cached yet (the short answers Dina gives all the time), one at a time,
     * until [shouldStop] says a turn needs the voice. Returns how many it made.
     */
    fun prewarm(phrases: List<String>, shouldStop: () -> Boolean): Int {
        val store = cache ?: return 0
        var made = 0
        for (chunk in phrases.flatMap { SpeechPlanner.plan(it) }) {
            if (shouldStop()) break
            if (store.contains(SpeechKey(chunk.text, signature))) continue
            synthesize(chunk.text)
            made++
        }
        return made
    }

    /** Waits for a sentence in progress (a background prewarm) before closing the voice under it. */
    override fun close() = synchronized(lock) { inner.close() }
}
