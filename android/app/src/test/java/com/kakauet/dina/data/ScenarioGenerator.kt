package com.kakauet.dina.data

import com.kakauet.dina.dialog.Action
import com.kakauet.dina.dialog.Clock
import com.kakauet.dina.dialog.Day
import com.kakauet.dina.dialog.Ref
import com.kakauet.dina.dialog.Shift
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Random

/**
 * Samples scenarios from the coverage matrix (docs/datos.md): phenomenon × domain × state complexity,
 * each checked by replaying its canonical actions on the real engine (the expected brief must come out).
 */
class ScenarioGenerator(private val seed: Long, private val mix: String = BASE) {
    /** [mix]: the batch kind that samples it ([MIXES]); each mix has its own phenomena and templates. */
    class Template(val phenomenon: String, val domain: String, val name: String, val weight: Int, val mix: String = BASE, val build: Ctx.() -> List<TurnSpec>?)

    class Ctx(val rng: Random, val draft: Draft, val clock: LocalDateTime) {
        val today: LocalDate get() = clock.toLocalDate()
        fun <T> pick(values: List<T>): T = Words.pick(rng, values)
        fun chance(p: Double) = rng.nextDouble() < p

        fun alarmText(a: Draft.A) = (a.day?.let { Words.dayText(it, today) + " a " } ?: "") + Words.timeText(a.time) + (a.label?.let { " ($it)" } ?: "") + if (a.status == "off") ", desactivada" else ""
        fun alarmsText() = draft.alarms.joinToString("; ") { alarmText(it) }
        fun timerText(t: Draft.T) = "de " + Words.durationText(t.total) + (t.label?.let { " para $it" } ?: "") +
            when (t.status) { "paused" -> ", en pausa"; "ringing" -> ", sonando"; else -> ", en marcha" }
        fun timersText() = draft.timers.joinToString("; ") { timerText(it) }
        fun listText() = draft.shopping.joinToString(", ") { it.name + if (it.done) " (ya comprado)" else "" }
    }

    /** Fresh coverage stats: attempts and rejections per template, for the scenarios report. */
    val attempts = mutableMapOf<String, Int>()
    val rejected = mutableMapOf<String, Int>()

    fun generate(n: Int, prefix: String, devShare: Double = 0.1): List<Scenario> {
        val rng = Random(seed)
        val out = mutableListOf<Scenario>()
        var index = 0
        val phenomena = MIXES[mix] ?: error("mezcla desconocida: $mix (${MIXES.keys.joinToString()})")
        while (out.size < n) {
            val phenomenon = weighted(rng, phenomena)
            val template = weighted(rng, TEMPLATES.filter { it.mix == mix && it.phenomenon == phenomenon }.map { it to it.weight })
            val id = "%s-%05d".format(prefix, index++)
            val split = if (java.util.SplittableRandom(seed * 1_000_003 + index).nextDouble() < devShare) "dev" else "train"
            build(template, Random(rng.nextLong()), id, split)?.let { out += it }
        }
        return out
    }

    private fun build(template: Template, rng: Random, id: String, split: String): Scenario? {
        repeat(30) {
            attempts.merge(template.name, 1, Int::plus)
            val clock = clock(rng)
            val complexity = weighted(rng, COMPLEXITY)
            val draft = Draft(rng).apply { fill(template.domain, complexity) }
            val turns = template.build(Ctx(rng, draft, clock))
            if (turns != null) {
                val scenario = Scenario(id, template.phenomenon, template.domain, complexity, split, clock, draft.json(), turns)
                if (Replay.check(scenario)) return scenario
            }
            rejected.merge(template.name, 1, Int::plus)
        }
        return null
    }

    /** Any day of the year ahead, waking hours mostly, with edges (near midnight, DST changes, month ends). */
    private fun clock(rng: Random): LocalDateTime {
        val date = when (rng.nextInt(20)) {
            0 -> Words.pick(rng, listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25), LocalDate.of(2027, 3, 27), LocalDate.of(2027, 3, 28)))
            1 -> LocalDate.of(2026, 10 + rng.nextInt(3), 1).plusMonths(1).minusDays(1)
            else -> LocalDate.of(2026, 10, 1).plusDays(rng.nextInt(365).toLong())
        }
        val time = when (rng.nextInt(12)) {
            0 -> Words.pick(rng, listOf(LocalTime.of(23, 58), LocalTime.of(0, 5), LocalTime.of(23, 40), LocalTime.of(0, 30)))
            else -> LocalTime.of(6 + rng.nextInt(17), rng.nextInt(60))
        }
        return LocalDateTime.of(date, time)
    }

    companion object {
        const val BASE = "base"

        val PHENOMENA = listOf(
            "directas" to 18, "paráfrasis" to 8, "referencias" to 12, "falta dato" to 7, "pendiente" to 7, "correcciones" to 10,
            "multiacción" to 9, "interrupciones" to 7, "confirmaciones" to 4, "fuera de alcance" to 4, "errores" to 2, "charla" to 12,
        )
        val COMPLEXITY = listOf("vacío" to 30, "1-2" to 35, "varios" to 35)

        /** Batch kinds: `base` (Dina 4's batches), `refuerzo` ([FocusTemplates]) and `conversiones` ([ConversionTemplates]). */
        val MIXES: Map<String, List<Pair<String, Int>>> by lazy {
            mapOf(BASE to PHENOMENA, FocusTemplates.MIX to FocusTemplates.PHENOMENA, ConversionTemplates.MIX to ConversionTemplates.PHENOMENA)
        }

        fun <T> weighted(rng: Random, items: List<Pair<T, Int>>): T {
            var roll = rng.nextInt(items.sumOf { it.second })
            for ((item, weight) in items) { roll -= weight; if (roll < 0) return item }
            return items.last().first
        }

        fun act(op: String, target: Ref? = null, vararg args: Pair<String, Any?>) =
            Action(op, target, args.mapNotNull { (k, v) -> v?.let { k to it } }.toMap())

        /** "a" + "el temporizador…" → "al temporizador…". */
        internal fun al(which: String) = if (which.startsWith("el ")) "al " + which.removePrefix("el ") else "a $which"

        internal fun Ctx.periodClock(time: LocalTime) = Clock(if (time.hour % 12 == 0) 12 else time.hour % 12, time.minute, if (time.hour == 12) null else Words.period(time))
        internal fun Ctx.otherTime(time: LocalTime) = Words.alarmTime(rng, Words.period(time).word).takeIf { it != time } ?: time.plusMinutes(30)

        /** An alarm the user can name unambiguously: by label if unique, else by its time. */
        internal fun Ctx.alarmRef(a: Draft.A): Pair<Ref?, String> = when {
            draft.alarms.size == 1 -> null to "la alarma (es la única)"
            a.label != null && draft.alarms.count { it.label == a.label } == 1 && chance(0.6) -> Ref.Named(a.label) to "la alarma de ${a.label} (menciónala por su motivo, no por la hora)"
            else -> Ref.At(periodClock(a.time)) to "la alarma de ${Words.timeText(a.time)} (menciónala por la hora)"
        }

        internal fun Ctx.timerRef(t: Draft.T): Pair<Ref?, String>? = when {
            draft.timers.size == 1 -> null to "el temporizador ${timerText(t)} (es el único)"
            t.label != null -> Ref.Named(t.label) to "el temporizador de ${t.label} (menciónalo por su nombre)"
            else -> null
        }

        /** Out-of-scope topics (the time of other places is in scope since Dina 4.5). */
        internal val TOPICS = listOf(
            "música" to "Quieres que Dina ponga música (%s).", "llamadas" to "Quieres llamar por teléfono a %s.",
            "mensajes" to "Quieres mandar un mensaje a %s diciendo que llegas tarde.", "internet" to "Quieres que Dina busque en internet una receta de %s.",
            "tiempo" to "Quieres saber qué tiempo va a hacer %s.", "casa" to "Quieres %s.", "apps" to "Quieres abrir %s en el móvil.",
            "fotos" to "Quieres %s.", "calendario" to "Quieres apuntar en el calendario una cita %s.", "compras" to "Quieres %s.",
            "pagos" to "Quieres hacer un Bizum de %s.", "móvil" to "Quieres %s en el móvil.", "noticias" to "Quieres escuchar %s.",
            "idiomas" to "Quieres saber cómo se dice %s en inglés.", "otro" to "Quieres %s.",
        )
        internal val TOPIC_FILLS = mapOf(
            "música" to listOf("algo de Rosalía", "la radio", "una canción para dormir", "rock de los ochenta"),
            "llamadas" to listOf("tu hermana", "tu madre", "Javi", "el fontanero"),
            "mensajes" to listOf("tu pareja", "Lucía", "tu jefe", "el grupo de la familia"),
            "internet" to listOf("paella", "tortilla de patatas", "croquetas", "gazpacho"),
            "tiempo" to listOf("mañana", "el fin de semana", "esta tarde", "hoy en Madrid"),
            "casa" to listOf("encender la luz del salón", "apagar la calefacción", "subir las persianas", "poner el aire acondicionado"),
            "apps" to listOf("WhatsApp", "Instagram", "el mapa", "la cámara"),
            "fotos" to listOf("hacer una foto", "ver las fotos de ayer", "mandar una foto"),
            "calendario" to listOf("para el jueves", "con el dentista el día 12", "el cumpleaños de tu madre"),
            "compras" to listOf("pedir una pizza a domicilio", "comprar unas zapatillas por internet", "pedir la compra al súper"),
            "pagos" to listOf("20 euros a tu hermano", "15 euros a Marta", "50 euros a tu madre"),
            "móvil" to listOf("activar el wifi", "poner el modo avión", "subir el brillo de la pantalla", "activar el bluetooth"),
            "noticias" to listOf("las noticias de hoy", "los resultados del fútbol", "las noticias de economía"),
            "idiomas" to listOf("«buenos días»", "«¿dónde está la estación?»", "«gracias por todo»"),
            "otro" to listOf("pedir un taxi", "jugar a un juego", "que te recomiende una película", "reservar mesa en un restaurante"),
        )
        /**
         * Chat without a request, by kind and weight. Dina's answer is not here: the
         * generator writes one per phrase with her personality sheet ([TurnSpec.reply]); the placeholder
         * `say` only lets the scenario replay.
         */
        private val CHAT = listOf(
            3 to listOf(
                "Saludas a Dina, sin pedirle nada.", "Le das los buenos días a Dina nada más levantarte.",
                "Saludas a Dina al llegar a casa por la noche.", "Saludas a Dina con mucha energía, de buen humor.",
            ),
            2 to listOf("Le das las gracias a Dina.", "Le dices a Dina que te ha salvado el día.", "Le dices a Dina que es muy útil."),
            1 to listOf("Te despides de Dina antes de irte a dormir.", "Te despides de Dina porque te vas a trabajar.", "Te despides de Dina hasta mañana."),
            4 to listOf(
                "Le cuentas a Dina que estás cansado o cansada después de un día largo.", "Le dices a Dina que estás agobiado o agobiada con el trabajo.",
                "Le cuentas a Dina que mañana tienes un examen y estás nervioso o nerviosa.", "Le dices a Dina que hoy no tienes un buen día.",
                "Le cuentas a Dina, muy contento o contenta, que te han dado el trabajo que querías.", "Le dices a Dina que no consigues dormirte.",
                "Le dices a Dina que te aburres.", "Le cuentas a Dina que hoy has salido a correr por primera vez en meses.",
            ),
            4 to listOf(
                "Le preguntas a Dina quién es.", "Le preguntas a Dina qué tal está.", "Le preguntas a Dina cuántos años tiene.",
                "Le preguntas a Dina si es una persona o una máquina.", "Le preguntas a Dina si necesita internet para funcionar.",
                "Le preguntas a Dina si te está escuchando todo el rato.", "Le preguntas a Dina si duerme alguna vez.",
                "Le preguntas a Dina si tiene sentimientos.", "Le preguntas a Dina si te escucha.",
            ),
            3 to listOf(
                "Le preguntas a Dina qué sabe hacer.", "Le preguntas a Dina en qué te puede ayudar.",
                "Le preguntas a Dina si sabe hacer algo más aparte de poner alarmas.", "Es la primera vez que usas a Dina y le preguntas cómo funciona.",
            ),
            2 to listOf(
                "Le preguntas a Dina si prefiere el café o el té.", "Le preguntas a Dina qué opina de los lunes.",
                "Le preguntas a Dina si la tortilla de patatas es mejor con cebolla o sin cebolla.", "Le preguntas a Dina si es más de playa o de montaña.",
                "Le preguntas a Dina cuál es su comida favorita.", "Le preguntas a Dina qué estación del año le gusta más.",
            ),
            2 to listOf(
                "Por curiosidad, le preguntas a Dina por qué el cielo es azul.", "Por curiosidad, le preguntas a Dina cuánto mide la Torre Eiffel.",
                "Por curiosidad, le preguntas a Dina si los peces duermen.", "Por curiosidad, le preguntas a Dina cuál es el animal más rápido del mundo.",
                "Le preguntas a Dina qué haría ella si fuera una persona.", "Le preguntas a Dina si sueña.",
            ),
            2 to listOf(
                "Le preguntas a Dina, en broma, si se casaría contigo.", "Le pides a Dina, de broma, que te haga la cena.",
                "Le dices a Dina, de broma, que es la mejor asistente del mundo.", "Le preguntas a Dina, en broma, si le cae bien Alexa.",
                "Le echas la culpa a Dina, en broma, de haberte despertado demasiado pronto.", "Le preguntas a Dina, de broma, si puede ir a trabajar por ti.",
            ),
        )
        private val FUN_ASKS = listOf(
            "joke" to listOf("Le pides a Dina que te cuente un chiste.", "Le pides a Dina que te haga reír un poco.", "Le pides a Dina un chiste malo."),
            "fact" to listOf("Le pides a Dina que te cuente una curiosidad.", "Le pides a Dina que te diga algo que no sepas.", "Le pides a Dina un dato curioso."),
        )

        /** Personal remarks that invite a coletilla after the action ("¡suerte en el médico!"): label to remark. */
        private val REMARKS = mapOf(
            "alarm" to listOf(
                "médico" to "tienes cita en el médico y estás un poco nervioso o nerviosa", "examen" to "tienes un examen importante y llevas días estudiando",
                "entrevista" to "tienes una entrevista de trabajo", "aeropuerto" to "te vas de vacaciones y te hace mucha ilusión",
                "dentista" to "te toca el dentista y no te apetece nada", "partido" to "juegas la final del torneo de pádel",
                "cumpleaños" to "es el cumpleaños de tu madre y quieres felicitarla la primera", "gimnasio" to "has empezado a ir al gimnasio y te está costando",
                "boda" to "es la boda de tu mejor amiga", "trabajo nuevo" to "empiezas en un trabajo nuevo",
            ),
            "timer" to listOf(
                "bizcocho" to "es la primera vez que haces un bizcocho", "pizza" to "has invitado a unos amigos a cenar pizza",
                "siesta" to "estás agotado o agotada", "lentejas" to "son las lentejas de la receta de tu abuela",
                "pan" to "estás aprendiendo a hacer pan en casa", "estudio" to "vas a estudiar y te cuesta concentrarte",
                "meditación" to "estás intentando relajarte un rato", "galletas" to "haces galletas con tus sobrinos",
            ),
            "list" to listOf(
                "velas" to "es el cumpleaños de tu hija", "cava" to "celebráis que te han ascendido", "helado" to "hace un calor horrible",
                "chocolate" to "has tenido un día malo", "pañales" to "acabas de tener un bebé", "pienso" to "tenéis un perro nuevo en casa",
                "flores" to "es vuestro aniversario",
            ),
        )
        internal val SAY = act("say", null, "text" to "¡Claro!")

        val TEMPLATES: List<Template> by lazy { BASE_TEMPLATES + FocusTemplates.TEMPLATES + ConversionTemplates.TEMPLATES }

        private val BASE_TEMPLATES: List<Template> = listOf(
            // ---------------------------------------------------------------- directas
            Template("directas", "alarm", "alarm.add", 5) {
                val (clock, said) = Words.spoken(rng, Words.alarmTime(rng))
                val repeat = if (chance(0.2)) pick(Words.REPEATS) else null
                val (day, dayText) = if (repeat == null) Words.day(rng, today) else null to null
                val label = if (chance(0.5)) Words.alarmLabel(rng) else null
                listOf(TurnSpec(
                    "Quieres una alarma" + (dayText?.let { " para $it" } ?: "") + (repeat?.let { " que suene ${it.second}" } ?: "") + " a $said." + (label?.let { " Es para ${it.second}." } ?: ""),
                    listOf(act("alarm.add", null, "time" to clock, "day" to day, "repeat" to repeat?.first, "label" to label?.first)),
                ))
            },
            Template("directas", "alarm", "alarm.ops", 3) {
                if (draft.alarms.isEmpty()) draft.addAlarm()
                val a = pick(draft.alarms)
                val (ref, which) = alarmRef(a)
                when (rng.nextInt(6)) {
                    0 -> listOf(TurnSpec("Quieres saber qué alarmas tienes puestas.", listOf(act("alarm.list")), ANSWERED))
                    1 -> listOf(TurnSpec("Quieres saber a qué hora suena tu próxima alarma.", listOf(act("alarm.get", Ref.Next)), ANSWERED))
                    2 -> listOf(TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres quitar $which.", listOf(act("alarm.del", ref))))
                    3 -> if (a.status == "off") null else listOf(TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres desactivar $which, sin borrarla.", listOf(act("alarm.off", ref))))
                    4 -> {
                        val off = draft.addAlarm(status = "off")
                        val (offRef, offWhich) = alarmRef(off)
                        listOf(TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres volver a activar $offWhich.", listOf(act("alarm.on", offRef))))
                    }
                    else -> {
                        val time = otherTime(a.time)
                        listOf(TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres cambiar $which para que suene a ${Words.timeText(time)}.", listOf(act("alarm.edit", ref, "at" to periodClock(time)))))
                    }
                }
            },
            Template("directas", "timer", "timer.add", 4) {
                val dur = Words.duration(rng)
                val label = if (chance(0.6)) Words.timerLabel(rng) else null
                listOf(TurnSpec("Quieres un temporizador de ${Words.durationSaid(rng, dur)}" + (label?.let { " para ${it.second}" } ?: "") + ".", listOf(act("timer.add", null, "dur" to dur, "label" to label?.first))))
            },
            Template("directas", "timer", "timer.ops", 3) {
                if (draft.timers.isEmpty()) draft.addTimer(status = if (chance(0.3)) "paused" else null)
                val t = pick(draft.timers.filter { it.status != "ringing" }.ifEmpty { return@Template null })
                val (ref, which) = timerRef(t) ?: return@Template null
                val situation = if (draft.timers.size > 1) "Tienes estos temporizadores: ${timersText()}. " else ""
                val extra = 60_000L * pick(listOf(1, 2, 3, 5, 10))
                when (rng.nextInt(5)) {
                    0 -> if (t.status == "paused") listOf(TurnSpec("${situation}Quieres que siga $which.", listOf(act("timer.resume", ref))))
                    else listOf(TurnSpec("${situation}Quieres pausar $which.", listOf(act("timer.pause", ref))))
                    1 -> listOf(TurnSpec("${situation}Quieres quitar $which.", listOf(act("timer.del", ref))))
                    2 -> listOf(TurnSpec("${situation}Quieres saber cuánto le queda ${al(which)}.", listOf(act("timer.get", ref)), ANSWERED))
                    3 -> listOf(TurnSpec("${situation}Quieres añadirle ${Words.durationText(extra)} más ${al(which)}.", listOf(act("timer.plus", ref, "dur" to extra))))
                    else -> if (t.left <= extra + 30_000) null else listOf(TurnSpec("${situation}Quieres quitarle ${Words.durationText(extra)} ${al(which)}.", listOf(act("timer.minus", ref, "dur" to extra))))
                }
            },
            Template("directas", "sw", "sw", 2) {
                if (draft.stopwatches.isEmpty() || chance(0.4)) {
                    draft.stopwatches.clear()
                    val label = if (chance(0.3)) pick(Words.STOPWATCH_LABELS) else null
                    listOf(TurnSpec("Quieres poner en marcha un cronómetro" + (label?.let { " para la $it" } ?: "") + ".", listOf(act("sw.add", null, "label" to label))))
                } else {
                    while (draft.stopwatches.size > 1) draft.stopwatches.removeAt(0)
                    val s = draft.stopwatches.single()
                    when (rng.nextInt(4)) {
                        0 -> listOf(TurnSpec("Tienes un cronómetro en marcha. Quieres saber cuánto tiempo lleva.", listOf(act("sw.get")), ANSWERED))
                        1 -> if (s.paused) listOf(TurnSpec("Tienes un cronómetro parado. Quieres que siga contando.", listOf(act("sw.resume"))))
                        else listOf(TurnSpec("Tienes un cronómetro en marcha. Quieres pararlo un momento.", listOf(act("sw.pause"))))
                        2 -> listOf(TurnSpec("Tienes un cronómetro. Quieres ponerlo a cero.", listOf(act("sw.reset"))))
                        else -> listOf(TurnSpec("Tienes un cronómetro. Quieres quitarlo.", listOf(act("sw.del"))))
                    }
                }
            },
            Template("directas", "list", "list.add", 3) {
                val p = Words.product(rng, draft.shopping.map { it.name }, quantity = chance(0.35))
                listOf(TurnSpec("Quieres apuntar ${p.said} en la lista de la compra.", listOf(act("list.add", null, "name" to p.name, "n" to p.n, "unit" to p.unit))))
            },
            Template("directas", "list", "list.ops", 2) {
                if (draft.shopping.isEmpty()) repeat(1 + rng.nextInt(3)) { draft.addItem() }
                val item = pick(draft.shopping)
                when (rng.nextInt(4)) {
                    0 -> listOf(TurnSpec("Quieres saber qué tienes apuntado en la lista de la compra.", listOf(act("list.list")), ANSWERED))
                    1 -> listOf(TurnSpec("Tienes en la lista: ${listText()}. Quieres quitar ${item.name} de la lista.", listOf(act("list.del", Ref.Named(item.name)))))
                    2 -> if (item.done) listOf(TurnSpec("Tienes en la lista: ${listText()}. Al final no has comprado ${item.name}: quieres desmarcarlo.", listOf(act("list.uncheck", Ref.Named(item.name)))))
                    else listOf(TurnSpec("Tienes en la lista: ${listText()}. Ya has cogido ${item.name}: quieres marcarlo como comprado.", listOf(act("list.check", Ref.Named(item.name)))))
                    else -> {
                        val n = 2.0 + rng.nextInt(5)
                        listOf(TurnSpec("Tienes ${item.name} en la lista. Quieres que pongan ${Words.number(n)} de cantidad.", listOf(act("list.edit", Ref.Named(item.name), "n" to n))))
                    }
                }
            },
            Template("directas", "vol", "vol", 4) {
                draft.muted = false
                when (rng.nextInt(7)) {
                    6 -> if (draft.volume == 100) null else listOf(TurnSpec("Quieres el volumen de Dina al máximo.", listOf(act("vol.set", null, "n" to 100))))
                    0 -> { val n = 10 * (1 + rng.nextInt(10)); if (n == draft.volume) null else listOf(TurnSpec("Quieres el volumen de Dina al $n.", listOf(act("vol.set", null, "n" to n)))) }
                    1 -> { draft.volume = minOf(draft.volume, 80); listOf(TurnSpec("Quieres que Dina suba el volumen (no dices cuánto).", listOf(act("vol.up")))) }
                    2 -> { draft.volume = maxOf(draft.volume, 40); listOf(TurnSpec("Quieres bajar el volumen 20 puntos.", listOf(act("vol.down", null, "n" to 20)))) }
                    3 -> listOf(TurnSpec("Quieres silenciar a Dina.", listOf(act("vol.mute"))))
                    4 -> { draft.muted = true; listOf(TurnSpec("Dina está en silencio y quieres volver a oírla.", listOf(act("vol.unmute")))) }
                    else -> listOf(TurnSpec("Quieres saber a cuánto está el volumen.", listOf(act("vol.get")), ANSWERED))
                }
            },
            Template("directas", "time", "time", 3) {
                when (rng.nextInt(7)) {
                    0, 1 -> listOf(TurnSpec("Quieres saber qué hora es.", listOf(act("time.now", null, "part" to "hora")), ANSWERED))
                    2 -> listOf(TurnSpec("Quieres saber la fecha de hoy.", listOf(act("time.now", null, "part" to "fecha")), ANSWERED))
                    3 -> listOf(TurnSpec("Quieres saber qué día de la semana es hoy.", listOf(act("time.now", null, "part" to "dia")), ANSWERED))
                    4 -> {
                        val date = today.plusDays(3L + rng.nextInt(200))
                        listOf(TurnSpec("Quieres saber qué día de la semana cae el ${date.dayOfMonth} de ${Words.MONTHS[date.monthValue - 1]}.", listOf(act("time.weekday", null, "day" to Day.Date(date.dayOfMonth, date.monthValue))), ANSWERED))
                    }
                    5 -> {
                        val (day, text) = if (chance(0.4)) pick(Words.HOLIDAYS).let { (d, m, name) -> Day.Date(d, m) to "$name (el $d de ${Words.MONTHS[m - 1]})" }
                        else Words.day(rng, today, allowNone = false)
                        if (chance(0.5)) listOf(TurnSpec("Quieres saber cuántos días faltan para $text.", listOf(act("time.until", null, "day" to day)), ANSWERED))
                        else if (day is Day.Date) listOf(TurnSpec("Quieres saber qué día de la semana cae $text.", listOf(act("time.weekday", null, "day" to day)), ANSWERED))
                        else listOf(TurnSpec("Quieres saber cuántos días faltan para $text.", listOf(act("time.until", null, "day" to day)), ANSWERED))
                    }
                    else -> {
                        val a = 2 + rng.nextInt(98)
                        val b = 2 + rng.nextInt(30)
                        val (expr, text) = pick(listOf("$a*$b" to "$a por $b", "$a+$b" to "$a más $b", "$a-$b" to "$a menos $b", "${a * b}/$b" to "${a * b} entre $b", "sqrt(${b * b})" to "la raíz cuadrada de ${b * b}", "${pick(listOf(10, 15, 20, 25, 50))}*${b * 4}/100".let { it to "el ${it.substringBefore('*')} por ciento de ${b * 4}" }))
                        listOf(TurnSpec("Quieres saber cuánto es $text.", listOf(act("calc", null, "expr" to expr)), ANSWERED))
                    }
                }
            },
            // ---------------------------------------------------------------- paráfrasis
            Template("paráfrasis", "alarm", "wake", 3) {
                val time = Words.alarmTime(rng, "mañana")
                val (_, said) = Words.spoken(rng, time)
                val (day, dayText) = Words.day(rng, today)
                listOf(TurnSpec(
                    "Quieres que Dina te despierte" + (dayText?.let { " $it" } ?: "") + " a $said. Restricción: no digas la palabra «alarma».",
                    // Waking up is the morning: the model writes the part of day even when it is not said (contrato.md).
                    listOf(act("alarm.add", null, "time" to periodClock(time), "day" to day)),
                ))
            },
            Template("paráfrasis", "timer", "remind", 3) {
                val dur = Words.duration(rng)
                val label = if (chance(0.5)) Words.timerLabel(rng) else null
                listOf(TurnSpec(
                    "Quieres que Dina te avise dentro de ${Words.durationText(dur)}" + (label?.let { " para ${it.second}" } ?: "") + ". Restricción: no digas «temporizador».",
                    listOf(act("timer.add", null, "dur" to dur, "label" to label?.first)),
                ))
            },
            Template("paráfrasis", "list", "remember", 2) {
                val p = Words.product(rng, draft.shopping.map { it.name })
                listOf(TurnSpec("Quieres que no se te olvide comprar ${p.said} (sin decir cuántos). Restricción: no digas «lista» ni «apunta».", listOf(act("list.add", null, "name" to p.name))))
            },
            Template("paráfrasis", "vol", "loudness", 1) {
                draft.muted = false
                if (chance(0.5)) { draft.volume = minOf(draft.volume, 70); listOf(TurnSpec("No oyes bien a Dina y quieres que hable más alto. Restricción: no digas «volumen» ni «sube».", listOf(act("vol.up")))) }
                else { draft.volume = maxOf(draft.volume, 40); listOf(TurnSpec("Dina habla demasiado alto y te molesta. Restricción: no digas «volumen» ni «baja».", listOf(act("vol.down")))) }
            },
            Template("paráfrasis", "timer", "left", 1) {
                draft.timers.clear()
                val label = Words.timerLabel(rng)
                draft.addTimer(label = label.first)
                listOf(TurnSpec("Tienes un temporizador puesto para ${label.second}. Quieres saber si le queda mucho. Restricción: no digas «temporizador».", listOf(act("timer.get")), ANSWERED))
            },
            // ---------------------------------------------------------------- referencias
            Template("referencias", "alarm", "period", 3) {
                draft.alarms.clear()
                val morning = draft.addAlarm(Words.alarmTime(rng, "mañana"))
                val afternoon = draft.addAlarm(Words.alarmTime(rng, pick(listOf("tarde", "noche"))))
                val target = pick(listOf(morning, afternoon))
                val period = Words.period(target.time)
                val (op, verb) = pick(listOf("alarm.del" to "quitar", "alarm.off" to "desactivar", "alarm.get" to "saber a qué hora suena"))
                listOf(TurnSpec(
                    "Tienes dos alarmas: ${alarmsText()}. Quieres $verb la ${period.word.let { if (it == "mañana") "de la mañana" else "de la $it" }}. Refiérete a ella por la parte del día, sin decir la hora ni el motivo.",
                    listOf(act(op, Ref.InPeriod(period))), if (op == "alarm.get") ANSWERED else DONE,
                ))
            },
            Template("referencias", "alarm", "next", 2) {
                if (draft.alarms.size < 2) { draft.addAlarm(); draft.addAlarm() }
                if (chance(0.5)) listOf(TurnSpec("Tienes varias alarmas. Quieres quitar la próxima que va a sonar, sin decir su hora.", listOf(act("alarm.del", Ref.Next))))
                else listOf(TurnSpec("Tienes varias alarmas. Quieres desactivar la próxima que va a sonar, sin decir su hora.", listOf(act("alarm.off", Ref.Next))))
            },
            Template("referencias", "alarm", "ordinal", 2) {
                while (draft.alarms.size < 3) draft.addAlarm()
                val n = 1 + rng.nextInt(draft.alarms.size)
                listOf(
                    TurnSpec("Quieres que Dina te diga qué alarmas tienes.", listOf(act("alarm.list")), ANSWERED),
                    TurnSpec("Dina te ha leído tus alarmas. Quieres quitar la ${Words.ordinal(n)} que ha dicho; refiérete a ella por su posición.", listOf(act("alarm.del", Ref.Nth(n)))),
                )
            },
            Template("referencias", "list", "ordinal", 2) {
                while (draft.shopping.size < 3) draft.addItem(done = false)
                val n = 1 + rng.nextInt(minOf(draft.shopping.size, 5))
                listOf(
                    TurnSpec("Quieres que Dina te lea la lista de la compra.", listOf(act("list.list")), ANSWERED),
                    TurnSpec("Dina te ha leído la lista. Quieres quitar el ${listOf("primero", "segundo", "tercero", "cuarto", "quinto")[n - 1]} que ha dicho; refiérete a él por su posición.", listOf(act("list.del", Ref.Nth(n)))),
                )
            },
            Template("referencias", "alarm", "focus", 3) {
                val time = Words.alarmTime(rng)
                val clock = periodClock(time)
                val label = if (chance(0.5)) Words.alarmLabel(rng) else null
                val first = TurnSpec("Quieres una alarma a ${Words.timeText(time)}" + (label?.let { " para ${it.second}" } ?: "") + ".", listOf(act("alarm.add", null, "time" to clock, "label" to label?.first)))
                val second = when (rng.nextInt(3)) {
                    0 -> otherTime(time).let { TurnSpec("Ahora quieres cambiar esa alarma a ${Words.timeText(it)}. Refiérete a ella sin repetir cuál es.", listOf(act("alarm.edit", Ref.Focus, "at" to periodClock(it)))) }
                    1 -> TurnSpec("Al final quieres quitar esa alarma. Refiérete a ella sin repetir cuál es.", listOf(act("alarm.del", Ref.Focus)))
                    else -> TurnSpec("Quieres que esa alarma se repita de lunes a viernes. Refiérete a ella sin repetir cuál es.", listOf(act("alarm.edit", Ref.Focus, "repeat" to com.kakauet.dina.tools.Repeat.Weekdays)))
                }
                listOf(first, second)
            },
            Template("referencias", "timer", "focus", 2) {
                val dur = Words.duration(rng)
                val label = if (chance(0.5)) Words.timerLabel(rng) else null
                val first = TurnSpec("Quieres un temporizador de ${Words.durationText(dur)}" + (label?.let { " para ${it.second}" } ?: "") + ".", listOf(act("timer.add", null, "dur" to dur, "label" to label?.first)))
                val extra = 60_000L * pick(listOf(2, 5, 10))
                val second = if (chance(0.5)) TurnSpec("Quieres añadirle ${Words.durationText(extra)} más a ese temporizador, sin repetir cuál es.", listOf(act("timer.plus", Ref.Focus, "dur" to extra)))
                else TurnSpec("Quieres pausar ese temporizador, sin repetir cuál es.", listOf(act("timer.pause", Ref.Focus)))
                listOf(first, second)
            },
            Template("referencias", "list", "focus", 1) {
                val p = Words.product(rng, draft.shopping.map { it.name })
                val n = 2.0 + rng.nextInt(5)
                listOf(
                    TurnSpec("Quieres apuntar ${p.said} en la lista.", listOf(act("list.add", null, "name" to p.name))),
                    TurnSpec("Quieres que de eso que acabas de apuntar sean ${Words.number(n)}, sin repetir el producto.", listOf(act("list.edit", Ref.Focus, "n" to n))),
                )
            },
            Template("referencias", "timer", "all", 2) {
                while (draft.timers.size < 2) draft.addTimer()
                if (chance(0.5)) listOf(TurnSpec("Tienes varios temporizadores: ${timersText()}. Quieres quitarlos todos.", listOf(act("timer.del", Ref.All))))
                else { draft.timers.replaceAll { Draft.T(it.total, it.label, it.left, null) }; listOf(TurnSpec("Tienes varios temporizadores en marcha. Quieres pausarlos todos.", listOf(act("timer.pause", Ref.All)))) }
            },
            Template("referencias", "alarm", "all", 1) {
                while (draft.alarms.size < 2) draft.addAlarm()
                draft.alarms.replaceAll { Draft.A(it.time, it.label, it.repeat, null) }
                listOf(TurnSpec("Te vas de vacaciones y quieres desactivar todas tus alarmas (${draft.alarms.size}).", listOf(act("alarm.off", Ref.All))))
            },
            Template("referencias", "timer", "label", 2) {
                while (draft.timers.size < 2) draft.addTimer(label = Words.timerLabel(rng).first)
                val t = pick(draft.timers.filter { it.label != null && it.status != "ringing" }.ifEmpty { return@Template null })
                listOf(TurnSpec("Tienes estos temporizadores: ${timersText()}. Quieres saber cuánto le queda al de ${t.label}.", listOf(act("timer.get", Ref.Named(t.label!!))), ANSWERED))
            },
            // ---------------------------------------------------------------- falta dato
            Template("falta dato", "alarm", "no-time", 3) {
                val (day, dayText) = Words.day(rng, today)
                val label = if (chance(0.4)) Words.alarmLabel(rng) else null
                val first = TurnSpec(
                    "Quieres una alarma" + (dayText?.let { " para $it" } ?: "") + (label?.let { " para ${it.second}" } ?: "") + ", pero no dices la hora.",
                    listOf(act("alarm.add", null, "day" to day, "label" to label?.first)), brief("falta hora"),
                )
                if (chance(0.4)) return@Template listOf(first)
                val (clock, said) = Words.spoken(rng, Words.alarmTime(rng), ambiguous = false)
                listOf(first, TurnSpec("Dina te ha preguntado a qué hora. Contestas: $said.", listOf(act("alarm.add", null, "time" to clock))))
            },
            Template("falta dato", "timer", "no-duration", 3) {
                val label = if (chance(0.5)) Words.timerLabel(rng) else null
                val first = TurnSpec("Quieres un temporizador" + (label?.let { " para ${it.second}" } ?: "") + ", pero no dices de cuánto tiempo.", listOf(act("timer.add", null, "label" to label?.first)), brief("falta duración"))
                if (chance(0.4)) return@Template listOf(first)
                val dur = Words.duration(rng)
                listOf(first, TurnSpec("Dina te ha preguntado cuánto tiempo. Contestas: ${Words.durationText(dur)}.", listOf(act("timer.add", null, "dur" to dur))))
            },
            Template("falta dato", "list", "no-product", 1) {
                val first = TurnSpec("Quieres apuntar algo en la lista de la compra, pero no dices qué (la frase se queda en eso).", listOf(act("list.add")), brief("falta producto"))
                val p = Words.product(rng, draft.shopping.map { it.name })
                listOf(first, TurnSpec("Dina te ha preguntado qué apunta. Contestas: ${p.said}.", listOf(act("list.add", null, "name" to p.name))))
            },
            Template("falta dato", "alarm", "edit-what", 1) {
                draft.alarms.clear()
                val a = draft.addAlarm(label = Words.alarmLabel(rng).first)
                val time = otherTime(a.time)
                listOf(
                    TurnSpec("Tienes una alarma: ${alarmText(a)}. Quieres cambiarla, pero no dices qué ni a qué hora.", listOf(act("alarm.edit")), ASKS),
                    TurnSpec("Dina te ha preguntado qué quieres cambiar. Contestas que la quieres a ${Words.timeText(time)}.", listOf(act("alarm.edit", null, "at" to periodClock(time)))),
                )
            },
            // ---------------------------------------------------------------- pendiente
            Template("pendiente", "alarm", "which", 3) {
                while (draft.alarms.size < 2) draft.addAlarm()
                val a = pick(draft.alarms)
                val (op, verb) = pick(listOf("alarm.del" to "quitar", "alarm.get" to "saber cuándo suena"))
                val ref = if (a.label != null && draft.alarms.count { it.label == a.label } == 1 && chance(0.5)) Ref.Named(a.label) to "por su motivo (${a.label})"
                else Ref.At(periodClock(a.time)) to "por la hora (${Words.timeText(a.time)})"
                listOf(
                    TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres $verb una alarma, pero no dices cuál.", listOf(act(op)), brief("¿cuál?")),
                    TurnSpec("Dina te pregunta cuál. Eliges una, refiriéndote a ella ${ref.second}.", listOf(act(op, ref.first)), if (op == "alarm.get") ANSWERED else DONE),
                )
            },
            Template("pendiente", "timer", "timer-which", 2) {
                while (draft.timers.count { it.label != null } < 2) draft.addTimer(label = Words.timerLabel(rng).first)
                draft.timers.replaceAll { Draft.T(it.total, it.label, it.left, null) }
                val t = pick(draft.timers.filter { it.label != null })
                listOf(
                    TurnSpec("Tienes estos temporizadores: ${timersText()}. Quieres pausar uno, pero no dices cuál.", listOf(act("timer.pause")), brief("¿cuál?")),
                    TurnSpec("Dina te pregunta cuál. Eliges el de ${t.label}.", listOf(act("timer.pause", Ref.Named(t.label!!)))),
                )
            },
            Template("pendiente", "timer", "interrupted", 2) {
                val dur = Words.duration(rng)
                listOf(
                    TurnSpec("Quieres un temporizador, pero no dices de cuánto.", listOf(act("timer.add")), brief("falta duración")),
                    TurnSpec("Antes de contestar a Dina, le preguntas qué hora es.", listOf(act("time.now", null, "part" to "hora")), ANSWERED),
                    TurnSpec("Dina te ha recordado lo del temporizador. Contestas: ${Words.durationText(dur)}.", listOf(act("timer.add", null, "dur" to dur))),
                )
            },
            Template("pendiente", "list", "interrupted", 1) {
                val p = Words.product(rng, draft.shopping.map { it.name })
                listOf(
                    TurnSpec("Quieres una alarma, pero no dices la hora.", listOf(act("alarm.add")), brief("falta hora")),
                    TurnSpec("En vez de contestar a Dina, le pides que apunte ${p.said} en la lista.", listOf(act("list.add", null, "name" to p.name))),
                )
            },
            // ---------------------------------------------------------------- correcciones
            Template("correcciones", "alarm", "same-turn", 2) {
                val right = Words.alarmTime(rng)
                val wrong = otherTime(right)
                listOf(TurnSpec(
                    "Quieres una alarma. Empiezas diciendo ${Words.timeText(wrong)} y en la misma frase te corriges: la buena es ${Words.timeText(right)}.",
                    listOf(act("alarm.add", null, "time" to periodClock(right))),
                ))
            },
            Template("correcciones", "timer", "same-turn", 1) {
                val right = Words.duration(rng)
                val wrong = right + 60_000L * pick(listOf(1, 2, 5, 10))
                listOf(TurnSpec("Quieres un temporizador. Dices ${Words.durationText(wrong)} y en la misma frase te corriges: ${Words.durationText(right)}.", listOf(act("timer.add", null, "dur" to right))))
            },
            Template("correcciones", "alarm", "next-turn", 3) {
                val first = Words.alarmTime(rng)
                val second = otherTime(first)
                val shift = pick(listOf(15, 30, 60))
                val fix = if (chance(0.6)) TurnSpec("Te has equivocado: la querías a ${Words.timeText(second)}. Corrígelo.", listOf(act("alarm.edit", Ref.Focus, "at" to periodClock(second))))
                else TurnSpec("Quieres retrasar esa alarma ${if (shift == 60) "una hora" else "$shift minutos"}.", listOf(act("alarm.edit", Ref.Focus, "at" to Shift(shift * 60_000L))))
                listOf(TurnSpec("Quieres una alarma a ${Words.timeText(first)}.", listOf(act("alarm.add", null, "time" to periodClock(first)))), fix)
            },
            Template("correcciones", "timer", "next-turn", 2) {
                val first = Words.duration(rng)
                val second = Words.duration(rng).takeIf { it != first } ?: (first + 120_000)
                val label = if (chance(0.5)) Words.timerLabel(rng) else null
                listOf(
                    TurnSpec("Quieres un temporizador de ${Words.durationText(first)}" + (label?.let { " para ${it.second}" } ?: "") + ".", listOf(act("timer.add", null, "dur" to first, "label" to label?.first))),
                    TurnSpec("Te has equivocado: lo querías de ${Words.durationText(second)}. Corrígelo.", listOf(act("timer.edit", Ref.Focus, "left" to second))),
                )
            },
            Template("correcciones", "list", "next-turn", 1) {
                val a = Words.product(rng, draft.shopping.map { it.name })
                val b = Words.product(rng, draft.shopping.map { it.name } + a.name)
                listOf(
                    TurnSpec("Quieres apuntar ${a.said} en la lista.", listOf(act("list.add", null, "name" to a.name))),
                    TurnSpec("Te has equivocado: no era ${a.name}, era ${b.name}. Corrígelo.", listOf(act("list.edit", Ref.Focus, "name" to b.name))),
                )
            },
            Template("correcciones", "list", "undo", 1) {
                val items = List(2 + rng.nextInt(2)) { Words.product(rng) }.distinctBy { it.name }
                listOf(
                    TurnSpec("Quieres apuntar en la lista ${items.joinToString(", ") { it.said }}.", items.map { act("list.add", null, "name" to it.name) }),
                    TurnSpec("Te arrepientes: quieres deshacer lo que Dina acaba de hacer.", listOf(act("undo"))),
                )
            },
            // ---------------------------------------------------------------- multiacción
            Template("multiacción", "list", "adds", 3) {
                val items = List(2 + rng.nextInt(3)) { Words.product(rng, draft.shopping.map { it.name }, quantity = chance(0.25)) }.distinctBy { it.name }
                listOf(TurnSpec("Quieres apuntar en la lista, en una sola frase: ${items.joinToString(", ") { it.said }}.", items.map { act("list.add", null, "name" to it.name, "n" to it.n, "unit" to it.unit) }))
            },
            Template("multiacción", "alarm", "alarm+timer", 1) {
                val time = Words.alarmTime(rng)
                val dur = Words.duration(rng)
                listOf(TurnSpec(
                    "En una sola frase quieres dos cosas: un temporizador de ${Words.durationText(dur)} y una alarma a ${Words.timeText(time)}.",
                    listOf(act("timer.add", null, "dur" to dur), act("alarm.add", null, "time" to periodClock(time))),
                ))
            },
            Template("multiacción", "alarm", "replace", 1) {
                draft.alarms.clear()
                val a = draft.addAlarm()
                val time = otherTime(a.time)
                listOf(TurnSpec(
                    "Tienes una alarma a ${Words.timeText(a.time)}. En una frase quieres quitarla y poner otra a ${Words.timeText(time)}.",
                    listOf(act("alarm.del", Ref.At(periodClock(a.time))), act("alarm.add", null, "time" to periodClock(time))),
                ))
            },
            Template("multiacción", "list", "check+add", 1) {
                if (draft.shopping.none { !it.done }) draft.addItem(done = false)
                val item = pick(draft.shopping.filter { !it.done })
                val p = Words.product(rng, draft.shopping.map { it.name })
                listOf(TurnSpec(
                    "Tienes ${item.name} en la lista. En una frase quieres marcarlo como comprado y apuntar ${p.said}.",
                    listOf(act("list.check", Ref.Named(item.name)), act("list.add", null, "name" to p.name)),
                ))
            },
            // ---------------------------------------------------------------- interrupciones
            Template("interrupciones", "alarm", "stop-alarm", 2) {
                draft.alarms.add(Draft.A(clock.toLocalTime().withSecond(0).withNano(0), if (chance(0.5)) Words.alarmLabel(rng).first else null, null, "ringing"))
                listOf(TurnSpec("Está sonando una alarma y quieres que se calle.", listOf(act("stop"))))
            },
            Template("interrupciones", "timer", "stop-timer", 2) {
                val label = Words.timerLabel(rng)
                draft.timers.add(Draft.T(60_000L * pick(listOf(5, 10, 15, 20)), label.first, 0, "ringing"))
                listOf(TurnSpec("Está sonando el temporizador de ${label.first} y quieres apagarlo.", listOf(act("stop"))))
            },
            Template("interrupciones", "alarm", "snooze", 1) {
                draft.alarms.add(Draft.A(clock.toLocalTime().withSecond(0).withNano(0), null, null, "ringing"))
                val minutes = pick(listOf(5, 10, 15))
                listOf(TurnSpec("Está sonando la alarma y quieres que vuelva a sonar dentro de $minutes minutos.", listOf(act("alarm.snooze", null, "dur" to minutes * 60_000L))))
            },
            Template("interrupciones", "timer", "drop", 1) {
                listOf(
                    TurnSpec("Quieres un temporizador, pero no dices de cuánto.", listOf(act("timer.add")), brief("falta duración")),
                    TurnSpec("Cambias de idea y le dices a Dina que lo deje, que da igual.", listOf(act("drop")), brief("olvidado")),
                )
            },
            // ---------------------------------------------------------------- confirmaciones
            Template("confirmaciones", "alarm", "past-today", 3) {
                if (clock.hour < 9) return@Template null
                val past = LocalTime.of(6 + rng.nextInt(clock.hour - 7), pick(listOf(0, 15, 30, 45)))
                val accept = chance(0.6)
                listOf(
                    TurnSpec("Quieres una alarma para hoy a ${Words.timeText(past)} (esa hora ya ha pasado hoy, pero no te das cuenta).", listOf(act("alarm.add", null, "time" to periodClock(past), "day" to Day.Today)), brief("¿confirmar?")),
                    if (accept) TurnSpec("Dina te propone ponerla para mañana. Aceptas.", listOf(act("yes")))
                    else TurnSpec("Dina te propone ponerla para mañana. No quieres.", listOf(act("nope")), brief("olvidado")),
                )
            },
            Template("confirmaciones", "alarm", "which-none", 1) {
                while (draft.alarms.size < 2) draft.addAlarm()
                listOf(
                    TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres quitar una alarma, pero no dices cuál.", listOf(act("alarm.del")), brief("¿cuál?")),
                    TurnSpec("Dina te pregunta cuál. Al final no quieres quitar ninguna.", listOf(act("nope")), brief("olvidado")),
                )
            },
            // ---------------------------------------------------------------- fuera de alcance
            Template("fuera de alcance", "otro", "topic", 1) {
                val (topic, text) = pick(TOPICS)
                listOf(TurnSpec(text.format(pick(TOPIC_FILLS.getValue(topic))), listOf(act("no", null, "topic" to topic)), brief("no puedo")))
            },
            // ---------------------------------------------------------------- errores
            Template("errores", "alarm", "missing-alarm", 2) {
                val time = Words.alarmTime(rng)
                draft.alarms.removeAll { it.time.hour % 12 == time.hour % 12 }
                listOf(TurnSpec("Quieres quitar la alarma de ${Words.timeText(time)}, pero no tienes ninguna a esa hora (no lo sabes).", listOf(act("alarm.del", Ref.At(periodClock(time)))), PROBLEM))
            },
            Template("errores", "list", "missing-item", 1) {
                val p = Words.product(rng, draft.shopping.map { it.name })
                listOf(TurnSpec("Quieres quitar ${p.name} de la lista, pero no está apuntado (no lo sabes).", listOf(act("list.del", Ref.Named(p.name))), PROBLEM))
            },
            Template("errores", "time", "zero", 1) {
                val n = 2 + rng.nextInt(50)
                listOf(TurnSpec("Quieres saber cuánto es $n entre cero.", listOf(act("calc", null, "expr" to "$n/0")), PROBLEM))
            },
            Template("errores", "vol", "max", 1) {
                draft.volume = 100; draft.muted = false
                listOf(TurnSpec("El volumen ya está al máximo (no lo sabes) y quieres subirlo más.", listOf(act("vol.up")), PROBLEM))
            },
            Template("errores", "alarm", "no-date", 1) {
                val (clock, said) = Words.spoken(rng, Words.alarmTime(rng), ambiguous = false)
                val (d, m) = pick(listOf(30 to 2, 31 to 4, 31 to 6, 31 to 9, 31 to 11))
                listOf(TurnSpec("Quieres una alarma para el $d de ${Words.MONTHS[m - 1]} (ese día no existe, no te das cuenta) a $said.", listOf(act("alarm.add", null, "time" to clock, "day" to Day.Date(d, m))), { it != "hecho" }))
            },
            // ---------------------------------------------------------------- charla
            Template("charla", "otro", "chat", 7) {
                listOf(TurnSpec(pick(weighted(rng, CHAT.map { it.second to it.first })), listOf(SAY), brief("charla"), TurnSpec.CHAT))
            },
            Template("charla", "fun", "fun", 2) {
                val (kind, asks) = pick(FUN_ASKS)
                val first = TurnSpec(pick(asks), listOf(act("fun.$kind")), brief(if (kind == "joke") "chiste" else "curiosidad"))
                if (!chance(0.3)) listOf(first)
                else listOf(first, TurnSpec("Te ha gustado y le pides ${if (kind == "joke") "otro" else "otra"}.", listOf(act("fun.$kind")), first.expect))
            },
            Template("charla", "alarm", "coletilla-alarm", 1) {
                val (label, remark) = pick(REMARKS.getValue("alarm"))
                val (clock, said) = Words.spoken(rng, Words.alarmTime(rng))
                val (day, dayText) = Words.day(rng, today)
                listOf(TurnSpec(
                    "Quieres una alarma" + (dayText?.let { " para $it" } ?: "") + " a $said. Es para: $label. De paso comentas que $remark.",
                    listOf(act("alarm.add", null, "time" to clock, "day" to day, "label" to label), SAY), DONE, TurnSpec.COLETILLA,
                ))
            },
            Template("charla", "timer", "coletilla-timer", 1) {
                val (label, remark) = pick(REMARKS.getValue("timer"))
                val dur = Words.duration(rng)
                listOf(TurnSpec("Quieres un temporizador de ${Words.durationText(dur)} para: $label. De paso comentas que $remark.",
                    listOf(act("timer.add", null, "dur" to dur, "label" to label), SAY), DONE, TurnSpec.COLETILLA))
            },
            Template("charla", "list", "coletilla-list", 1) {
                val (name, remark) = pick(REMARKS.getValue("list"))
                draft.shopping.removeAll { it.name == name }
                listOf(TurnSpec("Quieres apuntar en la lista de la compra: $name. De paso comentas que $remark.",
                    listOf(act("list.add", null, "name" to name), SAY), DONE, TurnSpec.COLETILLA))
            },
            Template("charla", "timer", "thanks", 1) {
                val dur = Words.duration(rng)
                val label = Words.timerLabel(rng)
                listOf(
                    TurnSpec("Quieres un temporizador de ${Words.durationText(dur)} para ${label.second}.", listOf(act("timer.add", null, "dur" to dur, "label" to label.first))),
                    TurnSpec(pick(listOf("Le das las gracias a Dina.", "Le dices a Dina que es un sol.", "Le das las gracias a Dina y te despides.")), listOf(SAY), brief("charla"), TurnSpec.CHAT),
                )
            },
            Template("charla", "otro", "unclear", 1) {
                listOf(TurnSpec("Empiezas a pedirle algo a Dina y lo dejas a medias, sin que se entienda qué quieres.", listOf(act("ask")), brief("¿qué?")))
            },
        )
    }
}
