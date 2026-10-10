package com.kakauet.dina.tools

import java.time.DayOfWeek
import java.time.ZoneId

/**
 * Typed state of everything Dina manages. The engine is the only writer; UI, voice and
 * every brain read immutable snapshots of it. Persisted by [ToolWorldJson].
 */
data class ToolWorld(
    val timers: List<Timer> = emptyList(),
    val alarms: List<Alarm> = emptyList(),
    val stopwatches: List<Stopwatch> = emptyList(),
    val shopping: List<ShoppingItem> = emptyList(),
    val counters: Map<String, Int> = emptyMap(),
    val muted: Boolean = false,
    val restorePercent: Int = DEFAULT_RESTORE_PERCENT,
) {
    companion object { const val DEFAULT_RESTORE_PERCENT = 72 }
}

enum class TimerStatus(val code: String) {
    RUNNING("running"), PAUSED("paused"), RINGING("ringing");
    companion object { fun of(code: String) = entries.firstOrNull { it.code == code } ?: RUNNING }
}

data class Timer(
    val id: String,
    val label: String?,
    /** Wall-clock end while running; stale otherwise. */
    val deadlineMs: Long,
    /** Remaining time captured when not running. */
    val remainingMs: Long,
    val durationMs: Long,
    val status: TimerStatus,
) {
    fun remainingAt(nowMs: Long): Long =
        if (status == TimerStatus.RUNNING) (deadlineMs - nowMs).coerceAtLeast(0L) else remainingMs
}

enum class AlarmStatus(val code: String) {
    SCHEDULED("scheduled"), SNOOZED("snoozed"), RINGING("ringing"), DISABLED("disabled");
    companion object { fun of(code: String) = entries.firstOrNull { it.code == code } ?: SCHEDULED }
}

sealed interface Repeat {
    data object Daily : Repeat
    data object Weekdays : Repeat
    data object Weekends : Repeat
    data class Weekly(val days: List<DayOfWeek>) : Repeat
}

data class Alarm(
    val id: String,
    val label: String?,
    val nextMs: Long,
    val repeat: Repeat?,
    val status: AlarmStatus,
)

enum class StopwatchStatus(val code: String) {
    RUNNING("running"), PAUSED("paused");
    companion object { fun of(code: String) = entries.firstOrNull { it.code == code } ?: RUNNING }
}

data class Stopwatch(
    val id: String,
    val label: String?,
    /** Accumulated time before [startedMs]. */
    val elapsedMs: Long,
    val startedMs: Long,
    val status: StopwatchStatus,
) {
    fun elapsedAt(nowMs: Long): Long =
        elapsedMs + if (status == StopwatchStatus.RUNNING) nowMs - startedMs else 0L
}

data class ShoppingItem(
    val id: String,
    val name: String,
    val quantity: Double?,
    val unit: String?,
    val completed: Boolean,
)

data class VolumeInfo(val percent: Int, val muted: Boolean, val restorePercent: Int)

/** Everything a brain may show the model, read at one instant of the engine clock. */
data class ToolSnapshot(val world: ToolWorld, val volume: VolumeInfo, val nowMs: Long, val zone: ZoneId)

/** Day codes shared by every tool contract. */
object DayCodes {
    private val codes = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    fun code(day: DayOfWeek) = codes[day.value - 1]
    fun day(code: String): DayOfWeek? = codes.indexOf(code).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) }
}
