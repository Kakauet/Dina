package com.kakauet.dina.eval

import com.kakauet.dina.brain.Brain
import com.kakauet.dina.brain.BrainSpec
import com.kakauet.dina.brain.ModelSpec
import com.kakauet.dina.brain.ToolExecutor
import com.kakauet.dina.brain.TurnEvents
import com.kakauet.dina.brain.TurnResult
import com.kakauet.dina.brain.dina45.Dina45Brain
import com.kakauet.dina.dialog.Policies
import com.kakauet.dina.llm.TextGenerator
import com.kakauet.dina.tools.ToolExecution
import com.kakauet.dina.tools.ToolPresentation

/** Builds a fresh brain bound to one episode's sandbox; the model itself is shared. */
interface BrainFactory : AutoCloseable {
    val id: String
    fun create(sandbox: Sandbox): Brain
    /** Extra measurements for summary.json and the report (e.g. the Dina 4.5 prompt's fixed tokens). */
    fun notes(): Map<String, Any> = emptyMap()
    override fun close() = Unit
}

/**
 * A perfect brain that performs the option the runner tells it to and states its facts.
 * Validates episodes and the evaluator itself: it must score 100 %.
 */
class OracleBrain(private val sandbox: Sandbox) : Brain {
    override val spec: BrainSpec = object : BrainSpec {
        override val id = "oracle"
        override val displayName = "Oráculo"
        override val model = ModelSpec("", "", 0, "")
        override fun create(llm: TextGenerator, tools: ToolExecutor) = error("eval only")
    }

    var plan: Expect? = null

    override suspend fun runTurn(userText: String, events: TurnEvents): TurnResult {
        val option = checkNotNull(plan) { "oracle without plan" }
        val executions = if (option.kind == Kind.ACT || option.kind == Kind.FAIL) option.actions.map { action ->
            val command = when (action) {
                is OracleAction.Line -> Dsl.command(action.text, sandbox.state(), sandbox.nowMs, sandbox.zone)
                is OracleAction.Command -> action.command
            }
            ToolExecution.of(command, sandbox.executor.execute(command))
        } else emptyList()
        val facts = option.says ?: executions.flatMap { Oracle.facts(it, sandbox.zone) }
        val body = facts.joinToString(", ") { render(it) }
        val answer = when (option.kind) {
            Kind.ACT -> (if (executions.any(Oracle::mutates)) "Hecho: " else "Vale: ") + body + "."
            Kind.ASK -> "¿" + Scorer.slotPhrase(option.ask?.slot) +
                option.ask?.candidates.orEmpty().takeIf { it.isNotEmpty() }?.joinToString(" o ", prefix = ": ") { render(it) }.orEmpty() + "?"
            Kind.REJECT -> "No puedo hacer eso. $body"
            Kind.FAIL -> "No he podido hacerlo. $body"
            Kind.ANSWER, Kind.CHAT -> body.ifEmpty { "Vale." }
        }
        return TurnResult(answer.trim(), executions)
    }

    override fun newConversation() = Unit

    private fun render(fact: Fact): String = when (fact) {
        is Fact.Time -> "a las %d:%02d".format(fact.time.hour, fact.time.minute)
        is Fact.Dur -> ToolPresentation.duration(fact.seconds)
        is Fact.Num -> if (fact.value % 1.0 == 0.0) fact.value.toLong().toString() else fact.value.toString()
        is Fact.Day -> fact.name
        is Fact.Date -> "${fact.date.dayOfMonth} de ${MONTHS[fact.date.monthValue - 1]}"
        is Fact.Word -> fact.alternatives.first()
        Fact.Neg -> "no hay ninguno"
        Fact.Cannot -> "no puedo"
        Fact.Failed -> "no he podido"
        Fact.Yes -> "sí"
        is Fact.AnyOf -> render(fact.facts.first())
    }

    private val MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
}

/** The Dina 4.5 brain as shipped (grammar and one turn of history unless told otherwise). */
class Dina45Factory(private val llm: TextGenerator, policies: Map<String, String>, private val grammar: Boolean = true, private val historyTurns: Int = 1, private val prefill: Boolean = true) : BrainFactory {
    override val id = "dina45"

    private val policies = Policies.of(policies)
    override fun create(sandbox: Sandbox): Brain = Dina45Brain(llm, sandbox.executor, policies, seed = 0, grammar = grammar, historyTurns = historyTurns, prefillWhileSpeaking = prefill)
}

class OracleFactory : BrainFactory {
    override val id = "oracle"
    override fun create(sandbox: Sandbox): Brain = OracleBrain(sandbox)
}
