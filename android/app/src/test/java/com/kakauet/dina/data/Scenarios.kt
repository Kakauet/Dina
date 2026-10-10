package com.kakauet.dina.data

import com.kakauet.dina.dialog.Action
import com.kakauet.dina.dialog.Clock
import com.kakauet.dina.dialog.Day
import com.kakauet.dina.dialog.Durations
import com.kakauet.dina.dialog.Period
import com.kakauet.dina.tools.Repeat
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Random

/**
 * One user turn: what the user wants, in Spanish but without the wording (the "ficha"), and the
 * canonical actions. [expect] guards the intended path: the brief the engine must produce.
 * [reply]: the text of the `say` action is written per phrase by the generator,
 * as a whole chat answer ([CHAT]) or as a short coletilla after the actions ([COLETILLA]).
 */
class TurnSpec(val ficha: String, val actions: List<Action>, val expect: (String) -> Boolean = DONE, val reply: String? = null) {
    companion object {
        const val CHAT = "charla"
        const val COLETILLA = "coletilla"
    }
}

val DONE: (String) -> Boolean = { it == "hecho" }
val ANSWERED: (String) -> Boolean = { it == "respondido" || it.firstOrNull()?.isDigit() == true }
val ASKS: (String) -> Boolean = { it.startsWith("falta") || it.startsWith("¿") }
val PROBLEM: (String) -> Boolean = { it in setOf("no existe", "falló", "ya estaba") }
fun brief(vararg values: String): (String) -> Boolean = { it in values }

/** Pure meaning: clock, initial state (the evaluator's state notation) and turns. No user wording. */
class Scenario(
    val id: String,
    val phenomenon: String,
    val domain: String,
    val complexity: String,
    val split: String,
    val clock: LocalDateTime,
    val state: JSONObject,
    val turns: List<TurnSpec>,
)

/** Initial world under construction, written in the evaluator's state notation (see `StateSpec`). */
class Draft(private val rng: Random) {
    /** [day]: in the state notation (`sab`, `mañana`, `2026-12-24`); null is its next occurrence. */
    class A(val time: LocalTime, val label: String?, val repeat: String? = null, val status: String? = null, val day: String? = null)
    class T(val total: Long, val label: String?, val left: Long = total, val status: String? = null)
    class S(val elapsed: Long, val label: String?, val paused: Boolean = false)
    class I(val name: String, val n: Double? = null, val unit: String? = null, val done: Boolean = false)

    val alarms = mutableListOf<A>()
    val timers = mutableListOf<T>()
    val stopwatches = mutableListOf<S>()
    val shopping = mutableListOf<I>()
    var volume = 50
    var muted = false

    fun json(): JSONObject = JSONObject().apply {
        if (alarms.isNotEmpty()) put("alarms", JSONArray(alarms.map { a ->
            listOfNotNull(a.day, "%02d:%02d".format(a.time.hour, a.time.minute), a.label?.let { "'$it'" }, a.repeat?.let { "rep=$it" }, a.status).joinToString(" ")
        }))
        if (timers.isNotEmpty()) put("timers", JSONArray(timers.map { t ->
            listOfNotNull(Durations.encode(t.total), t.label?.let { "'$it'" }, if (t.left != t.total) "left=${Durations.encode(t.left)}" else null, t.status).joinToString(" ")
        }))
        if (stopwatches.isNotEmpty()) put("stopwatches", JSONArray(stopwatches.map { s ->
            listOfNotNull(Durations.encode(s.elapsed), s.label?.let { "'$it'" }, if (s.paused) "paused" else null).joinToString(" ")
        }))
        if (shopping.isNotEmpty()) put("shopping", JSONArray(shopping.map { i ->
            listOfNotNull("'${i.name}'", i.n?.let { "n=" + Words.number(it) }, i.unit?.let { "unit=$it" }, if (i.done) "done" else null).joinToString(" ")
        }))
        put("volume", if (muted) "$volume muted" else "$volume")
    }

    fun addAlarm(time: LocalTime = Words.alarmTime(rng), label: String? = Words.maybe(rng, 0.6) { Words.alarmLabel(rng).first }, repeat: String? = null, status: String? = null, day: String? = null): A {
        val a = A(time, label?.takeIf { l -> alarms.none { it.label == l } }, repeat ?: if (day != null) null else Words.maybe(rng, 0.25) { Words.pick(rng, listOf("laborables", "diario", "finde", "lun,mie,vie", "mar,jue")) }, status, day)
        alarms += a
        return a
    }

    fun addTimer(total: Long = Words.duration(rng), label: String? = Words.maybe(rng, 0.7) { Words.timerLabel(rng).first }, status: String? = null, left: Long? = null): T {
        val t = T(total, label?.takeIf { l -> timers.none { it.label == l } }, left ?: (total * (2 + rng.nextInt(8)) / 10).let { it - it % 1000 }, status)
        timers += t
        return t
    }

    fun addStopwatch(label: String? = Words.maybe(rng, 0.4) { Words.pick(rng, Words.STOPWATCH_LABELS) }, paused: Boolean = rng.nextInt(3) == 0): S {
        val s = S(60_000L * (1 + rng.nextInt(40)) + 1000L * rng.nextInt(60), label?.takeIf { l -> stopwatches.none { it.label == l } }, paused)
        stopwatches += s
        return s
    }

    fun addItem(name: String = Words.product(rng, shopping.map { it.name }).name, done: Boolean = rng.nextInt(5) == 0): I {
        val i = I(name, null, null, done)
        shopping += i
        return i
    }

    /** Distractors for [domain] by state complexity: none, 1–2 or 3–6 items (similar labels and 7:00 / 19:00 included). */
    fun fill(domain: String, complexity: String) {
        val count = when (complexity) { "vacío" -> 0; "1-2" -> 1 + rng.nextInt(2); else -> 3 + rng.nextInt(4) }
        val target = if (domain in ITEM_DOMAINS) domain else Words.pick(rng, ITEM_DOMAINS)
        repeat(count) {
            when (target) {
                "alarm" -> if (complexity == "varios" && it == 0) { addAlarm(LocalTime.of(7, 0)); addAlarm(LocalTime.of(19, 0)) } else addAlarm()
                "timer" -> if (complexity == "varios" && it == 0 && timers.none { t -> t.label == "pasta" }) { addTimer(label = "pasta"); addTimer(label = "pasta 2") } else addTimer()
                "sw" -> addStopwatch()
                else -> addItem()
            }
        }
        if (complexity != "vacío" && rng.nextInt(3) == 0) when (Words.pick(rng, ITEM_DOMAINS.filter { it != target })) {
            "alarm" -> addAlarm(); "timer" -> addTimer(); "sw" -> addStopwatch(); else -> addItem()
        }
        volume = 10 * (2 + rng.nextInt(9))
        muted = rng.nextInt(20) == 0
    }

    companion object {
        val ITEM_DOMAINS = listOf("alarm", "timer", "sw", "list")
    }
}

/** Spanish words and value pools shared by the templates. */
object Words {
    fun <T> pick(rng: Random, values: List<T>): T = values[rng.nextInt(values.size)]
    fun <T> maybe(rng: Random, p: Double, value: () -> T): T? = if (rng.nextDouble() < p) value() else null

    val ALARM_LABELS = listOf(
        "trabajo" to "ir a trabajar", "médico" to "ir al médico", "gimnasio" to "ir al gimnasio", "pastilla" to "tomarte la pastilla",
        "niños" to "recoger a los niños", "reunión" to "una reunión", "dentista" to "el dentista", "vuelo" to "coger un vuelo",
        "perro" to "sacar al perro", "tren" to "coger el tren", "clase" to "ir a clase", "llamar a mamá" to "llamar a tu madre",
        "plantas" to "regar las plantas", "entreno" to "el entreno", "colegio" to "llevar a los niños al colegio", "pádel" to "el pádel",
        "yoga" to "la clase de yoga", "autobús" to "coger el autobús", "examen" to "el examen", "mercado" to "ir al mercado",
    )
    val TIMER_LABELS = listOf(
        "pasta" to "la pasta", "arroz" to "el arroz", "huevos" to "los huevos", "horno" to "el horno", "pizza" to "la pizza",
        "lavadora" to "la lavadora", "té" to "el té", "bizcocho" to "el bizcocho", "pan" to "el pan", "patatas" to "las patatas",
        "siesta" to "la siesta", "deberes" to "los deberes", "lentejas" to "las lentejas", "pollo" to "el pollo", "ropa" to "tender la ropa",
    )
    val STOPWATCH_LABELS = listOf("carrera", "plancha", "estudio", "bici", "llamada")

    class Product(val name: String, val n: Double? = null, val unit: String? = null, val said: String = name)

    private val PRODUCTS = listOf(
        "leche", "pan", "huevos", "tomates", "aceite", "café", "arroz", "manzanas", "yogures", "papel higiénico", "detergente",
        "queso", "jamón", "pollo", "plátanos", "cebollas", "ajos", "macarrones", "atún", "galletas", "agua", "cerveza", "mantequilla",
        "harina", "azúcar", "sal", "lechuga", "zanahorias", "patatas", "naranjas", "pimientos", "lentejas", "garbanzos", "champú",
        "servilletas", "pasta de dientes", "chorizo", "salmón", "fresas", "pepinos",
    )
    private val QUANTITIES = listOf(
        Product("leche", 2.0, "l", "2 litros de leche"), Product("huevos", 12.0, null, "12 huevos"), Product("tomates", 1.0, "kg", "un kilo de tomates"),
        Product("atún", 3.0, "lata", "3 latas de atún"), Product("café", 1.0, "paquete", "un paquete de café"), Product("agua", 6.0, "botella", "6 botellas de agua"),
        Product("yogures", 4.0, null, "4 yogures"), Product("patatas", 2.0, "kg", "2 kilos de patatas"), Product("cerveza", 1.0, "caja", "una caja de cerveza"),
        Product("plátanos", 6.0, null, "6 plátanos"), Product("harina", 500.0, "g", "500 gramos de harina"), Product("aceite", 1.0, "botella", "una botella de aceite"),
    )

    fun product(rng: Random, avoid: List<String> = emptyList(), quantity: Boolean = false): Product {
        if (quantity) QUANTITIES.filter { it.name !in avoid }.takeIf { it.isNotEmpty() }?.let { return pick(rng, it) }
        return Product(pick(rng, PRODUCTS.filter { it !in avoid }))
    }

    fun alarmLabel(rng: Random) = pick(rng, ALARM_LABELS)
    fun timerLabel(rng: Random) = pick(rng, TIMER_LABELS)

    private val MINUTES = listOf(0, 0, 0, 0, 15, 30, 30, 45, 10, 20, 40, 50, 5, 25, 35)

    fun alarmTime(rng: Random, part: String = pick(rng, listOf("mañana", "mañana", "mañana", "tarde", "noche"))): LocalTime = when (part) {
        "mañana" -> LocalTime.of(5 + rng.nextInt(6), pick(rng, MINUTES))
        "tarde" -> LocalTime.of(13 + rng.nextInt(7), pick(rng, MINUTES))
        else -> LocalTime.of(21 + rng.nextInt(3), pick(rng, MINUTES))
    }

    private val MINUTES_TIMER = listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 10, 12, 15, 15, 20, 25, 30, 30, 35, 40, 45, 50, 60, 90, 90, 120)

    fun duration(rng: Random): Long = when (rng.nextInt(12)) {
        0 -> 1000L * pick(rng, listOf(30, 40, 45, 90))
        1 -> 60_000L * pick(rng, listOf(1, 2, 3, 4)) + 30_000L
        else -> 60_000L * pick(rng, MINUTES_TIMER)
    }

    fun number(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    fun durationText(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        val s = ms % 60_000 / 1000
        val parts = listOfNotNull(
            if (h > 0) "$h ${if (h == 1L) "hora" else "horas"}" else null,
            if (m > 0) "$m ${if (m == 1L) "minuto" else "minutos"}" else null,
            if (s > 0) "$s segundos" else null,
        )
        return parts.joinToString(" y ")
    }

    fun period(time: LocalTime): Period = when (time.hour) {
        in 4..11 -> Period.MORNING
        in 12..20 -> Period.AFTERNOON
        else -> Period.NIGHT
    }

    private fun h12(time: LocalTime) = if (time.hour % 12 == 0) 12 else time.hour % 12
    fun hm(time: LocalTime) = "%d:%02d".format(time.hour, time.minute)
    fun hm12(time: LocalTime) = "%d:%02d".format(h12(time), time.minute)
    private fun las(time: LocalTime) = if (h12(time) == 1) "la" else "las"

    /** The time as the user knows it, unambiguous ("las 7:30 de la tarde"). */
    fun timeText(time: LocalTime): String = when (time.hour) {
        12 -> "las ${hm(time)} del mediodía"
        0 -> "las ${hm12(time)} de la noche"
        else -> "${las(time)} ${hm12(time)} " + when (period(time)) { Period.MORNING -> "de la mañana"; Period.AFTERNOON -> "de la tarde"; Period.NIGHT -> "de la noche" }
    }

    /** How the user will say [time]: with the part of day, in 24 h, or without saying which half. */
    fun spoken(rng: Random, time: LocalTime, ambiguous: Boolean = true): Pair<Clock, String> {
        val roll = rng.nextDouble()
        val words = timeWords(rng, time)?.takeIf { rng.nextDouble() < 0.5 }?.let { " (dicho con palabras: «$it»)" } ?: ""
        return when {
            time.hour == 12 || time.hour == 0 -> Clock(time.hour, time.minute) to timeText(time)
            ambiguous && roll < 0.25 -> Clock(h12(time), time.minute) to "${las(time)} ${hm12(time)}$words, sin decir si es de mañana o de tarde"
            time.hour >= 13 && roll < 0.4 -> Clock(time.hour, time.minute) to "las ${hm(time)} (dicho en formato de 24 horas)"
            else -> Clock(h12(time), time.minute, period(time)) to timeText(time) + words
        }
    }

    private val HOUR_WORDS = listOf("doce", "una", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce")

    /** «las ocho y cuarto», «las siete menos cuarto», «un cuarto para las siete» (correction cycle 1: RW2 failed these). */
    fun timeWords(rng: Random, time: LocalTime): String? {
        val hour = h12(time)
        val next = hour % 12 + 1
        fun las(h: Int) = if (h == 1) "la ${HOUR_WORDS[h]}" else "las ${HOUR_WORDS[h]}"
        return when (time.minute) {
            0 -> "${las(hour)} en punto"
            15 -> "${las(hour)} y cuarto"
            30 -> "${las(hour)} y media"
            45 -> if (rng.nextDouble() < 0.7) "${las(next)} menos cuarto" else "un cuarto para ${las(next)}"
            40 -> "${las(next)} menos veinte"
            50 -> if (rng.nextDouble() < 0.7) "${las(next)} menos diez" else "diez para ${las(next)}"
            55 -> if (rng.nextDouble() < 0.7) "${las(next)} menos cinco" else "cinco para ${las(next)}"
            else -> null
        }
    }

    /** A duration as the ficha names it: in figures, or sometimes as people say it («hora y media», «media hora»). */
    fun durationSaid(rng: Random, ms: Long): String {
        val words = when (ms) {
            15 * 60_000L -> "un cuarto de hora"
            30 * 60_000L -> "media hora"
            45 * 60_000L -> "tres cuartos de hora"
            60 * 60_000L -> "una hora"
            90 * 60_000L -> "hora y media"
            120 * 60_000L -> "dos horas"
            else -> null
        }
        return if (words != null && rng.nextDouble() < 0.5) "$words (dicho así, con palabras)" else durationText(ms)
    }

    val HOLIDAYS = listOf(Triple(25, 12, "Navidad"), Triple(31, 12, "Nochevieja"), Triple(6, 1, "Reyes"), Triple(1, 1, "Año Nuevo"), Triple(24, 6, "San Juan"), Triple(14, 2, "San Valentín"))

    val WEEKDAY_NAMES = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    val MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    /** A day for an alarm or a date question, and how the ficha names it. */
    fun day(rng: Random, today: LocalDate, allowNone: Boolean = true): Pair<Day?, String?> = when (rng.nextInt(if (allowNone) 10 else 5)) {
        0 -> Day.Tomorrow to "mañana"
        1 -> Day.AfterTomorrow to "pasado mañana"
        2 -> DayOfWeek.of(1 + rng.nextInt(7)).let { Day.Weekday(it) to "el ${WEEKDAY_NAMES[it.value - 1]}" }
        3, 4 -> today.plusDays(3L + rng.nextInt(80)).let { Day.Date(it.dayOfMonth, it.monthValue) to "el ${it.dayOfMonth} de ${MONTHS[it.monthValue - 1]}" }
        else -> null to null
    }

    val REPEATS = listOf(
        Repeat.Weekdays to "de lunes a viernes", Repeat.Daily to "todos los días", Repeat.Weekends to "los fines de semana",
        Repeat.Weekly(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)) to "los lunes y miércoles",
        Repeat.Weekly(listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)) to "los martes y jueves",
        Repeat.Weekly(listOf(DayOfWeek.SATURDAY)) to "los sábados",
    )

    fun ordinal(n: Int) = listOf("primera", "segunda", "tercera", "cuarta", "quinta", "sexta")[n - 1]

    /** Day codes of the state notation (`lun`…`dom`). */
    val DAY_CODES = listOf("lun", "mar", "mie", "jue", "vie", "sab", "dom")

    /** "el sábado", "mañana": an alarm's day of the state notation as the ficha says it. */
    fun dayText(code: String, today: LocalDate): String = when (code) {
        "hoy" -> "hoy"
        "manana", "mañana" -> "mañana"
        "pasado" -> "pasado mañana"
        in DAY_CODES -> "el " + WEEKDAY_NAMES[DAY_CODES.indexOf(code)]
        else -> LocalDate.parse(code).let { "el ${it.dayOfMonth} de ${MONTHS[it.monthValue - 1]}" }
    }
}
