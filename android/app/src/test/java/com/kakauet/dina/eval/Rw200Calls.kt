package com.kakauet.dina.eval

import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.Calculate
import com.kakauet.dina.tools.DatePart
import com.kakauet.dina.tools.DateTimeCommand
import com.kakauet.dina.tools.DayCodes
import com.kakauet.dina.tools.DayRef
import com.kakauet.dina.tools.Patch
import com.kakauet.dina.tools.Position
import com.kakauet.dina.tools.Repeat
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.StopwatchCommand
import com.kakauet.dina.tools.Target
import com.kakauet.dina.tools.TimerChange
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.ToolFailure
import com.kakauet.dina.tools.ToolWorldJson
import com.kakauet.dina.tools.VolumeCommand
import com.kakauet.dina.tools.WhenSpec
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Reads the expected tool calls of RealWorld200 v1, written in the JSON format of Dina 3:
 * `{"name": tool, "arguments": {"op": ..., ...}}`, with the aliases app 1.4 accepted.
 */
class Rw200Calls(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** @throws ToolFailure when the call cannot be mapped to a valid command. */
    fun decode(name: String, source: JSONObject): ToolCommand {
        val args = normalize(name, source)
        return when (name) {
            "timer" -> timer(args)
            "alarm" -> alarm(args)
            "stopwatch" -> stopwatch(args)
            "shopping" -> shopping(args)
            "volume" -> volume(args)
            "datetime" -> dateTime(args)
            "calculator" -> Calculate(args.requiredString("expression"))
            else -> throw unsupported()
        }
    }

    /** Aliases app 1.4 accepted. */
    private fun normalize(name: String, source: JSONObject): JSONObject {
        val args = JSONObject(source.toString())
        val rawOp = args.optString("op")
        val op = when (name) {
            "alarm" -> mapOf("on" to "enable", "off" to "disable", "delete" to "cancel", "remove" to "cancel")[rawOp]
            "timer" -> mapOf("continue" to "resume", "stop" to "cancel", "delete" to "cancel")[rawOp]
            "stopwatch" -> mapOf("continue" to "resume", "start_over" to "restart", "clear" to "reset")[rawOp]
            "shopping" -> mapOf("check" to "mark", "complete" to "mark", "uncheck" to "unmark", "delete" to "remove")[rawOp]
            "volume" -> mapOf("add" to "increase", "subtract" to "decrease", "unmute" to "restore")[rawOp]
            else -> null
        } ?: rawOp
        if (op.isNotBlank()) args.put("op", op)
        if (args.has("duration_s") && !args.has("duration")) args.put("duration", JSONObject().put("value", args.optLong("duration_s")).put("unit", "s"))
        if (args.has("level") && !args.has("percent")) args.put("percent", args.optInt("level"))
        if (args.opt("target") is String) args.put("target", JSONObject().put("label", args.getString("target")))
        if (name == "timer" && op == "create" && args.has("until") && !args.has("duration")) {
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone())
            var due = LocalDateTime.of(now.toLocalDate(), parseTime(args.getString("until"))).atZone(now.zone)
            if (!due.isAfter(now)) due = due.plusDays(1)
            args.remove("until")
            args.put("duration", JSONObject().put("value", Duration.between(now, due).seconds.coerceAtLeast(1L)).put("unit", "s"))
        }
        if (name == "alarm" && op == "snooze" && args.has("duration") && !args.has("minutes")) {
            args.put("minutes", ceil(durationMs(args.requiredObject("duration")) / 60_000.0).toInt())
            args.remove("duration")
        }
        if (name == "datetime" && op == "shift" && args.has("offset")) {
            val offset = args.requiredObject("offset")
            if (offset.optString("unit") == "day") args.put("days", offset.optLong("value")) else args.put("seconds", durationMs(offset) / 1_000L)
            args.remove("offset")
        }
        if (name == "timer" && op == "update" && !args.has("changes")) {
            when {
                args.has("remaining_s") -> args.put("changes", JSONObject().put("remaining", JSONObject().put("value", args.optLong("remaining_s")).put("unit", "s")))
                args.has("delta_s") -> {
                    val delta = args.optLong("delta_s")
                    args.put("changes", JSONObject().put(if (delta >= 0) "add" else "subtract", JSONObject().put("value", abs(delta)).put("unit", "s")))
                }
            }
        }
        return args
    }

    private fun timer(args: JSONObject): TimerCommand = when (args.requiredString("op")) {
        "create" -> TimerCommand.Create(durationMs(args.requiredObject("duration")), args.nullableString("label"))
        "list" -> TimerCommand.List
        "get" -> TimerCommand.Get(target(args))
        "pause" -> TimerCommand.Pause(target(args))
        "resume" -> TimerCommand.Resume(target(args))
        "rename" -> TimerCommand.Update(target(args), TimerChange.Rename(args.nullableString("label")))
        "update" -> {
            val changes = args.requiredObject("changes")
            TimerCommand.Update(target(args), when {
                changes.has("remaining") -> TimerChange.SetRemaining(durationMs(changes.requiredObject("remaining")))
                changes.has("add") -> TimerChange.Add(durationMs(changes.requiredObject("add")))
                changes.has("subtract") -> TimerChange.Subtract(durationMs(changes.requiredObject("subtract")))
                changes.has("label") -> TimerChange.Rename(changes.nullableString("label"))
                else -> throw invalid("changes")
            })
        }
        "cancel" -> TimerCommand.Cancel(target(args))
        "cancel_all" -> TimerCommand.CancelAll
        "dismiss" -> TimerCommand.Dismiss(optionalTarget(args))
        else -> throw unsupported()
    }

    private fun alarm(args: JSONObject): AlarmCommand = when (args.requiredString("op")) {
        "create" -> AlarmCommand.Create(whenSpec(args.requiredObject("when")), args.nullableString("label"), repeat(args.opt("repeat")))
        "list" -> AlarmCommand.List
        "update" -> {
            val changes = args.requiredObject("changes")
            AlarmCommand.Update(
                target(args),
                at = changes.optJSONObject("when")?.let(::whenSpec),
                label = if (changes.has("label")) Patch(changes.nullableString("label")) else null,
                repeat = if (changes.has("repeat")) Patch(repeat(changes.opt("repeat"))) else null,
            )
        }
        "cancel" -> AlarmCommand.Cancel(target(args))
        "cancel_all" -> AlarmCommand.CancelAll
        "enable" -> AlarmCommand.Enable(target(args))
        "disable" -> AlarmCommand.Disable(target(args))
        "snooze" -> AlarmCommand.Snooze(optionalTarget(args), args.optInt("minutes", 10))
        "dismiss" -> AlarmCommand.Dismiss(optionalTarget(args))
        else -> throw unsupported()
    }

    private fun stopwatch(args: JSONObject): StopwatchCommand = when (args.requiredString("op")) {
        "start" -> StopwatchCommand.Start(args.nullableString("label"))
        "list" -> StopwatchCommand.List
        "get" -> StopwatchCommand.Get(target(args))
        "pause" -> StopwatchCommand.Pause(target(args))
        "resume" -> StopwatchCommand.Resume(target(args))
        "reset" -> StopwatchCommand.Reset(target(args))
        "restart" -> StopwatchCommand.Restart(target(args))
        "delete" -> StopwatchCommand.Delete(target(args))
        "delete_all" -> StopwatchCommand.DeleteAll
        else -> throw unsupported()
    }

    private fun shopping(args: JSONObject): ShoppingCommand = when (args.requiredString("op")) {
        "add" -> ShoppingCommand.Add(args.requiredString("name"), ToolWorldJson.quantity(args.opt("quantity")), args.nullableString("unit"))
        "list" -> ShoppingCommand.List
        "clear" -> ShoppingCommand.Clear
        "remove" -> ShoppingCommand.Remove(target(args))
        "mark" -> ShoppingCommand.Mark(target(args))
        "unmark" -> ShoppingCommand.Unmark(target(args))
        "toggle" -> ShoppingCommand.Toggle(target(args))
        "update" -> {
            val changes = args.requiredObject("changes")
            ShoppingCommand.Update(
                target(args),
                name = if (changes.has("name")) Patch(changes.nullableString("name")) else null,
                quantity = if (changes.has("quantity")) Patch(ToolWorldJson.quantity(changes.opt("quantity"))) else null,
                unit = if (changes.has("unit")) Patch(changes.nullableString("unit")) else null,
                completed = if (changes.has("completed")) Patch(changes.optBoolean("completed")) else null,
            )
        }
        else -> throw unsupported()
    }

    private fun volume(args: JSONObject): VolumeCommand = when (args.requiredString("op")) {
        "get" -> VolumeCommand.Get
        "set" -> VolumeCommand.Set(args.requiredInt("percent"))
        "increase" -> VolumeCommand.Increase(args.optInt("points", 10))
        "decrease" -> VolumeCommand.Decrease(args.optInt("points", 10))
        "mute" -> VolumeCommand.Mute
        "restore" -> VolumeCommand.Restore
        "toggle_mute" -> VolumeCommand.ToggleMute
        else -> throw unsupported()
    }

    private fun dateTime(args: JSONObject): DateTimeCommand {
        val location = args.nullableString("location")
        return when (args.requiredString("op")) {
            "now" -> DateTimeCommand.Now(
                when (args.requiredString("part")) {
                    "time" -> DatePart.TIME
                    "date" -> DatePart.DATE
                    "datetime" -> DatePart.DATETIME
                    "weekday" -> DatePart.WEEKDAY
                    else -> throw invalid("part")
                },
                location,
            )
            "weekday" -> DateTimeCommand.Weekday(date(args, "date"), location)
            "shift" -> when {
                args.has("seconds") -> DateTimeCommand.Shift(seconds = args.optLong("seconds"), location = location)
                args.has("days") -> DateTimeCommand.Shift(days = args.optLong("days"), location = location)
                else -> throw invalid("offset")
            }
            "days_between" -> DateTimeCommand.DaysBetween(date(args, "from_date"), date(args, "to_date"))
            "offset_difference" -> DateTimeCommand.OffsetDifference(args.requiredString("from_location"), args.requiredString("to_location"))
            else -> throw unsupported()
        }
    }

    /** Without a target the engine picks the only item. */
    private fun target(args: JSONObject): Target =
        optionalTarget(args) ?: Target(name = args.nullableString("name").takeIf { args.optString("op") != "add" })

    private fun optionalTarget(args: JSONObject): Target? {
        val value = args.optJSONObject("target") ?: return null
        val position = if (!value.has("position")) null else when (val raw = value.opt("position")) {
            "first" -> Position.Nth(1)
            "second" -> Position.Nth(2)
            "third" -> Position.Nth(3)
            "last" -> Position.Last
            is Number -> Position.Nth(raw.toInt())
            is String -> Position.Nth(raw.toIntOrNull() ?: 0)
            else -> Position.Nth(0)
        }
        return Target(
            id = value.nullableString("id"),
            label = value.nullableString("label"),
            name = value.nullableString("name"),
            position = position,
            time = value.nullableString("time")?.let(::parseTime),
        )
    }

    private fun whenSpec(value: JSONObject): WhenSpec {
        if (value.has("after")) return WhenSpec.After(durationMs(value.requiredObject("after")))
        val time = parseTime(value.requiredString("time"))
        val day = when {
            value.has("date") -> DayRef.On(date(value, "date"))
            value.has("day") -> when (val code = value.requiredString("day")) {
                "today" -> DayRef.Today
                "tomorrow" -> DayRef.Tomorrow
                "day_after_tomorrow" -> DayRef.DayAfterTomorrow
                else -> DayRef.Next(DayCodes.day(code) ?: throw invalid("day"))
            }
            else -> null
        }
        return WhenSpec.At(time, day)
    }

    private fun repeat(value: Any?): Repeat? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> ToolWorldJson.decodeRepeat(value) ?: throw invalid("repeat")
        is String -> ToolWorldJson.decodeRepeat(JSONObject().put("type", value)) ?: throw invalid("repeat")
        else -> throw invalid("repeat")
    }

    private fun durationMs(value: JSONObject): Long {
        val amount = runCatching { value.getLong("value") }.getOrElse { throw invalid("duration") }
        if (amount <= 0) throw invalid("duration")
        return amount * when (value.optString("unit")) {
            "s" -> 1_000L
            "min" -> 60_000L
            "h" -> 3_600_000L
            else -> throw invalid("unit")
        }
    }

    private fun parseTime(text: String): LocalTime = runCatching {
        val normalized = text.lowercase(Locale.ROOT).trim()
        if (normalized.endsWith(" am") || normalized.endsWith(" pm")) {
            LocalTime.parse(normalized.uppercase(Locale.ROOT), DateTimeFormatter.ofPattern(if (normalized.contains(':')) "h:mm a" else "h a", Locale.US))
        } else {
            LocalTime.parse(if (normalized.count { it == ':' } == 0) "$normalized:00" else normalized, DateTimeFormatter.ofPattern("H:mm"))
        }
    }.getOrElse { throw invalid("time") }

    private fun date(args: JSONObject, key: String): LocalDate =
        runCatching { LocalDate.parse(args.requiredString(key)) }.getOrElse { throw invalid(key) }

    private fun invalid(field: String) = ToolFailure(ToolFailure.INVALID_ARGUMENTS, mapOf("field" to field))
    private fun unsupported() = ToolFailure(ToolFailure.UNSUPPORTED)

    private fun JSONObject.requiredString(key: String): String {
        val value = opt(key)
        return if (value is String && value.isNotBlank()) value else throw invalid(key)
    }

    private fun JSONObject.requiredObject(key: String): JSONObject = optJSONObject(key) ?: throw invalid(key)

    private fun JSONObject.requiredInt(key: String): Int = when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: throw invalid(key)
        else -> throw invalid(key)
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
