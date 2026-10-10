package com.kakauet.dina.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

/** Captured shape of `dina_tools/state` from Dina Android 1.4.0 (later versions write the same). */
val APP_14_STATE = """
    {"timers":[{"id":"t2","label":null,"deadlineMs":1790000000000,"remainingMs":300000,"durationMs":300000,"status":"running"}],
     "alarms":[{"id":"a1","label":"gym","nextMs":1790000600000,"repeat":{"type":"weekly","days":["mon","thu"]},"status":"scheduled"},
               {"id":"a2","label":null,"nextMs":1790000900000,"repeat":null,"status":"disabled"}],
     "stopwatches":[{"id":"s1","label":null,"elapsedMs":5000,"startedMs":1789999990000,"status":"paused"}],
     "shopping":[{"id":"i1","name":"leche","quantity":2,"unit":"litros","completed":false},
                 {"id":"i2","name":"pan","quantity":"media","unit":null,"completed":true}],
     "counters":{"timer":2,"alarm":2,"stopwatch":1,"shopping":2},"muted":false,"restorePercent":64}
""".trimIndent()

class ToolWorldJsonTest {
    @Test
    fun readsStateWrittenByApp14() {
        val world = requireNotNull(ToolWorldJson.decode(APP_14_STATE))
        assertEquals(TimerStatus.RUNNING, world.timers.single().status)
        assertEquals(Repeat.Weekly(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)), world.alarms[0].repeat)
        assertEquals(AlarmStatus.DISABLED, world.alarms[1].status)
        assertEquals(StopwatchStatus.PAUSED, world.stopwatches.single().status)
        assertEquals(2.0, world.shopping[0].quantity!!, 0.0)
        assertNull(world.shopping[1].quantity)
        assertEquals(mapOf("timer" to 2, "alarm" to 2, "stopwatch" to 1, "shopping" to 2), world.counters)
        assertEquals(64, world.restorePercent)
    }

    @Test
    fun roundTripsEveryField() {
        val world = ToolWorld(
            timers = listOf(Timer("t1", "pasta", 10, 20, 30, TimerStatus.PAUSED)),
            alarms = listOf(Alarm("a1", null, 40, Repeat.Weekdays, AlarmStatus.SNOOZED), Alarm("a2", "x", 50, null, AlarmStatus.RINGING)),
            stopwatches = listOf(Stopwatch("s1", "run", 60, 70, StopwatchStatus.RUNNING)),
            shopping = listOf(ShoppingItem("i1", "arroz", 1.5, "kg", true), ShoppingItem("i2", "sal", null, null, false)),
            counters = mapOf("timer" to 1, "alarm" to 2, "stopwatch" to 1, "shopping" to 2),
            muted = true,
            restorePercent = 33,
        )
        assertEquals(world, ToolWorldJson.decode(ToolWorldJson.encode(world)))
    }

    @Test
    fun unreadableStateIsIgnored() {
        assertNull(ToolWorldJson.decode(null))
        assertNull(ToolWorldJson.decode("not json"))
        // A broken item is skipped instead of losing everything else.
        val world = ToolWorldJson.decode("""{"timers":[{"label":"sin id"}],"shopping":[{"id":"i1","name":"pan"}]}""")!!
        assertEquals(0, world.timers.size)
        assertEquals("pan", world.shopping.single().name)
    }

    @Test
    fun writesTheKeysApp14Reads() {
        // Older versions (and a downgrade) read these exact keys: renaming one silently empties the user's things.
        val world = requireNotNull(ToolWorldJson.decode(APP_14_STATE))
        val root = JSONObject(ToolWorldJson.encode(world))
        assertEquals(setOf("timers", "alarms", "stopwatches", "shopping", "counters", "muted", "restorePercent"), root.keyNames())
        assertEquals(setOf("id", "label", "deadlineMs", "remainingMs", "durationMs", "status"), root.getJSONArray("timers").getJSONObject(0).keyNames())
        assertEquals(setOf("id", "label", "nextMs", "repeat", "status"), root.getJSONArray("alarms").getJSONObject(0).keyNames())
        assertEquals(setOf("id", "label", "elapsedMs", "startedMs", "status"), root.getJSONArray("stopwatches").getJSONObject(0).keyNames())
        assertEquals(setOf("id", "name", "quantity", "unit", "completed"), root.getJSONArray("shopping").getJSONObject(0).keyNames())
        val repeat = root.getJSONArray("alarms").getJSONObject(0).getJSONObject("repeat")
        assertEquals("weekly", repeat.getString("type"))
        assertEquals(listOf("mon", "thu"), repeat.getJSONArray("days").let { days -> (0 until days.length()).map(days::getString) })
        // Integral quantities stay integers, as 1.4 wrote them.
        assertEquals(2, root.getJSONArray("shopping").getJSONObject(0).get("quantity"))
        // Reading what this version wrote gives back the same state.
        assertEquals(world, ToolWorldJson.decode(root.toString()))
    }

    @Test
    fun aQuantityJsonCannotHoldIsDroppedInsteadOfBreakingEverySave() {
        listOf("NaN", "Infinity", "-Infinity", "1e999").forEach { assertNull(it, ToolWorldJson.quantity(it)) }
        assertEquals(0.5, ToolWorldJson.quantity("0,5")!!, 0.0)
        val world = ToolWorld(shopping = listOf(ShoppingItem("i1", "arroz", Double.POSITIVE_INFINITY, "kg", false), ShoppingItem("i2", "sal", Double.NaN, null, false)))
        val decoded = requireNotNull(ToolWorldJson.decode(ToolWorldJson.encode(world)))
        assertEquals(listOf("arroz", "sal"), decoded.shopping.map { it.name })
        assertEquals(listOf(null, null), decoded.shopping.map { it.quantity })
    }

    @Test
    fun countersNeverFallBehindTheIdsInUse() {
        // Counters missing, behind or ahead: the next id must never be one already in use.
        val world = requireNotNull(ToolWorldJson.decode("""
            {"timers":[{"id":"t7","deadlineMs":1,"status":"running"}],"alarms":[{"id":"a3","nextMs":1},{"id":"alarma","nextMs":2}],
             "shopping":[{"id":"i2","name":"pan"},{"id":"i12","name":"sal"}],"counters":{"alarm":1,"shopping":20}}
        """.trimIndent()))
        assertEquals(mapOf("timer" to 7, "alarm" to 3, "shopping" to 20), world.counters)
        // Consistent state is left as it was.
        assertEquals(mapOf("timer" to 2, "alarm" to 2, "stopwatch" to 1, "shopping" to 2), ToolWorldJson.decode(APP_14_STATE)!!.counters)
    }

    @Test
    fun aWeeklyRepeatWithoutKnownDaysBecomesAOneOffAlarm() {
        // The engine cannot find the next ring of an empty weekly repeat: the alarm could not be dismissed.
        val world = requireNotNull(ToolWorldJson.decode("""
            {"alarms":[{"id":"a1","nextMs":1,"repeat":{"type":"weekly","days":["lunes"]}},{"id":"a2","nextMs":2,"repeat":{"type":"weekly"}},
                       {"id":"a3","nextMs":3,"repeat":{"type":"weekly","days":["sun","xyz","sun"]}}]}
        """.trimIndent()))
        assertEquals(listOf(null, null, Repeat.Weekly(listOf(DayOfWeek.SUNDAY))), world.alarms.map { it.repeat })
    }
}

private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()
