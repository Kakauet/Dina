package com.kakauet.dina.brain.dina45

import com.kakauet.dina.dialog.Action
import com.kakauet.dina.dialog.Arg
import com.kakauet.dina.dialog.ArgType
import com.kakauet.dina.dialog.Clock
import com.kakauet.dina.dialog.Day
import com.kakauet.dina.dialog.Delta
import com.kakauet.dina.dialog.Durations
import com.kakauet.dina.dialog.Fraction
import com.kakauet.dina.dialog.Missing
import com.kakauet.dina.dialog.NoRepeat
import com.kakauet.dina.dialog.Ops
import com.kakauet.dina.dialog.Period
import com.kakauet.dina.dialog.Ref
import com.kakauet.dina.dialog.Shift
import com.kakauet.dina.tools.Repeat
import java.time.DayOfWeek

/**
 * The Dina 4.5 wire format (docs/contrato.md): one action per line, `dominio.op(objetivo, valor, clave=valor…)`. Positional values are recognised by their type,
 * so the reader is lenient about order; [encode] always writes the canonical order of [Ops].
 */
object Dina45Codec {
    data class Decoded(val actions: List<Action>, val invalidLines: Int)

    fun encode(actions: List<Action>): String = actions.joinToString("\n") { it.encode() }

    fun decode(text: String): Decoded {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val actions = lines.mapNotNull(::decodeLine)
        return Decoded(actions, lines.size - actions.size)
    }

    private val LINE = Regex("^([a-z]+(?:\\.[a-z]+)?)\\((.*)\\)$")

    /** One line, or null if it is not a valid action of the contract. */
    fun decodeLine(line: String): Action? {
        val match = LINE.matchEntire(line.trim()) ?: return null
        val spec = Ops.byName[match.groupValues[1]] ?: return null
        val tokens = split(match.groupValues[2]) ?: return null
        var target: Ref? = null
        val args = mutableMapOf<String, Any>()
        val free = spec.args.filter { !it.named }.toMutableList()
        for (token in tokens) {
            val named = Regex("^([a-z]+)=(.*)$").matchEntire(token)?.takeIf { m -> spec.args.any { it.named && it.key == m.groupValues[1] } }
            if (named != null) {
                val arg = spec.args.first { it.named && it.key == named.groupValues[1] }
                if (arg.key in args) return null
                args[arg.key] = value(named.groupValues[2], arg, spec.domain) ?: return null
                continue
            }
            val slot = free.firstOrNull { arg -> parse(token, arg, spec.domain) != null } ?: return null
            free.remove(slot)
            val value = parse(token, slot, spec.domain)!!
            if (slot.type == ArgType.TARGET) target = value as Ref else args[slot.key] = value
        }
        return Action(spec.op, target, args)
    }

    /** Named values may be `?` (the user wants to change it but did not say to what). */
    private fun value(text: String, arg: Arg, domain: String): Any? = if (text == "?") Missing else parse(text, arg, domain)

    private fun parse(text: String, arg: Arg, domain: String): Any? = when (arg.type) {
        ArgType.TARGET -> target(text, domain)
        ArgType.CLOCK -> clock(text)
        ArgType.DAY -> Day.parse(text)
        ArgType.REPEAT -> repeat(text)
        ArgType.TEXT -> Regex("^\"([^\"]+)\"$").matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        ArgType.DURATION -> Durations.parse(text)
        ArgType.AT -> clock(text) ?: Regex("^([+-])(.+)$").matchEntire(text)?.let { m -> Durations.parse(m.groupValues[2])?.let { Shift(if (m.groupValues[1] == "-") -it else it) } }
        ArgType.NUMBER -> Regex("^([+-])?(\\d+(?:\\.\\d+)?)$").matchEntire(text)?.let { m ->
            val value = m.groupValues[2].toDouble()
            when (m.groupValues[1]) { "+" -> Delta(value); "-" -> Delta(-value); else -> value }
        }
        ArgType.INT -> Regex("^\\d{1,3}$").matchEntire(text)?.value?.toInt()
        ArgType.UNIT -> text.takeIf { it in Ops.UNITS }
        ArgType.PART -> text.takeIf { it in Ops.PARTS }
        ArgType.TOPIC -> text.takeIf { it in Ops.TOPICS }
        ArgType.AMOUNT -> Regex("^(-?\\d+(?:\\.\\d+)?)(?:/(\\d+))?$").matchEntire(text)?.let { m ->
            val denominator = m.groupValues[2]
            when {
                denominator.isEmpty() -> m.groupValues[1].toDouble()
                '.' in m.groupValues[1] || denominator.toInt() == 0 -> null
                else -> Fraction(m.groupValues[1].toInt(), denominator.toInt())
            }
        }
        ArgType.MEASURE -> text.takeIf { it in Ops.MEASURES }
    }

    private fun target(text: String, domain: String): Ref? = when {
        text == "@" -> Ref.Focus
        text == "*" -> Ref.All
        text == "sonando" -> Ref.Ringing
        text == "#próximo" || text == "#próxima" -> Ref.Next
        text == "#último" || text == "#última" -> Ref.Last
        Regex("^#\\d{1,2}$").matches(text) -> text.drop(1).toInt().takeIf { it > 0 }?.let { Ref.Nth(it) }
        text.startsWith("\"") -> Regex("^\"([^\"]+)\"$").matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { Ref.Named(it) }
        domain == "alarm" -> clock(text)?.let { Ref.At(it) } ?: Period.of(text)?.let { Ref.InPeriod(it) }
        else -> null
    }

    private fun clock(text: String): Clock? {
        val m = Regex("^(\\d{1,2}):(\\d{2})(?: (mañana|tarde|noche))?$").matchEntire(text) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        return Clock(hour, minute, m.groupValues[3].takeIf { it.isNotEmpty() }?.let { Period.of(it) })
    }

    private fun repeat(text: String): Any? = when (text) {
        "diario" -> Repeat.Daily
        "laborables" -> Repeat.Weekdays
        "finde" -> Repeat.Weekends
        "no" -> NoRepeat
        else -> text.split(',').map { Ops.REPEAT_DAYS.indexOf(it) }.takeIf { days -> days.isNotEmpty() && days.all { it >= 0 } }
            ?.let { days -> Repeat.Weekly(days.distinct().sorted().map { DayOfWeek.of(it + 1) }) }
    }

    /** Splits on ", " outside quotes (a bare comma joins repeat days: `lun,mie`); null on an unclosed quote. */
    private fun split(text: String): List<String>? {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for ((i, c) in text.withIndex()) {
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == ',' && !quoted && text.getOrNull(i + 1) == ' ' -> { out += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        if (quoted) return null
        out += current.toString().trim()
        return out.filter { it.isNotEmpty() }
    }
}
