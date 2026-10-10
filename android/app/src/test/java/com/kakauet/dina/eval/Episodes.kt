package com.kakauet.dina.eval

import com.kakauet.dina.dialog.SpanishText
import com.kakauet.dina.tools.Alarm
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.ShoppingItem
import com.kakauet.dina.tools.Stopwatch
import com.kakauet.dina.tools.StopwatchStatus
import com.kakauet.dina.tools.Timer
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.ToolFailure
import com.kakauet.dina.tools.ToolWorld
import com.kakauet.dina.tools.ToolWorldJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** What Dina should do in a turn, in world terms. */
enum class Kind(val code: String) {
    ACT("act"), ASK("ask"), ANSWER("answer"), REJECT("reject"), CHAT("chat"), FAIL("fail");
    companion object { fun of(code: String) = entries.firstOrNull { it.code == code } ?: throw OracleError("unknown kind $code") }
}

/** A fact the answer must (or must not) state, checked on normalized Spanish. */
sealed interface Fact {
    val code: String
    fun holds(raw: String, normalized: String): Boolean

    data class Time(val time: LocalTime) : Fact {
        override val code get() = "time:$time"
        override fun holds(raw: String, normalized: String) = SpanishText.mentionsTime(normalized, time)
    }
    data class Dur(val seconds: Long) : Fact {
        override val code get() = "dur:${seconds}s"
        override fun holds(raw: String, normalized: String) = SpanishText.mentionsDuration(normalized, seconds)
    }
    data class Num(val value: Double) : Fact {
        override val code get() = "num:${if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()}"
        override fun holds(raw: String, normalized: String) = SpanishText.mentionsNumber(normalized, value)
    }
    data class Day(val name: String) : Fact {
        override val code get() = "day:$name"
        override fun holds(raw: String, normalized: String) = SpanishText.mentionsWeekday(normalized, name)
    }
    data class Date(val date: LocalDate) : Fact {
        override val code get() = "date:$date"
        override fun holds(raw: String, normalized: String) = SpanishText.mentionsDate(normalized, date)
    }
    data class Word(val alternatives: List<String>) : Fact {
        override val code get() = "word:${alternatives.joinToString("|")}"
        override fun holds(raw: String, normalized: String) = alternatives.any { SpanishText.mentionsPhrase(normalized, it) }
    }
    data object Neg : Fact {
        override val code get() = "neg"
        override fun holds(raw: String, normalized: String) = SpanishText.expressesAbsence(raw)
    }
    data object Cannot : Fact {
        override val code get() = "cannot"
        override fun holds(raw: String, normalized: String) = SpanishText.expressesInability(raw)
    }
    data object Failed : Fact {
        override val code get() = "fail"
        override fun holds(raw: String, normalized: String) = FAILURE.containsMatchIn(" ${SpanishText.fold(raw)} ") || SpanishText.expressesInability(raw)
    }
    data object Yes : Fact {
        override val code get() = "yes"
        override fun holds(raw: String, normalized: String) = SpanishText.expressesYes(raw)
    }
    data class AnyOf(val facts: List<Fact>) : Fact {
        override val code get() = facts.joinToString("/") { it.code }
        override fun holds(raw: String, normalized: String) = facts.any { it.holds(raw, normalized) }
    }

    companion object {
        private val FAILURE = Regex("\\b(no (he podido|se ha podido|pude|puedo|ha sido posible|lo he conseguido|se pudo)|ha fallado|fallo|error|problema|algo ha ido mal|no ha funcionado|me falta|no es valid[oa])\\b")

        fun parse(text: String): Fact {
            if ('/' in text) return AnyOf(text.split('/').map(::parse))
            val kind = text.substringBefore(':')
            val value = text.substringAfter(':', "")
            return when (kind) {
                "time" -> Time(LocalTime.parse(if (value.length == 4) "0$value" else value))
                "dur" -> Dur((Dsl.duration(value) ?: value.removeSuffix("s").toLongOrNull()?.times(1000) ?: throw OracleError("bad fact $text")) / 1000)
                "num", "pct", "count" -> Num(value.toDouble())
                "day" -> Day(value)
                "date" -> Date(LocalDate.parse(value))
                "word" -> Word(value.split('|'))
                "neg" -> Neg
                "cannot" -> Cannot
                "fail" -> Failed
                "yes" -> Yes
                else -> throw OracleError("unknown fact $text")
            }
        }
    }
}

sealed interface OracleAction {
    /** Benchmark notation, e.g. `alarm.edit @'gimnasio' at=20:00`. */
    data class Line(val text: String) : OracleAction
    /** A command decoded from another benchmark (RW200 v1 calls). */
    data class Command(val command: ToolCommand) : OracleAction
}

/** What Dina asks for: [slot] (time, day, ampm, duration, target, item, value, what, confirm, expr) and candidates. */
data class AskSpec(val slot: String?, val candidates: List<Fact>)


data class Expect(
    val kind: Kind,
    val actions: List<OracleAction> = emptyList(),
    /** Facts the answer must state; null = derived from the oracle's results. */
    val says: List<Fact>? = null,
    val not: List<Fact> = emptyList(),
    val ask: AskSpec? = null,
    /** Valid only under these undecided policies, e.g. ampm=ask. */
    val policy: Map<String, Set<String>> = emptyMap(),
    /** Turns that follow when this option is the one Dina took. */
    val then: List<Turn>? = null,
    val note: String? = null,
    /** The oracle's actions may fail (RW200 v1 expects some calls to fail without an injection). */
    val tolerateFailures: Boolean = false,
)

data class Turn(
    val user: String,
    val options: List<Expect>,
    val waitMs: Long = 0,
    /** Tool name ("alarm") or call index within the turn ("0") -> failure code returned instead of executing. */
    val inject: Map<String, String> = emptyMap(),
)

data class Episode(
    val id: String,
    val category: String,
    val tags: List<String>,
    val variety: String,
    val clock: LocalDateTime,
    val zone: ZoneId,
    val initial: WorldState,
    val turns: List<Turn>,
    val why: String = "",
    val source: String = "v2",
)

object EpisodeJson {
    val MADRID: ZoneId = ZoneId.of("Europe/Madrid")

    fun loadDir(dir: File): List<Episode> = dir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }
        .flatMap { file -> file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            try { parse(JSONObject(line)) } catch (e: Exception) { throw OracleError("${file.name}: ${e.message} in $line") }
        } }

    fun parse(json: JSONObject): Episode {
        val zone = ZoneId.of(json.optString("tz", MADRID.id))
        val clock = LocalDateTime.parse(json.getString("clock"))
        return Episode(
            id = json.getString("id"),
            category = json.getString("cat"),
            tags = json.optJSONArray("tags").strings(),
            variety = json.optString("var", "es-ES"),
            clock = clock,
            zone = zone,
            initial = StateSpec.build(json.optJSONObject("state"), clock, zone),
            turns = turns(json.getJSONArray("turns")),
            why = json.optString("why"),
        )
    }

    private fun turns(array: JSONArray): List<Turn> = (0 until array.length()).map { turn(array.getJSONObject(it)) }

    private fun turn(json: JSONObject): Turn {
        val options = json.optJSONArray("x")?.let { x -> (0 until x.length()).map { expect(x.getJSONObject(it)) } } ?: listOf(expect(json))
        val inject = json.optJSONObject("inject")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
        return Turn(json.getString("u"), options, json.optString("wait").takeIf { it.isNotEmpty() }?.let { Dsl.duration(it) } ?: 0L, inject)
    }

    private fun expect(json: JSONObject): Expect {
        val actions = json.optJSONArray("do").strings().map(OracleAction::Line)
        val ask = json.optJSONObject("ask")?.let { AskSpec(it.optString("slot").ifEmpty { null }, it.optJSONArray("opts").strings().map(Fact::parse)) }
        val kind = json.optString("kind").ifEmpty { null }?.let(Kind::of) ?: when {
            ask != null -> Kind.ASK
            actions.isNotEmpty() -> Kind.ACT
            else -> throw OracleError("option without kind: $json")
        }
        val policy = json.optJSONObject("pol")?.let { p ->
            p.keys().asSequence().associateWith { key ->
                when (val value = p.get(key)) { is JSONArray -> value.strings().toSet(); else -> setOf(value.toString()) }
            }
        }.orEmpty()
        return Expect(
            kind = kind,
            actions = actions,
            says = json.optJSONArray("says")?.strings()?.map(Fact::parse),
            not = json.optJSONArray("not").strings().map(Fact::parse),
            ask = ask,
            policy = policy,
            then = json.optJSONArray("then")?.let(::turns),
            note = json.optString("note").ifEmpty { null },
        )
    }
}

/**
 * Dina-Real: blind cards (`fichas.jsonl`, whose turns hold placeholders like "F012a") filled with
 * real phrases from `frases.tsv` (frase_id, persona, frase). One episode per card and speaker;
 * cards with a missing phrase for that speaker are skipped.
 */
object DinaReal {
    fun load(dir: File): List<Episode> {
        val phrases = File(dir, "frases.tsv").readLines(Charsets.UTF_8).drop(1).map { it.split('\t') }
            .filter { it.size >= 3 && it[2].isNotBlank() }
            .groupBy({ it[1].ifBlank { "anon" } }, { it[0] to it[2].trim() })
            .mapValues { it.value.toMap() }
        val cards = File(dir, "fichas.jsonl").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        return phrases.flatMap { (persona, byId) ->
            cards.mapNotNull { line ->
                val json = JSONObject(line)
                if (!fill(json.getJSONArray("turns"), byId)) return@mapNotNull null
                json.put("id", "${json.getString("id")}@$persona")
                EpisodeJson.parse(json)
            }
        }
    }

    /** Replaces every placeholder in [turns] (branches included); false if one has no phrase. */
    private fun fill(turns: JSONArray, phrases: Map<String, String>): Boolean {
        for (i in 0 until turns.length()) {
            val turn = turns.getJSONObject(i)
            turn.put("u", phrases[turn.getString("u")] ?: return false)
            val options = turn.optJSONArray("x") ?: continue
            for (k in 0 until options.length()) options.getJSONObject(k).optJSONArray("then")?.let { if (!fill(it, phrases)) return false }
        }
        return true
    }
}

/** RealWorld200 v1 episodes, read as they are (v1 is never modified). */
object Rw200 {
    fun load(dir: File): List<Episode> = dir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }
        .flatMap { file -> file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { convert(JSONObject(it)) } }

    fun convert(json: JSONObject): Episode {
        val start = OffsetDateTime.parse(json.getString("clock_start"))
        val zone = EpisodeJson.MADRID
        val clock = start.atZoneSameInstant(zone).toLocalDateTime()
        val nowMs = start.toInstant().toEpochMilli()
        val decoder = Rw200Calls(clock = { nowMs }, zone = { zone })
        val outcomes = json.optJSONArray("tool_outcomes")
        val turns = json.getJSONArray("turns")
        var callIndex = 0
        val converted = (0 until turns.length()).map { i ->
            val turn = turns.getJSONObject(i)
            val expected = turn.getJSONObject("expected")
            val calls = expected.getJSONArray("canonical_actions")
            val inject = mutableMapOf<String, String>()
            if (outcomes != null) for (k in 0 until outcomes.length()) {
                val outcome = outcomes.getJSONObject(k)
                val local = outcome.getInt("call_index") - callIndex
                if (local in 0 until calls.length()) inject[local.toString()] = outcome.getJSONObject("result").getString("error")
            }
            callIndex += calls.length()
            val actions = (0 until calls.length()).map { k ->
                val call = calls.getJSONObject(k)
                OracleAction.Command(decoder.decode(call.getString("name"), call.getJSONObject("arguments")))
            }
            val decision = expected.getString("decision")
            val results = expected.optJSONArray("tool_results")
            val failed = (0 until (results?.length() ?: 0)).filter { k -> !results!!.getJSONObject(k).optBoolean("ok", false) && k.toString() !in inject }
                .map { k -> results!!.getJSONObject(k).optString("error") }
            val injected = inject.values.firstOrNull() ?: failed.firstOrNull()
            val kind = when {
                decision == "act" && injected == ToolFailure.AMBIGUOUS -> Kind.ASK
                decision == "act" && (injected == ToolFailure.NOT_FOUND || injected == "empty_result") -> Kind.ANSWER
                decision == "act" && injected != null -> Kind.FAIL
                decision == "act" -> Kind.ACT
                decision == "ask" -> Kind.ASK
                decision == "reject" -> Kind.REJECT
                else -> Kind.ANSWER
            }
            val says = when {
                kind == Kind.ANSWER && injected != null -> listOf(Fact.Neg)
                kind == Kind.ACT -> null
                kind == Kind.FAIL -> listOf(Fact.Failed)
                else -> emptyList()
            }
            Turn(
                user = turn.getString("user"),
                options = listOf(Expect(kind, actions, says = says, ask = if (kind == Kind.ASK) AskSpec(null, emptyList()) else null, tolerateFailures = failed.isNotEmpty())),
                inject = inject,
            )
        }
        return Episode(
            id = json.getString("id"),
            category = json.getString("category"),
            tags = json.optJSONArray("phenomena").strings() + json.getString("domain"),
            variety = "es-ES",
            clock = clock,
            zone = zone,
            initial = rw200State(json.getJSONObject("initial_state"), nowMs, zone),
            turns = converted,
            why = json.optString("intent"),
            source = "rw200",
        )
    }

    /** RW200 states use the Python runtime's export format. */
    fun rw200State(state: JSONObject, nowMs: Long, zone: ZoneId): WorldState {
        fun list(key: String) = state.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        fun label(o: JSONObject, key: String = "label") = if (o.isNull(key)) null else o.optString(key).ifEmpty { null }
        val alarms = list("alarms").map { a ->
            Alarm(a.getString("id"), label(a), OffsetDateTime.parse(a.getString("next_at")).toInstant().toEpochMilli(),
                a.opt("repeat")?.let { r -> if (r is JSONObject) ToolWorldJson.decodeRepeat(r) else if (r is String) ToolWorldJson.decodeRepeat(JSONObject().put("type", r)) else null },
                AlarmStatus.of(a.optString("status", "scheduled")))
        }
        val timers = list("timers").map { t ->
            val remaining = t.getLong("remaining_s") * 1000
            val status = TimerStatus.of(t.optString("status", "running"))
            Timer(t.getString("id"), label(t), nowMs + remaining, remaining, t.optLong("total_s", t.getLong("remaining_s")) * 1000, status)
        }
        val stopwatches = list("stopwatches").map { s ->
            val elapsed = s.getLong("elapsed_s") * 1000
            val status = StopwatchStatus.of(s.optString("status", "running"))
            if (status == StopwatchStatus.RUNNING) Stopwatch(s.getString("id"), label(s), 0, nowMs - elapsed, status)
            else Stopwatch(s.getString("id"), label(s), elapsed, nowMs, status)
        }
        val shopping = list("shopping").map { i ->
            ShoppingItem(i.getString("id"), i.getString("name"), ToolWorldJson.quantity(i.opt("quantity")), label(i, "unit"), i.optBoolean("completed", false))
        }
        val volume = state.optJSONObject("volume") ?: JSONObject()
        val percent = volume.optInt("percent", 50)
        val muted = volume.optBoolean("muted", false)
        val counters = buildMap {
            fun max(prefix: Char, ids: List<String>) = ids.mapNotNull { it.drop(1).toIntOrNull() }.maxOrNull()
            max('a', alarms.map { it.id })?.let { put("alarm", it) }
            max('t', timers.map { it.id })?.let { put("timer", it) }
            max('s', stopwatches.map { it.id })?.let { put("stopwatch", it) }
            max('i', shopping.map { it.id })?.let { put("shopping", it) }
        }
        return WorldState(ToolWorld(timers, alarms, stopwatches, shopping, counters, muted, volume.optInt("restore_percent", percent)), percent, muted)
    }

}
