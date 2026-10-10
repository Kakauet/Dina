package com.kakauet.dina.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random

/**
 * Supertonic 3 (Supertone, OpenRAIL-M) with ONNX Runtime, as the reference pipeline
 * (`supertonic` 1.3.1 `core.py`): Spanish text in words ([SpanishSpeech]) → unicode ids →
 * duration → text encoder → [steps] of the flow-matching vector estimator → vocoder, 44.1 kHz.
 *
 * [dir] holds `onnx/` (four graphs, `guidance.bin`, `tts.json`, `unicode_indexer.json`) and `voice_styles/<style>.json`.
 * The estimator is the *body* graph of scripts/tts/supertonic_cfg.py: the classifier-free guidance (a batch of 2,
 * conditioned + unconditioned) is built and combined here ([SupertonicGuidance]), so only the first [guidedSteps]
 * steps pay for it and the rest run one row. Not thread-safe: one sentence at a time.
 */
class SupertonicVoice(
    dir: File,
    private val style: String = "F2",
    threads: Int = DEFAULT_THREADS,
    provider: OrtProvider = OrtProvider.CPU,
    /** Denoising steps: more is closer to the converged voice; the estimator is most of the synthesis time. */
    @Volatile var steps: Int = DEFAULT_STEPS,
    /** The first steps that are guided (the original: all of them); the others run only the conditioned row, about half the work. */
    @Volatile var guidedSteps: Int = ALL_GUIDED,
    private val speed: Float = 1.05f,
) : SpeechVoice {
    companion object {
        /** The full edition's default, chosen by ear in the 2.3: 8 steps. Lite uses 4 ([com.kakauet.dina.config.AppVariant.defaultSteps]). */
        const val DEFAULT_STEPS = 8
        /** Every step guided: the voice as it was. */
        const val ALL_GUIDED = Int.MAX_VALUE
        const val DEFAULT_THREADS = 4
        val STEPS = 2..8
        /** Same noise for the same sentence: Dina always sounds the same. */
        private const val SEED = 20_261_007L
    }

    /** Milliseconds per stage of the last sentence (PC benchmark and Diagnóstico). */
    data class Stages(val durationMs: Double = 0.0, val encoderMs: Double = 0.0, val estimatorMs: Double = 0.0, val vocoderMs: Double = 0.0)

    /** The tensors a step reuses for one sentence: rows for a batch of [batch] (2 guided, 1 not). */
    private class StepInputs(
        val batch: Int, val textEmb: OnnxTensor, val styleValue: OnnxTensor, val styleKey: OnnxTensor,
        val textMask: OnnxTensor, val latentMask: OnnxTensor, val totalStep: OnnxTensor,
    )

    override val label = "Supertonic 3 · $style"
    override val description get() = "$label · $steps pasos · ${guidanceText()} · ${provider.label}"
    /** Part of the audio cache key: everything that makes the same text sound different. */
    override val signature get() =
        "supertonic3|$modelId|$style|steps=${steps.coerceIn(1, 100)}|guided=${guidedSteps.coerceIn(0, steps)}|speed=$speed|seed=$SEED"

    private fun guidanceText() = when {
        guidedSteps >= steps -> "guía completa"
        guidedSteps <= 0 -> "sin guía"
        else -> "guía en los $guidedSteps primeros"
    }

    private val env = OrtEnvironment.getEnvironment()
    /** The provider actually in use (falls back to CPU if the requested one is not available). */
    val provider: OrtProvider
    private val options: OrtSession.SessionOptions
    private val config: SupertonicText.Config
    private val indexer: IntArray
    private val durationSession: OrtSession
    private val encoderSession: OrtSession
    private val estimatorSession: OrtSession
    private val vocoderSession: OrtSession
    private val guidance: SupertonicGuidance
    private val modelId: String
    private val styleTtl: OnnxTensor
    private val styleDp: OnnxTensor
    private val styleValues: FloatArray
    private val keyCond: OnnxTensor
    override val sampleRate: Int
    override val initMs: Double
    override val warmupMs: Double
    @Volatile var lastStages = Stages()
        private set

    init {
        val started = System.nanoTime()
        val onnx = File(dir, "onnx")
        val (opts, used) = sessionOptions(threads, provider)
        options = opts
        this.provider = used
        config = JSONObject(File(onnx, "tts.json").readText()).let { cfg ->
            SupertonicText.Config(
                sampleRate = cfg.getJSONObject("ae").getInt("sample_rate"),
                baseChunkSize = cfg.getJSONObject("ae").getInt("base_chunk_size"),
                chunkCompressFactor = cfg.getJSONObject("ttl").getInt("chunk_compress_factor"),
                latentDim = cfg.getJSONObject("ttl").getInt("latent_dim"),
            )
        }
        sampleRate = config.sampleRate
        indexer = readIndexer(File(onnx, "unicode_indexer.json"))
        val voice = JSONObject(File(dir, "voice_styles/$style.json").readText())
        styleTtl = tensor(voice.getJSONObject("style_ttl"))
        styleDp = tensor(voice.getJSONObject("style_dp"))
        styleValues = FloatArray(styleTtl.floatBuffer.remaining()).also { styleTtl.floatBuffer.get(it) }
        guidance = SupertonicGuidance.load(File(onnx, "guidance.bin"))
        keyCond = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(guidance.rows(FloatArray(SupertonicGuidance.TEXT_CHANNELS), styleValues, guided = false).styleKey),
            longArrayOf(1, SupertonicGuidance.STYLE_TOKENS.toLong(), SupertonicGuidance.STYLE_CHANNELS.toLong()),
        )
        modelId = listOf("duration_predictor", "text_encoder", "vector_estimator", "vocoder").joinToString("-") { File(onnx, "$it.onnx").length().toString(36) }
        durationSession = env.createSession(File(onnx, "duration_predictor.onnx").absolutePath, options)
        encoderSession = env.createSession(File(onnx, "text_encoder.onnx").absolutePath, options)
        estimatorSession = env.createSession(File(onnx, "vector_estimator.onnx").absolutePath, options)
        vocoderSession = env.createSession(File(onnx, "vocoder.onnx").absolutePath, options)
        initMs = (System.nanoTime() - started) / 1_000_000.0
        val warm = System.nanoTime()
        synthesize("Hola.")
        warmupMs = (System.nanoTime() - warm) / 1_000_000.0
    }

    override fun synthesize(text: String): SpeechAudio {
        val started = System.nanoTime()
        val ids = SupertonicText.ids(SpanishSpeech.expand(text), indexer)
        if (ids.size <= 9) return SpeechAudio(FloatArray(0), sampleRate, 0.0) // only "<es></es>"
        val length = ids.size.toLong()
        val steps = steps.coerceIn(1, 100)
        val guidedCount = guidedSteps.coerceIn(0, steps)
        val open = mutableListOf<AutoCloseable>()
        try {
            val textIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, length)).also(open::add)
            val textMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(ids.size) { 1f }), longArrayOf(1, 1, length)).also(open::add)

            val seconds = durationSession.run(mapOf("text_ids" to textIds, "style_dp" to styleDp, "text_mask" to textMask)).use { result ->
                (result[0] as OnnxTensor).floatBuffer.get(0) / speed
            }
            val afterDuration = System.nanoTime()
            val encoded = encoderSession.run(mapOf("text_ids" to textIds, "style_ttl" to styleTtl, "text_mask" to textMask)).also(open::add)
            val textEmb = (encoded[0] as OnnxTensor).floatBuffer.let { buffer -> FloatArray(buffer.remaining()).also { buffer.get(it) } }
            val afterEncoder = System.nanoTime()

            val shapes = SupertonicText.shapes(seconds, config)
            val channels = shapes.latentChannels
            val frames = shapes.frames
            val random = Random(SEED)
            val latent = FloatArray(channels * frames)
            for (c in 0 until channels) for (f in 0 until frames) {
                val value = random.nextGaussian().toFloat()
                latent[c * frames + f] = if (f < shapes.maskedFrames) value else 0f
            }
            val masks = FloatArray(frames) { if (it < shapes.maskedFrames) 1f else 0f }
            val textOnes = FloatArray(ids.size) { 1f }
            val sets = arrayOfNulls<StepInputs>(3) // by batch size
            fun inputs(batch: Int): StepInputs = sets[batch] ?: run {
                val b = batch.toLong()
                val rows = guidance.rows(textEmb, styleValues, guided = batch == 2)
                fun tensor(data: FloatArray, vararg shape: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).also(open::add)
                fun repeated(data: FloatArray) = if (batch == 2) data + data else data
                StepInputs(
                    batch,
                    tensor(rows.textEmb, b, SupertonicGuidance.TEXT_CHANNELS.toLong(), length),
                    tensor(rows.styleValue, b, SupertonicGuidance.STYLE_TOKENS.toLong(), SupertonicGuidance.STYLE_CHANNELS.toLong()),
                    if (batch == 2) tensor(rows.styleKey, 2, SupertonicGuidance.STYLE_TOKENS.toLong(), SupertonicGuidance.STYLE_CHANNELS.toLong()) else keyCond,
                    tensor(repeated(textOnes), b, 1, length),
                    tensor(repeated(masks), b, 1, frames.toLong()),
                    tensor(FloatArray(batch) { steps.toFloat() }, b),
                ).also { sets[batch] = it }
            }
            val batched = FloatArray(2 * latent.size)
            for (step in 0 until steps) {
                val guided = SupertonicGuidance.isGuided(step, guidedCount)
                val set = inputs(if (guided) 2 else 1)
                latent.copyInto(batched)
                if (guided) latent.copyInto(batched, latent.size)
                val rows = set.batch.toLong()
                val noisy = OnnxTensor.createTensor(env, FloatBuffer.wrap(batched.copyOf(set.batch * latent.size)), longArrayOf(rows, channels.toLong(), frames.toLong()))
                val current = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(set.batch) { step.toFloat() }), longArrayOf(rows))
                val velocity = FloatArray(set.batch * latent.size)
                try {
                    estimatorSession.run(
                        mapOf(
                            "noisy_latent" to noisy, "text_emb" to set.textEmb, "style_ttl" to set.styleValue, "style_key" to set.styleKey,
                            "latent_mask" to set.latentMask, "text_mask" to set.textMask, "current_step" to current, "total_step" to set.totalStep,
                        ),
                    ).use { result -> (result[0] as OnnxTensor).floatBuffer.get(velocity) }
                } finally {
                    noisy.close(); current.close()
                }
                guidance.advance(latent, velocity, guided, frames, shapes.maskedFrames, steps)
            }
            val afterEstimator = System.nanoTime()
            val latentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), longArrayOf(1, channels.toLong(), frames.toLong())).also(open::add)
            val samples = vocoderSession.run(mapOf("latent" to latentTensor)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                FloatArray(minOf(buffer.remaining(), shapes.samples)).also { buffer.get(it) }
            }
            val done = System.nanoTime()
            val stages = Stages(
                durationMs = (afterDuration - started) / 1_000_000.0,
                encoderMs = (afterEncoder - afterDuration) / 1_000_000.0,
                estimatorMs = (afterEstimator - afterEncoder) / 1_000_000.0,
                vocoderMs = (done - afterEstimator) / 1_000_000.0,
            )
            lastStages = stages
            return SpeechAudio(samples, sampleRate, (done - started) / 1_000_000.0, stages = stages)
        } finally {
            open.asReversed().forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        listOf(durationSession, encoderSession, estimatorSession, vocoderSession, styleTtl, styleDp, keyCond, options).forEach { runCatching { it.close() } }
    }

    private fun sessionOptions(threads: Int, provider: OrtProvider): Pair<OrtSession.SessionOptions, OrtProvider> {
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setInterOpNumThreads(1)
            setIntraOpNumThreads(threads)
        }
        val used = runCatching {
            when (provider) {
                // XNNPACK has its own thread pool; ORT's would only compete with it.
                OrtProvider.XNNPACK -> { options.addXnnpack(mapOf("intra_op_num_threads" to "$threads")); options.setIntraOpNumThreads(1) }
                OrtProvider.NNAPI -> options.addNnapi()
                OrtProvider.CPU -> Unit
            }
            provider
        }.getOrDefault(OrtProvider.CPU)
        return options to used
    }

    private fun tensor(json: JSONObject): OnnxTensor {
        val dims = json.getJSONArray("dims").let { d -> LongArray(d.length()) { d.getLong(it) } }
        val values = FloatArray(dims.fold(1L) { a, b -> a * b }.toInt())
        var index = 0
        fun flatten(array: JSONArray) {
            for (i in 0 until array.length()) {
                val item = array.get(i)
                if (item is JSONArray) flatten(item) else values[index++] = (item as Number).toFloat()
            }
        }
        flatten(json.getJSONArray("data"))
        check(index == values.size) { "voice style: ${values.size} values expected, $index read" }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(values), dims)
    }

    /** A JSON array of 65 536 ints; split by hand (org.json would box every one). */
    private fun readIndexer(file: File): IntArray {
        val text = file.readText()
        val body = text.substring(text.indexOf('[') + 1, text.lastIndexOf(']'))
        val parts = body.split(',')
        return IntArray(parts.size) { parts[it].trim().toInt() }
    }
}
