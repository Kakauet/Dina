package com.kakauet.dina.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LlmMetrics(
    val promptTokens: Int = 0,
    /** Prompt tokens restored from the prompt cache instead of being processed again. */
    val reusedPromptTokens: Int = 0,
    val completionTokens: Int = 0,
    val prefillMs: Double = 0.0,
    val tokenizationMs: Double = 0.0,
    val firstTokenMs: Double = 0.0,
    val generationMs: Double = 0.0,
    val tokensPerSecond: Double = 0.0,
)

data class LlmResult(val text: String, val metrics: LlmMetrics, val complete: Boolean = true)

/** Raw text completion. Brains own the prompt format; this only turns a prompt into text. */
interface TextGenerator {
    suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit = {}, grammar: String? = null): LlmResult

    /** Stops the running generation after the current token (callable from `onToken`). */
    fun cancel() {}

    /**
     * Prefills [prefix], the beginning of a prompt that is not complete yet, so a later [generate]
     * whose prompt starts with it only processes the rest. Returns the tokens processed now.
     */
    suspend fun warm(prefix: String): Int = 0
}

/** llama.cpp through JNI (`cpp/dina_llm_jni.cpp`). One model per instance. */
class NativeLlmEngine : TextGenerator, AutoCloseable {
    private var loaded = false

    companion object {
        init { System.loadLibrary("dina_native") }
    }

    private external fun nativeLoad(path: String, threads: Int, contextSize: Int): Boolean
    private external fun nativeBegin(prompt: String, maxTokens: Int, grammar: String?): Boolean
    private external fun nativeWarm(prefix: String): Int
    private external fun nativeSetThreads(threads: Int, batchThreads: Int)
    private external fun nativeNext(): String?
    private external fun nativeCancel()
    private external fun nativeStats(): String
    private external fun nativeClose()

    suspend fun load(path: String, threads: Int = 6, contextSize: Int = 2048) = withContext(Dispatchers.IO) {
        if (!nativeLoad(path, threads, contextSize)) {
            nativeClose()
            error("No se pudo cargar el modelo de lenguaje")
        }
        loaded = true
    }

    override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit, grammar: String?): LlmResult =
        withContext(Dispatchers.IO) {
            check(loaded) { "El modelo de lenguaje no está cargado" }
            check(nativeBegin(prompt, maxTokens, grammar)) { "No se pudo iniciar la inferencia" }
            val text = buildString {
                while (true) {
                    val piece = nativeNext() ?: break
                    append(piece)
                    onToken(piece)
                }
            }.substringBefore("<|im_end|>").trim()
            val raw = JSONObject(nativeStats())
            LlmResult(
                text,
                LlmMetrics(
                    promptTokens = raw.optInt("promptTokens"),
                    reusedPromptTokens = raw.optInt("reusedPromptTokens"),
                    completionTokens = raw.optInt("completionTokens"),
                    prefillMs = raw.optDouble("prefillMs"),
                    tokenizationMs = raw.optDouble("tokenizationMs"),
                    firstTokenMs = raw.optDouble("firstTokenMs"),
                    generationMs = raw.optDouble("generationMs"),
                    tokensPerSecond = raw.optDouble("tokensPerSecond"),
                ),
                complete = raw.optBoolean("complete", false),
            )
        }

    override fun cancel() = nativeCancel()

    override suspend fun warm(prefix: String): Int = withContext(Dispatchers.IO) {
        if (loaded) nativeWarm(prefix).coerceAtLeast(0) else 0
    }

    /** Decoding and prompt-processing threads, applied from the next call (no reload). */
    fun setThreads(threads: Int, batchThreads: Int = threads) { if (loaded) nativeSetThreads(threads, batchThreads) }

    override fun close() {
        if (loaded) nativeClose()
        loaded = false
    }
}
