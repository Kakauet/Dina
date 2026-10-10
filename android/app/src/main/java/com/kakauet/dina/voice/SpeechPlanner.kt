package com.kakauet.dina.voice

/** One piece of an answer for the voice: what to say, and the silence to leave before the next piece. */
data class SpeechChunk(val text: String, val pauseAfterMs: Int)

/**
 * Turns an answer into the pieces the voice synthesizes one by one: sentences, with the first one
 * cut at its first comma or colon when it is long, so the first sound does not wait for the whole sentence.
 * Each piece carries the pause that follows it, which depends on its punctuation.
 *
 * A cut is only made when it leaves no gap: the rest must be synthesized while the head plays
 * (`rtf · tail seconds ≤ head seconds + pause`, with the speed of speech and the real-time factor of the
 * voice as estimates), otherwise the listener would hear a hole between the two halves. Short sentences are never cut.
 */
object SpeechPlanner {
    data class Config(
        val splitFirst: Boolean = true,
        /** Only a first sentence longer than this many characters (as spoken, numbers in words) is cut. */
        val splitFirstAbove: Int = 70,
        val minHead: Int = 18,
        val minTail: Int = 24,
        /** Speech rate of Supertonic at speed 1.05 once the silence is cut: median 19.8 (15.8 to 25.3) characters per second on 17 answers; a little under, to stay on the safe side. */
        val charsPerSecond: Double = 17.0,
        val pauseSentenceMs: Int = 220,
        val pauseQuestionMs: Int = 260,
        val pauseExclamationMs: Int = 200,
        val pauseEllipsisMs: Int = 300,
        val pauseColonMs: Int = 160,
        val pauseSemicolonMs: Int = 120,
        val pauseCommaMs: Int = 80,
    ) {
        val signature get() = "plan:$splitFirst/$splitFirstAbove/$minHead/$minTail"
    }

    /** Synthesis seconds per audio second assumed until the voice has been measured on this phone. */
    const val DEFAULT_RTF = 0.35

    fun plan(text: String, rtf: Double = DEFAULT_RTF, config: Config = Config()): List<SpeechChunk> {
        val sentences = SpanishSpeech.sentences(SpanishSpeech.expand(text))
        if (sentences.isEmpty()) return emptyList()
        val chunks = ArrayList<SpeechChunk>(sentences.size + 1)
        sentences.forEachIndexed { index, sentence ->
            val parts = if (index == 0 && config.splitFirst) splitFirst(sentence, rtf, config) else listOf(sentence)
            parts.forEach { chunks += SpeechChunk(it, pauseAfter(it, config)) }
        }
        chunks[chunks.lastIndex] = chunks.last().copy(pauseAfterMs = 0)
        return chunks
    }

    /** The silence after a piece that ends with [text]'s last mark. */
    fun pauseAfter(text: String, config: Config = Config()): Int {
        val mark = text.trimEnd().trimEnd('"', '\'', ')', '»', '”', ']').lastOrNull()
        return when (mark) {
            '?' -> config.pauseQuestionMs
            '!' -> config.pauseExclamationMs
            '…' -> config.pauseEllipsisMs
            ':' -> config.pauseColonMs
            ';' -> config.pauseSemicolonMs
            ',' -> config.pauseCommaMs
            else -> config.pauseSentenceMs
        }
    }

    /** [sentence] cut at its first comma or colon that leaves a gap-free pair, or whole. */
    private fun splitFirst(sentence: String, rtf: Double, config: Config): List<String> {
        if (sentence.length <= config.splitFirstAbove) return listOf(sentence)
        for (i in sentence.indices) {
            val c = sentence[i]
            if ((c != ',' && c != ':') || i + 1 >= sentence.length || !sentence[i + 1].isWhitespace()) continue
            val head = sentence.substring(0, i + 1).trim()
            val tail = sentence.substring(i + 1).trim()
            if (head.length < config.minHead) continue
            if (tail.length < config.minTail) break // later cuts only leave less
            val headSeconds = head.length / config.charsPerSecond
            val tailSeconds = tail.length / config.charsPerSecond
            if (rtf * tailSeconds <= headSeconds + pauseAfter(head, config) / 1_000.0) return listOf(head, tail)
        }
        return listOf(sentence)
    }
}
