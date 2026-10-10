package com.kakauet.dina.dialog

import com.kakauet.dina.tools.Alarm
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.ShoppingItem
import com.kakauet.dina.tools.Stopwatch
import com.kakauet.dina.tools.Timer
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolSnapshot
import com.kakauet.dina.tools.ToolWorld
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Domains whose items can be referred to. [code] is the contract prefix; [tool] the engine's tool name. */
enum class Domain(val code: String, val tool: String) {
    ALARM("alarm", "alarm"), TIMER("timer", "timer"), SW("sw", "stopwatch"), LIST("list", "shopping");

    fun items(world: ToolWorld): List<Item> = when (this) {
        ALARM -> world.alarms.map { Item(this, it.id, it.label, it) }
        TIMER -> world.timers.map { Item(this, it.id, it.label, it) }
        SW -> world.stopwatches.map { Item(this, it.id, it.label, it) }
        LIST -> world.shopping.map { Item(this, it.id, it.name, it) }
    }

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code }
    }
}

/** A world item as the dialogue refers to it: label (or product name) plus the engine object. */
data class Item(val domain: Domain, val id: String, val name: String?, val raw: Any) {
    val alarm get() = raw as Alarm
    val timer get() = raw as Timer
    val stopwatch get() = raw as Stopwatch
    val product get() = raw as ShoppingItem

    fun time(zone: ZoneId): LocalTime? = (raw as? Alarm)?.let { Instant.ofEpochMilli(it.nextMs).atZone(zone).toLocalTime().withSecond(0).withNano(0) }
    fun date(zone: ZoneId): LocalDate? = (raw as? Alarm)?.let { Instant.ofEpochMilli(it.nextMs).atZone(zone).toLocalDate() }
}

/**
 * What a reference is resolved against besides the world: the item in focus, the list the user
 * just heard (positions "as read") and the candidates Dina just offered.
 */
data class RefContext(val focus: String? = null, val read: List<String>? = null, val offered: List<String>? = null)

/**
 * Turns a [Ref] into concrete items: 0 (not found), 1 or several (ambiguous, or all for `*`).
 * Labels compare by [SpanishText.key] (no articles, accents or plural "s"), then by containment
 * ("pollo" finds "pollo al horno"). Times without morning/afternoon match both.
 */
object Resolver {
    fun resolve(ref: Ref?, domain: Domain, snapshot: ToolSnapshot, context: RefContext = RefContext(), day: Day? = null): List<Item> {
        val zone = snapshot.zone
        val today = Instant.ofEpochMilli(snapshot.nowMs).atZone(zone).toLocalDate()
        var world = domain.items(snapshot.world)
        if (day != null && domain == Domain.ALARM) {
            world = when (day) {
                is Day.Weekday -> world.filter { it.date(zone)?.dayOfWeek == day.day }
                else -> day.resolve(today).let { date -> world.filter { it.date(zone) == date } }
            }
        }
        val offered = context.offered?.let { ids -> ids.mapNotNull { id -> world.firstOrNull { it.id == id } } }?.takeIf { it.isNotEmpty() }
        // Positions follow what the user heard last: the list read, else the candidates offered, else creation order.
        val ordered = context.read?.mapNotNull { id -> world.firstOrNull { it.id == id } }?.takeIf { it.isNotEmpty() } ?: offered ?: world
        fun within(match: (Item) -> Boolean): List<Item> = offered?.filter(match)?.takeIf { it.isNotEmpty() } ?: world.filter(match)
        return when (ref) {
            null -> offered ?: world
            Ref.All -> offered ?: world
            Ref.Focus -> world.filter { it.id == context.focus }.ifEmpty { offered ?: world }
            Ref.Ringing -> world.filter { (it.raw as? Alarm)?.status == AlarmStatus.RINGING || (it.raw as? Timer)?.status == TimerStatus.RINGING }
            Ref.Next -> listOfNotNull(next(offered ?: world, snapshot.nowMs))
            Ref.Last -> listOfNotNull(ordered.lastOrNull())
            is Ref.Nth -> listOfNotNull(ordered.getOrNull(ref.n - 1))
            is Ref.Named -> named(ref.text, offered).ifEmpty { named(ref.text, world) }
            is Ref.At -> ref.clock.times().let { times -> within { it.time(zone) in times } }
            is Ref.InPeriod -> within { item -> item.time(zone)?.let(ref.period::contains) == true }
        }
    }

    /** Exact label key first; otherwise every word of one contained in the other. */
    private fun named(text: String, items: List<Item>?): List<Item> {
        if (items == null) return emptyList()
        val key = SpanishText.key(text) ?: return emptyList()
        items.filter { SpanishText.key(it.name) == key }.takeIf { it.isNotEmpty() }?.let { return it }
        val wanted = key.split(' ').toSet()
        return items.filter { item ->
            val words = SpanishText.key(item.name)?.split(' ')?.toSet() ?: return@filter false
            words.containsAll(wanted) || (words.isNotEmpty() && wanted.containsAll(words))
        }
    }

    /** The alarm that rings first or the timer that ends first. */
    private fun next(items: List<Item>, nowMs: Long): Item? = items.minByOrNull { item ->
        when (val raw = item.raw) {
            is Alarm -> if (raw.status == AlarmStatus.DISABLED) Long.MAX_VALUE else raw.nextMs
            is Timer -> if (raw.status == TimerStatus.RINGING) 0L else raw.remainingAt(nowMs)
            else -> Long.MAX_VALUE
        }
    }?.takeIf { it.raw is Alarm || it.raw is Timer }
}
