package com.kakauet.dina.brain.dina45

import com.kakauet.dina.dialog.Arg
import com.kakauet.dina.dialog.ArgType
import com.kakauet.dina.dialog.Day
import com.kakauet.dina.dialog.OpSpec
import com.kakauet.dina.dialog.Ops
import com.kakauet.dina.dialog.Period

/**
 * GBNF for llama.cpp generated from the table of [Ops]: the model can only write lines that
 * [Dina45Codec] reads. Arguments go in canonical order and any of them may be left out (a partial
 * action is how the model says a value is missing); edits also take `?` for a value not said.
 */
object Dina45Grammar {
    val GBNF: String by lazy { build() }

    private fun rule(op: String) = op.replace('.', '-').let { if (it == "no") "no-op" else it }

    private fun build(): String = buildString {
        appendLine("root ::= line (\"\\n\" line)*")
        appendLine("line ::= " + Ops.all.joinToString(" | ") { rule(it.op) })
        Ops.all.forEach { appendLine("${rule(it.op)} ::= ${op(it)}") }
        appendLine("clock ::= [0-9] [0-9]? \":\" [0-5] [0-9] (\" \" period)?")
        appendLine("period ::= " + alternatives(Period.entries.map { it.word }))
        appendLine("day ::= " + alternatives(listOf("hoy", "mañana", "pasado", "ayer") + Day.WEEKDAYS + Day.HOLIDAYS.keys) + " | [0-9] [0-9]? (\"/\" [0-9] [0-9]? (\"/\" [0-9] [0-9] [0-9] [0-9])?)? | \"+\" [0-9] [0-9]? [0-9]?")
        appendLine("repeat ::= \"diario\" | \"laborables\" | \"finde\" | \"no\" | rday (\",\" rday)*")
        appendLine("rday ::= " + alternatives(Ops.REPEAT_DAYS))
        appendLine("text ::= \"\\\"\" [^\"\\n] [^\"\\n]* \"\\\"\"")
        appendLine("dur ::= [0-9]+ (\"h\" ([0-9]+ \"m\")? ([0-9]+ \"s\")? | \"m\" ([0-9]+ \"s\")? | \"s\")")
        appendLine("at ::= clock | [+-] dur")
        appendLine("number ::= [+-]? [0-9]+ (\".\" [0-9]+)?")
        appendLine("int ::= [0-9] [0-9]? [0-9]?")
        appendLine("unit ::= " + alternatives(Ops.UNITS))
        appendLine("part ::= " + alternatives(Ops.PARTS))
        appendLine("topic ::= " + alternatives(Ops.TOPICS))
        appendLine("amount ::= \"-\"? [0-9]+ (\".\" [0-9]+)? (\"/\" [0-9]+)?")
        appendLine("measure ::= " + alternatives(Ops.MEASURES))
        appendLine("ref ::= \"@\" | \"*\" | \"sonando\" | \"#próximo\" | \"#próxima\" | \"#último\" | \"#última\" | \"#\" [1-9] [0-9]? | text")
        appendLine("alarm-ref ::= ref | clock | period")
    }

    /** `"alarm.add(" (a (", " b)? | b)? ")"`: any ordered subset of the arguments. */
    private fun op(spec: OpSpec): String {
        val args = spec.args.map { arg(it, spec) }
        if (args.isEmpty()) return literal("${spec.op}()")
        val subsets = args.indices.joinToString(" | ") { i -> args[i] + args.drop(i + 1).joinToString("") { " (\", \" $it)?" } }
        return "${literal("${spec.op}(")} ($subsets)? ${literal(")")}"
    }

    private fun arg(arg: Arg, spec: OpSpec): String {
        val value = when (arg.type) {
            ArgType.TARGET -> if (spec.domain == "alarm") "alarm-ref" else "ref"
            ArgType.CLOCK -> "clock"
            ArgType.DAY -> "day"
            ArgType.REPEAT -> "repeat"
            ArgType.TEXT -> "text"
            ArgType.DURATION -> "dur"
            ArgType.AT -> "at"
            ArgType.NUMBER -> "number"
            ArgType.INT -> "int"
            ArgType.UNIT -> "unit"
            ArgType.PART -> "part"
            ArgType.TOPIC -> "topic"
            ArgType.AMOUNT -> "amount"
            ArgType.MEASURE -> "measure"
        }
        if (!arg.named) return value
        val missing = if (spec.op.endsWith(".edit")) " | \"?\"" else ""
        return "(${literal("${arg.key}=")} ($value$missing))"
    }

    private fun alternatives(words: List<String>) = words.joinToString(" | ") { literal(it) }
    private fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
