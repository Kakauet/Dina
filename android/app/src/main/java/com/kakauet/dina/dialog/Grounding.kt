package com.kakauet.dina.dialog

/**
 * Keeps a model from inventing what to note down (docs/motor.md): a `list.add` whose product is
 * a placeholder ("algo") or appears neither in the phrase nor in the previous one ("apunta una
 * cosa" → `list.add("chiste")`) loses it, so Dina asks what to add instead of writing it.
 * Lenient with speech recognition: a word with the same stem or a long common start counts, and so
 * does the whole name inside the phrase without spaces ("le chuga").
 */
object Grounding {
    private val PLACEHOLDERS = setOf("algo", "cosa", "una cosa", "otra cosa", "alguna cosa", "eso", "esto", "lo que te diga", "lo que sea", "lo otro")
    private val STOP = setOf("de", "del", "la", "el", "los", "las", "con", "y", "para", "un", "una", "unos", "unas", "al", "a", "en", "sin", "o", "mi", "mis")

    /** [actions] with every ungrounded product dropped; the first one becomes a question if no product is left. */
    fun products(actions: List<Action>, heard: List<String>): List<Action> {
        val folded = heard.map { SpanishText.fold(it) }
        val words = folded.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
        val compact = folded.joinToString(" ") { it.replace(" ", "") }
        val ungrounded = actions.filter { a -> a.op == "list.add" && a.text("name")?.let { !grounded(it, words, compact) } == true }
        if (ungrounded.isEmpty()) return actions
        val keepsOne = actions.any { it.op == "list.add" && it !in ungrounded }
        return actions.mapNotNull { a ->
            when {
                a !in ungrounded -> a
                !keepsOne && a == ungrounded.first() -> a.with("name", null)
                else -> null
            }
        }
    }

    fun grounded(name: String, words: List<String>, compact: String): Boolean {
        val key = SpanishText.fold(name).trim()
        if (key in PLACEHOLDERS) return false
        val tokens = key.split(' ').filter { it.isNotEmpty() && it !in STOP }
        if (tokens.isEmpty()) return false
        val joined = key.replace(" ", "")
        if (joined.length >= 5 && compact.contains(joined)) return true
        return tokens.any { token -> words.any { same(token, it) } }
    }

    private fun same(a: String, b: String): Boolean {
        if (SpanishText.stem(a) == SpanishText.stem(b)) return true
        val common = a.zip(b).takeWhile { it.first == it.second }.size
        return common >= maxOf(4, minOf(a.length, b.length) - 2)
    }
}
