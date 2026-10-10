package com.kakauet.dina.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ToolEngineTest {
    private val t = TestTools()

    private fun timer(data: ToolData) = (data as ToolData.TimerItem).timer
    private fun alarm(data: ToolData) = (data as ToolData.AlarmItem).alarm
    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(t.zone).toLocalDateTime()

    @Test
    fun timerLifecycleKeepsStateAndScheduling() {
        val created = timer(t.ok(TimerCommand.Create(5 * MINUTE, "pasta")))
        assertEquals("t1", created.id)
        assertEquals(t.now + 5 * MINUTE, t.alerts.scheduled["timer/t1"])

        t.advance(2 * MINUTE)
        val paused = timer(t.ok(TimerCommand.Pause(Target(label = "PASTA"))))
        assertEquals(TimerStatus.PAUSED, paused.status)
        assertEquals(3 * MINUTE, paused.remainingMs)
        assertNull(t.alerts.scheduled["timer/t1"])

        t.advance(10 * MINUTE)
        val resumed = timer(t.ok(TimerCommand.Resume(Target(id = "t1"))))
        assertEquals(t.now + 3 * MINUTE, resumed.deadlineMs)

        assertEquals(ToolFailure.INVALID_STATE, t.failure(TimerCommand.Resume(Target(id = "t1"))))
        assertEquals(ToolData.RemovedId("t1"), t.ok(TimerCommand.Cancel(Target(position = Position.Last))))
        assertTrue(t.engine.state.value.timers.isEmpty())
        assertTrue(t.alerts.scheduled.isEmpty())
    }

    @Test
    fun renamingARunningTimerKeepsItsDeadline() {
        t.ok(TimerCommand.Create(10 * MINUTE))
        t.advance(4 * MINUTE)
        val renamed = timer(t.ok(TimerCommand.Update(Target(id = "t1"), TimerChange.Rename("horno"))))
        assertEquals("horno", renamed.label)
        assertEquals(6 * MINUTE, renamed.remainingAt(t.now))
    }

    @Test
    fun timerAddAndSubtractWorkOnLiveRemaining() {
        t.ok(TimerCommand.Create(10 * MINUTE))
        t.advance(4 * MINUTE)
        assertEquals(8 * MINUTE, timer(t.ok(TimerCommand.Update(Target(id = "t1"), TimerChange.Add(2 * MINUTE)))).remainingAt(t.now))
        assertEquals(1_000L, timer(t.ok(TimerCommand.Update(Target(id = "t1"), TimerChange.Subtract(60 * MINUTE)))).remainingAt(t.now))
    }

    @Test
    fun dueTimerRingsAndCanBeDismissedWithoutTarget() {
        t.ok(TimerCommand.Create(MINUTE))
        t.advance(MINUTE + 1)
        t.engine.refresh()
        assertEquals(TimerStatus.RINGING, t.engine.state.value.timers.single().status)
        assertEquals(ToolData.RemovedId("t1"), t.ok(TimerCommand.Dismiss()))
        assertEquals(ToolFailure.NOT_FOUND, t.failure(TimerCommand.Dismiss()))
    }

    @Test
    fun ambiguousAndMissingTargetsAreReported() {
        t.ok(TimerCommand.Create(MINUTE, "té"))
        t.ok(TimerCommand.Create(2 * MINUTE, "té"))
        assertEquals(ToolFailure.AMBIGUOUS, t.failure(TimerCommand.Get(Target(label = "té"))))
        assertEquals(ToolFailure.NOT_FOUND, t.failure(TimerCommand.Get(Target(label = "café"))))
        assertEquals("t2", timer(t.ok(TimerCommand.Get(Target(position = Position.Nth(2))))).id)
        assertEquals(ToolFailure.INVALID_ARGUMENTS, t.failure(TimerCommand.Create(0)))
        // The failed command did not consume an id.
        assertEquals("t3", timer(t.ok(TimerCommand.Create(MINUTE))).id)
    }

    @Test
    fun alarmTimeResolvesToNextOccurrenceAndDays() {
        // 10:00 now: 9:00 today has passed, so it means tomorrow.
        assertEquals(LocalDateTime.of(2026, 10, 6, 9, 0), local(alarm(t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(9, 0))))).nextMs))
        assertEquals(LocalDateTime.of(2026, 10, 5, 11, 30), local(alarm(t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(11, 30))))).nextMs))
        // 2026-10-05 is a Monday: "next Monday" is a week later.
        assertEquals(LocalDateTime.of(2026, 10, 12, 7, 0), local(alarm(t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(7, 0), DayRef.Next(DayOfWeek.MONDAY))))).nextMs))
        assertEquals(LocalDateTime.of(2026, 10, 7, 7, 0), local(alarm(t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(7, 0), DayRef.DayAfterTomorrow)))).nextMs))
        assertEquals(t.now + 30 * MINUTE, alarm(t.ok(AlarmCommand.Create(WhenSpec.After(30 * MINUTE)))).nextMs)
    }

    @Test
    fun repeatedAlarmWithoutDayGoesToItsNextRepeatDay() {
        // Monday 10:00: weekends -> Saturday, not tomorrow.
        assertEquals(LocalDateTime.of(2026, 10, 10, 7, 0), local(alarm(t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(7, 0)), repeat = Repeat.Weekends))).nextMs))
        // Later today still counts when today is a repeat day.
        assertEquals(LocalDateTime.of(2026, 10, 5, 18, 0), local(alarm(t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(18, 0)), repeat = Repeat.Weekdays))).nextMs))
        val weekly = Repeat.Weekly(listOf(DayOfWeek.THURSDAY))
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 0), local(alarm(t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(9, 0)), repeat = weekly))).nextMs))
        // Changing only the repeat moves the alarm to a valid day too.
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(6, 0))))
        assertEquals(LocalDateTime.of(2026, 10, 10, 6, 0), local(alarm(t.ok(AlarmCommand.Update(Target(id = "a4"), repeat = Patch(Repeat.Weekends)))).nextMs))
    }

    @Test
    fun emptyTargetMeansTheOnlyItem() {
        assertEquals(ToolFailure.NOT_FOUND, t.failure(TimerCommand.Pause(Target())))
        t.ok(TimerCommand.Create(MINUTE, "té"))
        assertEquals(TimerStatus.PAUSED, timer(t.ok(TimerCommand.Pause(Target()))).status)
        t.ok(TimerCommand.Create(2 * MINUTE, "café"))
        val outcome = t.engine.execute(TimerCommand.Get(Target())) as ToolOutcome.Failure
        assertEquals(ToolFailure.AMBIGUOUS, outcome.code)
        assertEquals(listOf("té", "café"), (outcome.candidates!!.data as ToolData.Timers).items.map { it.label })
    }

    @Test
    fun alarmsCanBeSelectedByTime() {
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(8, 0)), "clase"))
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(9, 30))))
        assertEquals("a2", alarm(t.ok(AlarmCommand.Disable(Target(time = LocalTime.of(9, 30))))).id)
        assertEquals(ToolFailure.NOT_FOUND, t.failure(AlarmCommand.Cancel(Target(time = LocalTime.of(7, 0)))))
    }

    @Test
    fun repeatingAlarmMovesToNextWeekdayWhenDismissed() {
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(10, 30)), repeat = Repeat.Weekdays))
        t.engine.markRinging(AlertKind.ALARM, "a1")
        val next = alarm(t.ok(AlarmCommand.Dismiss()))
        assertEquals(LocalDateTime.of(2026, 10, 6, 10, 30), local(next.nextMs))
        assertEquals(AlarmStatus.SCHEDULED, next.status)

        // Friday → Monday.
        t.ok(AlarmCommand.Update(Target(id = "a1"), at = WhenSpec.At(LocalTime.of(8, 0), DayRef.On(LocalDate.of(2026, 10, 9)))))
        t.engine.markRinging(AlertKind.ALARM, "a1")
        assertEquals(LocalDateTime.of(2026, 10, 12, 8, 0), local(alarm(t.ok(AlarmCommand.Dismiss(Target(id = "a1")))).nextMs))
    }

    @Test
    fun oneShotAlarmIsRemovedWhenDismissedAndSnoozeNeedsRinging() {
        t.ok(AlarmCommand.Create(WhenSpec.After(MINUTE)))
        assertEquals(ToolFailure.INVALID_STATE, t.failure(AlarmCommand.Snooze(Target(id = "a1"))))
        t.advance(MINUTE)
        val snoozed = alarm(t.ok(AlarmCommand.Snooze(minutes = 5)))
        assertEquals(AlarmStatus.SNOOZED, snoozed.status)
        assertEquals(t.now + 5 * MINUTE, snoozed.nextMs)
        t.advance(5 * MINUTE)
        assertEquals(ToolData.RemovedId("a1"), t.ok(AlarmCommand.Dismiss()))
    }

    @Test
    fun pastOneShotAlarmCannotBeEnabled() {
        t.ok(AlarmCommand.Create(WhenSpec.After(MINUTE)))
        t.ok(AlarmCommand.Disable(Target(id = "a1")))
        t.advance(2 * MINUTE)
        assertEquals(ToolFailure.INVALID_STATE, t.failure(AlarmCommand.Enable(Target(id = "a1"))))
    }

    @Test
    fun alarmPatchesCanClearFields() {
        t.ok(AlarmCommand.Create(WhenSpec.After(MINUTE), label = "gym", repeat = Repeat.Daily))
        val cleared = alarm(t.ok(AlarmCommand.Update(Target(id = "a1"), label = Patch(null), repeat = Patch(null))))
        assertNull(cleared.label)
        assertNull(cleared.repeat)
        assertEquals(ToolFailure.INVALID_ARGUMENTS, t.failure(AlarmCommand.Create(WhenSpec.After(MINUTE), repeat = Repeat.Weekly(emptyList()))))
    }

    @Test
    fun stopwatchPauseResumeAndReset() {
        t.ok(StopwatchCommand.Start("correr"))
        t.advance(90_000)
        val paused = (t.ok(StopwatchCommand.Pause(Target(label = "correr"))) as ToolData.StopwatchItem).stopwatch
        assertEquals(90_000L, paused.elapsedAt(t.now + 1_000_000))
        t.ok(StopwatchCommand.Resume(Target(id = "s1")))
        t.advance(10_000)
        assertEquals(100_000L, t.engine.state.value.stopwatches.single().elapsedAt(t.now))
        t.ok(StopwatchCommand.Reset(Target(id = "s1")))
        assertEquals(0L, t.engine.state.value.stopwatches.single().elapsedAt(t.now))
        assertEquals(ToolData.RemovedCount(1), t.ok(StopwatchCommand.DeleteAll))
    }

    @Test
    fun shoppingRejectsDuplicatesAndSelectsByName() {
        t.ok(ShoppingCommand.Add("Leche", 2.0, "litros"))
        assertEquals(ToolFailure.CONFLICT, t.failure(ShoppingCommand.Add("leche")))
        t.ok(ShoppingCommand.Add("pan"))
        val marked = (t.ok(ShoppingCommand.Toggle(Target(name = "LECHE"))) as ToolData.ShoppingEntry).item
        assertTrue(marked.completed)
        val updated = (t.ok(ShoppingCommand.Update(Target(id = "i2"), quantity = Patch(3.0), unit = Patch(null))) as ToolData.ShoppingEntry).item
        assertEquals(3.0, updated.quantity!!, 0.0)
        assertEquals(ToolData.RemovedCount(2), t.ok(ShoppingCommand.Clear))
        // Ids keep counting so references never point to a different, newer item.
        assertEquals("i3", (t.ok(ShoppingCommand.Add("huevos")) as ToolData.ShoppingEntry).item.id)
    }

    @Test
    fun volumeMuteRemembersAndRestoresLevel() {
        t.volume.value = 40
        val muted = (t.ok(VolumeCommand.Mute) as ToolData.Volume).info
        assertTrue(muted.muted)
        assertEquals(40, muted.restorePercent)
        assertEquals(0, t.volume.value)
        val restored = (t.ok(VolumeCommand.ToggleMute) as ToolData.Volume).info
        assertFalse(restored.muted)
        assertEquals(40, t.volume.value)
        assertEquals(100, (t.ok(VolumeCommand.Increase(90)) as ToolData.Volume).info.percent)
    }

    @Test
    fun dateTimeAndCalculator() {
        assertEquals("10:00", (t.ok(DateTimeCommand.Now(DatePart.TIME)) as ToolData.DateTimeValue).value.take(5))
        assertEquals("mon", (t.ok(DateTimeCommand.Now(DatePart.WEEKDAY)) as ToolData.DateTimeValue).value)
        assertEquals("09:00", (t.ok(DateTimeCommand.Now(DatePart.TIME, "Londres")) as ToolData.DateTimeValue).value.take(5))
        assertEquals(ToolFailure.NOT_FOUND, t.failure(DateTimeCommand.Now(DatePart.TIME, "Atlantis")))
        assertEquals(ToolData.DaysBetween(25), t.ok(DateTimeCommand.DaysBetween(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 26))))
        assertEquals(ToolData.OffsetSeconds(-3_600), t.ok(DateTimeCommand.OffsetDifference("madrid", "londres")))
        assertEquals(ToolData.Number(14L), t.ok(Calculate("2 + 3 * 4")))
        assertEquals(ToolData.Number(2.5), t.ok(Calculate("(2 + 3) / 2")))
        assertEquals(ToolData.Number(8L), t.ok(Calculate("2 ** 3")))
        assertEquals(ToolData.Number(3L), t.ok(Calculate("sqrt(9)")))
        assertEquals(ToolFailure.INVALID_ARGUMENTS, t.failure(Calculate("2 +")))
    }

    @Test
    fun calculatorFollowsPythonPrecedenceAndReadsSpokenNotation() {
        assertEquals(ToolData.Number(-4L), t.ok(Calculate("-2 ** 2")))
        assertEquals(ToolData.Number(512L), t.ok(Calculate("2 ** 3 ** 2")))
        assertEquals(ToolData.Number(0.25), t.ok(Calculate("2 ** -2")))
        assertEquals(ToolData.Number(391L), t.ok(Calculate("17 x 23")))
        assertEquals(ToolData.Number(391L), t.ok(Calculate("17×23")))
        assertEquals(ToolData.Number(39L), t.ok(Calculate("234 : 6")))
        assertEquals(ToolData.Number(13.5), t.ok(Calculate("3 * 4,5")))
        assertEquals(ToolData.Number(144L), t.ok(Calculate("12^2")))
        assertEquals(ToolData.Number(12L), t.ok(Calculate("sqrt(144)")))
        assertEquals(ToolFailure.INVALID_ARGUMENTS, t.failure(Calculate("sqrt(-4)")))
        assertEquals(ToolFailure.INVALID_ARGUMENTS, t.failure(Calculate("7 / 0")))
        // Too big for a Long: stays a Double instead of being clamped.
        assertEquals(ToolData.Number(1e20), t.ok(Calculate("10 ** 20")))
    }

    @Test
    fun ringingTimerGivenMoreTimeCountsDownAgain() {
        t.ok(TimerCommand.Create(MINUTE))
        t.advance(MINUTE)
        val more = timer(t.ok(TimerCommand.Update(Target(id = "t1"), TimerChange.Add(5 * MINUTE))))
        assertEquals(TimerStatus.RUNNING, more.status)
        assertEquals(5 * MINUTE, more.remainingAt(t.now))
        assertEquals(t.now + 5 * MINUTE, t.alerts.scheduled["timer/t1"])
    }

    @Test
    fun repeatedAlarmDismissedDaysLateGoesToItsNextFutureDay() {
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(10, 30)), repeat = Repeat.Daily))
        // The phone was off: the alarm rang on Monday and is dismissed on Thursday at noon.
        t.advance(3 * 24 * 60 * MINUTE + 2 * 60 * MINUTE)
        t.engine.refresh()
        val next = alarm(t.ok(AlarmCommand.Dismiss()))
        assertEquals(LocalDateTime.of(2026, 10, 9, 10, 30), local(next.nextMs))
        assertEquals(next.nextMs, t.alerts.scheduled["alarm/a1"])
    }

    @Test
    fun editingAnAlarmWhoseTimeHasGoneMovesItToTheFuture() {
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(10, 30))))
        t.ok(AlarmCommand.Disable(Target(id = "a1")))
        t.advance(60 * MINUTE)
        // Renaming re-enables it: it must not ring at once for a time already gone.
        val renamed = alarm(t.ok(AlarmCommand.Update(Target(id = "a1"), label = Patch("médico"))))
        assertEquals(LocalDateTime.of(2026, 10, 6, 10, 30), local(renamed.nextMs))
        val repeated = alarm(t.ok(AlarmCommand.Update(Target(id = "a1"), repeat = Patch(Repeat.Weekly(listOf(DayOfWeek.MONDAY))))))
        assertEquals(LocalDateTime.of(2026, 10, 12, 10, 30), local(repeated.nextMs))
    }

    @Test
    fun everyChangeIsPersistedAndRestored() {
        t.ok(TimerCommand.Create(MINUTE))
        t.ok(ShoppingCommand.Add("pan"))
        val reloaded = ToolEngine(MemoryStore(t.store.world), t.alerts, t.volume, clock = { t.now }, zone = { t.zone })
        assertEquals(1, reloaded.state.value.timers.size)
        assertEquals("i2", (reloaded.execute(ShoppingCommand.Add("sal")) as? ToolOutcome.Success)?.data?.let { (it as ToolData.ShoppingEntry).item.id })
    }

    @Test
    fun rescheduleAllRegistersPendingAlerts() {
        t.ok(TimerCommand.Create(MINUTE))
        t.ok(AlarmCommand.Create(WhenSpec.After(MINUTE)))
        t.alerts.scheduled.clear()
        t.engine.rescheduleAll()
        assertEquals(setOf("timer/t1", "alarm/a1"), t.alerts.scheduled.keys)
    }

    @Test
    fun snapshotRingsDueAlertsAndCarriesClockAndVolume() {
        t.ok(TimerCommand.Create(MINUTE, "té"))
        t.advance(2 * MINUTE)
        val snapshot = t.engine.snapshot()
        assertEquals(TimerStatus.RINGING, snapshot.world.timers.single().status)
        assertEquals(TimerStatus.RINGING, t.engine.state.value.timers.single().status)
        assertEquals(t.now, snapshot.nowMs)
        assertEquals(t.zone, snapshot.zone)
        assertEquals(50, snapshot.volume.percent)
    }
}
