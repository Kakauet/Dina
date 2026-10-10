package com.kakauet.dina.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ToolPresentationTest {
    private val t = TestTools()

    private fun run(vararg commands: ToolCommand) = commands.map { ToolExecution.of(it, t.engine.execute(it)) }

    @Test
    fun widgetFollowsTheLastResult() {
        val timer = ToolPresentation.widget(run(TimerCommand.Create(MINUTE)))!!
        assertEquals(WidgetKind.TIMER, timer.kind)
        assertEquals("t1", timer.focusId)
        assertTrue(timer.autoOpen)

        val list = ToolPresentation.widget(run(AlarmCommand.List))!!
        assertTrue(list.showAll)

        assertNull(ToolPresentation.widget(run(TimerCommand.Cancel(Target(id = "t1")))))
        assertNull(ToolPresentation.widget(run(TimerCommand.Get(Target(id = "missing")))))

        val volume = ToolPresentation.widget(run(VolumeCommand.Get), nowMs = 1_000)!!
        assertEquals(5_500L, volume.expiresAtMs)

        val time = ToolPresentation.widget(run(DateTimeCommand.Now(DatePart.TIME)))!!
        assertFalse(time.autoOpen)
    }

    @Test
    fun widgetValuesAreReadable() {
        assertEquals("12", ToolPresentation.widget(run(Calculate("3*4")))!!.value)
        assertEquals("0,74", ToolPresentation.widget(run(Calculate("17/23")))!!.value)
        assertEquals("10:00", ToolPresentation.widget(run(DateTimeCommand.Now(DatePart.TIME)))!!.value)
        assertEquals("lunes, 5 de octubre", ToolPresentation.widget(run(DateTimeCommand.Now(DatePart.DATE)))!!.value)
        assertEquals("sábado, 10 de octubre", ToolPresentation.widget(run(DateTimeCommand.Weekday(LocalDate.of(2026, 10, 10))))!!.value)
        assertEquals("81 días", ToolPresentation.widget(run(DateTimeCommand.DaysBetween(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 12, 25))))!!.value)
    }

    @Test
    fun numbersHaveAtMostTwoDecimals() {
        assertEquals("391", ToolPresentation.number(391L))
        assertEquals("2,5", ToolPresentation.number(2.5))
        assertEquals("0,74", ToolPresentation.number(17.0 / 23))
        assertEquals("100000000000000000000", ToolPresentation.number(1e20))
        assertTrue(ToolPresentation.isRounded(17.0 / 23))
        assertFalse(ToolPresentation.isRounded(2.5))
        assertFalse(ToolPresentation.isRounded(391L))
    }

    @Test
    fun durationsUseAtMostTwoUnits() {
        assertEquals("1 hora y 30 minutos", ToolPresentation.duration(5_400))
        assertEquals("1 minuto y 5 segundos", ToolPresentation.duration(65))
        assertEquals("0 segundos", ToolPresentation.duration(0))
    }

    @Test
    fun conversionAndPlaceCardsShowTheirValues() {
        val miles = ToolPresentation.widget(run(Convert(3.5, "milla", "km")))!!
        assertEquals(WidgetKind.CONVERSION, miles.kind)
        assertEquals("≈ 5,63 km", miles.value)
        assertEquals("3,5 mi", miles.detail)
        val cups = ToolPresentation.widget(run(Convert(2.0, "taza", "g", "harina")))!!
        assertEquals("250 g", cups.value)
        assertEquals("2 tazas de harina", cups.detail)
        assertEquals("356 °F", ToolPresentation.widget(run(Convert(180.0, "celsius", "fahrenheit")))!!.value)
        val london = ToolPresentation.widget(run(DateTimeCommand.Now(DatePart.TIME, "londres")))!!
        assertEquals("Londres · 9:00", london.value)
        assertTrue(london.autoOpen)
    }
}
