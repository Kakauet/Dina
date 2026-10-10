package com.kakauet.dina.core

import com.kakauet.dina.tools.ContextualWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VoiceState(val label: String) {
    IDLE("Esperando «Dina»"),
    ACTIVATED("Te escucho"),
    LISTENING("Escuchando"),
    TRANSCRIBING("Transcribiendo"),
    THINKING("Pensando"),
    EXECUTING("Un momento"),
    SPEAKING("Hablando"),
    FOLLOW_UP("Puedes seguir hablando…"),
    LOADING("Preparando Dina"),
    ERROR("Algo ha fallado");

    /** Dina is in the middle of a turn and cannot take another one. */
    val busy get() = this == TRANSCRIBING || this == THINKING || this == EXECUTING || this == SPEAKING
}

enum class MessageRole { USER, DINA }

data class DinaMessage(
    val id: Long,
    val role: MessageRole,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val widget: ContextualWidget? = null,
)

/** What Dina is doing right now. Changes a few times per turn. */
data class SessionState(
    val state: VoiceState = VoiceState.LOADING,
    val detail: String = "Preparando los modelos…",
    val ready: Boolean = false,
    val transcript: String = "",
    val response: String = "",
    val widget: ContextualWidget? = null,
    val debugError: String = "",
)

data class PerformanceMetrics(
    val wakeToActivationMs: Double? = null,
    val voiceEndToVadMs: Double? = null,
    val vadToSttFinalMs: Double? = null,
    val endOfSpeechToTranscriptMs: Double? = null,
    val sttFinalToPromptReadyMs: Double? = null,
    val tokenizationMs: Double? = null,
    val prefillMs: Double? = null,
    val timeToFirstTokenMs: Double? = null,
    val sttToFirstTokenMs: Double? = null,
    val generationMs: Double? = null,
    val tokensPerSecond: Double? = null,
    val toolMs: Double? = null,
    val sttInitMs: Double? = null,
    val ttsInitMs: Double? = null,
    val ttsWarmupMs: Double? = null,
    val audioTrackInitMs: Double? = null,
    val ttsToFirstAudioMs: Double? = null,
    val ttsRtf: Double? = null,
    val endOfSpeechToFirstAudioMs: Double? = null,
    val ramMb: Double? = null,
    val cpuPercent: Double? = null,
    val cpuTemperatureC: Double? = null,
    val promptTokens: Int? = null,
    val reusedPromptTokens: Int? = null,
    val completionTokens: Int? = null,
    val modelBytes: Long? = null,
    val backend: String = "llama.cpp CPU · LFM2.5 · ARM64 KleidiAI · 6 hilos",
    val modelLoadMs: Double? = null,
    val sttMs: Double? = null,
    val ttsMs: Double? = null,
    // End of transcript → Dina starts speaking, by stage (Diagnóstico › Latencia).
    /** Prompt prefilled while the user spoke (not on the turn's path). */
    val prepareMs: Double? = null,
    /** Waiting for that prefill to finish, plus recording the user message. */
    val turnWaitMs: Double? = null,
    /** LLM time after the prompt is processed (first token sampling + the rest of the output). */
    val decodeMs: Double? = null,
    /** Conversation, widget and state updates after the brain answered. */
    val turnBookkeepingMs: Double? = null,
    val firstSynthesisMs: Double? = null,
    /** First sentence synthesized → audio playing (AudioTrack setup and first write). */
    val audioStartMs: Double? = null,
    val transcriptToFirstAudioMs: Double? = null,
    val sentences: Int? = null,
    val voice: String = "",
    // First sentence of the last answer, by synthesis stage (Diagnóstico › Latencia).
    val ttsDurationMs: Double? = null,
    val ttsEncoderMs: Double? = null,
    val ttsEstimatorMs: Double? = null,
    val ttsVocoderMs: Double? = null,
    /** Silence cut before the first sound of the first sentence. */
    val ttsLeadTrimmedMs: Double? = null,
    /** Silence heard between pieces beyond their planned pause: the next piece arrived late. */
    val ttsGapMs: Double? = null,
    /** Sentences of the last answer that came from the audio cache, and how long reading one took. */
    val ttsCachedSentences: Int? = null,
    val ttsCacheReadMs: Double? = null,
    /** Which clock saw the audio end ("timestamp" or "head"), and when the follow-up listening opened after it. */
    val audioEndClock: String = "",
    /** The app variant: Dina or Dina Lite. */
    val variant: String = "",
)

data class WakeDiagnosticEvent(
    val id: Long,
    val timestampMs: Long,
    val confidence: Float,
    val detected: Boolean,
    val threshold: Float,
)

/**
 * Observable app state, split by update rate so the UI only redraws what changed:
 * the microphone level updates ~50 times per second, the rest a few times per turn.
 */
class DinaStore {
    private val mutableSession = MutableStateFlow(SessionState())
    private val mutableMessages = MutableStateFlow<List<DinaMessage>>(emptyList())
    private val mutableAudioLevel = MutableStateFlow(0f)
    private val mutableMetrics = MutableStateFlow(PerformanceMetrics())
    private val mutableWake = MutableStateFlow<WakeDiagnosticEvent?>(null)
    private var nextMessageId = 0L

    val session: StateFlow<SessionState> = mutableSession.asStateFlow()
    val messages: StateFlow<List<DinaMessage>> = mutableMessages.asStateFlow()
    val audioLevel: StateFlow<Float> = mutableAudioLevel.asStateFlow()
    val metrics: StateFlow<PerformanceMetrics> = mutableMetrics.asStateFlow()
    val wakeDiagnostic: StateFlow<WakeDiagnosticEvent?> = mutableWake.asStateFlow()

    fun updateSession(transform: (SessionState) -> SessionState) = mutableSession.update(transform)

    fun setState(state: VoiceState, detail: String = state.label) = mutableSession.update { it.copy(state = state, detail = detail) }

    @Synchronized
    fun appendMessage(role: MessageRole, text: String, widget: ContextualWidget? = null) {
        val message = DinaMessage(++nextMessageId, role, text, widget = widget)
        mutableMessages.update { (it + message).takeLast(MAX_MESSAGES) }
    }

    fun clearConversation() {
        mutableMessages.value = emptyList()
        mutableSession.update { it.copy(transcript = "", response = "", widget = null) }
    }

    /** Exponential smoothing keeps the orb calm without lagging speech. */
    fun pushAudioLevel(level: Float) = mutableAudioLevel.update { it * 0.65f + level.coerceIn(0f, 1f) * 0.35f }

    fun updateMetrics(transform: (PerformanceMetrics) -> PerformanceMetrics) = mutableMetrics.update(transform)

    fun setWakeDiagnostic(event: WakeDiagnosticEvent) { mutableWake.value = event }

    private companion object { const val MAX_MESSAGES = 30 }
}
