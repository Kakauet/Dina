package com.kakauet.dina.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.kakauet.dina.config.DinaSettings
import com.kakauet.dina.core.ConversationController
import com.kakauet.dina.core.DinaStore
import com.kakauet.dina.core.MessageRole
import com.kakauet.dina.core.TurnOrigin
import com.kakauet.dina.core.TurnOutcome
import com.kakauet.dina.core.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * The voice loop: microphone → wake word "Dina" → speech (Android on-device STT or Moonshine)
 * → [ConversationController] → voice (Supertonic or Piper, sentence by sentence) → follow-up
 * window → standby. While the user speaks, the brain prefills its prompt ([ConversationController.prepare]).
 *
 * The microphone is ignored while Dina thinks or speaks, so she never wakes herself up.
 */
class VoicePipeline(
    private val context: Context,
    private val settings: DinaSettings,
    private val store: DinaStore,
    private val controller: ConversationController,
    /** Moonshine, only when it is the chosen recognizer. */
    private val stt: MoonshineStt?,
    private val voice: SpeechVoice,
    private val wake: WakeWordDetector,
    private val scope: CoroutineScope,
    /** Policy from the service: voice mode on, Dina active and app visible (or background allowed). */
    private val mayListen: () -> Boolean,
) : AutoCloseable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val output = AudioOutput()
    private val diagnostics = WakeDiagnostics(context, settings, store)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val vad = FastVad()
    private val wakeDecision = WakeDecision()
    private var recorder: AudioRecord? = null
    private var audioJob: Job? = null
    private var turnJob: Job? = null
    private var nativeRecognizer: SpeechRecognizer? = null
    private var nativeGeneration = 0L
    private var nativeStartedNs = 0L
    private var nativeSpeechEndNs = 0L
    private var followDeadlineNs = 0L
    private var activationDeadlineNs = 0L
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
            .setOnAudioFocusChangeListener { }
            .build()
    }

    /** A voice turn (STT, thinking or speaking) is in progress. */
    val isTurnActive get() = turnJob?.isActive == true

    val wakeDiagnostics get() = diagnostics

    /** Starts waiting for "Dina" (or for speech, in Moonshine follow-up). */
    fun startListening() {
        if (audioJob?.isActive == true) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            store.setState(VoiceState.ERROR, "Dina necesita permiso para usar el micrófono.")
            return
        }
        recorder = openRecorder() ?: run {
            store.setState(VoiceState.ERROR, "No puedo usar el micrófono: puede que otra app lo esté usando.")
            return
        }

        audioJob = scope.launch(Dispatchers.IO) {
            val frame = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
            while (isActive) {
                // Waiting for "Dina" reads 80 ms at a time (the detector's step, fewer wake-ups); a conversation, 20 ms.
                val idle = store.session.value.state == VoiceState.IDLE
                val count = recorder?.read(frame, 0, if (idle) frame.size else 320, AudioRecord.READ_BLOCKING) ?: break
                if (count <= 0) continue
                val now = System.nanoTime()
                publishAudioLevel(frame, count)
                when (store.session.value.state) {
                    VoiceState.IDLE -> wake.accept(frame, count) { score -> onWakeScore(score, now) }
                    VoiceState.ACTIVATED, VoiceState.FOLLOW_UP, VoiceState.LISTENING -> {
                        when (val event = vad.accept(frame, count, now)) {
                            FastVad.Event.SpeechStarted -> { store.setState(VoiceState.LISTENING); prepareBrain() }
                            is FastVad.Event.SpeechEnded -> turnJob = scope.launch { processUtterance(event.pcm, event.speechEndNs, event.detectedNs) }
                            FastVad.Event.None -> Unit
                        }
                        if (!vad.speaking && now >= activeDeadline()) endSession()
                    }
                    else -> Unit // During inference and TTS the microphone is intentionally ignored.
                }
            }
        }
    }

    /** Stops the microphone and any recognition in progress. */
    fun stopListening() {
        stopAudioLoop()
        stopNativeRecognition()
    }

    /** Closes the current voice session: back to waiting for "Dina". */
    fun endSession() {
        stopNativeRecognition()
        controller.endSession()
        vad.reset(); wake.reset(); wakeDecision.reset()
        audioManager.abandonAudioFocusRequest(focusRequest)
        store.setState(VoiceState.IDLE)
        if (mayListen()) startListening()
    }

    /** Measures the whole SLM + voice path on device without using the microphone. */
    fun runBenchmark() {
        if (isTurnActive || controller.isBusy) return
        val startedNs = System.nanoTime()
        val cpuStarted = android.os.Process.getElapsedCpuTime()
        turnJob = scope.launch {
            store.setState(VoiceState.THINKING, "Midiendo una respuesta…")
            try {
                controller.prepare()
                val turnStartNs = System.nanoTime()
                val outcome = controller.runTurn(BENCHMARK_PHRASE, TurnOrigin.BENCHMARK)
                if (outcome !is TurnOutcome.Answered) error((outcome as? TurnOutcome.Failed)?.error?.message ?: "Dina está ocupada")
                store.setState(VoiceState.SPEAKING)
                speakAnswer(outcome.answer, turnStartNs, outcome.endNs, startedNs)
                store.updateMetrics {
                    it.copy(ramMb = DeviceStats.memoryMb(), cpuPercent = DeviceStats.cpuPercent(cpuStarted, startedNs), cpuTemperatureC = DeviceStats.temperatureC())
                }
                endSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                store.setState(VoiceState.ERROR, "No he podido medir la respuesta: ${error.message}")
            }
        }
    }

    override fun close() {
        stopListening()
        turnJob?.cancel()
        output.close()
    }

    // ---- Wake word ----

    private fun onWakeScore(score: Float, now: Long) {
        if (store.session.value.state != VoiceState.IDLE) return
        val detected = wakeDecision.accept(score, settings.wakeSensitivity.threshold)
        diagnostics.observe(score, detected)
        if (detected) activate(now)
    }

    /** [heardNs]: when the audio that completed "Dina" was read. */
    private fun activate(heardNs: Long) {
        audioManager.requestAudioFocus(focusRequest)
        wake.reset(); vad.reset(); wakeDecision.reset()
        activationDeadlineNs = System.nanoTime() + 12_000_000_000L
        store.updateSession {
            it.copy(state = VoiceState.ACTIVATED, transcript = "", response = "", detail = VoiceState.ACTIVATED.label)
        }
        store.updateMetrics { it.copy(wakeToActivationMs = (System.nanoTime() - heardNs) / 1_000_000.0) }
        prepareBrain() // the state is known now, before the user starts the phrase
        if (settings.moonshineStt) {
            startListening() // Moonshine keeps using our AudioRecord + VAD.
        } else {
            stopAudioLoop() // Android's recognizer needs the microphone for itself.
            startNativeRecognition()
        }
    }

    // ---- Turn ----

    private suspend fun processUtterance(pcm: ShortArray, endNs: Long, vadDetectedNs: Long) {
        try {
            store.setState(VoiceState.TRANSCRIBING)
            val (text, sttMs) = withContext(Dispatchers.Default) { checkNotNull(stt) { "El modelo Moonshine no está activado" }.transcribe(pcm) }
            processTranscript(text, sttMs, endNs, vadDetectedNs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            recover(error)
        }
    }

    private suspend fun processTranscript(text: String, sttMs: Double, endNs: Long, vadDetectedNs: Long) {
        val wallStartNs = System.nanoTime()
        val cpuStartMs = android.os.Process.getElapsedCpuTime()
        try {
            store.setState(VoiceState.TRANSCRIBING)
            if (text.isBlank()) { openFollowUp(); return }
            val sttDoneNs = System.nanoTime()
            store.updateMetrics {
                it.copy(
                    sttMs = sttMs,
                    voiceEndToVadMs = (vadDetectedNs - endNs) / 1_000_000.0,
                    vadToSttFinalMs = (sttDoneNs - vadDetectedNs) / 1_000_000.0,
                    endOfSpeechToTranscriptMs = (sttDoneNs - endNs) / 1_000_000.0,
                )
            }
            val outcome = controller.runTurn(text, TurnOrigin.VOICE)
            if (outcome is TurnOutcome.Failed) { recover(outcome.error, recordMessage = false); return }
            if (outcome !is TurnOutcome.Answered) { openFollowUp(); return }
            store.updateMetrics {
                it.copy(sttToFirstTokenMs = if (outcome.firstTokenNs > sttDoneNs) (outcome.firstTokenNs - sttDoneNs) / 1_000_000.0 else it.timeToFirstTokenMs)
            }
            if (!settings.respondByVoice) { openFollowUp(); return }
            store.setState(VoiceState.SPEAKING)
            speakAnswer(outcome.answer, sttDoneNs, outcome.endNs, endNs)
            openFollowUp() // The follow-up clock starts only after the audio has finished.
            // After opening the microphone: Debug.getMemoryInfo reads /proc/<pid>/smaps and can take tens of ms.
            scope.launch(Dispatchers.Default) {
                store.updateMetrics {
                    it.copy(ramMb = DeviceStats.memoryMb(), cpuPercent = DeviceStats.cpuPercent(cpuStartMs, wallStartNs), cpuTemperatureC = DeviceStats.temperatureC())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            recover(error)
        }
    }

    /**
     * Speaks [answer] sentence by sentence: the first plays as soon as it is synthesized and the
     * rest are synthesized while it plays. Records the voice part of the latency from [transcriptNs].
     */
    @OptIn(ExperimentalCoroutinesApi::class) // produce
    private suspend fun speakAnswer(answer: String, transcriptNs: Long, turnEndNs: Long, speechEndNs: Long) {
        val synthesized = mutableListOf<SpeechAudio>()
        // Seconds of synthesis per second of audio on this phone, from the last answer that was not cached.
        val rtf = store.metrics.value.ttsRtf?.takeIf { it > 0.0 } ?: SpeechPlanner.DEFAULT_RTF
        val playback = coroutineScope {
            val sentences = speechSentences(voice, answer, rtf = rtf)
            val recorded = produce(capacity = 1) { for (audio in sentences) { synthesized += audio; send(audio) } }
            output.play(recorded, settings.voiceVolume)
        }
        wake.reset(); vad.reset(); wakeDecision.reset()
        val first = synthesized.firstOrNull() ?: return
        playback ?: return
        val made = synthesized.filterNot { it.cached }
        val synthesisMs = made.sumOf { it.synthesisMs }
        val audioSeconds = made.sumOf { it.durationSeconds }
        val cachedReads = synthesized.filter { it.cached }
        store.updateMetrics {
            it.copy(
                firstSynthesisMs = first.synthesisMs,
                audioStartMs = (playback.firstAudioNs - playback.firstReadyNs) / 1_000_000.0,
                transcriptToFirstAudioMs = (playback.firstAudioNs - transcriptNs) / 1_000_000.0,
                ttsMs = synthesisMs,
                audioTrackInitMs = playback.initMs,
                ttsToFirstAudioMs = (playback.firstAudioNs - turnEndNs) / 1_000_000.0,
                // A cache hit costs nothing to synthesize: keep the last real figure for the planner.
                ttsRtf = if (audioSeconds > 0.0) (synthesisMs / 1000.0) / audioSeconds else it.ttsRtf,
                endOfSpeechToFirstAudioMs = (playback.firstAudioNs - speechEndNs) / 1_000_000.0,
                sentences = synthesized.size,
                voice = voice.description,
                ttsDurationMs = first.stages?.durationMs, ttsEncoderMs = first.stages?.encoderMs,
                ttsEstimatorMs = first.stages?.estimatorMs, ttsVocoderMs = first.stages?.vocoderMs,
                ttsLeadTrimmedMs = first.trimmedLeadMs.takeIf { _ -> !first.cached },
                ttsGapMs = playback.gapMs,
                ttsCachedSentences = cachedReads.size,
                ttsCacheReadMs = cachedReads.takeIf { it.isNotEmpty() }?.map { it.synthesisMs }?.average(),
                audioEndClock = playback.endClock,
            )
        }
    }

    /** Lets the brain prefill its prompt while the user speaks (never blocks the audio loop). */
    private fun prepareBrain() {
        scope.launch(Dispatchers.Default) { controller.prepare() }
    }

    private suspend fun recover(error: Throwable, recordMessage: Boolean = true) {
        Log.w(TAG, "Voice turn failed", error)
        // Brain failures are already recorded by the controller; audio failures are recorded here.
        if (recordMessage) store.appendMessage(MessageRole.DINA, ConversationController.FAILED)
        store.updateSession {
            it.copy(
                response = if (recordMessage) ConversationController.FAILED else it.response,
                debugError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }
        store.setState(VoiceState.ERROR, "No he podido completarlo. Inténtalo otra vez.")
        delay(1_500)
        openFollowUp()
    }

    private fun openFollowUp() {
        followDeadlineNs = System.nanoTime() + settings.followUpTimeoutMs * 1_000_000L
        vad.reset()
        store.setState(VoiceState.FOLLOW_UP)
        if (settings.moonshineStt) {
            if (mayListen()) startListening()
        } else {
            stopAudioLoop()
            startNativeRecognition()
        }
    }

    private fun activeDeadline(): Long =
        if (store.session.value.state == VoiceState.FOLLOW_UP) followDeadlineNs else activationDeadlineNs

    private fun stopAudioLoop() {
        audioJob?.cancel()
        audioJob = null
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        vad.reset(); wake.reset(); wakeDecision.reset()
    }

    /** The microphone, or null when Android does not give it (another app holds it, a policy forbids it). */
    @SuppressLint("MissingPermission") // checked by startListening
    private fun openRecorder(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE * 2))
                .build()
        }.getOrNull() ?: return null
        val started = record.state == AudioRecord.STATE_INITIALIZED && runCatching { record.startRecording() }.isSuccess &&
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) { record.release(); return null }
        return record
    }

    private fun publishAudioLevel(frame: ShortArray, count: Int) {
        var sum = 0.0
        for (i in 0 until count) sum += frame[i].toDouble() * frame[i]
        store.pushAudioLevel((sqrt(sum / count) / 5_000.0).toFloat())
    }

    // ---- Android on-device recognizer (default STT) ----

    /** SpeechRecognizer must be driven from the main looper. */
    private fun startNativeRecognition() {
        if (settings.moonshineStt) return
        val action = Runnable {
            if (!controller.isReady || !mayListen()) return@Runnable
            val state = store.session.value.state
            if (state != VoiceState.ACTIVATED && state != VoiceState.FOLLOW_UP) return@Runnable
            stopNativeRecognitionOnMain()
            val recognizer = createNativeRecognizer()
            if (recognizer == null) {
                store.setState(VoiceState.ERROR, "Este móvil no tiene reconocimiento de voz sin conexión. Activa Moonshine en Ajustes › Escucha.")
                return@Runnable
            }
            nativeRecognizer = recognizer
            val generation = ++nativeGeneration
            nativeStartedNs = System.nanoTime()
            nativeSpeechEndNs = 0L
            recognizer.setRecognitionListener(recognitionListener(generation))
            store.setState(VoiceState.ACTIVATED)
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            })
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    /** Only on-device recognition: the generic recognizer may send audio to a network service. */
    private fun createNativeRecognizer(): SpeechRecognizer? = runCatching {
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
    }.getOrNull()

    private fun recognitionListener(generation: Long) = object : RecognitionListener {
        private fun isCurrent() = generation == nativeGeneration

        override fun onReadyForSpeech(params: Bundle?) { if (isCurrent()) store.setState(VoiceState.ACTIVATED) }
        override fun onBeginningOfSpeech() {
            if (!isCurrent()) return
            store.setState(VoiceState.LISTENING)
            prepareBrain()
        }
        override fun onRmsChanged(rmsdB: Float) { if (isCurrent()) store.pushAudioLevel((rmsdB + 2f) / 12f) }
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!isCurrent()) return
            nativeSpeechEndNs = System.nanoTime()
            store.setState(VoiceState.TRANSCRIBING)
            prepareBrain() // again: a running timer may have ticked while the user spoke
        }

        override fun onError(error: Int) {
            if (!isCurrent()) return
            finishNativeSession(generation)
            Log.w(TAG, "SpeechRecognizer error=$error")
            endSession()
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent()) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            val endNs = nativeSpeechEndNs.takeIf { it > 0L } ?: System.nanoTime()
            val sttMs = (System.nanoTime() - nativeStartedNs) / 1_000_000.0
            finishNativeSession(generation)
            if (text.isBlank()) openFollowUp()
            else if (!isTurnActive) turnJob = scope.launch { processTranscript(text, sttMs, endNs, endNs) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent()) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) store.updateSession { it.copy(transcript = text) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun finishNativeSession(generation: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finishNativeSession(generation) }
            return
        }
        if (generation != nativeGeneration) return
        nativeGeneration++
        nativeRecognizer?.runCatching { destroy() }
        nativeRecognizer = null
    }

    private fun stopNativeRecognition() {
        if (Looper.myLooper() == Looper.getMainLooper()) stopNativeRecognitionOnMain() else mainHandler.post { stopNativeRecognitionOnMain() }
    }

    private fun stopNativeRecognitionOnMain() {
        nativeGeneration++
        nativeRecognizer?.runCatching { cancel() }
        nativeRecognizer?.runCatching { destroy() }
        nativeRecognizer = null
        nativeStartedNs = 0L
        nativeSpeechEndNs = 0L
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val TAG = "DinaVoice"
        /** Diagnóstico's measurement: a real request that runs a tool and speaks one short sentence. */
        const val BENCHMARK_PHRASE = "¿Qué hora es?"
    }
}
