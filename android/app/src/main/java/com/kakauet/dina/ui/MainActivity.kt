package com.kakauet.dina.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.dina
import com.kakauet.dina.ui.character.LocalCharacterDesign
import com.kakauet.dina.ui.components.slideBetween
import com.kakauet.dina.ui.screens.DiagnosticsScreen
import com.kakauet.dina.ui.screens.HomeMode
import com.kakauet.dina.ui.screens.HomeScreen
import com.kakauet.dina.ui.screens.HomeUiState
import com.kakauet.dina.ui.screens.MyThingsScreen
import com.kakauet.dina.ui.screens.SettingsScreen
import com.kakauet.dina.ui.theme.Dina
import com.kakauet.dina.ui.theme.DinaTheme
import com.kakauet.dina.ui.theme.LightMeadow
import com.kakauet.dina.ui.theme.NightMeadow
import com.kakauet.dina.ui.theme.rememberSystemReducedMotion
import com.kakauet.dina.voice.DinaService

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) DinaService.start(this)
        else dina.store.setState(VoiceState.ERROR, "Dina necesita el micrófono. Puedes darle permiso en los ajustes de Android.")
    }
    private val prefs by lazy { UiPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = when (prefs.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            DinaTheme(if (dark) NightMeadow else LightMeadow, reducedMotion = rememberSystemReducedMotion()) {
                CompositionLocalProvider(LocalCharacterDesign provides prefs.character) { DinaApp(prefs) }
            }
        }
        if (hasMicrophone()) DinaService.start(this)
        else permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }

    /** The launcher icon follows the chosen character; switching it while visible can close the app. */
    override fun onStop() {
        super.onStop()
        LauncherIcon.apply(this, prefs.character)
    }

    private fun hasMicrophone() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** Screens by depth: going deeper slides forward, going back slides back. */
private enum class Screen(val depth: Int) { HOME(0), THINGS(1), SETTINGS(1), DIAGNOSTICS(2) }

@Composable
private fun DinaApp(prefs: UiPreferences) {
    val context = LocalContext.current
    val graph = context.dina
    val session by graph.store.session.collectAsStateWithLifecycle()
    val messages by graph.store.messages.collectAsStateWithLifecycle()
    val world by graph.tools.state.collectAsStateWithLifecycle()
    val audioLevel = graph.store.audioLevel.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var mode by rememberSaveable { mutableStateOf(HomeMode.VOICE) }
    val motion = Dina.motion

    BackHandler(enabled = screen != Screen.HOME) {
        screen = if (screen == Screen.DIAGNOSTICS) Screen.SETTINGS else Screen.HOME
    }

    AnimatedContent(
        screen,
        transitionSpec = { slideBetween(motion, forward = targetState.depth > initialState.depth) },
        label = "screen",
    ) { current ->
        when (current) {
            Screen.HOME -> HomeScreen(
                state = HomeUiState(session, messages, world, graph.settings.showTimestamps),
                audioLevel = audioLevel,
                mode = mode,
                onModeChange = { next ->
                    mode = next
                    if (next == HomeMode.VOICE) DinaService.resumeVoice(context) else DinaService.pauseVoice(context)
                },
                onOpenThings = { graph.tools.refresh(); screen = Screen.THINGS },
                onOpenSettings = { screen = Screen.SETTINGS },
                onSend = { text ->
                    when {
                        !session.ready -> { Toast.makeText(context, "Dina aún se está preparando.", Toast.LENGTH_SHORT).show(); false }
                        session.state.busy -> { Toast.makeText(context, "Espera a que Dina termine de responder.", Toast.LENGTH_SHORT).show(); false }
                        else -> { DinaService.sendText(context, text); true }
                    }
                },
                onNewConversation = { DinaService.newConversation(context) },
                onRetry = { DinaService.retry(context) },
            )
            Screen.THINGS -> MyThingsScreen(world, onClose = { screen = Screen.HOME })
            Screen.SETTINGS -> SettingsScreen(
                prefs = prefs,
                onClose = { screen = Screen.HOME },
                onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
                onNewConversation = { DinaService.newConversation(context) },
            )
            Screen.DIAGNOSTICS -> {
                val metrics by graph.store.metrics.collectAsStateWithLifecycle()
                val wake by graph.store.wakeDiagnostic.collectAsStateWithLifecycle()
                DiagnosticsScreen(metrics, wake, session.debugError, onClose = { screen = Screen.SETTINGS })
            }
        }
    }
}
