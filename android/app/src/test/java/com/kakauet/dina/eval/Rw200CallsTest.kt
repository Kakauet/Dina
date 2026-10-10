package com.kakauet.dina.eval

import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.Calculate
import com.kakauet.dina.tools.DatePart
import com.kakauet.dina.tools.DateTimeCommand
import com.kakauet.dina.tools.DayRef
import com.kakauet.dina.tools.MINUTE
import com.kakauet.dina.tools.Patch
import com.kakauet.dina.tools.Position
import com.kakauet.dina.tools.Repeat
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.StopwatchCommand
import com.kakauet.dina.tools.Target
import com.kakauet.dina.tools.TestTools
import com.kakauet.dina.tools.TimerChange
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.ToolFailure
import com.kakauet.dina.tools.VolumeCommand
import com.kakauet.dina.tools.WhenSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class Rw200CallsTest {
    private val t = TestTools()
    private val calls = Rw200Calls(clock = { t.now }, zone = { t.zone })

    private fun decode(name: String, json: String) = calls.decode(name, JSONObject(json))

    private fun decodeFailure(name: String, json: String): String = try {
        decode(name, json)
        fail("Expected a decoding failure for $json")
        ""
    } catch (failure: ToolFailure) {
        failure.code
    }

    @Test
    fun decodesCanonicalCalls() {
        assertEquals(TimerCommand.Create(5 * MINUTE, "pasta"), decode("timer", """{"op":"create","duration":{"value":5,"unit":"min"},"label":"pasta"}"""))
        assertEquals(
            AlarmCommand.Create(WhenSpec.At(LocalTime.of(7, 30), DayRef.Tomorrow), repeat = Repeat.Weekdays),
            decode("alarm", """{"op":"create","when":{"day":"tomorrow","time":"7:30"},"repeat":{"type":"weekdays"}}"""),
        )
        assertEquals(
            AlarmCommand.Create(WhenSpec.At(LocalTime.of(19, 0), DayRef.Next(DayOfWeek.FRIDAY))),
            decode("alarm", """{"op":"create","when":{"day":"fri","time":"7 pm"}}"""),
        )
        assertEquals(StopwatchCommand.Pause(Target(position = Position.Nth(2))), decode("stopwatch", """{"op":"pause","target":{"position":"second"}}"""))
        assertEquals(ShoppingCommand.Add("leche", 2.0, "litros"), decode("shopping", """{"op":"add","name":"leche","quantity":2,"unit":"litros"}"""))
        assertEquals(VolumeCommand.Set(30), decode("volume", """{"op":"set","percent":30}"""))
        assertEquals(DateTimeCommand.Now(DatePart.TIME, "Tokio"), decode("datetime", """{"op":"now","part":"time","location":"Tokio"}"""))
        assertEquals(Calculate("2+2"), decode("calculator", """{"op":"calculate","expression":"2+2"}"""))
    }

    @Test
    fun acceptsSmallModelAliases() {
        assertEquals(AlarmCommand.Enable(Target(label = "gym")), decode("alarm", """{"op":"on","target":"gym"}"""))
        assertEquals(TimerCommand.Cancel(Target(position = Position.Last)), decode("timer", """{"op":"stop","target":{"position":"last"}}"""))
        assertEquals(TimerCommand.Create(90_000), decode("timer", """{"op":"create","duration_s":90}"""))
        assertEquals(TimerCommand.Update(Target(id = "t1"), TimerChange.Subtract(30_000)), decode("timer", """{"op":"update","target":{"id":"t1"},"delta_s":-30}"""))
        assertEquals(TimerCommand.Update(Target(id = "t1"), TimerChange.Rename("horno")), decode("timer", """{"op":"rename","target":{"id":"t1"},"label":"horno"}"""))
        assertEquals(VolumeCommand.Set(20), decode("volume", """{"op":"set","level":20}"""))
        assertEquals(VolumeCommand.Restore, decode("volume", """{"op":"unmute"}"""))
        assertEquals(ShoppingCommand.Mark(Target(name = "pan")), decode("shopping", """{"op":"check","target":{"name":"pan"}}"""))
        assertEquals(AlarmCommand.Snooze(null, 15), decode("alarm", """{"op":"snooze","duration":{"value":15,"unit":"min"}}"""))
        assertEquals(DateTimeCommand.Shift(days = 3), decode("datetime", """{"op":"shift","offset":{"value":3,"unit":"day"}}"""))
        // 10:00 now; "until 10:30" is a 30-minute timer.
        assertEquals(TimerCommand.Create(30 * MINUTE), decode("timer", """{"op":"create","until":"10:30"}"""))
    }

    @Test
    fun patchesDistinguishMissingFromNull() {
        val update = decode("alarm", """{"op":"update","target":{"id":"a1"},"changes":{"label":null}}""") as AlarmCommand.Update
        assertEquals(Patch(null), update.label)
        assertEquals(null, update.repeat)
        val shopping = decode("shopping", """{"op":"update","target":{"id":"i1"},"changes":{"quantity":3}}""") as ShoppingCommand.Update
        assertEquals(Patch(3.0), shopping.quantity)
        assertEquals(null, shopping.name)
    }

    @Test
    fun missingTargetIsLeftToTheEngine() {
        assertEquals(TimerCommand.Pause(Target()), decode("timer", """{"op":"pause"}"""))
        assertEquals(ShoppingCommand.Remove(Target(name = "arroz")), decode("shopping", """{"op":"remove","name":"arroz"}"""))
        assertEquals(AlarmCommand.Cancel(Target(time = LocalTime.of(8, 0))), decode("alarm", """{"op":"cancel","target":{"time":"8:00"}}"""))
    }

    @Test
    fun rejectsInvalidCalls() {
        assertEquals(ToolFailure.UNSUPPORTED, decodeFailure("weather", """{"op":"get"}"""))
        assertEquals(ToolFailure.UNSUPPORTED, decodeFailure("timer", """{"op":"explode"}"""))
        assertEquals(ToolFailure.INVALID_ARGUMENTS, decodeFailure("timer", """{"duration":{"value":5,"unit":"min"}}"""))
        assertEquals(ToolFailure.INVALID_ARGUMENTS, decodeFailure("timer", """{"op":"create","duration":{"value":5,"unit":"days"}}"""))
        assertEquals(ToolFailure.INVALID_ARGUMENTS, decodeFailure("timer", """{"op":"create"}"""))
        assertEquals(ToolFailure.INVALID_ARGUMENTS, decodeFailure("alarm", """{"op":"create","when":{"time":"25:99"}}"""))
        assertEquals(ToolFailure.INVALID_ARGUMENTS, decodeFailure("alarm", """{"op":"create","when":{"time":"7:00","day":"someday"}}"""))
        assertEquals(ToolFailure.INVALID_ARGUMENTS, decodeFailure("alarm", """{"op":"create","when":{"time":"7:00"},"repeat":{"type":"hourly"}}"""))
    }
}
