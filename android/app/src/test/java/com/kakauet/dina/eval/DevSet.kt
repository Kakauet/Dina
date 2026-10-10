package com.kakauet.dina.eval

import com.kakauet.dina.data.DataSteps
import com.kakauet.dina.data.DevResult
import org.json.JSONObject
import java.io.File

/**
 * `.\dev.ps1 eval dina45 dev:<batch>[+<batch2>] [model=<gguf>] [history=0] [limit=N] [tag=…]`: the pipeline's
 * development set (other generator family, reserved personas) read by a GGUF through the app's Dina 4.5
 * brain and llama.cpp, scored by outcome on the engine like the round trip ([DataSteps.dev]). Writes
 * eval-results/dev-<batch>/<brain>[-tag]/summary.json, report.md and turns.jsonl. The checkpoint is
 * chosen with this set, never with RW2 or Dina-Real.
 */
object DevSet {
    fun run(root: File, brainId: String, batches: String, options: Map<String, String>) {
        check(brainId == "dina45") { "the dev set is for the dina45 brain" }
        val modelPath = options["model"] ?: EvalModels.DINA45

        val engine = EvalModels.load(File(modelPath).takeIf { it.isAbsolute } ?: File(root, modelPath), options)
        val history = options["history"]?.toInt() ?: 1
        val limit = options["limit"]?.toIntOrNull() ?: Int.MAX_VALUE
        val result = engine.use { e ->
            DevResult(batches.split('+').flatMap { DataSteps.dev(File(root, "data/batches/$it"), e, history, limit).turns })
        }
        val runName = brainId + options["tag"]?.let { "-$it" }.orEmpty()
        val out = File(root, "eval-results/dev-${batches.replace('+', '-')}/$runName").apply { mkdirs() }
        val summary = summary(result, modelPath, history)
        File(out, "summary.json").writeText(summary.toString(1), Charsets.UTF_8)
        File(out, "turns.jsonl").writeText(result.turns.joinToString("") { t ->
            JSONObject().put("conversation", t.conversation).put("turn", t.turn).put("phenomenon", t.phenomenon).put("user", t.user)
                .put("expected", t.expected).put("output", t.output).put("answer", t.answer).put("ok", t.ok).put("why", t.why).toString() + "\n"
        }, Charsets.UTF_8)
        File(out, "report.md").writeText(report(result, summary, batches), Charsets.UTF_8)
        val headline = "dev-$batches $runName: conversaciones ${Rate(result.conversationsOk, result.conversations.size).withCi()}, turnos ${Rate(result.turnsOk, result.turns.size).withCi()}"
        File(root, "eval-results/last.txt").writeText("$headline\n${File(out, "report.md").path}\n", Charsets.UTF_8)
    }

    private fun pct(a: Int, b: Int) = if (b == 0) 0.0 else Math.round(1000.0 * a / b) / 10.0

    fun summary(result: DevResult, model: String, history: Int): JSONObject {
        val byPhenomenon = JSONObject()
        result.conversations.values.groupBy { it.first().phenomenon }.toSortedMap().forEach { (name, convs) ->
            val hits = convs.count { c -> c.all { it.ok } }
            byPhenomenon.put(name, JSONObject().put("n", convs.size).put("episodes", pct(hits, convs.size)).put("episode_rate", Rate(hits, convs.size).json()))
        }
        return JSONObject().put("model", model).put("history", history)
            .put("conversations", result.conversations.size).put("episodes", pct(result.conversationsOk, result.conversations.size))
            .put("turns_n", result.turns.size).put("turns", pct(result.turnsOk, result.turns.size))
            .put("episode_rate", Rate(result.conversationsOk, result.conversations.size).json())
            .put("turn_rate", Rate(result.turnsOk, result.turns.size).json())
            .put("by_phenomenon", byPhenomenon)
    }

    private fun report(result: DevResult, summary: JSONObject, batches: String): String = buildString {
        appendLine("# Desarrollo $batches · ${summary.getString("model")}")
        appendLine()
        appendLine("Conversaciones correctas: **${Rate(result.conversationsOk, result.conversations.size).withCi()}** (${result.conversationsOk}/${result.conversations.size}); turnos: ${Rate(result.turnsOk, result.turns.size).withCi()} (${result.turnsOk}/${result.turns.size}); IC 95 %; historial ${summary.getInt("history")}.")
        appendLine()
        appendLine("| Fenómeno | Conversaciones | Correctas (IC 95 %) |")
        appendLine("|---|---:|---:|")
        val phen = summary.getJSONObject("by_phenomenon")
        phen.keys().asSequence().sorted().forEach {
            val row = phen.getJSONObject(it)
            val counts = row.getJSONObject("episode_rate")
            appendLine("| $it | ${row.getInt("n")} | ${Rate(counts.getInt("hits"), counts.getInt("n")).withCi()} |")
        }
        appendLine()
        appendLine("## Charla (lo que dijo Dina)")
        result.turns.filter { it.output.startsWith("say(") || it.output.startsWith("fun.") }.take(12).forEach { appendLine("- «${it.user}» → ${it.answer}") }
        appendLine()
        appendLine("## Fallos (primeros 40)")
        result.turns.filterNot { it.ok }.take(40).forEach { appendLine("- [${it.phenomenon}] «${it.user}»: `${it.output.replace("\n", " ; ")}` (esperado `${it.expected.replace("\n", " ; ")}`): ${it.why}") }
    }
}
