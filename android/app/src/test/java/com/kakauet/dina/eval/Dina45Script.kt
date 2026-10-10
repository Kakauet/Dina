package com.kakauet.dina.eval

import com.kakauet.dina.brain.Brain
import com.kakauet.dina.brain.dina45.Dina45Brain
import com.kakauet.dina.brain.dina45.Dina45Codec
import com.kakauet.dina.brain.dina45.Dina45Prompt
import com.kakauet.dina.dialog.Action
import com.kakauet.dina.dialog.Clock
import com.kakauet.dina.dialog.Day
import com.kakauet.dina.dialog.Domain
import com.kakauet.dina.dialog.Fraction
import com.kakauet.dina.dialog.Missing
import com.kakauet.dina.dialog.NoRepeat
import com.kakauet.dina.dialog.Period
import com.kakauet.dina.dialog.Policies
import com.kakauet.dina.dialog.Ref
import com.kakauet.dina.dialog.Shift
import com.kakauet.dina.dialog.SpanishText
import com.kakauet.dina.llm.LlmMetrics
import com.kakauet.dina.llm.LlmResult
import com.kakauet.dina.llm.TextGenerator
import com.kakauet.dina.tools.Places
import com.kakauet.dina.tools.Target
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.Units
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking

/**
 * The perfect Dina 4.5 model for RW2 (the design ceiling, docs/HISTORY.md): for each turn it writes what a
 * perfect reading of the phrase gives in the contract, derived from the expected option (oracle
 * actions, or the partial action behind a question) and how the phrase refers to things. Turns
 * whose output cannot be derived (answers that come from a failed attempt, chat) are in
 * [OVERRIDES]. Measures the design (contract + dialogue + answers), not a model.
 */
class ScriptedModel(private val tokenizer: TextGenerator? = null) : TextGenerator {
    var next: String = ""
    var lastPrompt: String = ""
    val promptTokens = mutableListOf<Int>()

    override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit, grammar: String?): LlmResult {
        lastPrompt = prompt
        val tokens = tokenizer?.generate(prompt, 1)?.metrics?.promptTokens ?: 0
        if (tokens > 0) promptTokens += tokens
        return LlmResult(next, LlmMetrics(promptTokens = tokens, completionTokens = next.length / 3))
    }
}

class ScriptedDina45Brain(private val inner: Dina45Brain, private val model: ScriptedModel, private val sandbox: Sandbox) : Brain by inner, ScriptedBrain {
    override fun plan(episode: Episode, turn: Turn, eligible: List<IndexedValue<Expect>>, upcoming: List<Turn>) {
        model.next = Dina45Script(sandbox, inner).output(episode, turn, eligible.map { it.value }, upcoming)
    }
}

class ScriptedDina45Factory(policies: Map<String, String>, private val tokenizer: TextGenerator? = null) : BrainFactory {
    override val id = "dina45-scripted"

    private val policies = Policies.of(policies)

    /** Tokens of the fixed part (system prompt and chat template, always in the prompt cache). */
    override fun notes(): Map<String, Any> = tokenizer?.let { mapOf("fixed_prompt_tokens" to runBlocking { it.generate(Dina45Prompt.render("", null, ""), 1).metrics.promptTokens }) }.orEmpty()

    override fun create(sandbox: Sandbox): Brain {
        val model = ScriptedModel(tokenizer)
        return ScriptedDina45Brain(Dina45Brain(model, sandbox.executor, policies, seed = 0), model, sandbox)
    }
}

class Dina45Script(private val sandbox: Sandbox, private val brain: Dina45Brain) {
    private val snapshot = brain.dialogue.snapshot()
    private val world = snapshot.world
    private val zone = sandbox.zone
    private val now: ZonedDateTime = Instant.ofEpochMilli(snapshot.nowMs).atZone(zone)
    private val today: LocalDate = now.toLocalDate()
    private lateinit var utterance: String
    private lateinit var normalized: String

    fun output(episode: Episode, turn: Turn, eligible: List<Expect>, upcoming: List<Turn>): String {
        utterance = turn.user
        normalized = SpanishText.normalize(turn.user)
        OVERRIDES["${episode.id}|${turn.user}"]?.let { return it }
        val option = eligible.firstOrNull { o -> o.kind in setOf(Kind.ACT, Kind.FAIL) && o.actions.none { (it as OracleAction.Line).text.startsWith("alarm.in") } }
            ?: eligible.firstOrNull { it.kind == Kind.ASK } ?: eligible.first()
        val actions = when (option.kind) {
            Kind.ACT, Kind.FAIL -> translateAll(option.actions.map { (it as OracleAction.Line).text })
            Kind.ASK -> listOf(partial(option, upcoming))
            Kind.REJECT -> listOf(Action("no", args = mapOf("topic" to topic())))
            Kind.CHAT -> listOf(Action("say", args = mapOf("text" to chat())))
            Kind.ANSWER -> emptyList()
        }
        return Dina45Codec.encode(actions)
    }

    /** Translates the oracle lines of one option against the world before the turn. */
    private fun translateAll(lines: List<String>): List<Action> = lines.map { translate(it) }

    // ---- Oracle line -> contract action ----

    private fun translate(line: String): Action {
        val tokens = Dsl.tokens(line)
        val head = tokens.first().text
        val args = tokens.drop(1)
        fun kv(key: String) = args.firstNotNullOfOrNull { it.value(key) }
        fun quoted() = args.firstOrNull { it.quoted }?.text?.split('|')?.first()
        fun dur() = args.firstNotNullOfOrNull { if (it.quoted || it.text.contains('=')) null else Dsl.duration(it.text) }
        fun int() = args.firstNotNullOfOrNull { if (it.quoted) null else it.text.toIntOrNull() }
        val command = runCatching { Dsl.command(line, sandbox.state(), snapshot.nowMs, zone) }.getOrNull()
        fun target(domain: Domain) = (targetOf(command)?.id)?.let { descriptor(domain, it, args.firstOrNull { a -> !a.quoted && a.text.startsWith("@") }?.text) }
        return when (head) {
            "alarm.add" -> {
                val time = args.firstNotNullOf { Dsl.time(it.text) }
                val repeat = kv("rep")?.let(Dsl::repeat)
                val clock = clockFor(time)
                val date = kv("day")?.let { Dsl.date(it, today) }
                Action("alarm.add", null, buildMap {
                    put("time", clock)
                    dayFor(date, time, repeat)?.let { put("day", it) }
                    repeat?.let { put("repeat", it) }
                    quoted()?.let { put("label", it) }
                })
            }
            "alarm.in" -> Action("timer.add", null, mapOf("dur" to dur()!!))
            "alarm.edit" -> {
                val id = targetOf(command)!!.id!!
                val alarm = world.alarms.first { it.id == id }
                val current = Instant.ofEpochMilli(alarm.nextMs).atZone(zone)
                Action("alarm.edit", descriptor(Domain.ALARM, id, args.firstOrNull { it.text.startsWith("@") }?.text), buildMap {
                    kv("at")?.let(Dsl::time)?.let { at ->
                        put("at", if (mentions(at) != null) clockFor(at) else Shift((at.toSecondOfDay() - current.toLocalTime().withSecond(0).toSecondOfDay()) * 1000L))
                    }
                    kv("day")?.let { put("day", dayValue(Dsl.date(it, today))) }
                    kv("rep")?.let { put("repeat", if (it == "-") NoRepeat else Dsl.repeat(it)) }
                    kv("label")?.let { put("label", it.split('|').first()) }
                })
            }
            "alarm.del", "alarm.off", "alarm.on" -> Action(head, target(Domain.ALARM))
            "alarm.delall" -> Action("alarm.del", Ref.All)
            "alarm.snooze" -> Action("alarm.snooze", null, dur()?.let { mapOf("dur" to it) }.orEmpty())
            "alarm.stop", "timer.stop" -> Action("stop")
            "alarm.list" -> identified()?.let { (ref, day) -> Action("alarm.get", ref, day?.let { mapOf("day" to it) }.orEmpty()) } ?: Action("alarm.list")
            "timer.add" -> Action("timer.add", null, buildMap { put("dur", dur()!!); quoted()?.let { put("label", it) } })
            "timer.pause", "timer.resume", "timer.del", "timer.get" -> Action(head, target(Domain.TIMER))
            "timer.delall" -> Action("timer.del", Ref.All)
            "timer.list" -> Action("timer.list")
            "timer.plus", "timer.minus" -> Action(head, target(Domain.TIMER), mapOf("dur" to dur()!!))
            "timer.set" -> Action("timer.edit", target(Domain.TIMER), mapOf("left" to dur()!!))
            "timer.rename" -> Action("timer.edit", target(Domain.TIMER), mapOf("label" to quoted()!!))
            "sw.add" -> Action("sw.add", null, quoted()?.let { mapOf("label" to it) }.orEmpty())
            "sw.delall" -> Action("sw.del", Ref.All)
            "sw.list" -> Action("sw.list")
            "sw.pause", "sw.resume", "sw.reset", "sw.restart", "sw.del", "sw.get" -> Action(head, target(Domain.SW))
            "list.add" -> Action("list.add", null, buildMap {
                put("name", quoted()!!)
                kv("n")?.let { put("n", it.replace(',', '.').toDouble()) }
                kv("unit")?.let { put("unit", it) }
            })
            "list.del", "list.check", "list.uncheck" -> Action(head, target(Domain.LIST))
            "list.edit" -> Action("list.edit", target(Domain.LIST), buildMap {
                kv("n")?.let { put("n", it.replace(',', '.').toDouble()) }
                kv("unit")?.let { put("unit", it) }
                kv("name")?.let { put("name", it.split('|').first()) }
            })
            "list.clear" -> Action("list.del", Ref.All)
            "list.get" -> Action("list.list")
            "vol.set" -> Action("vol.set", null, mapOf("n" to int()!!))
            "vol.up", "vol.down" -> Action(head, null, int()?.takeIf { n -> SpanishText.numbers(normalized).any { it == n.toDouble() } }?.let { mapOf("n" to it) }.orEmpty())
            "vol.mute", "vol.unmute", "vol.get" -> Action(head)
            "time.now" -> Action("time.now", null, buildMap {
                put("part", when (args.firstOrNull { !it.quoted }?.text) { "date" -> "fecha"; "weekday" -> "dia"; else -> "hora" })
                quoted()?.let { put("place", Places.find(it)?.name ?: it) }
            })
            "time.weekday", "time.until" -> Action(head, null, mapOf("day" to dayValue(Dsl.date(args.first().text, today))))
            "calc" -> Action("calc", null, mapOf("expr" to quoted()!!))
            "conv" -> args.filter { !it.quoted }.let { plain ->
                Action("conv", null, buildMap {
                    put("n", amount(plain[0].text.toDouble()))
                    put("from", plain[1].text)
                    // The target unit is written unless it is the one Dina would pick anyway and the phrase does not name it.
                    if (Units.defaultTarget(plain[1].text) != plain[2].text || mentionsUnit(plain[2].text)) put("to", plain[2].text)
                    quoted()?.let { put("what", it) }
                })
            }
            else -> error("unknown oracle action $line")
        }
    }

    /** 0.333… is written `1/3`, other amounts as they are. */
    private fun amount(value: Double): Any {
        val thirds = Math.round(value * 3)
        return if (kotlin.math.abs(thirds / 3.0 - value) < 1e-6 && thirds % 3 != 0L) Fraction(thirds.toInt(), 3) else value
    }

    /** Whether the phrase names [code]: its word, its symbol or (temperatures) the scale. */
    private fun mentionsUnit(code: String): Boolean {
        val unit = Units.of(code) ?: return false
        val words = SpanishText.fold(utterance).split(' ').map(SpanishText::stem)
        return listOf(unit.one, unit.many, code).map { SpanishText.fold(it).split(' ').first().let(SpanishText::stem) }.any { it in words }
    }

    private fun targetOf(command: ToolCommand?): Target? = command?.let { c ->
        c.javaClass.methods.firstOrNull { it.name == "getTarget" && it.parameterCount == 0 }?.invoke(c) as? Target
    }

    /** How the phrase refers to an item: its label, its time, a position, the focus, or nothing if it is the only one. */
    private fun descriptor(domain: Domain, id: String, selector: String?): Ref? {
        val items = domain.items(world)
        val item = items.first { it.id == id }
        val name = item.name
        if (name != null && SpanishText.mentionsPhrase(normalized, name)) return Ref.Named(name)
        if (name != null) SpanishText.key(name)!!.split(' ').firstOrNull { it.length >= 4 && SpanishText.words(normalized).map(SpanishText::stem).contains(it) }?.let { word ->
            name.split(' ').firstOrNull { SpanishText.stem(SpanishText.fold(it)) == word }?.let { return Ref.Named(it) }
        }
        if (domain == Domain.ALARM) {
            val time = item.time(zone)!!
            mentions(time)?.let { return Ref.At(it) }
        }
        val ordinal = Regex("\\b(primer|segund|tercer|cuart|ultim)").containsMatchIn(SpanishText.fold(utterance))
        if (ordinal && selector == "@last") return Ref.Last
        if (ordinal && selector?.drop(1)?.toIntOrNull() != null) return Ref.Nth(selector.drop(1).toInt())
        if (selector == "@ringing") return Ref.Ringing
        if (brain.dialogue.view().focus.any { it.first == domain && it.second == id }) return Ref.Focus
        if (items.size == 1) return null
        return name?.let { Ref.Named(it) } ?: item.time(zone)?.let { Ref.At(Clock.exact(it)) } ?: Ref.Nth(items.indexOf(item) + 1)
    }

    /** The clock as said: ambiguous if the phrase only says "las siete" (unless it is about waking up). */
    private fun clockFor(time: LocalTime): Clock = when (val said = mentions(time)) {
        null -> Clock.exact(time)
        else -> if (said.ambiguous && WAKE.containsMatchIn(SpanishText.fold(utterance)) && time.hour < 12) Clock(said.hour, said.minute, Period.MORNING) else said
    }

    /** The phrase's mention of [time]: exact (`7:00 mañana`, `19:00`) or ambiguous (`7:00`). */
    private fun mentions(time: LocalTime): Clock? {
        val all = SpanishText.times(normalized)
        if (all.any { !it.ambiguous && it.hour == time.hour && it.minute == time.minute }) return Clock.exact(time)
        val vague = all.firstOrNull { it.ambiguous && it.minute == time.minute && (it.hour == time.hour || (it.hour + 12) % 24 == time.hour) } ?: return null
        return Clock(vague.hour, vague.minute)
    }

    /** The day is written when the phrase says one or when leaving it out would give another date. */
    private fun dayFor(date: LocalDate?, time: LocalTime, repeat: com.kakauet.dina.tools.Repeat?): Day? {
        if (date == null) return null
        val pendingDay = brain.dialogue.pending?.action?.day()?.resolve(today)
        if (!mentionsDay() && pendingDay == date) return null
        var implied = if (LocalTime.from(now).isBefore(time)) today else today.plusDays(1)
        if (repeat != null) implied = generateSequence(implied) { it.plusDays(1) }.take(7).first { d -> includes(repeat, d) }
        if (!mentionsDay() && implied == date) return null
        return dayValue(date)
    }

    private fun includes(repeat: com.kakauet.dina.tools.Repeat, date: LocalDate) = when (repeat) {
        com.kakauet.dina.tools.Repeat.Daily -> true
        com.kakauet.dina.tools.Repeat.Weekdays -> date.dayOfWeek.value <= 5
        com.kakauet.dina.tools.Repeat.Weekends -> date.dayOfWeek.value >= 6
        is com.kakauet.dina.tools.Repeat.Weekly -> date.dayOfWeek in repeat.days
    }

    private fun dayValue(date: LocalDate): Day {
        val folded = SpanishText.fold(utterance)
        val weekday = Day.Weekday(date.dayOfWeek)
        val name = SpanishText.fold(Day.WEEKDAYS[date.dayOfWeek.value - 1])
        if (Regex("\\b$name\\b").containsMatchIn(folded) && weekday.resolve(today) == date) return weekday
        if (Regex("\\bpasado manana\\b").containsMatchIn(folded) && date == today.plusDays(2)) return Day.AfterTomorrow
        Day.HOLIDAYS.keys.firstOrNull { name -> folded.contains(SpanishText.fold(name)) && Day.Named(name).resolve(today) == date }?.let { return Day.Named(it) }
        return Day.of(date, today)
    }

    private fun mentionsDay(): Boolean {
        val folded = " " + SpanishText.fold(utterance) + " "
        return Regex("\\b(hoy|pasado|lunes|martes|miercoles|jueves|viernes|sabado|domingo|dia \\d|\\d+ de [a-z]+)\\b").containsMatchIn(SpanishText.normalize(utterance)) ||
            (Regex("\\bmanana\\b").containsMatchIn(folded) && !Regex("\\b(de|por|en|a) la manana\\b").containsMatchIn(folded))
    }

    /** "¿A qué hora es la del dentista?" names one alarm: a `get` of it rather than the whole list. */
    private fun identified(): Pair<Ref?, Day?>? {
        val items = Domain.ALARM.items(world)
        val folded = SpanishText.fold(utterance)
        if (Regex("\\b(proxima|temprana|antes)\\b").containsMatchIn(folded) && items.size > 1) return Ref.Next to null
        items.filter { it.name != null && SpanishText.mentionsPhrase(normalized, it.name) }.singleOrNull()?.let { return Ref.Named(it.name!!) to null }
        if (mentionsDay() && Regex("\\bmanana\\b").containsMatchIn(folded)) {
            val tomorrow = items.filter { it.date(zone) == today.plusDays(1) }
            if (tomorrow.size == 1 && items.size > 1) return null to Day.Tomorrow
        }
        return null
    }

    // ---- Questions: the partial action the phrase gives ----

    private fun partial(option: Expect, upcoming: List<Turn>): Action {
        val slot = option.ask?.slot.orEmpty()
        val keyword = keywordOp()
        when {
            slot == "item" -> return Action("list.add")
            slot == "value" -> return Action("vol.set")
            slot == "expr" -> return Action("calc")
            slot in setOf("amount", "unit", "ingredient") -> {
                val follow = followUp(option, upcoming, "conv") ?: return Action("conv")
                return when (slot) {
                    "amount" -> follow.with("n", null).with("what", null)
                    "unit" -> follow.with("from", null).with("to", null).with("what", null)
                    else -> follow.with("what", null)
                }
            }
            slot == "day" && keyword in setOf("time.weekday", "time.until") -> return Action(keyword!!)
        }
        val follow = followUp(option, upcoming, keyword?.substringBefore('.')) ?: return keyword?.let { Action(it) } ?: Action("ask")
        // An hour in the phrase names the alarm only if one rings then ("quita la de las siete").
        val target = if (slot != "target") follow.target else SpanishText.times(normalized)
            .map { m -> if (m.ambiguous) Clock(m.hour, m.minute) else Clock.exact(LocalTime.of(m.hour, m.minute)) }
            .firstOrNull { c -> follow.domain == "alarm" && world.alarms.any { a -> Instant.ofEpochMilli(a.nextMs).atZone(zone).toLocalTime().withSecond(0) in c.times() } }
            ?.let { Ref.At(it) }
        val said = follow.copy(target = target, args = follow.args.filter { (key, value) -> saidNow(key, value) && !(slot == "target" && value is Clock && Ref.At(value) == target) })
        return when (slot) {
            "time" -> if (said.op == "alarm.edit") said.with("at", Missing) else said.with("time", null)
            "label" -> said.with("label", Missing)
            "ampm" -> follow.with("day", follow.day().takeIf { mentionsDay() }).let { a -> a.clock()?.let { c -> a.with("time", Clock(c.hour % 12, c.minute)) } ?: a }
            "confirm" -> follow
            else -> said
        }
    }

    /** The action that later completes this question: in the branch that follows it, or in a later turn (reads are interruptions). */
    private fun followUp(option: Expect, upcoming: List<Turn>, domain: String?): Action? {
        val turns = option.then ?: upcoming
        val lines = turns.flatMap { t -> t.options.flatMap { o -> o.actions.map { (it as OracleAction.Line).text } } }
        val line = lines.firstOrNull { l -> (domain == null || translateDomain(l) == domain) && l.substringBefore(' ') !in READS } ?: return null
        return runCatching { translate(line) }.getOrNull()
    }

    private fun translateDomain(line: String) = when (val head = line.substringBefore(' ')) {
        "alarm.in" -> "timer"
        "list.clear", "list.get" -> "list"
        "calc" -> "calc"
        "conv" -> "conv"
        else -> head.substringBefore('.')
    }

    private fun keywordOp(): String? {
        val f = SpanishText.fold(utterance)
        return when {
            Regex("alarma|despiert|recuerdame").containsMatchIn(f) -> "alarm.add"
            Regex("temporizador|timer|avisame|cuenta atras").containsMatchIn(f) -> "timer.add"
            Regex("crono").containsMatchIn(f) -> "sw.add"
            Regex("lista|apunt|anot|anad|agreg|compra").containsMatchIn(f) -> "list.add"
            Regex("volumen|sonido").containsMatchIn(f) -> "vol.set"
            Regex("calcul|cuenta|cuanto es|cuanto son").containsMatchIn(f) -> "calc"
            Regex("que dia cae|dia cae").containsMatchIn(f) -> "time.weekday"
            Regex("faltan|quedan").containsMatchIn(f) -> "time.until"
            else -> null
        }
    }

    /** Whether this turn's phrase already says [value] (later turns fill the rest through the pending action). */
    private fun saidNow(key: String, value: Any): Boolean = when (value) {
        is Clock -> value.times().any { mentions(it) != null }
        is Day -> mentionsDay()
        is Long -> SpanishText.durations(normalized).any { it * 1000 == value }
        is String -> key == "unit" || SpanishText.mentionsPhrase(normalized, value)
        is Int, is Double -> SpanishText.numbers(normalized).any { it == (value as Number).toDouble() }
        else -> Regex("\\b(cada|todos los|diario|laborables|lunes a viernes|fin de semana|fines de semana)\\b").containsMatchIn(SpanishText.fold(utterance))
    }

    private fun topic(): String {
        val f = SpanishText.fold(utterance)
        return TOPICS.firstOrNull { it.first.containsMatchIn(f) }?.second ?: "otro"
    }

    private fun chat(): String {
        val f = SpanishText.fold(utterance)
        return CHATS.firstOrNull { it.first.containsMatchIn(f) }?.second ?: "Vale."
    }

    companion object {
        private val WAKE = Regex("\\b(despiert|levant|madrug)")
        private val READS = setOf("alarm.list", "timer.list", "timer.get", "sw.list", "sw.get", "list.get", "time.now", "vol.get")

        private val TOPICS = listOf(
            Regex("musica|cancion|rosalia|luis miguel|queen|radio|una de ") to "música",
            Regex("\\bllama\\b|llamada") to "llamadas",
            Regex("whatsapp|mensaje|audio|mandale") to "mensajes",
            Regex("tiempo va a hacer|clima") to "tiempo",
            Regex("internet|busca|correo") to "internet",
            Regex("\\bluz\\b|enciende") to "casa",
            Regex("youtube|\\babre\\b") to "apps",
            Regex("\\bfoto") to "fotos",
            Regex("calendario|\\bcita\\b") to "calendario",
            Regex("uber|pizza|pide|billete|cuesta") to "compras",
            Regex("transferencia|euros") to "pagos",
            Regex("brillo|modo avion|apaga el movil|movil de") to "móvil",
            Regex("noticias|telediario|novela|partido") to "noticias",
            Regex("ingles|traduc|como se dice") to "idiomas",
        )

        private val CHATS = listOf(
            Regex("como te llamas|tu nombre") to "Me llamo Dina.",
            Regex("eres una persona|eres humana") to "No, soy una asistente de voz.",
            Regex("quien te ha creado|quien te creo") to "Me hizo Kakauet, para este móvil.",
            Regex("buenas noches|me voy a dormir") to "¡Buenas noches! Que descanses.",
            Regex("\\bhola\\b|que tal|que onda|buenos dias") to "¡Hola! ¿En qué te ayudo?",
            Regex("gracias|genia|copada|amor|maja|\\bsol\\b|guapa") to "¡De nada! Aquí estoy.",
            Regex("chiste") to "¿Qué le dice un techo a otro? Techo de menos.",
            Regex("cansad") to "Pues a descansar un poco, que te lo mereces.",
            Regex("escuchas") to "Sí, te escucho.",
            Regex("despacio") to "Vale, intentaré hablar más despacio.",
            Regex("cumpleanos") to "¡Felicidades!",
            Regex("anos tienes") to "No tengo edad: soy una asistente.",
            Regex("musica") to "Me gusta, aunque no puedo ponerla.",
        )

        /** Turns whose contract output is not in the oracle: what a perfect model writes for them. */
        val OVERRIDES = mapOf(
            "err-01|Pausa el temporizador del horno." to "timer.pause(\"horno\")",
            "err-02|pausa el temporizador" to "timer.pause()",
            "err-03|Añade leche." to "list.add(\"leche\")",
            "err-04|Pon el volumen al ciento cincuenta." to "vol.set(150)",
            "err-05|Quita la alarma de las nueve." to "alarm.del(9:00)",
            "err-08|Para la alarma." to "stop()",
            "err-09|Pospón la alarma." to "alarm.snooze()",
            "err-10|reanuda el crono" to "sw.resume()",
            "err-11|Borra el cronómetro de lectura." to "sw.del(\"lectura\")",
            "err-12|Quita los huevos de la lista." to "list.del(\"huevos\")",
            "err-13|¿Cuánto le queda al temporizador?" to "timer.get()",
            "err-15|¿Cuánto es siete entre cero?" to "calc(\"7/0\")",
            "err-16|Pon una alarma para ayer a las ocho." to "alarm.add(8:00, day=ayer)",
            "err-17|Pon una alarma hoy a las siete de la mañana." to "alarm.add(7:00 mañana, day=hoy)",
            "err-19|Elimina el temporizador de la pasta." to "timer.del(\"pasta\")",
            "err-20|Cambia la alarma del gimnasio a las ocho." to "alarm.edit(\"gimnasio\", at=8:00)",
            "err-21|Sube el volumen." to "vol.up()",
            "err-22|baja el volumen" to "vol.down()",
            "err-23|Pon un temporizador de cero minutos." to "timer.add(0s)",
            "err-24|Quita la alarma de mañana." to "alarm.del(day=mañana)",
            "err-25|Bueno, déjalo." to "drop()",
            "err-27|Pausa el cronómetro de natación." to "sw.pause(\"natación\")",
            "err-29|¿Cuánto le falta a mi timer?" to "timer.get()",
            "err-30|ponme una alarma el 31 de febrero a las diez" to "alarm.add(10:00, day=31/2)",
            "err-31|Apunta leche y pan." to "list.add(\"leche\")\nlist.add(\"pan\")",
            "err-35|reanuda el temporizador" to "timer.resume()",
            "err-36|Borrá todas las alarmas." to "alarm.del(*)",
            "err-37|¿Cuánto le falta al timer?" to "timer.get()",
            "err-38|Agregá yerba." to "list.add(\"yerba\")",
            "fue-25|Apunta en mi calendario una cena el viernes." to "no(calendario)",
            "fue-08|No pongas ninguna alarma, que mañana libro." to "say(\"Vale, no pongo ninguna. ¡Disfruta del día libre!\")",
            "fue-33|No hace falta que pongas el temporizador, ya estoy pendiente." to "say(\"Vale, como quieras.\")",
            "cha-05|¿Qué cosas sabes hacer?" to "say(\"Puedo poner alarmas, temporizadores y cronómetros, apuntar la compra, cambiar el volumen y hacer cuentas.\")",
            "cha-11|¿Funcionas sin internet?" to "say(\"Sí, funciono sin internet: todo va en el móvil.\")",
            "cha-18|nada nada olvídalo" to "drop()",
            // The oracle writes the items back; a perfect model writes what was said (and never names products not said).
            "cor-18|ay no deshazlo" to "undo()",
            // Conversions set: answers that come from a conversion or a place the engine refuses.
            "cv-26|Pásame una taza de quinoa a gramos." to "conv(1, taza, g, \"quinoa\")",
            "cv-50|¿Cuántos litros son tres kilómetros?" to "conv(3, km, l)",
            "cv-51|Pasa cinco horas a metros." to "conv(5, h, m)",
            "cv-58|¿Qué hora es en Villaconejos de Abajo?" to "time.now(hora, \"Villaconejos de Abajo\")",
            "pen-10|no no déjalo como está" to "nope()",
            "pen-25|Nada, déjalo." to "drop()",
            "pen-33|Mejor ahorita no, olvídalo." to "drop()",
        )
    }
}
