package com.kakauet.dina.dialog

import com.kakauet.dina.dialog.Spoken.Companion.capitalize
import com.kakauet.dina.dialog.Spoken.Companion.count
import com.kakauet.dina.dialog.Spoken.Companion.join
import com.kakauet.dina.dialog.Spoken.Companion.listed
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.Calculate
import com.kakauet.dina.tools.DatePart
import com.kakauet.dina.tools.Convert
import com.kakauet.dina.tools.DateTimeCommand
import com.kakauet.dina.tools.Dimension
import com.kakauet.dina.tools.Places
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.StopwatchStatus
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolData
import com.kakauet.dina.tools.ToolExecution
import com.kakauet.dina.tools.ToolFailure
import com.kakauet.dina.tools.ToolOutcome
import com.kakauet.dina.tools.ToolPresentation
import com.kakauet.dina.tools.Units
import com.kakauet.dina.tools.VolumeCommand
import com.kakauet.dina.tools.VolumeInfo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Random
import kotlin.math.abs

/** What the dialogue has to tell the user after a turn. Text comes only from [Responder]. */
sealed interface Reply {
    /**
     * A successful tool call; [item] is what it acted on (removals do not carry it), [undo] when it
     * reverted the previous turn, [clamped] when a value was limited (volume 150), [soon] when an
     * alarm for "mañana" said in the small hours rings today (the answer says how long until then).
     */
    data class Done(val execution: ToolExecution, val item: Item? = null, val undo: Boolean = false, val clamped: Boolean = false, val soon: Boolean = false) : Reply
    /** Several items read out (a list, or a query that matched several). */
    data class Listed(val domain: Domain, val items: List<Item>) : Reply
    /** One item read out (alarm.get, timer.get, sw.get). */
    data class Info(val item: Item) : Reply
    /** The tool refused; [item] is the one it was about, when known. */
    data class Failed(val execution: ToolExecution, val item: Item? = null) : Reply
    /** Nothing to do: it already was so ([case]: on, off, checked, unchecked, max, min, muted, unmuted). */
    data class Already(val case: String, val item: Item? = null, val volume: VolumeInfo? = null) : Reply
    data class NotFound(val domain: Domain, val ref: Ref?, val day: Day? = null) : Reply
    data class AskSlot(val action: Action, val slot: String) : Reply
    data class AskWhich(val action: Action, val candidates: List<Item>) : Reply
    data class AskAmpm(val options: List<LocalTime>) : Reply
    data class AskConfirm(val domain: Domain, val count: Int) : Reply
    /** "Hoy a las 7:00 ya ha pasado. ¿La pongo para mañana?" */
    data class PastToday(val time: LocalTime) : Reply
    data class Past(val date: LocalDate) : Reply
    data class NoSuchDate(val day: Day.Date, val asks: Boolean) : Reply
    data object ZeroDuration : Reply
    data class Reject(val topic: String) : Reply
    data object AskWhat : Reply
    data class Dropped(val had: Boolean) : Reply
    data object NothingRinging : Reply
    data object NothingToUndo : Reply
    /** Reminder of what is still pending after an interruption. */
    data class Remind(val question: Reply) : Reply
    /** A joke or a curious fact from the engine's banks ([text] null: the bank is empty). */
    data class Fun(val kind: FunBank.Kind, val text: String?) : Reply
    /** `yes()` with nothing to confirm. */
    data object Ack : Reply
    /** The model output could not be read. */
    data object NotUnderstood : Reply

    val isQuestion get() = this is AskSlot || this is AskWhich || this is AskAmpm || this is AskConfirm || this is PastToday ||
        this is AskWhat || this is Remind || this is NotUnderstood || this is Ack || (this is NoSuchDate && asks) || this is ZeroDuration
    val isNegative get() = this is Failed || this is Already || this is NotFound || this is Past || (this is NoSuchDate && !asks) ||
        this is Reject || this is NothingRinging || this is NothingToUndo
}

/**
 * Spanish answers from real results (docs/motor.md): every variant states all the
 * facts; variants rotate without repeating the last two of the same case (seeded, so tests are
 * reproducible). Several actions: what was done first, then what was not, then the question.
 */
class Responder(seed: Long = 0) {
    private val random = Random(seed)
    private val recent = mutableMapOf<String, ArrayDeque<Int>>()

    fun pick(case: String, variants: List<String>): String {
        if (variants.size == 1) return variants.single()
        val last = recent.getOrPut(case) { ArrayDeque() }
        val allowed = variants.indices.filter { it !in last }
        val index = allowed[random.nextInt(allowed.size)]
        last.addLast(index)
        while (last.size > minOf(2, variants.size - 1)) last.removeFirst()
        return variants[index]
    }

    /** The whole answer of a turn; [say] is the model's own text, already filtered. */
    fun render(replies: List<Reply>, say: String?, spoken: Spoken): String {
        val positives = replies.filter { !it.isQuestion && !it.isNegative }
        val negatives = replies.filter { it.isNegative }
        val questions = replies.filter { it.isQuestion }
        val sentences = mutableListOf<String>()
        sentences += aggregate(positives, spoken)
        negatives.forEachIndexed { i, reply ->
            val text = sentence(reply, spoken)
            sentences += if (i == 0 && positives.isNotEmpty() && reply !is Reply.Already) "Pero " + Spoken.decapitalize(text) else text
        }
        // The model's own words go before the question, so a turn always ends with what the user must answer.
        if (say != null) sentences += say
        questions.firstOrNull()?.let { sentences += sentence(it, spoken) }
        if (sentences.isEmpty()) sentences += pick("chat", CHAT)
        return sentences.joinToString(" ")
    }

    /** Several adds of the same kind become one sentence ("He apuntado leche, huevos y pan"). */
    private fun aggregate(replies: List<Reply>, spoken: Spoken): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < replies.size) {
            val reply = replies[i]
            if ((reply as? Reply.Done)?.undo == true) {
                var j = i + 1
                while (j < replies.size && (replies[j] as? Reply.Done)?.undo == true) j++
                out += undone(replies.subList(i, j).map { it as Reply.Done }, spoken)
                i = j
                continue
            }
            val op = (reply as? Reply.Done)?.execution?.let { "${it.tool}.${it.op}" }
            var j = i + 1
            while (op != null && op in GROUPED && j < replies.size && (replies[j] as? Reply.Done)?.let { !it.undo && "${it.execution.tool}.${it.execution.op}" == op } == true) j++
            out += if (j - i > 1) grouped(op!!, replies.subList(i, j).map { (it as Reply.Done).execution }, spoken) else sentence(reply, spoken)
            i = j
        }
        return out
    }

    /** Everything one undo did, in one sentence: "Deshecho: he quitado agua y servilletas de la lista." */
    private fun undone(replies: List<Reply.Done>, spoken: Spoken): String {
        if (replies.size == 1) return sentence(replies.single(), spoken)
        val removed = replies.filter { "${it.execution.tool}.${it.execution.op}" == "shopping.remove" }
        val names = removed.mapNotNull { it.item?.name }.reversed() // undo runs newest first; say them in the order they were added
        if (names.size == replies.size) return "Deshecho: he quitado ${join(names)} de la lista."
        return "Deshecho: " + replies.joinToString("; ") { Spoken.decapitalize(done(it, spoken)).removeSuffix(".") } + "."
    }

    private fun grouped(op: String, executions: List<ToolExecution>, spoken: Spoken): String = when (op) {
        "shopping.add" -> {
            val names = join(executions.mapNotNull { (it.data as? ToolData.ShoppingEntry)?.item?.let(spoken::product) })
            pick("list.add+", listOf("He apuntado $names.", "Apuntados: $names.", "Vale, $names a la lista."))
        }
        "timer.create" -> {
            val timers = executions.mapNotNull { (it.data as? ToolData.TimerItem)?.timer }
            "Temporizadores en marcha: " + join(timers.map { spoken.duration(it.durationMs) + spoken.ofLabel(it.label) }) + "."
        }
        else -> {
            val alarms = executions.mapNotNull { (it.data as? ToolData.AlarmItem)?.alarm }
            val days = alarms.map { Instant.ofEpochMilli(it.nextMs).atZone(spoken.zone).toLocalDate() }.distinct()
            val parts = if (days.size == 1) {
                val first = spoken.whenText(alarms.first().nextMs)
                listOf(first + spoken.ofLabel(alarms.first().label)) + alarms.drop(1).map { spoken.atTime(Instant.ofEpochMilli(it.nextMs).atZone(spoken.zone).toLocalTime()) + spoken.ofLabel(it.label) }
            } else alarms.map { spoken.alarmItem(it) }
            "He puesto ${count(alarms.size, "alarma", "alarmas")}: ${join(parts)}."
        }
    }

    fun sentence(reply: Reply, spoken: Spoken): String = when (reply) {
        is Reply.Done -> (if (reply.undo) "Deshecho: " else "") + done(reply, spoken).let { if (reply.undo) Spoken.decapitalize(it) else it }
        is Reply.Listed -> listed(reply, spoken)
        is Reply.Info -> info(reply.item, spoken)
        is Reply.Failed -> failed(reply, spoken)
        is Reply.Already -> already(reply, spoken)
        is Reply.NotFound -> notFound(reply, spoken)
        is Reply.AskSlot -> askSlot(reply.action, reply.slot, spoken)
        is Reply.AskWhich -> {
            val options = reply.candidates.take(3).map { candidate(it, spoken) }
            val which = join(options, "o")
            pick("which", listOf("¿Cuál: $which?", "¿Cuál quieres: $which?", "Hay ${reply.candidates.size}: $which. ¿Cuál?"))
        }
        is Reply.AskAmpm -> {
            val (a, b) = reply.options.map { (if (it.hour == 1) "la " else "las ") + spoken.hm(it) + if (it.hour < 12) " de la mañana" else "" }
            pick("ampm", listOf("¿A $a o a $b?", "¿Para $a o para $b?"))
        }
        is Reply.AskConfirm -> {
            val what = when (reply.domain) {
                Domain.ALARM -> "las ${reply.count} alarmas"
                Domain.TIMER -> "los ${reply.count} temporizadores"
                Domain.SW -> "los ${reply.count} cronómetros"
                Domain.LIST -> "las ${reply.count} cosas de la lista"
            }
            pick("confirm", listOf("¿Seguro que borro $what?", "Vas a borrar $what. ¿Lo hago?"))
        }
        is Reply.PastToday -> "Hoy ${spoken.atTime(reply.time)} ya ha pasado. ¿La pongo para mañana?"
        is Reply.Past -> "No puedo poner alarmas en el pasado: ${if (reply.date == spoken.today.minusDays(1)) "ayer" else spoken.day(reply.date)} ya ha pasado."
        is Reply.NoSuchDate -> (if (reply.day.month in 1..12) "El ${reply.day.day} de ${Spoken.MONTHS[reply.day.month - 1]} no existe." else "Esa fecha no existe.") +
            if (reply.asks) " ¿Qué día quieres?" else ""
        Reply.ZeroDuration -> "No puedo poner un temporizador de cero. ¿De cuánto tiempo lo quieres?"
        is Reply.Reject -> pick("no", listOf("", "Lo siento: ", "Perdona, ")).let { prefix ->
            val text = REJECTIONS[reply.topic] ?: REJECTIONS.getValue("otro")
            if (prefix.isEmpty()) text else prefix + Spoken.decapitalize(text)
        }
        Reply.AskWhat -> pick("what", listOf("¿Qué necesitas?", "No te he entendido del todo. ¿Qué quieres que haga?", "Dime, ¿qué quieres que haga?"))
        is Reply.Dropped -> pick("drop", listOf("Vale, lo dejo.", "De acuerdo, no hago nada.", "Vale, olvídalo."))
        Reply.NothingRinging -> pick("ringing", listOf("No hay nada sonando ahora mismo.", "Ahora mismo no hay ninguna alarma ni temporizador sonando."))
        Reply.NothingToUndo -> "No hay nada que deshacer."
        is Reply.Remind -> "Por cierto, " + Spoken.decapitalize(sentence(reply.question, spoken))
        Reply.Ack -> "Vale. ¿Qué necesitas?"
        is Reply.Fun -> reply.text ?: if (reply.kind == FunBank.Kind.JOKE) "Ahora mismo no me sé ningún chiste." else "Ahora mismo no me sé ninguna curiosidad."
        Reply.NotUnderstood -> pick("misread", listOf("Perdona, no te he entendido. ¿Me lo repites?", "No te he entendido bien. ¿Me lo dices de otra forma?"))
    }

    // ---- Done ----

    private fun done(reply: Reply.Done, spoken: Spoken): String {
        val execution = reply.execution
        val success = execution.outcome as ToolOutcome.Success
        val data = success.data
        val op = "${execution.tool}.${execution.op}"
        return when (data) {
            is ToolData.AlarmItem -> {
                val alarm = data.alarm
                val l = spoken.ofLabel(alarm.label)
                val w = spoken.whenText(alarm.nextMs) + (alarm.repeat?.let { ", " + spoken.repeatText(it) } ?: "") +
                    if (reply.soon) ", dentro de ${spoken.roughDuration(alarm.nextMs - spoken.nowMs)}" else ""
                when (execution.op) {
                    "create" -> pick(op, listOf("Alarma$l puesta para $w.", "Hecho, alarma$l para $w.", "Vale, alarma$l $w.", "Listo: alarma$l, $w."))
                    "update" -> pick(op, listOf("Alarma$l cambiada: suena $w.", "Hecho, la alarma$l sonará $w.", "Vale, ahora la alarma$l suena $w."))
                    "enable" -> pick(op, listOf("Alarma$l activada: suena $w.", "Hecho, la alarma$l vuelve a sonar $w."))
                    "disable" -> pick(op, listOf("La alarma$l ${ofWhen(w)} queda desactivada.", "Alarma$l ${ofWhen(w)} desactivada."))
                    "snooze" -> pick(op, listOf("Alarma pospuesta hasta las ${spoken.hm(time(alarm.nextMs, spoken))}.", "Vale, vuelve a sonar ${spoken.atTime(time(alarm.nextMs, spoken))}."))
                    "dismiss" -> "Alarma$l apagada; vuelve a sonar $w."
                    else -> "La alarma$l suena $w."
                }
            }
            is ToolData.TimerItem -> {
                val timer = data.timer
                val l = spoken.ofLabel(timer.label)
                val r = spoken.remaining(timer)
                when (execution.op) {
                    "create" -> {
                        val d = spoken.duration(timer.durationMs)
                        if (l.isEmpty()) pick(op, listOf("Temporizador de $d en marcha.", "Vale, $d.", "Hecho: temporizador de $d."))
                        else pick("$op+l", listOf("Temporizador$l en marcha: $d.", "Vale, $d para el temporizador$l.", "Hecho: temporizador$l, $d."))
                    }
                    "pause" -> pick(op, listOf("Temporizador$l en pausa; quedan $r.", "Vale, temporizador$l en pausa con $r."))
                    "resume" -> pick(op, listOf("Temporizador$l en marcha otra vez; quedan $r.", "Sigue el temporizador$l: quedan $r."))
                    "update" -> pick(op, listOf("Temporizador$l: ahora quedan $r.", "Hecho, al temporizador$l le quedan $r."))
                    else -> "Al temporizador$l le quedan $r."
                }
            }
            is ToolData.StopwatchItem -> {
                val sw = data.stopwatch
                val l = spoken.ofLabel(sw.label)
                val e = spoken.elapsed(sw)
                when (execution.op) {
                    "start" -> pick(op, listOf("Cronómetro$l en marcha.", "Vale, cronómetro$l en marcha."))
                    "pause" -> pick(op, listOf("Cronómetro$l en pausa en $e.", "Cronómetro$l parado en $e."))
                    "resume" -> "Cronómetro$l en marcha desde $e."
                    "reset" -> "Cronómetro$l a cero."
                    "restart" -> "Cronómetro$l a cero y en marcha."
                    else -> "El cronómetro$l marca $e."
                }
            }
            is ToolData.ShoppingEntry -> {
                val p = spoken.product(data.item)
                when (execution.op) {
                    "add" -> pick(op, listOf("He apuntado $p.", "Apuntado: $p.", "Vale, $p a la lista.", "Añadido $p a la lista."))
                    "mark" -> pick(op, listOf("Marcado como comprado: ${data.item.name}.", "Vale, tachado de la lista: ${data.item.name}."))
                    "unmark" -> "${capitalize(data.item.name)} vuelve a estar pendiente."
                    else -> pick(op, listOf("Ahora en la lista: $p.", "Cambiado: $p."))
                }
            }
            is ToolData.RemovedId -> removed(execution, reply.item, spoken)
            is ToolData.RemovedCount -> when (execution.tool) {
                "alarm" -> "He borrado ${count(data.count, "alarma", "alarmas")}."
                "timer" -> "He quitado ${count(data.count, "temporizador", "temporizadores")}."
                "stopwatch" -> "He borrado ${count(data.count, "cronómetro", "cronómetros")}."
                else -> "He vaciado la lista: ${count(data.count, "cosa", "cosas")} fuera."
            }
            is ToolData.Volume -> when {
                data.info.muted -> pick("vol.mute", listOf("Silenciado.", "Vale, en silencio."))
                reply.clamped && data.info.percent == 100 -> "Volumen al máximo, 100 por ciento."
                execution.command is VolumeCommand.Get -> volumeNow(data.info)
                execution.command is VolumeCommand.Restore -> "Volumen al ${data.info.percent} por ciento otra vez."
                else -> pick("vol.set", listOf("Volumen al ${data.info.percent} por ciento.", "Vale, volumen al ${data.info.percent}.", "Hecho: el volumen está al ${data.info.percent} por ciento."))
            }
            is ToolData.DateTimeValue -> dateTime(execution, data, spoken)
            is ToolData.DaysBetween -> {
                val to = (execution.command as DateTimeCommand.DaysBetween).to
                when (data.days) {
                    0L -> "Es hoy."
                    1L -> "Falta 1 día: es mañana."
                    else -> pick("until", listOf("Faltan ${data.days} días para el ${spoken.date(to)}.", "Quedan ${data.days} días para el ${spoken.date(to)}."))
                }
            }
            is ToolData.Number -> spoken.number(data.value).let {
                if (ToolPresentation.isRounded(data.value)) pick("calc~", listOf("Son aproximadamente $it.", "El resultado es aproximadamente $it."))
                else pick("calc", listOf("Son $it.", "El resultado es $it.", "Da $it."))
            }
            is ToolData.Conversion -> {
                val from = spoken.amount(data.value, data.from, exact = true) + (data.ingredient?.let { " de $it" } ?: "")
                val to = spoken.amount(data.result, data.to)
                capitalize(pick("conv", listOf("$from son $to.", "$from " + (if (data.value == 1.0) "equivale" else "equivalen") + " a $to.")))
            }
            else -> "Hecho."
        }
    }

    private fun removed(execution: ToolExecution, item: Item?, spoken: Spoken): String {
        val l = spoken.ofLabel(item?.name)
        return when (execution.tool) {
            "alarm" -> {
                val w = item?.let { " " + ofWhen(spoken.whenText(it.alarm.nextMs)) }.orEmpty()
                if (execution.op == "dismiss") "Alarma$l apagada." else pick("alarm.cancel", listOf("Alarma$l$w eliminada.", "He quitado la alarma$l$w.", "Vale, alarma$l$w borrada."))
            }
            "timer" -> if (execution.op == "dismiss") "Temporizador$l apagado." else pick("timer.cancel", listOf("Temporizador$l cancelado.", "He quitado el temporizador$l."))
            "stopwatch" -> "Cronómetro$l borrado."
            else -> (item?.name ?: "eso").let { pick("list.del", listOf("He quitado $it de la lista.", "Quitado $it de la lista.")) }
        }
    }

    private fun dateTime(execution: ToolExecution, data: ToolData.DateTimeValue, spoken: Spoken): String = when (val command = execution.command) {
        is DateTimeCommand.Now -> if (command.location != null) placeTime(command, data, spoken) else when (command.part) {
            DatePart.TIME -> LocalTime.parse(data.value).let { t ->
                val verb = if (t.hour == 1) "Es la" else "Son las"
                pick("now", listOf("$verb ${spoken.hm(t)}.", "Ahora ${verb.lowercase()} ${spoken.hm(t)}."))
            }
            DatePart.DATE -> LocalDate.parse(data.value).let { d -> pick("date", listOf("Hoy es ${spoken.weekday(d.dayOfWeek)}, ${spoken.date(d)}.", "Estamos a ${spoken.weekday(d.dayOfWeek)}, ${spoken.date(d)}.")) }
            DatePart.WEEKDAY -> "Hoy es ${Spoken.dayName(data.value)}, ${spoken.date(spoken.today)}."
            DatePart.DATETIME -> OffsetDateTime.parse(data.value).let { "Hoy es ${spoken.weekday(it.dayOfWeek)}, ${spoken.date(it.toLocalDate())}, y son las ${spoken.hm(it.toLocalTime())}." }
        }
        is DateTimeCommand.Weekday -> {
            val day = Spoken.dayName(data.value)
            when (command.date) {
                spoken.today -> "Hoy es $day, ${spoken.date(command.date)}."
                spoken.today.plusDays(1) -> "Mañana es $day, ${spoken.date(command.date)}."
                spoken.today.plusDays(2) -> "Pasado mañana es $day, ${spoken.date(command.date)}."
                else -> pick("weekday", listOf("El ${spoken.date(command.date)} es $day.", "El ${spoken.date(command.date)} cae en $day."))
            }
        }
        else -> data.value
    }

    /** "En Londres son las 9:00, una hora menos que aquí."; the day too when it is another one there. */
    private fun placeTime(command: DateTimeCommand.Now, data: ToolData.DateTimeValue, spoken: Spoken): String {
        val place = Places.find(command.location!!)?.name ?: capitalize(command.location.trim())
        val there = ZoneId.of(data.timezone)
        val instant = Instant.ofEpochMilli(spoken.nowMs)
        val local = instant.atZone(there)
        val day = local.toLocalDate()
        return when (command.part) {
            DatePart.TIME -> {
                val other = if (day != spoken.today) " del ${spoken.weekday(day.dayOfWeek)}" else ""
                val verb = if (local.hour == 1) "es la" else "son las"
                "En $place $verb ${spoken.hm(local.toLocalTime())}$other, ${offset(there.rules.getOffset(instant).totalSeconds - spoken.zone.rules.getOffset(instant).totalSeconds)}."
            }
            else -> "En $place es ${spoken.weekday(day.dayOfWeek)}, ${spoken.date(day)}."
        }
    }

    /** "la misma hora que aquí", "una hora menos que aquí", "3 horas y media más que aquí". */
    private fun offset(seconds: Int): String {
        if (seconds == 0) return "la misma hora que aquí"
        val total = abs(seconds) / 60
        val hours = total / 60
        val minutes = total % 60
        val amount = when {
            hours == 0 -> "$minutes minutos"
            hours == 1 && minutes == 0 -> "una hora"
            else -> count(hours, "hora", "horas") + when (minutes) { 0 -> ""; 30 -> " y media"; else -> " y $minutes minutos" }
        }
        return "$amount ${if (seconds > 0) "más" else "menos"} que aquí"
    }

    private fun volumeNow(info: VolumeInfo) = if (info.muted) "El volumen está en silencio." else pick("vol.get", listOf("El volumen está al ${info.percent} por ciento.", "Está al ${info.percent} por ciento."))

    // ---- Reads ----

    private fun listed(reply: Reply.Listed, spoken: Spoken): String {
        val items = reply.items
        if (items.isEmpty()) return notFound(Reply.NotFound(reply.domain, null), spoken)
        return when (reply.domain) {
            Domain.ALARM -> "Tienes ${count(items.size, "alarma", "alarmas")}: ${listed(items.map { spoken.alarmItem(it.alarm) })}."
            Domain.TIMER -> "Tienes ${count(items.size, "temporizador", "temporizadores")}: ${listed(items.map { spoken.timerItem(it.timer) })}."
            Domain.SW -> "Tienes ${count(items.size, "cronómetro", "cronómetros")}: ${listed(items.map { spoken.stopwatchItem(it.stopwatch) })}."
            Domain.LIST -> {
                val pending = items.filterNot { it.product.completed }
                val done = items.filter { it.product.completed }
                val total = count(items.size, "cosa", "cosas")
                when {
                    pending.isEmpty() -> "En la lista hay $total, ya compradas: ${listed(done.map { spoken.product(it.product) })}."
                    done.isEmpty() -> pick("list.list", listOf("En la lista tienes $total: ${listed(pending.map { spoken.product(it.product) })}.", "Tienes $total apuntadas: ${listed(pending.map { spoken.product(it.product) })}."))
                    else -> "En la lista tienes $total: ${listed(pending.map { spoken.product(it.product) })}; ya has comprado ${listed(done.map { spoken.product(it.product) })}."
                }
            }
        }
    }

    private fun info(item: Item, spoken: Spoken): String = when (item.domain) {
        Domain.ALARM -> {
            val alarm = item.alarm
            val l = spoken.ofLabel(alarm.label)
            val w = spoken.whenText(alarm.nextMs) + (alarm.repeat?.let { ", " + spoken.repeatText(it) } ?: "")
            when (alarm.status) {
                AlarmStatus.DISABLED -> "La alarma$l ${ofWhen(w)} está desactivada."
                AlarmStatus.RINGING -> "La alarma$l está sonando ahora."
                AlarmStatus.SNOOZED -> "La alarma$l está pospuesta hasta las ${spoken.hm(time(alarm.nextMs, spoken))}."
                AlarmStatus.SCHEDULED -> {
                    val left = alarm.nextMs - spoken.nowMs
                    val inText = if (left in 60_000L until 86_400_000L) ", dentro de ${spoken.roughDuration(left)}" else ""
                    pick("alarm.get", listOf("La alarma$l suena $w$inText.", "Tu alarma$l suena $w$inText."))
                }
            }
        }
        Domain.TIMER -> {
            val timer = item.timer
            val l = spoken.ofLabel(timer.label)
            val r = spoken.remaining(timer)
            when (timer.status) {
                TimerStatus.RUNNING -> pick("timer.get", listOf("Al temporizador$l le quedan $r.", "Quedan $r en el temporizador$l."))
                TimerStatus.PAUSED -> "El temporizador$l está en pausa; quedan $r."
                TimerStatus.RINGING -> "El temporizador$l está sonando: ya ha terminado."
            }
        }
        Domain.SW -> {
            val sw = item.stopwatch
            val l = spoken.ofLabel(sw.label)
            "El cronómetro$l marca ${spoken.elapsed(sw)}" + (if (sw.status == StopwatchStatus.PAUSED) " y está en pausa." else ".")
        }
        Domain.LIST -> "En la lista tienes ${spoken.product(item.product)}" + if (item.product.completed) ", ya comprado." else "."
    }

    // ---- Problems ----

    private fun failed(reply: Reply.Failed, spoken: Spoken): String {
        val execution = reply.execution
        val failure = execution.outcome as ToolOutcome.Failure
        val subject = reply.item?.let { subject(it, spoken) }
        return when (failure.code) {
            ToolFailure.INVALID_STATE -> when {
                failure.details["expected"] == "running" -> "${subject ?: "Eso"} ya está en pausa."
                failure.details["expected"] == "paused" -> "${subject ?: "Eso"} ya está en marcha."
                failure.details["expected"] == "ringing" -> "${subject ?: "Eso"} no está sonando."
                failure.details["reason"] == "past_alarm" -> "${subject ?: "Esa alarma"} ya ha pasado; dime para cuándo la quieres."
                else -> "No he podido hacerlo en su estado actual."
            }
            ToolFailure.CONFLICT -> (execution.command as? ShoppingCommand.Add)?.let { "${capitalize(it.name.trim())} ya estaba en la lista." } ?: "Eso ya existe."
            ToolFailure.NOT_FOUND -> when (execution.tool) {
                "alarm" -> "No hay ninguna alarma sonando."
                "timer" -> "No hay ningún temporizador sonando."
                "datetime" -> (failure.details["location"] as? String)?.let { "No sé qué hora es en ${capitalize(it.trim())}." } ?: "No lo he encontrado."
                else -> "No lo he encontrado."
            }
            ToolFailure.INVALID_ARGUMENTS -> when {
                execution.command is Calculate && DIVISION_BY_ZERO.containsMatchIn(execution.command.expression) -> "No puedo dividir entre cero: no tiene resultado."
                execution.command is Calculate -> "No puedo hacer esa cuenta."
                execution.command is Convert -> unconvertible(execution.command, failure.details, spoken)
                else -> "No he podido ${verb(execution)}: algún dato no es válido."
            }
            else -> "No he podido ${verb(execution)}: ${REASONS[failure.code] ?: "algo ha fallado"}."
        }
    }

    /** Why a conversion cannot be done, with what Dina can say instead. */
    private fun unconvertible(command: Convert, details: Map<String, Any?>, spoken: Spoken): String {
        val from = Units.of(command.from)
        val to = Units.of(command.to)
        return when (details["reason"]) {
            "dimension" -> if (from != null && to != null) "No puedo pasar ${from.many} a ${to.many}: uno es ${from.dimension.word} y el otro ${to.dimension.word}."
                else "No puedo hacer esa conversión."
            "ingredient" -> {
                val what = "${spoken.amount(command.value, command.from, exact = true)} de ${command.ingredient?.trim() ?: "eso"}"
                val ml = details["ml"] as? Double
                if (from?.dimension == Dimension.VOLUME) "No sé cuánto pesa $what" + (ml?.let { "; en volumen son ${spoken.amount(it, "ml")}" } ?: "") + "."
                else "No sé cuánto ocupa $what."
            }
            "absolute_zero" -> "Esa temperatura no existe: está por debajo del cero absoluto."
            else -> "No puedo hacer esa conversión."
        }
    }

    private fun already(reply: Reply.Already, spoken: Spoken): String {
        val item = reply.item
        return when (reply.case) {
            "on" -> "La alarma${spoken.ofLabel(item?.name)} ya está activada: suena ${spoken.whenText(item!!.alarm.nextMs)}."
            "off" -> "La alarma${spoken.ofLabel(item?.name)} ${ofWhen(spoken.whenText(item!!.alarm.nextMs))} ya estaba desactivada."
            "checked" -> "${capitalize(item!!.name.orEmpty())} ya estaba marcado como comprado."
            "unchecked" -> "${capitalize(item!!.name.orEmpty())} ya estaba pendiente."
            "max" -> "El volumen ya está al máximo, al 100 por ciento."
            "min" -> "El volumen ya está en silencio, al 0."
            "muted" -> "El volumen ya está en silencio."
            else -> "El volumen no está silenciado: está al ${reply.volume?.percent ?: 0} por ciento."
        }
    }

    private fun notFound(reply: Reply.NotFound, spoken: Spoken): String {
        val ref = reply.ref
        val dayText = reply.day?.let { day -> day.resolve(spoken.today)?.let { " " + spoken.day(it).removePrefix("el ").let { d -> if (day is Day.Weekday) "el $d" else d } } }.orEmpty()
        return when (reply.domain) {
            Domain.LIST -> (ref as? Ref.Named)?.let { "No tienes ${it.text} en la lista." } ?: "La lista de la compra está vacía."
            else -> {
                val (none, noun) = when (reply.domain) {
                    Domain.ALARM -> "ninguna" to "alarma"
                    Domain.TIMER -> "ningún" to "temporizador"
                    else -> "ningún" to "cronómetro"
                }
                val which = when (ref) {
                    is Ref.Named -> spoken.ofLabel(ref.text)
                    is Ref.At -> " " + spoken.atTime(if (ref.clock.ambiguous) LocalTime.of(ref.clock.hour, ref.clock.minute) else ref.clock.times().first())
                    is Ref.InPeriod -> " por la ${ref.period.word}"
                    Ref.Ringing -> " sonando"
                    else -> ""
                }
                "No tienes $none $noun$which$dayText."
            }
        }
    }

    private fun askSlot(action: Action, slot: String, spoken: Spoken): String {
        val case = "${action.op}/$slot"
        return when (case) {
            "alarm.add/time" -> {
                val day = action.day()?.resolve(spoken.today)?.let { " " + if (spoken.day(it).startsWith("el ")) spoken.day(it) else "para " + spoken.day(it) }.orEmpty()
                pick(case, listOf("¿A qué hora pongo la alarma$day?", "¿A qué hora la quieres$day?", "Vale, ¿a qué hora$day?", "¿Para qué hora$day?"))
            }
            "alarm.edit/time" -> pick(case, listOf("¿A qué hora la pongo?", "¿A qué hora la cambio?"))
            "alarm.edit/label", "timer.edit/label", "list.edit/name" -> pick("label", listOf("¿Qué nombre le pongo?", "¿Cómo la llamo?".let { if (action.domain == "alarm") it else "¿Cómo lo llamo?" }))
            "alarm.add/day", "time.weekday/day", "time.until/day" -> pick("day", listOf("¿Qué día?", "¿Qué fecha?", "¿De qué día me hablas?"))
            "timer.add/dur" -> pick(case, listOf("¿De cuánto tiempo pongo el temporizador?", "¿Cuántos minutos?", "¿Cuánto tiempo le pongo?", "Vale, ¿de cuánto tiempo?"))
            "timer.plus/dur", "timer.minus/dur", "timer.edit/left" -> "¿Cuánto tiempo?"
            "list.add/name" -> pick(case, listOf("¿Qué quieres que apunte?", "¿Qué apunto?", "Dime qué añado a la lista.", "¿Qué añado a la lista?"))
            "list.edit/n" -> "¿Cuántos pongo?"
            "vol.set/n" -> pick(case, listOf("¿A qué volumen lo pongo?", "¿A cuánto lo pongo?", "¿Qué volumen quieres?"))
            "calc/expr" -> pick(case, listOf("¿Qué cuenta hago?", "¿Qué operación quieres?", "Dime la cuenta."))
            "conv/n" -> Units.of(action.text("from").orEmpty())?.let { unit ->
                val many = "¿${if (unit.feminine) "Cuántas" else "Cuántos"} ${unit.many}"
                pick(case, listOf("$many?", "$many quieres pasar?"))
            } ?: "¿Qué cantidad?"
            "conv/from" -> pick(case, listOf("¿En qué unidad está?", "¿${spoken.number(action.amount() ?: 0.0)} qué?"))
            "conv/to" -> pick(case, listOf("¿A qué unidad lo paso?", "¿A qué lo paso?"))
            "conv/what" -> pick(case, listOf("¿De qué ingrediente? Una taza de harina no pesa lo mismo que una de azúcar.", "¿De qué? No pesa lo mismo la harina que el azúcar."))
            else -> pick("change", listOf("¿Qué quieres cambiar?", "¿Qué cambio?"))
        }
    }

    private fun candidate(item: Item, spoken: Spoken): String = when (item.domain) {
        Domain.ALARM -> "la de " + spoken.whenText(item.alarm.nextMs).let { if (it.startsWith("el ")) it.drop(3) else it } + spoken.ofLabel(item.name)
        Domain.TIMER -> "el" + (spoken.ofLabel(item.name).ifEmpty { " sin nombre" }) + " (${spoken.remaining(item.timer)})"
        Domain.SW -> "el" + spoken.ofLabel(item.name).ifEmpty { " sin nombre" }
        Domain.LIST -> spoken.product(item.product)
    }

    private fun subject(item: Item, spoken: Spoken): String = when (item.domain) {
        Domain.ALARM -> "La alarma" + spoken.ofLabel(item.name)
        Domain.TIMER -> "El temporizador" + spoken.ofLabel(item.name)
        Domain.SW -> "El cronómetro" + spoken.ofLabel(item.name)
        Domain.LIST -> capitalize(item.name.orEmpty())
    }

    private fun verb(execution: ToolExecution): String = when ("${execution.tool}.${execution.op}") {
        "alarm.create" -> "poner la alarma"
        "timer.create" -> "poner el temporizador"
        "stopwatch.start" -> "poner el cronómetro"
        "shopping.add" -> (execution.command as? ShoppingCommand.Add)?.let { "apuntar ${it.name}" } ?: "apuntarlo"
        "volume.set", "volume.increase", "volume.decrease", "volume.mute", "volume.restore" -> "cambiar el volumen"
        else -> "hacerlo"
    }

    private fun ofWhen(text: String) = if (text.startsWith("el ")) "del ${text.drop(3)}" else "de $text"
    private fun time(ms: Long, spoken: Spoken) = Instant.ofEpochMilli(ms).atZone(spoken.zone).toLocalTime()

    companion object {
        private val GROUPED = setOf("shopping.add", "timer.create", "alarm.create")
        private val DIVISION_BY_ZERO = Regex("[/:÷]\\s*\\(?\\s*0+(?:[.,]0+)?(?![\\d.,])")
        val CHAT = listOf("Aquí estoy. ¿En qué te ayudo?", "Dime.", "Te escucho.", "Vale.")
        private val REASONS = mapOf(
            "permission_denied" to "me falta un permiso",
            "unavailable" to "ahora mismo no está disponible; inténtalo otra vez",
            ToolFailure.INTERNAL to "algo ha fallado",
            ToolFailure.UNSUPPORTED to "no sé hacerlo",
        )
        /** Every topic says what Dina cannot do and, when there is one, the closest thing she can. */
        val REJECTIONS = mapOf(
            "música" to "No puedo poner música. Sí puedo ponerte alarmas y temporizadores.",
            "llamadas" to "No puedo hacer llamadas. Si quieres, te pongo una alarma para acordarte.",
            "mensajes" to "No puedo mandar mensajes. Si quieres, te pongo una alarma para acordarte.",
            "internet" to "No puedo buscar en internet: funciono sin conexión.",
            "tiempo" to "No puedo consultar el tiempo: funciono sin internet.",
            "casa" to "No puedo controlar luces ni aparatos de casa.",
            "apps" to "No puedo abrir aplicaciones.",
            "fotos" to "No puedo hacer ni ver fotos.",
            "calendario" to "No puedo usar tu calendario, pero sí ponerte una alarma con nombre.",
            "compras" to "No puedo pedir ni comprar nada, pero sí apuntarlo en la lista de la compra.",
            "pagos" to "No puedo hacer pagos ni transferencias.",
            "móvil" to "No puedo cambiar los ajustes del móvil; solo el volumen.",
            "noticias" to "No puedo poner noticias, radio ni tele: funciono sin internet.",
            "idiomas" to "No puedo traducir; solo hablo español.",
            "zonas" to "No puedo decirte la hora de otras ciudades; solo la de aquí.",
            "fechas" to "No puedo calcular fechas así; sí puedo decirte qué día cae una fecha o cuánto falta.",
            "otro" to "Eso no puedo hacerlo. Puedo con alarmas, temporizadores, cronómetros, la compra, el volumen, la hora y cuentas.",
        )
    }
}

/**
 * The only free text a model may say (`say`). Dropped when it has clock times, names of the user's
 * items or claims to have done something: those come from results only. Next to actions it is a
 * short coletilla without numbers; in a turn of pure chat ([chatOnly]) it may be
 * longer and use ordinary numbers ("tengo cero años de experiencia en vacaciones").
 * Dina never claims to be a person: such a say becomes [IDENTITY].
 */
object SayFilter {
    const val IDENTITY = "No soy una persona: soy Dina, una asistente de voz."
    private const val MAX_WORDS = 25
    private const val MAX_CHAT_WORDS = 50
    private val HUMAN_CLAIM = Regex("(?<!\\bno )\\b(soy|somos) (una |un )?(persona|humana|humano|ser humano|de carne y hueso)\\b")
    private val NUMBER_WORDS = setOf(
        "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece", "catorce",
        "quince", "veinte", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa", "cien", "ciento", "mil",
    )

    fun accept(text: String?, names: Collection<String>, chatOnly: Boolean = false): String? {
        val clean = text?.let(Texts::clean)?.takeIf { it.isNotEmpty() } ?: return null
        val folded = SpanishText.fold(clean)
        if (HUMAN_CLAIM.containsMatchIn(folded)) return IDENTITY
        val words = folded.split(' ')
        if (words.size > if (chatOnly) MAX_CHAT_WORDS else MAX_WORDS) return null
        if (!chatOnly && (clean.any { it.isDigit() } || words.any { it in NUMBER_WORDS })) return null
        if (SpanishText.times(SpanishText.normalize(clean)).isNotEmpty()) return null
        val normalized = SpanishText.normalize(clean)
        if (names.any { name -> SpanishText.mentionsPhrase(normalized, name) }) return null
        if (SpanishText.successClaim(clean) != SpanishText.Claim.NONE) return null
        return clean
    }
}
