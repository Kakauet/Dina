package com.kakauet.dina.voice

import com.kakauet.dina.voice.SpanishSpeech.Gender
import org.junit.Assert.assertEquals
import org.junit.Test

class SpanishSpeechTest {
    @Test
    fun numbersAgreeWithTheNounAndApocopate() {
        assertEquals("uno", SpanishSpeech.number(1))
        assertEquals("un", SpanishSpeech.number(1, Gender.MASCULINE))
        assertEquals("una", SpanishSpeech.number(1, Gender.FEMININE))
        assertEquals("veintiún", SpanishSpeech.number(21, Gender.MASCULINE))
        assertEquals("veintiuna", SpanishSpeech.number(21, Gender.FEMININE))
        assertEquals("treinta y un", SpanishSpeech.number(31, Gender.MASCULINE))
        assertEquals("cien", SpanishSpeech.number(100))
        assertEquals("ciento uno", SpanishSpeech.number(101))
        assertEquals("doscientas", SpanishSpeech.number(200, Gender.FEMININE))
        assertEquals("quinientos cuarenta y cinco", SpanishSpeech.number(545))
        assertEquals("mil", SpanishSpeech.number(1_000))
        assertEquals("dos mil veintisiete", SpanishSpeech.number(2_027))
        assertEquals("veintiún mil", SpanishSpeech.number(21_000))
        assertEquals("un millón doscientos mil", SpanishSpeech.number(1_200_000))
        assertEquals("dos millones", SpanishSpeech.number(2_000_000))
        assertEquals("menos tres", SpanishSpeech.number(-3))
        assertEquals("cero", SpanishSpeech.number(0))
    }

    @Test
    fun answersAreReadInWordsWithoutChangingTheirContent() {
        val cases = mapOf(
            "Listo: alarma mañana a las 7:15." to "Listo: alarma mañana a las siete y cuarto.",
            "Son las 19:00." to "Son las diecinueve.",
            "Es la 1:05." to "Es la una y cinco.",
            "Temporizador de 1 hora y 30 minutos en marcha." to "Temporizador de una hora y treinta minutos en marcha.",
            "Te quedan 3 minutos y 1 segundo." to "Te quedan tres minutos y un segundo.",
            "Tienes 21 alarmas." to "Tienes veintiuna alarmas.",
            "Falta 1 día: es mañana." to "Falta un día: es mañana.",
            "El 1 de octubre cae en jueves." to "El uno de octubre cae en jueves.",
            "Volumen al máximo, 100 por ciento." to "Volumen al máximo, cien por ciento.",
            "Volumen al 40 %." to "Volumen al cuarenta por ciento.",
            "Apuntado: 2 l de leche y 12 huevos." to "Apuntado: dos litros de leche y doce huevos.",
            "1 kg de manzanas" to "un kilo de manzanas",
            "El resultado es 2,5." to "El resultado es dos coma cinco.",
            "El resultado es 0.05." to "El resultado es cero coma cero cinco.",
            "1,5 kg" to "uno coma cinco kilos",
            "El resultado es -4." to "El resultado es menos cuatro.",
            "Hay +1 h de diferencia." to "Hay más una hora de diferencia.",
            "La de las 7:00 (trabajo), número 2." to "La de las siete (trabajo), número dos.",
            "Hoy es el 25 de diciembre de 2026." to "Hoy es el veinticinco de diciembre de dos mil veintiséis.",
            "Sin números, «pasta»." to "Sin números, «pasta».",
        )
        cases.forEach { (text, spoken) -> assertEquals(text, spoken, SpanishSpeech.expand(text)) }
    }

    @Test
    fun answersSplitIntoSentencesSoTheFirstCanPlayEarly() {
        assertEquals(
            listOf("Hecho.", "Tienes tres cosas en la lista: pan, leche y huevos.", "¿Algo más?"),
            SpanishSpeech.sentences("Hecho. Tienes tres cosas en la lista: pan, leche y huevos.  ¿Algo más?"),
        )
        assertEquals(listOf("Son las 7:15"), SpanishSpeech.sentences(" Son las 7:15 "))
        assertEquals(listOf("El resultado es 2.5, aproximadamente."), SpanishSpeech.sentences("El resultado es 2.5, aproximadamente."))
        val long = (1..40).joinToString(", ") { "cosa $it" } + "."
        val parts = SpanishSpeech.sentences(long, maxChars = 100)
        assertEquals(long, parts.joinToString(" "))
        parts.forEach { assert(it.length <= 100) { it } }
    }
}
