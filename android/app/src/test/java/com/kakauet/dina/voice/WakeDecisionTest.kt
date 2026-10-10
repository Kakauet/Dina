package com.kakauet.dina.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeDecisionTest {
    @Test
    fun needsTwoConsecutiveStepsOverTheThreshold() {
        val decision = WakeDecision()
        assertEquals(listOf(false, false, false, true), listOf(.96f, .2f, .97f, .98f).map { decision.accept(it, .95f) })
    }

    @Test
    fun pausesAfterAnActivation() {
        val decision = WakeDecision()
        decision.accept(.99f, .95f)
        assertTrue(decision.accept(.99f, .95f))
        val during = List(WakeDecision.REFRACTORY_STEPS - 1) { decision.accept(.99f, .95f) }
        assertTrue(during.none { it })
        assertEquals(listOf(false, true), listOf(decision.accept(.99f, .95f), decision.accept(.99f, .95f)))
    }

    @Test
    fun ignoresInvalidScoresAndResets() {
        val decision = WakeDecision()
        decision.accept(.99f, .95f)
        decision.accept(Float.NaN, .95f)
        assertEquals(false, decision.accept(.99f, .95f))
        decision.reset()
        assertEquals(false, decision.accept(.99f, .95f))
    }

    @Test
    fun sensitivityLevelsAreOrdered() {
        val (low, normal, high) = listOf(WakeSensitivity.LOW, WakeSensitivity.NORMAL, WakeSensitivity.HIGH).map { it.threshold }
        assertTrue(low > normal && normal > high)
    }
}
