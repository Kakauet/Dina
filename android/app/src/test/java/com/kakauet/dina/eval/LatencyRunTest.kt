package com.kakauet.dina.eval

import com.kakauet.dina.brain.Brain
import com.kakauet.dina.brain.dina45.Dina45Brain
import com.kakauet.dina.core.ConversationController
import com.kakauet.dina.core.DinaStore
import com.kakauet.dina.core.TurnOrigin
import com.kakauet.dina.core.TurnOutcome
import com.kakauet.dina.dialog.Policies
import com.kakauet.dina.llm.NativeLlmEngine
import com.kakauet.dina.tools.TestTools
import com.kakauet.dina.voice.LruSpeechCache
import com.kakauet.dina.voice.OrtProvider
import com.kakauet.dina.voice.PolishedVoice
import com.kakauet.dina.voice.SilenceTrim
import com.kakauet.dina.voice.SpanishSpeech
import com.kakauet.dina.voice.SpeechPlanner
import com.kakauet.dina.voice.SupertonicVoice
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * Entry point of `.\dev.ps1 latency [k=v…]`: the app's path from the final transcript to the
 * first sentence of audio, stage by stage, on the PC. Real [ConversationController], brain,
 * ToolEngine, the app's llama.cpp + PromptCache (dina_native.dll) and Supertonic with the
 * desktop ONNX Runtime. Only AudioTrack is missing (measure it on the phone in Diagnóstico).
 *
 * Options: model=<gguf>, warm=1|0 (prefill while the user speaks), stream=1|0
 * (speak from the first piece), steps=4, guided=2 (guidance in the first K steps; default all),
 * voice_threads=4 (0 = ONNX Runtime's default), llm_threads=6, reps=2, gap_ms=1500 (real pause between turns),
 * first_chunk=1|0 (cut a long first sentence at its first comma), trim=1|0 (cut the silence around each sentence),
 * cache=1|0 (audio cache: with it, repeated answers cost a read), voice_dir=models/tts/supertonic3,
 * demo=1 (also write the answers as WAV, "antes" = app 2.3 behavior and "después" = the current one with short, normal and
 * long pauses, with an index.html), demo_only=1 (only the demo: no model, no latency run),
 * parity=eval-results/tts/ref-kt-dq8-s4 (compare the voice with the Python renders made with `bench_supertonic.py --java-noise`, same
 * noise: checks the step loop of SupertonicVoice; use the same steps and no guided=),
 * tag=<name> → eval-results/latency/<tag>.md
 *
 * The phrases are written here (not from benchmark/). Between the prefill and the transcript the
 * clock moves 600 ms, as when the user stops speaking: a running timer can then miss the cache.
 *
 * "Primer sonido" is the first *audible* sound: the model puts ~0.55 s of silence before every sentence, which
 * plays unless trim=1 cuts it (the app 2.3 played it).
 */
class LatencyRunTest {
    private val phrases = listOf(
        "pon un temporizador de cinco minutos para la pasta",
        "¿cuánto le queda?",
        "despiértame mañana a las siete y media",
        "añade leche, huevos y pan a la lista de la compra",
        "¿qué tengo en la lista?",
        "¿qué hora es?",
        "sube un poco el volumen",
        "pon un cronómetro",
        "¿cuánto es veintitrés por cuarenta y siete?",
        "cancela la alarma de mañana",
        "cuéntame un chiste",
        "gracias, Dina",
    )

    /** The answers the 12 phrases above get (for demo_only, which does not run the model). */
    private val shortAnswers = listOf(
        "Temporizador de pasta en marcha: 5 minutos.", "Quedan 4 minutos y 55 segundos en el temporizador de pasta.",
        "Vale, alarma para mañana a las 7:30.", "Vale, leche, huevos y pan a la lista.", "Tienes 3 cosas apuntadas: leche, huevos y pan.",
        "Son las 9:30.", "Hecho: el volumen está al 60 por ciento.", "Cronómetro en marcha.", "Son 1081.",
        "No tienes ninguna alarma por la mañana.", "¿Cuál es el colmo de un despertador? Que lo pongan de mal humor por las mañanas.",
        "Gracias, me alegro de que te haya gustado.",
    )

    /** Long answers for the demo, written here: a long first sentence and several sentences. */
    private val demoAnswers = listOf(
        "Hecho. Tienes tres alarmas: una a las siete y media, otra a las ocho menos cuarto y la última a las nueve de la mañana. ¿Quieres que las repita?",
        "Te leo la lista de la compra: leche, huevos, pan, tomates, queso, manzanas y un paquete de arroz, en total siete productos.",
        "Vale, temporizador de pasta en marcha: cinco minutos. Te aviso cuando suene.",
        "No puedo poner música. Sí puedo ponerte alarmas y temporizadores.",
        "¿Quieres que ponga la alarma a las siete y media o a las ocho?",
    )

    private data class Sample(
        val prompt: Double, val prefill: Double, val decode: Double, val engine: Double, val rest: Double,
        val turn: Double, val synthesis: Double, val total: Double, val prepare: Double, val reused: Int, val promptTokens: Int,
        val firstSentence: String, val audioSeconds: Double,
        val stages: SupertonicVoice.Stages?, val leadMs: Double, val audible: Double,
    )

    @Test
    fun run() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        val args = System.getProperty("dina.latency").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val root = File(System.getProperty("dina.root") ?: "../..")

        val warm = options["warm"] != "0"
        val stream = options["stream"] != "0"
        val steps = options["steps"]?.toInt() ?: SupertonicVoice.DEFAULT_STEPS
        val guided = options["guided"]?.toInt() ?: SupertonicVoice.ALL_GUIDED
        val firstChunk = options["first_chunk"] != "0"
        val trim = options["trim"] != "0"
        val cache = options["cache"] == "1"
        val demoOnly = options["demo_only"] == "1"
        val demo = options["demo"] == "1" || demoOnly
        val voiceDir = options["voice_dir"] ?: "models/tts/supertonic3"
        val voiceThreads = options["voice_threads"]?.toInt() ?: SupertonicVoice.DEFAULT_THREADS
        val llmThreads = options["llm_threads"]?.toInt() ?: 6
        val reps = options["reps"]?.toInt() ?: 2
        val gapMs = options["gap_ms"]?.toLong() ?: 1_500L
        val modelPath = options["model"] ?: EvalModels.DINA45
        val guideTag = if (guided >= steps) "" else "-g$guided"
        val tag = options["tag"] ?: "dina45-${if (warm) "warm" else "cold"}-${if (stream) "stream" else "whole"}-s$steps$guideTag-t$voiceThreads"

        val llm = NativeLlmEngine()
        if (!demoOnly) llm.load(File(root, modelPath).absolutePath, llmThreads, 1536)
        val raw = SupertonicVoice(File(root, voiceDir), "F2", voiceThreads, OrtProvider.CPU, steps, guided)
        val voice = PolishedVoice(raw, if (trim) SilenceTrim.Config() else null, if (cache) LruSpeechCache() else null)
        val planner = SpeechPlanner.Config(splitFirst = firstChunk)
        val samples = mutableListOf<Sample>()
        val answers = mutableListOf<String>()
        val parity = options["parity"]?.let { parity(raw, File(root, it)) }
        var synthSeconds = 0.0
        var audioSeconds = 0.0
        try {
            repeat(if (demoOnly) 0 else reps + 1) { rep ->
                val tools = TestTools(LocalDateTime.of(2026, 10, 7, 9, 30))
                val brain: Brain = Dina45Brain(llm, tools.executor, Policies(), seed = 0, prefillWhileSpeaking = warm)

                val store = DinaStore()
                val controller = ConversationController(store).also { it.attach(brain) }
                for (phrase in phrases) {
                    tools.advance(5_000) // the user thinks, says "Dina…"
                    if (warm) controller.prepare()
                    tools.advance(600) // …and finishes the phrase; the recognizer finalizes it
                    Thread.sleep(300)
                    val transcriptNs = System.nanoTime()
                    val outcome = controller.runTurn(phrase, TurnOrigin.VOICE)
                    check(outcome is TurnOutcome.Answered) { "turn failed: $outcome" }
                    val turnEndNs = System.nanoTime()
                    if (rep == 0) answers += outcome.answer
                    val rtf = if (audioSeconds > 0) synthSeconds / audioSeconds else SpeechPlanner.DEFAULT_RTF
                    val first = if (stream) SpeechPlanner.plan(outcome.answer, rtf, planner).first().text else SpanishSpeech.expand(outcome.answer)
                    val audio = voice.synthesize(first)
                    val audioNs = System.nanoTime()
                    if (!audio.cached) { synthSeconds += audio.synthesisMs / 1000.0; audioSeconds += audio.durationSeconds }
                    Thread.sleep(gapMs) // Dina speaks and the user starts the next phrase: threads go idle
                    if (rep == 0) continue // warm-up session: model pages, ORT allocations
                    val m = store.metrics.value
                    // Silence the model put before the first sound: cut (trim) or played (what app 2.3 did).
                    val leadMs = if (audio.cached || trim) audio.trimmedLeadMs else SilenceTrim.leadingSilenceMs(audio.samples, audio.sampleRate)
                    val total = (audioNs - transcriptNs) / 1e6
                    samples += Sample(
                        prompt = m.sttFinalToPromptReadyMs ?: 0.0,
                        prefill = (m.tokenizationMs ?: 0.0) + (m.prefillMs ?: 0.0),
                        decode = m.decodeMs ?: 0.0,
                        engine = m.toolMs ?: 0.0,
                        rest = (m.turnWaitMs ?: 0.0) + (m.turnBookkeepingMs ?: 0.0),
                        turn = (turnEndNs - transcriptNs) / 1e6,
                        synthesis = (audioNs - turnEndNs) / 1e6,
                        total = total,
                        prepare = if (warm) m.prepareMs ?: 0.0 else 0.0,
                        reused = m.reusedPromptTokens ?: 0,
                        promptTokens = m.promptTokens ?: 0,
                        firstSentence = first,
                        audioSeconds = audio.durationSeconds,
                        stages = audio.stages,
                        leadMs = leadMs,
                        audible = total + if (trim) 0.0 else leadMs,
                    )
                }
            }
            if (demo) writeDemo(File(root, "eval-results/tts/muestras-app/$tag"), raw, voice, planner, (if (demoOnly) shortAnswers else answers) + demoAnswers)
        } finally {
            voice.close()
            if (!demoOnly) llm.close()
        }
        if (demoOnly) {
            File(root, "eval-results/last.txt").writeText("demo $tag: eval-results/tts/muestras-app/$tag/index.html\n", Charsets.UTF_8)
            return@runBlocking
        }
        val report = report(tag, modelPath, warm, stream, steps, guided, firstChunk, trim, cache, voiceDir, voiceThreads, llmThreads, samples)
        val out = File(root, "eval-results/latency").also { it.mkdirs() }
        File(out, "$tag.md").writeText(report + (parity?.let { "\n## Paridad con Python (mismo ruido)\n\n$it\n" } ?: ""), Charsets.UTF_8)
        val total = samples.map { it.total }
        File(root, "eval-results/last.txt").writeText(
            "latencia $tag: fin de transcripción → primer audio p50 ${ms(median(total))} · primer sonido p50 ${ms(median(samples.map { it.audible }))} (${samples.size} turnos)\n${File(out, "$tag.md").path}\n",
            Charsets.UTF_8,
        )
    }

    /** The voice against Python renders with the same noise: relative RMS error and largest sample difference per sentence. */
    private fun parity(voice: SupertonicVoice, dir: File): String {
        val sentences = org.json.JSONArray(File(dir, "sentences.json").readText(Charsets.UTF_8)).let { a -> List(a.length()) { a.getString(it) } }
        return buildString {
            appendLine("| Frase | error RMS relativo | diferencia máxima | muestras (Kotlin / Python) |")
            appendLine("|---|---:|---:|---:|")
            var worst = 0.0
            sentences.forEachIndexed { index, sentence ->
                val ours = voice.synthesize(sentence).samples
                val theirs = readNpy(File(dir, "%02d.npy".format(index)))
                val n = minOf(ours.size, theirs.size)
                var diff = 0.0; var energy = 0.0; var peak = 0.0
                for (i in 0 until n) { val d = (ours[i] - theirs[i]).toDouble(); diff += d * d; energy += theirs[i].toDouble() * theirs[i]; peak = maxOf(peak, kotlin.math.abs(d)) }
                val relative = kotlin.math.sqrt(diff / energy.coerceAtLeast(1e-12))
                worst = maxOf(worst, relative)
                appendLine("| ${sentence.take(48).replace("|", "/")} | ${"%.2e".format(Locale.US, relative)} | ${"%.2e".format(Locale.US, peak)} | ${ours.size} / ${theirs.size} |")
            }
            appendLine()
            appendLine("Peor error relativo: ${"%.2e".format(Locale.US, worst)}. Con las mismas entradas y ONNX Runtime, solo debería haber redondeo de coma flotante.")
        }
    }

    /** A float32 .npy file (version 1, little endian). */
    private fun readNpy(file: File): FloatArray {
        val bytes = file.readBytes()
        val headerLength = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        val data = java.nio.ByteBuffer.wrap(bytes, 10 + headerLength, bytes.size - 10 - headerLength).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(data.remaining()).also { data.get(it) }
    }

    /**
     * "antes": the app 2.3 — sentences, raw audio (silence included), a fixed 160 ms pause.
     * "después": [SpeechPlanner] pieces, trimmed audio, the pause each piece asks for.
     */
    private fun writeDemo(dir: File, raw: SupertonicVoice, polished: PolishedVoice, planner: SpeechPlanner.Config, answers: List<String>) {
        dir.mkdirs()
        val rate = raw.sampleRate
        fun silence(ms: Int) = FloatArray(rate * ms / 1000)
        val rows = StringBuilder()
        answers.forEachIndexed { index, answer ->
            val before = SpanishSpeech.sentences(answer).map { raw.synthesize(it) }
            val beforeWav = before.flatMapIndexed { i, a -> a.samples.toList() + if (i < before.lastIndex) silence(160).toList() else emptyList() }.toFloatArray()
            val pieces = SpeechPlanner.plan(answer, SpeechPlanner.DEFAULT_RTF, planner)
            val after = pieces.map { polished.synthesize(it.text) }
            val n = "%02d".format(index + 1)
            Wav.write(File(dir, "antes-$n.wav"), beforeWav, rate)
            rows.appendLine("<tr><td colspan=2><b>${index + 1}.</b> ${answer.replace("<", "&lt;")}</td></tr>")
            rows.appendLine("<tr><td>antes · app 2.3 (${"%.1f".format(Locale.US, beforeWav.size.toDouble() / rate)} s, ${before.size} frases, huecos de ~1,4 s)</td><td><audio controls preload=none src=\"antes-$n.wav\"></audio></td></tr>")
            for ((factor, name) in listOf(0.6 to "pausas cortas", 1.0 to "pausas normales", 1.6 to "pausas largas")) {
                val wav = after.flatMapIndexed { i, a -> a.samples.toList() + if (i < after.lastIndex) silence((pieces[i].pauseAfterMs * factor).toInt()).toList() else emptyList() }.toFloatArray()
                val file = "despues-$n-${name.substringAfter(' ')}.wav"
                Wav.write(File(dir, file), wav, rate)
                rows.appendLine("<tr><td>después · $name (${"%.1f".format(Locale.US, wav.size.toDouble() / rate)} s; trozos: ${pieces.joinToString(" | ") { "${it.text.length} car, ${(it.pauseAfterMs * factor).toInt()} ms" }})</td><td><audio controls preload=none src=\"$file\"></audio></td></tr>")
            }
        }
        File(dir, "index.html").writeText(
            """<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Dina: respuestas antes y después</title>
<style>body{font:15px/1.4 system-ui,sans-serif;margin:16px auto;max-width:980px;padding:0 16px}td{border-bottom:1px solid #ddd;padding:6px 8px;vertical-align:middle}audio{width:260px}
@media (prefers-color-scheme:dark){body{background:#161616;color:#ddd}td{border-color:#333}}</style>
<h1>Respuestas completas: antes y después</h1><p>«Antes» es la app 2.3 (cada frase con su silencio inicial y final y 160 ms fijos). «Después» recorta ese silencio y deja la pausa de cada signo de puntuación (punto 220 ms, pregunta 260, dos puntos 160, coma 80…); si la primera frase es larga, empieza en la primera coma. Las pausas cortas son 0,6 veces las normales y las largas 1,6 veces. Todas las muestras tienen los mismos pasos de voz, así que solo cambia la reproducción.</p>
<table>$rows</table></html>""",
            Charsets.UTF_8,
        )
    }

    private object Wav {
        fun write(file: File, samples: FloatArray, rate: Int) {
            val data = ByteArray(samples.size * 2)
            samples.forEachIndexed { i, v ->
                val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
                data[2 * i] = (s and 0xFF).toByte(); data[2 * i + 1] = (s shr 8).toByte()
            }
            fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            file.outputStream().use { out ->
                out.write("RIFF".toByteArray()); out.write(le(36 + data.size)); out.write("WAVEfmt ".toByteArray()); out.write(le(16))
                out.write(byteArrayOf(1, 0, 1, 0)); out.write(le(rate)); out.write(le(rate * 2)); out.write(byteArrayOf(2, 0, 16, 0))
                out.write("data".toByteArray()); out.write(le(data.size)); out.write(data)
            }
        }
    }

    private fun report(
        tag: String, model: String, warm: Boolean, stream: Boolean, steps: Int, guided: Int, firstChunk: Boolean, trim: Boolean, cache: Boolean,
        voiceDir: String, voiceThreads: Int, llmThreads: Int, s: List<Sample>,
    ): String = buildString {
        val guidance = when { guided >= steps -> "guía completa"; guided <= 0 -> "sin guía"; else -> "guía en los $guided primeros pasos" }
        appendLine("# Latencia en el PC: $tag")
        appendLine()
        appendLine(
            "Dina 4.5 (`$model`, $llmThreads hilos) · precalentado ${if (warm) "sí" else "no"} · voz desde ${if (stream) "el primer trozo" else "la respuesta entera"} · " +
                "Supertonic F2 (`$voiceDir`) $steps pasos, $guidance, ${if (voiceThreads == 0) "hilos por defecto de ORT" else "$voiceThreads hilos"} · " +
                "primer trozo corto ${if (firstChunk) "sí" else "no"} · silencio recortado ${if (trim) "sí" else "no"} · caché de audio ${if (cache) "sí" else "no"}. ${s.size} turnos.",
        )
        appendLine()
        appendLine("| Tramo | p50 | media | p95 |")
        appendLine("|---|---:|---:|---:|")
        fun row(name: String, values: List<Double>) = appendLine("| $name | ${ms(median(values))} | ${ms(values.average())} | ${ms(percentile(values, 0.95))} |")
        row("Prompt", s.map { it.prompt })
        row("Prefill (tokenización + prompt)", s.map { it.prefill })
        row("Decodificación LLM", s.map { it.decode })
        row("Motor y respuesta", s.map { it.engine })
        row("Resto del turno", s.map { it.rest })
        row("**Turno** (transcripción → respuesta)", s.map { it.turn })
        row("Primera síntesis", s.map { it.synthesis })
        s.mapNotNull { it.stages }.takeIf { it.isNotEmpty() }?.let { stages ->
            row("· duración + codificador de texto", stages.map { it.durationMs + it.encoderMs })
            row("· estimador (los pasos)", stages.map { it.estimatorMs })
            row("· vocoder", stages.map { it.vocoderMs })
        }
        row("**Total** (→ primer audio entregado, sin AudioTrack)", s.map { it.total })
        row("Silencio inicial de la voz (${if (trim) "recortado" else "se oye"})", s.map { it.leadMs })
        row("**Primer sonido audible** (total + silencio que se oye)", s.map { it.audible })
        row("Precalentado (fuera del camino)", s.map { it.prepare })
        appendLine()
        appendLine("Prompt reutilizado de la caché: ${"%.0f".format(Locale.US, 100.0 * s.sumOf { it.reused } / s.sumOf { it.promptTokens }.coerceAtLeast(1))} % de los tokens.")
        appendLine("Audio del primer trozo: p50 ${"%.1f".format(Locale.US, median(s.map { it.audioSeconds }))} s.")
        appendLine()
        appendLine("| Primer trozo | prefill | decod. | síntesis | total | audible |")
        appendLine("|---|---:|---:|---:|---:|---:|")
        s.take(phrases.size).forEach { appendLine("| ${it.firstSentence.replace("|", "/")} | ${ms(it.prefill)} | ${ms(it.decode)} | ${ms(it.synthesis)} | ${ms(it.total)} | ${ms(it.audible)} |") }
    }

    private fun ms(value: Double) = "%.0f ms".format(Locale.US, value)
    private fun median(values: List<Double>) = percentile(values, 0.5)
    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }
}
