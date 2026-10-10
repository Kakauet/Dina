package com.kakauet.dina.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakauet.dina.core.DinaMessage
import com.kakauet.dina.core.MessageRole
import com.kakauet.dina.core.SessionState
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.tools.ToolWorld
import com.kakauet.dina.tools.WidgetKind
import com.kakauet.dina.ui.character.DinaCharacter
import com.kakauet.dina.ui.components.ButtonStyle
import com.kakauet.dina.ui.components.Caption
import com.kakauet.dina.ui.components.DinaButton
import com.kakauet.dina.ui.components.DinaIcon
import com.kakauet.dina.ui.components.DinaTextField
import com.kakauet.dina.ui.components.Halo
import com.kakauet.dina.ui.components.Overline
import com.kakauet.dina.ui.components.PillSwitch
import com.kakauet.dina.ui.components.RoundIconButton
import com.kakauet.dina.ui.components.SketchIcon
import com.kakauet.dina.ui.components.SpeechBubble
import com.kakauet.dina.ui.components.Tail
import com.kakauet.dina.ui.components.appear
import com.kakauet.dina.ui.components.sketchBackground
import com.kakauet.dina.ui.components.slideBetween
import com.kakauet.dina.ui.theme.Dina
import com.kakauet.dina.ui.widgets.ContextualWidgetView
import com.kakauet.dina.ui.widgets.rememberNow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class HomeMode { VOICE, TEXT }

/** Everything the home screen shows; built in [com.kakauet.dina.ui.DinaApp] from the stores. */
data class HomeUiState(
    val session: SessionState,
    val messages: List<DinaMessage>,
    val world: ToolWorld,
    val showTimestamps: Boolean,
)

@Composable
fun HomeScreen(
    state: HomeUiState,
    audioLevel: State<Float>,
    mode: HomeMode,
    onModeChange: (HomeMode) -> Unit,
    onOpenThings: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: (String) -> Boolean,
    onNewConversation: () -> Unit,
    onRetry: () -> Unit,
) {
    val c = Dina.colors
    val motion = Dina.motion
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to c.backgroundTint, 0.55f to c.background))
            .safeDrawingPadding(),
    ) {
        Header(onOpenThings, onOpenSettings)
        PillSwitch(
            listOf("Voz" to DinaIcon.MIC, "Texto" to DinaIcon.CHAT),
            mode.ordinal,
            { onModeChange(HomeMode.entries[it]) },
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 300.dp).fillMaxWidth().padding(horizontal = Dina.spacing.gutter),
        )
        AnimatedContent(
            mode,
            Modifier.weight(1f),
            transitionSpec = { slideBetween(motion, forward = targetState.ordinal > initialState.ordinal) },
            label = "mode",
        ) { current ->
            when (current) {
                HomeMode.VOICE -> VoiceView(state, audioLevel, onRetry)
                HomeMode.TEXT -> ChatView(state, audioLevel, onSend, onNewConversation)
            }
        }
    }
}

@Composable
private fun Header(onOpenThings: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 6.dp, bottom = 8.dp).heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Dina", Modifier.semantics { heading() }, style = Dina.type.display, color = Dina.colors.ink)
            Overline("Asistente local", color = Dina.colors.inkMuted)
        }
        DinaButton("Mis cosas", onOpenThings, icon = DinaIcon.LIST)
        RoundIconButton(DinaIcon.GEAR, "Ajustes", onOpenSettings, Modifier.padding(start = 6.dp), style = ButtonStyle.GHOST)
    }
}

@Composable
private fun VoiceView(state: HomeUiState, audioLevel: State<Float>, onRetry: () -> Unit) {
    val c = Dina.colors
    val motion = Dina.motion
    val session = state.session
    val now = rememberNow(500L)
    val widget = session.widget?.takeIf { it.autoOpen && (it.expiresAtMs == null || it.expiresAtMs > now) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dina.spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 10.dp).size(250.dp), contentAlignment = Alignment.Center) {
            Halo(c.halo, c.shadow, outline = null, modifier = Modifier.size(236.dp))
            DinaCharacter(session.state, Modifier.size(244.dp), audioLevel = { audioLevel.value })
        }
        AnimatedContent(
            session.state.label,
            transitionSpec = { fadeIn(tween(motion.ms(motion.standard))) togetherWith fadeOut(tween(motion.ms(motion.quick))) },
            label = "state",
        ) { label ->
            Text(label, Modifier.padding(top = 4.dp), style = Dina.type.state, color = c.ink, textAlign = TextAlign.Center)
        }
        if (session.detail.trimEnd('…', '.') != session.state.label.trimEnd('…', '.')) {
            Caption(
                session.detail,
                Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp),
                color = if (session.state == VoiceState.ERROR) c.danger else c.inkMuted,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
        }
        Spacer(Modifier.height(Dina.spacing.l))
        if (session.transcript.isNotBlank()) {
            SpeechBubble(mine = true, tail = Tail.BOTTOM_END, modifier = Modifier.align(Alignment.End).widthIn(max = 340.dp).appear()) {
                Overline("Tú", color = c.inkMuted)
                Text(session.transcript, Modifier.padding(top = 2.dp), style = Dina.type.bodyLarge, color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        if (session.response.isNotBlank()) {
            SpeechBubble(mine = false, tail = Tail.TOP_START, modifier = Modifier.align(Alignment.Start).padding(top = 6.dp).widthIn(max = 360.dp).appear(delayMs = 80)) {
                Overline("Dina")
                Text(session.response, Modifier.padding(top = 2.dp), style = Dina.type.bodyLarge, color = c.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
        if (widget != null) ContextualWidgetView(widget, state.world, Modifier.padding(top = Dina.spacing.l).appear(delayMs = 160))
        if (session.state == VoiceState.ERROR && !session.ready) {
            DinaButton("Reintentar", onRetry, Modifier.padding(top = Dina.spacing.l), icon = DinaIcon.REFRESH, style = ButtonStyle.PRIMARY)
        }
        Spacer(Modifier.height(Dina.spacing.xxl))
    }
}

@Composable
private fun ChatView(state: HomeUiState, audioLevel: State<Float>, onSend: (String) -> Boolean, onNewConversation: () -> Unit) {
    val c = Dina.colors
    val session = state.session
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    val busy = !session.ready || session.state.busy
    val typing = session.state in setOf(VoiceState.TRANSCRIBING, VoiceState.THINKING, VoiceState.EXECUTING) &&
        state.messages.lastOrNull()?.role == MessageRole.USER
    val openedAt = remember { System.currentTimeMillis() }
    val rows = state.messages.size + if (typing) 1 else 0
    LaunchedEffect(rows) {
        if (rows > 0) listState.animateScrollToItem(rows - 1)
    }
    val submit = { if (draft.isNotBlank() && onSend(draft.trim())) draft = "" }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = Dina.spacing.l)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption(
                when {
                    !session.ready -> session.detail
                    session.state == VoiceState.THINKING || session.state == VoiceState.EXECUTING -> "Dina está pensando…"
                    else -> "Conversación"
                },
                Modifier.weight(1f).padding(start = 4.dp),
                color = c.accent,
                maxLines = 2,
            )
            if (state.messages.isNotEmpty()) DinaButton("Nueva", onNewConversation, icon = DinaIcon.REFRESH, style = ButtonStyle.GHOST)
        }
        if (state.messages.isEmpty()) {
            EmptyChat(session, Modifier.weight(1f)) { suggestion -> onSend(suggestion) }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val lastDina = state.messages.lastOrNull { it.role == MessageRole.DINA }?.id
                items(state.messages, key = { it.id }) { message ->
                    val live = message.id == lastDina && !typing && message === state.messages.last()
                    val entrance = if (message.timestampMs >= openedAt) Modifier.appear() else Modifier
                    MessageItem(message, state, live, audioLevel, Modifier.animateItem().then(entrance))
                }
                if (typing) item(key = "typing") { TypingRow(session.state, Modifier.animateItem().appear()) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = Dina.spacing.m), verticalAlignment = Alignment.Bottom) {
            DinaTextField(
                draft, { draft = it }, "Escribe a Dina…", Modifier.weight(1f),
                singleLine = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                label = "Mensaje para Dina",
            )
            RoundIconButton(DinaIcon.SEND, "Enviar", submit, Modifier.padding(start = Dina.spacing.s), style = ButtonStyle.PRIMARY, enabled = !busy && draft.isNotBlank(), size = 52.dp)
        }
    }
}

private val suggestions = listOf("Pon un temporizador de 10 minutos", "Añade pan a la lista", "¿Qué hora es?")

@Composable
private fun EmptyChat(session: SessionState, modifier: Modifier, onSuggestion: (String) -> Unit) {
    val c = Dina.colors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            Halo(c.halo, c.shadow, outline = null, modifier = Modifier.size(156.dp))
            DinaCharacter(if (session.ready) VoiceState.FOLLOW_UP else session.state, Modifier.size(164.dp), describe = false, greeting = session.ready)
        }
        Text("Escríbele a Dina", Modifier.padding(top = 10.dp), style = Dina.type.heading, color = c.ink, textAlign = TextAlign.Center)
        Caption(
            "Puede hacer lo mismo que por voz: alarmas, temporizadores, la compra y más.",
            Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
            textAlign = TextAlign.Center,
        )
        Column(Modifier.padding(top = Dina.spacing.l), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            suggestions.forEachIndexed { index, text ->
                Row(
                    Modifier
                        .appear(delayMs = 60 * index)
                        .sketchBackground(c.surface, c.line, c.shadow, radius = 22.dp, seed = 70 + index)
                        .clickable(enabled = session.ready && !session.state.busy, role = Role.Button) { onSuggestion(text) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SketchIcon(DinaIcon.SPARK, null, tint = c.accent, size = 16.dp)
                    Text(text, Modifier.padding(start = 8.dp), style = Dina.type.bodyStrong, color = c.ink)
                }
            }
        }
    }
}

/** Dina's small avatar on a soft disk. Only the live one animates; history stays still. */
@Composable
private fun Avatar(state: VoiceState, live: Boolean, audioLevel: State<Float>) {
    Box(Modifier.size(38.dp).background(Dina.colors.halo, Dina.shapes.pill), contentAlignment = Alignment.Center) {
        DinaCharacter(
            if (live) state else VoiceState.IDLE,
            Modifier.size(38.dp),
            audioLevel = { audioLevel.value },
            animated = live,
            describe = false,
        )
    }
}

@Composable
private fun MessageItem(message: DinaMessage, state: HomeUiState, live: Boolean, audioLevel: State<Float>, modifier: Modifier) {
    val c = Dina.colors
    val user = message.role == MessageRole.USER
    val time = if (state.showTimestamps) {
        Instant.ofEpochMilli(message.timestampMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm"))
    } else null
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${if (user) "Tú" else "Dina"}: ${message.text}" },
            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!user) {
                Box(Modifier.padding(bottom = 11.dp, end = 6.dp)) { Avatar(state.session.state, live, audioLevel) }
            }
            SpeechBubble(mine = user, tail = if (user) Tail.BOTTOM_END else Tail.BOTTOM_START, modifier = Modifier.widthIn(max = 320.dp)) {
                Text(message.text, style = Dina.type.body, color = c.ink)
                if (time != null) Caption(time, Modifier.padding(top = 4.dp).align(if (user) Alignment.End else Alignment.Start))
            }
        }
        // The live widget goes under the bubble, aligned with it, so its controls have room.
        message.widget?.takeIf { it.kind != WidgetKind.VOLUME }?.let {
            ContextualWidgetView(it, state.world, Modifier.padding(start = 44.dp, top = 6.dp))
        }
    }
}

/** "Dina is writing": her thinking avatar and three bouncing dots. */
@Composable
private fun TypingRow(state: VoiceState, modifier: Modifier) {
    val c = Dina.colors
    val reduced = Dina.motion.reduced
    val transition = rememberInfiniteTransition(label = "typing")
    val phase = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(900)), label = "dots")
    Row(modifier.fillMaxWidth().semantics { contentDescription = "Dina está pensando" }, verticalAlignment = Alignment.Bottom) {
        Box(Modifier.padding(bottom = 11.dp, end = 6.dp)) {
            Box(Modifier.size(38.dp).background(c.halo, Dina.shapes.pill), contentAlignment = Alignment.Center) {
                DinaCharacter(state, Modifier.size(38.dp), describe = false)
            }
        }
        SpeechBubble(mine = false, tail = Tail.BOTTOM_START) {
            Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0..2) {
                    Box(
                        Modifier
                            .graphicsLayer {
                                if (!reduced) {
                                    val t = ((phase.value - i * 0.18f) % 1f + 1f) % 1f
                                    translationY = -5.dp.toPx() * (if (t < 0.4f) kotlin.math.sin(t / 0.4f * Math.PI).toFloat() else 0f)
                                }
                            }
                            .size(8.dp)
                            .background(c.accent, Dina.shapes.pill),
                    )
                }
            }
        }
    }
}
