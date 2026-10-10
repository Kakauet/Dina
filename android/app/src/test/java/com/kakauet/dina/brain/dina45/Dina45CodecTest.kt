package com.kakauet.dina.brain.dina45

import com.kakauet.dina.dialog.Action
import com.kakauet.dina.dialog.Clock
import com.kakauet.dina.dialog.Day
import com.kakauet.dina.dialog.Delta
import com.kakauet.dina.dialog.Fraction
import com.kakauet.dina.dialog.Missing
import com.kakauet.dina.dialog.NoRepeat
import com.kakauet.dina.dialog.Ops
import com.kakauet.dina.dialog.Period
import com.kakauet.dina.dialog.Ref
import com.kakauet.dina.dialog.Shift
import com.kakauet.dina.tools.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class Dina45CodecTest {
    /** One full example per op of the contract (and a few partial ones). */
    val examples: List<Action> = listOf(
        Action("alarm.add", null, mapOf("time" to Clock(7, 0, Period.MORNING), "day" to Day.Tomorrow, "repeat" to Repeat.Weekly(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)), "label" to "trabajo")),
        Action("alarm.add", null, mapOf("day" to Day.Weekday(DayOfWeek.FRIDAY))),
        Action("alarm.add", null, mapOf("time" to Clock(19, 30), "day" to Day.Date(25, 12))),
        Action("alarm.edit", Ref.At(Clock(7, 0)), mapOf("at" to Clock(8, 30), "day" to Day.Plus(3), "repeat" to NoRepeat, "label" to "gym")),
        Action("alarm.edit", Ref.Focus, mapOf("at" to Shift(-1_800_000L))),
        Action("alarm.edit", null, mapOf("label" to Missing)),
        Action("alarm.del", Ref.InPeriod(Period.AFTERNOON)),
        Action("alarm.del", null, mapOf("day" to Day.Tomorrow)),
        Action("alarm.off", Ref.Named("pádel")),
        Action("alarm.on", Ref.Nth(2)),
        Action("alarm.get", Ref.Next),
        Action("alarm.list"),
        Action("alarm.snooze", null, mapOf("dur" to 300_000L)),
        Action("timer.add", null, mapOf("dur" to 5_400_000L, "label" to "asado")),
        Action("timer.pause", Ref.All),
        Action("timer.resume", Ref.Last),
        Action("timer.del", Ref.Ringing),
        Action("timer.get"),
        Action("timer.list"),
        Action("timer.plus", Ref.Named("pasta"), mapOf("dur" to 120_000L)),
        Action("timer.minus", null, mapOf("dur" to 45_000L)),
        Action("timer.edit", Ref.Focus, mapOf("left" to 1_200_000L, "label" to "pescado")),
        Action("sw.add", null, mapOf("label" to "plancha")),
        Action("sw.pause"), Action("sw.resume"), Action("sw.reset"), Action("sw.restart"), Action("sw.del", Ref.All), Action("sw.get"), Action("sw.list"),
        Action("list.add", null, mapOf("name" to "leche", "n" to 2.0, "unit" to "l")),
        Action("list.del", Ref.Named("pan")),
        Action("list.check", Ref.Nth(1)),
        Action("list.uncheck", Ref.Named("aceite")),
        Action("list.edit", Ref.Named("leche"), mapOf("n" to Delta(2.0), "name" to "leche entera")),
        Action("list.list"),
        Action("vol.set", null, mapOf("n" to 150)), Action("vol.up"), Action("vol.down", null, mapOf("n" to 20)),
        Action("vol.mute"), Action("vol.unmute"), Action("vol.get"),
        Action("time.now", null, mapOf("part" to "fecha")),
        Action("time.weekday", null, mapOf("day" to Day.AfterTomorrow)),
        Action("time.until", null, mapOf("day" to Day.Date(1, 1, 2027))),
        Action("calc", null, mapOf("expr" to "sqrt(144)*2")),
        Action("time.now", null, mapOf("part" to "hora", "place" to "Londres")),
        Action("time.until", null, mapOf("day" to Day.Named("año nuevo"))),
        Action("time.weekday", null, mapOf("day" to Day.MonthDay(25))),
        Action("conv", null, mapOf("n" to 3.5, "from" to "milla", "to" to "km")),
        Action("conv", null, mapOf("n" to Fraction(1, 3), "from" to "taza", "to" to "g", "what" to "harina")),
        Action("conv", null, mapOf("n" to -10.0, "from" to "celsius")),
        Action("conv", null, mapOf("from" to "km/h", "to" to "m/s")),
        Action("stop"), Action("ask"), Action("no", null, mapOf("topic" to "música")), Action("say", null, mapOf("text" to "¡Hola, Kakauet! ¿Qué tal?")),
        Action("undo"), Action("drop"), Action("yes"), Action("nope"), Action("fun.joke"), Action("fun.fact"),
    )

    @Test
    fun everyOpRoundTrips() {
        assertTrue(Ops.all.all { spec -> examples.any { it.op == spec.op } })
        examples.forEach { action -> assertEquals(action.encode(), action, Dina45Codec.decodeLine(action.encode())) }
        val decoded = Dina45Codec.decode(Dina45Codec.encode(examples))
        assertEquals(examples, decoded.actions)
        assertEquals(0, decoded.invalidLines)
    }

    @Test
    fun readsPositionalValuesByTypeInAnyOrder() {
        assertEquals(Dina45Codec.decodeLine("alarm.add(7:00, \"trabajo\")"), Dina45Codec.decodeLine("alarm.add(\"trabajo\", 7:00)"))
        assertEquals(Action("timer.plus", null, mapOf("dur" to 300_000L)), Dina45Codec.decodeLine("timer.plus(5m)"))
        assertEquals(Action("alarm.add", null, mapOf("time" to Clock(7, 0), "repeat" to Repeat.Weekly(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))), Dina45Codec.decodeLine("alarm.add(7:00, repeat=mie,lun)"))
        assertEquals(Action("list.edit", Ref.Named("leche"), mapOf("n" to 3.0)), Dina45Codec.decodeLine("  list.edit(\"leche\", n=3)  "))
    }

    @Test
    fun rejectsWhatIsNotTheContract() {
        listOf(
            "alarm.add(25:00)", "alarm.fly()", "timer.add(\"pasta)", "timer.pause(7:00)", "vol.set(alto)", "say(hola)",
            "alarm.add(7:00, 8:00)", "no(nadar)", "alarm.add(7:00, day=lunes, day=martes)", "time.now(semana)", "{\"tool_calls\":[]}",
            "conv(3.5, millas, km)", "conv(1/0, taza)", "conv(1.5/2, taza)", "time.until(32)", "time.until(navidades)",
        ).forEach { assertNull(it, Dina45Codec.decodeLine(it)) }
        val decoded = Dina45Codec.decode("alarm.list()\nhola\n\nvol.get()")
        assertEquals(listOf(Action("alarm.list"), Action("vol.get")), decoded.actions)
        assertEquals(1, decoded.invalidLines)
    }
}
