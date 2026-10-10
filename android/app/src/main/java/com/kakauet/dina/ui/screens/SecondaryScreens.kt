package com.kakauet.dina.ui.screens

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kakauet.dina.config.DinaSettings
import com.kakauet.dina.core.PerformanceMetrics
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.core.WakeDiagnosticEvent
import com.kakauet.dina.dina
import com.kakauet.dina.tools.ShoppingCommand
import com.kakauet.dina.tools.StopwatchCommand
import com.kakauet.dina.tools.TimerCommand
import com.kakauet.dina.tools.ToolWorld
import com.kakauet.dina.ui.ThemeMode
import com.kakauet.dina.ui.UiPreferences
import com.kakauet.dina.ui.character.CharacterDesign
import com.kakauet.dina.ui.character.DinaCharacter
import com.kakauet.dina.ui.components.ButtonStyle
import com.kakauet.dina.ui.components.Caption
import com.kakauet.dina.ui.components.ConfirmDialog
import com.kakauet.dina.ui.components.DinaButton
import com.kakauet.dina.ui.components.DinaCard
import com.kakauet.dina.ui.components.DinaIcon
import com.kakauet.dina.ui.components.DinaSlider
import com.kakauet.dina.ui.components.EmptyState
import com.kakauet.dina.ui.components.InfoRow
import com.kakauet.dina.ui.components.Overline
import com.kakauet.dina.ui.components.PillSwitch
import com.kakauet.dina.ui.components.ScreenScaffold
import com.kakauet.dina.ui.components.SectionHeader
import com.kakauet.dina.ui.components.SketchIcon
import com.kakauet.dina.ui.components.ToggleRow
import com.kakauet.dina.ui.components.sketchBackground
import com.kakauet.dina.ui.theme.Dina
import com.kakauet.dina.ui.widgets.AlarmCard
import com.kakauet.dina.ui.widgets.ShoppingCard
import com.kakauet.dina.ui.widgets.ShoppingComposer
import com.kakauet.dina.ui.widgets.StopwatchCard
import com.kakauet.dina.ui.widgets.TimerCard
import com.kakauet.dina.voice.DinaService
import com.kakauet.dina.voice.OrtProvider
import com.kakauet.dina.voice.SupertonicVoice
import com.kakauet.dina.config.AppVariant
import com.kakauet.dina.voice.VoiceChoice
import com.kakauet.dina.voice.WakeSensitivity
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val SPANISH: Locale = Locale.forLanguageTag("es-ES")

/** "Mis cosas": the same persistent state voice and text use, with direct controls. */
@Composable
fun MyThingsScreen(world: ToolWorld, onClose: () -> Unit) {
    val tools = LocalContext.current.dina.tools
    ScreenScaffold("Mis cosas", onClose, subtitle = "Lo que Dina guarda para ti. Puedes cambiarlo aquí o por voz.") {
        item(key = "timers") { SectionHeader("Temporizadores", icon = DinaIcon.HOURGLASS, count = world.timers.size) }
        if (world.timers.isEmpty()) item(key = "timers-empty") {
            EmptyState(DinaIcon.HOURGLASS, "Sin temporizadores", "Prueba a decir «pon un temporizador de 10 minutos».", Modifier.animateItem())
        }
        items(world.timers, key = { "timer-" + it.id }) { Box(Modifier.animateItem()) { TimerCard(it) } }

        item(key = "alarms") { SectionHeader("Alarmas", icon = DinaIcon.BELL, count = world.alarms.size) }
        if (world.alarms.isEmpty()) item(key = "alarms-empty") {
            EmptyState(DinaIcon.BELL, "Sin alarmas", "Prueba a decir «pon una alarma a las 7».", Modifier.animateItem())
        }
        items(world.alarms, key = { "alarm-" + it.id }) { Box(Modifier.animateItem()) { AlarmCard(it) } }

        item(key = "stopwatches") { SectionHeader("Cronómetro", icon = DinaIcon.STOPWATCH, count = world.stopwatches.size) }
        if (world.stopwatches.isEmpty()) item(key = "stopwatches-empty") {
            EmptyState(DinaIcon.STOPWATCH, "Cronómetro listo", "Inícialo aquí o diciendo «pon un cronómetro».", Modifier.animateItem()) {
                DinaButton(
                    "Iniciar", { tools.execute(StopwatchCommand.Start()) }, Modifier.fillMaxWidth().padding(top = 14.dp),
                    icon = DinaIcon.PLAY, style = ButtonStyle.PRIMARY,
                )
            }
        }
        items(world.stopwatches, key = { "stopwatch-" + it.id }) { Box(Modifier.animateItem()) { StopwatchCard(it) } }

        item(key = "shopping") { SectionHeader("Lista de la compra", icon = DinaIcon.CART, count = world.shopping.count { !it.completed }) }
        item(key = "shopping-add") { ShoppingComposer() }
        item(key = "shopping-list") { ShoppingCard(world.shopping) }
    }
}

@Composable
fun SettingsScreen(prefs: UiPreferences, onClose: () -> Unit, onOpenDiagnostics: () -> Unit, onNewConversation: () -> Unit) {
    val context = LocalContext.current
    val graph = context.dina
    val settings = graph.settings
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    fun changed(block: DinaSettings.() -> Unit) {
        settings.block()
        DinaService.settingsChanged(context)
    }
    val exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    val notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    ScreenScaffold("Ajustes", onClose) {
        if (!exactAlarms || !notifications) {
            section("Permisos", DinaIcon.BELL)
            if (!notifications) item {
                DinaButton("Permitir notificaciones", {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }, Modifier.fillMaxWidth(), icon = DinaIcon.BELL, style = ButtonStyle.PRIMARY)
            }
            if (!exactAlarms) item {
                DinaButton("Permitir alarmas exactas", {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }, Modifier.fillMaxWidth(), icon = DinaIcon.BELL, style = ButtonStyle.PRIMARY)
            }
            item { Caption("Sin ellos, las alarmas y los temporizadores pueden sonar tarde o sin aviso.", Modifier.padding(start = 4.dp)) }
        }

        section("Escucha", DinaIcon.MIC)
        item { SettingToggle("Dina activa", "Escucha la palabra «Dina» mientras la app está abierta.", settings.dinaActive) { changed { dinaActive = it } } }
        item { SettingToggle("Escuchar en segundo plano", "Sigue escuchando al salir de la app, con una notificación fija.", settings.backgroundListening) { changed { backgroundListening = it } } }
        item { SettingToggle("Con la pantalla apagada", "Solo con la escucha en segundo plano. Gasta más batería.", settings.listenScreenOff) { changed { listenScreenOff = it } } }
        item { Overline("Sensibilidad", Modifier.padding(start = 4.dp, top = 4.dp), color = Dina.colors.inkMuted) }
        item {
            SettingChoice(WakeSensitivity.entries.reversed().map { it.name to it.label }, settings.wakeSensitivity.name) { name ->
                changed { wakeSensitivity = WakeSensitivity.valueOf(name) }
            }
        }
        item { Caption("Alta: te oye mejor de lejos o en voz baja, pero se activa más por error. Baja: casi nunca se activa sola.", Modifier.padding(start = 4.dp)) }
        item { SliderRow("Tiempo para seguir hablando", settings.followUpTimeoutMs / 1000f, 3f..12f, { "${it.toInt()} s" }, steps = 8) { changed { followUpTimeoutMs = it.toLong() * 1_000L } } }
        item {
            SettingToggle(
                "Reconocimiento con Moonshine",
                "Por defecto se usa el reconocimiento de voz de Android, sin conexión. Moonshine funciona dentro de Dina y ocupa 65 MB más.",
                settings.moonshineStt,
            ) { changed { moonshineStt = it } }
        }

        section("Voz", DinaIcon.SPEAKER)
        item { SettingToggle("Responder por voz", "Dina dice la respuesta en voz alta.", settings.respondByVoice) { changed { respondByVoice = it } } }
        item {
            SettingChoice(VoiceChoice.entries.map { it.id to it.label }, settings.voice.id) { id -> changed { voiceId = id } }
        }
        item { Caption("Supertonic suena más natural; Piper es más ligera y responde antes en móviles lentos.", Modifier.padding(start = 4.dp)) }
        if (AppVariant.stepChoices.size > 1) {
            item {
                SettingChoice(AppVariant.stepChoices.map { it.steps.toString() to it.label }, settings.supertonicSteps.toString()) { steps -> changed { supertonicSteps = steps.toInt() } }
            }
            item { Caption("Natural: voz más pulida. Rápida: empieza a hablar antes. Recomendado: ${AppVariant.defaultSteps} pasos.", Modifier.padding(start = 4.dp)) }
        } else {
            item { Caption("Dina Lite habla con ${AppVariant.defaultSteps} pasos, para responder antes en móviles modestos.", Modifier.padding(start = 4.dp)) }
        }
        item { SliderRow("Volumen de Dina", settings.voiceVolume * 100f, 0f..100f, { "${it.toInt()} %" }) { changed { voiceVolume = it / 100f } } }

        section("Apariencia", DinaIcon.SPARK)
        item { CharacterPicker(prefs.character, prefs::updateCharacter) }
        item { Caption("El icono de la app cambia con el personaje al salir de Dina.", Modifier.padding(start = 4.dp)) }
        item {
            PillSwitch(
                ThemeMode.entries.map { it.label to null },
                prefs.theme.ordinal,
                { prefs.updateTheme(ThemeMode.entries[it]) },
                Modifier.fillMaxWidth(),
            )
        }

        section("Conversación", DinaIcon.CHAT)
        item { SettingToggle("Mostrar la hora", "Añade la hora a los mensajes del modo texto.", settings.showTimestamps) { changed { showTimestamps = it } } }
        item { DinaButton("Nueva conversación", onNewConversation, Modifier.fillMaxWidth(), icon = DinaIcon.REFRESH) }
        item { Caption("Dina olvida lo hablado; tus alarmas, temporizadores y listas se mantienen.", Modifier.padding(start = 4.dp)) }

        section("Datos", DinaIcon.LIST)
        item {
            val bytes = File(context.filesDir, "models").takeIf(File::exists)?.walkTopDown()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
            InfoRow("Espacio de los modelos", if (bytes <= 0) "Se calcula cuando estén listos" else String.format(SPANISH, "%.0f MB", bytes / 1024.0 / 1024.0))
        }
        item {
            DinaButton("Vaciar la lista de la compra", {
                confirm = Confirmation("¿Vaciar la lista?", "Se borrarán todas las cosas de la lista de la compra.", "Vaciar") { graph.tools.execute(ShoppingCommand.Clear) }
            }, Modifier.fillMaxWidth(), icon = DinaIcon.TRASH, style = ButtonStyle.DANGER)
        }
        item {
            DinaButton("Cancelar los temporizadores", {
                confirm = Confirmation("¿Cancelar los temporizadores?", "Se cancelarán todos los temporizadores, también los que están en pausa.", "Cancelarlos") { graph.tools.execute(TimerCommand.CancelAll) }
            }, Modifier.fillMaxWidth(), icon = DinaIcon.TRASH, style = ButtonStyle.DANGER)
        }

        section("Acerca de Dina", DinaIcon.SPARK)
        item {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            InfoRow("Versión", version ?: "—")
        }
        item { InfoRow("Edición", AppVariant.label) }
        item { InfoRow("Modelo", graph.brainSpec.displayName) }
        item { InfoRow("Reconocimiento de voz", if (settings.moonshineStt) "Moonshine Spanish Base, dentro de Dina" else "Android, sin conexión") }
        item { InfoRow("Voz", settings.voice.detail) }
        item { InfoRow("Privacidad", "Todo funciona en el móvil: Dina no tiene permiso de Internet y no guarda audio.") }
        item { InfoRow("Licencias", "Tipografías Fredoka y Nunito (SIL Open Font License 1.1). Voz Supertonic 3 de Supertone (BigScience OpenRAIL-M).") }
        item { DinaButton("Diagnóstico", onOpenDiagnostics, Modifier.fillMaxWidth(), icon = DinaIcon.GEAR) }
    }
    confirm?.let { action ->
        ConfirmDialog(action.title, action.message, action.confirmLabel, { action.run() }) { confirm = null }
    }
}

/** A destructive action waiting for the user's yes. */
private class Confirmation(val title: String, val message: String, val confirmLabel: String, val run: () -> Unit)

/** Both looks side by side; the chosen one moves. */
@Composable
private fun CharacterPicker(selected: CharacterDesign, onSelect: (CharacterDesign) -> Unit) {
    val c = Dina.colors
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CharacterDesign.entries.forEach { design ->
            val active = design == selected
            Column(
                Modifier
                    .weight(1f)
                    .sketchBackground(if (active) c.accentSoft else c.surface, if (active) c.accent else c.line, c.shadow, seed = 80 + design.ordinal)
                    .selectable(active, role = Role.RadioButton) { onSelect(design) }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DinaCharacter(VoiceState.IDLE, Modifier.size(112.dp), design = design, animated = active, describe = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (active) SketchIcon(DinaIcon.CHECK, null, Modifier.padding(end = 4.dp), tint = c.accent, size = 18.dp)
                    Text(design.label, style = Dina.type.heading, color = c.ink)
                }
                Caption(design.pitch, Modifier.padding(top = 2.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(metrics: PerformanceMetrics, wake: WakeDiagnosticEvent?, debugError: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val metricsText = formatMetrics(metrics)
    val wakeText = wake?.let { event ->
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.timestampMs))
        String.format(SPANISH, "Confianza: %.4f\nUmbral: %.2f\nDecisión: %s\nHora: %s",
            event.confidence, event.threshold, if (event.detected) "activada" else "casi (no activada)", time)
    } ?: "Activa «Registrar intentos» y di «Dina»."
    val settings = context.dina.settings
    fun changed(block: DinaSettings.() -> Unit) {
        settings.block()
        DinaService.settingsChanged(context)
    }
    ScreenScaffold("Diagnóstico", onClose, subtitle = "Medidas de la última respuesta en este móvil.") {
        item { DinaButton("Medir una respuesta", { DinaService.benchmark(context) }, Modifier.fillMaxWidth(), icon = DinaIcon.PLAY, style = ButtonStyle.PRIMARY) }
        item { Caption("Dina responde a «¿Qué hora es?» en voz alta y mide cada etapa.", Modifier.padding(start = 4.dp)) }
        item {
            DinaCard {
                Overline("Tiempo hasta la voz")
                Text(latencyText(metrics), Modifier.padding(top = 8.dp), style = Dina.type.mono, color = Dina.colors.ink)
            }
        }
        item {
            DinaCard {
                Overline("Detalle")
                Text(metricsText, Modifier.padding(top = 8.dp), style = Dina.type.mono, color = Dina.colors.ink)
            }
        }
        item { SectionHeader("Rendimiento", Modifier.padding(top = 8.dp), icon = DinaIcon.GEAR) }
        item { SliderRow("Hilos del modelo", settings.llmThreads.toFloat(), 2f..8f, { "${it.toInt()} hilos" }, steps = 5) { changed { llmThreads = it.toInt() } } }
        item { SliderRow("Hilos de la voz", settings.voiceThreads.toFloat(), 1f..8f, { "${it.toInt()} hilos" }, steps = 6) { changed { voiceThreads = it.toInt() } } }
        item {
            SettingChoice(OrtProvider.entries.map { it.name to it.label }, settings.voiceProvider.name) { name -> changed { voiceProvider = OrtProvider.valueOf(name) } }
        }
        item { Caption("Motor de la voz Supertonic. Cambiar los hilos o el motor recarga la voz; compara «Primera síntesis».", Modifier.padding(start = 4.dp)) }
        item { SectionHeader("Palabra «Dina»", Modifier.padding(top = 8.dp), icon = DinaIcon.MIC) }
        item { SettingToggle("Registrar intentos", "Guarda la confianza de cada activación y de cada casi activación, nunca audio.", settings.wakeDiagnosticEnabled) { changed { wakeDiagnosticEnabled = it } } }
        item {
            DinaCard {
                Overline("Último intento")
                Text(wakeText, Modifier.padding(vertical = 8.dp), style = Dina.type.mono, color = Dina.colors.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(Dina.spacing.s)) {
                    DinaButton("Era «Dina»", { DinaService.labelWake(context, "positive") }, Modifier.weight(1f), icon = DinaIcon.CHECK)
                    DinaButton("No lo era", { DinaService.labelWake(context, "negative") }, Modifier.weight(1f), icon = DinaIcon.CLOSE)
                }
            }
        }
        if (debugError.isNotBlank()) item {
            DinaCard {
                Overline("Último error", color = Dina.colors.danger)
                Text(debugError, Modifier.padding(top = 6.dp), style = Dina.type.mono, color = Dina.colors.danger)
            }
        }
        item {
            DinaButton("Copiar diagnóstico", {
                val events = File(context.filesDir, "wakeword-diagnostics/events.jsonl").takeIf(File::isFile)?.readText()?.takeLast(30_000).orEmpty()
                val content = listOf(latencyText(metrics), metricsText, wakeText, debugError, events).filter(String::isNotBlank).joinToString("\n\n")
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diagnóstico de Dina", content))
            }, Modifier.fillMaxWidth(), icon = DinaIcon.LIST)
        }
    }
}

/** One of several options as a pill switch; like [SettingToggle], it keeps its own state. */
@Composable
private fun SettingChoice(options: List<Pair<String, String>>, initial: String, onChange: (String) -> Unit) {
    var selected by remember { mutableStateOf(options.indexOfFirst { it.first == initial }.coerceAtLeast(0)) }
    PillSwitch(options.map { it.second to null }, selected, { selected = it; onChange(options[it].first) }, Modifier.fillMaxWidth())
}

/** SharedPreferences are not observable, so each switch keeps its own state. */
@Composable
private fun SettingToggle(title: String, detail: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    ToggleRow(title, detail, checked) { checked = it; onChange(it) }
}

private fun LazyListScope.section(title: String, icon: DinaIcon) = item { SectionHeader(title, Modifier.padding(top = 8.dp), icon = icon) }

@Composable
private fun SliderRow(
    title: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    steps: Int = 0,
    onCommit: (Float) -> Unit,
) {
    var value by remember { mutableFloatStateOf(initial.coerceIn(range)) }
    DinaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = Dina.type.bodyStrong, color = Dina.colors.ink)
            Text(format(value), style = Dina.type.label, color = Dina.colors.accent)
        }
        DinaSlider(value, { value = it }, range, steps = steps, onValueChangeFinished = { onCommit(value) })
    }
}

/** The path from the final transcript to Dina's first audio, stage by stage. */
private fun latencyText(m: PerformanceMetrics): String {
    fun ms(value: Double?) = value?.let { "%.0f ms".format(it) } ?: "—"
    val prefill = if (m.tokenizationMs == null && m.prefillMs == null) null else (m.tokenizationMs ?: 0.0) + (m.prefillMs ?: 0.0)
    val rest = if (m.turnWaitMs == null && m.turnBookkeepingMs == null) null else (m.turnWaitMs ?: 0.0) + (m.turnBookkeepingMs ?: 0.0)
    return listOf(
        "Petición" to ms(m.sttFinalToPromptReadyMs),
        "Lectura del modelo" to ms(prefill) + (m.promptTokens?.let { " · ${m.reusedPromptTokens ?: 0}/$it en caché" } ?: ""),
        "Escritura del modelo" to ms(m.decodeMs) + (m.completionTokens?.let { " · $it tokens" } ?: ""),
        "Motor y respuesta" to ms(m.toolMs),
        "Resto" to ms(rest),
        "Primera síntesis" to ms(m.firstSynthesisMs) + (m.sentences?.let { " · $it frases" } ?: ""),
        "  por etapas" to if (m.ttsEstimatorMs == null) "—" else
            "duración ${ms(m.ttsDurationMs)} · codificador ${ms(m.ttsEncoderMs)} · estimador ${ms(m.ttsEstimatorMs)} · vocoder ${ms(m.ttsVocoderMs)}",
        "Silencio recortado al inicio" to ms(m.ttsLeadTrimmedMs),
        "Inicio del audio" to ms(m.audioStartMs),
        "TOTAL" to ms(m.transcriptToFirstAudioMs),
        "Huecos entre frases" to ms(m.ttsGapMs),
        "Caché de la voz" to (m.ttsCachedSentences?.let { n -> "$n de ${m.sentences ?: n} frases" + (m.ttsCacheReadMs?.let { " · lectura ${ms(it)}" } ?: "") } ?: "—"),
        "Fin del audio visto por" to m.audioEndClock.ifBlank { "—" },
        "Preparado mientras hablas" to ms(m.prepareMs),
        "Voz" to m.voice.ifBlank { "—" },
        "Variante" to m.variant.ifBlank { "—" },
    ).joinToString("\n") { "${it.first}: ${it.second}" }
}

private fun formatMetrics(m: PerformanceMetrics): String {
    fun ms(value: Double?) = value?.let { "%.0f ms".format(it) } ?: "—"
    return listOf(
        "«Dina» → activación" to ms(m.wakeToActivationMs),
        "Fin de tu voz → detección" to ms(m.voiceEndToVadMs),
        "Detección → texto" to ms(m.vadToSttFinalMs),
        "Fin de tu voz → texto" to ms(m.endOfSpeechToTranscriptMs),
        "Texto → petición lista" to ms(m.sttFinalToPromptReadyMs),
        "Tokenización" to ms(m.tokenizationMs),
        "Lectura del modelo" to ms(m.prefillMs),
        "Hasta el primer token" to ms(m.timeToFirstTokenMs),
        "Texto → primer token" to ms(m.sttToFirstTokenMs),
        "Generación" to ms(m.generationMs),
        "Velocidad" to (m.tokensPerSecond?.let { "%.1f tokens/s".format(it) } ?: "—"),
        "Motor" to ms(m.toolMs),
        "Carga del reconocimiento" to ms(m.sttInitMs),
        "Carga de la voz" to ms(m.ttsInitMs),
        "Calentamiento de la voz" to ms(m.ttsWarmupMs),
        "Salida de audio" to ms(m.audioTrackInitMs),
        "Síntesis de voz" to ms(m.ttsMs),
        "Respuesta → primer audio" to ms(m.ttsToFirstAudioMs),
        "RTF de la voz" to (m.ttsRtf?.let { "%.3f".format(it) } ?: "—"),
        "Fin de tu voz → audio" to ms(m.endOfSpeechToFirstAudioMs),
        "RAM (PSS)" to (m.ramMb?.let { "%.0f MB".format(it) } ?: "—"),
        "Temperatura" to (m.cpuTemperatureC?.let { "%.1f °C".format(it) } ?: "no disponible"),
        "CPU aproximada" to (m.cpuPercent?.let { "%.0f %%".format(it) } ?: "—"),
        "Contexto" to "${m.promptTokens ?: 0} de entrada (${m.reusedPromptTokens ?: 0} en caché) · ${m.completionTokens ?: 0} de salida",
        "Tamaño del modelo" to (m.modelBytes?.let { "%.1f MB".format(it / 1024.0 / 1024.0) } ?: "—"),
        "Motor de inferencia" to m.backend,
        "Carga del modelo" to ms(m.modelLoadMs),
    ).joinToString("\n") { "${it.first}: ${it.second}" }
}
