package com.kakauet.dina.ui.character

import com.kakauet.dina.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MotionTest {
    @Test
    fun appendagesStayStillWhenTheBodyDoes() {
        val springs = Secondary()
        springs.reset(0.01f, 3f, 0f)
        repeat(120) { springs.step(1f / 60f, 0.01f, 3f, 0f) }
        assertEquals(0f, springs.flop, 1e-6f)
        assertEquals(0f, springs.sway, 1e-6f)
    }

    @Test
    fun appendagesTrailAJumpOvershootAndSettle() {
        val springs = Secondary()
        springs.reset(0f, 0f, 0f)
        val flops = (1..240).map { springs.step(1f / 60f, 0.05f, 0f, 0f); springs.flop }
        assertTrue("cuelgan al saltar: ${flops[2]}", flops[2] > 0.02f)
        assertTrue("rebotan un poco: ${flops.min()}", flops.min() < 0f && flops.min() > -0.05f)
        assertEquals(0f, flops.last(), 1e-3f)
    }

    @Test
    fun appendagesTrailATurn() {
        val springs = Secondary()
        springs.reset(0f, 0f, 0f)
        springs.step(1f / 60f, 0f, 10f, 0f)
        assertTrue(springs.sway > 5f)
        repeat(300) { springs.step(1f / 60f, 0f, 10f, 0f) }
        assertEquals(0f, springs.sway, 0.05f)
    }

    @Test
    fun aLongFrameDoesNotBlowUp() {
        val springs = Secondary()
        springs.reset(0f, 0f, 0f)
        springs.step(5f, 0.05f, 20f, 0.01f)
        assertTrue(springs.flop.isFinite() && abs(springs.flop) <= 0.1f)
        assertTrue(springs.sway.isFinite() && abs(springs.sway) <= 60f)
    }

    @Test
    fun jumpIsSmoothRisesToItsHeightAndLands() {
        var last = Pose().also { jump(0f, 0.05f, it) }
        var top = 0f
        for (ms in 1..1500) {
            val pose = Pose()
            jump(ms / 1000f, 0.05f, pose)
            assertTrue("salto a $ms ms: ${pose.squash - last.squash}", abs(pose.squash - last.squash) < 0.01f)
            assertTrue(abs(pose.hop - last.hop) < 0.002f)
            top = maxOf(top, pose.hop)
            last = pose
        }
        assertEquals(0.05f, top, 0.001f)
        assertEquals(0f, last.hop, 0f)
        assertEquals(0f, last.squash, 0.002f)
    }

    @Test
    fun blinkIsQuickAndComplete() {
        assertEquals(1f, blinkShape(0f), 0f)
        assertEquals(0f, blinkShape(0.08f), 0f)
        assertEquals(1f, blinkShape(0.3f), 0f)
        // The lids drop faster than they lift: shut by 65 ms, still opening at 200 ms.
        assertEquals(0f, blinkShape(0.066f), 0f)
        assertTrue(blinkShape(0.2f) < 1f)
    }

    @Test
    fun changesOfExpressionBlinkButTheFirstStateAndSurprisesDoNot() {
        val accents = Accents()
        accents.start(VoiceState.IDLE, 0f, transition = false)
        assertEquals(1f, Pose().also { accents.apply(0.08f, motion = true, it) }.eyeOpen, 0f)
        accents.start(VoiceState.THINKING, 1f, transition = true)
        assertEquals(0f, Pose().also { accents.apply(1.08f, motion = true, it) }.eyeOpen, 0f)
        accents.start(VoiceState.ACTIVATED, 2f, transition = true)
        val surprised = Pose().also { accents.apply(2.08f, motion = true, it) }
        assertEquals(1f, surprised.eyeOpen, 0f)
        assertTrue("salta al oír «Dina»", Pose().also { accents.apply(2.3f, motion = true, it) }.hop > 0.02f)
    }

    @Test
    fun aFinishedTaskThrowsConfettiThatOutlivesTheState() {
        val accents = Accents()
        accents.start(VoiceState.EXECUTING, 0f, transition = true)
        accents.start(VoiceState.SPEAKING, 0.2f, transition = true)
        val pose = Pose().also { accents.apply(0.6f, motion = true, it) }
        assertTrue(pose.burst > 0f && pose.burst < 1f)
        assertEquals(0f, Pose().also { accents.apply(3f, motion = true, it) }.burst, 0f)
        assertEquals(0f, Pose().also { accents.apply(0.6f, motion = false, it) }.burst, 0f)
    }
}
