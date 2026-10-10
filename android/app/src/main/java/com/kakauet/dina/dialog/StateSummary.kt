package com.kakauet.dina.dialog

import com.kakauet.dina.tools.Alarm
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.Repeat
import com.kakauet.dina.tools.StopwatchStatus
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolSnapshot
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The `[estado]` block of the prompt (docs/contrato.md): clock, only the domains that have
 * something, positions `#n` only for a list the user just heard, at most 5 items and "y N más",
 * volume only when muted or just talked about, the most recent focus and what is pending.
 */
object StateSummary {
    private const val SHOWN = 5
    private val WEEKDAYS = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
    private val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    fun render(snapshot: ToolSnapshot, view: DialogueView = DialogueView()): String {
        val now = Instant.ofEpochMilli(snapshot.nowMs).atZone(snapshot.zone)
        val world = snapshot.world
        val lines = mutableListOf("hora: ${WEEKDAYS[now.dayOfWeek.value - 1]} ${now.dayOfMonth} ${MONTHS[now.monthValue - 1]}, ${hm(now.hour, now.minute)}")
        fun section(name: String, domain: Domain, describe: (Item) -> String) {
            val items = domain.items(world)
            if (items.isEmpty()) return
            val read = view.read[domain]
            val shown = if (items.size > SHOWN + 1) items.take(SHOWN) else items
            val text = shown.joinToString(" · ") { item ->
                val position = read?.indexOf(item.id)?.takeIf { it >= 0 }?.let { "#${it + 1} " }.orEmpty()
                position + describe(item)
            } + if (shown.size < items.size) " y ${items.size - shown.size} más" else ""
            lines += "$name: $text"
        }
        section("alarmas", Domain.ALARM) { alarm(it.alarm, snapshot) }
        section("temporizadores", Domain.TIMER) { item ->
            val timer = item.timer
            listOfNotNull(label(item.name), clock(timer.remainingAt(snapshot.nowMs)), when (timer.status) {
                TimerStatus.PAUSED -> "(pausa)"
                TimerStatus.RINGING -> "(sonando)"
                TimerStatus.RUNNING -> null
            }).joinToString(" ")
        }
        section("cronos", Domain.SW) { item ->
            val sw = item.stopwatch
            listOfNotNull(label(item.name), clock(sw.elapsedAt(snapshot.nowMs)), "(pausa)".takeIf { sw.status == StopwatchStatus.PAUSED }).joinToString(" ")
        }
        section("compra", Domain.LIST) { item ->
            val product = item.product
            listOfNotNull(
                product.name,
                product.quantity?.let { "×" + encodeValue(it) + (product.unit?.let { u -> " $u" } ?: "") },
                "(hecho)".takeIf { product.completed },
            ).joinToString(" ")
        }
        if (snapshot.volume.muted || view.volumeRecent) lines += "volumen: " + if (snapshot.volume.muted) "silencio" else "${snapshot.volume.percent}"
        view.focus.firstOrNull()?.let { (domain, id) -> domain.items(world).firstOrNull { it.id == id }?.let { lines += "foco: " + focus(it, snapshot) } }
        view.pending?.let { lines += "pendiente: " + pending(it, snapshot) }
        return lines.joinToString("\n")
    }

    private fun alarm(alarm: Alarm, snapshot: ToolSnapshot): String {
        val at = Instant.ofEpochMilli(alarm.nextMs).atZone(snapshot.zone)
        val today = Instant.ofEpochMilli(snapshot.nowMs).atZone(snapshot.zone).toLocalDate()
        val day = when (val days = ChronoUnit.DAYS.between(today, at.toLocalDate())) {
            0L -> "hoy"
            1L -> "mañana"
            2L -> "pasado"
            in 3L..6L -> WEEKDAYS[at.dayOfWeek.value - 1]
            else -> if (days < 0) "hoy" else "${at.dayOfMonth}/${at.monthValue}"
        }
        val repeat = when (val r = alarm.repeat) {
            null -> null
            Repeat.Daily -> "diario"
            Repeat.Weekdays -> "lun-vie"
            Repeat.Weekends -> "finde"
            is Repeat.Weekly -> r.days.joinToString(",") { WEEKDAYS[it.value - 1] }
        }
        val status = when (alarm.status) {
            AlarmStatus.DISABLED -> "(desactivada)"
            AlarmStatus.RINGING -> "(sonando)"
            AlarmStatus.SNOOZED -> "(pospuesta)"
            AlarmStatus.SCHEDULED -> null
        }
        return listOfNotNull(day, hm(at.hour, at.minute), label(alarm.label), repeat, status).joinToString(" ")
    }

    private fun focus(item: Item, snapshot: ToolSnapshot): String = when (item.domain) {
        Domain.ALARM -> "alarma " + alarm(item.alarm, snapshot)
        Domain.TIMER -> "temporizador " + (label(item.name) ?: clock(item.timer.remainingAt(snapshot.nowMs)))
        Domain.SW -> "crono " + (label(item.name) ?: clock(item.stopwatch.elapsedAt(snapshot.nowMs)))
        Domain.LIST -> "compra " + item.product.name
    }

    private fun pending(pending: Pending, snapshot: ToolSnapshot): String {
        val need = when (val n = pending.need) {
            is Need.Slot -> "falta " + Dialogue.SLOT_NAMES.getOrDefault(n.slot, n.slot)
            is Need.Choice -> "¿cuál? " + n.ids.mapNotNull { id -> Domain.of(pending.action.domain)?.items(snapshot.world)?.firstOrNull { it.id == id } }
                .joinToString(" · ") { if (it.domain == Domain.ALARM) alarm(it.alarm, snapshot) else label(it.name) ?: it.id }
            is Need.Ampm -> "¿mañana o tarde?"
            Need.Confirm -> "¿confirmar?"
        }
        return "${pending.action.encode()} $need"
    }

    private fun label(text: String?) = text?.trim()?.takeIf { it.isNotEmpty() }?.let { "«$it»" }
    private fun hm(hour: Int, minute: Int) = "%d:%02d".format(hour, minute)

    /** "4:12", "1:05:00". */
    private fun clock(ms: Long): String {
        val seconds = (ms + 999) / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
