package com.kakauet.dina.dialog

import java.util.Random

/**
 * Jokes and curious facts for `fun.joke()` and `fun.fact()`: the engine picks them, never the model.
 * The banks are text resources (one line each, written by one model and kept only if a judge of
 * another family approves it; docs/datos.md). Each deck is shuffled and does not repeat a line
 * until it has told them all.
 */
class FunBank(private val jokes: List<String>, private val facts: List<String>, seed: Long = 0) {
    enum class Kind(val file: String) { JOKE("chistes.txt"), FACT("curiosidades.txt") }

    private val random = Random(seed)
    private val decks = mutableMapOf<Kind, ArrayDeque<String>>()
    private val last = mutableMapOf<Kind, String>()

    /** The next line of [kind], or null when its bank is empty. */
    fun next(kind: Kind): String? {
        val all = if (kind == Kind.JOKE) jokes else facts
        if (all.isEmpty()) return null
        val deck = decks.getOrPut(kind) { ArrayDeque() }
        if (deck.isEmpty()) {
            deck.addAll(all.shuffled(random))
            // A new round never starts with the line just told.
            if (deck.size > 1 && deck.first() == last[kind]) deck.addLast(deck.removeFirst())
        }
        return deck.removeFirst().also { last[kind] = it }
    }

    companion object {
        /** Java resources (absolute path, safe from R8 renaming): one line per joke or fact, `#` for comments. */
        private const val DIR = "/dina/fun/"

        private val shipped: Map<Kind, List<String>> by lazy { Kind.entries.associateWith { load(it.file) } }

        /** The banks shipped with the app, with a fresh deck. */
        fun shipped(seed: Long = 0) = FunBank(shipped.getValue(Kind.JOKE), shipped.getValue(Kind.FACT), seed)

        private fun load(name: String): List<String> =
            FunBank::class.java.getResourceAsStream(DIR + name)?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }.orEmpty()
    }
}
