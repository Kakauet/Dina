package com.kakauet.dina.voice

/**
 * Ajustes › Escucha. Thresholds from `scripts/wakeword/reports/calibration.json`: at most 0.2 (Baja), 0.7 (Normal)
 * and 2 (Alta) false activations per hour of nonstop conversation on held-out voices.
 */
enum class WakeSensitivity(val label: String, val threshold: Float) {
    LOW("Baja", 0.999f),
    NORMAL("Normal", 0.998f),
    HIGH("Alta", 0.95f),
}

/** "Dina" is heard when two consecutive 80 ms steps reach the threshold; then [REFRACTORY_STEPS] of pause. */
class WakeDecision {
    private var streak = 0
    private var pause = 0

    fun reset() {
        streak = 0
        pause = 0
    }

    fun accept(score: Float, threshold: Float): Boolean {
        if (pause > 0) pause--
        if (pause > 0 || !(score >= threshold)) {
            streak = 0
            return false
        }
        if (++streak < HITS) return false
        streak = 0
        pause = REFRACTORY_STEPS
        return true
    }

    companion object {
        const val HITS = 2
        const val REFRACTORY_STEPS = 25
    }
}
