package com.kakauet.dina.eval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Dina-Real cards are checked without their phrases: the oracle never reads what the user said. */
class DinaRealTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cards = File(System.getProperty("dina.root") ?: "../..", "benchmark/dina_real/fichas.jsonl")
    private val placeholders by lazy { Regex("\"u\":\"(F\\d{3}[a-h])\"").findAll(cards.readText()).map { it.groupValues[1] }.toList() }

    private fun withPhrases(rows: List<String>): List<Episode> {
        cards.copyTo(File(tmp.root, "fichas.jsonl"))
        File(tmp.root, "frases.tsv").writeText((listOf("frase_id\tpersona\tfrase") + rows).joinToString("\n"))
        return DinaReal.load(tmp.root)
    }

    @Test
    fun everyCardIsCoherentUnderEveryPolicy() = runBlocking {
        val episodes = withPhrases(placeholders.map { "$it\tprueba\tfrase de prueba" })
        assertEquals(100, episodes.size)
        val problems = mutableListOf<String>()
        for (policies in BenchmarkV2Test.POLICIES) {
            val runner = EvalRunner(OracleFactory(), policies)
            for (episode in episodes) for (path in EvalRunner.paths(episode, policies)) {
                val record = try { runner.run(episode, path) } catch (e: OracleError) { problems += "${episode.id}: ${e.message}"; continue }
                if (!record.pass) problems += "${episode.id} $path: ${record.turns.last().verdict.reasons}"
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun cardsWithoutAPhraseAreSkippedPerSpeaker() {
        val episodes = withPhrases(listOf("F001a\tAna\tdespiértame a las siete y cuarto", "F002a\tLuis\tnueve minutos para la pasta", "F029a\tLuis\tsolo la primera"))
        assertEquals(listOf("F001@Ana", "F002@Luis"), episodes.map { it.id })
        assertEquals("despiértame a las siete y cuarto", episodes.first().turns.single().user)
    }
}
