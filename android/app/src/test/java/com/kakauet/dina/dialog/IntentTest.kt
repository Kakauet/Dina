package com.kakauet.dina.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class IntentTest {
    private val monday = LocalDate.of(2026, 10, 5)

    @Test
    fun clocksKnowWhenMorningOrAfternoonIsMissing() {
        assertEquals(listOf(LocalTime.of(7, 0), LocalTime.of(19, 0)), Clock(7, 0).times())
        assertTrue(Clock(7, 0).ambiguous)
        assertEquals(listOf(LocalTime.of(19, 30)), Clock(7, 30, Period.AFTERNOON).times())
        assertEquals(listOf(LocalTime.of(21, 0)), Clock(9, 0, Period.NIGHT).times())
        assertEquals(listOf(LocalTime.of(2, 0)), Clock(2, 0, Period.NIGHT).times())
        assertEquals(listOf(LocalTime.of(0, 0)), Clock(12, 0, Period.NIGHT).times())
        assertEquals(listOf(LocalTime.of(12, 0)), Clock(12, 0).times())
        assertFalse(Clock(19, 0).ambiguous)
        assertEquals("7:05 mañana", Clock.exact(LocalTime.of(7, 5)).encode())
        assertEquals("19:00", Clock.exact(LocalTime.of(19, 0)).encode())
    }

    @Test
    fun daysResolveToTheNextDateAndRejectImpossibleOnes() {
        assertEquals(monday.plusDays(1), Day.Tomorrow.resolve(monday))
        assertEquals(monday.minusDays(1), Day.Yesterday.resolve(monday))
        assertEquals(LocalDate.of(2026, 10, 12), Day.Weekday(DayOfWeek.MONDAY).resolve(monday))
        assertEquals(LocalDate.of(2026, 10, 9), Day.Weekday(DayOfWeek.FRIDAY).resolve(monday))
        assertEquals(LocalDate.of(2026, 12, 25), Day.Date(25, 12).resolve(monday))
        assertEquals(LocalDate.of(2027, 10, 2), Day.Date(2, 10).resolve(monday))
        assertEquals(LocalDate.of(2028, 2, 29), Day.Date(29, 2).resolve(monday))
        assertNull(Day.Date(31, 2).resolve(monday))
        assertEquals(monday.plusDays(3), Day.Plus(3).resolve(monday))
        assertEquals(Day.Date(2, 10), Day.of(LocalDate.of(2027, 10, 2), monday))
        assertEquals(Day.Date(2, 10, 2028), Day.of(LocalDate.of(2028, 10, 2), monday))
        assertEquals(Day.Tomorrow, Day.of(monday.plusDays(1), monday))
        listOf("hoy", "mañana", "pasado", "ayer", "miércoles", "25/12", "1/1/2027", "+3", "25", "navidad", "año nuevo").forEach { assertEquals(it, Day.parse(it)!!.encode()) }
    }

    @Test
    fun aDayOfTheMonthOrAHolidayIsTheNextOne() {
        assertEquals(LocalDate.of(2026, 10, 25), Day.MonthDay(25).resolve(monday))
        assertEquals(monday, Day.MonthDay(5).resolve(monday))
        assertEquals(LocalDate.of(2026, 11, 2), Day.MonthDay(2).resolve(monday))
        assertEquals(LocalDate.of(2026, 10, 31), Day.MonthDay(31).resolve(monday))
        assertEquals(LocalDate.of(2026, 12, 31), Day.MonthDay(31).resolve(LocalDate.of(2026, 11, 3)))
        assertEquals(LocalDate.of(2026, 12, 25), Day.Named("navidad").resolve(monday))
        assertEquals(LocalDate.of(2026, 12, 24), Day.Named("nochebuena").resolve(monday))
        assertEquals(LocalDate.of(2027, 1, 6), Day.Named("reyes").resolve(monday))
        assertEquals(LocalDate.of(2027, 1, 1), Day.Named("año nuevo").resolve(monday))
        assertNull(Day.parse("32"))
        assertNull(Day.parse("0"))
    }

    @Test
    fun durationsRoundTrip() {
        assertEquals(5_400_000L, Durations.parse("1h30m"))
        assertEquals(45_000L, Durations.parse("45s"))
        assertEquals(0L, Durations.parse("0s"))
        assertNull(Durations.parse("10"))
        assertNull(Durations.parse(""))
        assertEquals("1h30m", Durations.encode(5_400_000L))
        assertEquals("2m5s", Durations.encode(125_000L))
        assertEquals("+15m", Shift(900_000L).encode())
        assertEquals("-30m", Shift(-1_800_000L).encode())
    }

    @Test
    fun actionsEncodeInTheCanonicalOrder() {
        val action = Action("alarm.add", null, mapOf("label" to "trabajo", "repeat" to com.kakauet.dina.tools.Repeat.Weekdays, "day" to Day.Tomorrow, "time" to Clock(7, 0, Period.MORNING)))
        assertEquals("alarm.add(7:00 mañana, day=mañana, repeat=laborables, \"trabajo\")", action.encode())
        assertEquals("alarm.edit(@, at=-30m)", Action("alarm.edit", Ref.Focus, mapOf("at" to Shift(-1_800_000L))).encode())
        assertEquals("list.edit(\"leche\", n=+2)", Action("list.edit", Ref.Named("leche"), mapOf("n" to Delta(2.0))).encode())
        assertEquals("alarm.edit(label=?)", Action("alarm.edit", null, mapOf("label" to Missing)).encode())
        assertEquals("say(\"Hola, 'Dina'\")", Action("say", null, mapOf("text" to "Hola, \"Dina\"")).encode())
        assertTrue(Ops.all.map { it.op }.toSet().size == Ops.all.size)
    }
}
