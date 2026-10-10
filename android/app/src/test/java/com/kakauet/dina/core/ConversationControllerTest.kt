package com.kakauet.dina.core

import com.kakauet.dina.brain.Brain
import com.kakauet.dina.brain.BrainSpec
import com.kakauet.dina.brain.ModelSpec
import com.kakauet.dina.brain.ToolExecutor
import com.kakauet.dina.brain.TurnEvents
import com.kakauet.dina.brain.TurnResult
import com.kakauet.dina.llm.TextGenerator
import com.kakauet.dina.tools.MINUTE
import com.kakauet.dina.tools.TestTools
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.ToolExecution
import com.kakauet.dina.tools.WidgetKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A brain that creates a one-minute timer for any input, or fails on demand. */
private class FakeBrain(private val tools: ToolExecutor, private val gate: CompletableDeferred<Unit>? = null) : Brain {
    var conversations = 0
    override val spec = object : BrainSpec {
        override val id = "fake"
        override val displayName = "Dina de prueba"
        override val model = ModelSpec("a", "b", 1, "Q")
        override fun create(llm: TextGenerator, tools: ToolExecutor) = error("unused")
    }

    override suspend fun runTurn(userText: String, events: TurnEvents): TurnResult {
        gate?.await()
        if (userText == "falla") error("modelo roto")
        events.onExecuting("timer")
        val command = TimerCommand.Create(MINUTE)
        return TurnResult("Hecho.", listOf(ToolExecution.of(command, tools.execute(command))))
    }

    override fun newConversation() { conversations++ }

    var prepares = 0
    var prepareGate: CompletableDeferred<Unit>? = null
    override suspend fun prepare() { prepares++; prepareGate?.await() }
}

class ConversationControllerTest {
    private val t = TestTools()
    private val store = DinaStore()
    private val controller = ConversationController(store)

    @Test
    fun rejectsTurnsUntilABrainIsAttached() = runBlocking {
        assertEquals(TurnOutcome.Rejected, controller.runTurn("hola", TurnOrigin.TEXT))
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun textTurnRecordsConversationWidgetAndState() = runBlocking {
        controller.attach(FakeBrain({ t.engine.execute(it) }))
        val outcome = controller.runTurn("pon un temporizador", TurnOrigin.TEXT)
        assertTrue(outcome is TurnOutcome.Answered)
        val messages = store.messages.value
        assertEquals(listOf(MessageRole.USER, MessageRole.DINA), messages.map { it.role })
        assertEquals(WidgetKind.TIMER, messages[1].widget?.kind)
        val session = store.session.value
        assertEquals(VoiceState.IDLE, session.state)
        assertEquals("Hecho.", session.response)
        assertEquals(1, t.engine.state.value.timers.size)
    }

    @Test
    fun failuresAreRecordedAndReleaseTheController() = runBlocking {
        controller.attach(FakeBrain({ t.engine.execute(it) }))
        val outcome = controller.runTurn("falla", TurnOrigin.TEXT)
        assertTrue(outcome is TurnOutcome.Failed)
        assertEquals(ConversationController.FAILED, store.messages.value.last().text)
        assertTrue(store.session.value.debugError.contains("modelo roto"))
        assertTrue(controller.runTurn("otra", TurnOrigin.TEXT) is TurnOutcome.Answered)
    }

    @Test
    fun onlyOneTurnAtATime() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        controller.attach(FakeBrain({ t.engine.execute(it) }, gate))
        val first = async { controller.runTurn("uno", TurnOrigin.VOICE) }
        while (!controller.isBusy) kotlinx.coroutines.yield()
        assertEquals(TurnOutcome.Rejected, controller.runTurn("dos", TurnOrigin.TEXT))
        gate.complete(Unit)
        assertTrue(first.await() is TurnOutcome.Answered)
    }

    @Test
    fun newConversationClearsContextButNotTools() = runBlocking {
        val brain = FakeBrain({ t.engine.execute(it) })
        controller.attach(brain)
        controller.runTurn("pon un temporizador", TurnOrigin.TEXT)
        controller.newConversation()
        assertTrue(store.messages.value.isEmpty())
        assertEquals(1, brain.conversations)
        assertEquals(1, t.engine.state.value.timers.size)
    }

    @Test
    fun prepareRunsWhileTheUserSpeaksAndATurnWaitsForIt() = runBlocking {
        val brain = FakeBrain({ t.engine.execute(it) })
        controller.attach(brain)
        controller.prepare()
        assertEquals(1, brain.prepares)
        assertTrue(store.messages.value.isEmpty())
        assertTrue(store.metrics.value.prepareMs != null)

        // A turn that arrives while the brain is preparing waits for it instead of interleaving.
        val gate = CompletableDeferred<Unit>()
        brain.prepareGate = gate
        val preparing = async(kotlinx.coroutines.Dispatchers.Default) { controller.prepare() }
        while (brain.prepares < 2) kotlinx.coroutines.yield()
        val turn = async(kotlinx.coroutines.Dispatchers.Default) { controller.runTurn("pon un temporizador", TurnOrigin.VOICE) }
        kotlinx.coroutines.delay(50)
        assertEquals(0, t.engine.state.value.timers.size)
        gate.complete(Unit)
        preparing.await()
        assertTrue(turn.await() is TurnOutcome.Answered)
        assertEquals(1, t.engine.state.value.timers.size)
        assertTrue(store.metrics.value.turnWaitMs!! >= 40.0)
    }

    @Test
    fun prepareIsSkippedDuringATurn() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val brain = FakeBrain({ t.engine.execute(it) }, gate)
        controller.attach(brain)
        val turn = async { controller.runTurn("uno", TurnOrigin.VOICE) }
        while (!controller.isBusy) kotlinx.coroutines.yield()
        controller.prepare()
        assertEquals(0, brain.prepares)
        gate.complete(Unit)
        assertTrue(turn.await() is TurnOutcome.Answered)
    }

    @Test
    fun benchmarkTurnsAreNotRecorded() = runBlocking {
        controller.attach(FakeBrain({ t.engine.execute(it) }))
        controller.runTurn("x", TurnOrigin.BENCHMARK)
        assertTrue(store.messages.value.isEmpty())
    }
}
