package com.kakauet.dina.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** App restarts: every new engine reads only the JSON text the previous one left, as on the phone. */
class ToolPersistenceTest {
    /** Stores the encoded text, like `dina_tools/state`, so each restart goes through [ToolWorldJson]. */
    private class JsonStore : ToolStore {
        var text: String? = null
        override fun load() = ToolWorldJson.decode(text)
        override fun save(world: ToolWorld) { text = ToolWorldJson.encode(world) }
    }

    private val zone = ZoneId.of("Europe/Madrid")
    private var now = LocalDateTime.of(2026, 10, 5, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val disk = JsonStore()

    private fun start(alerts: RecordingAlerts = RecordingAlerts()) = ToolEngine(disk, alerts, FakeVolume(), clock = { now }, zone = { zone })

    private fun ToolEngine.ok(command: ToolCommand): ToolData {
        val outcome = execute(command)
        check(outcome is ToolOutcome.Success) { "Expected success for $command, got $outcome" }
        return outcome.data
    }

    @Test
    fun aRestartKeepsEveryLiveValueAndItsAlerts() {
        val first = start()
        first.ok(TimerCommand.Create(10 * MINUTE, "pasta"))
        first.ok(TimerCommand.Create(5 * MINUTE))
        now += MINUTE
        first.ok(TimerCommand.Pause(Target(id = "t2")))
        first.ok(StopwatchCommand.Start("correr"))
        first.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(7, 30)), "trabajo", Repeat.Weekdays))
        first.ok(ShoppingCommand.Add("arroz", 1.5, "kg"))
        first.ok(ShoppingCommand.Add("pan"))
        first.ok(ShoppingCommand.Mark(Target(name = "pan")))
        val before = first.state.value

        now += 3 * MINUTE // the app was killed for three minutes
        val alerts = RecordingAlerts()
        val second = start(alerts)
        assertEquals(before, second.state.value)
        val world = second.state.value
        assertEquals(6 * MINUTE, world.timers[0].remainingAt(now))
        assertEquals(4 * MINUTE, world.timers[1].remainingAt(now))
        assertEquals(3 * MINUTE, world.stopwatches.single().elapsedAt(now))
        // Boot and app updates re-register exactly what was pending (the paused timer stays silent).
        second.rescheduleAll()
        assertEquals(mapOf("timer/t1" to world.timers[0].deadlineMs, "alarm/a1" to world.alarms.single().nextMs), alerts.scheduled)
    }

    @Test
    fun alertsDueWhileTheAppWasDeadRingAfterARestartAndCanBeDismissed() {
        val first = start()
        first.ok(TimerCommand.Create(MINUTE))
        first.ok(AlarmCommand.Create(WhenSpec.After(2 * MINUTE)))
        now += 5 * MINUTE

        val second = start()
        val snapshot = second.snapshot()
        assertEquals(TimerStatus.RINGING, snapshot.world.timers.single().status)
        assertEquals(AlarmStatus.RINGING, snapshot.world.alarms.single().status)
        second.ok(TimerCommand.Dismiss())
        second.ok(AlarmCommand.Dismiss())

        val third = start().state.value
        assertTrue(third.timers.isEmpty() && third.alarms.isEmpty())
    }

    @Test
    fun aRestartNeverHandsOutAnIdStillInUse() {
        val first = start()
        first.ok(ShoppingCommand.Add("pan"))
        first.ok(ShoppingCommand.Add("sal"))
        first.ok(ShoppingCommand.Remove(Target(id = "i1")))
        // Counters lost (old or hand-edited state): "leche" must not become a second i2.
        disk.text = JSONObject(disk.text).apply { remove("counters") }.toString()
        val added = start().ok(ShoppingCommand.Add("leche")) as ToolData.ShoppingEntry
        assertEquals("i3", added.item.id)
        assertEquals(listOf("i2", "i3"), start().state.value.shopping.map { it.id })
    }

    @Test
    fun aQuantityJsonCannotHoldDoesNotStopLaterSaves() {
        val engine = start()
        engine.ok(ShoppingCommand.Add("arroz", Double.POSITIVE_INFINITY, "kg"))
        engine.ok(ShoppingCommand.Add("sal"))
        assertEquals(listOf("arroz", "sal"), start().state.value.shopping.map { it.name })
    }

    @Test
    fun stateFromApp14StartsTheEngineAndItsAlerts() {
        disk.text = APP_14_STATE
        val alerts = RecordingAlerts()
        val engine = start(alerts)
        engine.rescheduleAll()
        assertEquals(setOf("timer/t2", "alarm/a1"), alerts.scheduled.keys)
        // New items continue the 1.4 counters.
        assertEquals("i3", (engine.ok(ShoppingCommand.Add("sal")) as ToolData.ShoppingEntry).item.id)
        assertEquals(3, start().state.value.shopping.size)
    }
}
