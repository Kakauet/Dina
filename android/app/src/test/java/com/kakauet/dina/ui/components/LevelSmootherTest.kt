package com.kakauet.dina.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelSmootherTest {
    private fun LevelSmoother.run(target: Float, seconds: Float, fps: Int = 60): Float {
        repeat((seconds * fps).toInt()) { next(target, 1f / fps) }
        return value
    }

    @Test
    fun risesFastAndFallsSlowly() {
        val rise = LevelSmoother().run(1f, 0.1f)
        val fall = LevelSmoother().apply { reset(1f) }.run(0f, 0.1f)
        assertTrue("sube a $rise en 100 ms", rise > 0.85f)
        assertTrue("baja a $fall en 100 ms", fall > 0.55f)
    }

    @Test
    fun doesNotDependOnTheFrameRate() {
        val at60 = LevelSmoother().run(0.8f, 0.2f, fps = 60)
        val at120 = LevelSmoother().run(0.8f, 0.2f, fps = 120)
        assertEquals(at60, at120, 0.01f)
    }

    @Test
    fun staysInRangeAndIgnoresLongPauses() {
        val smoother = LevelSmoother()
        smoother.next(3f, 0.016f)
        assertTrue(smoother.value <= 1f)
        // A frame after the app was in the background must not jump straight to the target.
        smoother.reset(0f)
        smoother.next(1f, 5f)
        assertTrue(smoother.value < 1f)
        smoother.next(-2f, 0.016f)
        assertTrue(smoother.value >= 0f)
    }
}
