package com.kakauet.dina.models

import android.content.Context
import com.kakauet.dina.brain.ModelSpec
import com.kakauet.dina.voice.VoiceChoice
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class DinaModelFiles(
    val root: File,
    val llm: File,
    val stt: File,
    val wakeMel: File,
    val wakeEmbedding: File,
    val wakeHead: File,
    val ttsRoot: File,
    val ttsModel: File,
    val ttsConfig: File,
    /** Supertonic 3: `onnx/`, `voice_styles/F2.json` and its OpenRAIL-M `LICENSE`. */
    val supertonic: File,
)

/**
 * Makes the model files available as real paths (inference APIs mmap them).
 *
 * - Release APKs bundle the models: they are copied once from assets to internal storage.
 * - Development APKs do not (fast builds and installs): `.\dev.ps1 push-models`
 *   copies them once to [externalRoot] and they are used in place.
 */
class ModelInstaller(private val context: Context) {
    /** `/sdcard/Android/data/com.kakauet.dina/files/models`, writable by adb. */
    private val externalRoot = File(context.getExternalFilesDir(null), "models")
    private val assetRoot = "models"
    // The copy is skipped when the byte length matches, so each model file has its own name; the small
    // wake head is copied every time, because a retrained head keeps its name and its length.
    private val targetRoot = File(context.filesDir, "models")

    fun install(
        llm: ModelSpec,
        llmLabel: String,
        includeMoonshine: Boolean,
        voice: VoiceChoice = VoiceChoice.default,
        onProgress: (String) -> Unit = {},
    ): DinaModelFiles {
        val assets = buildList {
            add(ModelAsset(llm.assetPath, llm.fileName, llm.bytes, llmLabel))
            if (includeMoonshine) {
                add(ModelAsset("$assetRoot/stt/base-es/encoder_model.ort", "stt/base-es/encoder_model.ort", 20_964_320L, "Moonshine 1/3"))
                add(ModelAsset("$assetRoot/stt/base-es/decoder_model_merged.ort", "stt/base-es/decoder_model_merged.ort", 43_612_200L, "Moonshine 2/3"))
                add(ModelAsset("$assetRoot/stt/base-es/tokenizer.bin", "stt/base-es/tokenizer.bin", 241_639L, "Moonshine 3/3"))
            }
            add(ModelAsset("$assetRoot/wake/melspectrogram.onnx", "wake/melspectrogram.onnx", 1_087_958L, "detector de «Dina» 1/3"))
            add(ModelAsset("$assetRoot/wake/embedding_model.onnx", "wake/embedding_model.onnx", 1_326_578L, "detector de «Dina» 2/3"))
            add(ModelAsset("$assetRoot/$WAKE_HEAD", WAKE_HEAD, WAKE_HEAD_BYTES, "detector de «Dina» 3/3"))
            // Only the chosen voice is copied; the other one is copied if the user switches (Ajustes › Voz).
            when (voice) {
                VoiceChoice.PIPER_SHARVARD -> {
                    add(ModelAsset("$PIPER_ROOT.ort", "$PIPER_ROOT.ort", 77_277_896L, "Piper Sharvard"))
                    add(ModelAsset("$PIPER_ROOT.onnx.json", "$PIPER_ROOT.onnx.json", 4_934L, "configuración de Piper"))
                }
                VoiceChoice.SUPERTONIC_F2 -> SUPERTONIC.forEachIndexed { index, (path, bytes) ->
                    add(ModelAsset("$SUPERTONIC_ROOT/$path", "$SUPERTONIC_ROOT/$path", bytes, "Supertonic ${index + 1}/${SUPERTONIC.size}"))
                }
            }
        }

        if (!bundled(llm.assetPath)) return useExternal(assets, llm)

        val missingBytes = assets.sumOf { asset ->
            val output = File(targetRoot, asset.outputPath)
            if (output.isFile && output.length() == asset.bytes) 0L else asset.bytes
        }
        val safetyBytes = 128L * 1024 * 1024
        if (missingBytes > 0 && context.filesDir.usableSpace < missingBytes + safetyBytes) {
            val neededMb = (missingBytes + safetyBytes) / (1024 * 1024)
            val freeMb = context.filesDir.usableSpace / (1024 * 1024)
            error("Espacio insuficiente: Dina necesita ${neededMb} MB libres y hay ${freeMb} MB")
        }
        assets.forEachIndexed { index, asset ->
            onProgress("Copiando ${asset.label} · ${index + 1}/${assets.size}")
            copyAsset(asset.assetPath, File(targetRoot, asset.outputPath), asset.bytes,
                refresh = asset.outputPath == WAKE_HEAD)
        }
        // Only after a complete copy: what is not in use any more (Moonshine when off, the other voice,
        // earlier brains and voices) and the model folders of older app versions.
        if (!includeMoonshine) File(targetRoot, "stt").deleteRecursively()
        val voiceDir = (if (voice == VoiceChoice.SUPERTONIC_F2) SUPERTONIC_ROOT else PIPER_ROOT).removePrefix("tts/").substringBefore('/')
        File(targetRoot, "tts").listFiles()?.filter { it.name != voiceDir }?.forEach { it.deleteRecursively() }
        val wakeFiles = assets.map { it.outputPath }.filter { it.startsWith("wake/") }.map { it.substringAfter('/') }
        File(targetRoot, "wake").listFiles()?.filter { it.name !in wakeFiles }?.forEach(File::delete)
        File(targetRoot, llm.fileName).parentFile?.listFiles()?.filter { it.name != File(llm.fileName).name }?.forEach(File::delete)
        context.filesDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("dina-models-") }?.forEach { it.deleteRecursively() }
        File(targetRoot, "installed.txt").delete()

        return files(targetRoot, llm)
    }

    /** Release APKs pack the models; debug APKs read them from [externalRoot]. */
    private fun bundled(assetPath: String): Boolean =
        context.assets.list(assetPath.substringBeforeLast('/'))?.contains(assetPath.substringAfterLast('/')) == true

    private fun useExternal(assets: List<ModelAsset>, llm: ModelSpec): DinaModelFiles {
        val missing = assets.filterNot { File(externalRoot, it.outputPath).let { file -> file.isFile && file.length() == it.bytes } }
        check(missing.isEmpty()) {
            "Este APK de desarrollo no incluye modelos. Ejecuta «.\\dev.ps1 push-models». Faltan: " +
                missing.joinToString { it.outputPath }
        }
        return files(externalRoot, llm)
    }

    private fun files(root: File, llm: ModelSpec): DinaModelFiles {
        return DinaModelFiles(
            root = root,
            llm = File(root, llm.fileName),
            stt = File(root, "stt/base-es"),
            wakeMel = File(root, "wake/melspectrogram.onnx"),
            wakeEmbedding = File(root, "wake/embedding_model.onnx"),
            wakeHead = File(root, WAKE_HEAD),
            ttsRoot = File(root, "tts"),
            ttsModel = File(root, "$PIPER_ROOT.ort"),
            ttsConfig = File(root, "$PIPER_ROOT.onnx.json"),
            supertonic = File(root, SUPERTONIC_ROOT),
        )
    }

    private fun copyAsset(assetPath: String, output: File, expectedBytes: Long, refresh: Boolean = false) {
        if (!refresh && output.isFile && output.length() == expectedBytes) return
        output.parentFile?.mkdirs()
        val partial = File(output.parentFile, "${output.name}.partial")
        try {
            // open() works for both compressed and uncompressed APK assets; openFd() does not.
            context.assets.open(assetPath).use { input ->
                partial.outputStream().buffered(4 * 1024 * 1024).use { out -> input.copyTo(out, 4 * 1024 * 1024) }
            }
            check(partial.length() == expectedBytes) { "Copia incompleta (${partial.length()}/$expectedBytes bytes)" }
            runCatching {
                Files.move(
                    partial.toPath(), output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                Files.move(partial.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        } catch (error: Throwable) {
            partial.delete()
            throw IllegalStateException("No se pudo instalar ${output.name}: ${error.message}", error)
        }
    }

    private companion object {
        /**
         * Hugging Face Supertone/supertonic-3 @ 724fb5abbf5502583fb520898d45929e62f02c0b, converted by
         * scripts/tts/quantize_supertonic.py: 8-bit weights, 1x1 convolutions as MatMul and the guidance split out of
         * the estimator (guidance.bin). Versioned folder: the fast install path only compares sizes.
         * `scripts/models/prepare-models.ps1` checks these sizes against the staged files.
         */
        const val SUPERTONIC_ROOT = "tts/supertonic3-v2"
        const val WAKE_HEAD = "wake/dina_wakeword_head.onnx"
        const val WAKE_HEAD_BYTES = 800_297L

        /** Piper Sharvard Medium in ORT format. */
        const val PIPER_ROOT = "tts/es_es/piper-voices/es_ES-sharvard-medium"
        val SUPERTONIC = listOf(
            "onnx/duration_predictor.onnx" to 1_188_757L,
            "onnx/text_encoder.onnx" to 9_702_733L,
            "onnx/vector_estimator.onnx" to 65_506_604L,
            "onnx/vocoder.onnx" to 25_789_382L,
            "onnx/guidance.bin" to 154_624L,
            "onnx/tts.json" to 8_253L,
            "onnx/unicode_indexer.json" to 277_676L,
            "voice_styles/F2.json" to 292_423L,
            "LICENSE" to 15_007L,
        )
    }

    private data class ModelAsset(
        val assetPath: String,
        val outputPath: String,
        val bytes: Long,
        val label: String,
    )
}
