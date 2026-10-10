package com.kakauet.dina.dialog

import com.kakauet.dina.brain.ToolExecutor
import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.Calculate
import com.kakauet.dina.tools.Convert
import com.kakauet.dina.tools.DatePart
import com.kakauet.dina.tools.DateTimeCommand
import com.kakauet.dina.tools.DayRef
import com.kakauet.dina.tools.Patch
import com.kakauet.dina.tools.Repeat
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.StopwatchCommand
import com.kakauet.dina.tools.StopwatchStatus
import com.kakauet.dina.tools.Target
import com.kakauet.dina.tools.TimerChange
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.ToolData
import com.kakauet.dina.tools.ToolExecution
import com.kakauet.dina.tools.ToolSnapshot
import com.kakauet.dina.tools.Units
import com.kakauet.dina.tools.VolumeCommand
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

enum class AmpmPolicy { ASK, NEXT, MIXED }

/**
 * Policies (docs/contrato.md). `ampm`: an hour from 1 to 11 without morning/afternoon
 * is asked, or the next occurrence (`mixed` also takes the morning for a wake-up label; the model
 * writes `mañana` when the context says so). `bulk`: deleting several asks first only when
 * [bulkConfirm] and there are at least [bulkThreshold]; otherwise it deletes and `undo()` restores.
 */
data class Policies(val ampm: AmpmPolicy = AmpmPolicy.MIXED, val bulkConfirm: Boolean = false, val bulkThreshold: Int = 3) {
    companion object {
        /** From the evaluator's `policy=ampm:mixed+bulk:direct`; missing keys keep the app's choice. */
        fun of(values: Map<String, String>) = Policies(
            ampm = when (values["ampm"]) { "ask" -> AmpmPolicy.ASK; "next" -> AmpmPolicy.NEXT; else -> AmpmPolicy.MIXED },
            bulkConfirm = values["bulk"] == "confirm",
        )
    }
}

/** What a pending action is waiting for. */
sealed interface Need {
    /** A value the user did not give: time, dur, name, n, expr, day, label, left, change. */
    data class Slot(val slot: String) : Need
    /** Which of these items. */
    data class Choice(val ids: List<String>) : Need
    /** Morning or afternoon for an alarm. */
    data class Ampm(val options: List<LocalTime>) : Need
    /** Yes or no (bulk delete, "¿la pongo para mañana?"). */
    data object Confirm : Need
}

data class Pending(val action: Action, val need: Need, val question: Reply, val turn: Int, val atMs: Long, val reminded: Boolean = false)

/**
 * The dialogue's view of the conversation for the prompt: [focus] (most recent first), lists just
 * read (positions "as read"), what is pending and whether the volume was just talked about.
 */
data class DialogueView(
    val focus: List<Pair<Domain, String>> = emptyList(),
    val read: Map<Domain, List<String>> = emptyMap(),
    val pending: Pending? = null,
    val volumeRecent: Boolean = false,
)

data class DialogueTurn(val answer: String, val executions: List<ToolExecution>, val replies: List<Reply>, val brief: String)

/**
 * The turn engine of Dina 4.5 (docs/motor.md): takes the canonical actions a model read in a
 * phrase and decides everything else: references, policies, what is pending, focus, undo, and
 * the answer. All changes go through the [ToolExecutor]; the dialogue state is ephemeral.
 */
class Dialogue(
    private val tools: ToolExecutor,
    val policies: Policies = Policies(),
    seed: Long = 0,
    private val funBank: FunBank = FunBank.shipped(seed),
) {
    private val responder = Responder(seed)
    private var turn = 0
    private val focus = mutableMapOf<Domain, Mark>()
    private val read = mutableMapOf<Domain, Mark>()
    private var journal: List<List<ToolCommand>> = emptyList()
    private var journalTurn = 0
    private var volumeTurn = Int.MIN_VALUE / 2

    var pending: Pending? = null
        private set

    private data class Mark(val ids: List<String>, val turn: Int, val atMs: Long)

    fun reset() {
        turn = 0
        focus.clear()
        read.clear()
        pending = null
        journal = emptyList()
        journalTurn = 0
        volumeTurn = Int.MIN_VALUE / 2
    }

    fun view(): DialogueView = DialogueView(
        focus = focus.entries.sortedByDescending { it.value.turn }.map { it.key to it.value.ids.single() },
        read = read.mapValues { it.value.ids },
        pending = pending,
        volumeRecent = turn - volumeTurn < FOCUS_TURNS,
    )

    fun snapshot(): ToolSnapshot = checkNotNull(tools.snapshot()) { "the dialogue needs an executor with state" }

    /** Runs one turn. [misread] when the model wrote something that could not be read. */
    fun run(actions: List<Action>, misread: Boolean = false, onExecuting: (String) -> Unit = {}): DialogueTurn {
        turn++
        val t = TurnRun(onExecuting)
        expire(t.snap.nowMs)
        val before = pending
        for (action in actions) {
            when (action.op) {
                "say" -> action.text("text")?.let { t.says += it }
                "fun.joke" -> t.replies += Reply.Fun(FunBank.Kind.JOKE, funBank.next(FunBank.Kind.JOKE))
                "fun.fact" -> t.replies += Reply.Fun(FunBank.Kind.FACT, funBank.next(FunBank.Kind.FACT))
                "ask" -> t.replies += Reply.AskWhat
                "no" -> t.replies += Reply.Reject(action.text("topic") ?: "otro")
                "undo" -> t.undo()
                "drop", "nope" -> { t.replies += Reply.Dropped(pending != null); pending = null }
                "yes" -> t.yes()
                "stop" -> t.stop()
                else -> t.domain(action)
            }
        }
        // A creation that answers an open "¿a qué hora…?" in another way (a timer instead of an alarm) closes it.
        val created = t.executions.any { it.ok && (it.op == "create") }
        if (before != null && pending == before && created && before.action.op in setOf("alarm.add", "timer.add")) pending = null
        val stillOpen = pending
        if (stillOpen != null && stillOpen == before && !stillOpen.reminded && t.replies.none { it.isQuestion } && (t.replies.isNotEmpty() || t.says.isNotEmpty())) {
            t.replies += Reply.Remind(stillOpen.question)
            pending = stillOpen.copy(reminded = true)
        }
        if (t.inverses.isNotEmpty()) { journal = t.inverses; journalTurn = turn } else if (t.undid) journal = emptyList()
        if (t.replies.isEmpty() && t.says.isEmpty() && misread) t.replies += Reply.NotUnderstood
        // The coletilla after an action may name what this very turn wrote ("¡Suerte en el médico!"); no other item.
        val written = actions.filter { it.op != "say" }.flatMap { a -> listOfNotNull(a.text("label"), a.text("name"), (a.target as? Ref.Named)?.text) }
        val names = t.snap.world.let { w -> w.alarms.mapNotNull { it.label } + w.timers.mapNotNull { it.label } + w.stopwatches.mapNotNull { it.label } + w.shopping.map { it.name } }
            .filter { name -> written.none { SpanishText.fold(it) == SpanishText.fold(name) } }
        val say = SayFilter.accept(t.says.joinToString(" ").ifBlank { null }, names, chatOnly = actions.all { it.op == "say" })
        val answer = responder.render(t.replies, say, Spoken(t.snap.nowMs, t.snap.zone))
        return DialogueTurn(answer, t.executions, t.replies, brief(t.replies, say != null))
    }

    private fun expire(nowMs: Long) {
        focus.entries.removeAll { turn - it.value.turn > FOCUS_TURNS || nowMs - it.value.atMs > FOCUS_MS }
        read.entries.removeAll { turn - it.value.turn > FOCUS_TURNS || nowMs - it.value.atMs > FOCUS_MS }
        pending?.let { if (turn - it.turn > PENDING_TURNS || nowMs - it.atMs > PENDING_MS) pending = null }
    }

    /** Short result for the prompt's `[antes]` block: "hecho", "falta hora", "2 alarmas"… */
    private fun brief(replies: List<Reply>, said: Boolean): String {
        val main = replies.firstOrNull { it !is Reply.Remind } ?: return if (said) "charla" else "nada"
        return when (main) {
            is Reply.Done -> if (Mutations.mutates(main.execution)) "hecho" else "respondido"
            is Reply.Listed -> "${main.items.size} " + when (main.domain) { Domain.ALARM -> "alarmas"; Domain.TIMER -> "temporizadores"; Domain.SW -> "cronómetros"; Domain.LIST -> "cosas" }
            is Reply.Info -> "respondido"
            is Reply.AskSlot -> "falta " + SLOT_NAMES.getOrDefault(main.slot, main.slot)
            is Reply.AskWhich -> "¿cuál?"
            is Reply.AskAmpm -> "¿mañana o tarde?"
            is Reply.AskConfirm, is Reply.PastToday -> "¿confirmar?"
            is Reply.NotFound -> "no existe"
            is Reply.Failed -> "falló"
            is Reply.Already -> "ya estaba"
            is Reply.Reject -> "no puedo"
            Reply.AskWhat -> "¿qué?"
            is Reply.Dropped -> "olvidado"
            is Reply.Fun -> if (main.kind == FunBank.Kind.JOKE) "chiste" else "curiosidad"
            else -> "respondido"
        }
    }

    /** Everything one turn does; kept apart so each action sees the world as the previous one left it. */
    private inner class TurnRun(val onExecuting: (String) -> Unit) {
        var snap: ToolSnapshot = snapshot()
        val replies = mutableListOf<Reply>()
        val executions = mutableListOf<ToolExecution>()
        val inverses = mutableListOf<List<ToolCommand>>()
        val says = mutableListOf<String>()
        var undid = false

        val now: ZonedDateTime get() = Instant.ofEpochMilli(snap.nowMs).atZone(snap.zone)
        val today: LocalDate get() = now.toLocalDate()

        fun exec(command: ToolCommand, item: Item? = null, reply: Boolean = true, clamped: Boolean = false, undo: Boolean = false, soon: Boolean = false, inverse: (ToolExecution) -> List<ToolCommand> = { emptyList() }): ToolExecution {
            onExecuting(command.tool)
            val prior = snap
            val execution = ToolExecution.of(command, tools.execute(command))
            executions += execution
            snap = snapshot()
            if (execution.ok) {
                inverse(execution).takeIf { it.isNotEmpty() }?.let { inverses += it }
                // A removal does not carry its item: name it from the world as it was (undo answers need it).
                val named = item ?: (execution.data as? ToolData.RemovedId)?.id?.let { id -> Domain.entries.flatMap { it.items(prior.world) }.firstOrNull { it.id == id } }
                if (reply) replies += Reply.Done(execution, named, undo = undo, clamped = clamped, soon = soon)
            } else replies += Reply.Failed(execution, item)
            return execution
        }

        fun ask(action: Action, need: Need, question: Reply) {
            pending = Pending(action, need, question, turn, snap.nowMs)
            replies += question
        }

        fun setFocus(domain: Domain, id: String) { focus[domain] = Mark(listOf(id), turn, snap.nowMs) }

        fun setRead(domain: Domain, items: List<Item>) {
            if (items.size == 1) setFocus(domain, items.single().id)
            read[domain] = Mark(items.map { it.id }, turn, snap.nowMs)
        }

        fun context(domain: Domain, offered: List<String>?) = RefContext(focus[domain]?.ids?.single(), read[domain]?.ids, offered)

        /** The items an action refers to: null after answering (not found) or asking (several). */
        fun resolve(action: Action, domain: Domain, offered: List<String>?, said: Day? = null, many: Boolean = false): List<Item>? {
            // In the small hours "la de mañana" is the one that rings after sleeping, if there is one today.
            val day = if (said == Day.Tomorrow && smallHours && Resolver.resolve(action.target, domain, snap, context(domain, offered), Day.Today).isNotEmpty()) Day.Today else said
            val items = Resolver.resolve(action.target, domain, snap, context(domain, offered), day)
            return when {
                items.isEmpty() -> { replies += Reply.NotFound(domain, action.target, day); null }
                items.size == 1 || action.target == Ref.All || many -> items
                else -> { ask(action, Need.Choice(items.map { it.id }), Reply.AskWhich(action, items)); null }
            }
        }

        // ---- Control ----

        fun undo() {
            // "Cancela lo que estabas poniendo": a question asked after the last change is what gets undone.
            val open = pending
            if (open != null && (journal.isEmpty() || open.turn > journalTurn)) { replies += Reply.Dropped(true); pending = null; return }
            undid = true
            if (journal.isEmpty()) { replies += Reply.NothingToUndo; return }
            journal.reversed().forEach { group -> group.forEach { exec(it, undo = true) } }
            journal = emptyList()
        }

        fun yes() {
            val open = pending
            when {
                open == null -> replies += Reply.Ack
                open.need is Need.Confirm -> { pending = null; domain(open.action, confirmed = true) }
                else -> replies += open.question
            }
        }

        fun stop() {
            val ringingAlarms = snap.world.alarms.filter { it.status == AlarmStatus.RINGING }
            val ringingTimers = snap.world.timers.filter { it.status == TimerStatus.RINGING }
            if (ringingAlarms.isEmpty() && ringingTimers.isEmpty()) { replies += Reply.NothingRinging; return }
            ringingAlarms.forEach { exec(AlarmCommand.Dismiss(Target(id = it.id)), Item(Domain.ALARM, it.id, it.label, it)) }
            ringingTimers.forEach { exec(TimerCommand.Dismiss(Target(id = it.id)), Item(Domain.TIMER, it.id, it.label, it)) }
        }

        // ---- Domain actions ----

        fun domain(input: Action, confirmed: Boolean = false) {
            var action = input
            var offered: List<String>? = null
            var isConfirmed = confirmed
            val open = pending
            if (open != null && open.action.op == action.op) {
                // "¿A qué unidad lo paso?" → "a millas": a lone unit is where to.
                if (open.need == Need.Slot("to") && action.args.keys == setOf("from")) action = action.copy(args = mapOf("to" to action.args.getValue("from")))
                action = merge(open.action, action)
                when (open.need) {
                    is Need.Choice -> offered = open.need.ids
                    Need.Confirm -> isConfirmed = true
                    else -> Unit
                }
                pending = null
            }
            action.args.entries.firstOrNull { it.value == Missing }?.let { missing ->
                val slot = when (missing.key) { "at" -> "time"; else -> missing.key }
                ask(action.with(missing.key, null), Need.Slot(slot), Reply.AskSlot(action, slot))
                return
            }
            action.spec.required?.let { key ->
                if (action.args[key] == null) { ask(action, Need.Slot(key), Reply.AskSlot(action, key)); return }
            }
            when (action.domain) {
                "alarm" -> alarm(action, offered, isConfirmed)
                "timer" -> timer(action, offered, isConfirmed)
                "sw" -> stopwatch(action, offered, isConfirmed)
                "list" -> shopping(action, offered, isConfirmed)
                "vol" -> volume(action)
                "time" -> time(action)
                else -> when (action.op) {
                    "calc" -> exec(Calculate(action.text("expr")!!))
                    "conv" -> convert(action)
                }
            }
        }

        /** Asks for what is missing: the unit it is in, where to (if no everyday counterpart), the ingredient for cups ↔ grams. */
        private fun convert(a: Action) {
            val from = a.text("from") ?: run { ask(a, Need.Slot("from"), Reply.AskSlot(a, "from")); return }
            val to = a.text("to") ?: Units.defaultTarget(from) ?: run { ask(a, Need.Slot("to"), Reply.AskSlot(a, "to")); return }
            val action = a.with("to", to)
            if (Units.needsIngredient(from, to) && a.text("what") == null) { ask(action, Need.Slot("what"), Reply.AskSlot(action, "what")); return }
            exec(Convert(a.amount()!!, from, to, a.text("what")))
        }

        /** "Mañana" said before [SMALL_HOURS] is the morning after sleeping: today. */
        private val smallHours get() = now.hour < SMALL_HOURS

        private fun merge(old: Action, new: Action) = new.copy(
            target = new.target ?: old.target,
            args = old.args.filterValues { it != Missing } + new.args,
        )

        /** Bulk deletes ask first only under the confirm policy. */
        private fun confirmBulk(action: Action, domain: Domain, count: Int, confirmed: Boolean): Boolean {
            if (confirmed || !policies.bulkConfirm || count < policies.bulkThreshold) return true
            ask(action, Need.Confirm, Reply.AskConfirm(domain, count))
            return false
        }

        // ---- Alarms ----

        private fun alarm(a: Action, offered: List<String>?, confirmed: Boolean) {
            when (a.op) {
                "alarm.add" -> alarmAdd(a)
                "alarm.edit" -> {
                    // Which one first ("cambia una alarma" with two), then what to change.
                    val items = resolve(a, Domain.ALARM, offered) ?: return
                    if (listOf("at", "day", "repeat", "label").none { it in a.args }) {
                        items.singleOrNull()?.let { setFocus(Domain.ALARM, it.id) }
                        ask(if (items.size == 1) a.copy(target = Ref.Focus) else a, Need.Slot("change"), Reply.AskSlot(a, "change"))
                        return
                    }
                    items.forEach { alarmEdit(a, it) }
                }
                "alarm.del" -> {
                    val items = resolve(a, Domain.ALARM, offered, a.day()) ?: return
                    if (a.target == Ref.All && items.size > 1) {
                        if (!confirmBulk(a, Domain.ALARM, items.size, confirmed)) return
                        val inverse = items.flatMap { recreateAlarm(it) }
                        if (items.size == snap.world.alarms.size && offered == null) exec(AlarmCommand.CancelAll) { inverse }
                        else items.forEach { item -> exec(AlarmCommand.Cancel(Target(id = item.id)), item) { recreateAlarm(item) } }
                    } else items.forEach { item -> exec(AlarmCommand.Cancel(Target(id = item.id)), item) { recreateAlarm(item) } }
                }
                "alarm.off" -> resolve(a, Domain.ALARM, offered, a.day())?.forEach { item ->
                    setFocus(Domain.ALARM, item.id)
                    if (item.alarm.status == AlarmStatus.DISABLED) replies += Reply.Already("off", item)
                    else exec(AlarmCommand.Disable(Target(id = item.id)), item) { listOf(AlarmCommand.Enable(Target(id = item.id))) }
                }
                "alarm.on" -> resolve(a, Domain.ALARM, offered, a.day())?.forEach { item ->
                    setFocus(Domain.ALARM, item.id)
                    if (item.alarm.status == AlarmStatus.SCHEDULED) replies += Reply.Already("on", item)
                    else exec(AlarmCommand.Enable(Target(id = item.id)), item) { listOf(AlarmCommand.Disable(Target(id = item.id))) }
                }
                "alarm.get" -> {
                    val items = resolve(a, Domain.ALARM, offered, a.day(), many = true) ?: return
                    exec(AlarmCommand.List, reply = false)
                    if (items.size == 1) { setFocus(Domain.ALARM, items.single().id); replies += Reply.Info(items.single()) }
                    else { setRead(Domain.ALARM, items); replies += Reply.Listed(Domain.ALARM, items) }
                }
                "alarm.list" -> {
                    exec(AlarmCommand.List, reply = false)
                    val items = Domain.ALARM.items(snap.world)
                    setRead(Domain.ALARM, items)
                    replies += Reply.Listed(Domain.ALARM, items)
                }
                "alarm.snooze" -> {
                    val minutes = ((a.duration() ?: 600_000L) / 60_000L).toInt().coerceAtLeast(1)
                    exec(AlarmCommand.Snooze(null, minutes))
                }
            }
        }

        private fun alarmAdd(a: Action) {
            val clock = a.clock()!!
            val repeat = a.args["repeat"] as? Repeat
            val label = cleanLabel(a.text("label"))
            val early = a.day() == Day.Tomorrow && smallHours
            val day = if (early) Day.Today else a.day()
            var date = day?.let { d ->
                d.resolve(today) ?: run {
                    ask(a.with("day", null), Need.Slot("day"), Reply.NoSuchDate(d as Day.Date, asks = true))
                    return
                }
            }
            if (date != null && date.isBefore(today)) { replies += Reply.Past(date); return }
            val options = clock.times()
            val time = when {
                options.size == 1 -> options.single()
                policies.ampm == AmpmPolicy.ASK -> { ask(a, Need.Ampm(options), Reply.AskAmpm(options)); return }
                policies.ampm == AmpmPolicy.MIXED && label != null && WAKE.containsMatchIn(SpanishText.fold(label)) -> options.first()
                else -> options.minBy { next(it, date, repeat) }
            }
            var soon = early
            if (date != null && !LocalDateTime.of(date, time).atZone(snap.zone).isAfter(now)) {
                when {
                    early -> { date = today.plusDays(1); soon = false } // "mañana a la 1:00" said at 1:30 is the next night
                    day == Day.Today -> { ask(a.copy(args = a.args + ("time" to Clock.exact(time)) + ("day" to Day.Tomorrow)), Need.Confirm, Reply.PastToday(time)); return }
                    else -> { replies += Reply.Past(date); return }
                }
            }
            exec(AlarmCommand.Create(com.kakauet.dina.tools.WhenSpec.At(time, date?.let { DayRef.On(it) }), label, repeat), soon = soon) { execution ->
                val id = (execution.data as ToolData.AlarmItem).alarm.id
                setFocus(Domain.ALARM, id)
                listOf(AlarmCommand.Cancel(Target(id = id)))
            }
        }

        /** When [time] would ring next: on [date], or today/tomorrow (the next repeat day for [repeat]). */
        private fun next(time: LocalTime, date: LocalDate?, repeat: Repeat?): ZonedDateTime {
            if (date != null) return LocalDateTime.of(date, time).atZone(snap.zone).let { if (it.isAfter(now)) it else it.plusYears(100) }
            var at = LocalDateTime.of(today, time).atZone(snap.zone)
            if (!at.isAfter(now)) at = at.plusDays(1)
            if (repeat != null) at = generateSequence(at) { it.plusDays(1) }.take(7).firstOrNull { includes(repeat, it.dayOfWeek.value) } ?: at
            return at
        }

        private fun alarmEdit(a: Action, item: Item) {
            val alarm = item.alarm
            setFocus(Domain.ALARM, item.id)
            val current = Instant.ofEpochMilli(alarm.nextMs).atZone(snap.zone)
            val early = a.day() == Day.Tomorrow && smallHours
            val newDay = if (early) Day.Today else a.day()
            var date = current.toLocalDate()
            if (newDay != null) date = newDay.resolve(today) ?: run {
                replies += Reply.NoSuchDate(newDay as Day.Date, asks = false)
                return
            }
            var time = current.toLocalTime()
            when (val at = a.args["at"]) {
                is Clock -> time = at.times().minBy { circular(it, current.toLocalTime()) }
                is Shift -> current.plusNanos(at.ms * 1_000_000).let { shifted ->
                    time = shifted.toLocalTime()
                    if (newDay == null) date = shifted.toLocalDate()
                }
            }
            val timeChanged = a.args["at"] != null || newDay != null
            // A new time already gone today moves to its next occurrence (the engine applies the repeat).
            val whenSpec = when {
                !timeChanged -> null
                (newDay == null || early) && !LocalDateTime.of(date, time).atZone(snap.zone).isAfter(now) -> com.kakauet.dina.tools.WhenSpec.At(time, null)
                else -> com.kakauet.dina.tools.WhenSpec.At(time, DayRef.On(date))
            }
            val repeat = when (val r = a.args["repeat"]) { is Repeat -> Patch(r); NoRepeat -> Patch(null); else -> null }
            val label = a.text("label")?.let { Patch(it) }
            val inverse = listOf<ToolCommand>(
                AlarmCommand.Update(Target(id = item.id), com.kakauet.dina.tools.WhenSpec.At(current.toLocalTime(), DayRef.On(current.toLocalDate())), Patch(alarm.label), Patch(alarm.repeat)),
            ) + if (alarm.status == AlarmStatus.DISABLED) listOf(AlarmCommand.Disable(Target(id = item.id))) else emptyList()
            exec(AlarmCommand.Update(Target(id = item.id), whenSpec, label, repeat), item) { inverse }
        }

        private fun recreateAlarm(item: Item): List<ToolCommand> {
            val alarm = item.alarm
            val at = Instant.ofEpochMilli(alarm.nextMs).atZone(snap.zone)
            val create = AlarmCommand.Create(com.kakauet.dina.tools.WhenSpec.At(at.toLocalTime(), DayRef.On(at.toLocalDate())), alarm.label, alarm.repeat)
            // The new id is unknown until it runs; the restored alarm is the newest one.
            return listOf(create) + if (alarm.status == AlarmStatus.DISABLED) listOf(AlarmCommand.Disable(Target(position = com.kakauet.dina.tools.Position.Last))) else emptyList()
        }

        // ---- Timers ----

        private fun timer(a: Action, offered: List<String>?, confirmed: Boolean) {
            when (a.op) {
                "timer.add" -> {
                    val duration = a.duration()!!
                    if (duration <= 0) { ask(a.with("dur", null), Need.Slot("dur"), Reply.ZeroDuration); return }
                    exec(TimerCommand.Create(duration, cleanLabel(a.text("label")))) { execution ->
                        val id = (execution.data as ToolData.TimerItem).timer.id
                        setFocus(Domain.TIMER, id)
                        listOf(TimerCommand.Cancel(Target(id = id)))
                    }
                }
                "timer.list" -> list(Domain.TIMER, TimerCommand.List)
                "timer.get" -> get(a, Domain.TIMER, offered, TimerCommand.List)
                "timer.del" -> {
                    val items = resolve(a, Domain.TIMER, offered) ?: return
                    if (a.target == Ref.All && items.size > 1 && !confirmBulk(a, Domain.TIMER, items.size, confirmed)) return
                    if (a.target == Ref.All && items.size > 1 && items.size == snap.world.timers.size && offered == null) exec(TimerCommand.CancelAll) { items.flatMap(::recreateTimer) }
                    else items.forEach { item ->
                        // Removing one that is ringing is turning it off.
                        if (item.timer.status == TimerStatus.RINGING) exec(TimerCommand.Dismiss(Target(id = item.id)), item)
                        else exec(TimerCommand.Cancel(Target(id = item.id)), item) { recreateTimer(item) }
                    }
                }
                else -> resolve(a, Domain.TIMER, offered)?.forEach { item ->
                    setFocus(Domain.TIMER, item.id)
                    val target = Target(id = item.id)
                    val timer = item.timer
                    val restore = listOf(TimerCommand.Update(target, TimerChange.SetRemaining(timer.remainingAt(snap.nowMs).coerceAtLeast(1_000L))))
                    when (a.op) {
                        "timer.pause" -> exec(TimerCommand.Pause(target), item) { listOf(TimerCommand.Resume(target)) }
                        "timer.resume" -> exec(TimerCommand.Resume(target), item) { listOf(TimerCommand.Pause(target)) }
                        "timer.plus" -> exec(TimerCommand.Update(target, TimerChange.Add(a.duration()!!)), item) { restore }
                        "timer.minus" -> exec(TimerCommand.Update(target, TimerChange.Subtract(a.duration()!!)), item) { restore }
                        "timer.edit" -> {
                            val left = a.duration("left")
                            val label = a.text("label")
                            if (left == null && label == null) { ask(a, Need.Slot("left"), Reply.AskSlot(a, "left")); return@forEach }
                            if (label != null) exec(TimerCommand.Update(target, TimerChange.Rename(label)), item, reply = left == null) { listOf(TimerCommand.Update(target, TimerChange.Rename(timer.label))) }
                            if (left != null) exec(TimerCommand.Update(target, TimerChange.SetRemaining(left)), item) { restore }
                        }
                    }
                }
            }
        }

        private fun recreateTimer(item: Item): List<ToolCommand> {
            val timer = item.timer
            val create = TimerCommand.Create(timer.remainingAt(snap.nowMs).coerceAtLeast(1_000L), timer.label)
            return listOf(create) + if (timer.status == TimerStatus.PAUSED) listOf(TimerCommand.Pause(Target(position = com.kakauet.dina.tools.Position.Last))) else emptyList()
        }

        // ---- Stopwatches ----

        private fun stopwatch(a: Action, offered: List<String>?, confirmed: Boolean) {
            when (a.op) {
                "sw.add" -> exec(StopwatchCommand.Start(cleanLabel(a.text("label")))) { execution ->
                    val id = (execution.data as ToolData.StopwatchItem).stopwatch.id
                    setFocus(Domain.SW, id)
                    listOf(StopwatchCommand.Delete(Target(id = id)))
                }
                "sw.list" -> list(Domain.SW, StopwatchCommand.List)
                "sw.get" -> get(a, Domain.SW, offered, StopwatchCommand.List)
                "sw.del" -> {
                    val items = resolve(a, Domain.SW, offered) ?: return
                    if (a.target == Ref.All && items.size > 1 && !confirmBulk(a, Domain.SW, items.size, confirmed)) return
                    items.forEach { item -> exec(StopwatchCommand.Delete(Target(id = item.id)), item) { listOf(StopwatchCommand.Start(item.name)) } }
                }
                else -> resolve(a, Domain.SW, offered)?.forEach { item ->
                    setFocus(Domain.SW, item.id)
                    val target = Target(id = item.id)
                    val running = item.stopwatch.status == StopwatchStatus.RUNNING
                    when (a.op) {
                        "sw.pause" -> exec(StopwatchCommand.Pause(target), item) { listOf(StopwatchCommand.Resume(target)) }
                        "sw.resume" -> exec(StopwatchCommand.Resume(target), item) { listOf(StopwatchCommand.Pause(target)) }
                        "sw.reset" -> exec(StopwatchCommand.Reset(target), item)
                        "sw.restart" -> exec(StopwatchCommand.Restart(target), item) { if (running) emptyList() else listOf(StopwatchCommand.Pause(target)) }
                    }
                }
            }
        }

        // ---- Shopping ----

        private fun shopping(a: Action, offered: List<String>?, confirmed: Boolean) {
            when (a.op) {
                "list.add" -> exec(ShoppingCommand.Add(a.text("name")!!, a.number(), a.text("unit"))) { execution ->
                    val id = (execution.data as ToolData.ShoppingEntry).item.id
                    setFocus(Domain.LIST, id)
                    listOf(ShoppingCommand.Remove(Target(id = id)))
                }
                "list.list" -> {
                    exec(ShoppingCommand.List, reply = false)
                    // Read as spoken: what is pending first, then what is bought.
                    val items = Domain.LIST.items(snap.world).sortedBy { it.product.completed }
                    setRead(Domain.LIST, items)
                    replies += Reply.Listed(Domain.LIST, items)
                }
                "list.del" -> {
                    val items = resolve(a, Domain.LIST, offered) ?: return
                    if (a.target == Ref.All && items.size > 1) {
                        if (!confirmBulk(a, Domain.LIST, items.size, confirmed)) return
                        if (items.size == snap.world.shopping.size && offered == null) { exec(ShoppingCommand.Clear) { items.flatMap(::recreateProduct) }; return }
                    }
                    items.forEach { item -> exec(ShoppingCommand.Remove(Target(id = item.id)), item) { recreateProduct(item) } }
                }
                "list.check", "list.uncheck" -> resolve(a, Domain.LIST, offered)?.forEach { item ->
                    setFocus(Domain.LIST, item.id)
                    val check = a.op == "list.check"
                    val target = Target(id = item.id)
                    if (item.product.completed == check) replies += Reply.Already(if (check) "checked" else "unchecked", item)
                    else if (check) exec(ShoppingCommand.Mark(target), item) { listOf(ShoppingCommand.Unmark(target)) }
                    else exec(ShoppingCommand.Unmark(target), item) { listOf(ShoppingCommand.Mark(target)) }
                }
                "list.edit" -> resolve(a, Domain.LIST, offered)?.forEach { item ->
                    setFocus(Domain.LIST, item.id)
                    val product = item.product
                    val quantity = when (val n = a.args["n"]) {
                        is Double -> Patch(n)
                        is Delta -> Patch(((product.quantity ?: 1.0) + n.value).coerceAtLeast(1.0))
                        else -> null
                    }
                    val name = a.text("name")?.let { Patch(it) }
                    val unit = a.text("unit")?.let { Patch(it) }
                    if (quantity == null && name == null && unit == null) { ask(a, Need.Slot("n"), Reply.AskSlot(a, "n")); return@forEach }
                    val target = Target(id = item.id)
                    exec(ShoppingCommand.Update(target, name, quantity, unit), item) {
                        listOf(ShoppingCommand.Update(target, Patch(product.name), Patch(product.quantity), Patch(product.unit)))
                    }
                }
            }
        }

        private fun recreateProduct(item: Item): List<ToolCommand> {
            val product = item.product
            return listOf(ShoppingCommand.Add(product.name, product.quantity, product.unit)) +
                if (product.completed) listOf(ShoppingCommand.Mark(Target(name = product.name))) else emptyList()
        }

        // ---- Reads shared by timers and stopwatches ----

        private fun list(domain: Domain, command: ToolCommand) {
            exec(command, reply = false)
            val items = domain.items(snap.world)
            setRead(domain, items)
            replies += Reply.Listed(domain, items)
        }

        private fun get(a: Action, domain: Domain, offered: List<String>?, command: ToolCommand) {
            val items = resolve(a, domain, offered, many = true) ?: return
            exec(command, reply = false)
            val fresh = domain.items(snap.world).filter { item -> items.any { it.id == item.id } }
            if (fresh.size == 1) { setFocus(domain, fresh.single().id); replies += Reply.Info(fresh.single()) }
            else { setRead(domain, fresh); replies += Reply.Listed(domain, fresh) }
        }

        // ---- Volume ----

        private fun volume(a: Action) {
            volumeTurn = turn
            val info = snap.volume
            val restore = if (info.muted) listOf<ToolCommand>(VolumeCommand.Mute) else listOf(VolumeCommand.Set(info.percent))
            when (a.op) {
                "vol.set" -> {
                    val n = a.int()!!
                    exec(VolumeCommand.Set(n.coerceIn(0, 100)), clamped = n > 100) { restore }
                }
                "vol.up" -> if (!info.muted && info.percent >= 100) replies += Reply.Already("max", volume = info)
                    else exec(VolumeCommand.Increase(a.int() ?: STEP)) { restore }
                "vol.down" -> if (info.muted || info.percent <= 0) replies += Reply.Already("min", volume = info)
                    else exec(VolumeCommand.Decrease(a.int() ?: STEP)) { restore }
                "vol.mute" -> if (info.muted) replies += Reply.Already("muted", volume = info) else exec(VolumeCommand.Mute) { restore }
                "vol.unmute" -> if (!info.muted) replies += Reply.Already("unmuted", volume = info) else exec(VolumeCommand.Restore) { restore }
                "vol.get" -> exec(VolumeCommand.Get)
            }
        }

        // ---- Date and time ----

        private fun time(a: Action) {
            when (a.op) {
                "time.now" -> exec(DateTimeCommand.Now(when (a.text("part")) { "fecha" -> DatePart.DATE; "dia" -> DatePart.DATE; else -> DatePart.TIME }, a.text("place")))
                else -> {
                    val day = a.day()!!
                    val date = day.resolve(today) ?: run { replies += Reply.NoSuchDate(day as Day.Date, asks = false); return }
                    if (a.op == "time.weekday") exec(DateTimeCommand.Weekday(date))
                    else exec(DateTimeCommand.DaysBetween(today, date))
                }
            }
        }
    }

    companion object {
        const val FOCUS_TURNS = 3
        const val FOCUS_MS = 10 * 60_000L
        const val PENDING_TURNS = 2
        const val PENDING_MS = 2 * 60_000L
        const val STEP = 10
        /** Until this hour, "mañana" is the coming morning (today). */
        const val SMALL_HOURS = 5
        private val WAKE = Regex("\\b(despert|levant|madrug)")
        private val GENERIC_LABELS = setOf("timer", "temporizador", "alarma", "cronometro", "crono")

        val SLOT_NAMES = mapOf(
            "time" to "hora", "dur" to "duración", "name" to "producto", "n" to "valor", "expr" to "cuenta",
            "day" to "día", "label" to "nombre", "left" to "tiempo", "change" to "cambio",
            "from" to "unidad", "to" to "unidad", "what" to "ingrediente",
        )

        /** A label that only repeats a duration or the kind of thing ("15m", "timer") is no label. */
        fun cleanLabel(label: String?): String? = label?.takeUnless { Durations.parse(it.trim()) != null || SpanishText.fold(it).trim() in GENERIC_LABELS }

        private fun includes(repeat: Repeat, day: Int) = when (repeat) {
            Repeat.Daily -> true
            Repeat.Weekdays -> day <= 5
            Repeat.Weekends -> day >= 6
            is Repeat.Weekly -> repeat.days.any { it.value == day }
        }

        /** Minutes between two times of day, the short way round. */
        private fun circular(a: LocalTime, b: LocalTime): Int {
            val diff = abs(a.toSecondOfDay() - b.toSecondOfDay()) / 60
            return minOf(diff, 24 * 60 - diff)
        }
    }
}

/** `n=+2`: a change relative to what there is ("dos más"). */
data class Delta(val value: Double)

/** Read-only operations (they never go to the undo journal). */
object Mutations {
    private val READS = setOf("list", "get", "now", "weekday", "shift", "days_between", "offset_difference", "calculate", "convert")
    fun mutates(execution: ToolExecution) = execution.ok && execution.op !in READS
}
