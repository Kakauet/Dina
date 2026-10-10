package com.kakauet.dina.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class UnitsTest {
    private val t = TestTools()

    private fun result(value: Double, from: String, to: String, ingredient: String? = null) =
        (t.ok(Convert(value, from, to, ingredient)) as ToolData.Conversion).result

    @Test
    fun convertsWithinEveryDimension() {
        assertEquals(5.6327, result(3.5, "milla", "km"), 1e-4)
        assertEquals(2.54, result(1.0, "pulgada", "cm"), 1e-9)
        assertEquals(1.82880, result(6.0, "pie", "m"), 1e-9)
        assertEquals(2.26796, result(5.0, "libra", "kg"), 1e-5)
        assertEquals(16.0, result(1.0, "taza", "cucharada"), 1e-9)
        assertEquals(3.0, result(1.0, "cucharada", "cucharadita"), 1e-9)
        assertEquals(3.78541, result(1.0, "galón", "l"), 1e-5)
        assertEquals(100.0, result(27.7777778, "m/s", "km/h"), 1e-5)
        assertEquals(96.5606, result(60.0, "mph", "km/h"), 1e-4)
        assertEquals(2.47105, result(1.0, "hectárea", "acre"), 1e-5)
        assertEquals(86_400.0, result(1.0, "día", "s"), 1e-9)
        assertEquals(1.5, result(90.0, "min", "h"), 1e-9)
        assertEquals(365.0, result(1.0, "año", "día"), 1e-9)
    }

    @Test
    fun temperaturesAreAffineAndStopAtAbsoluteZero() {
        assertEquals(356.0, result(180.0, "celsius", "fahrenheit"), 1e-9)
        assertEquals(37.7778, result(100.0, "fahrenheit", "celsius"), 1e-4)
        assertEquals(14.0, result(-10.0, "celsius", "fahrenheit"), 1e-9)
        assertEquals(0.0, result(273.15, "kelvin", "celsius"), 1e-9)
        assertEquals(ToolFailure.INVALID_ARGUMENTS, t.failure(Convert(-300.0, "celsius", "kelvin")))
    }

    @Test
    fun massAndVolumeNeedAKnownIngredient() {
        assertEquals(250.0, result(2.0, "taza", "g", "harina"), 1e-6)
        assertEquals(2.0, result(250.0, "g", "taza", "harina de trigo"), 1e-6)
        assertEquals(200.0, result(1.0, "taza", "g", "azúcar"), 1e-6)
        assertEquals(120.0, result(1.0, "taza", "g", "azúcar glas"), 1e-6)
        assertEquals("leche", Units.ingredient("leches"))
        assertNull(Units.ingredient("quinoa"))
        val unknown = t.engine.execute(Convert(1.0, "taza", "g", "quinoa")) as ToolOutcome.Failure
        assertEquals("ingredient", unknown.details["reason"])
        assertEquals(240.0, unknown.details["ml"])
        val mixed = t.engine.execute(Convert(2.0, "kg", "km")) as ToolOutcome.Failure
        assertEquals("dimension", mixed.details["reason"])
        assertTrue(Units.needsIngredient("taza", "g"))
        assertFalse(Units.needsIngredient("taza", "ml"))
    }

    @Test
    fun resultsAreRoundedAsTheyAreSaid() {
        fun round(value: Double, code: String) = Units.round(value, Units.of(code)!!)
        assertEquals(1609.0, round(1609.344, "m"), 0.0)
        assertEquals(30.5, round(30.48, "cm"), 0.0)
        assertEquals(5.63, round(5.6327, "km"), 0.0)
        assertEquals(0.4, round(0.404686, "hectárea"), 0.0)
        assertEquals(0.00062, round(0.000621371, "milla"), 0.0)
        assertEquals(37.8, round(37.7778, "celsius"), 0.0)
        assertEquals(4.4, round(4.4444, "celsius"), 0.0)
        assertTrue(Units.approximate(5.6327, 5.63))
        assertFalse(Units.approximate(356.0, 356.0))
        assertNull(Units.defaultTarget("km"))
        assertEquals("km", Units.defaultTarget("milla"))
        assertEquals("fahrenheit", Units.defaultTarget("celsius"))
    }

    @Test
    fun placesGiveTheirTime() {
        val london = t.ok(DateTimeCommand.Now(DatePart.TIME, "londres")) as ToolData.DateTimeValue
        assertEquals("09:00:00", london.value) // 10:00 in Madrid, 5 October 2026
        assertEquals("Europe/London", london.timezone)
        assertEquals("17:00:00", (t.ok(DateTimeCommand.Now(DatePart.TIME, "Tokio")) as ToolData.DateTimeValue).value)
        assertEquals("04:00:00", (t.ok(DateTimeCommand.Now(DatePart.TIME, "la Habana")) as ToolData.DateTimeValue).value)
        assertEquals("Nueva York", Places.find("nueva york")?.name)
        assertEquals("El Cairo", Places.find("Cairo")?.name)
        assertEquals(ToolFailure.NOT_FOUND, t.failure(DateTimeCommand.Now(DatePart.TIME, "Villarriba")))
        val winter = TestTools(LocalDateTime.of(2026, 12, 1, 10, 0))
        assertEquals("04:00:00", (winter.ok(DateTimeCommand.Now(DatePart.TIME, "Nueva York")) as ToolData.DateTimeValue).value)
    }
}
