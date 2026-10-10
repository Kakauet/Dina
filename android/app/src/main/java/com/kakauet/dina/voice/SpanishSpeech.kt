package com.kakauet.dina.voice

/**
 * Spanish text as a character-level voice should read it (Supertonic has no phonemizer): times,
 * numbers, units and percentages in words, and the answer split into sentences so the first one
 * can play while the rest is synthesized. Only pronunciation changes: what Dina says still comes
 * from Responder/ToolPresentation, and the screen shows it unchanged.
 */
object SpanishSpeech {
    /** "uno" (alone), "un" (before a masculine noun), "una" (before a feminine noun). */
    enum class Gender { NEUTRAL, MASCULINE, FEMININE }

    /** Sentences of [text] in order; one longer than [maxChars] is cut at a comma. */
    fun sentences(text: String, maxChars: Int = 300): List<String> =
        text.trim().split(SENTENCE_END).map(String::trim).filter(String::isNotEmpty).flatMap { split(it, maxChars) }

    fun expand(text: String): String {
        var out = TIME.replace(text) { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            if (hour > 24 || minute > 59) m.value else time(hour, minute)
        }
        out = PERCENT.replace(out) { m -> decimal(m.groupValues[1]) + " por ciento" }
        out = DECIMAL.replace(out) { m ->
            val unit = UNITS[m.groupValues[3]]?.second?.let { " $it" }.orEmpty()
            (if (m.groupValues[1].isEmpty()) "" else "menos ") + decimal(m.groupValues[2]) + unit
        }
        out = INTEGER.replace(out) { m ->
            val sign = when (m.groupValues[1]) { "-", "−" -> "menos "; "+" -> "más "; else -> "" }
            val digits = m.groupValues[2]
            val value = digits.toLongOrNull()?.takeIf { it < 1_000_000_000_000L } ?: return@replace m.value
            val next = m.groupValues[3]
            val unit = UNITS[next]
            val noun = unit?.let { if (value == 1L) it.first else it.second } ?: next
            val words = number(value, if (unit != null || next.isNotEmpty()) gender(noun) else Gender.NEUTRAL)
            sign + words + if (next.isEmpty()) "" else " $noun"
        }
        return out
    }

    /** Cardinal in words: 21 → "veintiuno" / "veintiún" / "veintiuna", 100 → "cien", 2027 → "dos mil veintisiete". */
    fun number(n: Long, gender: Gender = Gender.NEUTRAL): String = when {
        n < 0 -> "menos " + number(-n, gender)
        n == 0L -> "cero"
        n < 1_000 -> below1000(n.toInt(), gender)
        n < 1_000_000 -> {
            val thousands = (n / 1_000).toInt()
            val head = if (thousands == 1) "mil" else below1000(thousands, if (gender == Gender.FEMININE) Gender.FEMININE else Gender.MASCULINE) + " mil"
            listOf(head, below1000((n % 1_000).toInt(), gender)).filter(String::isNotEmpty).joinToString(" ")
        }
        else -> {
            val millions = n / 1_000_000
            val head = if (millions == 1L) "un millón" else number(millions, Gender.MASCULINE) + " millones"
            val rest = n % 1_000_000
            if (rest == 0L) head else "$head ${number(rest, gender)}"
        }
    }

    /** "7:15" → "siete y cuarto", "19:00" → "diecinueve", "1:05" → "una y cinco". Hours are feminine (las horas). */
    fun time(hour: Int, minute: Int): String {
        val h = number(hour.toLong(), Gender.FEMININE)
        return when (minute) {
            0 -> h
            15 -> "$h y cuarto"
            30 -> "$h y media"
            else -> "$h y ${number(minute.toLong())}"
        }
    }

    /** "2,5" → "dos coma cinco", "0,05" → "cero coma cero cinco". */
    private fun decimal(text: String): String {
        val parts = text.split(',', '.')
        val whole = parts[0].toLongOrNull()?.let { number(it) } ?: parts[0]
        val fraction = parts.getOrNull(1) ?: return whole
        val read = if (fraction.length <= 2 && !fraction.startsWith("0")) number(fraction.toLong())
        else fraction.map { UNITS_WORDS[it - '0'] }.joinToString(" ")
        return "$whole coma $read"
    }

    private fun below1000(n: Int, gender: Gender): String {
        if (n == 0) return ""
        if (n == 100) return "cien"
        val hundreds = HUNDREDS[n / 100].let { if (gender == Gender.FEMININE) it.replace("cientos", "cientas") else it }
        val rest = n % 100
        val restWords = when {
            rest == 0 -> ""
            rest < 30 -> UNITS_WORDS[rest]
            rest % 10 == 0 -> TENS[rest / 10]
            else -> "${TENS[rest / 10]} y ${UNITS_WORDS[rest % 10]}"
        }
        val agreed = when {
            gender == Gender.NEUTRAL || !restWords.endsWith("uno") -> restWords
            gender == Gender.FEMININE -> restWords.dropLast(1) + "a"
            restWords == "veintiuno" -> "veintiún"
            else -> restWords.dropLast(1)
        }
        return listOf(hundreds, agreed).filter(String::isNotEmpty).joinToString(" ")
    }

    /** Gender of the word after a number: nouns agree ("una alarma", "un día"); "de", "y"… leave it alone ("uno de octubre"). */
    private fun gender(word: String): Gender {
        val w = word.lowercase()
        return when {
            w in FUNCTION_WORDS -> Gender.NEUTRAL
            w in FEMININE_NOUNS -> Gender.FEMININE
            w in MASCULINE_IN_A -> Gender.MASCULINE
            w.endsWith("a") || w.endsWith("as") -> Gender.FEMININE
            else -> Gender.MASCULINE
        }
    }

    private fun split(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val cut = sentence.lastIndexOf(", ", maxChars).takeIf { it > 0 } ?: sentence.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
        return listOf(sentence.substring(0, cut + 1).trim()) + split(sentence.substring(cut + 1).trim(), maxChars)
    }

    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
    private val TIME = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])")
    private val PERCENT = Regex("(?<![\\d,.])(\\d+(?:[.,]\\d+)?)\\s*%")
    private val DECIMAL = Regex("(?<![\\w,.])([-−]?)(\\d+[.,]\\d+)(?![\\d.,]*\\d)(?:\\s+(kg|g|l|ml|h|min|s|km|m|cm)\\b)?")
    /** A number and the word after it (unit or noun), if any. */
    private val INTEGER = Regex("(?<![\\w,.])([-−+]?)(\\d+)(?!\\d)(?![,.]\\d)(?:\\s+(\\p{L}+))?")

    private val UNITS = mapOf(
        "h" to ("hora" to "horas"), "min" to ("minuto" to "minutos"), "s" to ("segundo" to "segundos"),
        "seg" to ("segundo" to "segundos"), "l" to ("litro" to "litros"), "ml" to ("mililitro" to "mililitros"),
        "kg" to ("kilo" to "kilos"), "g" to ("gramo" to "gramos"), "km" to ("kilómetro" to "kilómetros"),
        "m" to ("metro" to "metros"), "cm" to ("centímetro" to "centímetros"),
    )
    private val FUNCTION_WORDS = setOf("de", "del", "y", "e", "o", "u", "a", "al", "en", "por", "para", "con", "que", "es", "son", "más", "menos", "entre", "sobre", "hasta", "desde")
    private val FEMININE_NOUNS = setOf("vez", "veces", "unidad", "unidades", "canción", "canciones", "noche", "noches", "tarde", "tardes", "ración", "raciones")
    private val MASCULINE_IN_A = setOf("día", "días", "mapa", "mapas", "problema", "problemas", "programa", "programas", "sistema", "sistemas", "tema", "temas", "idioma", "idiomas", "clima", "planeta", "planetas", "sofá", "sofás", "pijama", "pijamas")
    private val UNITS_WORDS = listOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece", "catorce",
        "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós", "veintitrés",
        "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve",
    )
    private val TENS = listOf("", "diez", "veinte", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val HUNDREDS = listOf("", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos")
}
