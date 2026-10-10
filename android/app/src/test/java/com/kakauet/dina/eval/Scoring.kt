package com.kakauet.dina.eval

import com.kakauet.dina.dialog.SpanishText
import com.kakauet.dina.dialog.Spoken
import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.DatePart
import com.kakauet.dina.tools.DateTimeCommand
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.StopwatchCommand
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.ToolData
import com.kakauet.dina.tools.ToolExecution
import com.kakauet.dina.tools.ToolOutcome
import com.kakauet.dina.tools.Units
import com.kakauet.dina.tools.VolumeCommand
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Result of executing an option's oracle actions on a copy of the world before the turn. */
class OracleRun(val expected: ExpectedWorld, val executions: List<ToolExecution>, val facts: List<Fact>)

object Oracle {
    fun run(pre: WorldState, nowMs: Long, zone: ZoneId, option: Expect, inject: Map<String, String> = emptyMap()): OracleRun {
        val sandbox = Sandbox(pre, nowMs, zone)
        sandbox.beginTurn(inject)
        val aliases = mutableMapOf<String, Set<String>>()
        val executions = option.actions.mapIndexed { index, action ->
            val command = when (action) {
                is OracleAction.Line -> Dsl.command(action.text, sandbox.state(), nowMs, zone).also {
                    val alternatives = Dsl.labelAlternatives(action.text)
                    if (alternatives.size > 1) aliases[SpanishText.key(alternatives.first())!!] = alternatives.mapNotNull(SpanishText::key).toSet()
                }
                is OracleAction.Command -> action.command
            }
            val outcome = sandbox.executor.execute(command)
            if (outcome is ToolOutcome.Failure && index !in sandbox.injected && !option.tolerateFailures) {
                throw OracleError("oracle action failed (${outcome.code}): ${(action as? OracleAction.Line)?.text ?: command}")
            }
            ToolExecution.of(command, outcome)
        }
        val state = sandbox.state()
        return OracleRun(ExpectedWorld(state, nowMs, zone, aliases), executions, executions.flatMap { facts(it, zone) })
    }

    /** Facts a good answer must give for one successful execution. */
    fun facts(execution: ToolExecution, zone: ZoneId): List<Fact> {
        val outcome = execution.outcome as? ToolOutcome.Success ?: return emptyList()
        val command = execution.command ?: return emptyList()
        val at = outcome.atMs
        fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().withSecond(0).withNano(0)
        return when (val data = outcome.data) {
            is ToolData.AlarmItem -> if (command is AlarmCommand.Create || command is AlarmCommand.Update || command is AlarmCommand.Enable || command is AlarmCommand.Snooze) listOf(Fact.Time(time(data.alarm.nextMs))) else emptyList()
            is ToolData.Alarms -> when {
                data.items.isEmpty() -> listOf(Fact.Neg)
                data.items.size <= 3 -> data.items.map { Fact.Time(time(it.nextMs)) }
                else -> listOf(Fact.Num(data.items.size.toDouble()))
            }
            is ToolData.TimerItem -> when (command) {
                is TimerCommand.Create -> listOf(Fact.Dur(data.timer.durationMs / 1000))
                is TimerCommand.Get, is TimerCommand.Update -> listOf(Fact.Dur((data.timer.remainingAt(at) + 999) / 1000))
                else -> emptyList()
            }
            is ToolData.Timers -> when {
                data.items.isEmpty() -> listOf(Fact.Neg)
                data.items.size <= 3 -> data.items.map { t -> Fact.AnyOf(listOfNotNull(t.label?.let { Fact.Word(listOf(it)) }, Fact.Dur((t.remainingAt(at) + 999) / 1000))) }
                else -> listOf(Fact.Num(data.items.size.toDouble()))
            }
            is ToolData.StopwatchItem -> if (command is StopwatchCommand.Get) listOf(Fact.Dur(data.stopwatch.elapsedAt(at) / 1000)) else emptyList()
            is ToolData.Stopwatches -> when {
                data.items.isEmpty() -> listOf(Fact.Neg)
                data.items.size <= 3 -> data.items.map { s -> Fact.AnyOf(listOfNotNull(s.label?.let { Fact.Word(listOf(it)) }, Fact.Dur(s.elapsedAt(at) / 1000))) }
                else -> listOf(Fact.Num(data.items.size.toDouble()))
            }
            is ToolData.ShoppingEntry -> if (command is ShoppingCommand.Add) listOf(Fact.Word(listOf(data.item.name))) else emptyList()
            is ToolData.ShoppingList -> when {
                data.items.isEmpty() -> listOf(Fact.Neg)
                data.items.size <= 6 -> data.items.map { Fact.Word(listOf(it.name)) }
                else -> listOf(Fact.Num(data.items.size.toDouble()))
            }
            is ToolData.Volume -> when {
                command is VolumeCommand.Mute -> emptyList()
                data.info.muted -> listOf(Fact.Word(listOf("silencio", "silenciado", "silenciada", "mute", "sin sonido")))
                else -> listOf(Fact.Num(data.info.percent.toDouble()))
            }
            is ToolData.DateTimeValue -> when (command) {
                is DateTimeCommand.Now -> when (command.part) {
                    DatePart.TIME -> listOf(Fact.Time(LocalTime.parse(data.value).withSecond(0).withNano(0)))
                    DatePart.DATE -> listOf(Fact.Date(LocalDate.parse(data.value)))
                    DatePart.WEEKDAY -> listOf(Fact.Day(DAY_NAMES.getValue(data.value)))
                    DatePart.DATETIME -> java.time.OffsetDateTime.parse(data.value).let { listOf(Fact.Time(it.toLocalTime().withSecond(0).withNano(0)), Fact.Date(it.toLocalDate())) }
                }
                is DateTimeCommand.Weekday -> listOf(Fact.Day(DAY_NAMES.getValue(data.value)))
                else -> emptyList()
            }
            is ToolData.DaysBetween -> listOf(Fact.Num(data.days.toDouble()))
            is ToolData.Number -> listOf(Fact.Num(data.value.toDouble()))
            // The result as Dina says it (rounded, or a kitchen fraction like "taza y media").
            is ToolData.Conversion -> listOf(conversionFact(data))
            else -> emptyList()
        }
    }

    /** The converted amount: its rounded number, or the words of a kitchen fraction ("2 tazas y media"). */
    fun conversionFact(data: ToolData.Conversion): Fact {
        val unit = Units.of(data.to)!!
        val words = Spoken(0, ZoneId.of("UTC")).amount(data.result, data.to).removePrefix("unos ").removePrefix("unas ")
        // Signs are not read by the number detector: "10 grados bajo cero" states 10.
        val number = Fact.Num(kotlin.math.abs(Units.round(data.result, unit)))
        return if (unit.kitchen) Fact.AnyOf(listOf(number, Fact.Word(listOf(words)))) else number
    }

    val DAY_NAMES = mapOf("mon" to "lunes", "tue" to "martes", "wed" to "miercoles", "thu" to "jueves", "fri" to "viernes", "sat" to "sabado", "sun" to "domingo")

    val READ_OPS = setOf("list", "get", "now", "weekday", "shift", "days_between", "offset_difference", "calculate", "convert")
    fun mutates(execution: ToolExecution) = execution.ok && execution.op !in READ_OPS
}

/** What a brain produced in one turn, as the evaluator sees it. */
data class TurnOutput(val answer: String, val executions: List<ToolExecution>)

data class Verdict(
    val option: Int,
    /** World and decision right (what happened). */
    val actionOk: Boolean,
    /** Everything right, answer included. */
    val pass: Boolean,
    val reasons: List<String>,
    val diff: List<String>,
)

object Scorer {
    private const val CHAT_MAX_WORDS = 40
    private const val ANSWER_MAX_WORDS = 50

    fun eligible(options: List<Expect>, policies: Map<String, String>): List<IndexedValue<Expect>> =
        options.withIndex().filter { (_, option) ->
            option.policy.all { (key, allowed) -> policies[key].let { it == null || it == "any" || it in allowed } }
        }

    /** Best verdict over the eligible options: full pass first, then action-only, then the closest miss. */
    fun score(
        turn: Turn, pre: WorldState, post: WorldState, output: TurnOutput,
        nowMs: Long, zone: ZoneId, policies: Map<String, String>,
    ): Verdict {
        val verdicts = eligible(turn.options, policies).map { (index, option) ->
            val run = Oracle.run(pre, nowMs, zone, option, turn.inject)
            fair(index, option, run, post, output)
        }
        require(verdicts.isNotEmpty()) { "no option eligible under $policies" }
        return verdicts.firstOrNull { it.pass } ?: verdicts.firstOrNull { it.actionOk } ?: verdicts.minBy { it.reasons.size }
    }

    fun fair(index: Int, option: Expect, run: OracleRun, post: WorldState, output: TurnOutput): Verdict {
        val diff = run.expected.diff(post)
        val action = mutableListOf<String>()
        val response = mutableListOf<String>()
        if (diff.isNotEmpty()) action += "estado"
        val raw = output.answer
        val text = SpanishText.normalize(raw)
        val asked = SpanishText.isQuestion(raw)
        val says = option.says ?: when (option.kind) {
            Kind.FAIL -> listOf(Fact.Failed)
            Kind.REJECT -> listOf(Fact.Cannot)
            Kind.ACT -> run.facts
            else -> emptyList()
        }
        when (option.kind) {
            Kind.ACT -> if (option.actions.isNotEmpty() && output.executions.none { it.ok } && run.executions.all { !Oracle.mutates(it) }) {
                // A query answered without running the tool cannot be trusted, even if it sounds right.
                if (!says.all { it.holds(raw, text) }) action += "no consulta"
            }
            Kind.ASK -> {
                if (!asked) action += "no pregunta"
                val ask = option.ask
                if (asked && ask?.slot != null && !askedFor(ask.slot, raw)) response += "pregunta otra cosa (${ask.slot})"
                ask?.candidates?.filterNot { it.holds(raw, text) }?.forEach { response += "no ofrece ${it.code}" }
            }
            Kind.REJECT -> Unit
            Kind.CHAT -> {
                if (output.executions.isNotEmpty()) action += "ejecuta en charla"
                if (SpanishText.wordCount(raw) > CHAT_MAX_WORDS) response += "larga"
            }
            Kind.ANSWER -> if (SpanishText.wordCount(raw) > ANSWER_MAX_WORDS) response += "larga"
            Kind.FAIL -> Unit
        }
        says.filterNot { it.holds(raw, text) }.forEach { response += "falta ${it.code}" }
        option.not.filter { it.holds(raw, text) }.forEach { response += "afirma ${it.code}" }
        if (raw.isBlank()) response += "sin respuesta"
        val claim = SpanishText.successClaim(raw)
        val mutated = output.executions.any(Oracle::mutates)
        val anyOk = output.executions.any { it.ok }
        if ((claim == SpanishText.Claim.STRONG && !mutated) || (claim == SpanishText.Claim.WEAK && !anyOk)) response += "afirma algo que no hizo"
        val actionOk = action.isEmpty()
        return Verdict(index, actionOk, actionOk && response.isEmpty(), action + response, diff)
    }

    private val SLOT_WORDS = mapOf(
        "time" to listOf("hora", "cuando", "a que"),
        "day" to listOf("dia", "cuando", "hoy", "manana", "fecha"),
        "ampm" to listOf("manana", "tarde", "noche", "am", "pm", "madrugada"),
        "duration" to listOf("cuanto", "minuto", "tiempo", "duracion", "hora"),
        "target" to listOf("cual", "que alarma", "que temporizador", "que crono", "que producto", "la de", "el de", "a cual"),
        "item" to listOf("que", "cual", "producto", "anad", "apunt"),
        "value" to listOf("cuanto", "volumen", "nivel", "porcentaje", "a que", "que numero"),
        "what" to listOf("que", "como", "ayud", "dime", "necesitas", "quieres"),
        "confirm" to listOf("segur", "confirm", "quieres que", "de verdad", "todas", "todos", "borro", "elimino", "vacio", "lo hago"),
        "expr" to listOf("que", "operacion", "cuenta", "calcul", "numero"),
        "label" to listOf("nombre", "como", "llam", "etiqueta", "para que"),
        "amount" to listOf("cuant", "cantidad"),
        "unit" to listOf("unidad", "que", "cual"),
        "ingredient" to listOf("ingrediente", "de que", "que es"),
    )

    fun askedFor(slot: String, raw: String): Boolean {
        val folded = " ${SpanishText.fold(raw)} "
        return SLOT_WORDS[slot]?.any { folded.contains(it) } ?: true
    }

    /** Words a question about [slot] can use (the oracle brain uses the first one). */
    fun slotPhrase(slot: String?): String = when (slot) {
        "time" -> "A qué hora"
        "day" -> "Qué día"
        "ampm" -> "De la mañana o de la tarde"
        "duration" -> "Cuánto tiempo"
        "target" -> "Cuál"
        "item" -> "Qué quieres que apunte"
        "value" -> "A cuánto lo pongo"
        "confirm" -> "Seguro que quieres borrarlo todo"
        "expr" -> "Qué operación"
        "label" -> "Cómo lo llamo"
        "amount" -> "Cuántos"
        "unit" -> "En qué unidad"
        "ingredient" -> "De qué ingrediente"
        else -> "Qué necesitas"
    }
}
