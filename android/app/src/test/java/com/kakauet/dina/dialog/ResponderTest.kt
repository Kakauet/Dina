package com.kakauet.dina.dialog

import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.Calculate
import com.kakauet.dina.tools.Convert
import com.kakauet.dina.tools.DayRef
import com.kakauet.dina.tools.MINUTE
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.TestTools
import com.kakauet.dina.tools.TimerChange
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.Target
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.ToolExecution
import com.kakauet.dina.tools.VolumeCommand
import com.kakauet.dina.tools.WhenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** Every variant of every case states the facts, read with the same detectors that score RW2. */
class ResponderTest {
    private val t = TestTools()
    private val spoken get() = Spoken(t.now, t.zone)

    private fun run(command: ToolCommand) = ToolExecution.of(command, t.engine.execute(command))

    /** All the variants a case produces (rotation goes through them). */
    private fun variants(reply: Reply): Set<String> {
        val responder = Responder(seed = 3)
        return (1..16).map { responder.render(listOf(reply), null, spoken) }.toSet()
    }

    private fun assertFacts(texts: Set<String>, vararg facts: (String) -> Boolean) {
        texts.forEach { text -> facts.forEachIndexed { i, fact -> assertTrue("fact $i missing in «$text»", fact(text)) } }
        texts.forEach { assertTrue(it, SpanishText.wordCount(it) <= 25) }
    }

    private fun time(h: Int, m: Int) = { text: String -> SpanishText.mentionsTime(SpanishText.normalize(text), LocalTime.of(h, m)) }
    private fun duration(seconds: Long) = { text: String -> SpanishText.mentionsDuration(SpanishText.normalize(text), seconds) }
    private fun word(w: String) = { text: String -> SpanishText.mentionsPhrase(SpanishText.normalize(text), w) }
    private fun number(n: Double) = { text: String -> SpanishText.mentionsNumber(SpanishText.normalize(text), n) }

    @Test
    fun calculationsAreSaidWithAtMostTwoDecimals() {
        val exact = variants(Reply.Done(run(Calculate("17*23"))))
        assertFacts(exact, number(391.0))
        exact.forEach { assertFalse(it, "aproximadamente" in it) }
        val rounded = variants(Reply.Done(run(Calculate("17/23"))))
        rounded.forEach { assertTrue(it, it.endsWith("aproximadamente 0,74.")) }
        assertFacts(variants(Reply.Done(run(Calculate("10/4")))), number(2.5))
    }

    @Test
    fun conversionsSayBothAmountsRoundedAndNaturally() {
        fun said(value: Double, from: String, to: String, ingredient: String? = null) = variants(Reply.Done(run(Convert(value, from, to, ingredient))))
        assertFacts(said(3.5, "milla", "km"), word("3,5 millas"), word("unos 5,63 kilómetros"), number(5.63))
        assertFacts(said(180.0, "celsius", "fahrenheit"), word("180 grados celsius"), word("356 grados fahrenheit"))
        said(180.0, "celsius", "fahrenheit").forEach { assertFalse(it, "unos" in it) }
        assertFacts(said(-10.0, "celsius", "fahrenheit"), word("10 grados celsius bajo"), number(14.0))
        assertFacts(said(1.0, "milla", "m"), word("una milla"), number(1609.0))
        assertFacts(said(360.0, "ml", "taza"), word("una taza y media"))
        assertFacts(said(60.0, "ml", "taza"), word("un cuarto de taza"))
        assertFacts(said(0.5, "taza", "ml"), word("media taza"), number(120.0))
        assertFacts(said(1.0, "taza", "g", "harina"), word("una taza de harina"), word("125 gramos"))
        assertFacts(said(1.0, "milla", "km"), word("una milla equivale a unos 1,61 kilómetros").let { phrase -> { text: String -> phrase(text) || word("son unos 1,61 kilómetros")(text) } })
    }

    @Test
    fun conversionProblemsAndQuestionsSayWhy() {
        fun failed(command: Convert) = Responder(1).render(listOf(Reply.Failed(run(command))), null, spoken)
        assertEquals("No puedo pasar kilos a kilómetros: uno es peso y el otro longitud.", failed(Convert(2.0, "kg", "km")))
        assertEquals("No sé cuánto pesa una taza de quinoa; en volumen son 240 mililitros.", failed(Convert(1.0, "taza", "g", "quinoa")))
        assertTrue(failed(Convert(-500.0, "celsius", "kelvin")).contains("cero absoluto"))
        val ask = Responder(1).render(listOf(Reply.AskSlot(Action("conv", null, mapOf("from" to "milla", "to" to "km")), "n")), null, spoken)
        assertTrue(ask, ask.startsWith("¿Cuántas millas"))
    }

    @Test
    fun doneStatesWhatWhenLabelAndValue() {
        val alarm = run(AlarmCommand.Create(WhenSpec.At(LocalTime.of(7, 15), DayRef.Tomorrow), "médico"))
        assertFacts(variants(Reply.Done(alarm)), time(7, 15), word("mañana"), word("médico"))
        val timer = run(TimerCommand.Create(10 * MINUTE, "pasta"))
        assertFacts(variants(Reply.Done(timer)), duration(600), word("pasta"))
        t.advance(MINUTE)
        val plus = run(TimerCommand.Update(Target(id = "t1"), TimerChange.Add(2 * MINUTE)))
        assertFacts(variants(Reply.Done(plus)), duration(660), word("pasta"))
        assertFacts(variants(Reply.Done(run(ShoppingCommand.Add("leche", 2.0, "l")))), word("leche"), word("2 litros"))
        assertFacts(variants(Reply.Done(run(ShoppingCommand.Add("agua", 6.0, "botella")))), word("6 botellas de agua"))
        assertFacts(variants(Reply.Done(run(ShoppingCommand.Add("harina", 1.0, "kg")))), word("1 kilo de harina"))
        assertFacts(variants(Reply.Done(run(VolumeCommand.Set(40)))), number(40.0))
        assertFacts(variants(Reply.Done(run(VolumeCommand.Set(100)), clamped = true)), number(100.0), word("máximo"))
        val created = (alarm.data as com.kakauet.dina.tools.ToolData.AlarmItem).alarm
        val cancel = run(AlarmCommand.Cancel(Target(id = created.id)))
        assertFacts(variants(Reply.Done(cancel, Item(Domain.ALARM, created.id, created.label, created))), word("médico"), time(7, 15))
    }

    @Test
    fun severalAddsBecomeOneSentence() {
        val adds = listOf("leche", "huevos", "pan").map { Reply.Done(run(ShoppingCommand.Add(it))) }
        val text = Responder(1).render(adds, null, spoken)
        assertEquals(1, text.count { it == '.' })
        assertFacts(setOf(text), word("leche"), word("huevos"), word("pan"))
        val alarms = listOf(6 to 0, 6 to 10, 6 to 20).map { (h, m) -> Reply.Done(run(AlarmCommand.Create(WhenSpec.At(LocalTime.of(h, m), DayRef.Tomorrow)))) }
        assertFacts(setOf(Responder(1).render(alarms, null, spoken)), time(6, 0), time(6, 10), time(6, 20), word("3 alarmas"))
    }

    @Test
    fun listsNameUpToSixThenCount() {
        (1..8).forEach { t.ok(ShoppingCommand.Add("cosa$it")) }
        val items = Domain.LIST.items(t.engine.state.value)
        val text = variants(Reply.Listed(Domain.LIST, items)).first()
        assertTrue(text, text.contains("8 cosas") && text.contains("cosa6") && !text.contains("cosa7") && text.contains("y 2 más"))
        assertTrue(SpanishText.expressesAbsence(Responder().render(listOf(Reply.Listed(Domain.LIST, emptyList())), null, spoken)))
        assertTrue(SpanishText.expressesAbsence(Responder().render(listOf(Reply.Listed(Domain.ALARM, emptyList())), null, spoken)))
    }

    @Test
    fun questionsAskForTheSlotAndNameTheCandidates() {
        assertFacts(variants(Reply.AskSlot(Action("alarm.add", null, mapOf("day" to Day.Weekday(java.time.DayOfWeek.FRIDAY))), "time")), { it.contains("hora") && it.contains("?") }, word("viernes"))
        assertFacts(variants(Reply.AskSlot(Action("timer.add"), "dur")), { SpanishText.isQuestion(it) && Regex("cuánt|tiempo|minutos").containsMatchIn(it) })
        assertFacts(variants(Reply.AskSlot(Action("list.add"), "name")), { SpanishText.isQuestion(it) && Regex("apunt|añad").containsMatchIn(it) })
        assertFacts(variants(Reply.AskSlot(Action("vol.set"), "n")), { SpanishText.isQuestion(it) && Regex("volumen|cuánto").containsMatchIn(it) })
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(7, 0), DayRef.Tomorrow), "trabajo"))
        t.ok(AlarmCommand.Create(WhenSpec.At(LocalTime.of(19, 0), DayRef.Tomorrow), "gimnasio"))
        val candidates = Domain.ALARM.items(t.engine.state.value)
        assertFacts(variants(Reply.AskWhich(Action("alarm.del"), candidates)), time(7, 0), time(19, 0), word("trabajo"), word("gimnasio"), { it.contains("Cuál") || it.contains("cuál") })
        assertFacts(variants(Reply.AskAmpm(listOf(LocalTime.of(7, 0), LocalTime.of(19, 0)))), time(7, 0), time(19, 0))
        assertFacts(variants(Reply.AskConfirm(Domain.LIST, 4)), number(4.0), { it.contains("?") })
    }

    @Test
    fun problemsSayWhatWasNotDone() {
        Ops.TOPICS.forEach { topic -> assertTrue(topic, variants(Reply.Reject(topic)).all(SpanishText::expressesInability)) }
        assertTrue(variants(Reply.NotFound(Domain.TIMER, Ref.Named("arroz"))).all { SpanishText.expressesAbsence(it) && it.contains("arroz") })
        assertTrue(variants(Reply.NotFound(Domain.ALARM, Ref.At(Clock(9, 0)))).all { SpanishText.expressesAbsence(it) && it.contains("9:00") })
        assertTrue(variants(Reply.NothingRinging).all(SpanishText::expressesAbsence))
        val failed = ToolExecution.of(ShoppingCommand.Add("pan"), com.kakauet.dina.tools.ToolOutcome.Failure("unavailable"))
        assertTrue(variants(Reply.Failed(failed)).all { it.startsWith("No he podido apuntar pan") })
        val zero = ToolExecution.of(com.kakauet.dina.tools.Calculate("7/0"), t.engine.execute(com.kakauet.dina.tools.Calculate("7/0")))
        assertTrue(variants(Reply.Failed(zero)).all { SpanishText.expressesInability(it) && it.contains("entre cero") })
    }

    @Test
    fun rotationNeverRepeatsEitherOfTheLastTwo() {
        val responder = Responder(seed = 11)
        val picks = (1..200).map { responder.pick("case", listOf("a", "b", "c", "d")) }
        picks.windowed(3).forEach { (a, b, c) -> assertNotEquals(a, c); assertNotEquals(b, c) }
        assertEquals(picks, Responder(seed = 11).let { r -> (1..200).map { r.pick("case", listOf("a", "b", "c", "d")) } })
        val two = Responder(seed = 2).let { r -> (1..10).map { r.pick("two", listOf("x", "y")) } }
        two.windowed(2).forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test
    fun sayKeepsChatAndDropsFactsOrClaims() {
        assertEquals("¡Hola! ¿Qué tal?", SayFilter.accept("¡Hola! ¿Qué tal?", emptyList()))
        assertEquals("No, soy una asistente de voz.", SayFilter.accept("No, soy una asistente de voz.", emptyList()))
        assertNull(SayFilter.accept("Te despierto a las 7.", emptyList()))
        assertNull(SayFilter.accept("Faltan tres días.", emptyList()))
        assertNull(SayFilter.accept("Ya tienes la pasta en marcha.", listOf("pasta")))
        assertNull(SayFilter.accept("He apuntado todo.", emptyList()))
        assertNull(SayFilter.accept("Alarma puesta.", emptyList()))
        assertNull(SayFilter.accept("", emptyList()))
        assertFalse(SayFilter.accept("¡Suerte en el médico!", emptyList()).isNullOrEmpty())
    }

    @Test
    fun pureChatMayUseNumbersAndTalkLongerButNeverTimesNamesOrClaims() {
        val joke = "Llevo dos días aprendiendo chistes nuevos y ya me sé tres, ¿quieres oír uno?"
        assertNull(SayFilter.accept(joke, emptyList()))
        assertEquals(joke, SayFilter.accept(joke, emptyList(), chatOnly = true))
        val long = List(40) { "bla" }.joinToString(" ")
        assertNull(SayFilter.accept(long, emptyList()))
        assertEquals(long, SayFilter.accept(long, emptyList(), chatOnly = true))
        assertNull(SayFilter.accept(List(51) { "bla" }.joinToString(" "), emptyList(), chatOnly = true))
        assertNull(SayFilter.accept("Mañana a las 7 te despierto.", emptyList(), chatOnly = true))
        assertNull(SayFilter.accept("Seguro que la pasta te sale rica.", listOf("pasta"), chatOnly = true))
        assertNull(SayFilter.accept("Ya te he apuntado 3 cosas.", emptyList(), chatOnly = true))
    }

    @Test
    fun dinaNeverClaimsToBeAPerson() {
        assertEquals(SayFilter.IDENTITY, SayFilter.accept("Sí, soy una persona. Aquí estoy para ayudarte.", emptyList(), chatOnly = true))
        assertEquals(SayFilter.IDENTITY, SayFilter.accept("Claro, soy humana como tú.", emptyList(), chatOnly = true))
        assertEquals(SayFilter.IDENTITY, SayFilter.accept("¡Somos de carne y hueso!", emptyList()))
        val honest = "No, no soy una persona: soy una asistente de voz."
        assertEquals(honest, SayFilter.accept(honest, emptyList(), chatOnly = true))
        assertEquals("Eres una persona muy maja.", SayFilter.accept("Eres una persona muy maja.", emptyList(), chatOnly = true))
    }
}
