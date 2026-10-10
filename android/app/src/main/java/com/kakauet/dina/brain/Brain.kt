package com.kakauet.dina.brain

import com.kakauet.dina.brain.dina45.Dina45Brain
import com.kakauet.dina.config.AppVariant
import com.kakauet.dina.llm.LlmMetrics
import com.kakauet.dina.llm.TextGenerator
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.ToolEngine
import com.kakauet.dina.tools.ToolExecution
import com.kakauet.dina.tools.ToolOutcome
import com.kakauet.dina.tools.ToolSnapshot

/**
 * The model-specific part of Dina: prompt, chat template, tool-call format and history.
 * Everything else (tools, voice, UI) is shared. A new model is a new [BrainSpec] set as
 * [BrainRegistry.default]; nothing outside `brain/` has to change.
 */
interface Brain {
    val spec: BrainSpec

    /** Runs one user turn end to end, executing tools through the executor given at creation. */
    suspend fun runTurn(userText: String, events: TurnEvents = TurnEvents()): TurnResult

    /** Forgets conversational context. Persistent tool state is never touched. */
    fun newConversation()

    /**
     * Gets the model ready for a turn that has not been transcribed yet (the user is speaking):
     * Dina 4.5 prefills its prompt up to the phrase. Never changes what [runTurn] produces.
     */
    suspend fun prepare() {}
}

/** Static description of a brain and the model file it needs. */
interface BrainSpec {
    val id: String
    /** Shown in the UI, e.g. "Dina 4.5 1.2B". */
    val displayName: String
    val model: ModelSpec

    fun create(llm: TextGenerator, tools: ToolExecutor): Brain
}

data class ModelSpec(
    /** Path inside the APK assets. */
    val assetPath: String,
    /** Path inside the installed models bundle. */
    val fileName: String,
    val bytes: Long,
    val quantization: String,
    val contextSize: Int = 1536,
    val threads: Int = 6,
)

fun interface ToolExecutor {
    fun execute(command: ToolCommand): ToolOutcome

    /** Current state, for brains that show it to the model (Dina 4.5); null when not available. */
    fun snapshot(): ToolSnapshot? = null
}

/** The app's executor: runs on [engine] and exposes its state. */
class EngineExecutor(private val engine: ToolEngine) : ToolExecutor {
    override fun execute(command: ToolCommand): ToolOutcome = engine.execute(command)
    override fun snapshot(): ToolSnapshot = engine.snapshot()
}

class TurnEvents(
    val onToken: (String) -> Unit = {},
    /** A tool is about to run; argument is the tool name. */
    val onExecuting: (String) -> Unit = {},
)

data class TurnResult(
    val answer: String,
    val executions: List<ToolExecution>,
    val metrics: TurnMetrics = TurnMetrics(),
    /** What the model wrote, before the engine read it (evaluator and data tools). */
    val modelOutput: String = "",
)

data class TurnMetrics(
    val llm: LlmMetrics = LlmMetrics(),
    val promptBuildMs: Double = 0.0,
    /** Dialogue engine and tools, answer included. */
    val toolMs: Double = 0.0,
)

object BrainRegistry {
    /** Each edition ships one brain: Dina 4.5 1.2B (full) or Dina 4.5 350M (lite). */
    fun forEdition(lite: Boolean): BrainSpec = if (lite) Dina45Brain.LiteSpec else Dina45Brain.Spec

    val default: BrainSpec = forEdition(AppVariant.isLite)
}

