package com.kakauet.dina.eval

import com.kakauet.dina.dialog.SpanishText
import com.kakauet.dina.brain.TurnResult
import java.time.ZoneId

data class TurnRecord(
    val episode: String,
    val turn: Int,
    val user: String,
    val answer: String,
    val executions: List<String>,
    val expectedKind: Kind,
    val verdict: Verdict,
    val wallMs: Double,
    val promptTokens: Int,
    val reusedTokens: Int,
    val completionTokens: Int,
    /** No tool ran and the answer is a question. */
    val askedByText: Boolean,
    val mutated: Boolean,
    /** What the model wrote (the contract lines), to tell model errors from engine errors. */
    val modelOutput: String,
)

data class EpisodeRecord(
    val episode: Episode,
    val turns: List<TurnRecord>,
    /** Every turn fully right (world, decision and answer). */
    val pass: Boolean,
    /** Every turn's world and decision right; the answer may be lacking. */
    val actionPass: Boolean,
)

/** A brain whose model is told the expected output of each turn (the design ceiling of Dina 4.5). */
interface ScriptedBrain {
    fun plan(episode: Episode, turn: Turn, eligible: List<IndexedValue<Expect>>, upcoming: List<Turn>)
}

/**
 * Runs episodes turn by turn against the real engine. A turn whose world or decision is wrong
 * ends the episode (later scripted turns would no longer make sense); a turn whose only problem
 * is the answer continues, so action and answer quality can be told apart.
 */
class EvalRunner(
    private val factory: BrainFactory,
    private val policies: Map<String, String> = emptyMap(),
    /** Call [Brain.prepare] before each turn, as the app does while the user speaks (Dina 4.5 prefills its prompt). */
    private val warm: Boolean = false,
) {
    /** [choices] fixes the option the oracle brain takes at each turn of the path (default: first eligible). */
    suspend fun run(episode: Episode, choices: List<Int>? = null): EpisodeRecord {
        val zone: ZoneId = episode.zone
        val sandbox = Sandbox(episode.initial, episode.clock.atZone(zone).toInstant().toEpochMilli(), zone)
        val brain = factory.create(sandbox)
        brain.newConversation()
        val records = mutableListOf<TurnRecord>()
        var turns = episode.turns
        var index = 0
        var step = 0
        var pass = true
        var actionPass = true
        while (index < turns.size) {
            val turn = turns[index]
            sandbox.advance(turn.waitMs)
            val pre = sandbox.state()
            val eligible = Scorer.eligible(turn.options, policies)
            if (brain is OracleBrain) {
                val wanted = choices?.getOrNull(step)
                brain.plan = (eligible.firstOrNull { it.index == wanted } ?: eligible.first()).value
            }
            if (brain is ScriptedBrain) brain.plan(episode, turn, eligible, turns.drop(index + 1))
            sandbox.beginTurn(turn.inject)
            if (warm) brain.prepare()
            val started = System.nanoTime()
            val result: TurnResult = brain.runTurn(turn.user)
            val wallMs = (System.nanoTime() - started) / 1e6
            val post = sandbox.state()
            val output = TurnOutput(result.answer, result.executions)
            val verdict = Scorer.score(turn, pre, post, output, sandbox.nowMs, zone, policies)
            val llm = result.metrics.llm
            records += TurnRecord(
                episode = episode.id,
                turn = step + 1,
                user = turn.user,
                answer = result.answer,
                executions = result.executions.map { e -> "${e.tool}.${e.op}" + if (e.ok) "" else " ✗${(e.outcome as com.kakauet.dina.tools.ToolOutcome.Failure).code}" },
                expectedKind = turn.options[verdict.option].kind,
                verdict = verdict,
                wallMs = wallMs,
                promptTokens = llm.promptTokens,
                reusedTokens = llm.reusedPromptTokens,
                completionTokens = llm.completionTokens,
                askedByText = result.executions.isEmpty() && SpanishText.isQuestion(result.answer),
                mutated = result.executions.any(Oracle::mutates),
                modelOutput = result.modelOutput,
            )
            pass = pass && verdict.pass
            actionPass = actionPass && verdict.actionOk
            if (!verdict.actionOk) break
            val then = turn.options[verdict.option].then
            if (then != null) { turns = then; index = 0 } else index++
            step++
        }
        return EpisodeRecord(episode, records, pass, actionPass)
    }

    companion object {
        /** Every sequence of option choices through an episode, following branches. */
        fun paths(episode: Episode, policies: Map<String, String> = emptyMap()): List<List<Int>> {
            val out = mutableListOf<List<Int>>()
            fun walk(turns: List<Turn>, index: Int, prefix: List<Int>) {
                if (index >= turns.size) { out += prefix; return }
                for ((i, option) in Scorer.eligible(turns[index].options, policies)) {
                    if (option.then != null) walk(option.then, 0, prefix + i) else walk(turns, index + 1, prefix + i)
                }
            }
            walk(episode.turns, 0, emptyList())
            return out
        }
    }
}
