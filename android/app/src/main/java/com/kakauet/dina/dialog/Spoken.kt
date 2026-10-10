package com.kakauet.dina.dialog

import com.kakauet.dina.tools.Alarm
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.DayCodes
import com.kakauet.dina.tools.Dimension
import com.kakauet.dina.tools.MeasureUnit
import com.kakauet.dina.tools.Repeat
import com.kakauet.dina.tools.ShoppingItem
import com.kakauet.dina.tools.Stopwatch
import com.kakauet.dina.tools.StopwatchStatus
import com.kakauet.dina.tools.Timer
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolPresentation
import com.kakauet.dina.tools.Units
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil

/** Spanish wording of values: days relative to the clock, 24 h times, durations, items. */
class Spoken(val nowMs: Long, val zone: ZoneId) {
    val today: LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    fun hm(time: LocalTime) = "%d:%02d".format(time.hour, time.minute)

    /** "a las 7:15", "a la 1:05". */
    fun atTime(time: LocalTime) = (if (time.hour == 1) "a la " else "a las ") + hm(time)

    /** "hoy", "mañana", "pasado mañana", "el sábado", "el jueves 29 de octubre". */
    fun day(date: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "hoy"
        1L -> "mañana"
        2L -> "pasado mañana"
        in 3L..6L -> "el ${weekday(date.dayOfWeek)}"
        else -> "el ${weekday(date.dayOfWeek)} ${date(date)}"
    }

    /** "25 de diciembre", with the year only when it is not this one. */
    fun date(date: LocalDate) = "${date.dayOfMonth} de ${MONTHS[date.monthValue - 1]}" + if (date.year != today.year) " de ${date.year}" else ""

    fun weekday(day: DayOfWeek) = Day.WEEKDAYS[day.value - 1]

    /** "mañana a las 7:15". */
    fun whenText(ms: Long): String {
        val at = Instant.ofEpochMilli(ms).atZone(zone)
        return "${day(at.toLocalDate())} ${atTime(at.toLocalTime())}"
    }

    fun alarmWhen(alarm: Alarm): String {
        val at = Instant.ofEpochMilli(alarm.nextMs).atZone(zone)
        val repeat = alarm.repeat
        return if (repeat == null) whenText(alarm.nextMs) else "${atTime(at.toLocalTime())} ${repeatText(repeat)}"
    }

    fun repeatText(repeat: Repeat): String = when (repeat) {
        Repeat.Daily -> "todos los días"
        Repeat.Weekdays -> "de lunes a viernes"
        Repeat.Weekends -> "los fines de semana"
        is Repeat.Weekly -> "los " + join(repeat.days.map { plural(weekday(it)) })
    }

    private fun plural(day: String) = if (day.endsWith("o")) day + "s" else day

    /** "3 minutos y 5 segundos". */
    fun duration(ms: Long) = ToolPresentation.duration(ceil(ms / 1000.0).toLong())

    /** Rounded up to whole minutes: "7 horas y 30 minutos". */
    fun roughDuration(ms: Long) = ToolPresentation.duration((ms + 59_999) / 60_000 * 60)

    fun remaining(timer: Timer) = duration(timer.remainingAt(nowMs))

    fun elapsed(stopwatch: Stopwatch) = ToolPresentation.duration((stopwatch.elapsedAt(nowMs) / 1000).coerceAtLeast(0))

    /** " de pasta", " del médico", "" without label. */
    fun ofLabel(label: String?): String {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty()) return ""
        val lower = clean.lowercase(SPANISH)
        return " " + when {
            lower.startsWith("el ") -> "del ${clean.drop(3)}"
            lower.startsWith("de ") || lower.startsWith("del ") || lower.startsWith("para ") -> clean
            else -> "de $clean"
        }
    }

    /** "2 l de leche", "12 huevos", "pan". */
    fun product(item: ShoppingItem): String {
        val quantity = item.quantity ?: return item.name
        val amount = number(quantity)
        val unit = item.unit?.trim()?.takeIf { it.isNotEmpty() && it != "ud" }
        return if (unit != null) "$amount ${unitWord(unit, quantity)} de ${item.name}" else "$amount ${item.name}"
    }

    /** "12", "2,5", "0,74": at most two decimals. */
    fun number(value: Number): String = ToolPresentation.number(value)

    /**
     * An amount of a conversion unit as it is said: "unos 5,63 kilómetros", "una milla",
     * "menos 10 grados Celsius", "una taza y media", "unas 2 tazas y cuarto". [exact] amounts (what
     * the user said) never take "unos"; results are rounded by [Units.round].
     */
    fun amount(value: Double, code: String, exact: Boolean = false): String {
        val unit = Units.of(code) ?: return "${ToolPresentation.number(value)} $code"
        kitchen(value, unit)?.let { (text, rounded) -> return (if (rounded && !exact) about(unit) else "") + text }
        val shown = Units.round(value, unit)
        val prefix = if (!exact && Units.approximate(value, shown)) about(unit) else ""
        // Below zero: "10 grados Celsius bajo cero"; other negative amounts (rare) take "menos".
        val cold = shown < 0 && unit.dimension == Dimension.TEMPERATURE
        val sign = if (shown < 0 && !cold) "menos " else ""
        val size = kotlin.math.abs(shown)
        val text = if (size == 1.0) (if (unit.feminine) "una " else "un ") + unit.one else ToolPresentation.plain(size) + " " + unit.many
        return prefix + sign + text + if (cold) " bajo cero" else ""
    }

    private fun about(unit: MeasureUnit) = if (unit.feminine) "unas " else "unos "

    /** Cups and spoons in halves, quarters and thirds ("media taza", "2 tazas y cuarto"); null if none fits. */
    private fun kitchen(value: Double, unit: MeasureUnit): Pair<String, Boolean>? {
        if (!unit.kitchen || value <= 0) return null
        val whole = kotlin.math.floor(value).toInt()
        val (fraction, alone, after) = FRACTIONS.minBy { kotlin.math.abs(it.first - (value - whole)) }
        if (kotlin.math.abs(fraction - (value - whole)) > 0.03) return null
        val n = if (fraction == 1.0) whole + 1 else whole
        val rounded = kotlin.math.abs(n + (if (fraction == 1.0) 0.0 else fraction) - value) > 1e-9
        if (n == 0) return alone?.let { "$it ${unit.one}" to rounded }
        return (if (n == 1) "una ${unit.one}" else "$n ${unit.many}") + after to rounded
    }

    /** "la de las 7:00 (trabajo)" style description of an alarm used in lists and questions. */
    fun alarmItem(alarm: Alarm): String {
        val state = when (alarm.status) {
            AlarmStatus.DISABLED -> ", desactivada"
            AlarmStatus.RINGING -> ", sonando"
            AlarmStatus.SNOOZED -> ", pospuesta"
            AlarmStatus.SCHEDULED -> ""
        }
        return alarmWhen(alarm) + ofLabel(alarm.label) + state
    }

    fun timerItem(timer: Timer): String {
        val name = timer.label?.trim()?.takeIf { it.isNotEmpty() } ?: "uno sin nombre"
        return when (timer.status) {
            TimerStatus.RINGING -> "$name, sonando"
            TimerStatus.PAUSED -> "$name, en pausa con ${remaining(timer)}"
            TimerStatus.RUNNING -> "$name con ${remaining(timer)}"
        }
    }

    fun stopwatchItem(stopwatch: Stopwatch): String {
        val name = stopwatch.label?.trim()?.takeIf { it.isNotEmpty() } ?: "uno sin nombre"
        return if (stopwatch.status == StopwatchStatus.PAUSED) "$name, en pausa en ${elapsed(stopwatch)}" else "$name con ${elapsed(stopwatch)}"
    }

    fun item(item: Item): String = when (item.domain) {
        Domain.ALARM -> alarmItem(item.alarm)
        Domain.TIMER -> timerItem(item.timer)
        Domain.SW -> stopwatchItem(item.stopwatch)
        Domain.LIST -> product(item.product) + if (item.product.completed) " (comprado)" else ""
    }

    companion object {
        val SPANISH: Locale = Locale.forLanguageTag("es-ES")
        val MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
        const val LIST_LIMIT = 6

        /** Kitchen fractions: value, how it is said alone ("media taza") and after a whole ("una taza y media"). */
        private val FRACTIONS = listOf(
            Triple(0.0, null, ""), Triple(0.25, "un cuarto de", " y cuarto"), Triple(1 / 3.0, "un tercio de", " y un tercio"),
            Triple(0.5, "media", " y media"), Triple(2 / 3.0, "dos tercios de", " y dos tercios"), Triple(0.75, "tres cuartos de", " y tres cuartos"),
            Triple(1.0, null, ""),
        )

        fun join(values: List<String>, last: String = "y"): String = when (values.size) {
            0 -> ""
            1 -> values.single()
            else -> values.dropLast(1).joinToString(", ") + " $last " + values.last()
        }

        /** Up to [LIST_LIMIT] by name, then "y N más". */
        fun listed(values: List<String>): String {
            val shown = values.take(LIST_LIMIT)
            val rest = values.size - shown.size
            return if (rest > 0) shown.joinToString(", ") + " y $rest más" else join(shown)
        }

        fun count(value: Int, singular: String, plural: String) = "$value ${if (value == 1) singular else plural}"

        fun capitalize(text: String) = text.replaceFirstChar { it.titlecase(SPANISH) }

        /** Lower-cases the first letter, after any opening ¿ or ¡ ("¿Cuántos…" → "¿cuántos…"). */
        fun decapitalize(text: String): String {
            val i = text.indexOfFirst { it.isLetter() }
            return if (i < 0) text else text.substring(0, i) + text.substring(i, i + 1).lowercase(SPANISH) + text.substring(i + 1)
        }

        private val UNIT_WORDS = mapOf(
            "kg" to ("kilo" to "kilos"), "g" to ("gramo" to "gramos"), "l" to ("litro" to "litros"), "ml" to ("mililitro" to "mililitros"),
        )

        /** "2 l" → "2 litros", "6 botella" → "6 botellas": units as they are said. */
        fun unitWord(unit: String, quantity: Double): String {
            val one = quantity == 1.0
            UNIT_WORDS[unit.lowercase(SPANISH)]?.let { (singular, plural) -> return if (one) singular else plural }
            if (one || unit.contains(' ') || unit.last() in "sS") return unit
            return if (unit.last() in "aeiouáéíóú") unit + "s" else unit + "es"
        }

        fun dayName(code: String) = DayCodes.day(code)?.let { Day.WEEKDAYS[it.value - 1] } ?: code
    }
}
