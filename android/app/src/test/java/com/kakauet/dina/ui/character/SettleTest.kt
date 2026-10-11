package com.kakauet.dina.ui.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettleTest {
    @Test
    fun startsAtZeroOvershootsALittleAndLands() {
        assertEquals(0f, settle(0f), 0f)
        assertEquals(0f, settle(-1f), 0f)
        assertEquals(1f, settle(2f), 0f)
        val curve = (0..125).map { settle(it / 100f) }
        val peak = curve.max()
        assertTrue("sobrepasa $peak", peak > 1f && peak < 1.05f)
        assertEquals(1f, settle(1f), 0.02f)
        // No visible jump where the curve hands over to the constant 1.
        assertEquals(1f, settle(1.249f), 0.005f)
    }
}
