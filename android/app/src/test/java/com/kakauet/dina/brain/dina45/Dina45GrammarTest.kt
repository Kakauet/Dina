package com.kakauet.dina.brain.dina45

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GBNF accepts everything the codec writes and nothing else (checked by turning it into a regex). */
class Dina45GrammarTest {
    private val regex by lazy { Regex(Gbnf.toRegex(Dina45Grammar.GBNF)) }

    @Test
    fun acceptsEveryCanonicalLineAndMultiLineOutputs() {
        val examples = Dina45CodecTest().examples
        examples.forEach { assertTrue(it.encode(), regex.matches(it.encode())) }
        assertTrue(regex.matches(Dina45Codec.encode(examples.take(4))))
        assertTrue(regex.matches("alarm.add(day=viernes)"))
        assertTrue(regex.matches("alarm.add()"))
        assertTrue(regex.matches("alarm.edit(@, at=?)"))
        assertTrue(regex.matches("timer.add(0s)"))
    }

    @Test
    fun rejectsOtherShapes() {
        listOf(
            "alarm.add(day=lunes, 7:00)", "alarm.add(7)", "foo.bar()", "say(hola)", "alarm.add(7:00,day=lunes)",
            "timer.pause(7:00)", "alarm.add(7:00 tarde mañana)", "vol.set(1000)", "alarm.add(label=?)", "",
            "alarm.list()\n", "{\"tool_calls\":[]}",
        ).forEach { assertFalse(it, regex.matches(it)) }
    }
}

/** A small GBNF reader for tests: inlines the (non-recursive) rules into one regular expression. */
object Gbnf {
    fun toRegex(grammar: String): String {
        val rules = grammar.lines().filter { it.isNotBlank() }.associate { line ->
            line.substringBefore(" ::= ").trim() to line.substringAfter(" ::= ").trim()
        }
        val cache = mutableMapOf<String, String>()
        fun rule(name: String): String = cache.getOrPut(name) { Parser(rules.getValue(name), ::rule).expression() }
        return rule("root")
    }

    private class Parser(private val text: String, private val rule: (String) -> String) {
        private var i = 0

        fun expression(): String {
            val alternatives = mutableListOf(sequence())
            while (peek() == '|') { i++; alternatives += sequence() }
            return if (alternatives.size == 1) alternatives.single() else alternatives.joinToString("|", "(?:", ")")
        }

        private fun sequence(): String = buildString {
            while (true) {
                skip()
                if (i >= text.length || text[i] == '|' || text[i] == ')') break
                var atom = atom()
                if (i < text.length && text[i] in "?*+") atom = "(?:$atom)${text[i++]}"
                append(atom)
            }
        }

        private fun atom(): String = when (text[i]) {
            '"' -> literal()
            '[' -> charClass()
            '(' -> { i++; val inner = expression(); skip(); check(text[i] == ')'); i++; "(?:$inner)" }
            else -> { val start = i; while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '-')) i++; "(?:${rule(text.substring(start, i))})" }
        }

        private fun literal(): String {
            i++
            val out = StringBuilder()
            while (text[i] != '"') {
                if (text[i] == '\\') { i++; out.append(when (text[i]) { 'n' -> '\n'; else -> text[i] }) } else out.append(text[i])
                i++
            }
            i++
            return Regex.escape(out.toString())
        }

        private fun charClass(): String {
            val start = i
            while (text[i] != ']') { if (text[i] == '\\') i++; i++ }
            i++
            return text.substring(start, i)
        }

        private fun peek(): Char? { skip(); return text.getOrNull(i) }
        private fun skip() { while (i < text.length && text[i] == ' ') i++ }
    }
}
