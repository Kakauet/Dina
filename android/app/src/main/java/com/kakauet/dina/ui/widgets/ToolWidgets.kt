package com.kakauet.dina.ui.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kakauet.dina.core.VoiceState
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
import com.kakauet.dina.ui.character.Sketcher
import com.kakauet.dina.ui.components.Action
import com.kakauet.dina.ui.components.ActionRow
import com.kakauet.dina.ui.components.AnimatedColumn
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
import com.kakauet.dina.ui.components.FitText
import com.kakauet.dina.ui.components.Overline
import com.kakauet.dina.ui.components.RoundIconButton
import com.kakauet.dina.ui.components.SketchCheck
import com.kakauet.dina.ui.components.SketchIcon
import com.kakauet.dina.ui.components.SketchRing
import com.kakauet.dina.ui.components.enter
import com.kakauet.dina.ui.components.exit
import com.kakauet.dina.ui.components.pressFeedback
import com.kakauet.dina.ui.components.riseBetween
import com.kakauet.dina.ui.components.spring
import com.kakauet.dina.ui.components.tween
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

/**
 * Wall clock that ticks every [intervalMs] while [running] and the widget is on screen. It is a
 * state: read it only where the time is shown (a small readout, or a ring in the draw phase), so
 * the ticks do not recompose the whole card.
 */
@Composable
fun rememberClock(running: Boolean, intervalMs: Long = 250L): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running, intervalMs) {
        now.longValue = System.currentTimeMillis()
        while (running) {
            delay(intervalMs)
            now.longValue = System.currentTimeMillis()
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
    val motion = Dina.motion
    if (!active || motion.reduced) return { 0f }
    val transition = rememberInfiniteTransition(label = "ringing")
    val wobble = transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(motion.ring), RepeatMode.Reverse), label = "wobble")
    return { wobble.value }
}

/** The widget shown after a turn, rendered from live state rather than the turn's snapshot. */
@Composable
fun ContextualWidgetView(widget: ContextualWidget, world: ToolWorld, modifier: Modifier = Modifier) {
    Column(modifier) {
        when (widget.kind) {
            WidgetKind.TIMER -> FocusedOrList(widget, world.timers, Timer::id, "temporizadores") { TimerCard(it, compact = true) }
            WidgetKind.ALARM -> FocusedOrList(widget, world.alarms, Alarm::id, "alarmas") { AlarmCard(it, compact = true) }
            WidgetKind.STOPWATCH -> FocusedOrList(widget, world.stopwatches, Stopwatch::id, "cronómetros") { StopwatchCard(it, compact = true) }
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
    plural: String,
    card: @Composable (T) -> Unit,
) {
    if (widget.showAll) {
        if (items.isEmpty()) EmptyState("No hay $plural", "Cuando quieras, pídemelo.", mood = VoiceState.FOLLOW_UP)
        else Column(verticalArrangement = Arrangement.spacedBy(Dina.spacing.s)) {
            items.take(2).forEach { card(it) }
            if (items.size > 2) Caption("+ ${items.size - 2} más en Mis cosas", Modifier.padding(start = Dina.spacing.cardX))
        }
    } else {
        val item = widget.focusId?.let { focus -> items.firstOrNull { id(it) == focus } } ?: items.lastOrNull()
        if (item == null) EmptyState("Ya no está", "Ha terminado o se ha borrado.", mood = VoiceState.THINKING) else card(item)
    }
}

/** How a dial looks: running (accent), paused (muted, still) or ringing (warm, wobbling). */
private enum class DialMood { RUNNING, PAUSED, RINGING }

/** Ring with an icon in the middle; [progress] is read while drawing. */
@Composable
private fun Dial(progress: () -> Float, icon: DinaIcon, mood: DialMood, compact: Boolean) {
    val c = Dina.colors
    val wobble = ringingWobble(mood == DialMood.RINGING)
    val (ring, track, ink) = when (mood) {
        DialMood.RUNNING -> Triple(c.accent, c.surfaceSunken, c.accent)
        DialMood.PAUSED -> Triple(c.lineStrong, c.surfaceSunken, c.inkMuted)
        DialMood.RINGING -> Triple(c.warm, c.warm.copy(alpha = 0.3f), c.onWarm)
    }
    Box(Modifier.size(if (compact) Dina.sizes.dialCompact else Dina.sizes.dial), contentAlignment = Alignment.Center) {
        SketchRing(
            progress,
            color = ring,
            track = track,
            modifier = Modifier.matchParentSize().graphicsLayer {
                val pulse = 1f + 0.04f * wobble()
                scaleX = pulse
                scaleY = pulse
            },
            thickness = if (compact) Dina.sizes.dialStrokeCompact else Dina.sizes.dialStroke,
        )
        SketchIcon(
            icon,
            null,
            Modifier.graphicsLayer { rotationZ = 14f * wobble() },
            tint = ink,
            size = if (compact) Dina.sizes.dialIconCompact else Dina.sizes.dialIcon,
        )
    }
}

/** A big number that shrinks to fit narrow cards and large fonts instead of being cut. */
@Composable
private fun BigNumber(text: String, small: Boolean, color: Color, modifier: Modifier = Modifier) {
    val style = if (small) Dina.type.numberSmall else Dina.type.number
    val tint by animateColorAsState(color, Dina.motion.tween(Dina.motion.standard), label = "number")
    FitText(text, style, { tint }, modifier, minSize = Dina.type.numberMin)
}

@Composable
fun TimerCard(timer: Timer, compact: Boolean = false) {
    val execute = rememberRunner()
    val c = Dina.colors
    val motion = Dina.motion
    val ringing = timer.status == TimerStatus.RINGING
    val paused = timer.status == TimerStatus.PAUSED
    val now = rememberClock(running = timer.status == TimerStatus.RUNNING)
    DinaCard(tone = if (ringing) CardTone.WARM else CardTone.PLAIN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dial(
                { if (ringing) 1f else timer.remainingAt(now.value).toFloat() / timer.durationMs.coerceAtLeast(1L) },
                when { ringing -> DinaIcon.BELL; paused -> DinaIcon.PAUSE; else -> DinaIcon.HOURGLASS },
                when { ringing -> DialMood.RINGING; paused -> DialMood.PAUSED; else -> DialMood.RUNNING },
                compact,
            )
            Column(Modifier.padding(start = Dina.spacing.l - Dina.spacing.xxs).weight(1f)) {
                Overline("Temporizador" + (timer.label?.let { " · $it" } ?: ""), color = if (ringing) c.onWarm else c.accent)
                TimerReadout(timer, now, compact)
                if (paused) Chip("En pausa", Modifier.padding(top = Dina.spacing.xxs), DinaIcon.PAUSE, tone = CardTone.PLAIN)
                else Caption(if (ringing) "Ha terminado" else "Suena a las ${hourMinute(timer.deadlineMs)}", color = if (ringing) c.onWarm else c.inkMuted)
            }
        }
        AnimatedContent(ringing, transitionSpec = { riseBetween(motion) }, label = "timerActions") { ringingNow ->
            if (ringingNow) {
                ActionRow(Action("Detener", { execute(TimerCommand.Dismiss(byId(timer.id))) }, DinaIcon.CHECK, ButtonStyle.WARM), compact = compact)
            } else {
                ActionRow(
                    Action(
                        if (paused) "Seguir" else "Pausar",
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
}

/** The countdown itself: the only part of the card that changes every tick. */
@Composable
private fun TimerReadout(timer: Timer, now: State<Long>, compact: Boolean) {
    val c = Dina.colors
    val ringing = timer.status == TimerStatus.RINGING
    val shown = ceil(timer.remainingAt(now.value) / 1000.0).toLong() * 1000
    BigNumber(
        if (ringing) "¡Tiempo!" else clock(shown),
        small = compact || shown >= 3_600_000L,
        color = when { ringing -> c.onWarm; timer.status == TimerStatus.PAUSED -> c.inkMuted; else -> c.ink },
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = when {
                ringing -> "El temporizador está sonando"
                timer.status == TimerStatus.PAUSED -> "En pausa, quedan ${spoken(shown)}"
                else -> "Quedan ${spoken(shown)}"
            }
        },
    )
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
    val motion = Dina.motion
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var pickRepeat by rememberSaveable { mutableStateOf(false) }
    val next = Instant.ofEpochMilli(alarm.nextMs).atZone(ZoneId.systemDefault())
    val time = next.format(DateTimeFormatter.ofPattern("H:mm"))
    val enabled = alarm.status != AlarmStatus.DISABLED
    val ringing = alarm.status == AlarmStatus.RINGING
    DinaCard(tone = if (ringing) CardTone.WARM else CardTone.PLAIN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(ringing, enter = motion.enter(), exit = motion.exit()) {
                Row {
                    Dial({ 1f }, DinaIcon.BELL, DialMood.RINGING, compact = true)
                    Spacer(Modifier.width(Dina.spacing.l - Dina.spacing.xxs))
                }
            }
            Column(Modifier.weight(1f)) {
                Overline(
                    "Alarma" + (alarm.label?.let { " · $it" } ?: "") + if (alarm.status == AlarmStatus.SNOOZED) " · pospuesta" else "",
                    color = if (ringing) c.onWarm else c.accent,
                )
                BigNumber(time, small = compact, color = when { ringing -> c.onWarm; enabled -> c.ink; else -> c.inkMuted })
                Caption(dayLabel(next), color = if (ringing) c.onWarm else c.inkMuted)
                if (alarm.repeat != null) Chip(ToolPresentation.repeatLabel(alarm.repeat), Modifier.padding(top = Dina.spacing.s - Dina.spacing.xxs), DinaIcon.REFRESH)
            }
            if (!ringing) {
                DinaSwitch(
                    checked = enabled,
                    onCheckedChange = { checked -> execute(if (checked) AlarmCommand.Enable(byId(alarm.id)) else AlarmCommand.Disable(byId(alarm.id))) },
                    modifier = Modifier.semantics { contentDescription = "Alarma de las $time" },
                )
            }
        }
        AnimatedContent(ringing, transitionSpec = { riseBetween(motion) }, label = "alarmActions") { ringingNow ->
            if (ringingNow) {
                ActionRow(
                    Action("Posponer", { execute(AlarmCommand.Snooze(byId(alarm.id))) }, DinaIcon.SNOOZE),
                    Action("Detener", { execute(AlarmCommand.Dismiss(byId(alarm.id))) }, DinaIcon.CHECK, ButtonStyle.WARM),
                    compact = compact,
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(top = Dina.spacing.m), horizontalArrangement = Arrangement.spacedBy(Dina.spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    DinaButton("Hora", { pickTime = true }, Modifier.weight(1f), icon = DinaIcon.CLOCK.takeUnless { compact })
                    DinaButton("Repetir", { pickRepeat = true }, Modifier.weight(1f), icon = DinaIcon.REFRESH.takeUnless { compact })
                    RoundIconButton(DinaIcon.TRASH, "Eliminar la alarma de las $time", { execute(AlarmCommand.Cancel(byId(alarm.id))) }, style = ButtonStyle.DANGER)
                }
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
    val paused = stopwatch.status == StopwatchStatus.PAUSED
    val now = rememberClock(running = !paused, intervalMs = 100L)
    DinaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dial({ (stopwatch.elapsedAt(now.value) % 60_000L) / 60_000f }, if (paused) DinaIcon.PAUSE else DinaIcon.STOPWATCH, if (paused) DialMood.PAUSED else DialMood.RUNNING, compact)
            Column(Modifier.padding(start = Dina.spacing.l - Dina.spacing.xxs).weight(1f)) {
                Overline("Cronómetro" + (stopwatch.label?.let { " · $it" } ?: ""))
                StopwatchReadout(stopwatch, now, compact)
                if (paused) Chip("En pausa", Modifier.padding(top = Dina.spacing.xxs), DinaIcon.PAUSE, tone = CardTone.PLAIN)
                else Caption("En marcha")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Dina.spacing.m), horizontalArrangement = Arrangement.spacedBy(Dina.spacing.s), verticalAlignment = Alignment.CenterVertically) {
            DinaButton(
                if (paused) "Seguir" else "Pausar",
                { execute(if (paused) StopwatchCommand.Resume(byId(stopwatch.id)) else StopwatchCommand.Pause(byId(stopwatch.id))) },
                Modifier.weight(1f),
                icon = (if (paused) DinaIcon.PLAY else DinaIcon.PAUSE).takeUnless { compact },
                style = ButtonStyle.PRIMARY,
            )
            DinaButton("Reiniciar", { execute(StopwatchCommand.Reset(byId(stopwatch.id))) }, Modifier.weight(1f), icon = DinaIcon.REFRESH.takeUnless { compact })
            RoundIconButton(DinaIcon.TRASH, "Borrar el cronómetro", { execute(StopwatchCommand.Delete(byId(stopwatch.id))) }, style = ButtonStyle.DANGER)
        }
    }
}

/** Minutes, seconds and tenths: recomposes ten times a second, alone. */
@Composable
private fun StopwatchReadout(stopwatch: Stopwatch, now: State<Long>, compact: Boolean) {
    val c = Dina.colors
    val elapsed = stopwatch.elapsedAt(now.value)
    val paused = stopwatch.status == StopwatchStatus.PAUSED
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.clearAndSetSemantics { contentDescription = "Llevas ${spoken(elapsed)}" }) {
        BigNumber(clock(elapsed), small = compact || elapsed >= 3_600_000L, color = if (paused) c.inkMuted else c.ink, modifier = Modifier.weight(1f, fill = false))
        Text(",${elapsed % 1000 / 100}", Modifier.padding(bottom = Dina.spacing.s - Dina.spacing.xxs), style = Dina.type.heading, color = c.inkMuted)
    }
}

@Composable
fun ShoppingCard(items: List<ShoppingItem>, compact: Boolean = false) {
    val execute = rememberRunner()
    val c = Dina.colors
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    DinaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SketchIcon(DinaIcon.CART, null, tint = c.accent, size = Dina.sizes.icon)
            Overline("Lista de la compra", Modifier.padding(start = Dina.spacing.iconGap).weight(1f))
            if (items.isNotEmpty()) Caption("${items.count { it.completed }} de ${items.size}")
        }
        AnimatedVisibility(items.isEmpty(), enter = Dina.motion.enter(), exit = Dina.motion.exit()) {
            Caption("Está vacía. Di «añade leche».", Modifier.padding(top = Dina.spacing.s + Dina.spacing.xxs))
        }
        val visible = if (compact) items.take(4) else items
        AnimatedColumn(visible, key = { it.id }, Modifier.padding(top = Dina.spacing.xs)) { item ->
            ShoppingRow(item, compact, onToggle = { checked ->
                execute(if (checked) ShoppingCommand.Mark(byId(item.id)) else ShoppingCommand.Unmark(byId(item.id)))
            }, onEdit = { editing = item }, onRemove = { execute(ShoppingCommand.Remove(byId(item.id))) })
        }
        if (compact && items.size > visible.size) Caption("+ ${items.size - visible.size} más en Mis cosas")
    }
    editing?.let { item ->
        ShoppingEditDialog(item, onDismiss = { editing = null }) { name, quantity ->
            execute(ShoppingCommand.Update(byId(item.id), name = Patch(name), quantity = Patch(quantity)))
        }
    }
}

/** One thing to buy: tick it off (a hand-drawn line crosses it out), tap it to edit, or remove it. */
@Composable
private fun ShoppingRow(item: ShoppingItem, compact: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val c = Dina.colors
    val motion = Dina.motion
    val label = shoppingLabel(item)
    val struck by animateFloatAsState(if (item.completed) 1f else 0f, motion.spring(motion.smooth), label = "strike")
    val ink by animateColorAsState(if (item.completed) c.inkMuted else c.ink, motion.tween(motion.standard), label = "itemInk")
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val press = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = Dina.spacing.touch), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(Dina.spacing.touch).toggleable(item.completed, role = Role.Checkbox, onValueChange = onToggle)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { SketchCheck(item.completed, color = c.accent, outline = c.lineStrong, mark = c.onAccent) }
        Text(
            label,
            Modifier
                .weight(1f)
                .heightIn(min = Dina.spacing.touch)
                .pressFeedback(press)
                .clickable(press, indication = null, onClickLabel = "Editar", onClick = onEdit)
                .padding(vertical = Dina.spacing.m)
                .drawWithCache {
                    // Crossed out by hand, drawn in as the box is ticked.
                    val sk = Sketcher()
                    val first = layout
                    val y = if (first != null && first.lineCount > 0) (first.getLineTop(0) + first.getLineBottom(0)) / 2f else size.height / 2f
                    val right = if (first != null && first.lineCount > 0) first.getLineRight(0) else size.width
                    val line = Path()
                    sk.begin().point(-2f * density, y + density).point(right * 0.5f, y - density * 0.6f).point(right + 2f * density, y + density * 0.4f)
                        .jitter(0.6f * density, item.id.hashCode()).curve(false, 4)
                    sk.brush(line, 1.8f * density, item.id.hashCode(), variation = 0.3f, taper = 0.5f, weight = 0f)
                    onDrawWithContent {
                        drawContent()
                        val p = struck.coerceIn(0f, 1f)
                        if (p > 0.01f) clipRect(right = (right + 4f * density) * p) { drawPath(line, c.inkMuted) }
                    }
                },
            style = Dina.type.body,
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
        )
        if (!compact) RoundIconButton(DinaIcon.CLOSE, "Quitar ${item.name}", onRemove, style = ButtonStyle.GHOST)
    }
}

@Composable
private fun ShoppingEditDialog(item: ShoppingItem, onDismiss: () -> Unit, onSave: (String, Double?) -> Unit) {
    val c = Dina.colors
    var name by rememberSaveable { mutableStateOf(item.name) }
    var quantity by rememberSaveable { mutableStateOf(item.quantity?.let { ToolPresentation.number(it) }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar", style = Dina.type.heading, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dina.spacing.s)) {
                DinaTextField(
                    name, { name = it }, "Qué hay que comprar",
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
        RoundIconButton(DinaIcon.PLUS, "Añadir", add, style = ButtonStyle.PRIMARY, enabled = text.isNotBlank(), size = Dina.sizes.field)
    }
}

@Composable
fun VolumeCard() {
    val tools = LocalContext.current.dina.tools
    var percent by remember { mutableFloatStateOf(tools.currentVolume().percent.toFloat()) }
    DinaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SketchIcon(DinaIcon.SPEAKER, null, tint = Dina.colors.accent, size = Dina.sizes.icon)
            Overline("Volumen multimedia", Modifier.padding(start = Dina.spacing.iconGap).weight(1f))
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
            SketchIcon(icon, null, tint = Dina.colors.accent, size = Dina.sizes.icon)
            Overline(label, Modifier.padding(start = Dina.spacing.iconGap))
        }
        detail?.let { Text("$it =", Modifier.padding(top = Dina.spacing.s - Dina.spacing.xxs), style = Dina.type.body, color = Dina.colors.inkMuted) }
        Text(value, Modifier.padding(top = if (detail == null) Dina.spacing.s - Dina.spacing.xxs else 0.dp), style = Dina.type.display, color = Dina.colors.ink)
    }
}

private fun shoppingLabel(item: ShoppingItem): String {
    val quantity = item.quantity?.let { "${ToolPresentation.number(it)} " }.orEmpty()
    val unit = item.unit?.let { "$it " }.orEmpty()
    return "$quantity$unit${item.name}".trim()
}
