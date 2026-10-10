package com.kakauet.dina.dialog

import com.kakauet.dina.brain.ToolExecutor
import com.kakauet.dina.brain.dina45.Dina45Codec
import com.kakauet.dina.tools.MINUTE
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.TestTools
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.ToolOutcome
import com.kakauet.dina.tools.ToolSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class DialogueTest {
    // Monday 5 October 2026, 10:00 (Europe/Madrid).
    private val t = TestTools()
    private val d = Dialogue(t.executor, Policies(), seed = 7)

    private fun Dialogue.turn(vararg lines: String): DialogueTurn = run(lines.map { requireNotNull(Dina45Codec.decodeLine(it)) { it } })
    private val world get() = t.engine.state.value
    private fun alarms() = world.alarms.map { (Instant.ofEpochMilli(it.nextMs).atZone(t.zone).toLocalDateTime()) to it.label }
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)

    @Test
    fun aMissingValueIsAskedAndTheAnswerCompletesThePendingAction() {
        val ask = d.turn("alarm.add(day=viernes, \"médico\")")
        assertTrue(ask.answer, ask.answer.contains("hora") && ask.answer.contains("?"))
        assertNotNull(d.pending)
        d.turn("alarm.add(8:00 mañana)")
        assertEquals(listOf(at(9, 8) to "médico"), alarms())
        assertNull(d.pending)
    }

    @Test
    fun pendingSurvivesOneInterruptionRemindsOnceAndExpires() {
        d.turn("alarm.add(day=viernes)")
        val first = d.turn("time.now(hora)")
        assertTrue(first.answer, first.answer.contains("10:00") && first.answer.contains("Por cierto, ¿a qué hora"))
        val second = d.turn("time.now(hora)")
        assertFalse(second.answer, second.answer.contains("Por cierto"))
        d.turn("alarm.add(8:00 mañana)") // three turns later: the Friday is forgotten
        assertEquals(listOf(at(6, 8) to null), alarms())
    }

    @Test
    fun severalCandidatesAreNamedAndAPositionPicksAmongThem() {
        d.turn("alarm.add(7:00 mañana, day=mañana, \"trabajo\")", "alarm.add(19:00, day=mañana, \"gimnasio\")")
        val ask = d.turn("alarm.del()")
        listOf("¿", "7:00", "19:00", "trabajo", "gimnasio").forEach { assertTrue(ask.answer, ask.answer.contains(it)) }
        d.turn("alarm.del(#2)")
        assertEquals(listOf(at(6, 7) to "trabajo"), alarms())
        d.turn("alarm.add(19:00, day=mañana, \"gimnasio\")")
        d.turn("alarm.del(7:00)") // 7:00 and 19:00 both match "las siete"
        assertEquals(2, world.alarms.size)
        d.turn("alarm.del(tarde)")
        assertEquals(listOf(at(6, 7) to "trabajo"), alarms())
    }

    @Test
    fun ampmPolicies() {
        val ask = Dialogue(t.executor, Policies(ampm = AmpmPolicy.ASK), seed = 1)
        val question = ask.turn("alarm.add(7:00)")
        assertTrue(question.answer, question.answer.contains("7:00") && question.answer.contains("19:00"))
        ask.turn("alarm.add(7:00 tarde)")
        assertEquals(listOf(at(5, 19) to null), alarms())

        val next = Dialogue(t.executor, Policies(ampm = AmpmPolicy.NEXT), seed = 1)
        next.turn("alarm.add(9:00, \"clase\")") // 10:00 now: 21:00 today comes first
        assertEquals(at(5, 21) to "clase", alarms().last())

        val mixed = Dialogue(t.executor, Policies(ampm = AmpmPolicy.MIXED), seed = 1)
        mixed.turn("alarm.add(7:00, \"despertarme\")")
        assertEquals(at(6, 7) to "despertarme", alarms().last())
        mixed.turn("alarm.add(6:30, day=mañana)")
        assertEquals(at(6, 6, 30) to null, alarms().last())
    }

    @Test
    fun bulkDeletesDirectlyAndUndoRestoresOrAsksUnderConfirm() {
        listOf("pan", "leche", "huevos").forEach { t.ok(ShoppingCommand.Add(it)) }
        val cleared = d.turn("list.del(*)")
        assertTrue(cleared.answer, cleared.answer.contains("3 cosas"))
        assertTrue(world.shopping.isEmpty())
        val undone = d.turn("undo()")
        assertTrue(undone.answer, undone.answer.startsWith("Deshecho"))
        assertEquals(listOf("pan", "leche", "huevos"), world.shopping.map { it.name })

        val confirm = Dialogue(t.executor, Policies(bulkConfirm = true), seed = 1)
        val question = confirm.turn("list.del(*)")
        assertTrue(question.answer, question.answer.contains("?"))
        assertEquals(3, world.shopping.size)
        confirm.turn("yes()")
        assertTrue(world.shopping.isEmpty())
        confirm.turn("list.add(\"sal\")")
        confirm.turn("list.del(*)") // a single item: no question
        assertTrue(world.shopping.isEmpty())
    }

    @Test
    fun undoRevertsTheLastChangeOfEachKind() {
        assertEquals("No hay nada que deshacer.", d.turn("undo()").answer)
        d.turn("alarm.add(7:00 mañana, day=mañana)")
        d.turn("time.now(hora)") // a read does not replace what undo reverts
        d.turn("undo()")
        assertTrue(world.alarms.isEmpty())

        d.turn("timer.add(10m, \"pasta\")")
        t.advance(2 * MINUTE)
        d.turn("timer.del(\"pasta\")")
        d.turn("undo()")
        assertEquals(8 * MINUTE, world.timers.single().remainingAt(t.now))

        d.turn("vol.set(10)")
        val volume = d.turn("undo()")
        assertTrue(volume.answer, volume.answer.contains("50"))
        assertEquals(50, t.volume.value)

        d.turn("alarm.add(7:00 mañana, day=mañana, \"trabajo\")")
        d.turn("alarm.edit(@, at=8:00)")
        d.turn("undo()")
        assertEquals(listOf(at(6, 7) to "trabajo"), alarms())

        d.turn("list.add(\"agua\", n=6, unit=botella)", "list.add(\"servilletas\")")
        val both = d.turn("undo()").answer
        assertEquals("Deshecho: he quitado agua y servilletas de la lista.", both)
        assertTrue(world.shopping.isEmpty())
    }

    @Test
    fun focusFollowsTheLastItemAndExpires() {
        d.turn("timer.add(10m, \"pasta\")", "timer.add(5m, \"té\")")
        d.turn("timer.plus(@, 2m)")
        assertEquals(7 * MINUTE, world.timers.first { it.label == "té" }.remainingAt(t.now))
        repeat(4) { d.turn("time.now(hora)") }
        val ask = d.turn("timer.plus(@, 1m)")
        assertTrue(ask.answer, ask.answer.contains("pasta") && ask.answer.contains("té") && ask.answer.contains("?"))
    }

    @Test
    fun editsKeepTheDayAndTakeTheCloserHalfOrAShift() {
        d.turn("alarm.add(21:00, day=sábado, \"cena\")")
        d.turn("alarm.edit(\"cena\", at=9:30)")
        assertEquals(listOf(at(10, 21, 30) to "cena"), alarms())
        d.turn("alarm.edit(@, at=-30m)")
        assertEquals(listOf(at(10, 21) to "cena"), alarms())
        d.turn("alarm.edit(@, day=domingo)")
        assertEquals(listOf(at(11, 21) to "cena"), alarms())
        val ask = d.turn("alarm.edit(@, at=?)")
        assertTrue(ask.answer, ask.answer.contains("hora"))
        d.turn("alarm.edit(@, at=22:00)")
        assertEquals(listOf(at(11, 22) to "cena"), alarms())
    }

    @Test
    fun pastAndImpossibleDates() {
        val past = d.turn("alarm.add(7:00 mañana, day=hoy)")
        assertTrue(past.answer, past.answer.contains("ya ha pasado") && past.answer.contains("mañana?"))
        d.turn("yes()")
        assertEquals(listOf(at(6, 7) to null), alarms())
        assertTrue(d.turn("alarm.add(8:00 mañana, day=ayer)").answer.startsWith("No puedo"))
        val impossible = d.turn("alarm.add(10:00 mañana, day=31/2)")
        assertTrue(impossible.answer, impossible.answer.contains("no existe") && impossible.answer.contains("?"))
        d.turn("alarm.add(day=28/2)")
        assertEquals(LocalDateTime.of(2027, 2, 28, 10, 0), alarms().last().first)
    }

    @Test
    fun volumeLimitsAndAlreadyStates() {
        assertTrue(d.turn("vol.set(150)").answer.contains("máximo"))
        assertEquals(100, t.volume.value)
        assertTrue(d.turn("vol.up()").answer.contains("ya está al máximo"))
        d.turn("vol.mute()")
        assertTrue(d.turn("vol.mute()").answer.contains("ya está en silencio"))
        d.turn("alarm.add(8:00 mañana, day=mañana, \"clase\")")
        d.turn("alarm.off(\"clase\")")
        assertTrue(d.turn("alarm.off(\"clase\")").answer.contains("ya estaba desactivada"))
        d.turn("list.add(\"aceite\")", "list.check(\"aceite\")")
        assertTrue(d.turn("list.check(\"aceite\")").answer.contains("ya estaba marcado"))
        assertTrue(d.turn("list.add(\"aceite\")").answer.contains("ya estaba en la lista"))
    }

    @Test
    fun stopRejectionsAndNothingThere() {
        assertTrue(SpanishText.expressesAbsence(d.turn("stop()").answer))
        d.turn("timer.add(1m, \"huevos\")")
        t.advance(2 * MINUTE)
        assertEquals(TimerStatus.RINGING, t.engine.snapshot().world.timers.single().status)
        assertTrue(d.turn("stop()").answer.contains("apagado"))
        assertTrue(world.timers.isEmpty())
        assertTrue(d.turn("no(música)").answer.contains("no puedo poner música", ignoreCase = true))
        assertTrue(d.turn("timer.get(\"arroz\")").answer.startsWith("No tienes ningún temporizador de arroz"))
        assertTrue(d.turn("alarm.del(*)").answer.startsWith("No tienes ninguna alarma"))
    }

    @Test
    fun zeroDurationAsksAndAnotherCreationClosesAnOpenQuestion() {
        val zero = d.turn("timer.add(0s)")
        assertTrue(zero.answer, zero.answer.contains("cero") && zero.answer.contains("?"))
        d.turn("timer.add(5m)")
        assertEquals(1, world.timers.size)
        d.turn("alarm.add()")
        assertNotNull(d.pending)
        d.turn("timer.add(2h)")
        assertNull(d.pending)
    }

    @Test
    fun sayIsFilteredAndChatHasFallbacks() {
        assertEquals("¡Hola! ¿En qué te ayudo?", d.turn("say(\"¡Hola! ¿En qué te ayudo?\")").answer)
        assertTrue(d.turn("say(\"He puesto la alarma.\")").answer in Responder.CHAT)
        assertTrue(d.turn("say(\"Son las siete.\")").answer in Responder.CHAT)
        assertTrue(d.run(emptyList()).answer in Responder.CHAT)
        assertTrue(d.run(emptyList(), misread = true).answer.contains("no te he entendido", ignoreCase = true))
        val withTail = d.turn("timer.add(5m)", "say(\"¡Que aproveche!\")")
        assertTrue(withTail.answer, withTail.answer.endsWith("¡Que aproveche!"))
    }

    @Test
    fun aColetillaMayNameWhatTheTurnWroteButNothingElse() {
        val medico = d.turn("alarm.add(10:00 mañana, day=mañana, \"médico\")", "say(\"¡Suerte en el médico!\")")
        assertTrue(medico.answer, medico.answer.endsWith("¡Suerte en el médico!") && medico.answer.indexOf("10:00") < medico.answer.indexOf("¡Suerte"))
        assertEquals("hecho", medico.brief)
        val other = d.turn("timer.add(5m)", "say(\"¡Y suerte en el médico!\")")
        assertFalse(other.answer, other.answer.contains("Suerte", ignoreCase = true))
        val chat = d.turn("say(\"¿Nervios por el médico? Seguro que va bien.\")")
        assertTrue(chat.answer in Responder.CHAT)
    }

    @Test
    fun theSayGoesBeforeTheQuestionSoTheTurnEndsAsking() {
        d.turn("alarm.add(day=viernes)")
        val answer = d.turn("say(\"¡De nada!\")").answer
        assertTrue(answer, answer.startsWith("¡De nada!") && answer.endsWith("?"))
    }

    @Test
    fun jokesAndFactsComeFromTheEngineBanksWithoutRepeating() {
        val bank = FunBank(listOf("chiste uno", "chiste dos", "chiste tres"), listOf("dato uno"), seed = 3)
        val dialogue = Dialogue(t.executor, Policies(), seed = 3, funBank = bank)
        val told = List(3) { dialogue.turn("fun.joke()") }
        assertEquals(setOf("Chiste uno", "Chiste dos", "Chiste tres"), told.map { it.answer.replaceFirstChar(Char::uppercase) }.toSet())
        assertTrue(told.all { it.brief == "chiste" && it.executions.isEmpty() })
        val fact = dialogue.turn("fun.fact()")
        assertEquals("curiosidad", fact.brief)
        assertTrue(fact.answer, fact.answer.contains("dato uno"))
        val empty = Dialogue(t.executor, Policies(), funBank = FunBank(emptyList(), emptyList())).turn("fun.joke()")
        assertTrue(empty.answer, empty.answer.contains("no me sé ningún chiste"))
    }

    @Test
    fun aDeckTellsEveryLineBeforeRepeatingAndNeverTheSameTwiceInARow() {
        val bank = FunBank((1..20).map { "chiste $it" }, emptyList(), seed = 11)
        val first = List(20) { bank.next(FunBank.Kind.JOKE) }
        assertEquals(20, first.toSet().size)
        val next = bank.next(FunBank.Kind.JOKE)
        assertFalse(next == first.last())
    }

    @Test
    fun theShippedBanksAreLoaded() {
        val bank = FunBank.shipped()
        assertNotNull(bank.next(FunBank.Kind.JOKE))
        assertNotNull(bank.next(FunBank.Kind.FACT))
    }

    @Test
    fun aFailureSaysWhatFailedAfterWhatWasDone() {
        val failing = object : ToolExecutor {
            override fun execute(command: ToolCommand) = if (command.tool == "alarm") ToolOutcome.Failure("permission_denied") else t.engine.execute(command)
            override fun snapshot(): ToolSnapshot = t.engine.snapshot()
        }
        val answer = Dialogue(failing, seed = 1).turn("alarm.add(7:00 mañana)", "list.add(\"café\")").answer
        assertTrue(answer, answer.indexOf("café") < answer.indexOf("no he podido poner la alarma"))
        assertTrue(answer, answer.contains("permiso"))
    }

    @Test
    fun conversionsAskForWhatIsMissingAndAnswerFromTheEngine() {
        val done = d.turn("conv(3.5, milla, km)")
        assertTrue(done.answer, done.answer.contains("3,5 millas") && done.answer.contains("unos 5,63 kilómetros"))
        assertEquals("converter.convert", done.executions.single().let { "${it.tool}.${it.op}" })
        assertEquals("respondido", done.brief)
        // A unit with an everyday counterpart goes there; a metric one asks.
        assertTrue(d.turn("conv(5, libra)").answer.contains("kilos"))
        val where = d.turn("conv(10, km)")
        assertTrue(where.answer, where.answer.contains("?"))
        assertTrue(d.turn("conv(milla)").answer.contains("unas 6,21 millas"))
        // The amount comes later and completes the pending conversion.
        val ask = d.turn("conv(milla, km)")
        assertTrue(ask.answer, ask.answer.startsWith("¿Cuántas millas"))
        assertTrue(d.turn("conv(12)").answer.contains("19,3 kilómetros"))
        // Cups to grams need an ingredient.
        val what = d.turn("conv(2, taza, g)")
        assertTrue(what.answer, what.answer.contains("ingrediente") || what.answer.contains("¿De qué"))
        assertTrue(d.turn("conv(\"harina\")").answer.contains("250 gramos"))
        val mixed = d.turn("conv(2, kg, km)")
        assertTrue(mixed.answer, mixed.answer.contains("No puedo pasar kilos a kilómetros: uno es peso y el otro longitud"))
        assertNull(d.pending)
    }

    @Test
    fun theTimeOfAPlace() {
        val london = d.turn("time.now(hora, \"Londres\")")
        assertEquals("En Londres son las 9:00, una hora menos que aquí.", london.answer)
        assertTrue(d.turn("time.now(hora, \"Tokio\")").answer.contains("17:00, 7 horas más que aquí"))
        assertTrue(d.turn("time.now(hora, \"Villarriba\")").answer.contains("No sé qué hora es en Villarriba"))
        assertTrue(d.turn("time.until(navidad)").answer.contains("81 días"))
    }

    @Test
    fun inTheSmallHoursTomorrowIsTheComingMorning() {
        val night = TestTools(LocalDateTime.of(2026, 11, 14, 1, 30))
        val dialogue = Dialogue(night.executor, Policies(), seed = 2)
        val set = dialogue.turn("alarm.add(8:00 mañana, day=mañana, \"trabajo\")")
        val first = night.engine.state.value.alarms.single()
        assertEquals(LocalDateTime.of(2026, 11, 14, 8, 0), Instant.ofEpochMilli(first.nextMs).atZone(night.zone).toLocalDateTime())
        assertTrue(set.answer, set.answer.contains("hoy a las 8:00, dentro de 6 horas y 30 minutos"))
        // "La de mañana" is that one; an hour already gone today is the next night.
        dialogue.turn("alarm.off(day=mañana)")
        assertEquals(com.kakauet.dina.tools.AlarmStatus.DISABLED, night.engine.state.value.alarms.single().status)
        dialogue.turn("alarm.add(1:00 noche, day=mañana)")
        assertEquals(LocalDateTime.of(2026, 11, 15, 1, 0), Instant.ofEpochMilli(night.engine.state.value.alarms.last().nextMs).atZone(night.zone).toLocalDateTime())
        // From five on, tomorrow is tomorrow.
        night.advance(4 * 60 * MINUTE)
        dialogue.turn("alarm.add(9:00 mañana, day=mañana)")
        assertEquals(LocalDateTime.of(2026, 11, 15, 9, 0), Instant.ofEpochMilli(night.engine.state.value.alarms.last().nextMs).atZone(night.zone).toLocalDateTime())
    }

    @Test
    fun undoingAQuestionForgetsItInsteadOfAnOlderChange() {
        d.turn("list.add(\"leche\")")
        d.turn("alarm.add()")
        assertNotNull(d.pending)
        val undo = d.turn("undo()")
        assertNull(d.pending)
        assertEquals(listOf("leche"), world.shopping.map { it.name })
        assertFalse(undo.answer, undo.answer.contains("Deshecho"))
        // With nothing pending, undo still reverts the last change.
        d.turn("undo()")
        assertTrue(world.shopping.isEmpty())
    }

    @Test
    fun removingARingingTimerTurnsItOff() {
        d.turn("timer.add(1m, \"lentejas\")")
        t.advance(2 * MINUTE)
        val off = d.turn("timer.del(\"lentejas\")")
        assertEquals("timer.dismiss", off.executions.single().let { "${it.tool}.${it.op}" })
        assertTrue(off.answer, off.answer.contains("apagado"))
        assertTrue(world.timers.isEmpty())
    }

    @Test
    fun aDurationOrTheKindOfThingIsNoLabel() {
        d.turn("timer.add(15m, \"15m\")", "sw.add(\"cronómetro\")", "timer.add(5m, \"pasta\")")
        assertEquals(listOf(null, "pasta"), world.timers.map { it.label })
        assertNull(world.stopwatches.single().label)
    }
}
