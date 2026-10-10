package com.kakauet.dina.ui.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.kakauet.dina.core.ConversationController
import com.kakauet.dina.core.DinaMessage
import com.kakauet.dina.core.MessageRole
import com.kakauet.dina.core.PerformanceMetrics
import com.kakauet.dina.core.SessionState
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.tools.Alarm
import com.kakauet.dina.tools.AlarmStatus
import com.kakauet.dina.tools.ContextualWidget
import com.kakauet.dina.tools.Repeat
import com.kakauet.dina.tools.ShoppingItem
import com.kakauet.dina.tools.Stopwatch
import com.kakauet.dina.tools.StopwatchStatus
import com.kakauet.dina.tools.Timer
import com.kakauet.dina.tools.TimerStatus
import com.kakauet.dina.tools.ToolWorld
import com.kakauet.dina.tools.WidgetKind
import com.kakauet.dina.ui.UiPreferences
import com.kakauet.dina.ui.character.CharacterDesign
import com.kakauet.dina.ui.screens.DiagnosticsScreen
import com.kakauet.dina.ui.screens.HomeMode
import com.kakauet.dina.ui.screens.HomeScreen
import com.kakauet.dina.ui.screens.HomeUiState
import com.kakauet.dina.ui.screens.MyThingsScreen
import com.kakauet.dina.ui.screens.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Galaxy S24 Ultra at its default resolution (384 × 832 dp), rendered at 2×. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-xhdpi")
class ScreenScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeIdle() = home("home-1-idle", session(VoiceState.IDLE))

    @Test fun homeListening() = home(
        "home-2-listening",
        session(VoiceState.LISTENING, transcript = "Pon un temporizador de diez minutos para la pas"),
        level = 0.6f,
    )

    @Test fun homeSpeaking() = home(
        "home-3-speaking",
        session(
            VoiceState.SPEAKING,
            transcript = "Pon un temporizador de diez minutos para la pasta",
            response = "Temporizador de pasta en marcha: 10 minutos.",
            widget = ContextualWidget(WidgetKind.TIMER, focusId = "pasta"),
        ),
        level = 0.55f,
    )

    @Test fun homeError() = home(
        "home-4-error",
        session(VoiceState.ERROR, "No he podido copiar los modelos: Espacio insuficiente: Dina necesita 1050 MB libres y hay 812 MB", ready = false),
    )

    @Test fun homeDark() = home(
        "home-5-dark",
        session(
            VoiceState.FOLLOW_UP,
            transcript = "¿Qué tengo en la lista de la compra?",
            response = "En la lista tienes 3 cosas: leche, 6 huevos y 1 kilo de tomates; ya has comprado pan.",
            widget = ContextualWidget(WidgetKind.SHOPPING),
        ),
        dark = true,
    )

    @Test fun homeMusgo() = home("home-6-musgo", session(VoiceState.LISTENING), level = 0.5f, design = CharacterDesign.MUSGO)

    @Test fun chatEmpty() = home("chat-1-empty", session(VoiceState.IDLE, ConversationController.TEXT_IDLE), mode = HomeMode.TEXT, messages = emptyList())

    @Test fun chatConversation() = home("chat-2-conversation", session(VoiceState.THINKING), mode = HomeMode.TEXT, messages = conversation)

    @Test fun chatDark() = home("chat-3-dark", session(VoiceState.SPEAKING), mode = HomeMode.TEXT, messages = conversation.dropLast(1), dark = true, level = 0.5f)

    @Test fun things() = compose.snap("screens", "things-1") { Themed { MyThingsScreen(world(), onClose = {}) } }

    @Test fun thingsDark() = compose.snap("screens", "things-2-dark") { Themed(dark = true) { MyThingsScreen(world(), onClose = {}) } }

    @Test fun thingsEmpty() = compose.snap("screens", "things-3-empty") { Themed { MyThingsScreen(ToolWorld(), onClose = {}) } }

    @Test fun settings() = compose.snap("screens", "settings-1") {
        Themed { SettingsScreen(UiPreferences(RuntimeEnvironment.getApplication()), onClose = {}, onOpenDiagnostics = {}, onNewConversation = {}) }
    }

    @Test fun settingsDark() = compose.snap("screens", "settings-2-dark") {
        Themed(dark = true) { SettingsScreen(UiPreferences(RuntimeEnvironment.getApplication()), onClose = {}, onOpenDiagnostics = {}, onNewConversation = {}) }
    }

    /** The whole settings list at once (a tall screen), to review every section. */
    @Config(qualifiers = "w384dp-h3400dp-xhdpi")
    @Test fun settingsFull() = compose.snap("screens", "settings-3-full") {
        Themed { SettingsScreen(UiPreferences(RuntimeEnvironment.getApplication()), onClose = {}, onOpenDiagnostics = {}, onNewConversation = {}) }
    }

    @Test fun diagnostics() = compose.snap("screens", "diagnostics") {
        Themed {
            DiagnosticsScreen(
                PerformanceMetrics(
                    sttFinalToPromptReadyMs = 1.0, tokenizationMs = 4.0, prefillMs = 180.0, decodeMs = 240.0, generationMs = 220.0, tokensPerSecond = 38.4,
                    toolMs = 2.0, turnWaitMs = 1.0, turnBookkeepingMs = 3.0, firstSynthesisMs = 620.0, audioStartMs = 35.0, transcriptToFirstAudioMs = 1086.0,
                    prepareMs = 410.0, sentences = 2, voice = "Supertonic 3 · F2 · 4 pasos · guía en los 2 primeros · CPU · recorte · caché · 4 hilos",
                    ttsDurationMs = 3.0, ttsEncoderMs = 9.0, ttsEstimatorMs = 241.0, ttsVocoderMs = 57.0, ttsLeadTrimmedMs = 514.0, ttsGapMs = 0.0,
                    ttsCachedSentences = 1, ttsCacheReadMs = 2.0, audioEndClock = "timestamp", variant = "Dina Lite · Dina 4.5 350M (Q8_0) · 4 de 8 núcleos rápidos",
                    promptTokens = 132, reusedPromptTokens = 118, completionTokens = 9,
                ),
                wake = null,
                debugError = "",
                onClose = {},
            )
        }
    }

    private fun home(
        name: String,
        session: SessionState,
        mode: HomeMode = HomeMode.VOICE,
        messages: List<DinaMessage> = emptyList(),
        level: Float = 0f,
        dark: Boolean = false,
        design: CharacterDesign = CharacterDesign.BROTE,
    ) = compose.snap("screens", name) {
        Themed(dark, design) { Home(session, mode, messages, level) }
    }

    @Composable
    private fun Home(session: SessionState, mode: HomeMode, messages: List<DinaMessage>, level: Float) {
        val audio = remember { mutableFloatStateOf(level) }
        HomeScreen(
            state = HomeUiState(session, messages, world(), showTimestamps = false),
            audioLevel = audio,
            mode = mode,
            onModeChange = {},
            onOpenThings = {},
            onOpenSettings = {},
            onSend = { true },
            onNewConversation = {},
            onRetry = {},
        )
    }
}

private fun session(
    state: VoiceState,
    detail: String = state.label,
    ready: Boolean = true,
    transcript: String = "",
    response: String = "",
    widget: ContextualWidget? = null,
) = SessionState(state, detail, ready, transcript, response, widget)

private val conversation = listOf(
    DinaMessage(1, MessageRole.USER, "Pon un temporizador de 10 minutos para la pasta"),
    DinaMessage(2, MessageRole.DINA, "Temporizador de pasta en marcha: 10 minutos.", widget = ContextualWidget(WidgetKind.TIMER, focusId = "pasta")),
    DinaMessage(3, MessageRole.USER, "¿Qué tengo en la lista de la compra?"),
    DinaMessage(4, MessageRole.DINA, "En la lista tienes 3 cosas: leche, 6 huevos y 1 kilo de tomates; ya has comprado pan."),
    DinaMessage(5, MessageRole.USER, "Añade tomates"),
)

/** Sample state relative to the real clock, since the widgets tick with it. */
private fun world(): ToolWorld {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val tomorrow = LocalDate.now(zone).plusDays(1)
    fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
    return ToolWorld(
        timers = listOf(
            Timer("pasta", "pasta", now + 572_000L, 572_000L, 600_000L, TimerStatus.RUNNING),
            Timer("huevos", "huevos", now, 0L, 360_000L, TimerStatus.RINGING),
            Timer("colada", null, now, 2_400_000L, 3_600_000L, TimerStatus.PAUSED),
        ),
        alarms = listOf(
            Alarm("gym", "Gimnasio", at(tomorrow, 7, 30), Repeat.Weekdays, AlarmStatus.SCHEDULED),
            Alarm("siesta", null, at(tomorrow, 16, 0), null, AlarmStatus.DISABLED),
        ),
        stopwatches = listOf(Stopwatch("run", null, 83_400L, now, StopwatchStatus.RUNNING)),
        shopping = listOf(
            ShoppingItem("1", "leche", null, null, false),
            ShoppingItem("2", "pan", null, null, true),
            ShoppingItem("3", "huevos", 6.0, null, false),
            ShoppingItem("4", "tomates", 1.0, "kg", false),
        ),
    )
}
