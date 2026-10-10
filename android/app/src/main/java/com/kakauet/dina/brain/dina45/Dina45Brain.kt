package com.kakauet.dina.brain.dina45

import com.kakauet.dina.brain.Brain
import com.kakauet.dina.brain.BrainSpec
import com.kakauet.dina.brain.ModelSpec
import com.kakauet.dina.brain.ToolExecutor
import com.kakauet.dina.brain.TurnEvents
import com.kakauet.dina.brain.TurnMetrics
import com.kakauet.dina.brain.TurnResult
import com.kakauet.dina.dialog.Dialogue
import com.kakauet.dina.dialog.DialogueTurn
import com.kakauet.dina.dialog.DialogueView
import com.kakauet.dina.dialog.Grounding
import com.kakauet.dina.dialog.Policies
import com.kakauet.dina.dialog.StateSummary
import com.kakauet.dina.llm.TextGenerator
import com.kakauet.dina.tools.ToolSnapshot

/**
 * Dina 4.5: the model only reads the phrase with the visible state and writes canonical actions
 * (one call per turn, grammar-constrained); [Dialogue] resolves, decides, executes and answers.
 */
class Dina45Brain(
    private val llm: TextGenerator,
    tools: ToolExecutor,
    policies: Policies = Policies(),
    seed: Long = System.nanoTime(),
    private val grammar: Boolean = true,
    /** Turns of `[antes]` shown to the model: 1 (the design) or 0 (ablation). */
    private val historyTurns: Int = 1,
    /**
     * Prefill the prompt up to the phrase while the user speaks ([prepare]): ~85 % less prefill
     * after the transcript. The extra decode boundary changes the numerics slightly.
     */
    private val prefillWhileSpeaking: Boolean = true,
    /** Drop products the phrase never said ([Grounding]); off only when replaying canonical training labels. */
    private val grounding: Boolean = true,
) : Brain {
    object Spec : BrainSpec {
        override val id = "dina-4.5-1.2b"
        override val displayName = "Dina 4.5 1.2B"
        /** models/dina-4.5/model-Q4_K_M.gguf (LFM2.5-1.2B-Instruct + LoRA). */
        override val model = ModelSpec(
            assetPath = "models/llm/dina-4.5-1.2b-Q4_K_M.gguf",
            fileName = "llm/dina-4.5-1.2b-Q4_K_M.gguf",
            bytes = 730_898_368L,
            quantization = "Q4_K_M",
        )
        /** Policies() is the evaluated `ampm:mixed+bulk:direct`. */
        override fun create(llm: TextGenerator, tools: ToolExecutor): Brain = Dina45Brain(llm, tools, Policies())
    }

    /**
     * Dina 4.5 350M for the Lite edition: the same brain (prompt, contract, grammar, engine) on the smaller model,
     * which is 3 to 6 points under the 1.2B in the benchmarks (docs/HISTORY.md) and half the size.
     */
    object LiteSpec : BrainSpec {
        override val id = "dina-4.5-350m"
        override val displayName = "Dina 4.5 350M"
        /** models/dina-4.5-350m/model-Q8_0.gguf (LFM2.5-350M + LoRA). */
        override val model = ModelSpec(
            assetPath = "models/llm/dina-4.5-350m-Q8_0.gguf",
            fileName = "llm/dina-4.5-350m-Q8_0.gguf",
            bytes = 379_219_904L,
            quantization = "Q8_0",
            threads = 4,
        )
        override fun create(llm: TextGenerator, tools: ToolExecutor): Brain = Dina45Brain(llm, tools, Policies())
    }

    /** The spec of the edition this brain was created for ([BrainRegistry.default]). */
    override val spec: BrainSpec get() = com.kakauet.dina.brain.BrainRegistry.default

    val dialogue = Dialogue(tools, policies, seed)
    private var before: String? = null
    /** The previous phrase: a product said there still counts as said ([Grounding]). */
    private var lastUser: String? = null

    /** What the dialogue engine did in the last turn (answer, replies, brief); for Diagnóstico and the data tools. */
    var lastTurn: DialogueTurn? = null
        private set

    /** The prompt the next turn would send for [userText] (also used to render training data). */
    fun prompt(userText: String): String =
        Dina45Prompt.render(StateSummary.render(dialogue.snapshot(), dialogue.view()), before?.takeIf { historyTurns > 0 }, userText)

    /** The next prompt up to the phrase: state and previous turn are known before the user finishes. */
    fun promptPrefix(): String = prefixNow().prefix

    /** Tokens prefilled by the last [prepare] (Diagnóstico). */
    var preparedTokens = 0
        private set

    /** The prefix [prepare] prefilled and what it was rendered from. */
    private data class Prepared(val prefix: String, val snapshot: ToolSnapshot, val view: DialogueView, val before: String?)

    private var prepared: Prepared? = null

    /**
     * Prefills the prompt up to the phrase while the user speaks. The turn reuses that prefix when
     * only the clock has moved since (at most [MAX_PREPARED_AGE_MS], same minute): a running timer
     * then shows the model its remaining time from a moment ago instead of missing the prompt cache.
     * Nothing the model reads changes otherwise, and the engine always acts on the current state.
     */
    override suspend fun prepare() {
        if (!prefillWhileSpeaking) return
        val next = prefixNow()
        preparedTokens = llm.warm(next.prefix)
        prepared = next
    }

    private fun prefixNow(): Prepared {
        val snapshot = dialogue.snapshot()
        val view = dialogue.view()
        val history = before?.takeIf { historyTurns > 0 }
        return Prepared(Dina45Prompt.prefix(StateSummary.render(snapshot, view), history), snapshot, view, history)
    }

    /** The prefix for a turn starting now: the prepared one if still valid, else a fresh render. */
    private fun turnPrefix(): String {
        val ready = prepared.also { prepared = null }
        val now = prefixNow()
        if (ready == null) return now.prefix
        val age = now.snapshot.nowMs - ready.snapshot.nowMs
        val sameState = ready.snapshot.world == now.snapshot.world && ready.snapshot.volume == now.snapshot.volume &&
            ready.view == now.view && ready.before == now.before
        val sameMinute = ready.snapshot.nowMs / 60_000 == now.snapshot.nowMs / 60_000
        return if (sameState && sameMinute && age in 0..MAX_PREPARED_AGE_MS) ready.prefix else now.prefix
    }

    override suspend fun runTurn(userText: String, events: TurnEvents): TurnResult {
        val started = System.nanoTime()
        val prompt = Dina45Prompt.complete(turnPrefix(), userText)
        val promptMs = elapsedMs(started)
        val head = StringBuilder()
        var tokens = 0
        val output = llm.generate(prompt, Dina45Prompt.MAX_SAY_TOKENS, { piece ->
            if (head.length < 4) head.append(piece)
            if (++tokens >= Dina45Prompt.budget(head.toString())) llm.cancel()
            events.onToken(piece)
        }, if (grammar) Dina45Grammar.GBNF else null)
        val decoded = Dina45Codec.decode(output.text)
        val actions = if (grounding) Grounding.products(decoded.actions, listOfNotNull(userText, lastUser)) else decoded.actions
        lastUser = userText
        val toolStarted = System.nanoTime()
        val turn = dialogue.run(actions, misread = decoded.actions.isEmpty() && decoded.invalidLines > 0, onExecuting = events.onExecuting)
        before = Dina45Prompt.history(userText, actions, turn.brief)
        lastTurn = turn
        return TurnResult(
            answer = turn.answer,
            executions = turn.executions,
            metrics = TurnMetrics(output.metrics, promptMs, elapsedMs(toolStarted)),
            modelOutput = output.text,
        )
    }

    override fun newConversation() {
        dialogue.reset()
        before = null
        lastUser = null
        lastTurn = null
        prepared = null
    }

    companion object {
        /** A prepared prompt older than this is rendered again (the user spoke for a long time). */
        const val MAX_PREPARED_AGE_MS = 3_000L
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000.0
}
