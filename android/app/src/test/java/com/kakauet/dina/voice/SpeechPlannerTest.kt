package com.kakauet.dina.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlannerTest {
    private val colonText = "Tienes tres alarmas: una a las siete y media, otra a las ocho menos cuarto y la última a las nueve de la mañana."

    private fun texts(text: String, rtf: Double = 0.35) = SpeechPlanner.plan(text, rtf).map { it.text }

    @Test
    fun sentencesAreSeparatedAndTheLastOneAsksForNoPause() {
        val plan = SpeechPlanner.plan("Vale. ¿Qué necesitas?")
        assertEquals(listOf("Vale.", "¿Qué necesitas?"), plan.map { it.text })
        assertEquals(listOf(220, 0), plan.map { it.pauseAfterMs })
    }

    @Test
    fun pausesDependOnPunctuation() {
        val config = SpeechPlanner.Config()
        assertEquals(config.pauseSentenceMs, SpeechPlanner.pauseAfter("Hecho."))
        assertEquals(config.pauseQuestionMs, SpeechPlanner.pauseAfter("¿Cuánto es?"))
        assertEquals(config.pauseExclamationMs, SpeechPlanner.pauseAfter("¡Genial!"))
        assertEquals(config.pauseEllipsisMs, SpeechPlanner.pauseAfter("Bueno…"))
        assertEquals(config.pauseColonMs, SpeechPlanner.pauseAfter("Tienes tres cosas:"))
        assertEquals(config.pauseCommaMs, SpeechPlanner.pauseAfter("Mira,"))
        assertEquals(config.pauseQuestionMs, SpeechPlanner.pauseAfter("Dijo: «¿Vienes?»"))
    }

    @Test
    fun numbersAreReadInWordsBeforeCutting() {
        assertEquals(listOf("Son las siete y cuarto."), texts("Son las 7:15."))
    }

    @Test
    fun aShortFirstSentenceIsNeverCut() {
        assertEquals(listOf("Vale, alarma para mañana a las siete y media."), texts("Vale, alarma para mañana a las siete y media."))
    }

    @Test
    fun aLongFirstSentenceIsCutAtTheFirstPunctuationThatLeavesNoGap() {
        // Cutting at the colon would leave a tail (~92 characters) that takes longer to synthesize than the head lasts.
        val plan = SpeechPlanner.plan(colonText)
        assertEquals(2, plan.size)
        assertEquals("Tienes tres alarmas: una a las siete y media,", plan[0].text)
        assertEquals("otra a las ocho menos cuarto y la última a las nueve de la mañana.", plan[1].text)
        assertEquals(SpeechPlanner.Config().pauseCommaMs, plan[0].pauseAfterMs)
        assertEquals(0, plan[1].pauseAfterMs)
        // On a faster phone the colon is enough.
        assertEquals("Tienes tres alarmas:", texts(colonText, rtf = 0.1).first())
    }

    @Test
    fun noCutIsMadeWhenTheVoiceIsTooSlowToFollow() {
        assertEquals(1, texts(colonText, rtf = 3.0).size)
    }

    @Test
    fun aHeadThatIsTooShortIsSkipped() {
        val text = "Vale, he puesto el temporizador de la pasta, cinco minutos, y te aviso cuando queden treinta segundos."
        val parts = texts(text)
        assertTrue(parts.first().length >= SpeechPlanner.Config().minHead)
        assertNotEquals("Vale,", parts.first())
    }

    @Test
    fun onlyTheFirstSentenceIsCut() {
        val long = "Esta es una frase bastante larga, con una coma en medio y todavía bastante texto después de ella para seguir"
        val plan = texts("$long. $long.")
        assertEquals(3, plan.size)
        assertEquals("$long.", plan[2])
    }

    @Test
    fun blankTextHasNothingToSay() {
        assertTrue(SpeechPlanner.plan("   ").isEmpty())
    }

    @Test
    fun cuttingCanBeSwitchedOff() {
        assertEquals(1, SpeechPlanner.plan(colonText, 0.1, SpeechPlanner.Config(splitFirst = false)).size)
    }
}
