package com.kakauet.dina.config

import com.kakauet.dina.BuildConfig
import com.kakauet.dina.voice.SupertonicVoice
import java.io.File

/**
 * Which edition of the app this is (Gradle product flavors `full` and `lite`, one code base) and the defaults
 * that depend on it. Full: Dina 4.5 1.2B, tuned for a flagship phone (Galaxy S24 Ultra). Lite: Dina 4.5 350M,
 * for modest phones, with thread counts that follow the phone's fast cores instead of assuming a flagship's.
 *
 * Lite's rule comes from the PC (Dina 4.5 350M and the voice at 4 steps, `.\dev.ps1 latency llm_threads= voice_threads=`):
 * 1 → 2 threads halves the time, 3 → 4 still saves ~15 %, more than 4 saves nothing. So 2 to 4 threads, and as many
 * as the phone has fast cores; slow cores are left out because a thread on one makes the whole step wait.
 * What the PC cannot tell is a phone's topology: that part is a heuristic to check on a modest phone.
 */
object AppVariant {
    val isLite: Boolean = BuildConfig.LITE

    val label: String get() = label(isLite)

    fun label(lite: Boolean) = if (lite) "Dina Lite" else "Dina"

    /** Supertonic steps by default: 8 for full (the 2.3's, chosen by ear), 4 for lite (a modest phone: half the voice time). */
    val defaultSteps: Int get() = defaultSteps(isLite)

    fun defaultSteps(lite: Boolean): Int = if (lite) 4 else SupertonicVoice.DEFAULT_STEPS

    /** A number of Supertonic denoising steps the user can pick in Ajustes › Voz. */
    data class StepChoice(val steps: Int, val label: String)

    /** Full: 4 or 8 steps (faster or more polished). Lite speaks with 4 only, so it has nothing to pick. */
    fun stepChoices(lite: Boolean): List<StepChoice> =
        if (lite) listOf(StepChoice(4, "4 pasos")) else listOf(StepChoice(4, "Rápida · 4 pasos"), StepChoice(8, "Natural · 8 pasos"))

    val stepChoices: List<StepChoice> get() = stepChoices(isLite)

    /** [stored] if it is one of this edition's choices, else the nearest one (the faster on a tie). */
    fun allowedSteps(stored: Int, lite: Boolean): Int = stepChoices(lite).map { it.steps }.minByOrNull { kotlin.math.abs(it - stored) }!!

    /** llama.cpp threads: the flagship's 6 (X4 + A720 cores) for full; the fast cores, 2 to 4, for lite. */
    fun defaultLlmThreads(fastCores: Int, lite: Boolean = isLite): Int = if (lite) fastCores.coerceIn(2, 4) else 6

    /** ONNX Runtime threads of the voice: 4 for full; the fast cores, 2 to 4, for lite. */
    fun defaultVoiceThreads(fastCores: Int, lite: Boolean = isLite): Int = if (lite) fastCores.coerceIn(2, 4) else 4

    /**
     * Fast cores out of the kernel's per-core capacities (`cpu_capacity`: 1024 for the biggest core): those with at
     * least half of the biggest one's. A flagship gives 6 (X4 + A720s, not the A520s); a phone with 2 big and 6 little
     * cores gives 2. With no capacities, half of [cores].
     */
    fun fastCores(capacities: List<Int>, cores: Int): Int {
        val biggest = capacities.maxOrNull() ?: return (cores / 2).coerceAtLeast(1)
        return capacities.count { it * 2 >= biggest }.coerceAtLeast(1)
    }

    val cores: Int get() = Runtime.getRuntime().availableProcessors()

    /** This phone's fast cores (read once). */
    val fastCores: Int by lazy {
        val capacities = (0 until cores).mapNotNull { index ->
            runCatching { File("/sys/devices/system/cpu/cpu$index/cpu_capacity").readText().trim().toInt() }.getOrNull()
        }
        fastCores(capacities.takeIf { it.size == cores } ?: emptyList(), cores)
    }
}
