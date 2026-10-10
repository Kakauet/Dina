package com.kakauet.dina.dialog

import com.kakauet.dina.tools.Repeat
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Canonical intents of Dina 4.5 (docs/contrato.md): what the user asked, as the model read it,
 * before anything is resolved. Model-independent: a brain's codec turns its own output into
 * [Action]s and the [Dialogue] decides and executes.
 */

/** Part of the day said next to an hour or alone as a reference ("la de la tarde"). */
enum class Period(val word: String) {
    MORNING("mañana"), AFTERNOON("tarde"), NIGHT("noche");

    /** Ranges overlap at 20:00, which people call both "tarde" and "noche". */
    fun contains(time: LocalTime): Boolean = when (this) {
        MORNING -> time.hour in 4..11
        AFTERNOON -> time.hour in 12..20
        NIGHT -> time.hour >= 20 || time.hour < 4
    }

    companion object { fun of(word: String) = entries.firstOrNull { it.word == word } }
}

/** A clock time as said: `7:00` (morning or evening not said), `7:00 tarde`, `19:00`. */
data class Clock(val hour: Int, val minute: Int, val period: Period? = null) {
    init { require(hour in 0..23 && minute in 0..59) { "bad clock $hour:$minute" } }

    /** An hour from 1 to 11 without a period: the ampm policy decides. */
    val ambiguous get() = period == null && hour in 1..11

    /** The 24 h times it can mean: one, or two when [ambiguous]. */
    fun times(): List<LocalTime> {
        val h = when (period) {
            null -> return if (ambiguous) listOf(LocalTime.of(hour, minute), LocalTime.of(hour + 12, minute)) else listOf(LocalTime.of(hour, minute))
            Period.MORNING -> if (hour == 12) 0 else hour
            Period.AFTERNOON -> if (hour in 1..11) hour + 12 else hour
            Period.NIGHT -> when (hour) { 12 -> 0; in 7..11 -> hour + 12; else -> hour }
        }
        return listOf(LocalTime.of(h, minute))
    }

    fun encode() = "%d:%02d".format(hour, minute) + (period?.let { " ${it.word}" } ?: "")

    companion object {
        /** The unambiguous form of a 24 h time: `7:00 mañana`, `19:00`. */
        fun exact(time: LocalTime) = Clock(time.hour, time.minute, if (time.hour in 1..11) Period.MORNING else null)
    }
}

/**
 * A day as said: relative, a weekday, a date (maybe impossible, like 31/02), "+N", a day of the
 * month alone ("el 25": the next one) or a holiday with a fixed date ("navidad").
 */
sealed interface Day {
    data object Today : Day
    data object Tomorrow : Day
    data object AfterTomorrow : Day
    data object Yesterday : Day
    data class Weekday(val day: DayOfWeek) : Day
    data class Date(val day: Int, val month: Int, val year: Int? = null) : Day
    data class Plus(val days: Int) : Day
    data class MonthDay(val day: Int) : Day
    data class Named(val name: String) : Day

    /** The date it names from [today]; weekdays and dates without year are the next ones. Null if it does not exist. */
    fun resolve(today: LocalDate): LocalDate? = when (this) {
        Today -> today
        Tomorrow -> today.plusDays(1)
        AfterTomorrow -> today.plusDays(2)
        Yesterday -> today.minusDays(1)
        is Plus -> today.plusDays(days.toLong())
        is Weekday -> today.plusDays(((day.value - today.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }.toLong())
        is Date -> try {
            if (year != null) LocalDate.of(year, month, day) else {
                val thisYear = LocalDate.of(today.year, month, day)
                if (thisYear.isBefore(today)) LocalDate.of(today.year + 1, month, day) else thisYear
            }
        } catch (_: DateTimeException) {
            // 29/02 of a common year may exist the next leap year; anything else does not exist.
            if (year == null && month == 2 && day == 29) generateSequence(today.year) { it + 1 }.take(5).firstNotNullOfOrNull { y ->
                runCatching { LocalDate.of(y, 2, 29) }.getOrNull()?.takeIf { !it.isBefore(today) }
            } else null
        }
        // Today if it is that day; months without it are skipped ("el 31" in November is 31 December).
        is MonthDay -> generateSequence(today) { it.plusDays(1) }.take(400).first { it.dayOfMonth == day }
        is Named -> HOLIDAYS.getValue(name).let { (d, m) -> Date(d, m).resolve(today) }
    }

    fun encode(): String = when (this) {
        Today -> "hoy"
        Tomorrow -> "mañana"
        AfterTomorrow -> "pasado"
        Yesterday -> "ayer"
        is Weekday -> WEEKDAYS[day.value - 1]
        is Date -> "$day/$month" + (year?.let { "/$it" } ?: "")
        is Plus -> "+$days"
        is MonthDay -> "$day"
        is Named -> name
    }

    companion object {
        val WEEKDAYS = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

        /** Holidays with a fixed date (day, month), as the contract writes them. Only ever appended. */
        val HOLIDAYS: Map<String, Pair<Int, Int>> = linkedMapOf(
            "navidad" to (25 to 12), "nochebuena" to (24 to 12), "nochevieja" to (31 to 12), "año nuevo" to (1 to 1),
            "reyes" to (6 to 1), "san valentín" to (14 to 2), "san juan" to (24 to 6), "halloween" to (31 to 10),
        )

        fun parse(text: String): Day? = when (text) {
            "hoy" -> Today
            "mañana" -> Tomorrow
            "pasado" -> AfterTomorrow
            "ayer" -> Yesterday
            in WEEKDAYS -> Weekday(DayOfWeek.of(WEEKDAYS.indexOf(text) + 1))
            in HOLIDAYS -> Named(text)
            else -> Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?$").matchEntire(text)?.let { m ->
                Date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull())
            } ?: Regex("^\\+(\\d{1,3})$").matchEntire(text)?.let { Plus(it.groupValues[1].toInt()) }
                ?: Regex("^\\d{1,2}$").matchEntire(text)?.value?.toInt()?.takeIf { it in 1..31 }?.let { MonthDay(it) }
        }

        fun of(date: LocalDate, today: LocalDate): Day = when (date) {
            today -> Today
            today.plusDays(1) -> Tomorrow
            today.plusDays(2) -> AfterTomorrow
            else -> Date(date.dayOfMonth, date.monthValue).let { if (it.resolve(today) == date) it else it.copy(year = date.year) }
        }
    }
}

/** A relative change of an alarm's time: `at=+15m`, `at=-30m`. */
data class Shift(val ms: Long) {
    fun encode() = (if (ms < 0) "-" else "+") + Durations.encode(kotlin.math.abs(ms))
}

/** `?`: the user wants to set this but did not say the value ("cámbiale el nombre"). */
data object Missing

/** An amount said as a fraction: `1/3` ("un tercio de taza"). Other amounts are Doubles. */
data class Fraction(val numerator: Int, val denominator: Int) {
    val value: Double get() = numerator.toDouble() / denominator
    fun encode() = "$numerator/$denominator"
}

/** How the user referred to an existing item (the "objetivo"). Resolved only by [Resolver]. */
sealed interface Ref {
    /** `@`: "esa", "cámbiala": the item in focus. */
    data object Focus : Ref
    /** `*`: all of them (or all the candidates just offered). */
    data object All : Ref
    /** `sonando`: the one ringing. */
    data object Ringing : Ref
    /** `#próximo`: the one that rings or ends first. */
    data object Next : Ref
    /** `#último`: the last one, as read or as created. */
    data object Last : Ref
    /** `#2`: position, as read or as created. */
    data class Nth(val n: Int) : Ref
    /** `"gimnasio"`: label or product name. */
    data class Named(val text: String) : Ref
    /** `7:00`, `7:00 tarde`: an alarm by its time. */
    data class At(val clock: Clock) : Ref
    /** `tarde`: an alarm by part of the day. */
    data class InPeriod(val period: Period) : Ref

    fun encode(): String = when (this) {
        Focus -> "@"
        All -> "*"
        Ringing -> "sonando"
        Next -> "#próximo"
        Last -> "#último"
        is Nth -> "#$n"
        is Named -> Texts.quote(text)
        is At -> clock.encode()
        is InPeriod -> period.word
    }
}

/**
 * One canonical action: `alarm.edit(@, at=8:00)`. [args] holds typed values keyed by the names
 * of [Ops]: [Clock], [Day], [com.kakauet.dina.tools.Repeat] or [NoRepeat], [Shift], durations as Long ms,
 * numbers as Double, volume as Int, texts as String, or [Missing].
 */
data class Action(val op: String, val target: Ref? = null, val args: Map<String, Any> = emptyMap()) {
    val domain: String get() = op.substringBefore('.', "")
    val spec: OpSpec get() = Ops.byName.getValue(op)

    fun clock(key: String = "time") = args[key] as? Clock
    fun day(key: String = "day") = args[key] as? Day
    fun duration(key: String = "dur") = args[key] as? Long
    fun text(key: String) = args[key] as? String
    fun number(key: String = "n") = args[key] as? Double
    /** A plain or fractional amount (`conv`). */
    fun amount(key: String = "n") = (args[key] as? Fraction)?.value ?: number(key)
    fun int(key: String = "n") = args[key] as? Int
    fun missing(key: String) = args[key] == Missing

    fun with(key: String, value: Any?) = copy(args = if (value == null) args - key else args + (key to value))

    /** Canonical text, arguments in the order of [Ops]: `alarm.add(7:00 mañana, day=lunes, "trabajo")`. */
    fun encode(): String = op + "(" + spec.args.mapNotNull { arg ->
        val value = if (arg.type == ArgType.TARGET) target?.encode() else args[arg.key]?.let { if (arg.type == ArgType.TEXT && it is String) Texts.quote(it) else encodeValue(it) }
        value?.let { if (arg.named) "${arg.key}=$it" else it }
    }.joinToString(", ") + ")"

    override fun toString() = op + "(" + (listOfNotNull(target?.encode()) + args.map { "${it.key}=${it.value}" }).joinToString(", ") + ")"
}

/** `repeat=no`: an alarm that no longer repeats. */
data object NoRepeat

fun encodeValue(value: Any): String = when (value) {
    is Clock -> value.encode()
    is Day -> value.encode()
    is Shift -> value.encode()
    is Long -> Durations.encode(value)
    is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is Delta -> (if (value.value < 0) "-" else "+") + encodeValue(kotlin.math.abs(value.value))
    is Fraction -> value.encode()
    is Int -> value.toString()
    is Repeat -> when (value) {
        Repeat.Daily -> "diario"
        Repeat.Weekdays -> "laborables"
        Repeat.Weekends -> "finde"
        is Repeat.Weekly -> value.days.joinToString(",") { Ops.REPEAT_DAYS[it.value - 1] }
    }
    NoRepeat -> "no"
    Missing -> "?"
    is String -> value
    else -> value.toString()
}

/** [AMOUNT]: `3.5`, `-10`, `1/3` (conversions); [MEASURE]: a unit of [com.kakauet.dina.tools.Units]. */
enum class ArgType { TARGET, CLOCK, DAY, REPEAT, TEXT, DURATION, AT, NUMBER, INT, UNIT, PART, TOPIC, AMOUNT, MEASURE }

/** [named] arguments are written `key=value`; the others by position, recognised by their type. */
data class Arg(val key: String, val type: ArgType, val named: Boolean)

data class OpSpec(val op: String, val args: List<Arg>, val required: String? = null) {
    val domain get() = op.substringBefore('.', "")
}

/** The contract's table of operations, shared by codecs, grammar, prompt and [Dialogue]. */
object Ops {
    private fun pos(key: String, type: ArgType) = Arg(key, type, named = false)
    private fun kv(key: String, type: ArgType) = Arg(key, type, named = true)
    private val target = pos("target", ArgType.TARGET)
    private fun op(name: String, vararg args: Arg, required: String? = null) = OpSpec(name, args.toList(), required)

    val all: List<OpSpec> = listOf(
        op("alarm.add", pos("time", ArgType.CLOCK), kv("day", ArgType.DAY), kv("repeat", ArgType.REPEAT), pos("label", ArgType.TEXT), required = "time"),
        op("alarm.edit", target, kv("at", ArgType.AT), kv("day", ArgType.DAY), kv("repeat", ArgType.REPEAT), kv("label", ArgType.TEXT)),
        op("alarm.del", target, kv("day", ArgType.DAY)),
        op("alarm.off", target, kv("day", ArgType.DAY)),
        op("alarm.on", target, kv("day", ArgType.DAY)),
        op("alarm.get", target, kv("day", ArgType.DAY)),
        op("alarm.list"),
        op("alarm.snooze", pos("dur", ArgType.DURATION)),
        op("timer.add", pos("dur", ArgType.DURATION), pos("label", ArgType.TEXT), required = "dur"),
        op("timer.pause", target),
        op("timer.resume", target),
        op("timer.del", target),
        op("timer.get", target),
        op("timer.list"),
        op("timer.plus", target, pos("dur", ArgType.DURATION), required = "dur"),
        op("timer.minus", target, pos("dur", ArgType.DURATION), required = "dur"),
        op("timer.edit", target, kv("left", ArgType.DURATION), kv("label", ArgType.TEXT)),
        op("sw.add", pos("label", ArgType.TEXT)),
        op("sw.pause", target),
        op("sw.resume", target),
        op("sw.reset", target),
        op("sw.restart", target),
        op("sw.del", target),
        op("sw.get", target),
        op("sw.list"),
        op("list.add", pos("name", ArgType.TEXT), kv("n", ArgType.NUMBER), kv("unit", ArgType.UNIT), required = "name"),
        op("list.del", target),
        op("list.check", target),
        op("list.uncheck", target),
        op("list.edit", target, kv("n", ArgType.NUMBER), kv("unit", ArgType.UNIT), kv("name", ArgType.TEXT)),
        op("list.list"),
        op("vol.set", pos("n", ArgType.INT), required = "n"),
        op("vol.up", pos("n", ArgType.INT)),
        op("vol.down", pos("n", ArgType.INT)),
        op("vol.mute"),
        op("vol.unmute"),
        op("vol.get"),
        // A place ("Londres") asks for the time there.
        op("time.now", pos("part", ArgType.PART), pos("place", ArgType.TEXT)),
        op("time.weekday", pos("day", ArgType.DAY), required = "day"),
        op("time.until", pos("day", ArgType.DAY), required = "day"),
        op("calc", pos("expr", ArgType.TEXT), required = "expr"),
        // Units: `conv(3.5, milla, km)`, `conv(2, taza, g, "harina")`.
        op("conv", pos("n", ArgType.AMOUNT), pos("from", ArgType.MEASURE), pos("to", ArgType.MEASURE), pos("what", ArgType.TEXT), required = "n"),
        op("stop"),
        op("ask"),
        op("no", pos("topic", ArgType.TOPIC)),
        op("say", pos("text", ArgType.TEXT)),
        // A joke or a curious fact, picked by the engine from its banks (FunBank).
        op("fun.joke"),
        op("fun.fact"),
        op("undo"),
        op("drop"),
        op("yes"),
        op("nope"),
    )

    val byName: Map<String, OpSpec> = all.associateBy { it.op }

    /** What `hora`, `fecha` and `dia` ask for in `time.now`. */
    val PARTS = listOf("hora", "fecha", "dia")

    val UNITS = listOf("kg", "g", "l", "ml", "docena", "paquete", "botella", "bolsa", "lata", "caja", "bote")

    /** Units of `conv`, in the order of [com.kakauet.dina.tools.Units.ALL] (only ever appended). */
    val MEASURES: List<String> = com.kakauet.dina.tools.Units.CODES

    /** Out-of-scope topics of `no(tema)`; each has its own honest answer in [Responder]. */
    val TOPICS = listOf(
        "música", "llamadas", "mensajes", "internet", "tiempo", "casa", "apps", "fotos", "calendario",
        "compras", "pagos", "móvil", "noticias", "idiomas", "zonas", "fechas", "otro",
    )

    val REPEAT_DAYS = listOf("lun", "mar", "mie", "jue", "vie", "sab", "dom")
}

/** Durations in the contract: `10m`, `1h30m`, `45s`. */
object Durations {
    fun parse(text: String): Long? {
        val m = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(text) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        return (m.groupValues[1].toLongOrNull() ?: 0) * 3_600_000 + (m.groupValues[2].toLongOrNull() ?: 0) * 60_000 + (m.groupValues[3].toLongOrNull() ?: 0) * 1_000
    }

    fun encode(ms: Long): String {
        val seconds = ms / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildString {
            if (h > 0) append("${h}h")
            if (m > 0) append("${m}m")
            if (s > 0 || isEmpty()) append("${s}s")
        }
    }
}

object Texts {
    /** Contract texts are double-quoted and never contain quotes or line breaks. */
    fun quote(text: String) = "\"" + clean(text) + "\""
    fun clean(text: String) = text.replace('"', '\'').replace('\n', ' ').trim()
}
