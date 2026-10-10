package com.kakauet.dina.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class AlertKind(val code: String) {
    TIMER("timer"), ALARM("alarm");
    companion object { fun of(code: String) = if (code == "alarm") ALARM else TIMER }
}

/** Platform side effects, injected so the engine stays plain Kotlin and testable on the JVM. */
interface AlertScheduler {
    fun schedule(kind: AlertKind, id: String, atMs: Long, label: String?)
    fun cancel(kind: AlertKind, id: String)
}

interface VolumeControl {
    fun percent(): Int
    fun isMuted(): Boolean
    fun setPercent(percent: Int)
}

interface ToolStore {
    fun load(): ToolWorld?
    fun save(world: ToolWorld)
}

/**
 * The single owner of Dina's persistent state. Every source of change (any brain, the UI,
 * alarm broadcasts) goes through [execute], so state, scheduling and persistence never diverge.
 */
class ToolEngine(
    private val store: ToolStore,
    private val alerts: AlertScheduler,
    private val volume: VolumeControl,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private var world: ToolWorld = store.load() ?: ToolWorld()
    private val mutableState = MutableStateFlow(world)

    /** Latest committed state. Live values (remaining, elapsed) are projected with the clock. */
    val state: StateFlow<ToolWorld> = mutableState.asStateFlow()

    @Synchronized
    fun execute(command: ToolCommand): ToolOutcome {
        refreshDue()
        val now = clock()
        // A failed command must leave no trace (not even a consumed id).
        val before = world
        return try {
            val data = when (command) {
                is TimerCommand -> timer(command, now)
                is AlarmCommand -> alarm(command, now)
                is StopwatchCommand -> stopwatch(command, now)
                is ShoppingCommand -> shopping(command)
                is VolumeCommand -> volume(command)
                is DateTimeCommand -> dateTime(command, now)
                is Calculate -> ToolData.Number(MathParser(command.expression).parse())
                is Convert -> Units.convert(command.value, command.from, command.to, command.ingredient)
            }
            commit()
            ToolOutcome.Success(data, now, zone())
        } catch (failure: ToolFailure) {
            world = before
            ToolOutcome.Failure(failure.code, failure.details, failure.candidates)
        } catch (error: Exception) {
            world = before
            ToolOutcome.Failure(ToolFailure.INTERNAL, mapOf("message" to (error.message ?: error.javaClass.simpleName)))
        }
    }

    /** Moves due timers/alarms to ringing and publishes the result. */
    @Synchronized
    fun refresh() {
        refreshDue()
        commit()
    }

    /** Called when the platform fires an alert. */
    @Synchronized
    fun markRinging(kind: AlertKind, id: String) {
        world = when (kind) {
            AlertKind.TIMER -> world.copy(timers = world.timers.map { if (it.id == id) it.copy(status = TimerStatus.RINGING, remainingMs = 0L) else it })
            AlertKind.ALARM -> world.copy(alarms = world.alarms.map { if (it.id == id) it.copy(status = AlarmStatus.RINGING) else it })
        }
        commit()
    }

    /** Re-registers pending alerts after a reboot or an app update. */
    @Synchronized
    fun rescheduleAll() {
        world.timers.filter { it.status == TimerStatus.RUNNING }.forEach { alerts.schedule(AlertKind.TIMER, it.id, it.deadlineMs, it.label) }
        world.alarms.filter { it.status == AlarmStatus.SCHEDULED || it.status == AlarmStatus.SNOOZED }
            .forEach { alerts.schedule(AlertKind.ALARM, it.id, it.nextMs, it.label) }
    }

    /** State with due alerts already ringing, the volume and the engine clock. */
    @Synchronized
    fun snapshot(): ToolSnapshot {
        refresh()
        return ToolSnapshot(world, currentVolume(), clock(), zone())
    }

    fun currentVolume(): VolumeInfo {
        val percent = volume.percent()
        return VolumeInfo(percent, volume.isMuted() || percent == 0, world.restorePercent)
    }

    // ---- Timers ----

    private fun timer(command: TimerCommand, now: Long): ToolData = when (command) {
        is TimerCommand.Create -> {
            val item = Timer(nextId("timer", "t"), command.label, now + positive(command.durationMs), command.durationMs, command.durationMs, TimerStatus.RUNNING)
            world = world.copy(timers = world.timers + item)
            alerts.schedule(AlertKind.TIMER, item.id, item.deadlineMs, item.label)
            ToolData.TimerItem(item)
        }
        TimerCommand.List -> ToolData.Timers(world.timers)
        TimerCommand.CancelAll -> {
            world.timers.forEach { alerts.cancel(AlertKind.TIMER, it.id) }
            val removed = world.timers.size
            world = world.copy(timers = emptyList())
            ToolData.RemovedCount(removed)
        }
        is TimerCommand.Get -> ToolData.TimerItem(select(world.timers, command.target, Timer::id, Timer::label))
        is TimerCommand.Pause -> {
            val item = select(world.timers, command.target, Timer::id, Timer::label)
            requireStatus(item.status == TimerStatus.RUNNING, "running")
            alerts.cancel(AlertKind.TIMER, item.id)
            replaceTimer(item.copy(remainingMs = item.remainingAt(now), status = TimerStatus.PAUSED))
        }
        is TimerCommand.Resume -> {
            val item = select(world.timers, command.target, Timer::id, Timer::label)
            requireStatus(item.status == TimerStatus.PAUSED, "paused")
            val resumed = item.copy(deadlineMs = now + item.remainingMs, status = TimerStatus.RUNNING)
            alerts.schedule(AlertKind.TIMER, resumed.id, resumed.deadlineMs, resumed.label)
            replaceTimer(resumed)
        }
        is TimerCommand.Update -> {
            val item = select(world.timers, command.target, Timer::id, Timer::label)
            val remaining = item.remainingAt(now)
            var updated = when (val change = command.change) {
                is TimerChange.SetRemaining -> item.copy(remainingMs = positive(change.ms), durationMs = change.ms)
                is TimerChange.Add -> item.copy(remainingMs = remaining + positive(change.ms))
                is TimerChange.Subtract -> item.copy(remainingMs = (remaining - positive(change.ms)).coerceAtLeast(1_000L))
                is TimerChange.Rename -> item.copy(remainingMs = remaining, label = change.label)
            }
            // A ringing timer that gets more time is counting down again.
            if (updated.status == TimerStatus.RINGING && command.change !is TimerChange.Rename) updated = updated.copy(status = TimerStatus.RUNNING)
            if (updated.status == TimerStatus.RUNNING) {
                updated = updated.copy(deadlineMs = now + updated.remainingMs)
                alerts.schedule(AlertKind.TIMER, updated.id, updated.deadlineMs, updated.label)
            }
            replaceTimer(updated)
        }
        is TimerCommand.Cancel -> removeTimer(select(world.timers, command.target, Timer::id, Timer::label))
        is TimerCommand.Dismiss -> {
            val item = command.target?.let { select(world.timers, it, Timer::id, Timer::label) } ?: singleRinging(world.timers) { it.status == TimerStatus.RINGING }
            requireStatus(item.status == TimerStatus.RINGING, "ringing")
            removeTimer(item)
        }
    }

    private fun replaceTimer(item: Timer): ToolData {
        world = world.copy(timers = world.timers.map { if (it.id == item.id) item else it })
        return ToolData.TimerItem(item)
    }

    private fun removeTimer(item: Timer): ToolData {
        alerts.cancel(AlertKind.TIMER, item.id)
        world = world.copy(timers = world.timers.filterNot { it.id == item.id })
        return ToolData.RemovedId(item.id)
    }

    // ---- Alarms ----

    private fun alarm(command: AlarmCommand, now: Long): ToolData = when (command) {
        is AlarmCommand.Create -> {
            val repeat = validRepeat(command.repeat)
            val item = Alarm(nextId("alarm", "a"), command.label, resolveWhen(command.at, now, repeat), repeat, AlarmStatus.SCHEDULED)
            world = world.copy(alarms = world.alarms + item)
            alerts.schedule(AlertKind.ALARM, item.id, item.nextMs, item.label)
            ToolData.AlarmItem(item)
        }
        AlarmCommand.List -> ToolData.Alarms(world.alarms)
        AlarmCommand.CancelAll -> {
            world.alarms.forEach { alerts.cancel(AlertKind.ALARM, it.id) }
            val removed = world.alarms.size
            world = world.copy(alarms = emptyList())
            ToolData.RemovedCount(removed)
        }
        is AlarmCommand.Update -> {
            val item = selectAlarm(command.target)
            val repeat = if (command.repeat != null) validRepeat(command.repeat.value) else item.repeat
            val nextMs = when {
                command.at != null -> resolveWhen(command.at, now, repeat)
                command.repeat != null && repeat != null -> onRepeatDay(item.nextMs, repeat)
                else -> item.nextMs
            }
            val updated = item.copy(
                // Editing an alarm whose time has gone (ringing, or disabled long ago) moves it to its next occurrence.
                nextMs = if (nextMs > now) nextMs else nextAfter(item.copy(nextMs = nextMs, repeat = repeat), now),
                label = if (command.label != null) command.label.value else item.label,
                repeat = repeat,
                status = AlarmStatus.SCHEDULED,
            )
            scheduleAlarm(updated)
        }
        is AlarmCommand.Cancel -> removeAlarm(selectAlarm(command.target))
        is AlarmCommand.Disable -> {
            val item = selectAlarm(command.target)
            alerts.cancel(AlertKind.ALARM, item.id)
            replaceAlarm(item.copy(status = AlarmStatus.DISABLED))
        }
        is AlarmCommand.Enable -> {
            val item = selectAlarm(command.target)
            if (item.repeat == null && item.nextMs <= now) throw ToolFailure(ToolFailure.INVALID_STATE, mapOf("reason" to "past_alarm"))
            scheduleAlarm(item.copy(nextMs = if (item.nextMs > now) item.nextMs else nextAfter(item, now), status = AlarmStatus.SCHEDULED))
        }
        is AlarmCommand.Snooze -> {
            val item = command.target?.let(::selectAlarm) ?: singleRinging(world.alarms) { it.status == AlarmStatus.RINGING }
            requireStatus(item.status == AlarmStatus.RINGING, "ringing")
            val minutes = command.minutes.coerceIn(1, 10_080)
            scheduleAlarm(item.copy(nextMs = now + minutes * 60_000L, status = AlarmStatus.SNOOZED))
        }
        is AlarmCommand.Dismiss -> {
            val item = command.target?.let(::selectAlarm) ?: singleRinging(world.alarms) { it.status == AlarmStatus.RINGING }
            requireStatus(item.status == AlarmStatus.RINGING, "ringing")
            if (item.repeat == null) removeAlarm(item)
            else scheduleAlarm(item.copy(nextMs = nextAfter(item, now), status = AlarmStatus.SCHEDULED))
        }
    }

    private fun selectAlarm(target: Target) = select(world.alarms, target, Alarm::id, Alarm::label) {
        Instant.ofEpochMilli(it.nextMs).atZone(zone()).toLocalTime().withSecond(0).withNano(0)
    }

    private fun scheduleAlarm(item: Alarm): ToolData {
        alerts.schedule(AlertKind.ALARM, item.id, item.nextMs, item.label)
        return replaceAlarm(item)
    }

    private fun replaceAlarm(item: Alarm): ToolData {
        world = world.copy(alarms = world.alarms.map { if (it.id == item.id) item else it })
        return ToolData.AlarmItem(item)
    }

    private fun removeAlarm(item: Alarm): ToolData {
        alerts.cancel(AlertKind.ALARM, item.id)
        world = world.copy(alarms = world.alarms.filterNot { it.id == item.id })
        return ToolData.RemovedId(item.id)
    }

    private fun validRepeat(repeat: Repeat?): Repeat? {
        if (repeat is Repeat.Weekly && repeat.days.isEmpty()) throw ToolFailure(ToolFailure.INVALID_ARGUMENTS, mapOf("field" to "repeat"))
        return repeat
    }

    // ---- Stopwatches ----

    private fun stopwatch(command: StopwatchCommand, now: Long): ToolData {
        when (command) {
            is StopwatchCommand.Start -> {
                val item = Stopwatch(nextId("stopwatch", "s"), command.label, 0L, now, StopwatchStatus.RUNNING)
                world = world.copy(stopwatches = world.stopwatches + item)
                return ToolData.StopwatchItem(item)
            }
            StopwatchCommand.List -> return ToolData.Stopwatches(world.stopwatches)
            StopwatchCommand.DeleteAll -> {
                val removed = world.stopwatches.size
                world = world.copy(stopwatches = emptyList())
                return ToolData.RemovedCount(removed)
            }
            else -> Unit
        }
        val target = when (command) {
            is StopwatchCommand.Get -> command.target
            is StopwatchCommand.Pause -> command.target
            is StopwatchCommand.Resume -> command.target
            is StopwatchCommand.Reset -> command.target
            is StopwatchCommand.Restart -> command.target
            is StopwatchCommand.Delete -> command.target
        }
        val item = select(world.stopwatches, target, Stopwatch::id, Stopwatch::label)
        val updated = when (command) {
            is StopwatchCommand.Pause -> {
                requireStatus(item.status == StopwatchStatus.RUNNING, "running")
                item.copy(elapsedMs = item.elapsedAt(now), status = StopwatchStatus.PAUSED)
            }
            is StopwatchCommand.Resume -> {
                requireStatus(item.status == StopwatchStatus.PAUSED, "paused")
                item.copy(startedMs = now, status = StopwatchStatus.RUNNING)
            }
            is StopwatchCommand.Reset -> item.copy(elapsedMs = 0L, startedMs = if (item.status == StopwatchStatus.RUNNING) now else item.startedMs)
            is StopwatchCommand.Restart -> item.copy(elapsedMs = 0L, startedMs = now, status = StopwatchStatus.RUNNING)
            is StopwatchCommand.Delete -> {
                world = world.copy(stopwatches = world.stopwatches.filterNot { it.id == item.id })
                return ToolData.RemovedId(item.id)
            }
            else -> item
        }
        world = world.copy(stopwatches = world.stopwatches.map { if (it.id == updated.id) updated else it })
        return ToolData.StopwatchItem(updated)
    }

    // ---- Shopping ----

    private fun shopping(command: ShoppingCommand): ToolData {
        when (command) {
            is ShoppingCommand.Add -> {
                val name = command.name.trim().ifEmpty { throw ToolFailure(ToolFailure.INVALID_ARGUMENTS, mapOf("field" to "name")) }
                if (world.shopping.any { it.name.equals(name, ignoreCase = true) }) throw ToolFailure(ToolFailure.CONFLICT)
                val item = ShoppingItem(nextId("shopping", "i"), name, command.quantity, command.unit, completed = false)
                world = world.copy(shopping = world.shopping + item)
                return ToolData.ShoppingEntry(item)
            }
            ShoppingCommand.List -> return ToolData.ShoppingList(world.shopping)
            ShoppingCommand.Clear -> {
                val removed = world.shopping.size
                world = world.copy(shopping = emptyList())
                return ToolData.RemovedCount(removed)
            }
            else -> Unit
        }
        val target = when (command) {
            is ShoppingCommand.Remove -> command.target
            is ShoppingCommand.Mark -> command.target
            is ShoppingCommand.Unmark -> command.target
            is ShoppingCommand.Toggle -> command.target
            is ShoppingCommand.Update -> command.target
        }
        val item = selectShopping(target)
        val updated = when (command) {
            is ShoppingCommand.Remove -> {
                world = world.copy(shopping = world.shopping.filterNot { it.id == item.id })
                return ToolData.RemovedId(item.id)
            }
            is ShoppingCommand.Mark -> item.copy(completed = true)
            is ShoppingCommand.Unmark -> item.copy(completed = false)
            is ShoppingCommand.Toggle -> item.copy(completed = !item.completed)
            is ShoppingCommand.Update -> item.copy(
                name = command.name?.value?.trim()?.takeIf { it.isNotEmpty() } ?: item.name,
                quantity = if (command.quantity != null) command.quantity.value else item.quantity,
                unit = if (command.unit != null) command.unit.value else item.unit,
                completed = if (command.completed != null) command.completed.value ?: false else item.completed,
            )
        }
        world = world.copy(shopping = world.shopping.map { if (it.id == updated.id) updated else it })
        return ToolData.ShoppingEntry(updated)
    }

    private fun selectShopping(target: Target): ShoppingItem {
        if (target.name != null) {
            val matches = world.shopping.filter { it.name.equals(target.name, ignoreCase = true) }
            return when (matches.size) {
                1 -> matches.single()
                0 -> throw ToolFailure(ToolFailure.NOT_FOUND)
                else -> throw ambiguous(matches)
            }
        }
        return select(world.shopping, target, ShoppingItem::id, label = { null })
    }

    // ---- Volume ----

    private fun volume(command: VolumeCommand): ToolData {
        var percent = volume.percent()
        var muted = volume.isMuted() || percent == 0
        var restore = world.restorePercent
        fun apply(value: Int) { volume.setPercent(value.coerceIn(0, 100)) }
        fun mute() { if (percent > 0) restore = percent; percent = 0; muted = true; apply(0) }
        fun unmute() { percent = restore.coerceIn(1, 100); muted = false; apply(percent) }
        when (command) {
            VolumeCommand.Get -> Unit
            is VolumeCommand.Set -> { percent = command.percent.coerceIn(0, 100); muted = percent == 0; apply(percent) }
            is VolumeCommand.Increase -> { percent = (percent + command.points).coerceAtMost(100); muted = false; apply(percent) }
            is VolumeCommand.Decrease -> { percent = (percent - command.points).coerceAtLeast(0); muted = percent == 0; apply(percent) }
            VolumeCommand.Mute -> mute()
            VolumeCommand.Restore -> unmute()
            VolumeCommand.ToggleMute -> if (muted) unmute() else mute()
        }
        world = world.copy(muted = muted, restorePercent = restore)
        return ToolData.Volume(VolumeInfo(volume.percent(), muted, restore))
    }

    // ---- Date and time ----

    private fun dateTime(command: DateTimeCommand, now: Long): ToolData = when (command) {
        is DateTimeCommand.Now -> {
            val zone = resolveZone(command.location)
            val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            val value = when (command.part) {
                DatePart.TIME -> time.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME)
                DatePart.DATE -> time.toLocalDate().toString()
                DatePart.DATETIME -> time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                DatePart.WEEKDAY -> DayCodes.code(time.dayOfWeek)
            }
            ToolData.DateTimeValue(value, zone.id)
        }
        is DateTimeCommand.Weekday -> ToolData.DateTimeValue(DayCodes.code(command.date.dayOfWeek), resolveZone(command.location).id)
        is DateTimeCommand.Shift -> {
            val zone = resolveZone(command.location)
            val base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            val shifted = when {
                command.seconds != null -> base.plusSeconds(command.seconds)
                command.days != null -> base.plusDays(command.days)
                else -> throw ToolFailure(ToolFailure.INVALID_ARGUMENTS, mapOf("field" to "offset"))
            }
            ToolData.DateTimeValue(shifted.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), zone.id)
        }
        is DateTimeCommand.DaysBetween -> ToolData.DaysBetween(ChronoUnit.DAYS.between(command.from, command.to))
        is DateTimeCommand.OffsetDifference -> {
            val instant = Instant.ofEpochMilli(now)
            val from = resolveZone(command.fromLocation).rules.getOffset(instant).totalSeconds
            val to = resolveZone(command.toLocation).rules.getOffset(instant).totalSeconds
            ToolData.OffsetSeconds(to - from)
        }
    }

    private fun resolveZone(location: String?): ZoneId {
        if (location == null) return zone()
        Places.find(location)?.let { return it.zone }
        return try { ZoneId.of(location) } catch (_: Exception) {
            throw ToolFailure(ToolFailure.NOT_FOUND, mapOf("location" to location))
        }
    }

    /** A day-less time goes to its next occurrence; for a repeated alarm, the next day the repeat allows. */
    private fun resolveWhen(spec: WhenSpec, now: Long, repeat: Repeat? = null): Long {
        if (spec is WhenSpec.After) return now + positive(spec.durationMs)
        spec as WhenSpec.At
        val zone = zone()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date = when (val day = spec.day) {
            null -> {
                val first = if (LocalDateTime.of(today, spec.time).atZone(zone).toInstant().toEpochMilli() <= now) today.plusDays(1) else today
                if (repeat == null) first else generateSequence(first) { it.plusDays(1) }.take(7).firstOrNull { repeat.includes(it.dayOfWeek) } ?: first
            }
            DayRef.Today -> today
            DayRef.Tomorrow -> today.plusDays(1)
            DayRef.DayAfterTomorrow -> today.plusDays(2)
            is DayRef.On -> day.date
            is DayRef.Next -> {
                var delta = (day.day.value - today.dayOfWeek.value + 7) % 7
                if (delta == 0) delta = 7
                today.plusDays(delta.toLong())
            }
        }
        return LocalDateTime.of(date, spec.time).atZone(zone).toInstant().toEpochMilli()
    }

    /** The same time on the first day from [ms] on that [repeat] includes. */
    private fun onRepeatDay(ms: Long, repeat: Repeat): Long {
        val current = Instant.ofEpochMilli(ms).atZone(zone())
        return (generateSequence(current) { it.plusDays(1) }.take(7).firstOrNull { repeat.includes(it.dayOfWeek) } ?: current).toInstant().toEpochMilli()
    }

    private fun Repeat.includes(day: DayOfWeek): Boolean = when (this) {
        Repeat.Daily -> true
        Repeat.Weekdays -> day.value <= 5
        Repeat.Weekends -> day.value >= 6
        is Repeat.Weekly -> day in days
    }

    /**
     * The first time [item] rings after [now]: the next repeat day for a repeated alarm (it may have
     * rung days ago, e.g. with the phone off), the same time today or tomorrow for a one-off one.
     */
    private fun nextAfter(item: Alarm, now: Long): Long {
        if (item.repeat == null) {
            val time = Instant.ofEpochMilli(item.nextMs).atZone(zone()).toLocalTime()
            return resolveWhen(WhenSpec.At(time), now)
        }
        var next = item.copy(nextMs = nextRepeated(item))
        while (next.nextMs <= now) next = next.copy(nextMs = nextRepeated(next))
        return next.nextMs
    }

    private fun nextRepeated(item: Alarm): Long {
        val current = Instant.ofEpochMilli(item.nextMs).atZone(zone())
        val candidates = generateSequence(current.plusDays(1)) { it.plusDays(1) }.take(8)
        val next = when (val repeat = item.repeat) {
            Repeat.Daily -> current.plusDays(1)
            Repeat.Weekdays -> candidates.first { it.dayOfWeek.value <= 5 }
            Repeat.Weekends -> candidates.first { it.dayOfWeek.value >= 6 }
            is Repeat.Weekly -> candidates.firstOrNull { it.dayOfWeek in repeat.days }
                ?: throw ToolFailure(ToolFailure.INVALID_ARGUMENTS, mapOf("field" to "repeat"))
            null -> throw ToolFailure(ToolFailure.INVALID_ARGUMENTS, mapOf("field" to "repeat"))
        }
        return next.toInstant().toEpochMilli()
    }

    // ---- Shared helpers ----

    private fun refreshDue() {
        val now = clock()
        world = world.copy(
            timers = world.timers.map { if (it.status == TimerStatus.RUNNING && it.deadlineMs <= now) it.copy(status = TimerStatus.RINGING, remainingMs = 0L) else it },
            alarms = world.alarms.map {
                if ((it.status == AlarmStatus.SCHEDULED || it.status == AlarmStatus.SNOOZED) && it.nextMs <= now) it.copy(status = AlarmStatus.RINGING) else it
            },
        )
    }

    private fun commit() {
        store.save(world)
        mutableState.value = world
    }

    private fun nextId(domain: String, prefix: String): String {
        val next = (world.counters[domain] ?: 0) + 1
        world = world.copy(counters = world.counters + (domain to next))
        return "$prefix$next"
    }

    private fun <T> select(items: List<T>, target: Target, id: (T) -> String, label: (T) -> String?, time: ((T) -> LocalTime)? = null): T {
        if (target.isEmpty) return when (items.size) {
            1 -> items.single()
            0 -> throw ToolFailure(ToolFailure.NOT_FOUND)
            else -> throw ambiguous(items)
        }
        val matches = mutableListOf<T>()
        items.forEach { item ->
            when {
                target.id != null && id(item) == target.id -> matches += item
                target.label != null && label(item)?.equals(target.label, ignoreCase = true) == true -> matches += item
                target.time != null && time != null && time(item) == target.time -> matches += item
            }
        }
        target.position?.let { position ->
            val index = when (position) {
                Position.Last -> items.size - 1
                is Position.Nth -> position.n - 1
            }
            if (index in items.indices && items[index] !in matches) matches += items[index]
        }
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw ToolFailure(ToolFailure.NOT_FOUND)
            else -> throw ambiguous(matches)
        }
    }

    private fun ambiguous(candidates: List<Any?>) = ToolFailure(
        ToolFailure.AMBIGUOUS,
        candidates = when (candidates.firstOrNull()) {
            is Timer -> ToolData.Timers(candidates.filterIsInstance<Timer>())
            is Alarm -> ToolData.Alarms(candidates.filterIsInstance<Alarm>())
            is Stopwatch -> ToolData.Stopwatches(candidates.filterIsInstance<Stopwatch>())
            is ShoppingItem -> ToolData.ShoppingList(candidates.filterIsInstance<ShoppingItem>())
            else -> null
        }?.let { ToolOutcome.Success(it, clock(), zone()) },
    )

    private fun <T> singleRinging(items: List<T>, ringing: (T) -> Boolean): T {
        val matches = items.filter(ringing)
        if (matches.size != 1) throw ToolFailure(if (matches.isEmpty()) ToolFailure.NOT_FOUND else ToolFailure.AMBIGUOUS)
        return matches.single()
    }

    private fun requireStatus(condition: Boolean, expected: String) {
        if (!condition) throw ToolFailure(ToolFailure.INVALID_STATE, mapOf("expected" to expected))
    }

    private fun positive(ms: Long): Long {
        if (ms <= 0) throw ToolFailure(ToolFailure.INVALID_ARGUMENTS, mapOf("field" to "duration"))
        return ms
    }

}

/**
 * Arithmetic for the calculator tool: + - * / % ** sqrt() and parentheses, with the precedence of
 * Python (`-2**2` is -4). Also reads what a model may write instead: `x` `×` `·` for *, `÷` `:` for /,
 * `^` for ** and a decimal comma (`4,5`).
 */
internal class MathParser(expression: String) {
    private val source = normalize(expression)
    private var index = 0

    fun parse(): Number {
        val value = expression()
        skip()
        if (index != source.length || !value.isFinite()) throw invalid()
        return if (value % 1.0 == 0.0 && abs(value) < 1e15) value.toLong() else value
    }

    private fun expression(): Double {
        var value = term()
        while (true) {
            value = when {
                take('+') -> value + term()
                take('-') -> value - term()
                else -> return value
            }
        }
    }

    private fun term(): Double {
        var value = unary()
        while (true) {
            skip()
            value = when {
                source.startsWith("**", index) -> return value
                take('*') -> value * unary()
                take('/') -> value / unary()
                take('%') -> value % unary()
                else -> return value
            }
        }
    }

    private fun unary(): Double = when {
        take('+') -> unary()
        take('-') -> -unary()
        else -> power()
    }

    /** Right-associative, and binds tighter than a sign on its left: `-2**2` = -(2**2). */
    private fun power(): Double {
        val base = primary()
        skip()
        if (!source.startsWith("**", index)) return base
        index += 2
        return Math.pow(base, unary())
    }

    private fun primary(): Double {
        skip()
        return when {
            source.startsWith("sqrt", index) -> {
                index += 4
                expect('(')
                val value = expression()
                expect(')')
                if (value < 0) throw invalid()
                sqrt(value)
            }
            take('(') -> expression().also { expect(')') }
            else -> number()
        }
    }

    private fun number(): Double {
        skip()
        val start = index
        while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
        return source.substring(start, index).toDoubleOrNull() ?: throw invalid()
    }

    private fun skip() { while (index < source.length && source[index].isWhitespace()) index++ }
    private fun take(char: Char): Boolean { skip(); return if (index < source.length && source[index] == char) { index++; true } else false }
    private fun expect(char: Char) { if (!take(char)) throw invalid() }
    private fun invalid() = ToolFailure(ToolFailure.INVALID_ARGUMENTS, mapOf("field" to "expression"))

    private companion object {
        fun normalize(text: String): String = text.lowercase(Locale.ROOT)
            .replace(Regex("(?<=\\d),(?=\\d)"), ".")
            .replace(Regex("[x×·]"), "*")
            .replace(Regex("[÷:]"), "/")
            .replace("^", "**")
    }
}
