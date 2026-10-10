package com.kakauet.dina.tools

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Typed operations the engine understands. Brains translate their own wire format into these
 * (see `brain/dina45/Dina45Codec`), so a new model contract never touches the engine.
 * [tool] and [op] are the canonical names used for presentation and logs.
 */
sealed interface ToolCommand {
    val tool: String
    val op: String
}

/**
 * Selects an existing item. Every non-null field must point to the same, single item; an empty
 * target means "the only one there is".
 */
data class Target(
    val id: String? = null,
    val label: String? = null,
    /** Shopping items are selected by name. */
    val name: String? = null,
    val position: Position? = null,
    /** Alarms are also selected by their (local) time. */
    val time: LocalTime? = null,
) {
    val isEmpty get() = id == null && label == null && name == null && position == null && time == null
}

sealed interface Position {
    /** 1-based. */
    data class Nth(val n: Int) : Position
    data object Last : Position
}

sealed interface DayRef {
    data object Today : DayRef
    data object Tomorrow : DayRef
    data object DayAfterTomorrow : DayRef
    data class Next(val day: DayOfWeek) : DayRef
    data class On(val date: LocalDate) : DayRef
}

sealed interface WhenSpec {
    data class After(val durationMs: Long) : WhenSpec
    /** Without [day], the next occurrence of [time]: today or tomorrow, or the next repeat day. */
    data class At(val time: LocalTime, val day: DayRef? = null) : WhenSpec
}

/** Explicit field update: `null` means "leave unchanged"; `Patch(null)` clears the field. */
data class Patch<out T>(val value: T)

sealed interface TimerCommand : ToolCommand {
    override val tool get() = "timer"
    data class Create(val durationMs: Long, val label: String? = null) : TimerCommand { override val op get() = "create" }
    data object List : TimerCommand { override val op get() = "list" }
    data class Get(val target: Target) : TimerCommand { override val op get() = "get" }
    data class Pause(val target: Target) : TimerCommand { override val op get() = "pause" }
    data class Resume(val target: Target) : TimerCommand { override val op get() = "resume" }
    data class Update(val target: Target, val change: TimerChange) : TimerCommand { override val op get() = "update" }
    data class Cancel(val target: Target) : TimerCommand { override val op get() = "cancel" }
    data object CancelAll : TimerCommand { override val op get() = "cancel_all" }
    /** Stops a ringing timer; without target, the only ringing one. */
    data class Dismiss(val target: Target? = null) : TimerCommand { override val op get() = "dismiss" }
}

sealed interface TimerChange {
    data class SetRemaining(val ms: Long) : TimerChange
    data class Add(val ms: Long) : TimerChange
    data class Subtract(val ms: Long) : TimerChange
    data class Rename(val label: String?) : TimerChange
}

sealed interface AlarmCommand : ToolCommand {
    override val tool get() = "alarm"
    data class Create(val at: WhenSpec, val label: String? = null, val repeat: Repeat? = null) : AlarmCommand { override val op get() = "create" }
    data object List : AlarmCommand { override val op get() = "list" }
    data class Update(
        val target: Target,
        val at: WhenSpec? = null,
        val label: Patch<String?>? = null,
        val repeat: Patch<Repeat?>? = null,
    ) : AlarmCommand { override val op get() = "update" }
    data class Cancel(val target: Target) : AlarmCommand { override val op get() = "cancel" }
    data object CancelAll : AlarmCommand { override val op get() = "cancel_all" }
    data class Enable(val target: Target) : AlarmCommand { override val op get() = "enable" }
    data class Disable(val target: Target) : AlarmCommand { override val op get() = "disable" }
    data class Snooze(val target: Target? = null, val minutes: Int = 10) : AlarmCommand { override val op get() = "snooze" }
    data class Dismiss(val target: Target? = null) : AlarmCommand { override val op get() = "dismiss" }
}

sealed interface StopwatchCommand : ToolCommand {
    override val tool get() = "stopwatch"
    data class Start(val label: String? = null) : StopwatchCommand { override val op get() = "start" }
    data object List : StopwatchCommand { override val op get() = "list" }
    data class Get(val target: Target) : StopwatchCommand { override val op get() = "get" }
    data class Pause(val target: Target) : StopwatchCommand { override val op get() = "pause" }
    data class Resume(val target: Target) : StopwatchCommand { override val op get() = "resume" }
    /** Back to zero, keeping the running/paused status. */
    data class Reset(val target: Target) : StopwatchCommand { override val op get() = "reset" }
    /** Back to zero and running. */
    data class Restart(val target: Target) : StopwatchCommand { override val op get() = "restart" }
    data class Delete(val target: Target) : StopwatchCommand { override val op get() = "delete" }
    data object DeleteAll : StopwatchCommand { override val op get() = "delete_all" }
}

sealed interface ShoppingCommand : ToolCommand {
    override val tool get() = "shopping"
    data class Add(val name: String, val quantity: Double? = null, val unit: String? = null) : ShoppingCommand { override val op get() = "add" }
    data object List : ShoppingCommand { override val op get() = "list" }
    data class Remove(val target: Target) : ShoppingCommand { override val op get() = "remove" }
    data class Mark(val target: Target) : ShoppingCommand { override val op get() = "mark" }
    data class Unmark(val target: Target) : ShoppingCommand { override val op get() = "unmark" }
    data class Toggle(val target: Target) : ShoppingCommand { override val op get() = "toggle" }
    data class Update(
        val target: Target,
        val name: Patch<String?>? = null,
        val quantity: Patch<Double?>? = null,
        val unit: Patch<String?>? = null,
        val completed: Patch<Boolean?>? = null,
    ) : ShoppingCommand { override val op get() = "update" }
    data object Clear : ShoppingCommand { override val op get() = "clear" }
}

sealed interface VolumeCommand : ToolCommand {
    override val tool get() = "volume"
    data object Get : VolumeCommand { override val op get() = "get" }
    data class Set(val percent: Int) : VolumeCommand { override val op get() = "set" }
    data class Increase(val points: Int = 10) : VolumeCommand { override val op get() = "increase" }
    data class Decrease(val points: Int = 10) : VolumeCommand { override val op get() = "decrease" }
    data object Mute : VolumeCommand { override val op get() = "mute" }
    data object Restore : VolumeCommand { override val op get() = "restore" }
    data object ToggleMute : VolumeCommand { override val op get() = "toggle_mute" }
}

enum class DatePart { TIME, DATE, DATETIME, WEEKDAY }

sealed interface DateTimeCommand : ToolCommand {
    override val tool get() = "datetime"
    data class Now(val part: DatePart, val location: String? = null) : DateTimeCommand { override val op get() = "now" }
    data class Weekday(val date: LocalDate, val location: String? = null) : DateTimeCommand { override val op get() = "weekday" }
    /** Exactly one of [seconds] or [days]. */
    data class Shift(val seconds: Long? = null, val days: Long? = null, val location: String? = null) : DateTimeCommand { override val op get() = "shift" }
    data class DaysBetween(val from: LocalDate, val to: LocalDate) : DateTimeCommand { override val op get() = "days_between" }
    data class OffsetDifference(val fromLocation: String, val toLocation: String) : DateTimeCommand { override val op get() = "offset_difference" }
}

data class Calculate(val expression: String) : ToolCommand {
    override val tool get() = "calculator"
    override val op get() = "calculate"
}

/** [value] [from] in [to], unit codes of [Units]; [ingredient] weighs cups and spoons ("harina"). */
data class Convert(val value: Double, val from: String, val to: String, val ingredient: String? = null) : ToolCommand {
    override val tool get() = "converter"
    override val op get() = "convert"
}

/** Typed payload of a successful operation. */
sealed interface ToolData {
    data class TimerItem(val timer: Timer) : ToolData
    data class Timers(val items: kotlin.collections.List<Timer>) : ToolData
    data class AlarmItem(val alarm: Alarm) : ToolData
    data class Alarms(val items: kotlin.collections.List<Alarm>) : ToolData
    data class StopwatchItem(val stopwatch: Stopwatch) : ToolData
    data class Stopwatches(val items: kotlin.collections.List<Stopwatch>) : ToolData
    data class ShoppingEntry(val item: ShoppingItem) : ToolData
    data class ShoppingList(val items: kotlin.collections.List<ShoppingItem>) : ToolData
    data class RemovedId(val id: String) : ToolData
    data class RemovedCount(val count: Int) : ToolData
    data class Volume(val info: VolumeInfo) : ToolData
    data class DateTimeValue(val value: String, val timezone: String) : ToolData
    data class DaysBetween(val days: Long) : ToolData
    data class OffsetSeconds(val seconds: Int) : ToolData
    data class Number(val value: kotlin.Number) : ToolData
    /** [value] [from] is [result] [to]; [ingredient] is the known one used for mass ↔ volume. */
    data class Conversion(val value: Double, val from: String, val to: String, val result: Double, val ingredient: String? = null) : ToolData
}

sealed interface ToolOutcome {
    /**
     * [atMs] and [zone] are the engine clock and zone when the result was produced, used to project
     * live values and to say days and times.
     */
    data class Success(val data: ToolData, val atMs: Long, val zone: ZoneId = ZoneId.systemDefault()) : ToolOutcome
    /** [candidates] lists the items of an ambiguous selection, as a list result; contracts do not encode it. */
    data class Failure(val code: String, val details: Map<String, Any?> = emptyMap(), val candidates: Success? = null) : ToolOutcome
}

/** One executed (or rejected) call. [command] is null when the brain's call could not be decoded. */
data class ToolExecution(
    val tool: String,
    val op: String,
    val command: ToolCommand?,
    val outcome: ToolOutcome,
) {
    val ok get() = outcome is ToolOutcome.Success
    val data get() = (outcome as? ToolOutcome.Success)?.data

    companion object {
        fun of(command: ToolCommand, outcome: ToolOutcome) = ToolExecution(command.tool, command.op, command, outcome)
    }
}

/** Domain error codes, shared by all contracts. */
class ToolFailure(
    val code: String,
    val details: Map<String, Any?> = emptyMap(),
    val candidates: ToolOutcome.Success? = null,
) : Exception(code) {
    companion object {
        const val NOT_FOUND = "not_found"
        const val AMBIGUOUS = "ambiguous"
        const val INVALID_STATE = "invalid_state"
        const val INVALID_ARGUMENTS = "invalid_arguments"
        const val CONFLICT = "conflict"
        const val UNSUPPORTED = "unsupported"
        const val INTERNAL = "internal"
    }
}
