package com.kakauet.dina.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer

/**
 * The port of Supertonic's text front end against the reference package (`supertonic` 1.3.1,
 * `UnicodeProcessor` with lang="es"); expected values were printed by the Python reference.
 */
class SupertonicTextTest {
    private fun nfkd(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD)

    @Test
    fun preprocessingMatchesTheReference() {
        assertEquals("<es>Hola.</es>", SupertonicText.preprocess("Hola."))
        assertEquals(nfkd("<es>Listo: temporizador de 5 minutos «pasta» - ¿algo más?</es>"), SupertonicText.preprocess("Listo: temporizador de 5 minutos «pasta» — ¿algo más?"))
        assertEquals(nfkd("<es>Son las 9:05 de la mañana.</es>"), SupertonicText.preprocess("Son las 9:05 de la mañana"))
        assertEquals(nfkd("<es>Añadido: 6 botellas de agua, pan y café...</es>"), SupertonicText.preprocess("Añadido: 6 botellas de agua, pan y café…"))
        assertEquals("<es>Uno, dos? \"tres\" a b.</es>", SupertonicText.preprocess("  Uno ,  dos ? \"\"tres\"\" a/b"))
        assertEquals("<es>Hola.</es>", SupertonicText.preprocess("Hola 😀"))
    }

    @Test
    fun unsupportedCharactersAreDroppedNotFatal() {
        val indexer = IntArray(128) { -1 }.also { table -> "<es>/.Hola".forEach { table[it.code] = it.code } }
        val ids = SupertonicText.ids("Hola€", indexer)
        assertEquals("<es>Hola.</es>", String(CharArray(ids.size) { ids[it].toInt().toChar() }))
        assertEquals(setOf('€'), SupertonicText.unsupported("Hola€", indexer))
    }

    @Test
    fun idsMatchTheReferenceWithTheRealIndexer() {
        val file = File("../../models/tts/supertonic3/onnx/unicode_indexer.json")
        assumeTrue("Supertonic model not downloaded", file.isFile)
        val text = file.readText()
        val indexer = text.substring(text.indexOf('[') + 1, text.lastIndexOf(']')).split(',').map { it.trim().toInt() }.toIntArray()
        assertArrayEquals(longArrayOf(29, 64, 78, 31, 40, 74, 71, 60, 15, 29, 16, 64, 78, 31), SupertonicText.ids("Hola.", indexer))
        assertArrayEquals(
            longArrayOf(29, 64, 78, 31, 51, 74, 73, 2, 71, 60, 78, 2, 26, 27, 17, 22, 2, 63, 64, 2, 71, 60, 2, 72, 60, 73, 148, 60, 73, 60, 15, 29, 16, 64, 78, 31),
            SupertonicText.ids("Son las 9:05 de la mañana", indexer),
        )
        assertArrayEquals(
            longArrayOf(
                29, 64, 78, 31, 44, 68, 78, 79, 74, 27, 2, 79, 64, 72, 75, 74, 77, 68, 85, 60, 63, 74, 77, 2, 63, 64, 2, 22, 2, 72, 68, 73, 80, 79, 74, 78,
                2, 104, 75, 60, 78, 79, 60, 110, 2, 14, 2, 111, 60, 71, 66, 74, 2, 72, 60, 146, 78, 32, 29, 16, 64, 78, 31,
            ),
            SupertonicText.ids("Listo: temporizador de 5 minutos «pasta» — ¿algo más?", indexer),
        )
        // Everything Dina says after SpanishSpeech is readable.
        val spoken = SpanishSpeech.expand("Listo: alarma mañana a las 7:15 «gimnasio», 2 l de leche, 40 %, ¿algo más? ¡Hecho!")
        assertEquals(emptySet<Char>(), SupertonicText.unsupported(spoken, indexer))
    }

    @Test
    fun tensorShapesFollowTheReference() {
        val config = SupertonicText.Config()
        // One second: 44 100 samples, ceil(44 100 / 3 072) = 15 latent frames of 24 × 6 = 144 channels.
        assertEquals(SupertonicText.Shapes(samples = 44_100, frames = 15, maskedFrames = 15, latentChannels = 144), SupertonicText.shapes(1f, config))
        // "Hola." in the reference: 1.2733096 s at speed 1.05.
        val hola = SupertonicText.shapes(1.2733096f / 1.05f, config)
        assertEquals(18, hola.frames)
        assertEquals(53_479, hola.samples) // float32 like NumPy: 53479.004
        // Exactly 2 frames of audio: no extra frame.
        assertEquals(2, SupertonicText.shapes(6_144f / 44_100f, config).frames)
    }
}
