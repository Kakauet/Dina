package com.kakauet.dina.data

import com.kakauet.dina.data.ScenarioGenerator.Companion.act
import com.kakauet.dina.data.ScenarioGenerator.Companion.periodClock
import com.kakauet.dina.data.ScenarioGenerator.Ctx
import com.kakauet.dina.data.ScenarioGenerator.Template
import com.kakauet.dina.dialog.Action
import com.kakauet.dina.dialog.Day
import com.kakauet.dina.dialog.Fraction
import com.kakauet.dina.tools.Dimension
import com.kakauet.dina.tools.MeasureUnit
import com.kakauet.dina.tools.Units

/**
 * Batch kind `conversiones` (Dina 4.5): unit conversions (`conv`), the time in other places
 * (`time.now(hora, "Londres")`) and, so that the model does not see conversions everywhere,
 * requests with numbers and units that are something else (sums, timers, the shopping list).
 */
object ConversionTemplates {
    const val MIX = "conversiones"

    val PHENOMENA = listOf(
        "conversiones" to 50, "cocina" to 18, "conv pendiente" to 9, "conv seguimiento" to 7, "conv imposible" to 3, "ciudades" to 13, "distractores" to 12,
    )

    private fun t(phenomenon: String, domain: String, name: String, weight: Int, build: Ctx.() -> List<TurnSpec>?) =
        Template(phenomenon, domain, name, weight, MIX, build)

    /** Common pairs and amounts people ask for (from, to, amounts). */
    private val PAIRS: List<Triple<String, String, List<Double>>> = listOf(
        Triple("milla", "km", listOf(1.0, 3.5, 5.0, 10.0, 26.2, 100.0)), Triple("km", "milla", listOf(5.0, 10.0, 21.0, 42.195, 100.0)),
        Triple("pulgada", "cm", listOf(1.0, 5.0, 10.0, 32.0, 55.0, 65.0)), Triple("cm", "pulgada", listOf(10.0, 30.0, 100.0, 15.0)),
        Triple("pie", "m", listOf(1.0, 6.0, 10.0, 100.0, 30000.0)), Triple("m", "pie", listOf(1.0, 1.8, 10.0, 100.0)), Triple("yarda", "m", listOf(1.0, 10.0, 100.0)),
        Triple("m", "yarda", listOf(100.0, 400.0)), Triple("km", "m", listOf(1.5, 3.2, 0.8)), Triple("m", "cm", listOf(1.75, 2.5)), Triple("mm", "cm", listOf(25.0, 180.0)),
        Triple("pie", "cm", listOf(5.0, 6.0)), Triple("libra", "kg", listOf(1.0, 2.5, 5.0, 10.0, 150.0)), Triple("kg", "libra", listOf(1.0, 5.0, 70.0, 82.0)),
        Triple("onza", "g", listOf(1.0, 4.0, 8.0, 16.0)), Triple("g", "onza", listOf(100.0, 250.0, 500.0)), Triple("kg", "g", listOf(0.25, 1.5, 2.75)), Triple("t", "kg", listOf(1.0, 2.5)),
        Triple("mg", "g", listOf(500.0, 1500.0)), Triple("libra", "onza", listOf(1.0, 2.0)), Triple("g", "kg", listOf(750.0, 1500.0)),
        Triple("galón", "l", listOf(1.0, 5.0, 10.0)), Triple("l", "galón", listOf(4.0, 20.0, 50.0)), Triple("pinta", "ml", listOf(1.0, 2.0)), Triple("ml", "pinta", listOf(500.0)),
        Triple("l", "ml", listOf(0.33, 1.5, 2.0)), Triple("cl", "ml", listOf(33.0, 75.0)), Triple("m3", "l", listOf(1.0, 2.5)), Triple("dl", "ml", listOf(2.0, 5.0)), Triple("ml", "l", listOf(250.0, 1500.0)),
        Triple("celsius", "fahrenheit", listOf(180.0, 200.0, 220.0, 37.0, 37.5, -10.0, 0.0, 25.0, 100.0, -18.0)),
        Triple("fahrenheit", "celsius", listOf(350.0, 400.0, 32.0, 98.6, 70.0, 90.0, 0.0, 212.0, -4.0)), Triple("kelvin", "celsius", listOf(0.0, 300.0)), Triple("celsius", "kelvin", listOf(0.0, 25.0, 100.0)),
        Triple("km/h", "mph", listOf(50.0, 100.0, 120.0, 130.0)), Triple("mph", "km/h", listOf(30.0, 55.0, 65.0, 70.0)), Triple("nudo", "km/h", listOf(10.0, 30.0)),
        Triple("m/s", "km/h", listOf(1.0, 10.0, 20.0)), Triple("km/h", "m/s", listOf(36.0, 90.0)),
        Triple("m2", "pie2", listOf(50.0, 80.0, 100.0)), Triple("pie2", "m2", listOf(500.0, 1000.0)), Triple("hectárea", "m2", listOf(1.0, 2.5)), Triple("acre", "hectárea", listOf(1.0, 5.0, 10.0)),
        Triple("km2", "hectárea", listOf(1.0, 3.0)), Triple("hectárea", "acre", listOf(1.0, 10.0)),
        Triple("h", "min", listOf(1.5, 2.5, 3.5, 0.75)), Triple("min", "h", listOf(90.0, 150.0, 45.0)), Triple("día", "h", listOf(1.0, 3.0, 7.0)), Triple("semana", "día", listOf(2.0, 3.0, 6.0)),
        Triple("año", "día", listOf(1.0, 2.0)), Triple("min", "s", listOf(2.0, 5.0, 15.0)), Triple("h", "s", listOf(1.0, 24.0)), Triple("día", "s", listOf(1.0)), Triple("año", "h", listOf(1.0)),
        Triple("s", "min", listOf(90.0, 300.0, 1000.0)), Triple("semana", "h", listOf(1.0, 2.0)), Triple("año", "semana", listOf(1.0)),
    )

    /** A situation for some amounts (never for others: a wrong one confuses the writers). */
    private fun context(from: String, to: String, value: Double): String? = when {
        from == "celsius" && value >= 150 -> "es la temperatura del horno"
        from == "fahrenheit" && value >= 300 -> "es una receta americana"
        from == "celsius" && value in 36.0..41.0 -> "tienes fiebre"
        from == "fahrenheit" && value in 60.0..110.0 -> "es el tiempo que hace en Estados Unidos"
        from == "km" && to == "milla" && value in 20.0..43.0 -> "es una carrera"
        from == "pie" && to == "m" && value in 5.0..7.0 -> "es una estatura"
        from == "kg" && to == "libra" && value >= 50 -> "es tu peso"
        from == "mph" -> "es la velocidad de un coche"
        from == "nudo" -> "es la velocidad de un barco"
        from == "pulgada" && value >= 30 -> "es una tele"
        from == "m2" -> "es un piso"
        else -> null
    }

    /** Ingredients of [Units.INGREDIENTS] as people say them. */
    private val INGREDIENTS = listOf(
        "harina", "azúcar", "azúcar glas", "azúcar moreno", "arroz", "mantequilla", "aceite", "leche", "agua", "miel", "sal", "cacao", "avena",
        "maicena", "pan rallado", "queso rallado", "almendra molida", "nata", "yogur", "chocolate",
    )

    /** What the ficha calls an amount of a unit: "3,5 millas", "media taza", "una taza y media". */
    private fun amountText(value: Double, unit: MeasureUnit): String {
        if (unit.kitchen) {
            KITCHEN_WORDS[value]?.let { return it.replace("#", unit.one).replace("%", unit.many) }
        }
        val number = Words.number(value).replace('.', ',')
        return when {
            unit.dimension == Dimension.TEMPERATURE && value < 0 -> "${number.removePrefix("-")} ${unit.many} bajo cero"
            value == 1.0 -> (if (unit.feminine) "una " else "un ") + unit.one
            else -> "$number ${unit.many}"
        }
    }

    private val KITCHEN_WORDS = mapOf(0.25 to "un cuarto de #", 1 / 3.0 to "un tercio de #", 0.5 to "media #", 2 / 3.0 to "dos tercios de #", 0.75 to "tres cuartos de #",
        1.5 to "una # y media", 1.25 to "una # y cuarto", 2.0 to "dos %", 3.0 to "tres %", 1.0 to "una #", 2.5 to "dos % y media")

    /** `conv` as the model writes it: the amount (a fraction for thirds), the units, the target left out when unsaid and obvious. */
    private fun conv(value: Double, from: String, to: String?, what: String? = null): Action = act(
        "conv", null,
        "n" to (if (kotlin.math.abs(value * 3 - Math.round(value * 3)) < 1e-9 && Math.round(value * 3) % 3 != 0L) Fraction(Math.round(value * 3).toInt(), 3) else value),
        "from" to from, "to" to to, "what" to what,
    )

    private fun Ctx.question(amount: String, to: MeasureUnit, said: Boolean): String {
        val how = if (to.feminine) "cuántas" else "cuántos"
        return if (!said) pick(listOf(
            "Quieres saber cuánto son $amount (no dices en qué unidad lo quieres: se entiende la de siempre, ${to.many})",
            "Quieres saber a cuánto equivalen $amount, sin decir la unidad de destino (es ${to.many})",
        )) else pick(listOf(
            "Quieres saber $how ${to.many} son $amount", "Quieres pasar $amount a ${to.many}", "Quieres que Dina convierta $amount a ${to.many}",
            "Quieres saber a $how ${to.many} equivalen $amount", "Quieres saber cuánto es $amount en ${to.many}",
        ))
    }

    val TEMPLATES: List<Template> = listOf(
        // ---------------------------------------------------------------- conversiones
        t("conversiones", "conv", "pair", 8) {
            val (from, to, values) = pick(PAIRS.filter { !Units.of(it.first)!!.kitchen && !Units.of(it.second)!!.kitchen })
            val a = Units.of(from)!!
            val b = Units.of(to)!!
            val value = pick(values)
            val said = Units.defaultTarget(from) != to || chance(0.6)
            listOf(TurnSpec(question(amountText(value, a), b, said) + (context(from, to, value)?.let { " ($it)" } ?: "") + ".", listOf(conv(value, from, to.takeIf { said })), ANSWERED))
        },
        t("conversiones", "conv", "one-unit", 2) {
            // One big unit in small ones ("¿cuántos centímetros tiene una pulgada?"), never the other way round.
            val (from, to, _) = pick(PAIRS.filter { Units.of(it.first)!!.dimension != Dimension.TEMPERATURE && Units.of(it.first)!!.factor > Units.of(it.second)!!.factor })
            val a = Units.of(from)!!
            val b = Units.of(to)!!
            listOf(TurnSpec("Quieres saber ${if (b.feminine) "cuántas" else "cuántos"} ${b.many} ${pick(listOf("tiene", "son", "hay en"))} ${amountText(1.0, a)}.", listOf(conv(1.0, from, to)), ANSWERED))
        },
        // ---------------------------------------------------------------- cocina
        t("cocina", "conv", "cups-spoons", 3) {
            val (from, to, values) = pick(PAIRS.filter { Units.of(it.first)!!.kitchen || Units.of(it.second)!!.kitchen } + listOf(
                Triple("taza", "ml", listOf(1.0, 0.5, 0.75, 1.5, 2.0, 0.25, 1 / 3.0)), Triple("ml", "taza", listOf(120.0, 180.0, 250.0, 360.0, 500.0)),
                Triple("cucharada", "ml", listOf(1.0, 2.0, 3.0)), Triple("ml", "cucharada", listOf(30.0, 45.0, 60.0)), Triple("cucharadita", "ml", listOf(1.0, 2.0)),
                Triple("taza", "cucharada", listOf(1.0, 0.5)), Triple("cucharada", "cucharadita", listOf(1.0, 2.0)),
            ))
            val value = pick(values)
            listOf(TurnSpec(question(amountText(value, Units.of(from)!!), Units.of(to)!!, true) + " (estás cocinando).", listOf(conv(value, from, to)), ANSWERED))
        },
        t("cocina", "conv", "ingredient", 4) {
            val what = pick(INGREDIENTS)
            val (from, to, values) = pick(listOf(
                Triple("taza", "g", listOf(1.0, 2.0, 0.5, 0.75, 1 / 3.0, 1.5)), Triple("g", "taza", listOf(100.0, 200.0, 250.0, 500.0)),
                Triple("cucharada", "g", listOf(1.0, 2.0, 3.0)), Triple("cucharadita", "g", listOf(1.0, 2.0)), Triple("ml", "g", listOf(200.0, 500.0)), Triple("g", "ml", listOf(100.0, 250.0)),
            ))
            val value = pick(values)
            val amount = amountText(value, Units.of(from)!!) + " de $what"
            listOf(TurnSpec("Una receta pide $amount y quieres saber cuánto es en ${Units.of(to)!!.many}.", listOf(conv(value, from, to, what)), ANSWERED))
        },
        t("cocina", "conv", "unknown-ingredient", 1) {
            val what = pick(listOf("quinoa", "lentejas", "pipas", "coco rallado", "garbanzos", "sémola", "pasas"))
            val value = pick(listOf(1.0, 2.0, 0.5))
            listOf(TurnSpec("Quieres saber cuántos gramos son ${amountText(value, Units.of("taza")!!)} de $what.", listOf(conv(value, "taza", "g", what)), PROBLEM))
        },
        // ---------------------------------------------------------------- conv pendiente
        t("conv pendiente", "conv", "amount", 3) {
            val (from, to, values) = pick(PAIRS.filter { p -> Units.of(p.first)!!.dimension != Dimension.TEMPERATURE || p.third.all { it >= 0 } })
            val a = Units.of(from)!!
            val b = Units.of(to)!!
            val value = pick(values)
            listOf(
                TurnSpec("Quieres pasar ${a.many} a ${b.many}, pero no dices cuántos.", listOf(act("conv", null, "from" to from, "to" to to)), brief("falta valor")),
                TurnSpec("Dina te pregunta cuántos. Contestas solo la cantidad: ${Words.number(value).replace('.', ',')}.", listOf(conv(value, from, null).with("from", null)), ANSWERED),
            )
        },
        t("conv pendiente", "conv", "ingredient", 3) {
            val what = pick(INGREDIENTS)
            val value = pick(listOf(1.0, 2.0, 0.5, 3.0))
            val (from, to) = pick(listOf("taza" to "g", "cucharada" to "g", "g" to "taza"))
            listOf(
                TurnSpec("Quieres saber cuánto son ${amountText(value, Units.of(from)!!)} en ${Units.of(to)!!.many}, pero no dices de qué ingrediente.", listOf(conv(value, from, to)), brief("falta ingrediente")),
                TurnSpec("Dina te pregunta de qué. Contestas: de $what.", listOf(act("conv", null, "what" to what)), ANSWERED),
            )
        },
        t("conv pendiente", "conv", "target", 3) {
            val (from, values) = pick(listOf("km" to listOf(5.0, 15.0, 42.0), "kg" to listOf(3.0, 60.0, 90.0), "l" to listOf(2.0, 10.0), "m" to listOf(3.0, 50.0), "g" to listOf(200.0, 500.0), "cm" to listOf(20.0, 180.0), "km/h" to listOf(80.0, 120.0)))
            val a = Units.of(from)!!
            val to = pick(PAIRS.filter { it.first == from }).second
            val value = pick(values)
            listOf(
                TurnSpec("Tienes ${amountText(value, a)} y quieres pasarlos a otra unidad, pero no dices a cuál.", listOf(conv(value, from, null)), brief("falta unidad")),
                TurnSpec("Dina te pregunta a qué unidad. Contestas: a ${Units.of(to)!!.many}.", listOf(act("conv", null, "from" to to)), ANSWERED),
            )
        },
        // ---------------------------------------------------------------- conv seguimiento
        t("conv seguimiento", "conv", "other-target", 4) {
            val (from, to, values) = pick(PAIRS)
            val others = PAIRS.filter { it.first == from && it.second != to }.map { it.second } +
                Units.ALL.filter { u -> u.dimension == Units.of(from)!!.dimension && u.code != from && u.code != to && !u.kitchen }.map { it.code }.take(2)
            if (others.isEmpty()) return@t null
            val other = pick(others)
            val value = pick(values)
            listOf(
                TurnSpec(question(amountText(value, Units.of(from)!!), Units.of(to)!!, true) + ".", listOf(conv(value, from, to)), ANSWERED),
                TurnSpec("Ahora quieres el mismo valor en ${Units.of(other)!!.many}; pregúntalo corto, sin repetir la cantidad.", listOf(conv(value, from, other)), ANSWERED),
            )
        },
        t("conv seguimiento", "conv", "other-amount", 3) {
            val (from, to, values) = pick(PAIRS.filter { it.third.size > 1 })
            val (first, second) = values.shuffled(rng).take(2)
            listOf(
                TurnSpec(question(amountText(first, Units.of(from)!!), Units.of(to)!!, true) + ".", listOf(conv(first, from, to)), ANSWERED),
                TurnSpec("Ahora quieres lo mismo con ${Words.number(second).replace('.', ',')}; pregúntalo corto, sin repetir las unidades.", listOf(conv(second, from, to)), ANSWERED),
            )
        },
        // ---------------------------------------------------------------- conv imposible
        t("conv imposible", "conv", "mixed", 1) {
            val (from, to) = pick(listOf("km" to "l", "h" to "m", "kg" to "km", "celsius" to "km", "m2" to "l", "min" to "kg", "km/h" to "kg", "l" to "h"))
            val value = 2.0 + rng.nextInt(20)
            listOf(TurnSpec("Por despiste quieres saber cuántos ${Units.of(to)!!.many} son ${amountText(value, Units.of(from)!!)} (no tiene sentido, pero no te das cuenta).", listOf(conv(value, from, to)), PROBLEM))
        },
        // ---------------------------------------------------------------- ciudades
        t("ciudades", "time", "time-there", 6) {
            val city = pick(CITIES)
            listOf(TurnSpec("Quieres saber qué hora es ahora en $city.", listOf(act("time.now", null, "part" to "hora", "place" to city)), ANSWERED))
        },
        t("ciudades", "time", "day-there", 2) {
            val city = pick(CITIES)
            listOf(TurnSpec("Quieres saber qué día es ahora en $city (allí puede ser otro día).", listOf(act("time.now", null, "part" to pick(listOf("fecha", "dia")), "place" to city)), ANSWERED))
        },
        t("ciudades", "time", "and-there", 3) {
            val (a, b) = CITIES.shuffled(rng).take(2)
            listOf(
                TurnSpec("Quieres saber qué hora es en $a.", listOf(act("time.now", null, "part" to "hora", "place" to a)), ANSWERED),
                TurnSpec("Y ahora en $b; pregúntalo corto.", listOf(act("time.now", null, "part" to "hora", "place" to b)), ANSWERED),
            )
        },
        t("ciudades", "time", "unknown-place", 1) {
            val place = pick(listOf("Villarriba del Monte", "Fuentecilla de Abajo", "San Pedro del Arroyo", "Ciudad Esmeralda", "Puerto Brisa"))
            listOf(TurnSpec("Quieres saber qué hora es en $place (un pueblo pequeño o inventado).", listOf(act("time.now", null, "part" to "hora", "place" to place)), PROBLEM))
        },
        t("ciudades", "time", "here", 1) {
            listOf(TurnSpec("Quieres saber qué hora es aquí, donde estás.", listOf(act("time.now", null, "part" to "hora")), ANSWERED))
        },
        // ---------------------------------------------------------------- distractores
        t("distractores", "time", "sum", 3) {
            val a = 2 + rng.nextInt(60)
            val b = 2 + rng.nextInt(30)
            val (expr, text) = pick(listOf("$a*$b" to "$a por $b", "${a * b}/$b" to "${a * b} entre $b", "$a+$b" to "$a más $b", "$a*$b/100".let { it to "el $a por ciento de $b" }))
            listOf(TurnSpec("Quieres saber cuánto es $text.", listOf(act("calc", null, "expr" to expr)), ANSWERED))
        },
        t("distractores", "timer", "timer", 3) {
            val dur = Words.duration(rng)
            val label = if (chance(0.5)) Words.timerLabel(rng) else null
            listOf(TurnSpec("Quieres un temporizador de ${Words.durationSaid(rng, dur)}" + (label?.let { " para ${it.second}" } ?: "") + ".", listOf(act("timer.add", null, "dur" to dur, "label" to label?.first))))
        },
        t("distractores", "list", "amounts", 3) {
            val p = Words.product(rng, draft.shopping.map { it.name }, quantity = true)
            listOf(TurnSpec("Quieres apuntar ${p.said} en la lista de la compra.", listOf(act("list.add", null, "name" to p.name, "n" to p.n, "unit" to p.unit))))
        },
        t("distractores", "time", "until", 2) {
            val (name, text) = pick(listOf("navidad" to "Navidad", "nochevieja" to "Nochevieja", "reyes" to "Reyes", "año nuevo" to "Año Nuevo", "san juan" to "San Juan"))
            listOf(TurnSpec("Quieres saber cuántos días faltan para $text.", listOf(act("time.until", null, "day" to Day.Named(name))), ANSWERED))
        },
        t("distractores", "alarm", "alarm", 1) {
            val (clock, said) = Words.spoken(rng, Words.alarmTime(rng))
            listOf(TurnSpec("Quieres una alarma a $said.", listOf(act("alarm.add", null, "time" to clock))))
        },
    )

    /** Places of [com.kakauet.dina.tools.Places], as the ficha names them. */
    private val CITIES = listOf(
        "Londres", "París", "Roma", "Berlín", "Lisboa", "Nueva York", "Los Ángeles", "Chicago", "Miami", "Ciudad de México", "Bogotá", "Lima", "Buenos Aires",
        "Santiago de Chile", "Caracas", "Montevideo", "La Habana", "Quito", "Tokio", "Pekín", "Seúl", "Sídney", "Dubái", "Moscú", "Estambul", "El Cairo",
        "Canarias", "Toronto", "São Paulo", "Bangkok", "Nueva Delhi", "Hong Kong", "Atenas", "Ámsterdam", "Marruecos", "Panamá", "Costa Rica", "Guatemala",
        "Hawái", "Singapur", "Japón", "Argentina", "México", "Colombia", "Chile", "Perú",
    )
}
