package com.kakauet.dina.voice

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import androidx.core.app.NotificationCompat
import com.kakauet.dina.brain.EngineExecutor
import com.kakauet.dina.DinaApplication
import com.kakauet.dina.config.AppVariant
import com.kakauet.dina.core.ConversationController
import com.kakauet.dina.core.TurnOrigin
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.dina
import com.kakauet.dina.llm.NativeLlmEngine
import com.kakauet.dina.models.ModelInstaller
import com.kakauet.dina.tools.DinaTools
import com.kakauet.dina.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns Dina's models while the app is visible (or always, with
 * background listening). It only handles Android lifecycle: loading, notification, wake lock
 * and commands. Turns run in [com.kakauet.dina.core.ConversationController]; audio in [VoicePipeline].
 */
class DinaService : Service() {
    private val graph by lazy { dina }
    private val settings get() = graph.settings
    private val store get() = graph.store
    private val controller get() = graph.controller

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var initialization: Job? = null
    private var textJob: Job? = null
    private var llm: NativeLlmEngine? = null
    private var stt: MoonshineStt? = null
    private var voice: SpeechVoice? = null
    private var polished: PolishedVoice? = null
    private var prewarm: Job? = null
    private var wake: WakeWordDetector? = null
    private var pipeline: VoicePipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var loadedMoonshine = false
    private var initializingMoonshine: Boolean? = null
    /** What the loaded voice was built with: a different choice, thread count or provider reloads it. */
    private var loadedVoice: Triple<VoiceChoice, Int, OrtProvider>? = null
    private var voiceEnabled = true
    private var appVisible = true
    private var benchmarkPending = false
    private val pendingText = ArrayDeque<String>()

    private val ready get() = store.session.value.ready
    private val turnInProgress get() = controller.isBusy || pipeline?.isTurnActive == true || textJob?.isActive == true

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIFICATION_ID, notification("Preparando Dina…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        updateWakeLock()
        scope.launch { store.session.map { it.state }.distinctUntilChanged().collect { updateNotification() } }
        initialization = scope.launch { initialize() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pipeline?.stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_BENCHMARK -> {
                benchmarkPending = true
                if (ready && !turnInProgress) runBenchmark()
            }
            ACTION_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                if (ready && !turnInProgress) runTextTurn(text) else synchronized(pendingText) { pendingText.addLast(text) }
            }
            ACTION_NEW_CONVERSATION -> {
                pipeline?.stopListening()
                controller.newConversation()
                if (ready) {
                    store.setState(VoiceState.IDLE)
                    if (mayListen() && !settings.moonshineStt) pipeline?.startListening()
                }
            }
            ACTION_PAUSE_VOICE -> {
                voiceEnabled = false
                pipeline?.stopListening()
                if (ready) store.setState(VoiceState.IDLE, ConversationController.TEXT_IDLE)
                updateNotification()
            }
            ACTION_RESUME_VOICE -> {
                voiceEnabled = true
                if (ready) {
                    pipeline?.startListening()
                    store.setState(VoiceState.IDLE)
                }
                updateNotification()
            }
            ACTION_RETRY -> if (initialization?.isActive != true) {
                pipeline?.stopListening()
                initialization = scope.launch { initialize() }
            }
            ACTION_APP_FOREGROUND -> {
                appVisible = true
                if (ready && mayListen()) pipeline?.startListening() else if (!voiceEnabled) pipeline?.stopListening()
                updateWakeLock()
            }
            ACTION_APP_BACKGROUND -> {
                appVisible = false
                if (!settings.backgroundListening) {
                    pipeline?.stopListening()
                    stopSelf()
                } else {
                    if (ready && settings.dinaActive) pipeline?.startListening()
                    updateWakeLock()
                }
            }
            ACTION_SETTINGS_CHANGED -> onSettingsChanged()
            ACTION_WAKE_DIAGNOSTIC_LABEL -> intent.getStringExtra(EXTRA_WAKE_LABEL)?.let { pipeline?.wakeDiagnostics?.label(it) }
        }
        return if (settings.backgroundListening) START_STICKY else START_NOT_STICKY
    }

    private fun onSettingsChanged() {
        val desiredMoonshine = settings.moonshineStt
        // Applied in place: Supertonic's steps and llama.cpp's threads.
        (voice as? SupertonicVoice)?.steps = settings.supertonicSteps
        llm?.setThreads(settings.llmThreads)
        val modelsChanged = loadedMoonshine != desiredMoonshine ||
            (initializingMoonshine != null && initializingMoonshine != desiredMoonshine) ||
            (ready && loadedVoice != voiceSettings())
        if (modelsChanged) {
            pipeline?.stopListening()
            initialization?.cancel()
            initialization = scope.launch { initialize() }
        } else if (!settings.dinaActive) {
            pipeline?.stopListening()
        } else if (mayListen() && (settings.moonshineStt || store.session.value.state == VoiceState.IDLE)) {
            pipeline?.startListening()
        }
        if (!appVisible && !settings.backgroundListening) stopSelf()
        updateWakeLock()
        updateNotification()
    }

    private fun mayListen() = voiceEnabled && settings.dinaActive && (appVisible || settings.backgroundListening)

    private fun voiceSettings() = Triple(settings.voice, settings.voiceThreads, settings.voiceProvider)

    private suspend fun initialize() {
        var stage = "preparar el almacenamiento"
        val useMoonshine = settings.moonshineStt
        val spec = graph.brainSpec
        initializingMoonshine = useMoonshine
        try {
            store.updateSession { it.copy(state = VoiceState.LOADING, detail = "Comprobando el almacenamiento…", ready = false, debugError = "") }
            releaseModels()
            stage = "copiar los modelos locales"
            val (choice, voiceThreads, provider) = voiceSettings()
            val files = withContext(Dispatchers.IO) {
                ModelInstaller(this@DinaService).install(spec.model, spec.displayName, includeMoonshine = useMoonshine, voice = choice) { detail ->
                    store.setState(VoiceState.LOADING, detail)
                }
            }
            val loadStart = System.nanoTime()
            stage = "cargar ${spec.displayName}"
            store.setState(VoiceState.LOADING, "Cargando ${spec.displayName}…")
            val llmThreads = settings.llmThreads
            val engine = NativeLlmEngine().also { llm = it }
            engine.load(files.llm.absolutePath, llmThreads, spec.model.contextSize)
            val loadedStt = if (!useMoonshine) null else withContext(Dispatchers.IO) {
                stage = "cargar Moonshine Spanish Base"
                store.setState(VoiceState.LOADING, "Cargando Moonshine Spanish Base…")
                MoonshineStt(files)
            }.also { stt = it }
            stage = "cargar la voz ${choice.label}"
            store.setState(VoiceState.LOADING, "Cargando la voz ${choice.label}…")
            val speechVoice = withContext(Dispatchers.IO) {
                when (choice) {
                    VoiceChoice.SUPERTONIC_F2 -> SupertonicVoice(files.supertonic, "F2", voiceThreads, provider, settings.supertonicSteps)
                    VoiceChoice.PIPER_SHARVARD -> PiperVoice(this@DinaService, files)
                }
            }.also { voice = it }
            stage = "cargar el detector de «Dina»"
            store.setState(VoiceState.LOADING, "Cargando el detector de «Dina»…")
            val loadedWake = withContext(Dispatchers.IO) { WakeWordDetector(files) }.also { wake = it }
            controller.attach(spec.create(engine, EngineExecutor(graph.tools)))
            // The pipeline hears the finished voice: silence cut, sentences cached on disk (cacheDir/speech).
            val finishedVoice = PolishedVoice(speechVoice, SilenceTrim.Config(), LruSpeechCache(directory = File(cacheDir, "speech"))).also { polished = it }
            pipeline = VoicePipeline(this, settings, store, controller, loadedStt, finishedVoice, loadedWake, scope, ::mayListen)
            loadedMoonshine = useMoonshine
            loadedVoice = Triple(choice, voiceThreads, provider)
            store.updateSession { it.copy(state = VoiceState.IDLE, detail = VoiceState.IDLE.label, ready = true) }
            store.updateMetrics {
                it.copy(
                    modelLoadMs = (System.nanoTime() - loadStart) / 1_000_000.0,
                    sttInitMs = loadedStt?.initMs, ttsInitMs = speechVoice.initMs, ttsWarmupMs = speechVoice.warmupMs,
                    ramMb = DeviceStats.memoryMb(), cpuTemperatureC = DeviceStats.temperatureC(), modelBytes = files.llm.length(),
                    backend = "llama.cpp CPU · LFM2.5 · ARM64 KleidiAI · $llmThreads hilos",
                    variant = "${AppVariant.label} · ${spec.displayName} (${spec.model.quantization}) · ${AppVariant.fastCores} de ${AppVariant.cores} núcleos rápidos",
                    voice = "${finishedVoice.description} · $voiceThreads hilos",
                )
            }
            if (mayListen()) pipeline?.startListening()
            if (benchmarkPending) runBenchmark()
            drainPendingText()
            // The common short answers, a few seconds later and only while nobody is talking to Dina.
            prewarm = scope.launch {
                delay(PREWARM_DELAY_MS)
                finishedVoice.prewarm(CommonPhrases.ALL) { !isActive || store.session.value.state != VoiceState.IDLE || turnInProgress }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            releaseModels()
            val reason = error.message?.lineSequence()?.firstOrNull()?.take(140).orEmpty()
            Log.e(TAG, "Fallo al $stage", error)
            store.updateSession {
                it.copy(
                    state = VoiceState.ERROR,
                    detail = if (reason.isBlank()) "No he podido $stage." else "No he podido $stage: $reason",
                    ready = false,
                    debugError = error.stackTraceToString(),
                )
            }
        } finally {
            if (initializingMoonshine == useMoonshine) initializingMoonshine = null
        }
    }

    /** Runs [first] and then any text queued meanwhile, in order. */
    private fun runTextTurn(first: String) {
        textJob = scope.launch {
            var next: String? = first
            while (next != null) {
                controller.runTurn(next, TurnOrigin.TEXT)
                next = synchronized(pendingText) { pendingText.removeFirstOrNull() }
            }
        }
    }

    private fun drainPendingText() {
        if (turnInProgress) return
        synchronized(pendingText) { pendingText.removeFirstOrNull() }?.let(::runTextTurn)
    }

    private fun runBenchmark() {
        benchmarkPending = false
        pipeline?.runBenchmark()
    }

    private fun releaseModels() {
        controller.attach(null)
        prewarm?.cancel(); prewarm = null
        runCatching { pipeline?.close() }; pipeline = null
        runCatching { wake?.close() }; wake = null
        // Closing the finished voice waits for a sentence being synthesized (the background prewarm) to end.
        runCatching { (polished ?: voice)?.close() }; polished = null; voice = null
        runCatching { stt?.close() }; stt = null
        loadedVoice = null
        runCatching { llm?.close() }; llm = null
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, DinaService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, DinaApplication.VOICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Dina está activa")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Detener", stop)
            .build()
    }

    private fun updateNotification() {
        val state = store.session.value.state
        val text = when (state) {
            VoiceState.ERROR -> "Abre Dina para revisar el problema."
            VoiceState.IDLE -> if (voiceEnabled) VoiceState.IDLE.label else "Modo texto: el micrófono está en pausa"
            else -> state.label
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun updateWakeLock() {
        val shouldHold = settings.backgroundListening && settings.listenScreenOff && !appVisible
        if (shouldHold && wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dina::WakeWord")
                .apply { setReferenceCounted(false); acquire() }
        } else if (!shouldHold && wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onDestroy() {
        textJob?.cancel(); initialization?.cancel()
        releaseModels()
        DinaTools.flush()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        scope.cancel()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Commands for the service; the UI never holds a reference to it. */
    companion object {
        private const val PREWARM_DELAY_MS = 6_000L
        private const val ACTION_START = "com.kakauet.dina.START"
        private const val ACTION_STOP = "com.kakauet.dina.STOP"
        private const val ACTION_BENCHMARK = "com.kakauet.dina.BENCHMARK"
        private const val ACTION_TEXT = "com.kakauet.dina.TEXT"
        private const val ACTION_NEW_CONVERSATION = "com.kakauet.dina.NEW_CONVERSATION"
        private const val ACTION_PAUSE_VOICE = "com.kakauet.dina.PAUSE_VOICE"
        private const val ACTION_RESUME_VOICE = "com.kakauet.dina.RESUME_VOICE"
        private const val ACTION_RETRY = "com.kakauet.dina.RETRY"
        private const val ACTION_APP_FOREGROUND = "com.kakauet.dina.APP_FOREGROUND"
        private const val ACTION_APP_BACKGROUND = "com.kakauet.dina.APP_BACKGROUND"
        private const val ACTION_SETTINGS_CHANGED = "com.kakauet.dina.SETTINGS_CHANGED"
        private const val ACTION_WAKE_DIAGNOSTIC_LABEL = "com.kakauet.dina.WAKE_DIAGNOSTIC_LABEL"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_WAKE_LABEL = "wake_label"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "DinaService"

        private fun send(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            context.startForegroundService(Intent(context, DinaService::class.java).setAction(action).apply(configure))
        }

        fun start(context: Context) = send(context, ACTION_START)
        fun sendText(context: Context, text: String) = send(context, ACTION_TEXT) { putExtra(EXTRA_TEXT, text) }
        fun newConversation(context: Context) = send(context, ACTION_NEW_CONVERSATION)
        fun pauseVoice(context: Context) = send(context, ACTION_PAUSE_VOICE)
        fun resumeVoice(context: Context) = send(context, ACTION_RESUME_VOICE)
        fun retry(context: Context) = send(context, ACTION_RETRY)
        fun settingsChanged(context: Context) = send(context, ACTION_SETTINGS_CHANGED)
        fun benchmark(context: Context) = send(context, ACTION_BENCHMARK)
        fun labelWake(context: Context, label: String) = send(context, ACTION_WAKE_DIAGNOSTIC_LABEL) { putExtra(EXTRA_WAKE_LABEL, label) }
        fun appForeground(context: Context) = send(context, ACTION_APP_FOREGROUND)

        /** Whether the service exists; going to the background only tells a running one. */
        @Volatile private var running = false

        /** Not a foreground start, and only to a running service: going to the background must not create it. */
        fun appBackground(context: Context) {
            if (running) runCatching { context.startService(Intent(context, DinaService::class.java).setAction(ACTION_APP_BACKGROUND)) }
        }
    }
}
