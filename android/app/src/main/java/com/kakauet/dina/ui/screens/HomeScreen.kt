package com.kakauet.dina.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.kakauet.dina.core.DinaMessage
import com.kakauet.dina.core.MessageRole
import com.kakauet.dina.core.SessionState
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.tools.ContextualWidget
import com.kakauet.dina.tools.ToolWorld
import com.kakauet.dina.tools.WidgetKind
import com.kakauet.dina.ui.character.DinaCharacter
import com.kakauet.dina.ui.components.ButtonStyle
import com.kakauet.dina.ui.components.Caption
import com.kakauet.dina.ui.components.CardTone
import com.kakauet.dina.ui.components.DinaButton
import com.kakauet.dina.ui.components.DinaCard
import com.kakauet.dina.ui.components.DinaIcon
import com.kakauet.dina.ui.components.DinaTextField
import com.kakauet.dina.ui.components.FitText
import com.kakauet.dina.ui.components.From
import com.kakauet.dina.ui.components.Halo
import com.kakauet.dina.ui.components.LevelMeter
import com.kakauet.dina.ui.components.Overline
import com.kakauet.dina.ui.components.PillSwitch
import com.kakauet.dina.ui.components.RoundIconButton
import com.kakauet.dina.ui.components.SketchBar
import com.kakauet.dina.ui.components.SketchIcon
import com.kakauet.dina.ui.components.SpeechBubble
import com.kakauet.dina.ui.components.Tail
import com.kakauet.dina.ui.components.animateDinaItem
import com.kakauet.dina.ui.components.appear
import com.kakauet.dina.ui.components.enter
import com.kakauet.dina.ui.components.exit
import com.kakauet.dina.ui.components.pressFeedback
import com.kakauet.dina.ui.components.rememberSmoothedLevel
import com.kakauet.dina.ui.components.riseBetween
import com.kakauet.dina.ui.components.sketchBackground
import com.kakauet.dina.ui.components.slideBetween
import com.kakauet.dina.ui.components.spring
import com.kakauet.dina.ui.components.tween
import com.kakauet.dina.ui.theme.Dina
import com.kakauet.dina.ui.theme.DinaPalette
import com.kakauet.dina.ui.widgets.ContextualWidgetView
import kotlinx.coroutines.delay
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
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = Dina.sizes.readable - Dina.spacing.xxl * 2).fillMaxWidth().padding(horizontal = Dina.spacing.gutter),
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
        Modifier.fillMaxWidth().padding(start = Dina.spacing.gutter, end = Dina.spacing.gutter, top = Dina.spacing.xs, bottom = Dina.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Dina", Modifier.semantics { heading() }, style = Dina.type.display, color = Dina.colors.ink, maxLines = 1)
            val muted = Dina.colors.inkMuted
            FitText("ASISTENTE LOCAL", Dina.type.overline, { muted }, minSize = Dina.type.overlineMin)
        }
        DinaButton("Mis cosas", onOpenThings, icon = DinaIcon.LIST)
        Spacer(Modifier.width(Dina.spacing.s))
        RoundIconButton(DinaIcon.GEAR, "Ajustes", onOpenSettings)
    }
}

/** What Dina is doing, in her words; [VoiceState.label] stays the short technical name. */
private fun VoiceState.title(ready: Boolean): String = when (this) {
    VoiceState.IDLE -> "Di «Dina» cuando quieras"
    VoiceState.ACTIVATED -> "¡Dime!"
    VoiceState.LISTENING -> "Te escucho…"
    VoiceState.TRANSCRIBING -> "Entendiendo…"
    VoiceState.THINKING -> "Pensando…"
    VoiceState.EXECUTING -> "Un momento…"
    VoiceState.SPEAKING -> "Hablando"
    VoiceState.FOLLOW_UP -> "¿Algo más?"
    VoiceState.LOADING -> "Me estoy despertando"
    VoiceState.ERROR -> if (ready) "Vaya, algo ha fallado" else "No he podido arrancar"
}

/** The disk behind Dina takes on the mood: green while she listens, sunny while she thinks, peach on errors. */
private fun DinaPalette.haloFor(state: VoiceState): Color = when (state) {
    // In dark the disk only tints, so her ink outline keeps reading on it.
    VoiceState.ACTIVATED, VoiceState.LISTENING, VoiceState.FOLLOW_UP, VoiceState.SPEAKING -> if (isDark) lerp(halo, accent, 0.2f) else accentSoft
    VoiceState.TRANSCRIBING, VoiceState.THINKING, VoiceState.EXECUTING -> lerp(halo, sun, if (isDark) 0.18f else 0.32f)
    VoiceState.ERROR -> if (isDark) lerp(halo, warm, 0.3f) else warmSoft
    VoiceState.LOADING -> if (isDark) halo else surfaceAlt
    VoiceState.IDLE -> halo
}

/** Keeps the last value worth showing, so content that animates out does not go blank first. */
@Composable
private fun <T> rememberLast(value: T, keep: (T) -> Boolean): T {
    val last = remember { mutableStateOf(value) }
    if (keep(value)) last.value = value
    return last.value
}

/** True once [expiresAtMs] has passed; wakes up once at that moment instead of ticking. */
@Composable
private fun rememberExpired(expiresAtMs: Long?): Boolean {
    var expired by remember(expiresAtMs) { mutableStateOf(expiresAtMs != null && expiresAtMs <= System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMs) {
        if (expiresAtMs != null && !expired) {
            delay(expiresAtMs - System.currentTimeMillis())
            expired = true
        }
    }
    return expired
}

@Composable
private fun VoiceView(state: HomeUiState, audioLevel: State<Float>, onRetry: () -> Unit) {
    val c = Dina.colors
    val motion = Dina.motion
    val session = state.session
    val voice = session.state
    val hearing = voice == VoiceState.LISTENING || voice == VoiceState.SPEAKING
    val level = rememberSmoothedLevel({ audioLevel.value }, active = hearing)
    val halo by animateColorAsState(c.haloFor(voice), motion.tween(motion.gentle), label = "halo")
    val expired = rememberExpired(session.widget?.expiresAtMs)
    val liveWidget = session.widget?.takeIf { it.autoOpen && !expired }
    val idle = voice == VoiceState.IDLE && session.ready && session.transcript.isBlank() && session.response.isBlank() && liveWidget == null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dina.spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = Dina.spacing.s).size(Dina.sizes.character + Dina.spacing.xs), contentAlignment = Alignment.Center) {
            Halo(
                { halo },
                c.shadow,
                outline = null,
                modifier = Modifier.size(Dina.sizes.character - Dina.spacing.s).graphicsLayer {
                    // The disk breathes with the sound: yours while she listens, hers while she speaks.
                    val swell = 1f + 0.05f * level.value
                    scaleX = swell
                    scaleY = swell
                },
            )
            DinaCharacter(voice, Modifier.size(Dina.sizes.character), audioLevel = { audioLevel.value })
        }
        AnimatedContent(voice.title(session.ready), transitionSpec = { riseBetween(motion) }, label = "state") { title ->
            Text(title, Modifier.padding(top = Dina.spacing.xs), style = Dina.type.state, color = c.ink, textAlign = TextAlign.Center)
        }
        AnimatedVisibility(hearing, enter = motion.enter(), exit = motion.exit()) {
            LevelMeter({ level.value }, Modifier.padding(top = Dina.spacing.s), color = c.accent)
        }
        // Details that matter (Moonshine, a turn's progress); loading and errors have their own cards.
        val detail = session.detail.takeIf {
            voice != VoiceState.LOADING && voice != VoiceState.ERROR && it.trimEnd('…', '.') != voice.label.trimEnd('…', '.')
        }
        val shownDetail = rememberLast(detail) { it != null }
        AnimatedVisibility(detail != null, enter = motion.enter(), exit = motion.exit()) {
            Caption(
                shownDetail.orEmpty(),
                Modifier.padding(top = Dina.spacing.xs, start = Dina.spacing.m, end = Dina.spacing.m),
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
        }
        Spacer(Modifier.height(Dina.spacing.l))

        AnimatedVisibility(voice == VoiceState.LOADING, enter = motion.enter(), exit = motion.exit()) {
            LoadingCard(session)
        }
        val error = rememberLast(session.takeIf { voice == VoiceState.ERROR }) { it != null }
        AnimatedVisibility(voice == VoiceState.ERROR, enter = motion.enter(), exit = motion.exit()) {
            if (error != null) ErrorCard(error, onRetry)
        }
        AnimatedVisibility(idle, enter = motion.enter(), exit = motion.exit()) {
            IdleHint()
        }

        val transcript = rememberLast(session.transcript) { it.isNotBlank() }
        AnimatedVisibility(session.transcript.isNotBlank(), Modifier.align(Alignment.End), enter = motion.enter(), exit = motion.exit()) {
            SpeechBubble(
                mine = true,
                tail = Tail.BOTTOM_END,
                modifier = Modifier.padding(start = Dina.spacing.xxl).widthIn(max = Dina.sizes.readable).animateContentSize(motion.spring(motion.smooth)),
            ) {
                Overline("Tú", color = c.inkMuted)
                Text(transcript, Modifier.padding(top = Dina.spacing.xxs), style = Dina.type.bodyLarge, color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        val response = rememberLast(session.response) { it.isNotBlank() }
        AnimatedVisibility(session.response.isNotBlank(), Modifier.align(Alignment.Start), enter = motion.enter(), exit = motion.exit()) {
            SpeechBubble(
                mine = false,
                tail = Tail.TOP_START,
                modifier = Modifier.padding(end = Dina.spacing.xxl, top = Dina.spacing.xs).widthIn(max = Dina.sizes.readable).animateContentSize(motion.spring(motion.smooth)),
            ) {
                Overline("Dina")
                Text(response, Modifier.padding(top = Dina.spacing.xxs), style = Dina.type.bodyLarge, color = c.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
        val widget = rememberLast<ContextualWidget?>(liveWidget) { it != null }
        AnimatedVisibility(liveWidget != null, enter = motion.enter(), exit = motion.exit()) {
            if (widget != null) ContextualWidgetView(widget, state.world, Modifier.padding(top = Dina.spacing.l))
        }
        Spacer(Modifier.height(Dina.spacing.xxl))
    }
}

/** Waking up: what is loading now and how far along, with a shimmer so a long step still looks alive. */
@Composable
private fun LoadingCard(session: SessionState) {
    val c = Dina.colors
    val motion = Dina.motion
    val progress by animateFloatAsState(session.loadProgress, motion.spring(motion.calm), label = "loading")
    val shimmer = if (motion.reduced) null else {
        val loop = rememberInfiniteTransition(label = "shimmer")
        val phase = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(motion.loop * 2), RepeatMode.Restart), label = "phase");
        { phase.value }
    }
    val percent = (session.loadProgress * 100).toInt()
    DinaCard(Modifier.widthIn(max = Dina.sizes.readable)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline("Preparando los modelos", Modifier.weight(1f))
            Text("$percent %", style = Dina.type.label, color = c.accent)
        }
        AnimatedContent(session.detail, transitionSpec = { riseBetween(motion) }, label = "loadingStep") { step ->
            Text(step, Modifier.padding(top = Dina.spacing.xs), style = Dina.type.bodyStrong, color = c.ink)
        }
        SketchBar(
            { progress },
            Modifier.padding(top = Dina.spacing.m).semantics { contentDescription = "Preparando a Dina: $percent por ciento" },
            shimmer = shimmer,
        )
        Caption("La primera vez tarda un poco más. Todo se queda en tu móvil.", Modifier.padding(top = Dina.spacing.m))
    }
}

/** A warm card instead of red text: what failed in plain words, and a way out. */
@Composable
private fun ErrorCard(session: SessionState, onRetry: () -> Unit) {
    val c = Dina.colors
    DinaCard(Modifier.widthIn(max = Dina.sizes.readable), tone = CardTone.WARM) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SketchIcon(DinaIcon.SPARK, null, tint = c.onWarm, size = Dina.sizes.icon)
            Overline(if (session.ready) "Un momento" else "Algo me lo impide", Modifier.padding(start = Dina.spacing.iconGap), color = c.onWarm)
        }
        Text(session.detail, Modifier.padding(top = Dina.spacing.s), style = Dina.type.body, color = c.onWarm)
        if (!session.ready) {
            Caption("Si vuelve a pasar, en Ajustes › Diagnóstico tienes el detalle.", Modifier.padding(top = Dina.spacing.s), color = c.onWarm)
            DinaButton("Reintentar", onRetry, Modifier.fillMaxWidth().padding(top = Dina.spacing.m), icon = DinaIcon.REFRESH, style = ButtonStyle.PRIMARY)
        }
    }
}

// Non-breaking spaces keep each example's last word with the one before it.
private val examples = listOf(
    "«Dina, pon un temporizador de 10 minutos»",
    "«Dina, ¿qué tengo en la lista?»",
    "«Dina, pon una alarma a las 7:30»",
)

/** Resting: a few things to try, so the empty half of the screen says something. */
@Composable
private fun IdleHint() {
    val c = Dina.colors
    Column(
        Modifier.widthIn(max = Dina.sizes.readable).fillMaxWidth()
            .sketchBackground(c.surface.copy(alpha = 0.7f), c.line.copy(alpha = 0.7f), shadow = null, radius = Dina.shapes.cardRadius, seed = 140)
            .padding(horizontal = Dina.spacing.cardX, vertical = Dina.spacing.cardY),
    ) {
        Overline("Prueba a decir", color = c.inkMuted)
        examples.forEachIndexed { index, example ->
            Row(Modifier.padding(top = Dina.spacing.s).appear(delayMs = Dina.motion.stagger * (index + 1)), verticalAlignment = Alignment.Top) {
                SketchIcon(DinaIcon.SPARK, null, Modifier.padding(top = Dina.spacing.xxs), tint = c.accent, size = Dina.sizes.iconSmall)
                Text(example, Modifier.padding(start = Dina.spacing.iconGap), style = Dina.type.body, color = c.ink)
            }
        }
    }
}

@Composable
private fun ChatView(state: HomeUiState, audioLevel: State<Float>, onSend: (String) -> Boolean, onNewConversation: () -> Unit) {
    val c = Dina.colors
    val motion = Dina.motion
    val session = state.session
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    val busy = !session.ready || session.state.busy
    val working = session.state == VoiceState.TRANSCRIBING || session.state == VoiceState.THINKING || session.state == VoiceState.EXECUTING
    val typing = working && state.messages.lastOrNull()?.role == MessageRole.USER
    val openedAt = remember { System.currentTimeMillis() }
    val rows = state.messages.size + if (typing) 1 else 0
    LaunchedEffect(rows) {
        if (rows > 0) listState.animateScrollToItem(rows - 1)
    }
    val submit = { if (draft.isNotBlank() && onSend(draft.trim())) draft = "" }
    // Only what is worth saying: still loading, or something failed.
    val status = when {
        session.state == VoiceState.ERROR -> session.detail
        !session.ready -> session.detail
        else -> null
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = Dina.spacing.gutter)) {
        AnimatedVisibility(status != null || state.messages.isNotEmpty(), enter = motion.enter(), exit = motion.exit()) {
            Row(Modifier.fillMaxWidth().padding(top = Dina.spacing.xs).heightIn(min = Dina.spacing.touch), verticalAlignment = Alignment.CenterVertically) {
                val shownStatus = rememberLast(status) { it != null }
                Box(Modifier.weight(1f)) {
                    // Qualified: inside the row, the RowScope overload would shadow this one.
                    androidx.compose.animation.AnimatedVisibility(status != null, enter = motion.enter(), exit = motion.exit()) {
                        Caption(shownStatus.orEmpty(), color = if (session.state == VoiceState.ERROR) c.danger else c.inkMuted, maxLines = 2)
                    }
                }
                AnimatedVisibility(state.messages.isNotEmpty(), enter = motion.enter(), exit = motion.exit()) {
                    DinaButton("Nueva", onNewConversation, icon = DinaIcon.REFRESH, style = ButtonStyle.GHOST)
                }
            }
        }
        AnimatedContent(state.messages.isEmpty(), Modifier.weight(1f), transitionSpec = { riseBetween(motion) }, label = "chat") { empty ->
            if (empty) {
                EmptyChat(session, Modifier.fillMaxSize()) { suggestion -> onSend(suggestion) }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(top = Dina.spacing.xs, bottom = Dina.spacing.stack),
                    verticalArrangement = Arrangement.spacedBy(Dina.spacing.stack),
                ) {
                    val lastDina = state.messages.lastOrNull { it.role == MessageRole.DINA }?.id
                    items(state.messages, key = { it.id }) { message ->
                        val live = message.id == lastDina && !typing && message === state.messages.last()
                        val user = message.role == MessageRole.USER
                        val entrance = if (message.timestampMs >= openedAt) Modifier.appear(from = if (user) From.END else From.START) else Modifier
                        MessageItem(message, state, live, audioLevel, animateDinaItem().then(entrance))
                    }
                    if (typing) item(key = "typing") { TypingRow(session.state, animateDinaItem().appear(from = From.START)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Dina.spacing.s, bottom = Dina.spacing.m), verticalAlignment = Alignment.Bottom) {
            DinaTextField(
                draft, { draft = it }, "Escribe a Dina…", Modifier.weight(1f),
                singleLine = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                label = "Mensaje para Dina",
            )
            RoundIconButton(DinaIcon.SEND, "Enviar", submit, Modifier.padding(start = Dina.spacing.s), style = ButtonStyle.PRIMARY, enabled = !busy && draft.isNotBlank(), size = Dina.sizes.field)
        }
    }
}

private val suggestions = listOf("Pon un temporizador de 10 minutos", "Añade pan a la lista", "¿Qué hora es?")

@Composable
private fun EmptyChat(session: SessionState, modifier: Modifier, onSuggestion: (String) -> Unit) {
    val c = Dina.colors
    val enabled = session.ready && !session.state.busy
    Column(
        modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(Dina.sizes.characterSmall + Dina.spacing.xs), contentAlignment = Alignment.Center) {
            Halo({ c.halo }, c.shadow, outline = null, modifier = Modifier.size(Dina.sizes.characterSmall - Dina.spacing.s))
            DinaCharacter(if (session.ready) VoiceState.FOLLOW_UP else session.state, Modifier.size(Dina.sizes.characterSmall), describe = false, greeting = session.ready)
        }
        Text("Escríbeme", Modifier.padding(top = Dina.spacing.s).semantics { heading() }, style = Dina.type.heading, color = c.ink, textAlign = TextAlign.Center)
        Caption(
            "Lo mismo que por voz: alarmas, temporizadores, la compra y más.",
            Modifier.padding(top = Dina.spacing.xs, start = Dina.spacing.xl, end = Dina.spacing.xl),
            textAlign = TextAlign.Center,
        )
        Column(
            Modifier.padding(top = Dina.spacing.l).widthIn(max = Dina.sizes.readable - Dina.spacing.xxl).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dina.spacing.stack),
        ) {
            suggestions.forEachIndexed { index, text ->
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .appear(delayMs = Dina.motion.stagger * index)
                        .pressFeedback(press)
                        .graphicsLayer { alpha = if (enabled) 1f else 0.6f }
                        .sketchBackground(c.surface, c.line, c.shadow, radius = Dina.shapes.bubbleRadius, seed = 70 + index)
                        .clickable(press, indication = null, enabled = enabled, role = Role.Button) { onSuggestion(text) }
                        .heightIn(min = Dina.spacing.touch)
                        .padding(horizontal = Dina.spacing.l - Dina.spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SketchIcon(DinaIcon.SPARK, null, tint = c.accent, size = Dina.sizes.iconSmall)
                    Text(text, Modifier.padding(start = Dina.spacing.iconGap), style = Dina.type.bodyStrong, color = c.ink)
                }
            }
        }
    }
}

/** Dina's small avatar on a soft disk. Only the live one animates; history stays still. */
@Composable
private fun Avatar(state: VoiceState, live: Boolean, audioLevel: State<Float>) {
    Box(Modifier.size(Dina.sizes.avatar).background(Dina.colors.halo, Dina.shapes.pill), contentAlignment = Alignment.Center) {
        DinaCharacter(
            if (live) state else VoiceState.IDLE,
            Modifier.size(Dina.sizes.avatar),
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
    // Dina's widgets start where her bubble does.
    val indent = Dina.sizes.avatar + Dina.spacing.s
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${if (user) "Tú" else "Dina"}: ${message.text}" },
            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!user) {
                Box(Modifier.padding(bottom = Dina.spacing.m, end = Dina.spacing.s)) { Avatar(state.session.state, live, audioLevel) }
            } else {
                Spacer(Modifier.width(Dina.spacing.xxl))
            }
            SpeechBubble(mine = user, tail = if (user) Tail.BOTTOM_END else Tail.BOTTOM_START, modifier = Modifier.weight(1f, fill = false)) {
                Text(message.text, style = Dina.type.body, color = c.ink)
                if (time != null) Caption(time, Modifier.padding(top = Dina.spacing.xs).align(if (user) Alignment.End else Alignment.Start))
            }
            if (!user) Spacer(Modifier.width(Dina.spacing.l))
        }
        // The live widget goes under the bubble, aligned with it, so its controls have room.
        message.widget?.takeIf { it.kind != WidgetKind.VOLUME }?.let {
            ContextualWidgetView(it, state.world, Modifier.padding(start = indent, top = Dina.spacing.s - Dina.spacing.xxs))
        }
    }
}

/** "Dina is writing": her thinking avatar and three bouncing dots. */
@Composable
private fun TypingRow(state: VoiceState, modifier: Modifier) {
    val c = Dina.colors
    val motion = Dina.motion
    val transition = rememberInfiniteTransition(label = "typing")
    val phase = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(motion.loop)), label = "dots")
    val lift = Dina.spacing.xs + Dina.spacing.xxs / 2
    Row(modifier.fillMaxWidth().semantics { contentDescription = "Dina está pensando" }, verticalAlignment = Alignment.Bottom) {
        Box(Modifier.padding(bottom = Dina.spacing.m, end = Dina.spacing.s)) {
            Box(Modifier.size(Dina.sizes.avatar).background(c.halo, Dina.shapes.pill), contentAlignment = Alignment.Center) {
                DinaCharacter(state, Modifier.size(Dina.sizes.avatar), describe = false)
            }
        }
        SpeechBubble(mine = false, tail = Tail.BOTTOM_START) {
            Row(Modifier.height(Dina.spacing.l + Dina.spacing.xs), horizontalArrangement = Arrangement.spacedBy(Dina.spacing.s - Dina.spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0..2) {
                    Box(
                        Modifier
                            .graphicsLayer {
                                if (!motion.reduced) {
                                    val t = ((phase.value - i * 0.18f) % 1f + 1f) % 1f
                                    translationY = -lift.toPx() * (if (t < 0.4f) kotlin.math.sin(t / 0.4f * Math.PI).toFloat() else 0f)
                                }
                            }
                            .size(Dina.spacing.s)
                            .background(c.accent, Dina.shapes.pill),
                    )
                }
            }
        }
    }
}
