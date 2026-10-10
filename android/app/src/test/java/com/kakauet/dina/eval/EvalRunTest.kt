package com.kakauet.dina.eval

import com.kakauet.dina.llm.NativeLlmEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * Entry point of `.\dev.ps1 eval <brain> <set> [key=value…]`. Runs only with -Pdina.eval (see
 * app/build.gradle.kts): loads the app's llama.cpp + PromptCache built for the PC and writes
 * eval-results/<set>/<brain>/report.md, summary.json and turns.jsonl.
 */
class EvalRunTest {
    @Test
    fun run() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid")) // templates format alarms in the system zone
        val args = System.getProperty("dina.eval").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val brainId = args.getOrElse(0) { "dina45" }
        val setId = args.getOrElse(1) { "v2" }
        val options = args.drop(2).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val root = File(System.getProperty("dina.root") ?: "../..")
        if (setId.startsWith("dev:")) return@runBlocking DevSet.run(root, brainId, setId.removePrefix("dev:"), options)
        val policies = options["policy"]?.split('+')?.associate { it.substringBefore(':') to it.substringAfter(':') }.orEmpty()
        var episodes = when (setId) {
            "rw200" -> Rw200.load(File(root, "benchmark/realworld200/episodes"))
            "v2" -> EpisodeJson.loadDir(File(root, "benchmark/realworld_v2/episodes"))
            // Only the conversions section of RW2 v2.1.
            "conv" -> EpisodeJson.loadDir(File(root, "benchmark/realworld_v2/episodes")).filter { it.category == EvalSummary.V21 }
            "dina-real" -> DinaReal.load(File(root, "benchmark/dina_real"))
            else -> EpisodeJson.loadDir(File(root, setId))
        }
        options["filter"]?.let { pattern -> val regex = Regex(pattern); episodes = episodes.filter { regex.containsMatchIn(it.id) || regex.containsMatchIn(it.category) } }
        options["limit"]?.toIntOrNull()?.let { episodes = episodes.take(it) }

        val factory = EvalModels.factory(brainId, root, options)
        val runName = brainId + options["tag"]?.let { "-$it" }.orEmpty()
        val previousFile = File(root, "eval-results/$setId/$runName/summary.json")
        val previous = previousFile.takeIf { it.exists() }?.let { JSONObject(it.readText(Charsets.UTF_8)) }
        val runner = EvalRunner(factory, policies, warm = options["warm"] != "0")
        var notes: Map<String, Any> = emptyMap()
        val records = factory.use { f -> episodes.map { runner.run(it) }.also { notes = f.notes() } }
        val summary = EvalSummary(brainId, setId, records, policies, notes)
        val out = File(root, "eval-results/$setId/$runName")
        summary.write(out, previous)
        File(root, "eval-results/last.txt").writeText("${summary.headline()}\n${File(out, "report.md").path}\n", Charsets.UTF_8)
    }
}

/** Model files and brain variants the evaluator knows. */
object EvalModels {
    /** The app's model; `model=` evaluates another GGUF (e.g. a new training run). */
    const val DINA45 = "models/dina-4.5/model-Q4_K_M.gguf"

    fun factory(id: String, root: File, options: Map<String, String>): BrainFactory {
        if (id == "oracle") return OracleFactory()
        val policies = options["policy"]?.split('+')?.associate { it.substringBefore(':') to it.substringAfter(':') }.orEmpty()
        if (id == "dina45-scripted") {
            // The scripted model needs no weights; LFM2.5's tokenizer (any of its GGUFs) measures the prompt budget.
            val tokens = options["tokens"] ?: DINA45
            if (tokens == "none") return ScriptedDina45Factory(policies)
            val engine = load(File(tokens).takeIf { it.isAbsolute } ?: File(root, tokens), options)
            return object : BrainFactory by ScriptedDina45Factory(policies, engine) {
                override fun close() = engine.close()
            }
        }
        require(id == "dina45") { "unknown brain $id (dina45, dina45-scripted, oracle)" }
        val modelPath = options["model"] ?: DINA45
        val engine = load(File(modelPath).takeIf { it.isAbsolute } ?: File(root, modelPath), options)
        val inner = Dina45Factory(engine, policies, grammar = options["grammar"] != "off", historyTurns = options["history"]?.toInt() ?: 1, prefill = options["prefill"] != "0")
        return object : BrainFactory by inner {
            override fun close() = engine.close()
        }
    }

    fun load(file: File, options: Map<String, String>): NativeLlmEngine {
        check(file.exists()) { "model not found: $file" }
        val engine = NativeLlmEngine()
        runBlocking { engine.load(file.absolutePath, options["threads"]?.toInt() ?: 6, options["ctx"]?.toInt() ?: 1536) }
        return engine
    }
}
