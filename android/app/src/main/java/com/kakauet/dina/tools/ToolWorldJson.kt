package com.kakauet.dina.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence format of [ToolWorld]. It is byte-compatible with the state written by
 * app 1.4 (`dina_tools/state`), so updating the app keeps the user's alarms and lists.
 */
object ToolWorldJson {
    fun encode(world: ToolWorld): String = JSONObject()
        .put("timers", JSONArray(world.timers.map { timer ->
            JSONObject().put("id", timer.id).put("label", timer.label ?: JSONObject.NULL)
                .put("deadlineMs", timer.deadlineMs).put("remainingMs", timer.remainingMs)
                .put("durationMs", timer.durationMs).put("status", timer.status.code)
        }))
        .put("alarms", JSONArray(world.alarms.map { alarm ->
            JSONObject().put("id", alarm.id).put("label", alarm.label ?: JSONObject.NULL)
                .put("nextMs", alarm.nextMs).put("repeat", alarm.repeat?.let(::encodeRepeat) ?: JSONObject.NULL)
                .put("status", alarm.status.code)
        }))
        .put("stopwatches", JSONArray(world.stopwatches.map { stopwatch ->
            JSONObject().put("id", stopwatch.id).put("label", stopwatch.label ?: JSONObject.NULL)
                .put("elapsedMs", stopwatch.elapsedMs).put("startedMs", stopwatch.startedMs)
                .put("status", stopwatch.status.code)
        }))
        .put("shopping", JSONArray(world.shopping.map { item ->
            // JSON has no NaN or infinity: one such quantity would make every later save fail.
            JSONObject().put("id", item.id).put("name", item.name)
                .put("quantity", item.quantity?.takeIf { it.isFinite() }?.let(::number) ?: JSONObject.NULL)
                .put("unit", item.unit ?: JSONObject.NULL).put("completed", item.completed)
        }))
        .put("counters", JSONObject(world.counters))
        .put("muted", world.muted)
        .put("restorePercent", world.restorePercent)
        .toString()

    /** Returns null for missing or unreadable state; individual malformed items are skipped. */
    fun decode(raw: String?): ToolWorld? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return withSafeCounters(decodeRoot(root))
    }

    /** Id prefix of each counter in [ToolWorld.counters]. */
    private val idPrefixes = mapOf("timer" to "t", "alarm" to "a", "stopwatch" to "s", "shopping" to "i")

    /** Counters behind the ids already in use (lost or edited state) would hand out an existing id again. */
    private fun withSafeCounters(world: ToolWorld): ToolWorld {
        val ids = mapOf(
            "timer" to world.timers.map { it.id }, "alarm" to world.alarms.map { it.id },
            "stopwatch" to world.stopwatches.map { it.id }, "shopping" to world.shopping.map { it.id },
        )
        val counters = world.counters.toMutableMap()
        ids.forEach { (domain, used) ->
            val prefix = idPrefixes.getValue(domain)
            val highest = used.mapNotNull { id -> id.removePrefix(prefix).takeIf { id.startsWith(prefix) }?.toIntOrNull() }.maxOrNull() ?: return@forEach
            if ((counters[domain] ?: 0) < highest) counters[domain] = highest
        }
        return if (counters == world.counters) world else world.copy(counters = counters)
    }

    private fun decodeRoot(root: JSONObject): ToolWorld {
        return ToolWorld(
            timers = root.objects("timers").mapNotNull { item ->
                runCatching {
                    Timer(
                        id = item.getString("id"), label = item.nullableString("label"),
                        deadlineMs = item.optLong("deadlineMs"), remainingMs = item.optLong("remainingMs"),
                        durationMs = item.optLong("durationMs", item.optLong("remainingMs")),
                        status = TimerStatus.of(item.optString("status")),
                    )
                }.getOrNull()
            },
            alarms = root.objects("alarms").mapNotNull { item ->
                runCatching {
                    Alarm(
                        id = item.getString("id"), label = item.nullableString("label"), nextMs = item.getLong("nextMs"),
                        repeat = item.optJSONObject("repeat")?.let(::decodeRepeat), status = AlarmStatus.of(item.optString("status")),
                    )
                }.getOrNull()
            },
            stopwatches = root.objects("stopwatches").mapNotNull { item ->
                runCatching {
                    Stopwatch(
                        id = item.getString("id"), label = item.nullableString("label"), elapsedMs = item.optLong("elapsedMs"),
                        startedMs = item.optLong("startedMs"), status = StopwatchStatus.of(item.optString("status")),
                    )
                }.getOrNull()
            },
            shopping = root.objects("shopping").mapNotNull { item ->
                runCatching {
                    ShoppingItem(
                        id = item.getString("id"), name = item.getString("name"), quantity = quantity(item.opt("quantity")),
                        unit = item.nullableString("unit"), completed = item.optBoolean("completed", false),
                    )
                }.getOrNull()
            },
            counters = root.optJSONObject("counters")?.let { counters -> counters.keys().asSequence().associateWith { counters.optInt(it) } } ?: emptyMap(),
            muted = root.optBoolean("muted", false),
            restorePercent = root.optInt("restorePercent", ToolWorld.DEFAULT_RESTORE_PERCENT),
        )
    }

    fun encodeRepeat(repeat: Repeat): JSONObject = when (repeat) {
        Repeat.Daily -> JSONObject().put("type", "daily")
        Repeat.Weekdays -> JSONObject().put("type", "weekdays")
        Repeat.Weekends -> JSONObject().put("type", "weekends")
        is Repeat.Weekly -> JSONObject().put("type", "weekly").put("days", JSONArray(repeat.days.map(DayCodes::code)))
    }

    /** Unknown repeat types, and weekly ones without a known day (the engine cannot schedule them), decode to null. */
    fun decodeRepeat(value: JSONObject): Repeat? = when (value.optString("type")) {
        "daily" -> Repeat.Daily
        "weekdays" -> Repeat.Weekdays
        "weekends" -> Repeat.Weekends
        "weekly" -> {
            val days = value.optJSONArray("days")
            (0 until (days?.length() ?: 0)).mapNotNull { DayCodes.day(days!!.optString(it)) }.distinct()
                .takeIf { it.isNotEmpty() }?.let(Repeat::Weekly)
        }
        else -> null
    }

    /** Integral quantities are written as integers, as the 1.4 runtime did. */
    fun number(value: Double): Number = if (value % 1.0 == 0.0 && value in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) value.toLong() else value

    /** A finite number or null: "NaN", "Infinity" or "1e999" typed in the list editor are not quantities. */
    fun quantity(value: Any?): Double? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toDouble()
        is String -> value.replace(',', '.').trim().toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
