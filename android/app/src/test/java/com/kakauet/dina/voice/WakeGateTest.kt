package com.kakauet.dina.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WakeGateTest {
    private val random = Random(7)

    private fun chunk(rms: Int) = ShortArray(WakeWordDetector.CHUNK_SAMPLES) { (random.nextDouble(-1.0, 1.0) * rms * 1.732).toInt().toShort() }

    @Test
    fun sleepsAfterTwoSecondsOfSilenceAndWakesWithAQuietWhisper() {
        val gate = WakeGate()
        assertEquals(WakeGate.Step.SLEEP, gate.accept(chunk(15)))
        repeat(40) { gate.accept(chunk(15)) }
        // Floor ~15: the level is 45, a whisper at 1 m peaks at RMS ~300.
        assertEquals(WakeGate.Step.WAKE, gate.accept(chunk(120)))
        assertEquals(WakeGate.Step.RUN, gate.accept(chunk(15)))
        repeat(WakeGate.HOLD_CHUNKS - 2) { assertEquals(WakeGate.Step.RUN, gate.accept(chunk(15))) }
        assertEquals(WakeGate.Step.SLEEP, gate.accept(chunk(15)))
    }

    @Test
    fun replayKeepsTheLastChunksOldestFirst() {
        val gate = WakeGate()
        val chunks = List(40) { index -> ShortArray(WakeWordDetector.CHUNK_SAMPLES) { index.toShort() } }
        chunks.forEach { gate.accept(it) }
        val replayed = mutableListOf<Int>()
        gate.replay { replayed += it[0].toInt() }
        assertEquals((40 - WakeGate.REPLAY_CHUNKS until 40).toList(), replayed)
    }

    @Test
    fun aNoisyRoomKeepsTheDetectorRunning() {
        val gate = WakeGate()
        // Constant noise raises the floor, but the level stops at MAX_LEVEL: a fan at RMS 200 never puts it to sleep.
        val steps = List(600) { gate.accept(chunk(200)) }
        assertTrue(steps.drop(1).all { it == WakeGate.Step.RUN })
    }

    @Test
    fun resetForgetsHistoryAndSleeps() {
        val gate = WakeGate()
        repeat(5) { gate.accept(chunk(500)) }
        gate.reset()
        var replayed = 0
        gate.replay { replayed++ }
        assertEquals(0, replayed)
        assertEquals(WakeGate.Step.SLEEP, gate.accept(chunk(10)))
    }
}
