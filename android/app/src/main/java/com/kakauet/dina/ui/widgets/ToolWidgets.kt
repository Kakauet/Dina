package com.kakauet.dina.ui.widgets

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakauet.dina.dina
import com.kakauet.dina.tools.Alarm
import com.kakauet.dina.tools.AlarmCommand
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.ContextualWidget
import com.kakauet.dina.tools.Patch
import com.kakauet.dina.tools.Repeat
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.ShoppingItem
import com.kakauet.dina.tools.Stopwatch
import com.kakauet.dina.tools.StopwatchCommand
import com.kakauet.dina.tools.StopwatchStatus
import com.kakauet.dina.tools.Target
import com.kakauet.dina.tools.Timer
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolCommand
import com.kakauet.dina.tools.ToolPresentation
import com.kakauet.dina.tools.ToolWorld
import com.kakauet.dina.tools.ToolWorldJson
import com.kakauet.dina.tools.VolumeCommand
import com.kakauet.dina.tools.WhenSpec
import com.kakauet.dina.tools.WidgetKind
import com.kakauet.dina.ui.components.Action
import com.kakauet.dina.ui.components.ActionRow
import com.kakauet.dina.ui.components.ButtonStyle
import com.kakauet.dina.ui.components.Caption
import com.kakauet.dina.ui.components.CardTone
import com.kakauet.dina.ui.components.Chip
import com.kakauet.dina.ui.components.DinaButton
import com.kakauet.dina.ui.components.DinaCard
import com.kakauet.dina.ui.components.DinaIcon
import com.kakauet.dina.ui.components.DinaSlider
import com.kakauet.dina.ui.components.DinaSwitch
import com.kakauet.dina.ui.components.DinaTextField
import com.kakauet.dina.ui.components.EmptyState
import com.kakauet.dina.ui.components.Overline
import com.kakauet.dina.ui.components.RoundIconButton
import com.kakauet.dina.ui.components.SketchCheck
import com.kakauet.dina.ui.components.SketchIcon
import com.kakauet.dina.ui.components.SketchRing
import com.kakauet.dina.ui.theme.Dina
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private val spanish = Locale.forLanguageTag("es-ES")

/** Runs a tool command from a UI control. The same engine serves voice and text. */
@Composable
private fun rememberRunner(): (ToolCommand) -> Unit {
    val context = LocalContext.current
    return remember<(ToolCommand) -> Unit>(context) { { command -> context.dina.tools.execute(command) } }
}

/** Wall clock that ticks while the widget is on screen, for live countdowns. */
@Composable
fun rememberNow(intervalMs: Long = 250L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return now
}

private fun byId(id: String) = Target(id = id)

fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

/** "4 minutos y 12 segundos", for screen readers. */
private fun spoken(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val parts = listOfNotNull(
        (total / 3600).takeIf { it > 0 }?.let { if (it == 1L) "1 hora" else "$it horas" },
        (total % 3600 / 60).takeIf { it > 0 }?.let { if (it == 1L) "1 minuto" else "$it minutos" },
        (total % 60).takeIf { it > 0 || total == 0L }?.let { if (it == 1L) "1 segundo" else "$it segundos" },
    )
    return if (parts.size > 1) parts.dropLast(1).joinToString(", ") + " y " + parts.last() else parts.first()
}

private fun hourMinute(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm"))

/** A gentle back-and-forth used by ringing timers and alarms; still with reduced motion. */
@Composable
private fun ringingWobble(active: Boolean): () -> Float {
    if (!active || Dina.motion.reduced) return { 0f }
    val transition = rememberInfiniteTransition(label = "ringing")
    val wobble = transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "wobble")
    return { wobble.value }
}

/** The widget shown after a turn, rendered from live state rather than the turn's snapshot. */
@Composable
fun ContextualWidgetView(widget: ContextualWidget, world: ToolWorld, modifier: Modifier = Modifier) {
    Column(modifier) {
        when (widget.kind) {
            WidgetKind.TIMER -> FocusedOrList(widget, world.timers, Timer::id, DinaIcon.HOURGLASS, "temporizadores") { TimerCard(it, compact = true) }
            WidgetKind.ALARM -> FocusedOrList(widget, world.alarms, Alarm::id, DinaIcon.BELL, "alarmas") { AlarmCard(it, compact = true) }
            WidgetKind.STOPWATCH -> FocusedOrList(widget, world.stopwatches, Stopwatch::id, DinaIcon.STOPWATCH, "cronómetros") { StopwatchCard(it, compact = true) }
            WidgetKind.SHOPPING -> ShoppingCard(world.shopping, compact = true)
            WidgetKind.VOLUME -> VolumeCard()
            WidgetKind.CALCULATOR -> ValueCard("Cálculo", widget.value.orEmpty())
            WidgetKind.DATETIME -> ValueCard("Fecha y hora", widget.value.orEmpty(), DinaIcon.CLOCK)
            WidgetKind.CONVERSION -> ValueCard("Conversión", widget.value.orEmpty(), DinaIcon.REFRESH, detail = widget.detail)
        }
    }
}

@Composable
private fun <T> FocusedOrList(
    widget: ContextualWidget,
    items: List<T>,
    id: (T) -> String,
    icon: DinaIcon,
    plural: String,
    card: @Composable (T) -> Unit,
) {
    if (widget.showAll) {
        if (items.isEmpty()) EmptyState(icon, "No hay $plural", "Puedes pedírselo a Dina cuando quieras.")
        else Column(verticalArrangement = Arrangement.spacedBy(Dina.spacing.s)) {
            items.take(2).forEach { card(it) }
            if (items.size > 2) Caption("+ ${items.size - 2} más en Mis cosas", Modifier.padding(start = 6.dp))
        }
    } else {
        val item = widget.focusId?.let { focus -> items.firstOrNull { id(it) == focus } } ?: items.lastOrNull()
        if (item == null) EmptyState(icon, "Ya no está", "Ese elemento se ha terminado o se ha borrado.") else card(item)
    }
}

/** Ring with an icon in the middle; wobbles while ringing. */
@Composable
private fun Dial(progress: () -> Float, icon: DinaIcon, ringing: Boolean, compact: Boolean) {
    val c = Dina.colors
    val wobble = ringingWobble(ringing)
    Box(Modifier.size(if (compact) 74.dp else 90.dp), contentAlignment = Alignment.Center) {
        SketchRing(
            progress,
            color = if (ringing) c.warm else c.accent,
            track = if (ringing) c.warm.copy(alpha = 0.3f) else c.surfaceSunken,
            modifier = Modifier.matchParentSize().graphicsLayer {
                val pulse = 1f + 0.04f * wobble()
                scaleX = pulse
                scaleY = pulse
            },
            thickness = if (compact) 7.dp else 9.dp,
        )
        SketchIcon(
            icon,
            null,
            Modifier.graphicsLayer { rotationZ = 14f * wobble() },
            tint = if (ringing) c.onWarm else c.accent,
            size = if (compact) 26.dp else 30.dp,
        )
    }
}

@Composable
fun TimerCard(timer: Timer, compact: Boolean = false) {
    val execute = rememberRunner()
    val now = rememberNow()
    val c = Dina.colors
    val remaining = timer.remainingAt(now)
    val ringing = timer.status == TimerStatus.RINGING
    val shown = ceil(remaining / 1000.0).toLong() * 1000
    DinaCard(tone = if (ringing) CardTone.WARM else CardTone.PLAIN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dial({ if (ringing) 1f else remaining.toFloat() / timer.durationMs.coerceAtLeast(1L) }, if (ringing) DinaIcon.BELL else DinaIcon.HOURGLASS, ringing, compact)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Overline("Temporizador" + (timer.label?.let { " · $it" } ?: ""), color = if (ringing) c.onWarm else c.accent)
                Text(
                    if (ringing) "¡Tiempo!" else clock(shown),
                    Modifier.clearAndSetSemantics {
                        contentDescription = if (ringing) "El temporizador está sonando" else "Quedan ${spoken(shown)}"
                    },
                    style = if (compact || shown >= 3_600_000L) Dina.type.numberSmall else Dina.type.number,
                    color = if (ringing) c.onWarm else c.ink,
                    maxLines = 1,
                )
                Caption(
                    when (timer.status) {
                        TimerStatus.RUNNING -> "Suena a las ${hourMinute(timer.deadlineMs)}"
                        TimerStatus.PAUSED -> "En pausa"
                        TimerStatus.RINGING -> "Ha terminado"
                    },
                    color = if (ringing) c.onWarm else c.inkMuted,
                )
            }
        }
        if (ringing) {
            ActionRow(Action("Detener", { execute(TimerCommand.Dismiss(byId(timer.id))) }, DinaIcon.CHECK, ButtonStyle.WARM), compact = compact)
        } else {
            val paused = timer.status == TimerStatus.PAUSED
            ActionRow(
                Action(
                    if (paused) "Reanudar" else "Pausar",
                    { execute(if (paused) TimerCommand.Resume(byId(timer.id)) else TimerCommand.Pause(byId(timer.id))) },
                    if (paused) DinaIcon.PLAY else DinaIcon.PAUSE,
                    ButtonStyle.PRIMARY,
                ),
                Action("Cancelar", { execute(TimerCommand.Cancel(byId(timer.id))) }, DinaIcon.CLOSE),
                compact = compact,
            )
        }
    }
}

private fun dayLabel(next: java.time.ZonedDateTime): String {
    val today = LocalDate.now(next.zone)
    val date = next.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", spanish))
    return when (next.toLocalDate()) {
        today -> "Hoy, $date"
        today.plusDays(1) -> "Mañana, $date"
        else -> date.replaceFirstChar { it.uppercase(spanish) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmCard(alarm: Alarm, compact: Boolean = false) {
    val execute = rememberRunner()
    val c = Dina.colors
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var pickRepeat by rememberSaveable { mutableStateOf(false) }
    val next = Instant.ofEpochMilli(alarm.nextMs).atZone(ZoneId.systemDefault())
    val enabled = alarm.status != AlarmStatus.DISABLED
    val ringing = alarm.status == AlarmStatus.RINGING
    DinaCard(tone = if (ringing) CardTone.WARM else CardTone.PLAIN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ringing) {
                Dial({ 1f }, DinaIcon.BELL, ringing = true, compact = true)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Overline(
                    "Alarma" + (alarm.label?.let { " · $it" } ?: "") + if (alarm.status == AlarmStatus.SNOOZED) " · pospuesta" else "",
                    color = if (ringing) c.onWarm else c.accent,
                )
                Text(
                    next.format(DateTimeFormatter.ofPattern("H:mm")),
                    style = if (compact) Dina.type.numberSmall else Dina.type.number,
                    color = when { ringing -> c.onWarm; enabled -> c.ink; else -> c.inkMuted },
                )
                Caption(dayLabel(next), color = if (ringing) c.onWarm else c.inkMuted)
                if (alarm.repeat != null) Chip(ToolPresentation.repeatLabel(alarm.repeat), Modifier.padding(top = 6.dp), DinaIcon.REFRESH)
            }
            if (!ringing) {
                DinaSwitch(
                    checked = enabled,
                    onCheckedChange = { checked -> execute(if (checked) AlarmCommand.Enable(byId(alarm.id)) else AlarmCommand.Disable(byId(alarm.id))) },
                    modifier = Modifier.semantics { contentDescription = "Alarma activada" },
                )
            }
        }
        if (ringing) {
            ActionRow(
                Action("Posponer", { execute(AlarmCommand.Snooze(byId(alarm.id))) }, DinaIcon.SNOOZE),
                Action("Detener", { execute(AlarmCommand.Dismiss(byId(alarm.id))) }, DinaIcon.CHECK, ButtonStyle.WARM),
                compact = compact,
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(top = Dina.spacing.m), horizontalArrangement = Arrangement.spacedBy(Dina.spacing.s), verticalAlignment = Alignment.CenterVertically) {
                DinaButton("Hora", { pickTime = true }, Modifier.weight(1f), icon = DinaIcon.CLOCK.takeUnless { compact })
                DinaButton("Repetir", { pickRepeat = true }, Modifier.weight(1f), icon = DinaIcon.REFRESH.takeUnless { compact })
                RoundIconButton(DinaIcon.TRASH, "Eliminar alarma", { execute(AlarmCommand.Cancel(byId(alarm.id))) }, style = ButtonStyle.DANGER)
            }
        }
    }
    if (pickTime) {
        val state = rememberTimePickerState(next.hour, next.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Hora de la alarma", style = Dina.type.heading, color = c.ink) },
            text = {
                TimePicker(
                    state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.surfaceAlt,
                        selectorColor = c.accent,
                        timeSelectorSelectedContainerColor = c.accentSoft,
                        timeSelectorUnselectedContainerColor = c.surfaceAlt,
                        timeSelectorSelectedContentColor = c.onAccentSoft,
                        timeSelectorUnselectedContentColor = c.ink,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    execute(AlarmCommand.Update(byId(alarm.id), at = WhenSpec.At(LocalTime.of(state.hour, state.minute))))
                    pickTime = false
                }) { Text("Guardar", style = Dina.type.label, color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancelar", style = Dina.type.label, color = c.inkMuted) } },
            containerColor = c.dinaBubble,
            shape = Dina.shapes.card,
        )
    }
    if (pickRepeat) {
        val options = listOf("Nunca" to null, "Cada día" to Repeat.Daily, "Entre semana" to Repeat.Weekdays, "Fines de semana" to Repeat.Weekends)
        AlertDialog(
            onDismissRequest = { pickRepeat = false },
            title = { Text("Repetición", style = Dina.type.heading, color = c.ink) },
            text = {
                Column {
                    options.forEach { (label, repeat) ->
                        val selected = alarm.repeat == repeat
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Dina.spacing.touch).clickable(role = Role.RadioButton) {
                                execute(AlarmCommand.Update(byId(alarm.id), repeat = Patch(repeat)))
                                pickRepeat = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, Modifier.weight(1f), style = if (selected) Dina.type.bodyStrong else Dina.type.body, color = c.ink)
                            if (selected) SketchIcon(DinaIcon.CHECK, "Seleccionado", tint = c.accent)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickRepeat = false }) { Text("Cerrar", style = Dina.type.label, color = c.accent) } },
            containerColor = c.dinaBubble,
            shape = Dina.shapes.card,
        )
    }
}

@Composable
fun StopwatchCard(stopwatch: Stopwatch, compact: Boolean = false) {
    val execute = rememberRunner()
    val now = rememberNow(100L)
    val c = Dina.colors
    val elapsed = stopwatch.elapsedAt(now)
    val paused = stopwatch.status == StopwatchStatus.PAUSED
    DinaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dial({ (elapsed % 60_000L) / 60_000f }, DinaIcon.STOPWATCH, ringing = false, compact = compact)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Overline("Cronómetro" + (stopwatch.label?.let { " · $it" } ?: ""))
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.clearAndSetSemantics { contentDescription = "Llevas ${spoken(elapsed)}" }) {
                    Text(clock(elapsed), style = if (compact || elapsed >= 3_600_000L) Dina.type.numberSmall else Dina.type.number, color = c.ink, maxLines = 1)
                    Text(",${elapsed % 1000 / 100}", Modifier.padding(bottom = 6.dp), style = Dina.type.heading, color = c.inkMuted)
                }
                Caption(if (paused) "En pausa" else "En marcha")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Dina.spacing.m), horizontalArrangement = Arrangement.spacedBy(Dina.spacing.s), verticalAlignment = Alignment.CenterVertically) {
            DinaButton(
                if (paused) "Continuar" else "Pausar",
                { execute(if (paused) StopwatchCommand.Resume(byId(stopwatch.id)) else StopwatchCommand.Pause(byId(stopwatch.id))) },
                Modifier.weight(1f),
                icon = (if (paused) DinaIcon.PLAY else DinaIcon.PAUSE).takeUnless { compact },
                style = ButtonStyle.PRIMARY,
            )
            DinaButton("Reiniciar", { execute(StopwatchCommand.Reset(byId(stopwatch.id))) }, Modifier.weight(1f), icon = DinaIcon.REFRESH.takeUnless { compact })
            RoundIconButton(DinaIcon.TRASH, "Borrar cronómetro", { execute(StopwatchCommand.Delete(byId(stopwatch.id))) }, style = ButtonStyle.DANGER)
        }
    }
}

@Composable
fun ShoppingCard(items: List<ShoppingItem>, compact: Boolean = false) {
    val execute = rememberRunner()
    val c = Dina.colors
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    DinaCard(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SketchIcon(DinaIcon.CART, null, tint = c.accent, size = 20.dp)
            Overline("Lista de la compra", Modifier.padding(start = 8.dp).weight(1f))
            if (items.isNotEmpty()) Caption("${items.count { it.completed }} de ${items.size}")
        }
        if (items.isEmpty()) {
            Caption("La lista está vacía. Prueba a decir «añade leche».", Modifier.padding(top = 10.dp))
        } else {
            val visible = if (compact) items.take(4) else items
            Column(Modifier.padding(top = 4.dp)) {
                visible.forEach { item ->
                    Row(Modifier.fillMaxWidth().heightIn(min = Dina.spacing.touch), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(Dina.spacing.touch).toggleable(item.completed, role = Role.Checkbox) { checked ->
                                execute(if (checked) ShoppingCommand.Mark(byId(item.id)) else ShoppingCommand.Unmark(byId(item.id)))
                            }.semantics { contentDescription = shoppingLabel(item) },
                            contentAlignment = Alignment.Center,
                        ) { SketchCheck(item.completed, color = c.accent, outline = c.inkMuted, mark = c.onAccent) }
                        Text(
                            shoppingLabel(item),
                            Modifier.weight(1f).heightIn(min = Dina.spacing.touch).clickable(onClickLabel = "Editar") { editing = item }.padding(vertical = 12.dp),
                            style = Dina.type.body,
                            color = if (item.completed) c.inkMuted else c.ink,
                            textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!compact) RoundIconButton(DinaIcon.CLOSE, "Quitar ${item.name}", { execute(ShoppingCommand.Remove(byId(item.id))) }, style = ButtonStyle.GHOST, size = 44.dp)
                    }
                }
            }
            if (compact && items.size > visible.size) Caption("+ ${items.size - visible.size} más en Mis cosas")
        }
    }
    editing?.let { item ->
        ShoppingEditDialog(item, onDismiss = { editing = null }) { name, quantity ->
            execute(ShoppingCommand.Update(byId(item.id), name = Patch(name), quantity = Patch(quantity)))
        }
    }
}

@Composable
private fun ShoppingEditDialog(item: ShoppingItem, onDismiss: () -> Unit, onSave: (String, Double?) -> Unit) {
    val c = Dina.colors
    var name by rememberSaveable { mutableStateOf(item.name) }
    var quantity by rememberSaveable { mutableStateOf(item.quantity?.let { ToolPresentation.number(it) }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar elemento", style = Dina.type.heading, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dina.spacing.s)) {
                DinaTextField(
                    name, { name = it }, "Elemento",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                )
                DinaTextField(
                    quantity, { quantity = it }, "Cantidad (opcional)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), ToolWorldJson.quantity(quantity)); onDismiss() }, enabled = name.isNotBlank()) {
                Text("Guardar", style = Dina.type.label, color = c.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", style = Dina.type.label, color = c.inkMuted) } },
        containerColor = c.dinaBubble,
        shape = Dina.shapes.card,
    )
}

@Composable
fun ShoppingComposer() {
    val execute = rememberRunner()
    var text by rememberSaveable { mutableStateOf("") }
    val add = {
        val value = text.trim()
        if (value.isNotEmpty()) { execute(ShoppingCommand.Add(value)); text = "" }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dina.spacing.s)) {
        DinaTextField(
            text, { text = it }, "Añadir a la lista", Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
        )
        RoundIconButton(DinaIcon.PLUS, "Añadir", add, style = ButtonStyle.PRIMARY, enabled = text.isNotBlank(), size = 52.dp)
    }
}

@Composable
fun VolumeCard() {
    val tools = LocalContext.current.dina.tools
    var percent by remember { mutableFloatStateOf(tools.currentVolume().percent.toFloat()) }
    DinaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SketchIcon(DinaIcon.SPEAKER, null, tint = Dina.colors.accent, size = 20.dp)
            Overline("Volumen multimedia", Modifier.padding(start = 8.dp).weight(1f))
            Text("${percent.toInt()} %", style = Dina.type.heading, color = Dina.colors.ink)
        }
        DinaSlider(
            percent,
            { percent = it },
            0f..100f,
            Modifier.semantics { contentDescription = "Volumen multimedia" },
            onValueChangeFinished = { tools.execute(VolumeCommand.Set(percent.toInt())) },
        )
    }
}

@Composable
fun ValueCard(label: String, value: String, icon: DinaIcon = DinaIcon.SPARK, detail: String? = null) {
    DinaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SketchIcon(icon, null, tint = Dina.colors.accent, size = 20.dp)
            Overline(label, Modifier.padding(start = 8.dp))
        }
        detail?.let { Text("$it =", Modifier.padding(top = 6.dp), style = Dina.type.body, color = Dina.colors.inkMuted) }
        Text(value, Modifier.padding(top = if (detail == null) 6.dp else 0.dp), style = Dina.type.display, color = Dina.colors.ink)
    }
}

private fun shoppingLabel(item: ShoppingItem): String {
    val quantity = item.quantity?.let { "${ToolPresentation.number(it)} " }.orEmpty()
    val unit = item.unit?.let { "$it " }.orEmpty()
    return "$quantity$unit${item.name}".trim()
}
