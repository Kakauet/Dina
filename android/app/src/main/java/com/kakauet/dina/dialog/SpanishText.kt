package com.kakauet.dina.dialog

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/**
 * Spanish answers read as facts, never as literal substrings: "6:00" = "06:00" = "las seis",
 * "cuatro minutos y doce segundos" = 252 s, "miércoles" = "miercoles", "ochenta y cuatro" = 84.
 * Negated claims ("no he añadido café") are not claims.
 */
object SpanishText {
    /** Lowercase, no accents, numbers in digits, punctuation as spaces (":" "," "." kept between digits). */
    fun normalize(text: String): String {
        val folded = fold(text)
        return numbersToDigits(folded)
    }

    fun fold(text: String): String {
        val noMarks = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val kept = StringBuilder()
        for (i in noMarks.indices) {
            val c = noMarks[i]
            val betweenDigits = i > 0 && i < noMarks.length - 1 && noMarks[i - 1].isDigit() && noMarks[i + 1].isDigit()
            kept.append(
                when {
                    c.isLetterOrDigit() -> c
                    c == '%' -> " por ciento "
                    (c == ':' || c == '.' || c == ',' || c == '-' || c == '/') && betweenDigits -> if (c == ',') '.' else c
                    else -> ' '
                }
            )
        }
        return kept.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun words(normalized: String): List<String> = normalized.split(' ').filter { it.isNotEmpty() }

    /** Key used to compare labels and product names: no articles, no plural "s", folded. */
    fun key(label: String?): String? {
        if (label == null) return null
        val tokens = fold(label).split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (tokens.size > 1 && tokens.first() in LEADING_FILLERS) tokens.removeAt(0)
        return tokens.joinToString(" ") { stem(it) }
    }

    /** Crude singular: "tomates"/"tomate" -> "tomat", "panes"/"pan" -> "pan", "huevos" -> "huevo". */
    fun stem(word: String): String {
        val noS = if (word.length > 3 && word.endsWith("s")) word.dropLast(1) else word
        return if (noS.length > 3 && noS.endsWith("e") && noS[noS.length - 2] !in "aeiou") noS.dropLast(1) else noS
    }

    // ---- Numbers ----

    fun numbersToDigits(folded: String): String {
        val tokens = folded.split(' ').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val parsed = if (tokens[i] == "ciento" && i > 0 && tokens[i - 1] == "por") null else parseNumberWords(tokens, i)
            if (parsed == null) { out += tokens[i]; i++; continue }
            var (value, next) = parsed
            // "dos coma cinco" -> 2.5
            if (next + 1 < tokens.size && tokens[next] == "coma") {
                val decimal = parseNumberWords(tokens, next + 1)
                if (decimal != null) {
                    out += "${value.toLong()}.${decimal.first.toLong()}"
                    i = decimal.second
                    continue
                }
            }
            out += if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            i = next
        }
        return out.joinToString(" ")
    }

    /** Parses a run of Spanish number words starting at [start]; returns (value, next index). */
    private fun parseNumberWords(tokens: List<String>, start: Int): Pair<Double, Int>? {
        var total = 0L
        var current = 0L
        var i = start
        var any = false
        var lastWasTens = false
        while (i < tokens.size) {
            val token = tokens[i]
            val unit = UNITS[token]
            val tens = TENS[token]
            val hundreds = HUNDREDS[token]
            when {
                token == "y" && lastWasTens && i + 1 < tokens.size && (UNITS[tokens[i + 1]] ?: 99) in 1..9 -> { i++; continue }
                unit != null && !(any && current % 100 != 0L && !lastWasTens) -> {
                    current += unit; any = true; lastWasTens = false
                }
                tens != null && current % 100 == 0L -> { current += tens; any = true; lastWasTens = true }
                hundreds != null && current == 0L -> { current += hundreds; any = true; lastWasTens = false }
                token == "mil" && (any || i + 1 < tokens.size) -> {
                    total += (if (current == 0L) 1 else current) * 1000; current = 0; any = true; lastWasTens = false
                }
                else -> break
            }
            i++
        }
        if (!any) return null
        return (total + current).toDouble() to i
    }

    fun numbers(normalized: String): List<Double> =
        Regex("(?<![\\d:])\\d+(?:\\.\\d+)?(?![\\d:])").findAll(normalized).mapNotNull { it.value.toDoubleOrNull() }.toList()

    // ---- Clock times ----

    /** A spoken clock time: [hour] in 0..23 when explicit, or 1..12 with [ambiguous] = true. */
    data class ClockMention(val hour: Int, val minute: Int, val ambiguous: Boolean)

    fun times(normalized: String): List<ClockMention> {
        val found = mutableListOf<ClockMention>()
        val text = " $normalized "
        Regex("(?<![\\d.])(\\d{1,2})[:.h](\\d{2})(?![\\d])(?:\\s*(?:h|horas))?(\\s+(?:de la |del |en la |por la )?(?:manana|tarde|noche|madrugada|mediodia)|\\s+(?:a m|p m|am|pm))?").findAll(text).forEach { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            if (hour in 0..23 && minute in 0..59) found += qualify(hour, minute, m.groupValues[3])
        }
        val spoken = Regex(
            "\\b(?:las|la)\\s+(?<h>\\d{1,2})(?:\\s+(?:horas?|h))?" +
                "(?:\\s+(?<sign>y|menos)\\s+(?<part>\\d{1,2}|media|cuarto)|\\s+(?<bare>[1-5]\\d)(?!\\s*(?:minutos?|segundos?|horas?)\\b))?" +
                "(?:\\s+minutos?)?(?<q>\\s+(?:en punto\\s+)?(?:de la |del |en la |por la )?(?:manana|tarde|noche|madrugada|mediodia)|\\s+(?:a m|p m|am|pm))?"
        )
        spoken.findAll(text).forEach { m ->
            var hour = m.groups["h"]!!.value.toInt()
            if (hour !in 0..24) return@forEach
            var minute = m.groups["bare"]?.value?.toInt() ?: 0
            val sign = m.groups["sign"]?.value
            if (sign != null) {
                val amount = when (val part = m.groups["part"]!!.value) { "media" -> 30; "cuarto" -> 15; else -> part.toInt() }
                if (sign == "y") minute = amount else { minute = 60 - amount; hour = (hour + 23) % 24 }
            }
            if (minute in 0..59) found += qualify(hour % 24, minute, m.groups["q"]?.value.orEmpty())
        }
        // Latin American "un cuarto para las ocho" (7:45), "veinte para las nueve" (8:40).
        Regex("\\b(un cuarto|cuarto|\\d{1,2})(?: minutos)? para (?:las|la) (\\d{1,2})(\\s+(?:de la |del |en la |por la )?(?:manana|tarde|noche|madrugada)|\\s+(?:a m|p m|am|pm))?").findAll(text).forEach { m ->
            val before = if (m.groupValues[1].endsWith("cuarto")) 15 else m.groupValues[1].toInt()
            val hour = m.groupValues[2].toInt()
            if (before in 1..59 && hour in 1..24) found += qualify((hour + 23) % 24, 60 - before, m.groupValues[3])
        }
        if (Regex("\\bmediodia\\b").containsMatchIn(text) && found.none { it.hour == 12 }) found += ClockMention(12, 0, false)
        if (Regex("\\bmedianoche\\b").containsMatchIn(text)) found += ClockMention(0, 0, false)
        return found
    }

    private fun qualify(hour: Int, minute: Int, qualifier: String): ClockMention {
        val q = qualifier.trim()
        return when {
            hour > 12 || hour == 0 -> ClockMention(hour, minute, false)
            q.endsWith("tarde") || q.endsWith("pm") || q.endsWith("p m") -> ClockMention(if (hour == 12) 12 else hour + 12, minute, false)
            q.endsWith("noche") -> ClockMention(if (hour == 12) 0 else if (hour >= 7) hour + 12 else hour, minute, false)
            q.endsWith("manana") || q.endsWith("madrugada") || q.endsWith("am") || q.endsWith("a m") -> ClockMention(if (hour == 12) 0 else hour, minute, false)
            q.endsWith("mediodia") -> ClockMention(hour, minute, false)
            hour == 12 -> ClockMention(12, minute, false)
            else -> ClockMention(hour, minute, true)
        }
    }

    fun mentionsTime(normalized: String, expected: LocalTime): Boolean = times(normalized).any { m ->
        m.minute == expected.minute && (m.hour == expected.hour || (m.ambiguous && (m.hour + 12) % 24 == expected.hour))
    }

    // ---- Durations ----

    fun durations(normalized: String): List<Long> {
        val tokens = words(normalized)
        val found = mutableListOf<Long>()
        var i = 0
        while (i < tokens.size) {
            var total = 0L
            var j = i
            var parts = 0
            var lastUnit = Long.MAX_VALUE
            while (j < tokens.size) {
                val (seconds, next, unit) = durationPart(tokens, j) ?: break
                if (unit >= lastUnit) break // "10 minutos, 20 minutos" are two durations; "1 hora 20 minutos" is one
                total += seconds; parts++; j = next; lastUnit = unit
                if (j + 1 < tokens.size && tokens[j] == "y" && tokens[j + 1] in FRACTIONS) {
                    total += lastUnit / FRACTIONS.getValue(tokens[j + 1]); j += 2
                    break
                }
                if (j < tokens.size && tokens[j] == "y" && (durationPart(tokens, j + 1)?.third ?: Long.MAX_VALUE) < lastUnit) j++
            }
            if (parts > 0) { found += total; i = j } else i++
        }
        Regex("(?<![\\d.:])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?![\\d:])").findAll(normalized).forEach { m ->
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); val c = m.groupValues[3].toLongOrNull()
            found += if (c != null) a * 3600 + b * 60 + c else a * 60 + b
        }
        return found
    }

    /** One "amount unit" piece: seconds, index after it and the unit's size in seconds. */
    private fun durationPart(tokens: List<String>, i: Int): Triple<Long, Int, Long>? {
        val token = tokens.getOrNull(i) ?: return null
        if (token == "media" && tokens.getOrNull(i + 1)?.startsWith("hora") == true) return Triple(1800L, i + 2, 3600L)
        if (token == "1" && tokens.getOrNull(i + 1) == "cuarto" && tokens.getOrNull(i + 2) == "de" && tokens.getOrNull(i + 3)?.startsWith("hora") == true) return Triple(900L, i + 4, 3600L)
        if (token == "cuarto" && tokens.getOrNull(i + 1) == "de" && tokens.getOrNull(i + 2)?.startsWith("hora") == true) return Triple(900L, i + 3, 3600L)
        // "de hora y media", "hora y cuarto": an hour without "una"
        if (token == "hora" && tokens.getOrNull(i + 1) == "y" && tokens.getOrNull(i + 2) in FRACTIONS) return Triple(3600L, i + 1, 3600L)
        val amount = token.toDoubleOrNull() ?: return null
        val unit = tokens.getOrNull(i + 1) ?: return null
        val seconds = when {
            unit == "h" || unit.startsWith("hora") -> 3600L
            unit == "min" || unit == "mins" || unit.startsWith("minuto") -> 60L
            unit == "s" || unit == "seg" || unit == "segs" || unit.startsWith("segundo") -> 1L
            else -> return null
        }
        return Triple((amount * seconds).toLong(), i + 2, seconds)
    }

    /** Spoken durations are often rounded to whole minutes: "quedan unos cuatro minutos" for 4:12. */
    fun mentionsDuration(normalized: String, expectedSeconds: Long): Boolean = durations(normalized).any { d ->
        abs(d - expectedSeconds) <= 1 || (d % 60 == 0L && abs(d - expectedSeconds) < 60 && expectedSeconds >= 60)
    }

    fun mentionsNumber(normalized: String, expected: Double): Boolean = numbers(normalized).any { v ->
        abs(v - expected) <= maxOf(0.011, abs(expected) * 0.005)
    }

    // ---- Days and dates ----

    fun mentionsWeekday(normalized: String, day: String): Boolean = Regex("\\b${fold(day)}\\b").containsMatchIn(normalized)

    fun mentionsDate(normalized: String, date: LocalDate): Boolean {
        val month = MONTHS[date.monthValue - 1]
        val d = date.dayOfMonth
        return Regex("\\b$d de $month\\b").containsMatchIn(normalized) ||
            Regex("\\b0?$d[/-]0?${date.monthValue}\\b").containsMatchIn(normalized) ||
            normalized.contains(date.toString()) ||
            Regex("\\b$month $d\\b").containsMatchIn(normalized)
    }

    // ---- Words ----

    /** True when [phrase] appears as whole words, ignoring articles and plural "s". */
    fun mentionsPhrase(normalized: String, phrase: String): Boolean {
        val target = (key(phrase) ?: return false).split(' ')
        val tokens = words(normalized).map(::stem)
        if (target.isEmpty()) return false
        return tokens.windowed(target.size).any { it == target }
    }

    // ---- Speech acts ----

    fun isQuestion(raw: String): Boolean {
        if (raw.contains('?') || raw.contains('¿')) return true
        val text = fold(raw)
        return Regex("(^|\\b)(dime|indicame|especifica|concreta|aclarame|confirmame|confirma)\\b").containsMatchIn(text) ||
            Regex("\\b(necesito saber|me dices|me indicas|me confirmas|me concretas|tienes que decirme)\\b").containsMatchIn(text)
    }

    fun expressesInability(raw: String): Boolean = INABILITY.containsMatchIn(" ${fold(raw)} ")

    fun expressesAbsence(raw: String): Boolean = ABSENCE.containsMatchIn(" ${fold(raw)} ")

    fun expressesYes(raw: String): Boolean = Regex("\\b(si|claro|por supuesto|puedo|se hacerlo)\\b").containsMatchIn(fold(raw))

    enum class Claim { NONE, WEAK, STRONG }

    /** Strongest non-negated success claim: "he puesto…" (strong) or "alarma puesta" (weak). */
    fun successClaim(raw: String): Claim {
        val tokens = words(fold(raw))
        var claim = Claim.NONE
        fun negated(at: Int) = (maxOf(0, at - 3) until at).any { tokens[it] in NEGATIONS }
        // "ya estaba desactivada", "sigue en marcha": what is already so, not what was done
        fun describesState(at: Int) = (maxOf(0, at - 4) until at).any { k ->
            tokens[k] in STATE_WORDS || (tokens[k] == "ya" && tokens.getOrNull(k + 1)?.startsWith("esta") == true)
        }
        tokens.forEachIndexed { i, token ->
            if ((token == "he" || token == "hemos") && tokens.getOrNull(i + 1) in DONE_PARTICIPLES && !negated(i)) claim = Claim.STRONG
            // "Lista vacía", "lista de la compra" name the shopping list, they do not say "done"
            val namesList = token == "lista" && tokens.getOrNull(i + 1) in setOf("vacia", "de", "tiene", "esta", "con")
            if ((token == "listo" || token == "hecho" || token == "lista") && !namesList && (i == 0 || tokens[i - 1] in setOf("ya", "vale", "perfecto", "bueno")) && !negated(i)) claim = Claim.STRONG
            if (token == "ya" && tokens.getOrNull(i + 1) == "esta" && (i + 2 >= tokens.size || tokens[i + 2] in setOf("puesta", "puesto", "hecho", "lista", "listo")) && !negated(i)) claim = Claim.STRONG
            if (claim == Claim.NONE && (token in DONE_PARTICIPLES || (token == "en" && tokens.getOrNull(i + 1) == "marcha")) && !negated(i) && !describesState(i)) claim = Claim.WEAK
        }
        return claim
    }

    fun wordCount(raw: String): Int = words(fold(raw)).size

    // ---- Tables ----

    private val LEADING_FILLERS = setOf("el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "para", "por", "al", "lo", "mi", "mis")
    // Before a participle they describe how something is ("soy una asistente creada por…"), not an action
    private val STATE_WORDS = setOf("sigue", "siguen", "seguia", "todavia", "aun", "soy", "eres", "es", "fue", "fui")
    private val FRACTIONS = mapOf("media" to 2L, "medio" to 2L, "cuarto" to 4L)
    private val NEGATIONS = setOf("no", "ni", "nunca", "sin", "tampoco", "jamas")
    private val DONE_PARTICIPLES = setOf(
        "puesto", "puesta", "creado", "creada", "programado", "programada", "anadido", "anadida", "apuntado", "apuntada",
        "quitado", "quitada", "borrado", "borrada", "eliminado", "eliminada", "cancelado", "cancelada", "pausado", "pausada",
        "parado", "parada", "detenido", "detenida", "reanudado", "reanudada", "subido", "bajado", "silenciado", "silenciada",
        "cambiado", "cambiada", "actualizado", "actualizada", "movido", "movida", "activado", "activada", "desactivado",
        "desactivada", "reiniciado", "reiniciada", "iniciado", "iniciada", "arrancado", "arrancada", "empezado", "vaciado",
        "vaciada", "marcado", "marcada", "configurado", "configurada", "ajustado", "ajustada", "guardado", "guardada",
        "pospuesto", "pospuesta", "renombrado", "renombrada", "restablecido", "fijado", "fijada",
    )
    private val INABILITY = Regex(
        "\\b(no puedo|no se (hacer|como|poner|abrir|llamar|reproducir|mandar|enviar|consultar|buscar|mirar)|no tengo (acceso|forma|manera|esa|esta|capacidad|conexion|internet|la opcion)|" +
            "no es posible|no me es posible|fuera de (mi|lo que|mis)|no esta a mi alcance|no estan a mi alcance|solo (puedo|se)|todavia no (se|puedo)|aun no (se|puedo)|" +
            "no dispongo|no soy capaz|no esta disponible|sin conexion|no funciono con|no se hacer eso|eso no lo se hacer|no lo puedo|no podria|no tengo esa funcion|no gestiono|no manejo)\\b"
    )
    private val ABSENCE = Regex(
        "\\b(no (tienes|tengo|hay|encuentro|he encontrado|existe|existen|veo|consta|aparece|figura|esta en la lista)|ningun|ninguna|ninguno|esta vacia|vacia|nada en la lista|no queda ninguna)\\b"
    )
    private val MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    private val UNITS = mapOf(
        "cero" to 0L, "un" to 1L, "uno" to 1L, "una" to 1L, "dos" to 2L, "tres" to 3L, "cuatro" to 4L, "cinco" to 5L,
        "seis" to 6L, "siete" to 7L, "ocho" to 8L, "nueve" to 9L, "diez" to 10L, "once" to 11L, "doce" to 12L,
        "trece" to 13L, "catorce" to 14L, "quince" to 15L, "dieciseis" to 16L, "diecisiete" to 17L, "dieciocho" to 18L,
        "diecinueve" to 19L, "veintiun" to 21L, "veintiuno" to 21L, "veintiuna" to 21L, "veintidos" to 22L,
        "veintitres" to 23L, "veinticuatro" to 24L, "veinticinco" to 25L, "veintiseis" to 26L, "veintisiete" to 27L,
        "veintiocho" to 28L, "veintinueve" to 29L,
    )
    private val TENS = mapOf(
        "veinte" to 20L, "treinta" to 30L, "cuarenta" to 40L, "cincuenta" to 50L, "sesenta" to 60L,
        "setenta" to 70L, "ochenta" to 80L, "noventa" to 90L,
    )
    private val HUNDREDS = mapOf(
        "cien" to 100L, "ciento" to 100L, "doscientos" to 200L, "doscientas" to 200L, "trescientos" to 300L,
        "trescientas" to 300L, "cuatrocientos" to 400L, "quinientos" to 500L, "seiscientos" to 600L,
        "setecientos" to 700L, "ochocientos" to 800L, "novecientos" to 900L,
    )
}
