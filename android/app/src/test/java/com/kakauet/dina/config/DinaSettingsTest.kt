package com.kakauet.dina.config

import android.app.Application
import android.content.Context
import com.kakauet.dina.voice.OrtProvider
import com.kakauet.dina.voice.WakeSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DinaSettingsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("dina_settings", Context.MODE_PRIVATE)

    @Test
    fun choicesSurviveARestartAndOutOfRangeValuesAreClamped() {
        DinaSettings(context).apply {
            wakeSensitivity = WakeSensitivity.HIGH
            backgroundListening = true
            respondByVoice = false
            voiceId = "piper"
            voiceProvider = OrtProvider.entries.last()
            followUpTimeoutMs = 60_000L
            voiceVolume = 2f
            llmThreads = 1
        }
        val restarted = DinaSettings(context)
        assertEquals(WakeSensitivity.HIGH, restarted.wakeSensitivity)
        assertTrue(restarted.backgroundListening)
        assertFalse(restarted.respondByVoice)
        assertEquals("piper", restarted.voiceId)
        assertEquals(OrtProvider.entries.last(), restarted.voiceProvider)
        assertEquals(12_000L, restarted.followUpTimeoutMs)
        assertEquals(1f, restarted.voiceVolume, 0f)
        assertEquals(2, restarted.llmThreads)
    }

    @Test
    fun unknownStoredValuesFallBackToTheDefaults() {
        prefs.edit().putString("wake_sensitivity", "MAXIMA").putString("voice_provider", "TPU").putInt("llm_threads", 64).commit()
        val settings = DinaSettings(context)
        assertEquals(WakeSensitivity.NORMAL, settings.wakeSensitivity)
        assertEquals(OrtProvider.CPU, settings.voiceProvider)
        assertEquals(8, settings.llmThreads)
    }

    @Test
    fun startingDropsOnlyTheOldDetectorThresholds() {
        prefs.edit().putFloat("wake_threshold", 0.5f).putBoolean("wake_v3_candidate_enabled", true)
            .putString("wake_sensitivity", WakeSensitivity.LOW.name).putBoolean("dina_active", false).commit()
        val settings = DinaSettings(context)
        assertFalse(prefs.contains("wake_threshold"))
        assertFalse(prefs.contains("wake_v3_candidate_enabled"))
        assertEquals(WakeSensitivity.LOW, settings.wakeSensitivity)
        assertFalse(settings.dinaActive)
    }
}
