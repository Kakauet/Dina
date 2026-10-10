package com.kakauet.dina.voice

import android.content.Context
import com.kakauet.dina.config.DinaSettings
import com.kakauet.dina.core.DinaStore
import com.kakauet.dina.core.WakeDiagnosticEvent
import org.json.JSONObject
import java.io.File

/**
 * Optional log of the detector (Diagnóstico › Palabra «Dina»): scores and decisions, never audio.
 * One event per activation and one per near miss (a sound that scored at least [NEAR_MISS] without activating),
 * so the owner can mark which ones really were "Dina".
 */
class WakeDiagnostics(context: Context, private val settings: DinaSettings, private val store: DinaStore) {
    private val file = File(context.filesDir, "wakeword-diagnostics/events.jsonl")
    private var peak = 0f

    /** Every detector step while waiting for "Dina". */
    fun observe(score: Float, detected: Boolean) {
        when {
            !settings.wakeDiagnosticEnabled -> peak = 0f
            detected -> { record(score, true); peak = 0f }
            score >= NEAR_MISS -> peak = maxOf(peak, score)
            peak > 0f -> { record(peak, false); peak = 0f }
        }
    }

    private fun record(score: Float, detected: Boolean) {
        val now = System.currentTimeMillis()
        val event = WakeDiagnosticEvent(now, now, score, detected, settings.wakeSensitivity.threshold)
        store.setWakeDiagnostic(event)
        append(event, null)
    }

    /** The user says whether the last event really was "Dina". */
    fun label(groundTruth: String) {
        val event = store.wakeDiagnostic.value ?: return
        if (groundTruth in setOf("positive", "negative")) append(event, groundTruth)
    }

    @Synchronized
    private fun append(event: WakeDiagnosticEvent, label: String?) {
        if (!settings.wakeDiagnosticEnabled) return
        val payload = JSONObject()
            .put("event_id", event.id)
            .put("timestamp_ms", event.timestampMs)
            .put("confidence", event.confidence.toDouble())
            .put("detected", event.detected)
            .put("threshold", event.threshold.toDouble())
        if (label != null) payload.put("ground_truth", label)
        file.parentFile?.mkdirs()
        // Bounded: past MAX_BYTES only the newer half of the log is kept.
        if (file.length() > MAX_BYTES) file.writeText(file.readLines().let { it.drop(it.size / 2) }.joinToString("\n", postfix = "\n"))
        file.appendText(payload.toString() + "\n")
    }

    private companion object {
        const val NEAR_MISS = 0.5f
        const val MAX_BYTES = 1_000_000L
    }
}
