package com.kakauet.dina.eval

import com.kakauet.dina.dialog.SpanishText
import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.Calculate
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.TimerChange
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.VolumeCommand
import com.kakauet.dina.tools.WhenSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Quality gate for benchmark/realworld_v2: every path of every episode is reachable in the real
 * engine, every sentence states the values its expectation uses, and no sentence comes from RW200 v1.
 */
class BenchmarkV2Test {
    private val root = File(System.getProperty("dina.root") ?: "../..")
    private val episodes by lazy { EpisodeJson.loadDir(File(root, "benchmark/realworld_v2/episodes")) }

    @Test
    fun structure() {
        val ids = episodes.map { it.id }
        assertEquals("duplicated ids", ids.size, ids.toSet().size)
        val categories = episodes.groupingBy { it.category }.eachCount()
        assertEquals(CATEGORIES, categories.keys)
        categories.forEach { (category, count) -> assertTrue("$category has $count episodes", count >= 30) }
        episodes.forEach { e -> assertTrue("${e.id}: variety ${e.variety}", e.variety in VARIETIES) }
    }

    @Test
    fun everyPathPassesWithTheOracleUnderEveryPolicy() = runBlocking {
        val problems = mutableListOf<String>()
        for (policies in POLICIES) {
            val runner = EvalRunner(OracleFactory(), policies)
            for (episode in episodes) {
                val paths = try { EvalRunner.paths(episode, policies) } catch (e: Exception) { problems += "${episode.id}: ${e.message}"; continue }
                if (paths.isEmpty()) problems += "${episode.id}: no path under $policies"
                for (path in paths) {
                    val record = try { runner.run(episode, path) } catch (e: OracleError) { problems += "${episode.id} $path: ${e.message}"; continue }
                    if (!record.pass) problems += "${episode.id} $path $policies: ${record.turns.lastOrNull()?.let { it.verdict.reasons + it.verdict.diff + "«${it.answer}»" }}"
                    else if (record.turns.map { it.verdict.option } != path) problems += "${episode.id}: options ${record.turns.map { it.verdict.option }} are indistinguishable from $path"
                }
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun mutatingOptionsChangeTheWorld() {
        val problems = mutableListOf<String>()
        forEachOption { episode, pre, nowMs, turn, option, _ ->
            if (option.kind != Kind.ACT || option.actions.isEmpty()) return@forEachOption
            val run = Oracle.run(pre, nowMs, episode.zone, option, turn.inject)
            val changes = run.executions.any(Oracle::mutates)
            val same = run.expected.diff(pre).isEmpty()
            if (changes && same && "invalid_state" !in episode.tags) problems += "${episode.id} «${turn.user}»: the expected action changes nothing"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * Second pass: values used by the expectation must be said in the conversation so far, this
     * sentence included (or be explained in [DERIVED]). A bare number counts for a duration whose
     * unit the speaker left out («y otro de veinticinco»).
     */
    @Test
    fun sentencesStateWhatTheExpectationUses() {
        val problems = mutableListOf<String>()
        forEachOption { episode, pre, nowMs, turn, option, history ->
            if (option.kind != Kind.ACT) return@forEachOption
            val text = SpanishText.normalize((history + turn.user).joinToString(" "))
            fun saysDuration(ms: Long) = SpanishText.mentionsDuration(text, ms / 1000) ||
                listOf(60_000L, 3_600_000L).any { unit -> ms % unit == 0L && SpanishText.mentionsNumber(text, (ms / unit).toDouble()) }
            val sandbox = Sandbox(pre, nowMs, episode.zone)
            option.actions.filterIsInstance<OracleAction.Line>().forEach { action ->
                val command = Dsl.command(action.text, sandbox.state(), nowMs, episode.zone)
                val missing = mutableListOf<String>()
                when (command) {
                    is AlarmCommand.Create -> (command.at as? WhenSpec.At)?.let { if (!SpanishText.mentionsTime(text, it.time)) missing += "hora ${it.time}" }
                    is AlarmCommand.Update -> (command.at as? WhenSpec.At)?.let { if (action.text.contains("at=") && !SpanishText.mentionsTime(text, it.time)) missing += "hora ${it.time}" }
                    is TimerCommand.Create -> if (!saysDuration(command.durationMs)) missing += "duración ${command.durationMs / 1000}s"
                    is TimerCommand.Update -> when (val change = command.change) {
                        is TimerChange.Add -> if (!saysDuration(change.ms)) missing += "duración"
                        is TimerChange.Subtract -> if (!saysDuration(change.ms)) missing += "duración"
                        is TimerChange.SetRemaining -> if (!saysDuration(change.ms)) missing += "duración"
                        is TimerChange.Rename -> if (change.label != null && !SpanishText.mentionsPhrase(text, change.label)) missing += "etiqueta ${change.label}"
                    }
                    is ShoppingCommand.Add -> if (Dsl.labelAlternatives(action.text).none { SpanishText.mentionsPhrase(text, it) }) missing += "producto ${command.name}"
                    is VolumeCommand.Set -> if (!SpanishText.mentionsNumber(text, command.percent.toDouble()) && !Regex("maximo|tope|mitad|minimo").containsMatchIn(text)) missing += "volumen ${command.percent}"
                    is Calculate -> SpanishText.numbers(command.expression.replace(Regex("[^0-9.]"), " ")).filterNot { it == 100.0 && "por ciento" in text }
                        .filterNot { SpanishText.mentionsNumber(text, it) }.forEach { missing += "número $it" }
                    else -> Unit
                }
                val label = (command as? AlarmCommand.Create)?.label ?: (command as? TimerCommand.Create)?.label
                if (label != null && Dsl.labelAlternatives(action.text).none { SpanishText.mentionsPhrase(text, it) }) missing += "etiqueta $label"
                val excused = DERIVED.any { it.first == episode.id && (it.second.isEmpty() || SpanishText.fold(it.second) == SpanishText.fold(turn.user)) }
                if (missing.isNotEmpty() && !excused) problems +="${episode.id} «${turn.user}» (${action.text}): no dice ${missing.joinToString()}"
                sandbox.executor.execute(command)
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun noSentenceComesFromRealWorld200() {
        val old = Rw200.load(File(root, "benchmark/realworld200/episodes")).flatMap { e -> e.turns.map { SpanishText.fold(it.user) } }.toSet()
        // Short stock commands («¿qué hora es?», «pon una alarma») are how everybody says it, not a copied sentence.
        val reused = episodes.flatMap { e -> allTurns(e.turns).map { e.id to it.user } }
            .filter { SpanishText.wordCount(it.second) > SHORT_COMMAND_WORDS && SpanishText.fold(it.second) in old }
        assertTrue(reused.joinToString("\n"), reused.isEmpty())
    }

    // ---- Helpers ----

    private fun allTurns(turns: List<Turn>): List<Turn> = turns + turns.flatMap { t -> t.options.flatMap { o -> o.then?.let(::allTurns).orEmpty() } }

    /** Visits every option of every turn along every path, with the world right before the turn. */
    private fun forEachOption(visit: (Episode, WorldState, Long, Turn, Expect, List<String>) -> Unit) {
        for (episode in episodes) {
            fun walk(turns: List<Turn>, index: Int, state: WorldState, nowMs: Long, history: List<String>) {
                if (index >= turns.size) return
                val turn = turns[index]
                val now = nowMs + turn.waitMs
                val pre = Sandbox(state, now, episode.zone).state()
                for (option in turn.options) {
                    visit(episode, pre, now, turn, option, history)
                    val after = Oracle.run(pre, now, episode.zone, option, turn.inject).expected.state
                    if (option.then != null) walk(option.then, 0, after, now, history + turn.user) else walk(turns, index + 1, after, now, history + turn.user)
                }
            }
            walk(episode.turns, 0, episode.initial, episode.clock.atZone(episode.zone).toInstant().toEpochMilli(), emptyList())
        }
    }

    companion object {
        val CATEGORIES = setOf(
            "directas", "consultas", "referencias", "ambiguedad", "pendiente", "correcciones",
            "multiaccion", "interrupciones", "errores", "fuera_de_alcance", "charla",
            // v2.1 (Dina 4.5)
            "conversiones",
        )
        val VARIETIES = setOf("es-ES", "es-MX", "es-AR", "es-CO", "es-CL", "es-PE", "es-VE", "es-UY")
        val POLICIES = listOf(
            emptyMap(),
            mapOf("ampm" to "ask", "bulk" to "confirm"),
            mapOf("ampm" to "next", "bulk" to "direct"),
            mapOf("ampm" to "mixed", "bulk" to "confirm"),
            mapOf("ampm" to "mixed", "bulk" to "direct"), // the app's policies
        )

        /** Values the sentence implies without saying them: relative changes, context or common sense. */
        val DERIVED = listOf(
            "dir-07" to "", // «el sábado que viene»: el día no es una hora
            "ref-06" to "Ponla media hora antes.",
            "ref-16" to "Retrasa la de mañana un cuarto de hora.",
            "ref-01" to "Cambia la de las siete de la tarde a las ocho.",
            "cor-02" to "No, perdona, que sean veinte.",
            "cor-05" to "Deshaz eso.",
            "cor-06" to "¡No! Deshazlo, que la necesito.",
            "cor-18" to "Ay, no, deshazlo.",
            "cor-22" to "Uy, me he confundido, era el de la pasta. Vuelve a poner el del horno como estaba.",
            "cor-20" to "Mejor a las nueve y media.",
            "cor-35" to "Perdona, a las siete.",
            "cor-35" to "Recuérdame a las seis de la tarde llamar a mi madre.",
            "pen-04" to "Más o menos a la mitad.",
            "pen-24" to "Hasta las nueve.",
            "pen-23" to "Para dentro de dos horas.",
            "mul-19" to "¿Cuánto es 48 entre 4? ¿Y por 3?",
            "mul-32" to "Pon dos de leche más y añade cereales.",
            "amb-23" to "¿Y con ocho?",
            "con-31" to "¿Cuánto son tres kilos a cuatro cincuenta el kilo?",
            "dir-34" to "Calcula el quince por ciento de ochenta.",
            "err-04" to "Pon el volumen al ciento cincuenta.",
            "cor-39" to "uy no muy duro déjelo como estaba", // «como estaba» = el valor anterior
            "mul-33" to "", // «baja el volumen»: cuánto lo decide quien responde
        )
        const val SHORT_COMMAND_WORDS = 3
    }
}
