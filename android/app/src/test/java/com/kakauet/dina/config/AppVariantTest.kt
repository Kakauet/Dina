package com.kakauet.dina.config

import com.kakauet.dina.brain.BrainRegistry
import com.kakauet.dina.brain.dina45.Dina45Brain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppVariantTest {
    @Test
    fun theFullEditionKeepsTheFlagshipThreadsWhateverTheCores() {
        listOf(1, 2, 4, 6, 8).forEach { fast ->
            assertEquals(6, AppVariant.defaultLlmThreads(fast, lite = false))
            assertEquals(4, AppVariant.defaultVoiceThreads(fast, lite = false))
        }
    }

    @Test
    fun fullSpeaksWithEightStepsAndLiteWithFour() {
        assertEquals(8, AppVariant.defaultSteps(lite = false))
        assertEquals(4, AppVariant.defaultSteps(lite = true))
        assertEquals(8, AppVariant.defaultSteps) // the unit tests run the full edition
    }

    @Test
    fun fullOffersFourOrEightStepsAndLiteOnlyFour() {
        assertEquals(listOf(4, 8), AppVariant.stepChoices(lite = false).map { it.steps })
        assertEquals(listOf(4), AppVariant.stepChoices(lite = true).map { it.steps })
        assertEquals(4, AppVariant.allowedSteps(4, lite = false))
        assertEquals(8, AppVariant.allowedSteps(8, lite = false))
        assertEquals(8, AppVariant.allowedSteps(7, lite = false))
        assertEquals(4, AppVariant.allowedSteps(6, lite = false)) // a tie goes to the faster
        assertEquals(4, AppVariant.allowedSteps(2, lite = false))
        listOf(2, 4, 8).forEach { assertEquals(4, AppVariant.allowedSteps(it, lite = true)) }
    }

    @Test
    fun theLiteEditionUsesTheFastCoresBetweenTwoAndFour() {
        // The PC shows 1 -> 2 threads halving the time and nothing gained past 4 (docs/HISTORY.md, app 2.4).
        assertEquals(2, AppVariant.defaultLlmThreads(1, lite = true))
        assertEquals(2, AppVariant.defaultLlmThreads(2, lite = true))
        assertEquals(3, AppVariant.defaultLlmThreads(3, lite = true))
        assertEquals(4, AppVariant.defaultLlmThreads(4, lite = true))
        assertEquals(4, AppVariant.defaultLlmThreads(6, lite = true))
        assertEquals(2, AppVariant.defaultVoiceThreads(2, lite = true))
        assertEquals(3, AppVariant.defaultVoiceThreads(3, lite = true))
        assertEquals(4, AppVariant.defaultVoiceThreads(8, lite = true))
    }

    @Test
    fun fastCoresAreTheOnesWithAtLeastHalfOfTheBiggestCapacity() {
        // Galaxy S24 Ultra: 1 x X4, 2 + 3 x A720, 2 x A520 -> the six that the full edition uses.
        assertEquals(6, AppVariant.fastCores(listOf(280, 280, 760, 760, 760, 800, 800, 1024), cores = 8))
        // Two big and six little cores (Helio G99 class).
        assertEquals(2, AppVariant.fastCores(listOf(1024, 1024, 400, 400, 400, 400, 400, 400), cores = 8))
        // All alike.
        assertEquals(4, AppVariant.fastCores(listOf(512, 512, 512, 512), cores = 4))
        // Capacities not readable: half of the cores, at least one.
        assertEquals(4, AppVariant.fastCores(emptyList(), cores = 8))
        assertEquals(1, AppVariant.fastCores(emptyList(), cores = 1))
    }

    @Test
    fun theUnitTestsRunTheFullEdition() {
        assertFalse(AppVariant.isLite)
        assertEquals("Dina", AppVariant.label)
        assertEquals("Dina Lite", AppVariant.label(lite = true))
        assertEquals(Dina45Brain.Spec, BrainRegistry.default)
    }

    @Test
    fun eachEditionHasItsOwnBrainModel() {
        assertEquals(Dina45Brain.Spec, BrainRegistry.forEdition(lite = false))
        assertEquals(Dina45Brain.LiteSpec, BrainRegistry.forEdition(lite = true))
        val full = Dina45Brain.Spec.model
        val lite = Dina45Brain.LiteSpec.model
        assertNotEquals(full.assetPath, lite.assetPath)
        assertEquals("Dina 4.5 1.2B", Dina45Brain.Spec.displayName)
        assertEquals("Dina 4.5 350M", Dina45Brain.LiteSpec.displayName)
        assertEquals("Q8_0", lite.quantization)
        // The installer finds the file inside the bundle by this name: it is the asset path without "models/".
        assertEquals(lite.assetPath, "models/" + lite.fileName)
        assertEquals(full.assetPath, "models/" + full.fileName)
        assertTrue(lite.bytes < full.bytes)
        // Names follow the model, as AGENTS.md asks.
        assertTrue(lite.fileName.contains("dina-4.5-350m"))
    }

    @Test
    fun prepareModelsStagesTheFilesTheSpecsExpect() {
        val script = listOf("../../scripts/models/prepare-models.ps1", "scripts/models/prepare-models.ps1").map(::File).firstOrNull { it.isFile }?.readText()
            ?: return // not run from the module folder
        listOf(Dina45Brain.Spec, Dina45Brain.LiteSpec).forEach { spec ->
            assertTrue("${spec.model.assetPath} is not staged by prepare-models.ps1", script.contains(spec.model.assetPath))
        }
    }
}
