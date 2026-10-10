package com.kakauet.dina.data

import com.kakauet.dina.data.ScenarioGenerator.Companion.SAY
import com.kakauet.dina.data.ScenarioGenerator.Companion.TOPICS
import com.kakauet.dina.data.ScenarioGenerator.Companion.TOPIC_FILLS
import com.kakauet.dina.data.ScenarioGenerator.Companion.act
import com.kakauet.dina.data.ScenarioGenerator.Companion.al
import com.kakauet.dina.data.ScenarioGenerator.Companion.otherTime
import com.kakauet.dina.data.ScenarioGenerator.Companion.periodClock
import com.kakauet.dina.data.ScenarioGenerator.Ctx
import com.kakauet.dina.data.ScenarioGenerator.Template
import com.kakauet.dina.dialog.Action
import com.kakauet.dina.dialog.Clock
import com.kakauet.dina.dialog.Day
import com.kakauet.dina.dialog.Delta
import com.kakauet.dina.dialog.Missing
import com.kakauet.dina.dialog.Ref
import com.kakauet.dina.dialog.Shift
import java.time.LocalTime

/**
 * Batch kind `refuerzo` (Dina 4.5): scenarios aimed at what Dina 4 got wrong (docs/HISTORY.md):
 * a volume level against a step, sums said in words, "para" as a verb, items named by label, day,
 * order or state, edits instead of new items, times and dates said in words, several actions in
 * one phrase, compound names, corrections, asking instead of inventing, queries, talk with no
 * request and interruptions. Fichas describe situations; they never copy benchmark phrases.
 */
object FocusTemplates {
    const val MIX = "refuerzo"

    val PHENOMENA = listOf(
        "volumen" to 10, "cuentas" to 8, "parar" to 10, "referencias" to 14, "edición" to 8, "horas y fechas" to 12, "multiacción" to 10,
        "nombres" to 5, "correcciones" to 8, "falta dato" to 8, "consultas" to 5, "charla" to 6, "interrupciones" to 6,
    )

    private fun t(phenomenon: String, domain: String, name: String, weight: Int, build: Ctx.() -> List<TurnSpec>?) =
        Template(phenomenon, domain, name, weight, MIX, build)

    private val DONE_OR_ANSWERED: (String) -> Boolean = { DONE(it) || ANSWERED(it) }

    private val SW_LABELS = listOf("carrera", "plancha", "estudio", "bici", "llamada", "partido", "remo", "paseo", "spinning", "comba", "meditación", "tostadas", "pesas", "pintura")

    /** Products said with more than one word (none taken from a benchmark). */
    private val COMPOUND = listOf(
        "pan de molde", "leche sin lactosa", "aceite de oliva", "papel de cocina", "salsa de soja", "queso rallado", "pechuga de pollo",
        "zumo de naranja", "agua mineral", "nata para cocinar", "bolsas de basura", "pimiento rojo", "tomate triturado", "café descafeinado",
        "yogur natural", "harina de maíz", "arroz integral", "mantequilla sin sal", "frutos secos", "papas fritas", "frijoles negros",
        "queso fresco", "jamón serrano", "crema de leche", "dulce de leche", "pan integral", "lavavajillas a mano", "guisantes congelados",
        "caldo de pollo", "leche de avena", "pasta integral", "gel de ducha",
    )

    /** Labels of more than one word, said whole. */
    private val LONG_LABELS = listOf(
        "llamar a mi hermana", "sacar la basura", "tomar la pastilla de la tensión", "regar el huerto", "llamar al fontanero",
        "recoger el paquete", "pagar el alquiler", "poner la lavadora", "ir a por el pan", "llevar el coche al taller", "ver el partido",
    )

    /** Things said to Dina that are not requests, even with a domain word in them. */
    private val NO_REQUEST = listOf(
        "Le dices a Dina que te ha entendido muy bien.", "Le preguntas a Dina si te ha entendido bien.", "Le dices a Dina que tiene una voz muy agradable.",
        "Le comentas a Dina que hoy hace un calor horrible.", "Le cuentas a Dina que vas a ponerte a hacer la cena.", "Le dices a Dina que espere un momento, que ahora sigues.",
        "Le dices a Dina que no hace falta que haga nada, que ya está.", "Le dices a Dina que vale, que perfecto así.", "Comentas en voz alta, para ti, que no encuentras las llaves.",
        "Le cuentas a Dina que la alarma de esta mañana te ha despertado de un susto.", "Le dices a Dina que el temporizador de antes te ha venido genial.",
        "Le preguntas a Dina si se cansa de poner alarmas.", "Le dices a Dina que hoy no vas a necesitar el despertador porque libras.",
    )

    private val CAPABILITIES = listOf(
        "Le preguntas a Dina qué cosas sabe hacer.", "Le preguntas a Dina si puede organizarte la agenda.", "Le preguntas a Dina si puede leerte los correos.",
        "Le preguntas a Dina para qué sirve.", "Le preguntas a Dina si necesita conexión para funcionar.", "Le preguntas a Dina si se acuerda de lo que le dijiste ayer.",
    )

    private fun Ctx.labelledTimers(n: Int, status: (Int) -> String? = { null }): List<Draft.T> {
        draft.timers.clear()
        val labels = Words.TIMER_LABELS.shuffled(rng).take(n)
        return labels.mapIndexed { i, l -> draft.addTimer(label = l.first, status = status(i)) }
    }

    private fun Ctx.labelledStopwatches(n: Int, paused: (Int) -> Boolean = { false }): List<Draft.S> {
        draft.stopwatches.clear()
        return SW_LABELS.shuffled(rng).take(n).mapIndexed { i, l -> draft.addStopwatch(label = l, paused = paused(i)) }
    }

    private fun Ctx.labelledAlarms(n: Int): List<Draft.A> {
        draft.alarms.clear()
        val labels = Words.ALARM_LABELS.shuffled(rng).take(n)
        val times = mutableSetOf<LocalTime>()
        return labels.map { l ->
            var time = Words.alarmTime(rng)
            while (!times.add(time)) time = time.plusMinutes(10)
            draft.addAlarm(time, label = l.first, repeat = null)
        }
    }

    private fun Ctx.swText() = draft.stopwatches.joinToString("; ") { (it.label ?: "sin nombre") + if (it.paused) " (parado)" else " (en marcha)" }

    /** A sum said in words: expression and how the ficha names it. */
    private fun Ctx.spokenSum(): Pair<String, String> {
        val a = 11 + rng.nextInt(89)
        val b = 3 + rng.nextInt(27)
        val q = 20 + rng.nextInt(181)
        val c = 2 + rng.nextInt(19)
        val n = 4 + rng.nextInt(26)
        return when (rng.nextInt(11)) {
            0 -> "$a*$b" to "$a por $b"
            1 -> "${c * q}/$c" to "${c * q} entre $c"
            2 -> "${100 * a}+${10 * b}" to "${100 * a} más ${10 * b}"
            3 -> "${10 * a}-${q}" to "${10 * a} menos $q"
            4 -> "sqrt(${n * n})" to "la raíz cuadrada de ${n * n}"
            5 -> "$n**2" to "$n al cuadrado"
            6 -> pick(listOf(10, 15, 20, 25, 30, 75)).let { p -> "$p*${b * 20}/100" to "el $p por ciento de ${b * 20}" }
            7 -> "${2 * q}/2" to "la mitad de ${2 * q}"
            8 -> "$q*2" to "el doble de $q"
            9 -> "$a.5*$b".let { it to "$a con cinco por $b" }
            else -> "${1000 + 100 * b}/$c" to "${1000 + 100 * b} entre $c"
        }
    }

    private val SUM_HINT = " (dilo como se dice en voz alta, con los números en palabras)"

    val TEMPLATES: List<Template> = listOf(
        // ---------------------------------------------------------------- volumen
        t("volumen", "vol", "to-level", 4) {
            draft.muted = false
            draft.volume = 10 * (2 + rng.nextInt(8))
            val to = pick((1..19).map { it * 5 }.filter { kotlin.math.abs(it - draft.volume) >= 15 })
            val up = to > draft.volume
            listOf(TurnSpec(
                "El volumen está al ${draft.volume}. Quieres ${if (up) "subirlo" else "bajarlo"} hasta dejarlo en $to: $to es el nivel final, no cuánto ${if (up) "subir" else "bajar"}. " +
                    "Dilo con un verbo de ${if (up) "subir" else "bajar"} o de poner y el nivel final (a $to, en $to, hasta $to).",
                listOf(act("vol.set", null, "n" to to)),
            ))
        },
        t("volumen", "vol", "by-step", 3) {
            draft.muted = false
            val up = chance(0.5)
            val k = pick(listOf(5, 10, 15, 20, 30))
            draft.volume = if (up) 10 * (1 + rng.nextInt(6)) else 10 * (4 + rng.nextInt(6))
            listOf(TurnSpec(
                "El volumen está al ${draft.volume}. Quieres ${if (up) "subirlo" else "bajarlo"} $k puntos sobre lo que está: $k es cuánto ${if (up) "subir" else "bajar"}, no el nivel final.",
                listOf(act(if (up) "vol.up" else "vol.down", null, "n" to k)),
            ))
        },
        t("volumen", "vol", "ask-level", 3) {
            draft.muted = false
            val first = TurnSpec("Quieres que Dina ${pick(listOf("cambie", "ajuste", "ponga", "toque"))} el volumen, pero no dices a cuánto ni si más alto o más bajo.", listOf(act("vol.set")), brief("falta valor"))
            if (chance(0.5)) return@t listOf(first)
            val n = 10 * (1 + rng.nextInt(10))
            listOf(first, TurnSpec("Dina te ha preguntado a qué volumen lo pone. Contestas: $n.", listOf(act("vol.set", null, "n" to n))))
        },
        t("volumen", "vol", "fix-same-turn", 2) {
            draft.muted = false
            val a = 10 * (2 + rng.nextInt(8))
            val b = (a + pick(listOf(-20, -10, 10, 20))).coerceIn(10, 100)
            if (a == b) return@t null
            listOf(TurnSpec("Quieres el volumen al $a y en la misma frase te corriges: al $b.", listOf(act("vol.set", null, "n" to b))))
        },
        t("volumen", "vol", "as-it-was", 2) {
            draft.muted = false
            draft.volume = 10 * (2 + rng.nextInt(5))
            val to = draft.volume + 10 * (2 + rng.nextInt(3))
            listOf(
                TurnSpec("Quieres el volumen al $to.", listOf(act("vol.set", null, "n" to to))),
                TurnSpec("Te parece demasiado alto: quieres dejarlo como estaba antes, sin decir el número.", listOf(act("undo"))),
            )
        },
        t("volumen", "vol", "a-bit", 2) {
            draft.muted = false
            val up = chance(0.5)
            draft.volume = if (up) 30 + 10 * rng.nextInt(4) else 50 + 10 * rng.nextInt(4)
            listOf(TurnSpec("Quieres ${if (up) "subir" else "bajar"} un poco el volumen, sin decir cuánto.", listOf(act(if (up) "vol.up" else "vol.down"))))
        },
        t("volumen", "vol", "mute", 1) {
            if (chance(0.5)) { draft.muted = true; listOf(TurnSpec("Dina está en silencio y quieres volver a oírla. Restricción: no digas «sube».", listOf(act("vol.unmute")))) }
            else { draft.muted = false; listOf(TurnSpec("Quieres que Dina se quede en silencio un rato, sin apagarla.", listOf(act("vol.mute")))) }
        },
        // ---------------------------------------------------------------- cuentas
        t("cuentas", "time", "spoken-ops", 4) {
            val (expr, text) = spokenSum()
            listOf(TurnSpec("Quieres saber cuánto es $text$SUM_HINT.", listOf(act("calc", null, "expr" to expr)), ANSWERED))
        },
        t("cuentas", "time", "price", 2) {
            val n = 2 + rng.nextInt(9)
            val euros = pick(listOf(1.2, 1.5, 2.25, 3.4, 4.5, 0.8, 12.0, 2.75, 6.5))
            val (what, per) = pick(listOf("kilos de naranjas" to "el kilo", "cafés" to "cada uno", "entradas de cine" to "cada una", "barras de pan" to "cada una", "kilos de manzanas" to "el kilo", "botellas de vino" to "cada una", "libretas" to "cada una"))
            listOf(TurnSpec(
                "Compras $n $what a ${Words.number(euros).replace('.', ',')} euros $per. Quieres saber cuánto pagas en total: di la compra, no la operación.",
                listOf(act("calc", null, "expr" to "$n*${Words.number(euros)}")), ANSWERED,
            ))
        },
        t("cuentas", "time", "missing", 2) {
            val a = 2 + rng.nextInt(98)
            val first = if (chance(0.5)) TurnSpec("Quieres que Dina te haga una cuenta, pero no dices cuál.", listOf(act("calc")), brief("falta cuenta"))
            else TurnSpec("Empiezas a dictar una cuenta con el $a («$a entre…», «$a por…») y la dejas cortada, sin el segundo número.", listOf(act("calc")), brief("falta cuenta"))
            if (chance(0.4)) return@t listOf(first)
            val (expr, text) = spokenSum()
            listOf(first, TurnSpec("Dina te pregunta qué cuenta hace. Dices: $text.", listOf(act("calc", null, "expr" to expr)), ANSWERED))
        },
        t("cuentas", "time", "chain", 1) {
            val b = 2 + rng.nextInt(8)
            val a = b * (3 + rng.nextInt(30))
            val c = 2 + rng.nextInt(5)
            listOf(TurnSpec("En una frase quieres saber cuánto es $a entre $b y, después, ese resultado por $c.", listOf(act("calc", null, "expr" to "$a/$b"), act("calc", null, "expr" to "$a/$b*$c")), ANSWERED))
        },
        // ---------------------------------------------------------------- parar
        t("parar", "sw", "stop-stopwatch", 3) {
            val all = labelledStopwatches(1 + rng.nextInt(3))
            val s = pick(all)
            val ref = if (all.size == 1) null else Ref.Named(s.label!!)
            val which = if (ref == null) "el cronómetro" else "el cronómetro de ${s.label}"
            listOf(TurnSpec("Tienes ${if (all.size == 1) "un cronómetro en marcha" else "estos cronómetros en marcha: " + swText()}. Quieres parar $which (pausarlo, no borrarlo). Usa el verbo «parar» o «detener».", listOf(act("sw.pause", ref))))
        },
        t("parar", "timer", "stop-timer", 2) {
            val all = labelledTimers(2 + rng.nextInt(2))
            val tt = pick(all)
            listOf(TurnSpec("Tienes estos temporizadores en marcha: ${timersText()}. Quieres parar un momento el de ${tt.label} (pausarlo, no quitarlo). Usa el verbo «parar».", listOf(act("timer.pause", Ref.Named(tt.label!!)))))
        },
        t("parar", "sw", "resume", 2) {
            if (chance(0.5)) {
                val all = labelledStopwatches(2 + rng.nextInt(2)) { it == 0 }
                listOf(TurnSpec("Tienes estos cronómetros: ${swText()}. Quieres que siga contando el de ${all[0].label}; nómbralo.", listOf(act("sw.resume", Ref.Named(all[0].label!!)))))
            } else {
                val all = labelledTimers(2 + rng.nextInt(2)) { if (it == 0) "paused" else null }
                listOf(TurnSpec("Tienes estos temporizadores: ${timersText()}. Quieres que siga el de ${all[0].label}; nómbralo.", listOf(act("timer.resume", Ref.Named(all[0].label!!)))))
            }
        },
        t("parar", "alarm", "nothing-ringing", 2) {
            draft.alarms.removeAll { it.status == "ringing" }
            draft.timers.removeAll { it.status == "ringing" }
            if (chance(0.5)) listOf(TurnSpec("Crees que suena la alarma y quieres que se calle, pero ahora no está sonando nada (no lo sabes).", listOf(act("stop")), brief("respondido")))
            else {
                if (draft.alarms.isEmpty()) draft.addAlarm()
                listOf(TurnSpec("Quieres posponer la alarma unos minutos, pero ahora mismo no está sonando ninguna (no lo sabes).", listOf(act("alarm.snooze")), PROBLEM))
            }
        },
        t("parar", "sw", "sw-ops", 2) {
            val all = labelledStopwatches(2 + rng.nextInt(2))
            val s = pick(all)
            val ref = Ref.Named(s.label!!)
            when (rng.nextInt(4)) {
                0 -> listOf(TurnSpec("Tienes estos cronómetros: ${swText()}. Quieres saber cuánto lleva el de ${s.label}.", listOf(act("sw.get", ref)), ANSWERED))
                1 -> listOf(TurnSpec("Tienes estos cronómetros: ${swText()}. Quieres poner a cero el de ${s.label}.", listOf(act("sw.reset", ref))))
                2 -> listOf(TurnSpec("Tienes estos cronómetros: ${swText()}. Quieres que el de ${s.label} empiece de cero y siga contando.", listOf(act("sw.restart", ref))))
                else -> listOf(TurnSpec("Tienes estos cronómetros: ${swText()}. Ya no necesitas el de ${s.label}: quieres quitarlo.", listOf(act("sw.del", ref))))
            }
        },
        t("parar", "sw", "start-for", 1) {
            draft.stopwatches.clear()
            val label = pick(SW_LABELS)
            listOf(TurnSpec("Quieres un cronómetro para $label: empieza la frase con «pon» o «arranca».", listOf(act("sw.add", null, "label" to label))))
        },
        // ---------------------------------------------------------------- referencias
        t("referencias", "timer", "by-name", 4) {
            val all = labelledTimers(2 + rng.nextInt(3))
            val tt = pick(all)
            val ref = Ref.Named(tt.label!!)
            val situation = "Tienes estos temporizadores: ${timersText()}. "
            when (rng.nextInt(4)) {
                0 -> listOf(TurnSpec("${situation}Quieres pausar el de ${tt.label}; nómbralo.", listOf(act("timer.pause", ref))))
                1 -> listOf(TurnSpec("${situation}Quieres quitar el de ${tt.label}; nómbralo.", listOf(act("timer.del", ref))))
                2 -> listOf(TurnSpec("${situation}Quieres saber cuánto le queda al de ${tt.label}.", listOf(act("timer.get", ref)), ANSWERED))
                else -> (60_000L * pick(listOf(1, 2, 5))).let { extra -> listOf(TurnSpec("${situation}Quieres añadirle ${Words.durationText(extra)} al de ${tt.label}.", listOf(act("timer.plus", ref, "dur" to extra)))) }
            }
        },
        t("referencias", "alarm", "by-name", 3) {
            val all = labelledAlarms(2 + rng.nextInt(3))
            val a = pick(all)
            val ref = Ref.Named(a.label!!)
            val situation = "Tienes estas alarmas: ${alarmsText()}. "
            when (rng.nextInt(4)) {
                0 -> listOf(TurnSpec("${situation}Quieres quitar la de ${a.label}; nómbrala por su motivo.", listOf(act("alarm.del", ref))))
                1 -> listOf(TurnSpec("${situation}Quieres desactivar la de ${a.label}, sin borrarla.", listOf(act("alarm.off", ref))))
                2 -> listOf(TurnSpec("${situation}Quieres saber a qué hora suena la de ${a.label}.", listOf(act("alarm.get", ref)), ANSWERED))
                else -> otherTime(a.time).let { time -> listOf(TurnSpec("${situation}Quieres cambiar la de ${a.label} a ${Words.timeText(time)}.", listOf(act("alarm.edit", ref, "at" to periodClock(time))))) }
            }
        },
        t("referencias", "alarm", "by-day", 3) {
            draft.alarms.clear()
            val codes = (0..6).filter { it != today.dayOfWeek.value - 1 }.shuffled(rng).take(2 + rng.nextInt(2))
            codes.forEach { draft.addAlarm(Words.alarmTime(rng), label = Words.maybe(rng, 0.4) { Words.alarmLabel(rng).first }, day = Words.DAY_CODES[it]) }
            val target = codes.first()
            val day = Day.Weekday(java.time.DayOfWeek.of(target + 1))
            val name = Words.WEEKDAY_NAMES[target]
            val situation = "Tienes estas alarmas: ${alarmsText()}. "
            when (rng.nextInt(3)) {
                0 -> listOf(TurnSpec("${situation}Quieres quitar la del $name; refiérete a ella por el día.", listOf(act("alarm.del", null, "day" to day))))
                1 -> listOf(TurnSpec("${situation}Quieres desactivar la del $name; refiérete a ella por el día.", listOf(act("alarm.off", null, "day" to day))))
                else -> listOf(TurnSpec("${situation}Quieres saber a qué hora suena la del $name.", listOf(act("alarm.get", null, "day" to day)), ANSWERED))
            }
        },
        t("referencias", "timer", "superlative", 2) {
            val all = labelledTimers(2 + rng.nextInt(2))
            if (all.map { it.left }.toSet().size < all.size) return@t null
            val (op, verb) = pick(listOf("timer.del" to "quitar", "timer.pause" to "pausar", "timer.get" to "saber cuánto le queda a"))
            val expect = if (op == "timer.get") ANSWERED else DONE
            if (chance(0.5)) listOf(TurnSpec("Tienes estos temporizadores: ${timersText()}. Quieres $verb el que antes va a terminar, sin decir su nombre.", listOf(act(op, Ref.Next)), expect))
            else all.maxBy { it.left }.let { longest -> listOf(TurnSpec("Tienes estos temporizadores: ${timersText()}. Quieres $verb el que más tiempo le queda, sin decir su nombre.", listOf(act(op, Ref.Named(longest.label!!))), expect)) }
        },
        t("referencias", "sw", "by-state", 1) {
            val all = labelledStopwatches(2 + rng.nextInt(2)) { it == 0 }
            listOf(TurnSpec("Tienes estos cronómetros: ${swText()}. Quieres que siga el que está parado, sin decir su nombre.", listOf(act("sw.resume", Ref.Named(all[0].label!!)))))
        },
        t("referencias", "timer", "creation-order", 1) {
            draft.timers.clear()
            val (a, b) = Words.TIMER_LABELS.shuffled(rng).take(2)
            val da = Words.duration(rng)
            val db = Words.duration(rng).takeIf { it != da } ?: (da + 60_000)
            val first = pick(listOf(true, false))
            listOf(
                TurnSpec("En una frase pones dos temporizadores: uno de ${Words.durationText(da)} para ${a.second} y otro de ${Words.durationText(db)} para ${b.second}.",
                    listOf(act("timer.add", null, "dur" to da, "label" to a.first), act("timer.add", null, "dur" to db, "label" to b.first))),
                TurnSpec("Quieres quitar el ${if (first) "primero" else "último"} que has puesto; refiérete a él por el orden.", listOf(act("timer.del", if (first) Ref.Nth(1) else Ref.Last))),
            )
        },
        // ---------------------------------------------------------------- edición
        t("edición", "alarm", "rename", 2) {
            draft.alarms.clear()
            val a = draft.addAlarm(label = null, repeat = null)
            if (chance(0.5)) draft.addAlarm(otherTime(a.time), label = null, repeat = null)
            val label = Words.alarmLabel(rng)
            listOf(TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres que la de ${Words.timeText(a.time)} se llame «${label.first}»; refiérete a ella por la hora.",
                listOf(act("alarm.edit", Ref.At(periodClock(a.time)), "label" to label.first))))
        },
        t("edición", "alarm", "move", 3) {
            val all = labelledAlarms(2 + rng.nextInt(2))
            val a = pick(all)
            val time = otherTime(a.time)
            if (chance(0.6)) listOf(TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres mover la de ${a.label} a ${Words.timeText(time)}; nómbrala por su motivo.", listOf(act("alarm.edit", Ref.Named(a.label!!), "at" to periodClock(time)))))
            else listOf(TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres mover la de ${Words.timeText(a.time)} a ${Words.timeText(time)}; nómbrala por su hora.", listOf(act("alarm.edit", Ref.At(periodClock(a.time)), "at" to periodClock(time)))))
        },
        t("edición", "alarm", "focus-move", 2) {
            val all = labelledAlarms(1 + rng.nextInt(3))
            val a = pick(all)
            val time = otherTime(a.time)
            listOf(
                TurnSpec("Tienes estas alarmas: ${alarmsText()}. Preguntas a qué hora suena la de ${a.label}.", listOf(act("alarm.get", Ref.Named(a.label!!))), ANSWERED),
                TurnSpec("Quieres pasarla a ${Words.timeText(time)}, sin repetir cuál es.", listOf(act("alarm.edit", Ref.Focus, "at" to periodClock(time)))),
            )
        },
        t("edición", "alarm", "is-it-on", 2) {
            val all = labelledAlarms(1 + rng.nextInt(3))
            val a = pick(all)
            val off = chance(0.5)
            draft.alarms[draft.alarms.indexOf(a)] = Draft.A(a.time, a.label, null, if (off) "off" else null)
            listOf(TurnSpec("Tienes alarmas puestas (no recuerdas cuáles están activas). Quieres saber si la de ${a.label} está activada; es una pregunta, no quieres cambiar nada.", listOf(act("alarm.get", Ref.Named(a.label!!))), ANSWERED))
        },
        t("edición", "alarm", "change-which", 1) {
            val all = labelledAlarms(2 + rng.nextInt(2))
            val a = pick(all)
            val time = otherTime(a.time)
            listOf(
                TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres cambiar una alarma, sin decir cuál ni qué.", listOf(act("alarm.edit")), brief("¿cuál?")),
                TurnSpec("Dina te pregunta cuál. Dices la de ${a.label} y que la quieres a ${Words.timeText(time)}.", listOf(act("alarm.edit", Ref.Named(a.label!!), "at" to periodClock(time)))),
            )
        },
        t("edición", "list", "add-vs-edit", 1) {
            if (draft.shopping.none { !it.done }) draft.addItem(done = false)
            val item = pick(draft.shopping.filter { !it.done })
            val n = 2.0 + rng.nextInt(5)
            listOf(TurnSpec("Tienes en la lista: ${listText()}. Quieres que de ${item.name} sean ${Words.number(n)} (cambiar la cantidad, no apuntarlo otra vez).", listOf(act("list.edit", Ref.Named(item.name), "n" to n))))
        },
        // ---------------------------------------------------------------- horas y fechas
        t("horas y fechas", "alarm", "said-in-words", 4) {
            val time = LocalTime.of(5 + rng.nextInt(18), pick(listOf(15, 30, 45, 45, 40, 50, 55)))
            val words = Words.timeWords(rng, time) ?: return@t null
            val part = when (Words.period(time)) { com.kakauet.dina.dialog.Period.MORNING -> "de la mañana"; com.kakauet.dina.dialog.Period.AFTERNOON -> "de la tarde"; else -> "de la noche" }
            val withPart = chance(0.6)
            val clock = if (withPart) periodClock(time) else Clock(if (time.hour % 12 == 0) 12 else time.hour % 12, time.minute)
            val label = if (chance(0.4)) Words.alarmLabel(rng) else null
            listOf(TurnSpec(
                "Quieres una alarma a ${Words.timeText(time)}" + (label?.let { " para ${it.second}" } ?: "") + ". Di la hora con palabras, así: «$words${if (withPart) " $part" else ""}»" + (if (withPart) "." else ", sin decir si es de mañana o de tarde."),
                listOf(act("alarm.add", null, "time" to clock, "label" to label?.first)),
            ))
        },
        t("horas y fechas", "alarm", "earlier-later", 3) {
            draft.alarms.clear()
            val a = draft.addAlarm(Words.alarmTime(rng).let { if (it.hour < 7) it.withHour(7) else it }, label = Words.alarmLabel(rng).first, repeat = null)
            val minutes = pick(listOf(10, 15, 20, 30, 45, 60, 90))
            val later = chance(0.5)
            val amount = when (minutes) { 15 -> "un cuarto de hora"; 30 -> "media hora"; 45 -> "tres cuartos de hora"; 60 -> "una hora"; 90 -> "hora y media"; else -> "$minutes minutos" }
            listOf(TurnSpec("Tienes la alarma de ${a.label} a ${Words.timeText(a.time)}. Quieres que suene $amount ${if (later) "más tarde" else "antes"}; refiérete a ella por su motivo y no digas la hora nueva.",
                listOf(act("alarm.edit", Ref.Named(a.label!!), "at" to Shift((if (later) 1 else -1) * minutes * 60_000L)))))
        },
        t("horas y fechas", "alarm", "no-part", 2) {
            val time = LocalTime.of(1 + rng.nextInt(11), pick(listOf(0, 0, 15, 30, 45, 10)))
            val label = pick(Words.ALARM_LABELS.filter { it.first !in setOf("trabajo", "colegio", "clase") })
            listOf(TurnSpec("Quieres una alarma a las ${Words.hm12(time)} para ${label.second}, sin decir si es de la mañana o de la tarde (y no es para despertarte).",
                listOf(act("alarm.add", null, "time" to Clock(time.hour, time.minute), "label" to label.first))))
        },
        t("horas y fechas", "time", "named-dates", 3) {
            val (name, text) = pick(listOf("navidad" to "Navidad", "nochebuena" to "Nochebuena", "nochevieja" to "Nochevieja", "nochevieja" to "fin de año", "año nuevo" to "Año Nuevo",
                "reyes" to "el día de Reyes", "san valentín" to "San Valentín", "san juan" to "San Juan", "halloween" to "Halloween"))
            val day = Day.Named(name)
            when (rng.nextInt(3)) {
                0 -> listOf(TurnSpec("Quieres saber cuántos días faltan para $text.", listOf(act("time.until", null, "day" to day)), ANSWERED))
                1 -> listOf(TurnSpec("Quieres saber qué día de la semana cae $text.", listOf(act("time.weekday", null, "day" to day)), ANSWERED))
                else -> Words.spoken(rng, Words.alarmTime(rng), ambiguous = false).let { (clock, said) ->
                    listOf(TurnSpec("Quieres una alarma para $text a $said.", listOf(act("alarm.add", null, "time" to clock, "day" to day))))
                }
            }
        },
        t("horas y fechas", "time", "month-day", 2) {
            val d = 1 + rng.nextInt(28)
            if (chance(0.5)) listOf(TurnSpec("Quieres saber cuántos días faltan para el día $d, sin decir el mes (el próximo día $d).", listOf(act("time.until", null, "day" to Day.MonthDay(d))), ANSWERED))
            else Words.spoken(rng, Words.alarmTime(rng), ambiguous = false).let { (clock, said) ->
                listOf(TurnSpec("Quieres una alarma para el día $d (sin decir el mes) a $said.", listOf(act("alarm.add", null, "time" to clock, "day" to Day.MonthDay(d)))))
            }
        },
        t("horas y fechas", "time", "month-name", 2) {
            val date = today.plusDays(3L + rng.nextInt(300))
            val text = "el ${date.dayOfMonth} de ${Words.MONTHS[date.monthValue - 1]}"
            val day = Day.Date(date.dayOfMonth, date.monthValue)
            if (chance(0.5)) listOf(TurnSpec("Quieres saber qué día de la semana cae $text.", listOf(act("time.weekday", null, "day" to day)), ANSWERED))
            else listOf(TurnSpec("Quieres saber cuántos días faltan para $text.", listOf(act("time.until", null, "day" to day)), ANSWERED))
        },
        t("horas y fechas", "timer", "duration-words", 2) {
            val (ms, words) = pick(listOf(75 to "hora y cuarto", 45 to "tres cuartos de hora", 90 to "hora y media", 150 to "dos horas y media", 15 to "un cuarto de hora",
                30 to "media hora", 105 to "una hora y tres cuartos", 20 to "veinte minutos")).let { it.first * 60_000L to it.second }
            val label = if (chance(0.5)) Words.timerLabel(rng) else null
            listOf(TurnSpec("Quieres un temporizador de $words (dicho así, con palabras)" + (label?.let { " para ${it.second}" } ?: "") + ".", listOf(act("timer.add", null, "dur" to ms, "label" to label?.first))))
        },
        t("horas y fechas", "timer", "implicit-minutes", 1) {
            draft.timers.clear()
            val (a, b) = Words.TIMER_LABELS.shuffled(rng).take(2)
            val da = Words.duration(rng)
            val n = pick(listOf(5, 8, 10, 12, 15, 20, 25, 40))
            listOf(
                TurnSpec("Quieres un temporizador de ${Words.durationText(da)} para ${a.second}.", listOf(act("timer.add", null, "dur" to da, "label" to a.first))),
                TurnSpec("Y quieres otro para ${b.second} de $n, sin decir «minutos» (se sobreentiende).", listOf(act("timer.add", null, "dur" to n * 60_000L, "label" to b.first))),
            )
        },
        t("horas y fechas", "timer", "alarm-in", 1) {
            val dur = 60_000L * pick(listOf(10, 20, 30, 45, 60, 90, 120))
            listOf(TurnSpec("Quieres que algo suene dentro de ${Words.durationSaid(rng, dur)}; dices «alarma», no «temporizador».", listOf(act("timer.add", null, "dur" to dur))))
        },
        t("horas y fechas", "list", "more-of", 1) {
            draft.shopping.clear()
            val p = Words.product(rng)
            val n = 1.0 + rng.nextInt(4)
            draft.shopping += Draft.I(p.name, n)
            val k = 1.0 + rng.nextInt(3)
            listOf(TurnSpec("Tienes ${Words.number(n)} de ${p.name} en la lista. Quieres ${Words.number(k)} más (sumarlos a los que hay, sin decir el total).", listOf(act("list.edit", Ref.Named(p.name), "n" to Delta(k)))))
        },
        // ---------------------------------------------------------------- multiacción
        t("multiacción", "otro", "mixed", 7) {
            // One part per domain, picked before building (builders change the state).
            val parts = MULTI.shuffled(rng).distinctBy { it.first }.take(if (chance(0.3)) 3 else 2).mapNotNull { it.second(this) }
            if (parts.size < 2) return@t null
            listOf(TurnSpec("En una sola frase quieres: ${parts.joinToString("; ") { it.clause }}. Dilo todo en la misma frase, sin dejarte nada.", parts.flatMap { it.actions }, DONE_OR_ANSWERED))
        },
        t("multiacción", "list", "compound-adds", 2) {
            val items = COMPOUND.shuffled(rng).take(2 + rng.nextInt(2))
            draft.shopping.removeAll { it.name in items }
            listOf(TurnSpec("Quieres apuntar en la lista, en una sola frase: ${items.joinToString(", ")} (cada producto con su nombre completo).", items.map { act("list.add", null, "name" to it) }))
        },
        t("multiacción", "alarm", "several-alarms", 1) {
            draft.alarms.clear()
            val start = LocalTime.of(5 + rng.nextInt(3), pick(listOf(0, 30)))
            val step = pick(listOf(10, 15))
            val times = List(3) { start.plusMinutes(it * step.toLong()) }
            // "De la mañana" said once at the end applies to the three.
            listOf(TurnSpec("Quieres tres alarmas en una frase: a ${times.joinToString(", ") { Words.hm12(it) }} de la mañana (di «de la mañana» solo al final).",
                times.map { act("alarm.add", null, "time" to periodClock(it)) }))
        },
        // ---------------------------------------------------------------- nombres
        t("nombres", "list", "compound", 3) {
            val item = pick(COMPOUND)
            draft.shopping.removeAll { it.name == item }
            listOf(TurnSpec("Quieres apuntar $item en la lista (con su nombre completo).", listOf(act("list.add", null, "name" to item))))
        },
        t("nombres", "list", "detail", 1) {
            val (base, detail) = pick(listOf("manzanas" to "verdes", "leche" to "desnatada", "pan" to "integral", "café" to "descafeinado", "yogures" to "naturales",
                "queso" to "curado", "pimientos" to "rojos", "tomates" to "cherry", "arroz" to "basmati", "atún" to "en aceite"))
            draft.shopping.removeAll { it.name == base }
            listOf(
                TurnSpec("Quieres apuntar $base en la lista.", listOf(act("list.add", null, "name" to base))),
                TurnSpec("Precisas que sea $detail: el producto pasa a ser «$base $detail». Dilo sin repetir el producto entero.", listOf(act("list.edit", Ref.Focus, "name" to "$base $detail"))),
            )
        },
        t("nombres", "alarm", "long-label", 1) {
            val label = pick(LONG_LABELS)
            val (clock, said) = Words.spoken(rng, Words.alarmTime(rng))
            listOf(TurnSpec("Quieres que Dina te avise a $said de que tienes que $label (es el nombre de la alarma: dilo entero).", listOf(act("alarm.add", null, "time" to clock, "label" to label))))
        },
        // ---------------------------------------------------------------- correcciones
        t("correcciones", "list", "drop-one", 2) {
            val items = (Words.TIMER_LABELS.map { it.first }.filter { it in setOf("pan", "huevos", "arroz", "patatas", "lentejas", "pollo") } + COMPOUND).shuffled(rng).take(2 + rng.nextInt(2))
            draft.shopping.removeAll { it.name in items }
            val x = pick(items)
            listOf(
                TurnSpec("Quieres apuntar en la lista: ${items.joinToString(", ")}.", items.map { act("list.add", null, "name" to it) }),
                TurnSpec("Te das cuenta de que $x ya lo tienes en casa: quieres quitar solo $x (no deshacer todo).", listOf(act("list.del", Ref.Named(x)))),
            )
        },
        t("correcciones", "alarm", "undo-delete", 2) {
            val all = labelledAlarms(1 + rng.nextInt(2))
            val a = pick(all)
            listOf(
                TurnSpec("Tienes estas alarmas: ${alarmsText()}. Quieres quitar la de ${a.label}.", listOf(act("alarm.del", Ref.Named(a.label!!)))),
                TurnSpec("Te arrepientes enseguida: quieres que Dina lo deshaga y la alarma vuelva a estar.", listOf(act("undo"))),
            )
        },
        t("correcciones", "sw", "wrong-kind", 1) {
            draft.stopwatches.clear()
            val label = pick(SW_LABELS)
            val dur = Words.duration(rng)
            listOf(
                TurnSpec("Quieres un cronómetro para $label.", listOf(act("sw.add", null, "label" to label))),
                TurnSpec("Te has equivocado: no querías un cronómetro, sino un temporizador de ${Words.durationText(dur)}.", listOf(act("sw.del", Ref.Focus), act("timer.add", null, "dur" to dur))),
            )
        },
        t("correcciones", "timer", "meant-query", 1) {
            draft.timers.clear()
            draft.addTimer(label = Words.timerLabel(rng).first)
            listOf(
                TurnSpec("Tienes un temporizador en marcha. Quieres pausarlo.", listOf(act("timer.pause"))),
                TurnSpec("No: lo que querías era saber cuánto le queda, y que siga en marcha.", listOf(act("timer.resume", Ref.Focus), act("timer.get", Ref.Focus))),
            )
        },
        t("correcciones", "alarm", "absolute-fix", 2) {
            val first = Words.alarmTime(rng)
            val second = otherTime(first)
            val words = Words.timeWords(rng, second)?.takeIf { chance(0.5) }?.let { " (dicha con palabras: «$it»)" } ?: ""
            listOf(
                TurnSpec("Quieres una alarma a ${Words.timeText(first)}.", listOf(act("alarm.add", null, "time" to periodClock(first)))),
                TurnSpec("Te has equivocado de hora: era a ${Words.timeText(second)}$words. Corrígelo sin decir que es la alarma de antes.", listOf(act("alarm.edit", Ref.Focus, "at" to periodClock(second)))),
            )
        },
        t("correcciones", "timer", "fix-duration-words", 1) {
            val first = 60_000L * pick(listOf(30, 60, 90))
            val (second, words) = pick(listOf(75 to "hora y cuarto", 45 to "tres cuartos de hora", 105 to "una hora y tres cuartos", 40 to "cuarenta minutos")).let { it.first * 60_000L to it.second }
            val label = Words.timerLabel(rng)
            listOf(
                TurnSpec("Quieres un temporizador de ${Words.durationText(first)} para ${label.second}.", listOf(act("timer.add", null, "dur" to first, "label" to label.first))),
                TurnSpec("Te corriges: lo querías de $words (dicho así).", listOf(act("timer.edit", Ref.Focus, "left" to second))),
            )
        },
        // ---------------------------------------------------------------- falta dato
        t("falta dato", "list", "something", 3) {
            val first = TurnSpec("Quieres que Dina apunte algo en la lista de la compra, pero en esta frase no dices qué producto: solo que quieres apuntar algo.", listOf(act("list.add")), brief("falta producto"))
            if (chance(0.4)) return@t listOf(first)
            val p = Words.product(rng, draft.shopping.map { it.name })
            listOf(first, TurnSpec("Dina te pregunta qué apunta. Contestas: ${p.said}.", listOf(act("list.add", null, "name" to p.name))))
        },
        t("falta dato", "alarm", "wake-no-time", 1) {
            listOf(TurnSpec("Quieres que Dina te despierte, pero no dices a qué hora.", listOf(act("alarm.add")), brief("falta hora")))
        },
        t("falta dato", "timer", "several-no-duration", 1) {
            listOf(TurnSpec("Quieres poner ${pick(listOf("dos", "un par de", "tres"))} temporizadores, pero no dices de cuánto ninguno.", listOf(act("timer.add")), brief("falta duración")))
        },
        t("falta dato", "time", "which-day", 1) {
            if (chance(0.5)) listOf(TurnSpec("Quieres saber qué día de la semana cae una fecha, pero no dices cuál.", listOf(act("time.weekday")), brief("falta día")))
            else listOf(TurnSpec("Quieres saber cuántos días faltan, pero no dices para qué fecha.", listOf(act("time.until")), brief("falta día")))
        },
        t("falta dato", "alarm", "new-name", 1) {
            draft.alarms.clear()
            draft.addAlarm(label = null, repeat = null)
            val label = Words.alarmLabel(rng)
            listOf(
                TurnSpec("Tienes una sola alarma. Quieres cambiarle el nombre, pero no dices cuál será.", listOf(act("alarm.edit", null, "label" to Missing)), brief("falta nombre")),
                TurnSpec("Dina te pregunta qué nombre le pone. Contestas: ${label.first}.", listOf(act("alarm.edit", null, "label" to label.first))),
            )
        },
        t("falta dato", "alarm", "remind-later", 1) {
            val (day, dayText) = Words.day(rng, today, allowNone = false)
            val task = pick(LONG_LABELS)
            listOf(TurnSpec("Quieres que Dina te recuerde $dayText que tienes que $task, sin decir a qué hora.", listOf(act("alarm.add", null, "day" to day, "label" to task)), brief("falta hora")))
        },
        // ---------------------------------------------------------------- consultas
        t("consultas", "list", "anything", 3) {
            if (chance(0.3)) draft.shopping.clear() else if (draft.shopping.isEmpty()) repeat(1 + rng.nextInt(4)) { draft.addItem() }
            val question = pick(listOf("si hay algo apuntado en la lista de la compra", "cuántas cosas tienes apuntadas", "qué te falta por comprar",
                draft.shopping.firstOrNull()?.let { "si tienes apuntado ${it.name}" } ?: "si la lista está vacía"))
            listOf(TurnSpec("Quieres saber $question. Es una pregunta: no quieres apuntar nada.", listOf(act("list.list")), ANSWERED))
        },
        t("consultas", "timer", "which-timers", 1) {
            if (draft.timers.isEmpty()) draft.addTimer()
            listOf(TurnSpec("Quieres saber qué temporizadores tienes puestos.", listOf(act("timer.list")), ANSWERED))
        },
        t("consultas", "alarm", "next-ring", 1) {
            while (draft.alarms.size < 2) draft.addAlarm()
            listOf(TurnSpec("Quieres saber cuál es la próxima alarma que va a sonar.", listOf(act("alarm.get", Ref.Next)), ANSWERED))
        },
        // ---------------------------------------------------------------- charla
        t("charla", "otro", "no-request", 3) {
            listOf(TurnSpec(pick(NO_REQUEST) + " No le pides nada.", listOf(SAY), brief("charla"), TurnSpec.CHAT))
        },
        t("charla", "otro", "capabilities", 2) {
            listOf(TurnSpec(pick(CAPABILITIES), listOf(SAY), brief("charla"), TurnSpec.CHAT))
        },
        t("charla", "otro", "out-of-scope", 2) {
            val (topic, text) = pick(TOPICS)
            listOf(TurnSpec(text.format(pick(TOPIC_FILLS.getValue(topic))), listOf(act("no", null, "topic" to topic)), brief("no puedo")))
        },
        // ---------------------------------------------------------------- interrupciones
        t("interrupciones", "timer", "other-then-back", 3) {
            val label = Words.timerLabel(rng)
            val dur = Words.duration(rng)
            val (middle, actions, expect) = interruption()
            listOf(
                TurnSpec("Quieres un temporizador para ${label.second}, pero no dices de cuánto.", listOf(act("timer.add", null, "label" to label.first)), brief("falta duración")),
                TurnSpec("Antes de contestar a Dina, $middle", actions, expect),
                TurnSpec("Dina te recuerda lo del temporizador. Contestas: ${Words.durationText(dur)}.", listOf(act("timer.add", null, "dur" to dur))),
            )
        },
        t("interrupciones", "alarm", "alarm-then-other", 2) {
            val (day, dayText) = Words.day(rng, today)
            val (middle, actions, expect) = interruption()
            val first = TurnSpec("Quieres una alarma" + (dayText?.let { " para $it" } ?: "") + ", pero no dices la hora.", listOf(act("alarm.add", null, "day" to day)), brief("falta hora"))
            val second = TurnSpec("En vez de contestar a Dina, $middle", actions, expect)
            if (chance(0.4)) return@t listOf(first, second)
            val (clock, said) = Words.spoken(rng, Words.alarmTime(rng), ambiguous = false)
            listOf(first, second, TurnSpec("Ahora contestas lo de la alarma: $said.", listOf(act("alarm.add", null, "time" to clock))))
        },
        t("interrupciones", "timer", "cancel-pending", 1) {
            listOf(
                TurnSpec("Quieres un temporizador, pero no dices de cuánto.", listOf(act("timer.add")), brief("falta duración")),
                TurnSpec("Le dices a Dina que lo olvide, que cancele lo que estaba poniendo.", listOf(act("drop")), brief("olvidado")),
            )
        },
        t("interrupciones", "time", "calc-back", 1) {
            val label = Words.timerLabel(rng)
            draft.timers.removeAll { it.label == label.first }
            draft.addTimer(label = label.first)
            val (expr, text) = spokenSum()
            listOf(
                TurnSpec("Quieres que Dina te haga una cuenta, pero no dices cuál.", listOf(act("calc")), brief("falta cuenta")),
                TurnSpec("Antes de dictarla, preguntas cuánto le queda al temporizador de ${label.first}.", listOf(act("timer.get", Ref.Named(label.first))), ANSWERED),
                TurnSpec("Vuelves a la cuenta: $text.", listOf(act("calc", null, "expr" to expr)), ANSWERED),
            )
        },
    )

    /** A request that interrupts a question: what the ficha says, the actions and the brief. */
    private fun Ctx.interruption(): Triple<String, List<Action>, (String) -> Boolean> = when (rng.nextInt(5)) {
        0 -> Words.product(rng, draft.shopping.map { it.name }).let { p -> Triple("le pides que apunte ${p.said} en la lista.", listOf(act("list.add", null, "name" to p.name)), DONE) }
        1 -> Triple("le preguntas qué hora es.", listOf(act("time.now", null, "part" to "hora")), ANSWERED)
        2 -> spokenSum().let { (expr, text) -> Triple("le preguntas cuánto es $text.", listOf(act("calc", null, "expr" to expr)), ANSWERED) }
        3 -> { draft.muted = false; val n = 10 * (2 + rng.nextInt(7)); draft.volume = if (n == 50) 40 else 50; Triple("le pides el volumen al $n.", listOf(act("vol.set", null, "n" to n)), DONE) }
        else -> Triple("le preguntas qué día es hoy.", listOf(act("time.now", null, "part" to "fecha")), ANSWERED)
    }

    /** One part of a several-action phrase: how the ficha says it and its actions. */
    private class Part(val clause: String, val actions: List<Action>)

    /** Parts by domain: a phrase takes at most one part of each. */
    private val MULTI: List<Pair<String, Ctx.() -> Part?>> = listOf(
        "time" to { Part("saber qué hora es", listOf(act("time.now", null, "part" to "hora"))) },
        "alarm" to { Words.alarmTime(rng).let { time -> Part("una alarma a ${Words.timeText(time)}", listOf(act("alarm.add", null, "time" to periodClock(time)))) } },
        "timer" to {
            val label = Words.timerLabel(rng)
            draft.timers.removeAll { it.label == label.first }
            val dur = Words.duration(rng)
            Part("un temporizador de ${Words.durationText(dur)} para ${label.second}", listOf(act("timer.add", null, "dur" to dur, "label" to label.first)))
        },
        "sw" to { draft.stopwatches.clear(); Part("poner en marcha un cronómetro", listOf(act("sw.add"))) },
        "sw" to {
            val all = labelledStopwatches(2) { it == 1 }
            Part("parar el cronómetro de ${all[0].label} y que siga el de ${all[1].label}", listOf(act("sw.pause", Ref.Named(all[0].label!!)), act("sw.resume", Ref.Named(all[1].label!!))))
        },
        "timer" to {
            val label = Words.timerLabel(rng)
            draft.timers.removeAll { it.status == "ringing" || it.label == label.first }
            draft.timers += Draft.T(60_000L * pick(listOf(5, 10, 20)), label.first, 0, "ringing")
            val other = draft.addTimer(label = Words.TIMER_LABELS.first { l -> draft.timers.none { it.label == l.first } }.first)
            Part("apagar el temporizador que está sonando y saber cuánto le queda al de ${other.label}", listOf(act("stop"), act("timer.get", Ref.Named(other.label!!))))
        },
        "list" to { Words.product(rng, draft.shopping.map { it.name }).let { p -> Part("apuntar ${p.said}", listOf(act("list.add", null, "name" to p.name))) } },
        "list" to {
            if (draft.shopping.isEmpty()) draft.addItem(done = false)
            val item = pick(draft.shopping)
            val p = pick(COMPOUND.filter { c -> draft.shopping.none { it.name == c } })
            Part("quitar ${item.name} de la lista y apuntar $p", listOf(act("list.del", Ref.Named(item.name)), act("list.add", null, "name" to p)))
        },
        "vol" to { draft.muted = false; val n = 10 * (1 + rng.nextInt(9)); draft.volume = if (n == 50) 60 else 50; Part("el volumen al $n", listOf(act("vol.set", null, "n" to n))) },
        "alarm" to {
            val all = labelledAlarms(2 + rng.nextInt(2))
            val (a, b) = all.shuffled(rng).take(2)
            if (chance(0.5)) Part("desactivar las alarmas de ${a.label} y de ${b.label}", listOf(act("alarm.off", Ref.Named(a.label!!)), act("alarm.off", Ref.Named(b.label!!))))
            else otherTime(b.time).let { time ->
                Part("quitar la alarma de ${a.label} y pasar la de ${b.label} a ${Words.timeText(time)}", listOf(act("alarm.del", Ref.Named(a.label!!)), act("alarm.edit", Ref.Named(b.label!!), "at" to periodClock(time))))
            }
        },
        "timer" to {
            val all = labelledTimers(2)
            all.forEach { t -> draft.timers[draft.timers.indexOf(t)] = Draft.T(t.total, t.label, t.left, if (t == all[0]) "paused" else null) }
            Part("que siga el temporizador de ${all[0].label} y parar el de ${all[1].label}", listOf(act("timer.resume", Ref.Named(all[0].label!!)), act("timer.pause", Ref.Named(all[1].label!!))))
        },
        "calc" to { spokenSum().let { (expr, text) -> Part("saber cuánto es $text", listOf(act("calc", null, "expr" to expr))) } },
    )
}
