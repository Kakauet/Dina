package com.kakauet.dina.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SpanishTextTest {
    private fun n(text: String) = SpanishText.normalize(text)

    @Test
    fun numberWordsBecomeDigits() {
        assertEquals("84", n("ochenta y cuatro"))
        assertEquals("las 7 y 10", n("las siete y diez"))
        assertEquals("125", n("ciento veinticinco"))
        assertEquals("2.5 kilos", n("dos coma cinco kilos"))
        assertEquals("1200", n("mil doscientos"))
        assertEquals("30 por ciento", n("30%"))
    }

    @Test
    fun clockTimesAreComparedAsTimes() {
        val six = LocalTime.of(6, 0)
        listOf("A las 6:00.", "Suena a las 06:00", "a las seis", "a las seis de la mañana", "Mañana a las 6").forEach {
            assertTrue(it, SpanishText.mentionsTime(n(it), six))
        }
        assertFalse(SpanishText.mentionsTime(n("a las seis de la tarde"), six))
        assertTrue(SpanishText.mentionsTime(n("a las siete de la tarde"), LocalTime.of(19, 0)))
        assertTrue(SpanishText.mentionsTime(n("Alarma puesta para el martes 6 a las 19:00."), LocalTime.of(19, 0)))
        assertTrue(SpanishText.mentionsTime(n("a las siete y media"), LocalTime.of(19, 30)))
        assertTrue(SpanishText.mentionsTime(n("a las ocho menos cuarto"), LocalTime.of(7, 45)))
        assertTrue(SpanishText.mentionsTime(n("a las siete veinte"), LocalTime.of(7, 20)))
        assertTrue(SpanishText.mentionsTime(n("a las doce de la noche"), LocalTime.of(0, 0)))
        assertFalse(SpanishText.mentionsTime(n("Alarma puesta."), LocalTime.of(7, 0)))
    }

    @Test
    fun durationsAreComparedInSeconds() {
        assertTrue(SpanishText.mentionsDuration(n("Quedan 4 minutos y 12 segundos."), 252))
        assertTrue(SpanishText.mentionsDuration(n("quedan cuatro minutos y doce segundos"), 252))
        assertTrue(SpanishText.mentionsDuration(n("quedan unos cuatro minutos"), 252))
        assertTrue(SpanishText.mentionsDuration(n("una hora y media"), 5400))
        assertTrue(SpanishText.mentionsDuration(n("media hora"), 1800))
        assertTrue(SpanishText.mentionsDuration(n("Temporizador de 1 hora y 30 minutos en marcha."), 5400))
        assertTrue(SpanishText.mentionsDuration(n("diez minutos"), 600))
        assertFalse(SpanishText.mentionsDuration(n("cinco minutos"), 600))
        assertTrue(SpanishText.mentionsDuration(n("Temporizador de hora y media para el asado."), 5400))
        assertTrue(SpanishText.mentionsDuration(n("no, de hora y cuarto"), 4500))
        assertEquals(listOf(600L, 1200L), SpanishText.durations(n("Hecho: 10 minutos, 20 minutos.")))
        assertEquals(listOf(4800L), SpanishText.durations(n("1 hora 20 minutos")))
    }

    @Test
    fun numbersDaysDatesAndWords() {
        assertTrue(SpanishText.mentionsNumber(n("El resultado es 84."), 84.0))
        assertTrue(SpanishText.mentionsNumber(n("da 3,33"), 10.0 / 3))
        assertFalse(SpanishText.mentionsNumber(n("da 85"), 84.0))
        assertTrue(SpanishText.mentionsWeekday(n("Hoy es miércoles."), "miercoles"))
        assertTrue(SpanishText.mentionsDate(n("Estamos a 2 de septiembre de 2026"), LocalDate.of(2026, 9, 2)))
        assertTrue(SpanishText.mentionsDate(n("Hoy es 2026-09-02."), LocalDate.of(2026, 9, 2)))
        assertTrue(SpanishText.mentionsPhrase(n("He añadido los tomates a la lista"), "tomate"))
        assertTrue(SpanishText.mentionsPhrase(n("Quito el de la pasta"), "la pasta"))
        assertFalse(SpanishText.mentionsPhrase(n("Quito el del pastel"), "pasta"))
        assertEquals(SpanishText.key("La pasta"), SpanishText.key("pasta"))
        assertEquals(SpanishText.key("huevos"), SpanishText.key("huevo"))
    }

    @Test
    fun negatedClaimsAreNotClaims() {
        assertEquals(SpanishText.Claim.STRONG, SpanishText.successClaim("He añadido café a la lista."))
        assertEquals(SpanishText.Claim.NONE, SpanishText.successClaim("No he añadido café."))
        assertEquals(SpanishText.Claim.NONE, SpanishText.successClaim("No he hecho nada."))
        assertEquals(SpanishText.Claim.WEAK, SpanishText.successClaim("Alarma puesta para las 7."))
        assertEquals(SpanishText.Claim.NONE, SpanishText.successClaim("La alarma no está puesta."))
        assertEquals(SpanishText.Claim.STRONG, SpanishText.successClaim("Listo, ya la tienes."))
        assertEquals(SpanishText.Claim.NONE, SpanishText.successClaim("¿A qué hora la pongo?"))
        assertEquals(SpanishText.Claim.NONE, SpanishText.successClaim("Me llamo Dina, soy una asistente local creada por Kakauet."))
        assertEquals(SpanishText.Claim.NONE, SpanishText.successClaim("Lista vacía, ya no hay nada."))
        assertEquals(SpanishText.Claim.NONE, SpanishText.successClaim("No he pausado nada, el cronómetro sigue en marcha."))
        assertEquals(SpanishText.Claim.NONE, SpanishText.successClaim("Ya estaba desactivada."))
        assertEquals(SpanishText.Claim.STRONG, SpanishText.successClaim("Ya está."))
    }

    @Test
    fun questionsAreNotDetectedByTheLastCharacter() {
        assertTrue(SpanishText.isQuestion("Claro, dime qué quieres calcular."))
        assertTrue(SpanishText.isQuestion("¿A qué hora? Te la pongo enseguida."))
        assertFalse(SpanishText.isQuestion("Hecho, temporizador de diez minutos."))
        assertTrue(SpanishText.expressesInability("Lo siento, no puedo poner música."))
        assertTrue(SpanishText.expressesAbsence("No tienes ningún temporizador."))
    }
}
