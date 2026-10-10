package com.kakauet.dina.tools

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.Normalizer
import kotlin.math.abs

/** What a unit measures; a conversion stays within one (mass ↔ volume only through an ingredient). */
enum class Dimension(val word: String) {
    LENGTH("longitud"), MASS("peso"), VOLUME("volumen"), TEMPERATURE("temperatura"), SPEED("velocidad"), AREA("superficie"), TIME("tiempo")
}

/**
 * A unit of the converter: [code] as the contract writes it, how it is said ([one], [many],
 * [feminine]), how the card shows it ([symbol]; null shows the word) and its size in the
 * dimension's base unit (m, g, ml, m/s, m², s). Temperatures convert through Celsius instead.
 */
data class MeasureUnit(
    val code: String,
    val dimension: Dimension,
    val factor: Double,
    val one: String,
    val many: String,
    val symbol: String?,
    val feminine: Boolean = false,
) {
    /** Cups and spoons: amounts are said as fractions ("taza y media"). */
    val kitchen get() = code in KITCHEN

    companion object { private val KITCHEN = setOf("taza", "cucharada", "cucharadita") }
}

/**
 * Unit conversions (docs/contrato.md): lengths, masses, volumes (cups and spoons too),
 * temperatures, speeds, areas and times. A cup is 240 ml, a spoon 15 ml and a teaspoon 5 ml;
 * pints and gallons are US ones. Mass ↔ volume needs an [INGREDIENTS] density.
 */
object Units {
    private fun u(code: String, dimension: Dimension, factor: Double, one: String, many: String, symbol: String?, feminine: Boolean = false) =
        MeasureUnit(code, dimension, factor, one, many, symbol, feminine)

    /** In the contract's order: new units are only ever appended. */
    val ALL: List<MeasureUnit> = listOf(
        u("mm", Dimension.LENGTH, 0.001, "milímetro", "milímetros", "mm"),
        u("cm", Dimension.LENGTH, 0.01, "centímetro", "centímetros", "cm"),
        u("m", Dimension.LENGTH, 1.0, "metro", "metros", "m"),
        u("km", Dimension.LENGTH, 1000.0, "kilómetro", "kilómetros", "km"),
        u("pulgada", Dimension.LENGTH, 0.0254, "pulgada", "pulgadas", "in", feminine = true),
        u("pie", Dimension.LENGTH, 0.3048, "pie", "pies", "ft"),
        u("yarda", Dimension.LENGTH, 0.9144, "yarda", "yardas", "yd", feminine = true),
        u("milla", Dimension.LENGTH, 1609.344, "milla", "millas", "mi", feminine = true),
        u("mg", Dimension.MASS, 0.001, "miligramo", "miligramos", "mg"),
        u("g", Dimension.MASS, 1.0, "gramo", "gramos", "g"),
        u("kg", Dimension.MASS, 1000.0, "kilo", "kilos", "kg"),
        u("t", Dimension.MASS, 1_000_000.0, "tonelada", "toneladas", "t", feminine = true),
        u("onza", Dimension.MASS, 28.349523125, "onza", "onzas", "oz", feminine = true),
        u("libra", Dimension.MASS, 453.59237, "libra", "libras", "lb", feminine = true),
        u("ml", Dimension.VOLUME, 1.0, "mililitro", "mililitros", "ml"),
        u("cl", Dimension.VOLUME, 10.0, "centilitro", "centilitros", "cl"),
        u("dl", Dimension.VOLUME, 100.0, "decilitro", "decilitros", "dl"),
        u("l", Dimension.VOLUME, 1000.0, "litro", "litros", "l"),
        u("m3", Dimension.VOLUME, 1_000_000.0, "metro cúbico", "metros cúbicos", "m³"),
        u("cucharadita", Dimension.VOLUME, 5.0, "cucharadita", "cucharaditas", null, feminine = true),
        u("cucharada", Dimension.VOLUME, 15.0, "cucharada", "cucharadas", null, feminine = true),
        u("taza", Dimension.VOLUME, 240.0, "taza", "tazas", null, feminine = true),
        u("pinta", Dimension.VOLUME, 473.176473, "pinta", "pintas", "pt", feminine = true),
        u("galón", Dimension.VOLUME, 3785.411784, "galón", "galones", "gal"),
        u("celsius", Dimension.TEMPERATURE, 1.0, "grado Celsius", "grados Celsius", "°C"),
        u("fahrenheit", Dimension.TEMPERATURE, 1.0, "grado Fahrenheit", "grados Fahrenheit", "°F"),
        u("kelvin", Dimension.TEMPERATURE, 1.0, "kelvin", "kelvin", "K"),
        u("km/h", Dimension.SPEED, 1 / 3.6, "kilómetro por hora", "kilómetros por hora", "km/h"),
        u("m/s", Dimension.SPEED, 1.0, "metro por segundo", "metros por segundo", "m/s"),
        u("mph", Dimension.SPEED, 0.44704, "milla por hora", "millas por hora", "mph", feminine = true),
        u("nudo", Dimension.SPEED, 1852.0 / 3600.0, "nudo", "nudos", "kn"),
        u("cm2", Dimension.AREA, 0.0001, "centímetro cuadrado", "centímetros cuadrados", "cm²"),
        u("m2", Dimension.AREA, 1.0, "metro cuadrado", "metros cuadrados", "m²"),
        u("km2", Dimension.AREA, 1_000_000.0, "kilómetro cuadrado", "kilómetros cuadrados", "km²"),
        u("hectárea", Dimension.AREA, 10_000.0, "hectárea", "hectáreas", "ha", feminine = true),
        u("acre", Dimension.AREA, 4046.8564224, "acre", "acres", "ac"),
        u("pie2", Dimension.AREA, 0.09290304, "pie cuadrado", "pies cuadrados", "ft²"),
        u("s", Dimension.TIME, 1.0, "segundo", "segundos", "s"),
        u("min", Dimension.TIME, 60.0, "minuto", "minutos", "min"),
        u("h", Dimension.TIME, 3600.0, "hora", "horas", "h", feminine = true),
        u("día", Dimension.TIME, 86_400.0, "día", "días", null),
        u("semana", Dimension.TIME, 604_800.0, "semana", "semanas", null, feminine = true),
        u("año", Dimension.TIME, 31_536_000.0, "año", "años", null),
    )

    val CODES: List<String> = ALL.map { it.code }
    private val byCode = ALL.associateBy { it.code }

    fun of(code: String): MeasureUnit? = byCode[code]

    /** Where a unit goes when the user does not say: the everyday counterpart; null asks (metric to what?). */
    private val DEFAULT_TARGET = mapOf(
        "pulgada" to "cm", "pie" to "m", "yarda" to "m", "milla" to "km",
        "onza" to "g", "libra" to "kg",
        "cucharadita" to "ml", "cucharada" to "ml", "taza" to "ml", "pinta" to "ml", "galón" to "l",
        "fahrenheit" to "celsius", "celsius" to "fahrenheit", "kelvin" to "celsius",
        "mph" to "km/h", "nudo" to "km/h", "m/s" to "km/h",
        "acre" to "hectárea", "pie2" to "m2",
        "s" to "min", "min" to "h", "h" to "min", "día" to "h", "semana" to "día", "año" to "día",
    )

    fun defaultTarget(from: String): String? = DEFAULT_TARGET[from]

    /** Grams per millilitre, from what a 240 ml cup weighs (approximate, as kitchens measure). */
    val INGREDIENTS: Map<String, Double> = linkedMapOf(
        "azucar glas" to 120.0, "azucar moreno" to 220.0, "pan rallado" to 110.0, "queso rallado" to 100.0, "almendra molida" to 96.0,
        "harina" to 125.0, "maicena" to 128.0, "azucar" to 200.0, "arroz" to 190.0, "mantequilla" to 227.0, "aceite" to 218.0,
        "leche" to 245.0, "agua" to 240.0, "nata" to 240.0, "yogur" to 245.0, "miel" to 340.0, "sal" to 288.0, "cacao" to 100.0,
        "avena" to 90.0, "chocolate" to 170.0,
    ).mapValues { it.value / 240.0 }

    /** The known ingredient a phrase names ("harina de trigo" → harina), or null. */
    fun ingredient(text: String?): String? {
        val words = fold(text ?: return null).split(' ').filter { it.isNotEmpty() }
        // Each word as said or without a plural ending: "leches" → leche, "azúcares" → azucar.
        val forms = words.map { w -> setOf(w, w.removeSuffix("s"), w.removeSuffix("es")) }
        fun at(i: Int, name: List<String>) = name.indices.all { k -> i + k < forms.size && name[k] in forms[i + k] }
        val names = INGREDIENTS.keys.map { it to it.split(' ') }
        // Longer names first: "azúcar glas" before "azúcar".
        return names.sortedByDescending { it.second.size }.firstOrNull { (_, name) -> words.indices.any { at(it, name) } }?.first
    }

    /** True when going from one to the other weighs or measures an ingredient. */
    fun needsIngredient(from: String, to: String): Boolean {
        val dims = setOf(of(from)?.dimension, of(to)?.dimension)
        return dims == setOf(Dimension.MASS, Dimension.VOLUME)
    }

    /**
     * [value] [from] in [to]. Fails with `invalid_arguments` and a `reason`: `unit`, `dimension`,
     * `ingredient` (mass ↔ volume without a known one; `ml` says the volume) or `absolute_zero`.
     */
    fun convert(value: Double, from: String, to: String, ingredient: String? = null): ToolData.Conversion {
        val a = of(from) ?: throw invalid("unit", "unit" to from)
        val b = of(to) ?: throw invalid("unit", "unit" to to)
        if (!value.isFinite()) throw invalid("value")
        if (a.dimension == Dimension.TEMPERATURE && b.dimension == Dimension.TEMPERATURE) {
            val celsius = when (a.code) { "fahrenheit" -> (value - 32) * 5 / 9; "kelvin" -> value - 273.15; else -> value }
            if (celsius < -273.15 - 1e-9) throw invalid("absolute_zero")
            val result = when (b.code) { "fahrenheit" -> celsius * 9 / 5 + 32; "kelvin" -> celsius + 273.15; else -> celsius }
            return ToolData.Conversion(value, a.code, b.code, result)
        }
        if (a.dimension == b.dimension) return ToolData.Conversion(value, a.code, b.code, value * a.factor / b.factor)
        if (!needsIngredient(a.code, b.code)) throw invalid("dimension", "from" to a.dimension.word, "to" to b.dimension.word)
        val volume = if (a.dimension == Dimension.VOLUME) a else b
        val name = ingredient(ingredient)
            ?: throw invalid("ingredient", "ingredient" to ingredient, "ml" to (if (a.dimension == Dimension.VOLUME) value * a.factor else null))
        val density = INGREDIENTS.getValue(name)
        val grams = if (a.dimension == Dimension.MASS) value * a.factor else value * a.factor * density
        val result = if (b.dimension == Dimension.MASS) grams / b.factor else grams / density / volume.factor
        return ToolData.Conversion(value, a.code, b.code, result, name)
    }

    /**
     * The result as it is said: whole from 100 up, one decimal from 10 (and temperatures), two
     * below, two significant figures for tiny values.
     */
    fun round(value: Double, unit: MeasureUnit): Double {
        val size = abs(value)
        if (size == 0.0) return 0.0
        val decimal = BigDecimal(value)
        val rounded = when {
            size >= 100 -> decimal.setScale(0, RoundingMode.HALF_UP)
            size >= 10 || unit.dimension == Dimension.TEMPERATURE -> decimal.setScale(1, RoundingMode.HALF_UP)
            size >= 0.1 -> decimal.setScale(2, RoundingMode.HALF_UP)
            else -> decimal.round(MathContext(2, RoundingMode.HALF_UP))
        }
        return rounded.toDouble()
    }

    /** True when [shown] is not [exact] (the answer says "unos"). */
    fun approximate(exact: Double, shown: Double) = abs(exact - shown) > 1e-9 * maxOf(1.0, abs(exact))

    private fun invalid(reason: String, vararg details: Pair<String, Any?>) =
        ToolFailure(ToolFailure.INVALID_ARGUMENTS, mapOf("field" to "conversion", "reason" to reason) + details)

    private fun fold(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
