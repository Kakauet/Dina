package com.kakauet.dina.data

import com.kakauet.dina.brain.dina45.Dina45Codec
import com.kakauet.dina.brain.dina45.Dina45Grammar
import com.kakauet.dina.brain.dina45.Dina45Prompt
import com.kakauet.dina.brain.dina45.Gbnf
import com.kakauet.dina.llm.LlmMetrics
import com.kakauet.dina.llm.LlmResult
import com.kakauet.dina.llm.TextGenerator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataPipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scenarios by lazy { ScenarioGenerator(seed = 7).generate(120, "t") }

    @Test
    fun scenariosCoverTheMatrixAndTheirLabelsAreTheContract() {
        val grammar = Regex(Gbnf.toRegex(Dina45Grammar.GBNF))
        assertEquals(120, scenarios.size)
        val phenomena = scenarios.groupingBy { it.phenomenon }.eachCount()
        assertTrue(phenomena.toString(), phenomena.size >= 10)
        assertTrue(scenarios.any { it.turns.size > 1 })
        val dev = scenarios.count { it.split == "dev" }
        assertTrue("dev $dev", dev in 1..29)
        scenarios.flatMap { it.turns }.forEach { turn ->
            val text = Dina45Codec.encode(turn.actions)
            assertTrue(text, grammar.matches(text))
            assertEquals(turn.actions, Dina45Codec.decode(text).actions)
            assertFalse(turn.ficha, turn.ficha.isBlank())
        }
        // Same seed, same data.
        assertEquals(scenarios.map { DataSteps.toJson(it).toString() }, ScenarioGenerator(seed = 7).generate(120, "t").map { DataSteps.toJson(it).toString() })
    }

    @Test
    fun theNewMixesUseEveryTemplateAndWriteTheContract() {
        val grammar = Regex(Gbnf.toRegex(Dina45Grammar.GBNF))
        for ((mix, n) in listOf(FocusTemplates.MIX to 500, ConversionTemplates.MIX to 300)) {
            val generator = ScenarioGenerator(seed = 3, mix = mix)
            val made = generator.generate(n, mix, devShare = 0.0)
            assertEquals(n, made.size)
            val names = ScenarioGenerator.TEMPLATES.filter { it.mix == mix }.map { it.name }.toSet()
            val rejected = generator.rejected.filterKeys { it in names }
            // Every template replays on the engine most of the time.
            names.forEach { name ->
                val tried = generator.attempts[name] ?: 0
                assertTrue("$mix/$name never tried", tried > 0)
                assertTrue("$mix/$name rejected ${rejected[name]} of $tried", (rejected[name] ?: 0) * 2 < tried)
            }
            made.flatMap { it.turns }.forEach { turn ->
                val text = Dina45Codec.encode(turn.actions)
                assertTrue(text, grammar.matches(text))
                assertEquals(turn.actions, Dina45Codec.decode(text).actions)
            }
        }
    }

    @Test
    fun roundTripAcceptsEquivalentActionsAndRejectsOthers() {
        val dir = tmp.newFolder("b")
        val alarm = scenarios.first { it.turns.size == 1 && it.turns[0].actions.singleOrNull()?.op == "alarm.add" && (it.turns[0].actions[0].clock()?.period ?: com.kakauet.dina.dialog.Period.MORNING) != com.kakauet.dina.dialog.Period.MORNING }
        val clock = alarm.turns[0].actions[0].clock()!!
        val hour24 = clock.times().single()
        val same = alarm.turns[0].actions[0].with("time", com.kakauet.dina.dialog.Clock(hour24.hour, hour24.minute))
        val other = alarm.turns[0].actions[0].with("time", com.kakauet.dina.dialog.Clock(hour24.plusHours(1).hour, hour24.minute))
        val listed = scenarios.first { it.turns.size == 1 && it.turns[0].actions.singleOrNull()?.op == "list.add" }
        val name = listed.turns[0].actions[0].text("name")!!
        File(dir, "fichas.jsonl").writeText(listOf(alarm, listed).joinToString("") { DataSteps.toJson(it).toString() + "\n" })
        val rows = listOf(
            Triple(alarm.id, 1, Dina45Codec.encode(listOf(same))),
            Triple(alarm.id, 2, Dina45Codec.encode(listOf(other))),
            Triple(alarm.id, 3, "pon una alarma"),
            Triple(listed.id, 1, "list.add(\"$name\")"),
            Triple(listed.id, 2, "list.add(\"$name\")\nlist.add(\"pan de molde\")"),
        )
        File(dir, "roundtrip.jsonl").writeText(rows.joinToString("") { (id, v, text) ->
            JSONObject().put("id", "$id/1/$v").put("scenario", id).put("turn", 1).put("variant", v).put("actions", text).toString() + "\n"
        })
        DataSteps.verify(dir)
        val verdicts = DataSteps.jsonl(File(dir, "verified.jsonl")).associate { row ->
            val id = row.getString("id")
            (id.substringAfterLast('/').toInt() + if (id.startsWith(listed.id)) 10 else 0) to row.getBoolean("ok")
        }
        assertEquals(mapOf(1 to true, 2 to false, 3 to false, 11 to (listed.turns[0].actions[0].number() == null), 12 to false), verdicts)
    }

    @Test
    fun roundTripComparesResultsNotHowTheyWereAsked() {
        val scenario = scenarios.first { it.turns.size == 1 }
        fun same(expected: String, output: String) = DataSteps.compare(scenario, 1, output, expected = Dina45Codec.decode(expected).actions) == "igual"
        assertTrue(same("calc(\"45*13/100\")", "calc(\"45 / 100 * 13\")"))
        assertTrue(same("conv(500, g, taza, \"mantequilla\")", "conv(0.5, kg, taza, \"mantequilla\")"))
        assertFalse(same("conv(500, g, taza, \"mantequilla\")", "conv(1, kg, taza, \"mantequilla\")"))
        assertFalse(same("conv(500, g, taza, \"mantequilla\")", "conv(500, g, taza, \"harina\")"))
        assertFalse(same("calc(\"45*13/100\")", "calc(\"45*13\")"))
    }

    @Test
    fun renderUsesTheAppPromptAndCanonicalCompletions() {
        val dir = tmp.newFolder("r")
        val multi = scenarios.first { it.turns.size == 2 }
        File(dir, "fichas.jsonl").writeText(DataSteps.toJson(multi).toString() + "\n")
        File(dir, "selected.jsonl").writeText(JSONObject().put("scenario", multi.id).put("variant", 0).put("texts", org.json.JSONArray(listOf("primera  frase", "y la segunda"))).toString() + "\n")
        DataSteps.render(dir)
        val out = DataSteps.jsonl(File(dir, if (multi.split == "dev") "dev.jsonl" else "train.jsonl"))
        assertEquals(2, out.size)
        assertTrue(out[0].getString("prompt").startsWith("<|startoftext|><|im_start|>system\n${Dina45Prompt.SYSTEM}<|im_end|>"))
        assertTrue(out[0].getString("prompt").endsWith("[ahora]\nprimera frase<|im_end|>\n<|im_start|>assistant\n"))
        assertTrue(out[1].getString("prompt").contains("[antes]\nusuario: primera frase\ndina: " + out[0].getString("completion").replace("\n", " ; ")))
        assertEquals(Dina45Codec.encode(multi.turns[1].actions), out[1].getString("completion"))
        DataSteps.render(dir, history = 0)
        val noHistory = DataSteps.jsonl(File(dir, if (multi.split == "dev") "dev-h0.jsonl" else "train-h0.jsonl"))
        assertTrue(noHistory.none { it.getString("prompt").contains("[antes]") })
    }

    private val many by lazy { ScenarioGenerator(seed = 3).generate(600, "c") }

    @Test
    fun chatIsAboutOneTenthAndItsRepliesAreLeftToTheGenerator() {
        val chat = many.filter { it.phenomenon == "charla" }
        assertTrue("${chat.size}", chat.size in 40..100)
        val turns = chat.flatMap { it.turns }
        assertTrue(turns.any { it.reply == TurnSpec.CHAT && it.actions.single().op == "say" })
        assertTrue(turns.any { it.reply == TurnSpec.COLETILLA && it.actions.size == 2 && it.actions.last().op == "say" })
        assertTrue(turns.any { it.actions.singleOrNull()?.op == "fun.joke" } && turns.any { it.actions.singleOrNull()?.op == "fun.fact" })
        val json = DataSteps.toJson(chat.first { s -> s.turns.any { it.reply != null } })
        val back = DataSteps.fromJson(json)
        assertEquals(json.getJSONArray("turns").getJSONObject(back.turns.indexOfFirst { it.reply != null }).getString("reply"), back.turns.first { it.reply != null }.reply)
    }

    @Test
    fun roundTripReadsChatAsChatAndChecksTheGeneratedReplyAgainstTheSayFilter() {
        val dir = tmp.newFolder("c")
        val chat = many.first { it.turns.size == 1 && it.turns[0].reply == TurnSpec.CHAT }
        val tail = many.first { it.turns.size == 1 && it.turns[0].reply == TurnSpec.COLETILLA }
        File(dir, "fichas.jsonl").writeText(listOf(chat, tail).joinToString("") { DataSteps.toJson(it).toString() + "\n" })
        val core = Dina45Codec.encode(tail.turns[0].actions.filter { it.op != "say" })
        val rows = listOf(
            Triple(chat.id, 1, "say(\"Buenas, ¿qué tal?\")") to "¡Hola! Aquí estoy, con ganas de echarte una mano.",
            Triple(chat.id, 2, "alarm.list()") to "¡Hola!",
            Triple(chat.id, 3, "say(\"Hola\")") to "Mañana a las 7 te despierto, tranquilo.",
            Triple(tail.id, 1, core) to "¡Mucho ánimo!",
            Triple(tail.id, 2, "$core\nsay(\"¡Suerte!\")") to "He apuntado lo que me has dicho.",
        )
        File(dir, "roundtrip.jsonl").writeText(rows.joinToString("") { (r, _) ->
            JSONObject().put("id", "${r.first}/1/${r.second}").put("scenario", r.first).put("turn", 1).put("variant", r.second).put("actions", r.third).toString() + "\n"
        })
        File(dir, "phrases.jsonl").writeText(rows.joinToString("") { (r, reply) ->
            JSONObject().put("id", "${r.first}/1/${r.second}").put("text", "frase").put("reply", reply).toString() + "\n"
        })
        DataSteps.verify(dir)
        val why = DataSteps.jsonl(File(dir, "verified.jsonl")).associate { it.getString("id").substringBefore("/1/").let { s -> if (s == chat.id) "c" else "t" } + it.getString("id").last() to it.getString("why") }
        assertEquals("igual", why["c1"])
        assertTrue(why["c2"], why["c2"]!!.startsWith("distinto"))
        assertEquals("say filtrado", why["c3"])
        assertEquals("igual", why["t1"])
        assertEquals("say filtrado", why["t2"])
    }

    @Test
    fun renderPutsEachPhraseOwnReplyInTheCompletion() {
        val dir = tmp.newFolder("rr")
        val chat = many.first { it.turns.size == 1 && it.turns[0].reply == TurnSpec.CHAT }
        File(dir, "fichas.jsonl").writeText(DataSteps.toJson(chat).toString() + "\n")
        File(dir, "selected.jsonl").writeText(JSONObject().put("scenario", chat.id).put("variant", 0).put("split", chat.split)
            .put("texts", JSONArray(listOf("hola guapa"))).put("replies", JSONArray(listOf("¡Hola! ¿Qué tal te va el día?"))).toString() + "\n")
        DataSteps.render(dir)
        val out = DataSteps.jsonl(File(dir, if (chat.split == "dev") "dev.jsonl" else "train.jsonl")).single()
        assertEquals("say(\"¡Hola! ¿Qué tal te va el día?\")", out.getString("completion"))
    }

    /** Answers each prompt with the canonical completion of its phrase (or [fixed]), streaming like the JNI engine. */
    private class LookupModel(private val answers: Map<String, String>, private val fixed: String? = null) : TextGenerator {
        val prompts = mutableListOf<String>()
        override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit, grammar: String?): LlmResult {
            prompts += prompt
            val user = prompt.substringAfterLast("[ahora]\n").substringBefore("<|im_end|>")
            return LlmResult(fixed ?: answers.getValue(user), LlmMetrics())
        }
    }

    @Test
    fun theDevSetScoresAModelByOutcomeWithCanonicalEarlierTurns() {
        val dir = tmp.newFolder("d")
        val picked = many.filter { s -> s.turns.none { it.reply != null } }.take(30) + many.filter { s -> s.turns.any { it.reply == TurnSpec.CHAT } }.take(3)
        File(dir, "fichas.jsonl").writeText(picked.joinToString("") { DataSteps.toJson(it).toString() + "\n" })
        val answers = mutableMapOf<String, String>()
        File(dir, "selected.jsonl").writeText(picked.joinToString("") { s ->
            // Each phrase names what it asks for, as real ones do (the brain drops products a phrase never said).
            val texts = s.turns.indices.map { "frase ${s.id} $it " + Dina45Codec.encode(s.turns[it].actions).replace('\n', ' ') }
            val replies = s.turns.map { t -> if (t.reply != null) "¡Qué bien que me lo cuentes!" else null }
            texts.forEachIndexed { i, text -> answers[text] = Dina45Codec.encode(Replay.withReply(s.turns[i].actions, replies[i])) }
            JSONObject().put("scenario", s.id).put("variant", 0).put("split", "dev").put("texts", JSONArray(texts)).put("replies", JSONArray(replies.map { it ?: JSONObject.NULL })).toString() + "\n"
        })
        val perfect = LookupModel(answers)
        val good = DataSteps.dev(dir, perfect)
        assertEquals(picked.size, good.conversations.size)
        assertEquals(good.turns.size, good.turnsOk)
        assertTrue(perfect.prompts.all { it.startsWith("<|startoftext|><|im_start|>system\n${Dina45Prompt.SYSTEM}") })
        val lost = DataSteps.dev(dir, LookupModel(answers, fixed = "ask()"))
        assertTrue("${lost.conversationsOk}", lost.conversationsOk < picked.size / 3)
        // A chat answer the say filter drops is not chat any more.
        val chatOnly = good.turns.filter { it.expected.startsWith("say(") }
        assertTrue(chatOnly.isNotEmpty() && chatOnly.all { it.ok })
        val filtered = DataSteps.dev(dir, LookupModel(answers.mapValues { (_, v) -> if (v.startsWith("say(")) "say(\"Te despierto a las 7.\")" else v }))
        assertTrue(filtered.turns.filter { it.expected.startsWith("say(") }.none { it.ok })
    }
}
