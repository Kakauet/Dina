package com.kakauet.dina.eval

import com.kakauet.dina.dialog.SpanishText
import com.kakauet.dina.brain.ToolExecutor
import com.kakauet.dina.tools.Alarm
import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.Calculate
import com.kakauet.dina.tools.Convert
import com.kakauet.dina.tools.DatePart
import com.kakauet.dina.tools.DateTimeCommand
import com.kakauet.dina.tools.DayRef
import com.kakauet.dina.tools.FakeVolume
import com.kakauet.dina.tools.MemoryStore
import com.kakauet.dina.tools.Patch
import com.kakauet.dina.tools.RecordingAlerts
import com.kakauet.dina.tools.Repeat
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.ShoppingItem
import com.kakauet.dina.tools.Stopwatch
import com.kakauet.dina.tools.StopwatchCommand
import com.kakauet.dina.tools.StopwatchStatus
import com.kakauet.dina.tools.Target
import com.kakauet.dina.tools.Timer
import com.kakauet.dina.tools.TimerChange
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.ToolEngine
import com.kakauet.dina.tools.ToolOutcome
import com.kakauet.dina.tools.ToolSnapshot
import com.kakauet.dina.tools.ToolWorld
import com.kakauet.dina.tools.VolumeCommand
import com.kakauet.dina.tools.WhenSpec
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class OracleError(message: String) : Exception(message)

/** Everything a turn can change: the engine's world plus the (fake) system volume. */
data class WorldState(val world: ToolWorld, val volumePercent: Int, val volumeMuted: Boolean)

/** A real [ToolEngine] at a fake clock, with optional injected failures per turn. */
class Sandbox(start: WorldState, startMs: Long, val zone: ZoneId) {
    var nowMs: Long = startMs
        private set
    private val volume = FakeVolume(start.volumePercent, start.volumeMuted)
    val engine = ToolEngine(MemoryStore(start.world), RecordingAlerts(), volume, clock = { nowMs }, zone = { zone })
    private var injections: Map<String, String> = emptyMap()
    private var calls = 0

    /** Indices of this turn's calls that got an injected failure. */
    val injected = mutableSetOf<Int>()

    /** Injections are keyed by tool name ("alarm") or by call index within the turn ("0"). */
    val executor = object : ToolExecutor {
        override fun execute(command: ToolCommand): ToolOutcome {
            val index = calls++
            val code = injections[command.tool] ?: injections[index.toString()]
            return if (code != null) { injected += index; ToolOutcome.Failure(code) } else engine.execute(command)
        }
        override fun snapshot(): ToolSnapshot = engine.snapshot()
    }

    fun advance(ms: Long) { nowMs += ms }

    fun beginTurn(inject: Map<String, String>) { injections = inject; calls = 0; injected.clear() }

    fun state(): WorldState {
        engine.refresh()
        return WorldState(engine.state.value, volume.value, volume.mutedFlag)
    }

    val today: LocalDate get() = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
}

// ---------------------------------------------------------------------------------------------
// Initial state: readable strings, e.g. alarms ["mañana 07:30 'trabajo' rep=laborables"].
// ---------------------------------------------------------------------------------------------

object StateSpec {
    fun build(spec: JSONObject?, clock: LocalDateTime, zone: ZoneId): WorldState {
        val now = clock.atZone(zone).toInstant().toEpochMilli()
        if (spec == null) return WorldState(ToolWorld(), 50, false)
        val alarms = strings(spec, "alarms").mapIndexed { i, line -> alarm("a${i + 1}", line, clock, zone) }
        val timers = strings(spec, "timers").mapIndexed { i, line -> timer("t${i + 1}", line, now) }
        val stopwatches = strings(spec, "stopwatches").mapIndexed { i, line -> stopwatch("s${i + 1}", line, now) }
        val shopping = strings(spec, "shopping").mapIndexed { i, line -> shoppingItem("i${i + 1}", line) }
        val volume = Dsl.tokens(spec.optString("volume", "50"))
        val percent = volume.first().text.toInt()
        val muted = volume.any { it.text == "muted" } || percent == 0
        val restore = volume.firstNotNullOfOrNull { it.value("restore")?.toInt() } ?: if (percent > 0) percent else ToolWorld.DEFAULT_RESTORE_PERCENT
        val counters = buildMap {
            if (alarms.isNotEmpty()) put("alarm", alarms.size)
            if (timers.isNotEmpty()) put("timer", timers.size)
            if (stopwatches.isNotEmpty()) put("stopwatch", stopwatches.size)
            if (shopping.isNotEmpty()) put("shopping", shopping.size)
        }
        return WorldState(ToolWorld(timers, alarms, stopwatches, shopping, counters, muted, restore), if (muted) 0 else percent, muted)
    }

    private fun strings(spec: JSONObject, key: String): List<String> {
        val array = spec.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun alarm(id: String, line: String, clock: LocalDateTime, zone: ZoneId): Alarm {
        val tokens = Dsl.tokens(line)
        val time = tokens.firstNotNullOfOrNull { Dsl.time(it.text) } ?: throw OracleError("alarm without time: $line")
        val repeat = tokens.firstNotNullOfOrNull { it.value("rep") }?.let(Dsl::repeat)
        val status = when {
            tokens.any { it.text == "off" } -> AlarmStatus.DISABLED
            tokens.any { it.text == "ringing" } -> AlarmStatus.RINGING
            tokens.any { it.text == "snoozed" } -> AlarmStatus.SNOOZED
            else -> AlarmStatus.SCHEDULED
        }
        val explicitDay = tokens.firstOrNull { !it.quoted && Dsl.day(it.text, clock.toLocalDate()) != null && Dsl.time(it.text) == null }
        val date = when {
            explicitDay != null -> Dsl.date(explicitDay.text, clock.toLocalDate())
            status == AlarmStatus.RINGING -> if (time <= clock.toLocalTime()) clock.toLocalDate() else clock.toLocalDate().minusDays(1)
            else -> generateSequence(clock.toLocalDate()) { it.plusDays(1) }.take(9).first { day ->
                LocalDateTime.of(day, time).isAfter(clock) && matches(repeat, day.dayOfWeek)
            }
        }
        val label = tokens.firstOrNull { it.quoted }?.text
        return Alarm(id, label, LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli(), repeat, status)
    }

    private fun matches(repeat: Repeat?, day: DayOfWeek) = when (repeat) {
        null, Repeat.Daily -> true
        Repeat.Weekdays -> day.value <= 5
        Repeat.Weekends -> day.value >= 6
        is Repeat.Weekly -> day in repeat.days
    }

    private fun timer(id: String, line: String, now: Long): Timer {
        val tokens = Dsl.tokens(line)
        val total = tokens.firstNotNullOfOrNull { if (it.quoted) null else Dsl.duration(it.text) } ?: throw OracleError("timer without duration: $line")
        val left = tokens.firstNotNullOfOrNull { it.value("left")?.let(Dsl::duration) } ?: total
        val label = tokens.firstOrNull { it.quoted }?.text
        return when {
            tokens.any { it.text == "paused" } -> Timer(id, label, now, left, total, TimerStatus.PAUSED)
            tokens.any { it.text == "ringing" } -> Timer(id, label, now, 0L, total, TimerStatus.RINGING)
            else -> Timer(id, label, now + left, left, total, TimerStatus.RUNNING)
        }
    }

    private fun stopwatch(id: String, line: String, now: Long): Stopwatch {
        val tokens = Dsl.tokens(line)
        val elapsed = tokens.firstNotNullOfOrNull { if (it.quoted) null else Dsl.duration(it.text) } ?: 0L
        val label = tokens.firstOrNull { it.quoted }?.text
        return if (tokens.any { it.text == "paused" }) Stopwatch(id, label, elapsed, now, StopwatchStatus.PAUSED)
        else Stopwatch(id, label, 0L, now - elapsed, StopwatchStatus.RUNNING)
    }

    private fun shoppingItem(id: String, line: String): ShoppingItem {
        val tokens = Dsl.tokens(line)
        val name = tokens.firstOrNull { it.quoted }?.text ?: tokens.first().text
        val quantity = tokens.firstNotNullOfOrNull { it.value("n")?.replace(',', '.')?.toDouble() }
        val unit = tokens.firstNotNullOfOrNull { it.value("unit") }
        return ShoppingItem(id, name, quantity, unit, completed = tokens.any { it.text == "done" })
    }
}

// ---------------------------------------------------------------------------------------------
// Oracle actions: the benchmark's own canonical notation, translated to engine commands.
// Targets are resolved against the current world and must match exactly one item.
// ---------------------------------------------------------------------------------------------

object Dsl {
    data class Token(val text: String, val quoted: Boolean) {
        fun value(key: String): String? = if (!quoted && text.startsWith("$key=")) text.substringAfter('=').trim('\'') else null
    }

    fun tokens(line: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1).takeIf { it > 0 } ?: throw OracleError("unclosed quote: $line")
                    out += Token(line.substring(i + 1, end), true); i = end + 1
                }
                else -> {
                    var end = i
                    var quote = false
                    while (end < line.length && (quote || !line[end].isWhitespace())) { if (line[end] == '\'') quote = !quote; end++ }
                    out += Token(line.substring(i, end), false); i = end
                }
            }
        }
        return out
    }

    fun time(text: String): LocalTime? = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text)?.let {
        LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }

    fun duration(text: String): Long? {
        val m = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(text) ?: return null
        if (text.isEmpty() || m.groupValues.drop(1).all { it.isEmpty() }) return null
        return (m.groupValues[1].toLongOrNull() ?: 0) * 3_600_000 + (m.groupValues[2].toLongOrNull() ?: 0) * 60_000 + (m.groupValues[3].toLongOrNull() ?: 0) * 1_000
    }

    private val WEEKDAYS = mapOf(
        "lun" to DayOfWeek.MONDAY, "lunes" to DayOfWeek.MONDAY, "mar" to DayOfWeek.TUESDAY, "martes" to DayOfWeek.TUESDAY,
        "mie" to DayOfWeek.WEDNESDAY, "miercoles" to DayOfWeek.WEDNESDAY, "jue" to DayOfWeek.THURSDAY, "jueves" to DayOfWeek.THURSDAY,
        "vie" to DayOfWeek.FRIDAY, "viernes" to DayOfWeek.FRIDAY, "sab" to DayOfWeek.SATURDAY, "sabado" to DayOfWeek.SATURDAY,
        "dom" to DayOfWeek.SUNDAY, "domingo" to DayOfWeek.SUNDAY,
    )

    /** Day reference for alarms: hoy, mañana, pasado, lun..dom (next one), YYYY-MM-DD, +N. */
    fun day(text: String, today: LocalDate): DayRef? {
        val t = SpanishText.fold(text)
        return when {
            t == "hoy" -> DayRef.Today
            t == "manana" -> DayRef.Tomorrow
            t == "pasado" -> DayRef.DayAfterTomorrow
            t in WEEKDAYS -> DayRef.Next(WEEKDAYS.getValue(t))
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(text) -> DayRef.On(LocalDate.parse(text))
            Regex("^\\+\\d+$").matches(text) -> DayRef.On(today.plusDays(text.drop(1).toLong()))
            else -> null
        }
    }

    fun date(text: String, today: LocalDate): LocalDate = when (val ref = day(text, today) ?: throw OracleError("bad day: $text")) {
        DayRef.Today -> today
        DayRef.Tomorrow -> today.plusDays(1)
        DayRef.DayAfterTomorrow -> today.plusDays(2)
        is DayRef.On -> ref.date
        is DayRef.Next -> {
            var delta = (ref.day.value - today.dayOfWeek.value + 7) % 7
            if (delta == 0) delta = 7
            today.plusDays(delta.toLong())
        }
    }

    fun repeat(text: String): Repeat = when (SpanishText.fold(text)) {
        "diario", "todos", "cada dia" -> Repeat.Daily
        "laborables", "lun vie" -> Repeat.Weekdays
        "finde" -> Repeat.Weekends
        else -> Repeat.Weekly(text.split(',').map { WEEKDAYS[SpanishText.fold(it)] ?: throw OracleError("bad repeat: $text") })
    }

    /** Translates one oracle action to an engine command, resolving its target in [state]. */
    fun command(line: String, state: WorldState, nowMs: Long, zone: ZoneId): ToolCommand {
        val tokens = tokens(line)
        val head = tokens.first().text
        val args = tokens.drop(1)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate()
        val world = state.world
        fun quoted() = args.firstOrNull { it.quoted }?.text
        fun selector() = args.firstOrNull { !it.quoted && it.text.startsWith("@") }
        fun dur() = args.firstNotNullOfOrNull { if (it.quoted || it.text.contains('=')) null else duration(it.text) }
        fun int() = args.firstNotNullOfOrNull { if (it.quoted) null else it.text.toIntOrNull() }
        fun kv(key: String) = args.firstNotNullOfOrNull { it.value(key) }
        fun target(items: List<Any>, idOf: (Any) -> String, labelOf: (Any) -> String?, timeOf: ((Any) -> LocalTime?)? = null, ringing: (Any) -> Boolean): Target {
            val sel = selector()
            val matches = when {
                sel == null -> items
                sel.text == "@last" -> listOfNotNull(items.lastOrNull())
                sel.text == "@ringing" -> items.filter(ringing)
                sel.text.startsWith("@'") -> { val key = SpanishText.key(sel.text.drop(2).dropLast(1)); items.filter { SpanishText.key(labelOf(it)) == key } }
                time(sel.text.drop(1)) != null && timeOf != null -> items.filter { timeOf(it) == time(sel.text.drop(1)) }
                sel.text.drop(1).toIntOrNull() != null -> listOfNotNull(items.getOrNull(sel.text.drop(1).toInt() - 1))
                else -> throw OracleError("bad selector ${sel.text} in: $line")
            }
            if (matches.size != 1) throw OracleError("selector ${sel?.text ?: "(none)"} matches ${matches.size} items in: $line")
            return Target(id = idOf(matches.single()))
        }
        fun alarmTarget() = target(world.alarms, { (it as Alarm).id }, { (it as Alarm).label }, { Instant.ofEpochMilli((it as Alarm).nextMs).atZone(zone).toLocalTime() }) { (it as Alarm).status == AlarmStatus.RINGING }
        fun timerTarget() = target(world.timers, { (it as Timer).id }, { (it as Timer).label }) { (it as Timer).status == TimerStatus.RINGING }
        fun swTarget() = target(world.stopwatches, { (it as Stopwatch).id }, { (it as Stopwatch).label }) { false }
        fun itemTarget() = target(world.shopping, { (it as ShoppingItem).id }, { (it as ShoppingItem).name }) { false }
        fun label(): Patch<String?>? = kv("label")?.let { Patch(if (it == "-") null else it.split('|').first()) }

        return when (head) {
            "alarm.add" -> AlarmCommand.Create(
                WhenSpec.At(args.firstNotNullOf { time(it.text) }, kv("day")?.let { day(it, today) }),
                quoted()?.split('|')?.first(), kv("rep")?.let(::repeat),
            )
            "alarm.in" -> AlarmCommand.Create(WhenSpec.After(dur() ?: throw OracleError(line)), quoted()?.split('|')?.first())
            "alarm.edit" -> {
                val target = alarmTarget()
                val current = Instant.ofEpochMilli(world.alarms.first { it.id == target.id }.nextMs).atZone(zone).toLocalDateTime()
                val at = kv("at")?.let { time(it) ?: throw OracleError(line) }
                val newDay = kv("day")?.let { day(it, today) }
                val whenSpec = if (at != null || newDay != null) WhenSpec.At(at ?: current.toLocalTime(), newDay ?: DayRef.On(current.toLocalDate())) else null
                AlarmCommand.Update(target, whenSpec, label(), kv("rep")?.let { Patch(if (it == "-") null else repeat(it)) })
            }
            "alarm.del" -> AlarmCommand.Cancel(alarmTarget())
            "alarm.delall" -> AlarmCommand.CancelAll
            "alarm.off" -> AlarmCommand.Disable(alarmTarget())
            "alarm.on" -> AlarmCommand.Enable(alarmTarget())
            "alarm.snooze" -> AlarmCommand.Snooze(if (selector() != null) alarmTarget() else null, ((dur() ?: 600_000L) / 60_000L).toInt())
            "alarm.stop" -> AlarmCommand.Dismiss(if (selector() != null) alarmTarget() else null)
            "alarm.list" -> AlarmCommand.List
            "timer.add" -> TimerCommand.Create(dur() ?: throw OracleError(line), quoted()?.split('|')?.first())
            "timer.pause" -> TimerCommand.Pause(timerTarget())
            "timer.resume" -> TimerCommand.Resume(timerTarget())
            "timer.del" -> TimerCommand.Cancel(timerTarget())
            "timer.delall" -> TimerCommand.CancelAll
            "timer.get" -> TimerCommand.Get(timerTarget())
            "timer.list" -> TimerCommand.List
            "timer.plus" -> TimerCommand.Update(timerTarget(), TimerChange.Add(dur() ?: throw OracleError(line)))
            "timer.minus" -> TimerCommand.Update(timerTarget(), TimerChange.Subtract(dur() ?: throw OracleError(line)))
            "timer.set" -> TimerCommand.Update(timerTarget(), TimerChange.SetRemaining(dur() ?: throw OracleError(line)))
            "timer.rename" -> TimerCommand.Update(timerTarget(), TimerChange.Rename(quoted()?.split('|')?.first()))
            "timer.stop" -> TimerCommand.Dismiss(if (selector() != null) timerTarget() else null)
            "sw.add" -> StopwatchCommand.Start(quoted()?.split('|')?.first())
            "sw.pause" -> StopwatchCommand.Pause(swTarget())
            "sw.resume" -> StopwatchCommand.Resume(swTarget())
            "sw.reset" -> StopwatchCommand.Reset(swTarget())
            "sw.restart" -> StopwatchCommand.Restart(swTarget())
            "sw.del" -> StopwatchCommand.Delete(swTarget())
            "sw.delall" -> StopwatchCommand.DeleteAll
            "sw.get" -> StopwatchCommand.Get(swTarget())
            "sw.list" -> StopwatchCommand.List
            "list.add" -> ShoppingCommand.Add(quoted()?.split('|')?.first() ?: throw OracleError(line), kv("n")?.replace(',', '.')?.toDouble(), kv("unit"))
            "list.del" -> ShoppingCommand.Remove(itemTarget())
            "list.check" -> ShoppingCommand.Mark(itemTarget())
            "list.uncheck" -> ShoppingCommand.Unmark(itemTarget())
            "list.edit" -> ShoppingCommand.Update(
                itemTarget(),
                name = kv("name")?.let { Patch(it) },
                quantity = kv("n")?.let { Patch(it.replace(',', '.').toDouble()) },
                unit = kv("unit")?.let { Patch(if (it == "-") null else it) },
            )
            "list.clear" -> ShoppingCommand.Clear
            "list.get" -> ShoppingCommand.List
            "vol.set" -> VolumeCommand.Set(int() ?: throw OracleError(line))
            "vol.up" -> VolumeCommand.Increase(int() ?: 10)
            "vol.down" -> VolumeCommand.Decrease(int() ?: 10)
            "vol.mute" -> VolumeCommand.Mute
            "vol.unmute" -> VolumeCommand.Restore
            "vol.get" -> VolumeCommand.Get
            "time.now" -> DateTimeCommand.Now(
                when (args.firstOrNull { !it.quoted }?.text ?: "time") {
                    "time" -> DatePart.TIME
                    "date" -> DatePart.DATE
                    "weekday" -> DatePart.WEEKDAY
                    "datetime" -> DatePart.DATETIME
                    else -> throw OracleError(line)
                },
                quoted(),
            )
            "time.weekday" -> DateTimeCommand.Weekday(date(args.first().text, today))
            "time.until" -> DateTimeCommand.DaysBetween(today, date(args.first().text, today))
            "calc" -> Calculate(quoted() ?: throw OracleError(line))
            // conv <amount> <from> <to> ['ingredient']: unit codes of the contract (Units).
            "conv" -> args.filter { !it.quoted }.let { plain ->
                if (plain.size != 3) throw OracleError(line)
                Convert(plain[0].text.toDouble(), plain[1].text, plain[2].text, quoted())
            }
            else -> throw OracleError("unknown action: $line")
        }
    }

    /** Label alternatives written as 'gimnasio|el gym' in the oracle action. */
    fun labelAlternatives(line: String): List<String> {
        val tokens = tokens(line)
        val text = tokens.firstNotNullOfOrNull { it.value("label") } ?: tokens.firstOrNull { it.quoted }?.text
        return text?.split('|').orEmpty()
    }
}

// ---------------------------------------------------------------------------------------------
// Normalized view: what the user can observe, without ids or order.
// ---------------------------------------------------------------------------------------------

data class WorldView(
    val alarms: List<String>,
    val timers: List<String>,
    val stopwatches: List<String>,
    val shopping: List<String>,
    val volume: String,
) {
    fun lines(): List<String> = alarms.map { "alarma $it" } + timers.map { "temporizador $it" } +
        stopwatches.map { "crono $it" } + shopping.map { "compra $it" } + listOf("volumen $volume")

    companion object {
        fun of(state: WorldState, nowMs: Long, zone: ZoneId): WorldView {
            val world = state.world
            fun label(value: String?) = SpanishText.key(value).orEmpty()
            return WorldView(
                alarms = world.alarms.map { a ->
                    val at = Instant.ofEpochMilli(a.nextMs).atZone(zone).toLocalDateTime()
                    "${at.toLocalDate()} ${at.toLocalTime()} [${label(a.label)}] ${repeatCode(a.repeat)} ${a.status.code}"
                }.sorted(),
                timers = world.timers.map { t ->
                    "[${label(t.label)}] ${(t.remainingAt(nowMs) + 500) / 1000}s ${t.status.code}"
                }.sorted(),
                stopwatches = world.stopwatches.map { s ->
                    "[${label(s.label)}] ${s.elapsedAt(nowMs) / 1000}s ${s.status.code}"
                }.sorted(),
                shopping = world.shopping.map { i ->
                    "[${label(i.name)}] n=${i.quantity?.let(::number) ?: "-"} u=${unitKey(i.unit)} ${if (i.completed) "hecho" else "pendiente"}"
                }.sorted(),
                volume = if (state.volumeMuted || state.volumePercent == 0) "silencio" else "${state.volumePercent}",
            )
        }

        fun repeatCode(repeat: Repeat?): String = when (repeat) {
            null -> "una-vez"
            Repeat.Daily -> "diario"
            Repeat.Weekdays -> "laborables"
            Repeat.Weekends -> "finde"
            is Repeat.Weekly -> repeat.days.sorted().joinToString(",") { it.name.take(3).lowercase() }
        }

        private fun number(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        fun unitKey(unit: String?): String = when (val u = SpanishText.fold(unit.orEmpty())) {
            "", "ud", "uds", "unidad", "unidades", "u" -> "-"
            "l", "litro", "litros" -> "l"
            "kg", "kilo", "kilos", "kilogramo", "kilogramos" -> "kg"
            "g", "gr", "gramo", "gramos" -> "g"
            "ml", "mililitro", "mililitros" -> "ml"
            "docena", "docenas" -> "docena"
            "paquete", "paquetes" -> "paquete"
            "botella", "botellas" -> "botella"
            "bolsa", "bolsas" -> "bolsa"
            "lata", "latas" -> "lata"
            else -> u
        }
    }
}

/**
 * Expected world after a turn, with tolerances: labels may have alternatives; a label, quantity
 * or unit the user never gave ("" / "-") is not checked; live durations within one second.
 */
class ExpectedWorld(val state: WorldState, val nowMs: Long, val zone: ZoneId, private val aliases: Map<String, Set<String>> = emptyMap()) {
    fun diff(actual: WorldState): List<String> {
        val expected = WorldView.of(state, nowMs, zone)
        val got = WorldView.of(actual, nowMs, zone)
        val out = mutableListOf<String>()
        compare("alarma", expected.alarms, got.alarms, out)
        compare("temporizador", expected.timers, got.timers, out)
        compare("crono", expected.stopwatches, got.stopwatches, out)
        compare("compra", expected.shopping, got.shopping, out)
        if (expected.volume != got.volume) out += "volumen: esperado ${expected.volume}, real ${got.volume}"
        return out
    }

    private fun compare(kind: String, expected: List<String>, actual: List<String>, out: MutableList<String>) {
        val remaining = actual.toMutableList()
        val missing = mutableListOf<String>()
        // Exact matches first, so a lenient match never steals another item's twin.
        val pending = expected.filter { item -> if (item in remaining) { remaining.remove(item); false } else true }
        for (item in pending) {
            val match = remaining.firstOrNull { matches(item, it) }
            if (match != null) remaining.remove(match) else missing += item
        }
        missing.forEach { out += "$kind falta: $it" }
        remaining.forEach { out += "$kind sobra: $it" }
    }

    private fun matches(expected: String, actual: String): Boolean {
        val pattern = Regex("^(.*)\\[(.*)](.*)$")
        val e = pattern.matchEntire(expected) ?: return false
        val a = pattern.matchEntire(actual) ?: return false
        if (e.groupValues[1] != a.groupValues[1]) return false
        val label = e.groupValues[2]
        if (!(label.isEmpty() || label == a.groupValues[2] || aliases[label]?.contains(a.groupValues[2]) == true)) return false
        val eRest = e.groupValues[3].trim().split(' ')
        val aRest = a.groupValues[3].trim().split(' ')
        if (eRest.size != aRest.size) return false
        return eRest.indices.all { i ->
            val x = eRest[i]
            val y = aRest[i]
            val xs = x.removeSuffix("s").toLongOrNull()
            val ys = y.removeSuffix("s").toLongOrNull()
            when {
                x == y -> true
                x.endsWith("s") && y.endsWith("s") && xs != null && ys != null -> kotlin.math.abs(xs - ys) <= 1
                x == "n=-" && y.startsWith("n=") -> true
                x == "u=-" && y.startsWith("u=") -> true
                else -> false
            }
        }
    }
}

/** Renders a [JSONArray] of strings as a Kotlin list. */
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
