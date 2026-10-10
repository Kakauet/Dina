package com.kakauet.dina.eval

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/** Proportion with a 95 % Wilson interval. */
data class Rate(val hits: Int, val n: Int) {
    val value get() = if (n == 0) 0.0 else hits.toDouble() / n
    val interval: Pair<Double, Double> get() {
        if (n == 0) return 0.0 to 0.0
        val z = 1.96
        val p = value
        val denominator = 1 + z * z / n
        val center = (p + z * z / (2 * n)) / denominator
        val half = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denominator
        return (center - half).coerceAtLeast(0.0) to (center + half).coerceAtMost(1.0)
    }
    fun pct() = if (n == 0) "—" else "%.1f %%".format(Locale.ROOT, 100 * value).replace('.', ',')
    fun withCi() = if (n == 0) "—" else "${pct()} [${"%.0f".format(Locale.ROOT, 100 * interval.first)}–${"%.0f".format(Locale.ROOT, 100 * interval.second)}]"
    fun json(): JSONObject = JSONObject().put("hits", hits).put("n", n).put("rate", value).put("ci95", JSONArray(listOf(interval.first, interval.second)))
}

class EvalSummary(
    val brain: String,
    val set: String,
    val records: List<EpisodeRecord>,
    val policies: Map<String, String>,
    /** Brain-specific measurements (e.g. fixed prompt tokens), added to summary.json and the report. */
    val notes: Map<String, Any> = emptyMap(),
) {
    private val turns = records.flatMap { it.turns }
    val episodes = Rate(records.count { it.pass }, records.size)
    /** RW2 v2.0, the 432 episodes every earlier model was measured on: everything but the v2.1 section. Null without that section. */
    val core: Rate? = records.filter { it.episode.category != V21 }.takeIf { set == "v2" && it.size in 1 until records.size }?.let { rs -> Rate(rs.count { it.pass }, rs.size) }
    val actions = Rate(records.count { it.actionPass }, records.size)
    val turnRate = Rate(turns.count { it.verdict.pass }, turns.size)

    fun by(key: (Episode) -> String): Map<String, Pair<Rate, Rate>> = records.groupBy { key(it.episode) }.toSortedMap()
        .mapValues { (_, rs) -> Rate(rs.count { it.pass }, rs.size) to Rate(rs.count { it.actionPass }, rs.size) }

    val falseActions get() = Rate(turns.count { it.expectedKind != Kind.ACT && it.expectedKind != Kind.FAIL && "estado" in it.verdict.reasons && it.mutated }, turns.count { it.expectedKind != Kind.ACT && it.expectedKind != Kind.FAIL })
    val askRecall get() = Rate(turns.count { it.expectedKind == Kind.ASK && it.verdict.actionOk }, turns.count { it.expectedKind == Kind.ASK })
    val needlessAsks get() = Rate(turns.count { it.expectedKind == Kind.ACT && it.askedByText }, turns.count { it.expectedKind == Kind.ACT })
    val falseClaims get() = Rate(turns.count { "afirma algo que no hizo" in it.verdict.reasons }, turns.size)

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    fun write(dir: File, previous: JSONObject?) {
        dir.mkdirs()
        File(dir, "turns.jsonl").printWriter(Charsets.UTF_8).use { out ->
            turns.forEach { t ->
                out.println(JSONObject()
                    .put("episode", t.episode).put("turn", t.turn).put("user", t.user).put("answer", t.answer)
                    .put("executions", JSONArray(t.executions)).put("expected", t.expectedKind.code).put("option", t.verdict.option)
                    .put("pass", t.verdict.pass).put("action_ok", t.verdict.actionOk).put("reasons", JSONArray(t.verdict.reasons))
                    .put("diff", JSONArray(t.verdict.diff)).put("model", t.modelOutput).put("wall_ms", t.wallMs)
                    .put("prompt_tokens", t.promptTokens).put("reused_tokens", t.reusedTokens).put("completion_tokens", t.completionTokens).toString())
            }
        }
        val summary = JSONObject()
            .put("brain", brain).put("set", set).put("policies", JSONObject(policies))
            .put("episodes", episodes.json()).put("actions", actions.json()).put("turns", turnRate.json())
        core?.let { summary.put("core", it.json()) }
        summary
            .put("false_actions", falseActions.json()).put("ask_recall", askRecall.json()).put("needless_asks", needlessAsks.json())
            .put("false_claims", falseClaims.json())
            .put("by_category", JSONObject(by { it.category }.mapValues { it.value.first.json().put("actions", it.value.second.json()) }))
            .put("wall_ms_p50", percentile(turns.map { it.wallMs }, 0.5)).put("wall_ms_p95", percentile(turns.map { it.wallMs }, 0.95))
        val prompts = turns.filter { it.promptTokens > 0 }.map { it.promptTokens.toDouble() }
        if (prompts.isNotEmpty()) summary.put("prompt_tokens_p50", percentile(prompts, 0.5)).put("prompt_tokens_p95", percentile(prompts, 0.95)).put("prompt_tokens_max", prompts.max())
        notes.forEach { (key, value) -> summary.put(key, value) }
        File(dir, "summary.json").writeText(summary.toString(2), Charsets.UTF_8)
        File(dir, "report.md").writeText(markdown(previous), Charsets.UTF_8)
    }

    companion object {
        /** The category added in RW2 v2.1 (Dina 4.5): conversions and the time of other places. */
        const val V21 = "conversiones"
    }

    fun headline(): String = (core?.let { "v2.0 ${it.withCi()} · v2.1 " } ?: "") + "${episodes.withCi()} episodios · ${actions.pct()} sin contar la respuesta · ${records.size} episodios, ${turns.size} turnos"

    fun markdown(previous: JSONObject?): String = buildString {
        // Earlier runs without the v2.1 section only have "episodes": that is their v2.0 figure.
        val prevRate = previous?.optJSONObject("episodes")?.optDouble("rate")
        val prevCore = (previous?.optJSONObject("core") ?: previous?.takeIf { it.optJSONObject("by_category")?.has(V21) == false }?.optJSONObject("episodes"))?.optDouble("rate")
        fun before(rate: Double?) = rate?.let { " (antes ${"%.1f".format(Locale.ROOT, 100 * it).replace('.', ',')} %)" } ?: ""
        appendLine("# Evaluación: $brain en $set")
        appendLine()
        appendLine("Puntuación justa (estado, decisión y hechos normalizados)" +
            (if (policies.isEmpty()) "; políticas sin decidir: cualquiera vale." else "; políticas: ${policies.entries.joinToString { "${it.key}=${it.value}" }}."))
        appendLine()
        appendLine("| Métrica | Valor |")
        appendLine("|---|---:|")
        core?.let { appendLine("| **RW2 v2.0** (sin conversiones, comparable con los modelos anteriores) | **${it.withCi()}**${before(prevCore)} |") }
        appendLine("| **Éxito por episodio**${if (core != null) " (RW2 v2.1, todo)" else ""} (IC 95 %) | **${episodes.withCi()}**" +
            before(prevRate.takeIf { core == null || previous?.has("core") == true }) + " |")
        appendLine("| Éxito sin contar la respuesta (estado y decisión) | ${actions.withCi()} |")
        appendLine("| Turnos correctos | ${turnRate.pct()} (${turnRate.hits}/${turnRate.n}) |")
        appendLine("| Acciones falsas (cambia algo cuando no tocaba) | ${falseActions.pct()} (${falseActions.hits}/${falseActions.n}) |")
        appendLine("| Pregunta cuando hay que preguntar | ${askRecall.pct()} (${askRecall.hits}/${askRecall.n}) |")
        appendLine("| Preguntas innecesarias (debía actuar) | ${needlessAsks.pct()} (${needlessAsks.hits}/${needlessAsks.n}) |")
        appendLine("| Dice que hizo algo que no hizo | ${falseClaims.pct()} (${falseClaims.hits}/${falseClaims.n}) |")
        val wall = turns.map { it.wallMs }
        appendLine("| Tiempo por turno en el PC, p50 / p95 | ${"%.0f".format(Locale.ROOT, percentile(wall, 0.5))} / ${"%.0f".format(Locale.ROOT, percentile(wall, 0.95))} ms |")
        if (turns.isNotEmpty()) {
            appendLine("| Tokens generados por turno | ${"%.1f".format(Locale.ROOT, turns.map { it.completionTokens }.average())} |")
            val prompts = turns.filter { it.promptTokens > 0 }.map { it.promptTokens.toDouble() }
            if (prompts.isNotEmpty()) appendLine("| Tokens de prompt por turno, p50 / p95 / máx. | ${"%.0f".format(Locale.ROOT, percentile(prompts, 0.5))} / ${"%.0f".format(Locale.ROOT, percentile(prompts, 0.95))} / ${"%.0f".format(Locale.ROOT, prompts.max())} |")
            notes["fixed_prompt_tokens"]?.let { appendLine("| Tokens fijos del prompt (sistema y plantilla, en caché) | $it |") }
        }
        appendLine()
        appendLine("## Por categoría")
        appendLine()
        appendLine("| Categoría | n | Éxito (IC 95 %) | Sin respuesta |")
        appendLine("|---|---:|---:|---:|")
        by { it.category }.forEach { (cat, rates) -> appendLine("| $cat | ${rates.first.n} | ${rates.first.withCi()} | ${rates.second.pct()} |") }
        val varieties = by { if (it.variety == "es-ES") "España" else "Latinoamérica" }
        val noisy = by { if ("stt" in it.tags) "con ruido de STT" else "sin ruido" }
        if (varieties.size > 1 || noisy.size > 1) {
            appendLine()
            appendLine("| Corte | n | Éxito | Sin respuesta |")
            appendLine("|---|---:|---:|---:|")
            (varieties + noisy).forEach { (k, r) -> appendLine("| $k | ${r.first.n} | ${r.first.withCi()} | ${r.second.pct()} |") }
        }
        appendLine()
        appendLine("## Motivos de fallo (turnos)")
        appendLine()
        val reasons = turns.filterNot { it.verdict.pass }.flatMap { t -> t.verdict.reasons.map { it.substringBefore(':').replace(Regex(" (time|dur|num|word|day|date)$"), "") } }
            .map { it.replace(Regex("^(falta|afirma|no ofrece) .*"), "$1 …") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        reasons.forEach { (reason, count) -> appendLine("- $reason: $count") }
        appendLine()
        appendLine("## Fallos de ejemplo")
        appendLine()
        turns.filterNot { it.verdict.pass }.take(20).forEach { t ->
            appendLine("- **${t.episode}** T${t.turn} «${t.user}» → `${t.modelOutput.replace("\n", " ; ")}` → «${t.answer.take(140)}» `${t.executions.joinToString()}` — ${t.verdict.reasons.joinToString()}" +
                (if (t.verdict.diff.isNotEmpty()) " · ${t.verdict.diff.take(3).joinToString(" · ")}" else ""))
        }
    }
}
