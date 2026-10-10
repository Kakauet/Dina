package com.kakauet.dina.core

import com.kakauet.dina.brain.Brain
import com.kakauet.dina.brain.TurnEvents
import com.kakauet.dina.brain.TurnResult
import com.kakauet.dina.tools.ToolPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

enum class TurnOrigin { VOICE, TEXT, BENCHMARK }

sealed interface TurnOutcome {
    data class Answered(val answer: String, val result: TurnResult, val firstTokenNs: Long, val endNs: Long = System.nanoTime()) : TurnOutcome
    data class Failed(val error: Throwable) : TurnOutcome
    /** Not ready or another turn in progress; nothing was recorded. */
    data object Rejected : TurnOutcome
}

/**
 * Runs one user turn through the active [Brain] and records it: conversation, session state,
 * contextual widget and metrics. Voice, text and the benchmark all go through here; audio
 * (STT before, TTS after) stays in the voice pipeline.
 */
class ConversationController(private val store: DinaStore) {
    @Volatile private var brain: Brain? = null
    private val busy = AtomicBoolean(false)
    /** The brain is used by one coroutine at a time: a turn, or [prepare] while the user speaks. */
    private val brainLock = Mutex()

    val isReady get() = brain != null
    val isBusy get() = busy.get()

    fun attach(brain: Brain?) {
        this.brain = brain
    }

    /** Clears the visible conversation and the brain's context. Tools are never touched. */
    fun newConversation() {
        brain?.newConversation()
        store.clearConversation()
    }

    /** Forgets the brain's context only (end of a voice session). */
    fun endSession() { brain?.newConversation() }

    /**
     * Lets the brain get ready while the user is still speaking (Dina 4.5 prefills its prompt up to the
     * phrase). Skipped when a turn is running; a turn that starts meanwhile waits for it to finish.
     */
    suspend fun prepare() {
        val brain = brain ?: return
        if (busy.get() || !brainLock.tryLock()) return
        try {
            val started = System.nanoTime()
            brain.prepare()
            store.updateMetrics { it.copy(prepareMs = (System.nanoTime() - started) / 1_000_000.0) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Only an optimization: the turn prefills everything itself.
        } finally {
            brainLock.unlock()
        }
    }

    suspend fun runTurn(text: String, origin: TurnOrigin): TurnOutcome {
        val brain = brain ?: return TurnOutcome.Rejected
        if (!busy.compareAndSet(false, true)) return TurnOutcome.Rejected
        val startedNs = System.nanoTime()
        try {
            val recorded = origin != TurnOrigin.BENCHMARK
            if (recorded) store.appendMessage(MessageRole.USER, text)
            store.updateSession {
                it.copy(
                    state = VoiceState.THINKING,
                    detail = VoiceState.THINKING.label,
                    transcript = text, response = "",
                )
            }
            var firstTokenNs = 0L
            var brainStartNs = 0L
            val result = brainLock.withLock {
                brainStartNs = System.nanoTime()
                brain.runTurn(text, TurnEvents(
                    onToken = { if (firstTokenNs == 0L) firstTokenNs = System.nanoTime() },
                    onExecuting = { store.setState(VoiceState.EXECUTING) },
                ))
            }
            val brainEndNs = System.nanoTime()
            val answer = result.answer.ifBlank { FAILED }
            val widget = ToolPresentation.widget(result.executions)
            if (recorded) store.appendMessage(MessageRole.DINA, answer, widget)
            store.updateSession {
                it.copy(
                    response = answer, widget = widget,
                    state = if (origin == TurnOrigin.TEXT) VoiceState.IDLE else it.state,
                    detail = if (origin == TurnOrigin.TEXT) TEXT_IDLE else it.detail,
                )
            }
            val endNs = System.nanoTime()
            store.updateMetrics {
                val llm = result.metrics.llm
                it.copy(
                    sttFinalToPromptReadyMs = result.metrics.promptBuildMs,
                    tokenizationMs = llm.tokenizationMs,
                    prefillMs = llm.prefillMs,
                    timeToFirstTokenMs = llm.firstTokenMs,
                    generationMs = llm.generationMs,
                    decodeMs = (llm.firstTokenMs - llm.tokenizationMs - llm.prefillMs).coerceAtLeast(0.0) + llm.generationMs,
                    tokensPerSecond = llm.tokensPerSecond,
                    toolMs = result.metrics.toolMs,
                    turnWaitMs = (brainStartNs - startedNs) / 1_000_000.0,
                    turnBookkeepingMs = (endNs - brainEndNs) / 1_000_000.0,
                    promptTokens = llm.promptTokens,
                    reusedPromptTokens = llm.reusedPromptTokens,
                    completionTokens = llm.completionTokens,
                )
            }
            return TurnOutcome.Answered(answer, result, firstTokenNs, endNs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val answer = FAILED
            if (origin != TurnOrigin.BENCHMARK) store.appendMessage(MessageRole.DINA, answer)
            store.updateSession {
                it.copy(
                    response = answer,
                    debugError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                    state = if (origin == TurnOrigin.TEXT) VoiceState.IDLE else it.state,
                    detail = if (origin == TurnOrigin.TEXT) TEXT_IDLE else it.detail,
                )
            }
            return TurnOutcome.Failed(error)
        } finally {
            busy.set(false)
        }
    }

    companion object {
        const val TEXT_IDLE = "Lista para escribir"
        const val FAILED = "No he podido hacerlo. Inténtalo otra vez."
    }
}
