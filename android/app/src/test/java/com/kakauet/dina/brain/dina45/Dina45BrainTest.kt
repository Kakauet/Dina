package com.kakauet.dina.brain.dina45

import com.kakauet.dina.dialog.StateSummary
import com.kakauet.dina.llm.LlmMetrics
import com.kakauet.dina.llm.LlmResult
import com.kakauet.dina.llm.TextGenerator
import com.kakauet.dina.tools.TestTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Dina45BrainTest {
    private class FakeModel(vararg outputs: String) : TextGenerator {
        private val queue = ArrayDeque(outputs.toList())
        val prompts = mutableListOf<String>()
        val grammars = mutableListOf<String?>()
        val limits = mutableListOf<Int>()
        override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit, grammar: String?): LlmResult {
            prompts += prompt
            grammars += grammar
            limits += maxTokens
            return LlmResult(queue.removeFirst(), LlmMetrics(promptTokens = 100))
        }
    }

    /** Streams its output one character per token and stops when cancelled, like the JNI engine. */
    private class StreamingModel(private val output: String) : TextGenerator {
        private var cancelled = false
        override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit, grammar: String?): LlmResult {
            cancelled = false
            val text = StringBuilder()
            for (c in output.take(maxTokens)) {
                if (cancelled) break
                text.append(c)
                onToken(c.toString())
            }
            return LlmResult(text.toString(), LlmMetrics(completionTokens = text.length))
        }
        override fun cancel() { cancelled = true }
    }

    private val t = TestTools()

    @Test
    fun chatMayTalkUpTo120TokensButActionsStopAt48() = runBlocking {
        val long = "say(\"" + "ja".repeat(40) + "\")"
        var seen = 0
        Dina45Brain(StreamingModel(long), t.executor, seed = 1).runTurn("hola", com.kakauet.dina.brain.TurnEvents(onToken = { seen++ }))
        assertEquals(long.length, seen)
        val actions = (1..10).joinToString("\n") { "list.add(\"cosa$it\")" }
        var counted = 0
        Dina45Brain(StreamingModel(actions), t.executor, seed = 1).runTurn("apunta cosas", com.kakauet.dina.brain.TurnEvents(onToken = { counted++ }))
        assertEquals(Dina45Prompt.MAX_TOKENS, counted)
        assertEquals(Dina45Prompt.MAX_TOKENS, Dina45Prompt.budget("alarm"))
        assertEquals(Dina45Prompt.MAX_SAY_TOKENS, Dina45Prompt.budget("sa"))
    }

    @Test
    fun oneConstrainedCallPerTurnWithStateAndOneTurnOfHistory() = runBlocking {
        val model = FakeModel("alarm.add(7:00 mañana, \"gimnasio\")", "alarm.edit(@, at=7:30)")
        val brain = Dina45Brain(model, t.executor, seed = 1)
        val first = brain.runTurn("pon una alarma a las siete para el gimnasio")
        assertTrue(first.answer, first.answer.contains("7:00") && first.answer.contains("gimnasio"))
        assertEquals(listOf("alarm.create"), first.executions.map { "${it.tool}.${it.op}" })
        assertEquals("hecho", brain.lastTurn?.brief)
        assertEquals(Dina45Grammar.GBNF, model.grammars.single())
        assertEquals(Dina45Prompt.MAX_SAY_TOKENS, model.limits.single())
        assertTrue(model.prompts[0].startsWith("<|startoftext|><|im_start|>system\n${Dina45Prompt.SYSTEM}<|im_end|>"))
        assertTrue(model.prompts[0].endsWith("[ahora]\npon una alarma a las siete para el gimnasio<|im_end|>\n<|im_start|>assistant\n"))
        assertFalse(model.prompts[0].contains("[antes]"))

        val expected = brain.prompt("mejor a las siete y media")
        brain.runTurn("mejor a las siete y media")
        val second = model.prompts[1]
        assertEquals(expected, second)
        assertTrue(second, second.contains("[antes]\nusuario: pon una alarma a las siete para el gimnasio\ndina: alarm.add(7:00 mañana, \"gimnasio\") → hecho\n"))
        assertTrue(second, second.contains("alarmas: mañana 7:00 «gimnasio»") && second.contains("foco: alarma"))
        assertEquals(7, java.time.Instant.ofEpochMilli(t.engine.state.value.alarms.single().nextMs).atZone(t.zone).hour)
        assertEquals(30, java.time.Instant.ofEpochMilli(t.engine.state.value.alarms.single().nextMs).atZone(t.zone).minute)
    }

    /** Records what is prefilled ahead of a turn. */
    private class WarmModel(private val output: String) : TextGenerator {
        val warmed = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit, grammar: String?): LlmResult {
            prompts += prompt
            return LlmResult(output, LlmMetrics(promptTokens = 100))
        }
        override suspend fun warm(prefix: String): Int { warmed += prefix; return 42 }
    }

    @Test
    fun prepareWarmsExactlyThePromptBeforeThePhraseAndChangesNothing() = runBlocking {
        val model = WarmModel("timer.add(5m, \"pasta\")")
        val brain = Dina45Brain(model, t.executor, seed = 1, prefillWhileSpeaking = true)
        brain.prepare()
        assertEquals(42, brain.preparedTokens)
        assertTrue(t.engine.state.value.timers.isEmpty())
        assertTrue(model.prompts.isEmpty())
        brain.runTurn("pon cinco minutos para la pasta")
        assertTrue(model.prompts.single().startsWith(model.warmed.single()))
        assertTrue(model.warmed.single().endsWith("[ahora]\n"))
        // The next turn's prefix carries the new state and the previous turn.
        brain.prepare()
        val next = model.warmed.last()
        assertTrue(next, next.contains("temporizadores: «pasta»") && next.contains("[antes]\nusuario: pon cinco minutos para la pasta"))
        assertEquals(next + "¿cuánto queda?<|im_end|>\n<|im_start|>assistant\n", brain.prompt("¿cuánto queda?"))
    }

    @Test
    fun aRunningTimerDoesNotSpoilThePreparedPromptButARealChangeDoes() = runBlocking {
        val model = WarmModel("time.now(hora)")
        val brain = Dina45Brain(model, t.executor, seed = 1, prefillWhileSpeaking = true)
        t.ok(com.kakauet.dina.tools.TimerCommand.Create(5 * com.kakauet.dina.tools.MINUTE, "pasta"))
        // Only the clock moves (the timer ticks): the turn uses the prefix prefilled while the user spoke.
        brain.prepare()
        t.advance(1_600)
        brain.runTurn("¿qué hora es?")
        assertTrue(model.prompts.last().startsWith(model.warmed.last()))
        assertTrue(model.warmed.last(), model.warmed.last().contains("«pasta» 5:00"))
        // Too old: rendered again with the current remaining time.
        brain.prepare()
        t.advance(Dina45Brain.MAX_PREPARED_AGE_MS + 1_000)
        brain.runTurn("¿qué hora es?")
        assertFalse(model.prompts.last().startsWith(model.warmed.last()))
        assertTrue(model.prompts.last(), model.prompts.last().contains("«pasta» 4:55"))
        // The state really changed (a timer was added by hand): never the old prefix.
        brain.prepare()
        t.ok(com.kakauet.dina.tools.TimerCommand.Create(com.kakauet.dina.tools.MINUTE, "té"))
        brain.runTurn("¿qué hora es?")
        assertTrue(model.prompts.last().contains("«té»"))
        // A prepared prefix is used once; without prepare the prompt is the plain render.
        assertEquals(brain.prompt("hola"), Dina45Prompt.complete(brain.promptPrefix(), "hola"))
    }

    @Test
    fun prefillWhileSpeakingCanBeTurnedOff() = runBlocking {
        val model = WarmModel("time.now(hora)")
        val brain = Dina45Brain(model, t.executor, seed = 1, prefillWhileSpeaking = false)
        brain.prepare()
        assertTrue(model.warmed.isEmpty())
    }

    @Test
    fun dina45IsTheAppBrain() {
        assertEquals(Dina45Brain.Spec, com.kakauet.dina.brain.BrainRegistry.default)
        assertEquals("models/llm/dina-4.5-1.2b-Q4_K_M.gguf", Dina45Brain.Spec.model.assetPath)

    }

    @Test
    fun thePromptIsTheTrainingRenderByteForByte() {
        val brain = Dina45Brain(FakeModel(), t.executor, seed = 1)
        val state = StateSummary.render(t.engine.snapshot(), brain.dialogue.view())
        assertEquals(Dina45Prompt.render(state, null, "¿qué hora es?"), brain.prompt("¿qué hora es?"))
        assertEquals(Dina45Prompt.render(state, null, "dos  espacios\ny salto"), brain.prompt("dos espacios y salto"))
    }

    @Test
    fun unreadableOutputIsNeverSpokenAndHistoryCanBeOff() = runBlocking {
        val model = FakeModel("{\"tool_calls\":[]}", "say(\"¡Hola!\")", "vol.get()")
        val brain = Dina45Brain(model, t.executor, seed = 1, grammar = false, historyTurns = 0)
        val misread = brain.runTurn("bla")
        assertTrue(misread.answer, misread.answer.contains("no te he entendido", ignoreCase = true))
        assertTrue(misread.executions.isEmpty())
        assertEquals("¡Hola!", brain.runTurn("hola").answer)
        brain.runTurn("¿a cuánto está el volumen?")
        assertTrue(model.grammars.all { it == null })
        assertTrue(model.prompts.none { it.contains("[antes]") })
    }

    @Test
    fun newConversationForgetsPendingFocusAndHistory() = runBlocking {
        val model = FakeModel("timer.add(\"pasta\")", "time.now(hora)")
        val brain = Dina45Brain(model, t.executor, seed = 1)
        brain.runTurn("pon un temporizador para la pasta")
        assertTrue(brain.dialogue.pending != null)
        brain.newConversation()
        assertTrue(brain.lastTurn == null)
        brain.runTurn("qué hora es")
        assertTrue(brain.dialogue.pending == null)
        assertFalse(model.prompts[1].contains("[antes]") || model.prompts[1].contains("pendiente"))
    }
}
