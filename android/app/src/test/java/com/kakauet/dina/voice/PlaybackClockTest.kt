package com.kakauet.dina.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackClockTest {
    @Test
    fun framesHeardGrowWithTheTimeSinceTheTimestamp() {
        // Frame 44_100 was presented at t = 1 s; at t = 1.5 s, 22_050 more frames have been heard.
        assertEquals(66_150L, PlaybackClock.heardFrames(44_100, 1_000_000_000L, 1_500_000_000L, 44_100))
        // A timestamp from the future (clock jitter) does not rewind.
        assertEquals(44_100L, PlaybackClock.heardFrames(44_100, 2_000_000_000L, 1_500_000_000L, 44_100))
    }

    @Test
    fun remainingIsWhatIsWrittenAndNotYetHeard() {
        assertEquals(500.0, PlaybackClock.remainingMs(written = 44_100, heard = 22_050, sampleRate = 44_100), 1e-9)
        assertEquals(0.0, PlaybackClock.remainingMs(written = 44_100, heard = 50_000, sampleRate = 44_100), 0.0)
    }

    @Test
    fun theHeadPositionIsAnUnsignedCounter() {
        assertEquals(5L, PlaybackClock.unwrap(5))
        assertEquals(4_294_967_295L, PlaybackClock.unwrap(-1))
        assertEquals(1_000.0, PlaybackClock.mixerRemainingMs(written = 4_294_967_296L + 44_100, headPosition = -1, sampleRate = 44_100), 0.1)
    }
}
