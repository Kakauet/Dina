package com.kakauet.dina.voice

import com.kakauet.dina.voice.SupertonicGuidance.Companion.STYLE_SIZE
import com.kakauet.dina.voice.SupertonicGuidance.Companion.TEXT_CHANNELS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class SupertonicGuidanceTest {
    private val guidance = SupertonicGuidance(
        textToken = FloatArray(TEXT_CHANNELS) { it.toFloat() },
        keyCond = FloatArray(STYLE_SIZE) { 1f },
        keyUncond = FloatArray(STYLE_SIZE) { 2f },
        valueUncond = FloatArray(STYLE_SIZE) { 3f },
    )

    @Test
    fun aGuidedStepFeedsTheConditionedRowThenTheUnconditionedOne() {
        val length = 5
        val textEmb = FloatArray(TEXT_CHANNELS * length) { -1f }
        val style = FloatArray(STYLE_SIZE) { 9f }
        val rows = guidance.rows(textEmb, style, guided = true)
        assertEquals(2, rows.batch)
        // text: row 0 is the encoder's output, row 1 repeats each channel's token over the positions
        assertEquals(2 * TEXT_CHANNELS * length, rows.textEmb.size)
        assertTrue(rows.textEmb.take(TEXT_CHANNELS * length).all { it == -1f })
        for (channel in listOf(0, 7, TEXT_CHANNELS - 1)) {
            val start = TEXT_CHANNELS * length + channel * length
            assertTrue(rows.textEmb.slice(start until start + length).all { it == channel.toFloat() })
        }
        assertEquals(9f, rows.styleValue[0], 0f)
        assertEquals(3f, rows.styleValue[STYLE_SIZE], 0f)
        assertEquals(1f, rows.styleKey[0], 0f)
        assertEquals(2f, rows.styleKey[STYLE_SIZE], 0f)
        assertEquals(2 * STYLE_SIZE, rows.styleValue.size)
        assertEquals(2 * STYLE_SIZE, rows.styleKey.size)
    }

    @Test
    fun aConditionedOnlyStepFeedsOneRowWithoutCopying() {
        val textEmb = FloatArray(TEXT_CHANNELS * 3)
        val style = FloatArray(STYLE_SIZE)
        val rows = guidance.rows(textEmb, style, guided = false)
        assertEquals(1, rows.batch)
        assertTrue(rows.textEmb === textEmb)
        assertTrue(rows.styleValue === style)
        assertEquals(1f, rows.styleKey[0], 0f)
    }

    // 2 channels x 3 frames, the last frame is padding.
    private fun step(latent: FloatArray, velocity: FloatArray, guided: Boolean, total: Int = 4) =
        guidance.advance(latent, velocity, guided, frames = 3, maskedFrames = 2, totalSteps = total)

    @Test
    fun guidedStepCombinesFourCondMinusThreeUncond() {
        val latent = floatArrayOf(1f, 2f, 0f, 3f, 4f, 0f)
        val velocity = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, /* uncond */ 0f, 0f, 0f, 2f, 2f, 2f)
        step(latent, velocity, guided = true)
        // v = 4*cond - 3*uncond: 4 for the first channel, -2 for the second; x + v / 4; padding stays 0
        assertArrayEquals(floatArrayOf(2f, 3f, 0f, 2.5f, 3.5f, 0f), latent, 1e-6f)
    }

    @Test
    fun conditionedOnlyStepUsesTheRowAlone() {
        val latent = floatArrayOf(1f, 2f, 0f, 3f, 4f, 0f)
        step(latent, floatArrayOf(4f, 4f, 4f, 8f, 8f, 8f), guided = false)
        assertArrayEquals(floatArrayOf(2f, 3f, 0f, 5f, 6f, 0f), latent, 1e-6f)
    }

    @Test
    fun theStepSizeIsOneOverTheTotalNumberOfSteps() {
        val latent = FloatArray(6)
        step(latent, FloatArray(6) { 3f }, guided = false, total = 8)
        assertEquals(0.375f, latent[0], 1e-6f)
    }

    @Test
    fun theFirstStepsAreTheGuidedOnes() {
        assertTrue(SupertonicGuidance.isGuided(0, 2))
        assertTrue(SupertonicGuidance.isGuided(1, 2))
        assertFalse(SupertonicGuidance.isGuided(2, 2))
        assertFalse(SupertonicGuidance.isGuided(0, 0))
        assertTrue(SupertonicGuidance.isGuided(7, SupertonicVoice.ALL_GUIDED))
    }

    @Test
    fun theGuidanceFileIsReadInTheOrderThePreparationScriptWritesIt() {
        val floats = FloatArray(TEXT_CHANNELS + 3 * STYLE_SIZE) { index ->
            when {
                index < TEXT_CHANNELS -> 10f
                index < TEXT_CHANNELS + STYLE_SIZE -> 1f
                index < TEXT_CHANNELS + 2 * STYLE_SIZE -> 2f
                else -> 3f
            }
        }
        val file = Files.createTempFile("guidance", ".bin").toFile()
        try {
            file.writeBytes(ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(floats) }.array())
            val rows = SupertonicGuidance.load(file).rows(FloatArray(TEXT_CHANNELS), FloatArray(STYLE_SIZE), guided = true)
            assertEquals(10f, rows.textEmb[TEXT_CHANNELS], 0f)
            assertEquals(1f, rows.styleKey[0], 0f)
            assertEquals(2f, rows.styleKey[STYLE_SIZE], 0f)
            assertEquals(3f, rows.styleValue[STYLE_SIZE], 0f)
            file.writeBytes(ByteArray(40))
            val failed = runCatching { SupertonicGuidance.load(file) }.isFailure
            assertTrue(failed)
        } finally {
            file.delete()
        }
    }

    @Test
    fun wrongSizedTokensAreRejected() {
        val failed = runCatching { SupertonicGuidance(FloatArray(3), FloatArray(1), FloatArray(1), FloatArray(1)) }.isFailure
        assertTrue(failed)
    }
}
