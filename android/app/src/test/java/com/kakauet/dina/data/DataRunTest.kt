package com.kakauet.dina.data

import org.junit.Test
import java.io.File

/** Entry point of `.\dev.ps1 data <step> batch=<name> [k=v]`; normal test runs skip it. */
class DataRunTest {
    @Test
    fun run() {
        val spec = System.getProperty("dina.data") ?: return
        val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val options = parts.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val root = File(System.getProperty("dina.root") ?: "../..")
        val dir = File(root, "data/batches/" + (options["batch"] ?: error("falta batch=<nombre>")))
        val summary = when (val step = parts.first()) {
            "scenarios" -> DataSteps.scenarios(dir, options["n"]?.toInt() ?: 200, options["seed"]?.toLong() ?: 1L, options["dev"]?.toDouble() ?: 0.1, options["mix"] ?: ScenarioGenerator.BASE)
            "verify" -> DataSteps.verify(dir)
            "render" -> DataSteps.render(dir, options["history"]?.toInt() ?: 1)
            else -> error("paso desconocido: $step (scenarios, verify, render)")
        }
        File(root, "data/batches/last.txt").apply { parentFile?.mkdirs() }.writeText(summary + "\n", Charsets.UTF_8)
    }
}
