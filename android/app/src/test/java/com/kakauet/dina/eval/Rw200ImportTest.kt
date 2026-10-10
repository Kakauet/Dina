package com.kakauet.dina.eval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** RW200 v1 read through the new evaluator: every expectation must be reachable in the real engine. */
class Rw200ImportTest {
    private val dir = File(System.getProperty("dina.root") ?: "../..", "benchmark/realworld200/episodes")

    @Test
    fun allEpisodesImport() {
        val episodes = Rw200.load(dir)
        assertEquals(200, episodes.size)
        assertEquals(377, episodes.sumOf { it.turns.size })
    }

    @Test
    fun oracleScoresEveryEpisode() = runBlocking {
        val runner = EvalRunner(OracleFactory())
        val failures = Rw200.load(dir).map { runner.run(it) }.filterNot { it.pass }
            .map { r -> "${r.episode.id}: ${r.turns.last().verdict.reasons} ${r.turns.last().verdict.diff}" }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
