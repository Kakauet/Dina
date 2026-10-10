package com.kakauet.dina.voice

import android.os.Debug
import java.io.File

/** Best-effort device measurements for the diagnostics screen. */
object DeviceStats {
    fun memoryMb(): Double {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024.0
    }

    /** SoC temperature when the kernel exposes it; null otherwise. */
    fun temperatureC(): Double? {
        val zones = File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") } ?: return null
        return zones.asSequence().mapNotNull { zone ->
            runCatching {
                val type = File(zone, "type").readText().trim().lowercase()
                val raw = File(zone, "temp").readText().trim().toDouble()
                if (type.contains("soc") || type.contains("cpu") || type.contains("ap")) if (raw > 200) raw / 1000 else raw else null
            }.getOrNull()
        }.firstOrNull()
    }

    /** Average CPU use of this process since [cpuStartMs]/[wallStartNs], in percent of one core. */
    fun cpuPercent(cpuStartMs: Long, wallStartNs: Long): Double =
        ((android.os.Process.getElapsedCpuTime() - cpuStartMs) * 1_000_000.0 / (System.nanoTime() - wallStartNs).coerceAtLeast(1L) * 100.0).coerceIn(0.0, 800.0)
}
