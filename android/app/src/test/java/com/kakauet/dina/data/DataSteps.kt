package com.kakauet.dina.data

import com.kakauet.dina.brain.dina45.Dina45Brain
import com.kakauet.dina.brain.dina45.Dina45Codec
import com.kakauet.dina.brain.dina45.Dina45Prompt
import com.kakauet.dina.dialog.Action
import com.kakauet.dina.dialog.ArgType
import com.kakauet.dina.dialog.Day
import com.kakauet.dina.dialog.DialogueTurn
import com.kakauet.dina.dialog.Ops
import com.kakauet.dina.dialog.Policies
import com.kakauet.dina.dialog.StateSummary
import com.kakauet.dina.dialog.Texts
import com.kakauet.dina.eval.Sandbox
import com.kakauet.dina.eval.StateSpec
import com.kakauet.dina.eval.WorldView
import com.kakauet.dina.llm.LlmMetrics
import com.kakauet.dina.llm.LlmResult
import com.kakauet.dina.llm.TextGenerator
import com.kakauet.dina.tools.ToolData
import com.kakauet.dina.tools.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

val DATA_ZONE: ZoneId = ZoneId.of("Europe/Madrid")

/**
 * Replays a scenario on a real [com.kakauet.dina.tools.ToolEngine] through the real Dina 4.5 brain, with a
 * scripted model that answers each turn with the given contract text. The prompt it captures is the
 * app's prompt byte for byte, so training data and the app cannot drift apart. With a [live] model,
 * [playLive] lets it write the turn instead (the development set, `.\dev.ps1 eval dina45 dev:<batch>`).
 */
class Replay(scenario: Scenario, historyTurns: Int = 1, live: TextGenerator? = null) {
    private val sandbox = Sandbox(StateSpec.build(scenario.state, scenario.clock, DATA_ZONE), scenario.clock.atZone(DATA_ZONE).toInstant().toEpochMilli(), DATA_ZONE)
    private val model = ReplayModel(live)
    // Canonical labels are replayed as they are; a live model (development set) runs as in the app.
    private val brain = Dina45Brain(model, sandbox.executor, Policies(), seed = 0, historyTurns = historyTurns, grounding = live != null)
    private var turns = 0

    class Played(val prompt: String, val state: String, val answer: String, val brief: String, val outcome: String, val output: String)

    /** Answers with the scripted text when there is one, else asks the live model (with the brain's own budget and grammar). */
    private class ReplayModel(private val live: TextGenerator?) : TextGenerator {
        var next: String? = null
        var lastPrompt = ""
        var lastOutput = ""

        override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit, grammar: String?): LlmResult {
            lastPrompt = prompt
            val scripted = next
            val result = if (scripted != null) LlmResult(scripted, LlmMetrics()) else checkNotNull(live) { "no live model" }.generate(prompt, maxTokens, onToken, grammar)
            lastOutput = result.text
            return result
        }

        override fun cancel() { live?.cancel() }
    }

    fun play(user: String, actions: List<Action>): Played = play(user, Dina45Codec.encode(actions))

    fun play(user: String, output: String): Played = turn(user, output)

    /** The live model reads the turn and writes the actions. */
    fun playLive(user: String): Played = turn(user, null)

    private fun turn(user: String, output: String?): Played {
        if (turns++ > 0) sandbox.advance(TURN_GAP_MS)
        sandbox.beginTurn(emptyMap())
        val state = StateSummary.render(brain.dialogue.snapshot(), brain.dialogue.view())
        model.next = output
        runBlocking { brain.runTurn(user) }
        val turn = brain.lastTurn!!
        return Played(model.lastPrompt, state, turn.answer, turn.brief, outcome(turn), model.lastOutput)
    }

    /** What a turn did, equal for equivalent actions: world (labels by head word), executions, pending question and brief. */
    private fun outcome(turn: DialogueTurn): String {
        val world = WorldView.of(sandbox.state(), sandbox.nowMs, DATA_ZONE).lines().map { line ->
            if (line.startsWith("compra")) line else LABEL.replace(line) { "[" + it.groupValues[1].split(' ').last() + "]" }
        }
        val executions = turn.executions.map { e ->
            "${e.tool}.${e.op}:" + ((e.outcome as? ToolOutcome.Failure)?.code ?: when (val data = e.data) {
                is ToolData.AlarmItem -> data.alarm.id
                is ToolData.TimerItem -> data.timer.id
                is ToolData.StopwatchItem -> data.stopwatch.id
                is ToolData.ShoppingEntry -> data.item.id
                is ToolData.Alarms -> data.items.joinToString(",") { it.id }
                is ToolData.Timers -> data.items.joinToString(",") { it.id }
                is ToolData.Stopwatches -> data.items.joinToString(",") { it.id }
                is ToolData.ShoppingList -> data.items.joinToString(",") { it.id }
                // the same answer however it was asked: 45*13/100 and 45/100*13, medio kilo and 500 g
                is ToolData.Number -> "Number(${"%.9g".format(Locale.ROOT, data.value.toDouble())})"
                is ToolData.Conversion -> "Conversion(${"%.6g".format(Locale.ROOT, data.result)} ${data.to}, ${data.ingredient})"
                else -> data.toString()
            })
        }.sorted()
        val pending = brain.dialogue.pending?.let { "${it.action.op}:${it.need::class.simpleName}" }
        return (world + executions + "pendiente=$pending" + "brief=${turn.brief}").joinToString("\n")
    }

    companion object {
        const val TURN_GAP_MS = 20_000L
        private val LABEL = Regex("\\[([^\\]]*)]")

        /** True when every turn's canonical actions give the brief its template expects. */
        fun check(scenario: Scenario): Boolean {
            val replay = Replay(scenario)
            return scenario.turns.all { turn -> turn.expect(replay.play("", turn.actions).brief) }
        }

        /** The canonical actions with the generator's [reply] as the text of their `say`. */
        fun withReply(actions: List<Action>, reply: String?): List<Action> =
            if (reply == null) actions else actions.map { if (it.op == "say") it.copy(args = mapOf("text" to reply)) else it }

        /** True when only `say` (pure chat: no request in the phrase). */
        fun chatOnly(actions: List<Action>) = actions.isNotEmpty() && actions.all { it.op == "say" }
    }
}

/** The three Kotlin steps of the pipeline (datos.md); the API calls are in `scripts/data`. */
object DataSteps {
    fun scenarios(dir: File, n: Int, seed: Long, devShare: Double, mix: String = ScenarioGenerator.BASE): String {
        val generator = ScenarioGenerator(seed, mix)
        val scenarios = generator.generate(n, dir.name, devShare)
        dir.mkdirs()
        File(dir, "fichas.jsonl").writeText(scenarios.joinToString("") { toJson(it).toString() + "\n" }, Charsets.UTF_8)
        File(dir, "contract.txt").writeText(ContractReference.text(), Charsets.UTF_8)
        val tried = generator.attempts.values.sum()
        val failed = generator.rejected.values.sum()
        val worst = generator.rejected.entries.sortedByDescending { it.value }.take(5)
            .joinToString(", ") { "${it.key} ${it.value}/${generator.attempts[it.key]}" }
        File(dir, "scenarios.txt").writeText(
            generator.attempts.keys.sorted().joinToString("\n") { "$it\t${generator.attempts[it]}\t${generator.rejected[it] ?: 0}" } + "\n",
            Charsets.UTF_8,
        )
        return "${scenarios.size} escenarios (${scenarios.count { it.split == "dev" }} de desarrollo), " +
            "${scenarios.sumOf { it.turns.size }} turnos; plantillas descartadas $failed/$tried" + if (worst.isEmpty()) "" else " ($worst)"
    }

    /**
     * Round trip: the other model's actions must do what the canonical actions do, on the real engine.
     * Its own `say` texts do not count (a turn of pure chat only needs to be read as chat); a reply the
     * generator wrote for Dina must survive the app's `say` filter in its turn.
     */
    fun verify(dir: File): String {
        val scenarios = readScenarios(dir)
        val lines = jsonl(File(dir, "roundtrip.jsonl"))
        val replies = jsonl(File(dir, "phrases.jsonl")).filter { it.has("reply") }.associate { it.getString("id") to it.getString("reply") }
        val out = StringBuilder()
        var ok = 0
        lines.groupBy { it.getString("scenario") }.forEach { (id, rows) ->
            val scenario = scenarios[id] ?: return@forEach
            rows.forEach { row ->
                val turn = row.getInt("turn")
                val canonical = scenario.turns[turn - 1].actions
                val reply = replies[row.getString("id")]
                val why = compare(scenario, turn, row.optString("actions"), lenientChat = true).let { why ->
                    if (why != "igual" || reply == null) why
                    else if (replayTo(scenario, turn).play("", Replay.withReply(canonical, reply)).answer.contains(Texts.clean(reply))) why
                    else "say filtrado"
                }
                if (why == "igual") ok++
                out.append(JSONObject().put("id", row.getString("id")).put("ok", why == "igual").put("why", why)).append('\n')
            }
        }
        File(dir, "verified.jsonl").writeText(out.toString(), Charsets.UTF_8)
        return "ida y vuelta: $ok/${lines.size} frases dicen lo que pide su ficha"
    }

    /**
     * "igual" when [output] does on the engine what the canonical actions of [turn] do (after the
     * canonical earlier turns), else why not. [lenientChat]: a pure chat turn read as any `say` passes.
     */
    fun compare(scenario: Scenario, turn: Int, output: String, lenientChat: Boolean = false, expected: List<Action> = scenario.turns[turn - 1].actions): String {
        val decoded = Dina45Codec.decode(output)
        if (decoded.actions.isEmpty()) return "ilegible"
        if (decoded.invalidLines > 0) return "líneas fuera del contrato"
        if (lenientChat && Replay.chatOnly(expected) && Replay.chatOnly(decoded.actions)) return "igual"
        return differs(replayTo(scenario, turn).play("", expected).outcome, replayTo(scenario, turn).play("", output).outcome)
    }

    private fun differs(expected: String, got: String): String = if (expected == got) "igual" else "distinto: " +
        expected.lines().zip(got.lines()).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> "$a ≠ $b" }.orEmpty().ifEmpty { "número de cambios" }

    /** Prompts rendered by the app's own brain; completions are the canonical actions. [history] 0 is the ablation without `[antes]`. */
    fun render(dir: File, history: Int = 1): String {
        val scenarios = readScenarios(dir)
        val train = StringBuilder()
        val dev = StringBuilder()
        var examples = 0
        jsonl(File(dir, "selected.jsonl")).forEach { row ->
            val scenario = scenarios.getValue(row.getString("scenario"))
            val texts = row.getJSONArray("texts")
            val replies = row.optJSONArray("replies")
            val replay = Replay(scenario, history)
            for (i in 0 until texts.length()) {
                val user = texts.getString(i)
                val actions = Replay.withReply(scenario.turns[i].actions, replies?.takeUnless { it.isNull(i) }?.getString(i))
                val played = replay.play(user, actions)
                check(played.prompt.endsWith("[ahora]\n${Dina45Prompt.clean(user)}<|im_end|>\n<|im_start|>assistant\n")) { "prompt mismatch in ${scenario.id}" }
                val example = JSONObject()
                    .put("id", "${scenario.id}/${row.getInt("variant")}/${i + 1}")
                    .put("split", scenario.split).put("phenomenon", scenario.phenomenon).put("domain", scenario.domain)
                    .put("prompt", played.prompt).put("completion", Dina45Codec.encode(actions))
                (if (scenario.split == "dev") dev else train).append(example).append('\n')
                examples++
            }
        }
        val suffix = if (history == 1) "" else "-h$history"
        File(dir, "train$suffix.jsonl").writeText(train.toString(), Charsets.UTF_8)
        File(dir, "dev$suffix.jsonl").writeText(dev.toString(), Charsets.UTF_8)
        return "render$suffix: $examples ejemplos (${train.count { it == '\n' }} de entrenamiento, ${dev.count { it == '\n' }} de desarrollo)"
    }

    private fun replayTo(scenario: Scenario, turn: Int): Replay =
        Replay(scenario).also { replay -> scenario.turns.take(turn - 1).forEach { replay.play("", it.actions) } }

    /**
     * The development set with a real model (`.\dev.ps1 eval dina45 dev:<batch>`): each turn of each
     * dev conversation, after its canonical earlier turns (teacher forcing, like the round trip), is read
     * by [live] through the app's Dina 4.5 brain and scored by its outcome on the engine. A conversation
     * counts when all its turns do. Pure chat needs a `say` that survives the filter.
     */
    fun dev(dir: File, live: TextGenerator, history: Int = 1, limit: Int = Int.MAX_VALUE): DevResult {
        val scenarios = readScenarios(dir)
        val rows = jsonl(File(dir, "selected.jsonl")).filter { it.getString("split") == "dev" }.take(limit)
        check(rows.isNotEmpty()) { "no dev conversations in $dir/selected.jsonl" }
        val turns = mutableListOf<DevTurn>()
        rows.forEach { row ->
            val scenario = scenarios.getValue(row.getString("scenario"))
            val texts = (0 until row.getJSONArray("texts").length()).map { row.getJSONArray("texts").getString(it) }
            val replies = row.optJSONArray("replies")
            val actions = texts.indices.map { Replay.withReply(scenario.turns[it].actions, replies?.takeUnless { r -> r.isNull(it) }?.getString(it)) }
            texts.indices.forEach { i ->
                fun prefixed(model: TextGenerator?) = Replay(scenario, history, model).also { r -> (0 until i).forEach { r.play(texts[it], actions[it]) } }
                val expected = prefixed(null).play(texts[i], actions[i])
                val got = prefixed(live).playLive(texts[i])
                turns += DevTurn("${scenario.id}/${row.getInt("variant")}", i + 1, scenario.phenomenon, texts[i], Dina45Codec.encode(actions[i]),
                    got.output, got.answer, differs(expected.outcome, got.outcome))
            }
        }
        return DevResult(turns)
    }

    fun toJson(scenario: Scenario): JSONObject {
        val replay = Replay(scenario)
        val turns = JSONArray()
        scenario.turns.forEach { turn ->
            val played = replay.play("", turn.actions)
            turns.put(JSONObject().put("ficha", turn.ficha).put("actions", Dina45Codec.encode(turn.actions))
                .put("state", played.state).put("answer", played.answer).put("brief", played.brief).putOpt("reply", turn.reply))
        }
        return JSONObject().put("id", scenario.id).put("phenomenon", scenario.phenomenon).put("domain", scenario.domain)
            .put("complexity", scenario.complexity).put("split", scenario.split).put("clock", scenario.clock.toString())
            .put("state", scenario.state).put("turns", turns)
    }

    fun fromJson(json: JSONObject): Scenario {
        val turns = json.getJSONArray("turns")
        return Scenario(
            json.getString("id"), json.getString("phenomenon"), json.getString("domain"), json.getString("complexity"),
            json.getString("split"), LocalDateTime.parse(json.getString("clock")), json.getJSONObject("state"),
            (0 until turns.length()).map { i ->
                val turn = turns.getJSONObject(i)
                TurnSpec(turn.getString("ficha"), Dina45Codec.decode(turn.getString("actions")).actions, { true }, turn.optString("reply").ifEmpty { null })
            },
        )
    }

    private fun readScenarios(dir: File) = jsonl(File(dir, "fichas.jsonl")).map(::fromJson).associateBy { it.id }

    fun jsonl(file: File): List<JSONObject> =
        if (!file.exists()) emptyList() else file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map(::JSONObject)
}

/** The contract as the round-trip model reads it: formats, every op of [Ops], topics and a few made-up examples. */
object ContractReference {
    private val MEANING = mapOf(
        "alarm.add" to "poner una alarma", "alarm.edit" to "cambiar una alarma", "alarm.del" to "borrar alarmas",
        "alarm.off" to "desactivar sin borrar", "alarm.on" to "volver a activar", "alarm.get" to "cuándo suena una alarma",
        "alarm.list" to "qué alarmas hay", "alarm.snooze" to "posponer la que suena", "timer.add" to "poner un temporizador",
        "timer.pause" to "pausar", "timer.resume" to "reanudar", "timer.del" to "quitar", "timer.get" to "cuánto le queda",
        "timer.list" to "qué temporizadores hay", "timer.plus" to "añadir tiempo", "timer.minus" to "quitar tiempo",
        "timer.edit" to "cambiar tiempo restante o nombre", "sw.add" to "poner en marcha un cronómetro", "sw.pause" to "parar",
        "sw.resume" to "seguir", "sw.reset" to "poner a cero", "sw.restart" to "a cero y en marcha", "sw.del" to "quitar",
        "sw.get" to "cuánto lleva", "sw.list" to "qué cronómetros hay", "list.add" to "apuntar en la compra",
        "list.del" to "quitar de la compra", "list.check" to "marcar como comprado", "list.uncheck" to "desmarcar",
        "list.edit" to "cambiar cantidad, unidad o nombre", "list.list" to "leer la lista", "vol.set" to "volumen a un valor (0-100)",
        "vol.up" to "subir (opcional: cuánto)", "vol.down" to "bajar (opcional: cuánto)", "vol.mute" to "silenciar",
        "vol.unmute" to "quitar el silencio", "vol.get" to "a cuánto está", "time.now" to "hora, fecha o día de la semana de hoy; con \"lugar\", los de otra ciudad o país",
        "time.weekday" to "qué día de la semana cae una fecha", "time.until" to "cuántos días faltan", "calc" to "una cuenta (+ - * / ** sqrt())",
        "conv" to "pasar una cantidad de una unidad a otra; \"ingrediente\" para tazas o cucharadas a gramos y al revés",
        "stop" to "parar lo que suena", "ask" to "no se entiende qué quiere", "no" to "algo que Dina no puede hacer",
        "say" to "charla: lo que contesta Dina cuando no hay petición (saludo, gracias, preguntas sobre ella, curiosidades que no puede consultar); tras una acción, una coletilla breve opcional",
        "fun.joke" to "contar un chiste", "fun.fact" to "contar una curiosidad o dato curioso", "undo" to "deshacer lo último", "drop" to "olvidar la pregunta pendiente",
        "yes" to "sí a una confirmación", "nope" to "no a una confirmación o pregunta",
    )

    private fun arg(op: String, key: String, type: ArgType, named: Boolean): String = when {
        named -> "$key=…"
        type == ArgType.TARGET -> "objetivo"
        type == ArgType.CLOCK -> "hora"
        type == ArgType.DURATION -> "duración"
        type == ArgType.DAY -> "día"
        type == ArgType.INT -> "número"
        type == ArgType.PART -> Ops.PARTS.joinToString("|")
        type == ArgType.TOPIC -> "tema"
        type == ArgType.TEXT -> "\"" + mapOf("label" to "nombre", "name" to "producto", "text" to "texto", "expr" to "cuenta", "place" to "lugar", "what" to "ingrediente").getOrDefault(key, key) + "\""
        type == ArgType.AMOUNT -> "cantidad"
        type == ArgType.MEASURE -> if (key == "from") "de" else "a"
        else -> key
    }

    fun text(): String = buildString {
        appendLine("Formato: una acción por línea, dominio.op(objetivo, valor, clave=valor). Argumentos separados por \", \". Nada más.")
        appendLine("Valores:")
        appendLine("- hora: 7:30, 19:00. Si dice la franja: 7:30 mañana, 7:30 tarde, 10:00 noche. Si no la dice y puede ser las dos: 7:30.")
        appendLine("- day=: hoy, mañana, pasado, lunes…domingo, 25/12, 25/12/2027, +3 (dentro de 3 días), 25 (el próximo día 25, sin mes) o una fiesta: ${Day.HOLIDAYS.keys.joinToString(", ")}.")
        appendLine("- conv: cantidad 3.5, -10 o 1/3; unidades: ${Ops.MEASURES.joinToString(" ")}. Sin «a» si no se dice y es la de siempre (libras → kg, °F → °C); sin cantidad si falta.")
        appendLine("- repeat=: diario, laborables, finde o días sueltos lun,mie,vie; repeat=no quita la repetición.")
        appendLine("- duración: 10m, 1h30m, 45s. at= (al cambiar una alarma): hora nueva, o +15m / -30m para moverla.")
        appendLine("- textos entre comillas: \"médico\". n=2; n=+1 suma a lo que hay. Unidades (unit=): ${Ops.UNITS.joinToString(", ")}.")
        appendLine("- objetivo: @ (aquello de lo que se acaba de hablar), * (todos), sonando, #próximo, #último, #2 (posición en lo que Dina leyó), \"nombre\", una hora (7:00) o una franja (mañana, tarde, noche). Sin objetivo si solo hay uno.")
        appendLine("- ? en lugar de un valor: el dato falta y Dina lo preguntará. Sin la hora o la duración obligatoria también pregunta.")
        appendLine("Acciones:")
        Ops.all.forEach { spec ->
            appendLine("${spec.op}(${spec.args.joinToString(", ") { arg(spec.op, it.key, it.type, it.named) }})  ${MEANING[spec.op].orEmpty()}")
        }
        appendLine("Temas de no(): ${Ops.TOPICS.joinToString(", ")}.")
        appendLine("Ejemplos:")
        appendLine("«ponme una alarma a las siete y cuarto de la tarde para el pádel» → alarm.add(7:15 tarde, \"pádel\")")
        appendLine("«despiértame el jueves a las seis» → alarm.add(6:00, day=jueves)")
        appendLine("«apúntame dos litros de leche y pan» → list.add(\"leche\", n=2, unit=l) y en otra línea list.add(\"pan\")")
        appendLine("«quita todos los temporizadores» → timer.del(*)")
        appendLine("«¿y cuánto le queda?» (tras poner un temporizador) → timer.get(@)")
        appendLine("«pon una alarma» → alarm.add()")
        appendLine("«pon algo de jazz» → no(música)")
        appendLine("«¿cuántos kilómetros son tres millas y media?» → conv(3.5, milla, km)   «dos tazas de harina en gramos» → conv(2, taza, g, \"harina\")")
        appendLine("«¿qué hora es en Tokio?» → time.now(hora, \"Tokio\")   «¿cuántos días faltan para Navidad?» → time.until(navidad)")
        appendLine("«hola, buenas» → say(\"¡Hola! ¿En qué te ayudo?\")")
        appendLine("«cuéntame un chiste» → fun.joke()   «dime algo curioso» → fun.fact()")
        appendLine("«sí, vale» (Dina pidió confirmar) → yes()   «no, déjalo» → drop()   «deshaz eso» → undo()   «para» (suena algo) → stop()")
    }
}

/** One dev turn: what the model wrote and why it does (not) do what the canonical actions do. */
class DevTurn(val conversation: String, val turn: Int, val phenomenon: String, val user: String, val expected: String, val output: String, val answer: String, val why: String) {
    val ok get() = why == "igual"
}

class DevResult(val turns: List<DevTurn>) {
    val conversations: Map<String, List<DevTurn>> = turns.groupBy { it.conversation }
    val conversationsOk get() = conversations.values.count { c -> c.all { it.ok } }
    val turnsOk get() = turns.count { it.ok }
}
