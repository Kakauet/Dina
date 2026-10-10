package com.kakauet.dina.eval

import com.kakauet.dina.brain.Brain
import com.kakauet.dina.brain.BrainSpec
import com.kakauet.dina.brain.ModelSpec
import com.kakauet.dina.brain.ToolExecutor
import com.kakauet.dina.brain.TurnEvents
import com.kakauet.dina.brain.TurnResult
import com.kakauet.dina.llm.TextGenerator
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.ToolExecution
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Always answers with the same scripted command and text. */
private class FixedBrain(private val sandbox: Sandbox, private val reply: String, private val action: String?) : Brain {
    override val spec = object : BrainSpec {
        override val id = "fixed"
        override val displayName = "fixed"
        override val model = ModelSpec("", "", 0, "")
        override fun create(llm: TextGenerator, tools: ToolExecutor) = error("unused")
    }
    override suspend fun runTurn(userText: String, events: TurnEvents): TurnResult {
        val executions = listOfNotNull(action).map { line ->
            val command: ToolCommand = Dsl.command(line, sandbox.state(), sandbox.nowMs, sandbox.zone)
            ToolExecution.of(command, sandbox.executor.execute(command))
        }
        return TurnResult(reply, executions)
    }
    override fun newConversation() = Unit
}

private class FixedFactory(private val reply: String, private val action: String?) : BrainFactory {
    override val id = "fixed"
    override fun create(sandbox: Sandbox): Brain = FixedBrain(sandbox, reply, action)
}

class EvalRunnerTest {
    private val gym = """
        {"id":"t-gym","cat":"referencias","clock":"2026-10-05T22:40",
         "state":{"alarms":["mañana 06:45 'trabajo' rep=laborables","mañana 19:00 'gimnasio'"]},
         "turns":[{"u":"la del gimnasio pásala a las ocho","do":["alarm.edit @'gimnasio' at=20:00"]},
                  {"u":"y la otra quítala","do":["alarm.del @'trabajo'"]}]}
    """.trimIndent()

    private val ampm = """
        {"id":"t-ampm","cat":"ambiguedad","clock":"2026-10-05T15:00",
         "turns":[{"u":"ponme una alarma a las siete","x":[
            {"kind":"ask","ask":{"slot":"ampm","opts":["time:07:00","time:19:00"]},"pol":{"ampm":"ask"},
             "then":[{"u":"de la tarde","do":["alarm.add 19:00 day=hoy"]}]},
            {"do":["alarm.add 19:00 day=hoy"],"pol":{"ampm":["next","mixed"]}}]}]}
    """.trimIndent()

    private fun episode(json: String) = EpisodeJson.parse(JSONObject(json))

    @Test
    fun stateSpecBuildsRealisticWorlds() {
        val ep = episode(gym)
        val alarms = ep.initial.world.alarms
        assertEquals(listOf("trabajo", "gimnasio"), alarms.map { it.label })
        assertEquals(AlarmStatus.SCHEDULED, alarms.first().status)
        val view = WorldView.of(ep.initial, ep.clock.atZone(ep.zone).toInstant().toEpochMilli(), ep.zone)
        assertEquals(listOf("2026-10-06 06:45 [trabajo] laborables scheduled", "2026-10-06 19:00 [gimnasio] una-vez scheduled"), view.alarms)
    }

    @Test
    fun oracleBrainPassesEveryPath() = runBlocking {
        val runner = EvalRunner(OracleFactory())
        for (json in listOf(gym, ampm)) {
            val ep = episode(json)
            for (path in EvalRunner.paths(ep)) {
                val record = runner.run(ep, path)
                assertTrue("${ep.id} $path ${record.turns.map { it.verdict }}", record.pass)
                assertEquals(path, record.turns.map { it.verdict.option })
            }
        }
        assertEquals(listOf(listOf(0, 0), listOf(1)), EvalRunner.paths(episode(ampm)))
    }

    @Test
    fun wrongWorldStopsTheEpisode() = runBlocking {
        val record = EvalRunner(FixedFactory("Hecho, a las 20:00.", "alarm.edit @'trabajo' at=20:00")).run(episode(gym))
        assertFalse(record.pass)
        assertEquals(1, record.turns.size)
        assertTrue(record.turns.single().verdict.reasons.contains("estado"))
    }

    @Test
    fun rightActionWithPoorAnswerContinuesButFails() = runBlocking {
        val record = EvalRunner(FixedFactory("Alarma actualizada.", "alarm.edit @'gimnasio' at=20:00")).run(episode(gym))
        assertFalse(record.pass)
        assertTrue(record.turns.first().verdict.actionOk)
        assertTrue("falta time:20:00" in record.turns.first().verdict.reasons)
        assertEquals(2, record.turns.size)
    }

    @Test
    fun claimingWhatDidNotHappenFails() = runBlocking {
        val record = EvalRunner(FixedFactory("He puesto la alarma a las siete.", null)).run(episode(ampm))
        val verdict = record.turns.single().verdict
        assertTrue(verdict.reasons.toString(), "afirma algo que no hizo" in verdict.reasons)
    }

    @Test
    fun policiesSelectOptions() = runBlocking {
        val ask = EvalRunner(FixedFactory("¿De la mañana o de la tarde?", null), policies = mapOf("ampm" to "next")).run(episode(ampm))
        assertFalse(ask.pass)
        val any = EvalRunner(FixedFactory("¿A las siete de la mañana o a las siete de la tarde?", null)).run(episode(ampm))
        assertTrue(any.turns.first().verdict.reasons.toString(), any.turns.first().verdict.pass)
    }

    @Test
    fun clockStartsWhereTheEpisodeSays() {
        assertEquals(LocalDateTime.of(2026, 10, 5, 22, 40), episode(gym).clock)
    }
}
