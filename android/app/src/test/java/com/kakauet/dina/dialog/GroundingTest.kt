package com.kakauet.dina.dialog

import com.kakauet.dina.brain.dina45.Dina45Codec
import org.junit.Assert.assertEquals
import org.junit.Test

class GroundingTest {
    private fun ground(output: String, vararg heard: String) =
        Dina45Codec.encode(Grounding.products(Dina45Codec.decode(output).actions, heard.toList()))

    @Test
    fun anInventedProductBecomesAQuestion() {
        assertEquals("list.add()", ground("list.add(\"chiste\")", "Apunta una cosa en la lista."))
        assertEquals("list.add()", ground("list.add(\"algo\")", "Anotá algo en la lista."))
        assertEquals("list.add()", ground("list.add(\"pan\")", "anota algo en la lista pana"))
        assertEquals("list.add()", ground("list.add(\"frutas\")\nlist.add(\"leche\")", "añade a la lista lo que te diga"))
        assertEquals("list.add(n=2, unit=kg)", ground("list.add(\"manzanas\", n=2, unit=kg)", "apúntame dos kilos de lo que te diga"))
    }

    @Test
    fun whatWasSaidStaysEvenWithSpeechErrors() {
        assertEquals("list.add(\"leche\")\nlist.add(\"pan\")", ground("list.add(\"leche\")\nlist.add(\"pan\")", "Apunta leche y pan."))
        assertEquals("list.add(\"galletas\")", ground("list.add(\"galletas\")", "agregá galletitas"))
        assertEquals("list.add(\"lechuga\")", ground("list.add(\"lechuga\")", "apunta le chuga"))
        assertEquals("list.add(\"tomate frito\")", ground("list.add(\"tomate frito\")", "y tomates"))
        assertEquals("list.add(\"huevos\")", ground("list.add(\"huevos\")", "huevos", "Apúntame una cosa en la compra."))
        // Only the invented one goes when another product was said.
        assertEquals("list.add(\"leche\")", ground("list.add(\"leche\")\nlist.add(\"chiste\")", "apunta leche"))
        // Other actions are untouched.
        assertEquals("timer.add(5m, \"pasta\")", ground("timer.add(5m, \"pasta\")", "pon cinco minutos"))
    }
}
