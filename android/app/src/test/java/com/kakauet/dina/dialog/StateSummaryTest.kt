package com.kakauet.dina.dialog

import com.kakauet.dina.brain.dina45.Dina45Codec
import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.DayRef
import com.kakauet.dina.tools.MINUTE
import com.kakauet.dina.tools.Repeat
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.TestTools
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.WhenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class StateSummaryTest {
    // Monday 5 October 2026, 10:00.
    private val t = TestTools()

    @Test
    fun onlyDomainsWithSomethingAndTheClock() {
        assertEquals("hora: lun 5 oct, 10:00", StateSummary.render(t.engine.snapshot()))
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(6, 45), null), "trabajo", Repeat.Weekdays))
        t.ok(TimerCommand.Create(5 * MINUTE, "pasta"))
        t.ok(ShoppingCommand.Add("huevos", 6.0))
        assertEquals(
            listOf("hora: lun 5 oct, 10:00", "alarmas: mañana 6:45 «trabajo» lun-vie", "temporizadores: «pasta» 5:00", "compra: huevos ×6"),
            StateSummary.render(t.engine.snapshot()).lines(),
        )
    }

    @Test
    fun longListsAreCutAndPositionsOnlyAfterReading() {
        (1..8).forEach { t.ok(ShoppingCommand.Add("cosa$it")) }
        val line = StateSummary.render(t.engine.snapshot()).lines().last()
        assertEquals("compra: cosa1 · cosa2 · cosa3 · cosa4 · cosa5 y 3 más", line)
        val d = Dialogue(t.executor, seed = 1)
        d.run(listOf(Dina45Codec.decodeLine("list.list()")!!))
        assertTrue(StateSummary.render(t.engine.snapshot(), d.view()).contains("#2 cosa2"))
    }

    @Test
    fun volumeFocusAndPending() {
        val d = Dialogue(t.executor, seed = 1)
        assertFalse(StateSummary.render(t.engine.snapshot(), d.view()).contains("volumen"))
        d.run(listOf(Dina45Codec.decodeLine("vol.set(30)")!!))
        assertTrue(StateSummary.render(t.engine.snapshot(), d.view()).contains("volumen: 30"))
        d.run(listOf(Dina45Codec.decodeLine("alarm.add(7:00 mañana, day=mañana, \"gym\")")!!))
        d.run(listOf(Dina45Codec.decodeLine("timer.add(\"pasta\")")!!))
        val text = StateSummary.render(t.engine.snapshot(), d.view())
        assertTrue(text, text.contains("foco: alarma mañana 7:00 «gym»"))
        assertTrue(text, text.contains("pendiente: timer.add(\"pasta\") falta duración"))
        t.ok(TimerCommand.Create(MINUTE, "té"))
        t.advance(2 * MINUTE)
        assertTrue(StateSummary.render(t.engine.snapshot(), d.view()).contains("«té» 0:00 (sonando)"))
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(9, 0), DayRef.On(java.time.LocalDate.of(2026, 10, 30)))))
        assertTrue(StateSummary.render(t.engine.snapshot(), d.view()).contains("30/10 9:00"))
    }
}
