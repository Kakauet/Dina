package com.kakauet.dina.dialog

import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.DayRef
import com.kakauet.dina.tools.MINUTE
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.TestTools
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.WhenSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class ResolverTest {
    // Monday 5 October 2026, 10:00.
    private val t = TestTools().apply {
        ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(7, 0), DayRef.Tomorrow), "trabajo"))
        ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(19, 0), DayRef.Tomorrow), "gimnasio"))
        ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(10, 0), DayRef.Next(DayOfWeek.SATURDAY)), "pollo al horno"))
        ok(TimerCommand.Create(10 * MINUTE, "pasta"))
        ok(TimerCommand.Create(3 * MINUTE, "té"))
        ok(ShoppingCommand.Add("leche entera"))
        ok(ShoppingCommand.Add("leche de avena"))
        ok(ShoppingCommand.Add("pan"))
    }

    private fun ids(ref: Ref?, domain: Domain = Domain.ALARM, context: RefContext = RefContext(), day: Day? = null) =
        Resolver.resolve(ref, domain, t.engine.snapshot(), context, day).map { it.id }

    @Test
    fun labelsMatchByKeyThenByWords() {
        assertEquals(listOf("a2"), ids(Ref.Named("el Gimnasio")))
        assertEquals(listOf("a3"), ids(Ref.Named("pollo")))
        assertEquals(listOf("t2"), ids(Ref.Named("te"), Domain.TIMER))
        assertEquals(listOf("i1", "i2"), ids(Ref.Named("leche"), Domain.LIST))
        assertEquals(listOf("i3"), ids(Ref.Named("panes"), Domain.LIST))
        assertEquals(emptyList<String>(), ids(Ref.Named("yoga")))
    }

    @Test
    fun timesWithoutPeriodMatchBothAndPeriodsNarrow() {
        assertEquals(listOf("a1", "a2"), ids(Ref.At(Clock(7, 0))))
        assertEquals(listOf("a2"), ids(Ref.At(Clock(7, 0, Period.AFTERNOON))))
        assertEquals(listOf("a2"), ids(Ref.InPeriod(Period.AFTERNOON)))
        assertEquals(listOf("a1", "a3"), ids(Ref.InPeriod(Period.MORNING)))
        assertEquals(listOf("a1", "a2"), ids(null, day = Day.Tomorrow))
        assertEquals(listOf("a3"), ids(null, day = Day.Weekday(DayOfWeek.SATURDAY)))
        assertEquals(emptyList<String>(), ids(null, day = Day.Today))
    }

    @Test
    fun positionsFollowWhatWasReadOrOfferedElseCreation() {
        assertEquals(listOf("a2"), ids(Ref.Nth(2)))
        assertEquals(listOf("a3"), ids(Ref.Last))
        assertEquals(listOf("a1"), ids(Ref.Nth(2), context = RefContext(read = listOf("a3", "a1", "a2"))))
        assertEquals(listOf("a2"), ids(Ref.Last, context = RefContext(offered = listOf("a1", "a2"))))
        assertEquals(listOf("a2"), ids(Ref.InPeriod(Period.AFTERNOON), context = RefContext(offered = listOf("a1", "a2"))))
        assertEquals(listOf("a1", "a2"), ids(Ref.All, context = RefContext(offered = listOf("a1", "a2"))))
        assertEquals(listOf("a1", "a2", "a3"), ids(Ref.All))
    }

    @Test
    fun focusNextAndRinging() {
        assertEquals(listOf("a2"), ids(Ref.Focus, context = RefContext(focus = "a2")))
        assertEquals(listOf("a1", "a2", "a3"), ids(Ref.Focus))
        assertEquals(listOf("a1"), ids(Ref.Next))
        assertEquals(listOf("t2"), ids(Ref.Next, Domain.TIMER))
        assertEquals(emptyList<String>(), ids(Ref.Ringing, Domain.TIMER))
        t.advance(4 * MINUTE)
        assertEquals(listOf("t2"), ids(Ref.Ringing, Domain.TIMER))
    }
}
