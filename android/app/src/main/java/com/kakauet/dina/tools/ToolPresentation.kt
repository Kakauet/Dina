package com.kakauet.dina.tools

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs

enum class WidgetKind { TIMER, ALARM, STOPWATCH, SHOPPING, VOLUME, CALCULATOR, DATETIME, CONVERSION }

/** What the UI should show after a turn. It is derived from real results, never from model output. */
data class ContextualWidget(
    val kind: WidgetKind,
    /** Item to focus; null with [showAll] false means "the latest item of this kind". */
    val focusId: String? = null,
    val showAll: Boolean = false,
    /** Static value for calculator/date/conversion widgets, ready to show. */
    val value: String? = null,
    /** A smaller line above [value]: what was converted ("3,5 mi"). */
    val detail: String? = null,
    /** Shown automatically on the voice screen; chat bubbles always show it. */
    val autoOpen: Boolean = true,
    val expiresAtMs: Long? = null,
)

/**
 * Contract-independent presentation of tool results: the widget a turn shows and the Spanish
 * wording of numbers, durations and repeats shared by the UI and the spoken answers (`dialog/Spoken`).
 */
object ToolPresentation {
    private val spanish = Locale.forLanguageTag("es-ES")

    fun widget(executions: List<ToolExecution>, nowMs: Long = System.currentTimeMillis()): ContextualWidget? {
        val execution = executions.lastOrNull { it.tool in WIDGET_TOOLS } ?: return null
        val data = execution.data ?: return null
        val op = execution.op
        val kind = when (execution.tool) {
            "timer" -> WidgetKind.TIMER
            "alarm" -> WidgetKind.ALARM
            "stopwatch" -> WidgetKind.STOPWATCH
            "shopping" -> WidgetKind.SHOPPING
            "volume" -> WidgetKind.VOLUME
            "calculator" -> WidgetKind.CALCULATOR
            "converter" -> WidgetKind.CONVERSION
            else -> WidgetKind.DATETIME
        }
        if (data is ToolData.RemovedId || (data is ToolData.RemovedCount && kind != WidgetKind.SHOPPING)) return null
        val autoOpen = when (kind) {
            WidgetKind.TIMER, WidgetKind.ALARM, WidgetKind.STOPWATCH, WidgetKind.SHOPPING -> op !in REMOVAL_OPS
            WidgetKind.VOLUME, WidgetKind.CALCULATOR, WidgetKind.CONVERSION -> true
            WidgetKind.DATETIME -> op != "now" || (execution.command as? DateTimeCommand.Now)?.let { it.part == DatePart.DATETIME || it.location != null } == true
        }
        return ContextualWidget(
            kind = kind,
            focusId = when (data) {
                is ToolData.TimerItem -> data.timer.id
                is ToolData.AlarmItem -> data.alarm.id
                is ToolData.StopwatchItem -> data.stopwatch.id
                is ToolData.ShoppingEntry -> data.item.id
                else -> null
            },
            showAll = data is ToolData.Timers || data is ToolData.Alarms || data is ToolData.Stopwatches || kind == WidgetKind.SHOPPING,
            value = when (data) {
                is ToolData.Number -> number(data.value)
                is ToolData.DateTimeValue -> dateTimeValue(execution.command, data.value)
                is ToolData.DaysBetween -> abs(data.days).let { "$it ${if (it == 1L) "día" else "días"}" }
                is ToolData.OffsetSeconds -> offset(data.seconds)
                is ToolData.Conversion -> "≈ ".takeIf { Units.of(data.to)?.let { u -> Units.approximate(data.result, Units.round(data.result, u)) } == true }.orEmpty() + measure(data.result, data.to)
                else -> null
            },
            detail = (data as? ToolData.Conversion)?.let { measure(it.value, it.from) + (it.ingredient?.let { i -> " de $i" } ?: "") },
            autoOpen = autoOpen,
            expiresAtMs = if (kind == WidgetKind.VOLUME) nowMs + 4_500L else null,
        )
    }

    /** A number as it is said and shown: "12", "2,5", "0,74" (rounded to [DECIMALS] decimals, never in E notation). */
    fun number(value: Number): String = rounded(value).toPlainString().replace('.', ',')

    /** A number with all its decimals, never in E notation: "0,00062", "1609,34". */
    fun plain(value: Double): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString().replace('.', ',')

    /** "5,63 km", "180 °C", "2 tazas" for the conversion card (results rounded as they are said). */
    fun measure(value: Double, code: String): String {
        val unit = Units.of(code) ?: return "${plain(value)} $code"
        val shown = Units.round(value, unit)
        return plain(shown) + " " + (unit.symbol ?: if (shown == 1.0) unit.one else unit.many)
    }

    /** True when [number] had to round [value]. */
    fun isRounded(value: Number): Boolean = rounded(value).compareTo(BigDecimal(value.toString())) != 0

    private fun rounded(value: Number): BigDecimal = BigDecimal(value.toString()).setScale(DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros()

    /** "3 minutos y 5 segundos"; at most two units. */
    fun duration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return buildList {
            if (hours > 0) add("$hours ${if (hours == 1L) "hora" else "horas"}")
            if (minutes > 0) add("$minutes ${if (minutes == 1L) "minuto" else "minutos"}")
            if (secs > 0 || isEmpty()) add("$secs ${if (secs == 1L) "segundo" else "segundos"}")
        }.take(2).joinToString(" y ")
    }

    fun repeatLabel(repeat: Repeat?): String = when (repeat) {
        null -> "nunca"
        Repeat.Daily -> "cada día"
        Repeat.Weekdays -> "entre semana"
        Repeat.Weekends -> "fines de semana"
        is Repeat.Weekly -> repeat.days.joinToString(", ") { dayName(it) }
    }

    /** "10:00", "lunes, 5 de octubre", "sábado": the engine's ISO values as the widget shows them. */
    private fun dateTimeValue(command: ToolCommand?, value: String): String = runCatching {
        when (command) {
            is DateTimeCommand.Now -> (command.location?.let { (Places.find(it)?.name ?: it.trim()) + " · " } ?: "") + when (command.part) {
                DatePart.TIME -> hm(LocalTime.parse(value))
                DatePart.DATE -> date(LocalDate.parse(value))
                DatePart.WEEKDAY -> DayCodes.day(value)?.let(::dayName) ?: value
                DatePart.DATETIME -> OffsetDateTime.parse(value).let { "${date(it.toLocalDate())} · ${hm(it.toLocalTime())}" }
            }
            is DateTimeCommand.Weekday -> date(command.date)
            is DateTimeCommand.Shift -> OffsetDateTime.parse(value).let { "${date(it.toLocalDate())} · ${hm(it.toLocalTime())}" }
            else -> value
        }
    }.getOrDefault(value)

    private fun offset(seconds: Int): String {
        val hours = seconds / 3600.0
        val text = if (hours % 1.0 == 0.0) hours.toInt().toString() else "%.1f".format(spanish, hours)
        return "${if (seconds >= 0) "+" else ""}$text h"
    }

    private fun date(date: LocalDate) = "${dayName(date.dayOfWeek)}, ${date.dayOfMonth} de ${MONTHS[date.monthValue - 1]}"
    private fun dayName(day: DayOfWeek) = DAY_NAMES[day.value - 1]
    private fun hm(time: LocalTime) = "%d:%02d".format(time.hour, time.minute)

    private const val DECIMALS = 2
    private val WIDGET_TOOLS = setOf("timer", "alarm", "stopwatch", "shopping", "volume", "calculator", "datetime", "converter")
    private val REMOVAL_OPS = setOf("cancel", "cancel_all", "delete", "delete_all", "clear", "remove")
    private val DAY_NAMES = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    private val MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
}
