# Dina: guide for coding agents

Local Spanish voice assistant for Android (Galaxy S24 Ultra). On-device: wake word "Dina",
STT, a small LLM (llama.cpp) that reads the request, a dialogue engine that acts, Supertonic TTS. No internet.
Current: app 2.4 with Dina 4.5, in two editions from the same code: **Dina** (`full`: Dina 4.5 1.2B Q4_K_M, the S24 Ultra and alike)
and **Dina Lite** (`lite`: Dina 4.5 350M Q8_0, applicationId `.lite`, installs next to the other). See `docs/ROADMAP.md` for what comes next.
The owner (Kakauet) writes in Spanish: answer in Spanish. Code, comments and identifiers in English;
user-facing strings in Spanish.

## Names

Models are Dina 2, Dina 2.5, Dina 3, Dina 4 Preview, Dina 4 and Dina 4.5 (the app's brain: 1.2B in Dina, 350M in Dina Lite).
A brain is named after its model (`brain/dina45/Dina45Brain`, eval brain `dina45`, `models/dina-4.5/`, `models/dina-4.5-350m/`).
Use only these names in code, docs and data.

## Commands (always use these; never call gradlew directly)

`.\dev.ps1 <cmd>` prints one summary line, or only the errors. Full log: `android/build/dev-last.log`.

| Command | What | Time (warm) |
|---|---|---|
| `check` | compile Kotlin only | ~10 s |
| `test` | JVM unit tests (incl. benchmark checks) -> `OK <n> tests`; runs the `full` edition (`lite` is the same code, only `BuildConfig.LITE` differs) | ~15 s |
| `build [lite]` | debug APK without models (~86 MB); `full` unless `lite` is given | ~5 s - 1 min |
| `install [lite]` / `run [lite]` / `logs` | phone over adb (often no phone is connected: say so, do not retry) | |
| `push-models [lite]` | copy models to the phone once; debug APKs need it (own folder per edition) | |
| `tts-models [dq8\|nbits8]` | Supertonic for the app, in WSL (env `tts`): fp32 originals in `models/tts/supertonic3-fp32` (read-only, never modified) -> 8-bit weights, 1x1 convs as MatMul, guidance split out of the estimator (`onnx/guidance.bin`) -> `models/tts/supertonic3`. Never overwrites | ~1 min |
| `wake-synthesize` / `wake-train` / `wake-calibrate` / `wake-eval` | "Dina" detector (`scripts/wakeword/README.md`): TTS corpus + public voices and noise, train the head on the app's streaming features, pick the sensitivity thresholds, test once | 1 h / 15 min / 5 min / 10 min |
| `llm-bench` | PC run of the app's llama.cpp + `PromptCache` with Dina 4.5 (incl. the warm-up while the user speaks); checks cache exactness | ~1 min |
| `latency [k=v…]` | PC: end of transcript → first audio by stage (controller, Dina 4.5, engine, llama.cpp DLL, Supertonic with desktop ORT, trim, first piece, cache); `warm=0 stream=0 steps=8 voice_threads=0 first_chunk=0 trim=0` = unoptimized; `steps= guided=K` (guidance in the first K steps), `model=models/dina-4.5-350m/model-Q8_0.gguf` (Lite), `cache=1`, `demo=1` (WAV of whole answers before/after), `parity=<dir>` (voice vs Python render with the same noise) -> `eval-results/latency/<tag>.md` | ~3 min |
| `eval <brain> <set> [k=v…]` | Evaluator on the PC: real `ToolEngine` + the app's llama.cpp (`dina_native.dll`). Sets `v2` (RW2), `rw200`, `dina-real`, `dev:<batch>[+<batch2>]` (the pipeline's dev set, scored by outcome -> `eval-results/dev-<batch>/`); brains `dina45` (default model `models/dina-4.5/model-Q4_K_M.gguf`; `model=` another GGUF, `grammar=off`, `history=0`), `dina45-scripted` (real Dina 4.5 brain, model scripted from the oracle: the design ceiling), `oracle`; options `filter=`, `limit=`, `policy=ampm:mixed+bulk:confirm`, `tag=` -> `eval-results/<set>/<brain>/report.md`; `turns.jsonl` keeps what the model wrote per turn (`model`) | 5-30 min |
| `data <step> batch=<name>` | Kotlin steps of the data pipeline (`scenarios`, `verify`, `render`) -> `data/batches/<batch>/`. Orchestrator (agents only, no APIs): `python scripts/data/pipeline.py next --batch <name>` merges the agents' answers and lists the next files per model (`agents.json`; Codex prompt `scripts/data/codex.md`; `[claude]` groups need a judge subagent); `run --batch <name>` finishes the batch | ~20 s each |
| `train [k=v…]` | LoRA SFT in WSL (env `ml`, RTX 4060) + GGUF F16 + `llama-quantize`; `data=data/batches/<batch> base=1p2b\|350m out=models/<name> smoke=1` | minutes |
| `keystore` | creates the release signing key once in `~/.dina/` (`DINA_SIGNING` = another `release.properties`); never replaces it | ~5 s |
| `apk [full\|lite]` | release APKs with models, signed with that key (stops without it; checks each is not signed with the debug key) -> `dist/Dina-<version>.apk` (~1 GB) and `dist/Dina-Lite-<version>.apk` (~0.7 GB); both unless one is named | ~3-5 min each |
| `screenshots` | PNG/GIF renders of the character and every screen -> `screenshots/` (git-ignored) | ~1-2 min |
| `icons` | redraws the launcher icons (`res/mipmap-*`) from the character code; rerun after changing her look | ~30 s |

From the Bash tool: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File dev.ps1 test`.

Done = `.\dev.ps1 test` passes. UI changes: run `.\dev.ps1 screenshots` and look at the PNGs (Robolectric
renders Compose on the PC); touch, real animation speed and performance still need the phone, so say so.

## Map (all Kotlin under `android/app/src/main/java/com/kakauet/dina/`)

| Path | Lines | Role |
|---|---:|---|
| `tools/ToolEngine.kt` | 680 | All tool rules and persistent state. Pure Kotlin, fully unit tested. |
| `tools/ToolCommands.kt` | 225 | Typed commands, results, `ToolFailure` codes. |
| `tools/ToolModels.kt` | 95 | `ToolWorld`, `Timer`, `Alarm`, `Stopwatch`, `ShoppingItem`. |
| `tools/ToolPresentation.kt` | 150 | `ContextualWidget` after a turn; Spanish numbers, durations and repeats shared by UI and answers. |
| `tools/ToolWorldJson.kt` | 120 | Persistence. Must stay compatible with app 1.4 state. |
| `tools/AndroidTools.kt` | 120 | AlarmManager, AudioManager and prefs adapters, alert receivers. |
| `brain/Brain.kt` | 100 | `Brain`/`BrainSpec` interfaces + `BrainRegistry` (Dina 4.5 1.2B or 350M by edition). |
| `config/AppVariant.kt` | 30 | Which edition this is (`BuildConfig.LITE`) and the defaults that depend on it (threads by cores for Lite). |
| `brain/dina45/*` | 405 | Dina 4.5 brain: prompt with visible state (`Dina45Prompt`, also renders training data), contract codec, GBNF generated from `Ops`. |
| `dialog/` | 2550 | Dialogue engine (the engine decides): contract types and `Ops` table (`Intent`), references and focus (`Resolver`), pending, undo and policies (`Dialogue`), Spanish templates with every fact and the `say` filter (`Responder`, `Spoken`), state shown to the model (`StateSummary`), joke and fact banks (`FunBank`; texts in `src/main/resources/dina/fun/`). |
| `core/ConversationController.kt` | 145 | The one turn path (voice, text, evaluator). |
| `core/DinaStore.kt` | 145 | Observable state (session, messages, audio level, metrics). |
| `voice/VoicePipeline.kt` | 480 | Mic, wake word, VAD, Android/Moonshine STT, brain warm-up while the user speaks, speech piece by piece; opens the follow-up before the slow stats. |
| `voice/WakeWordDetector.kt`, `WakeGate.kt`, `WakeDecision.kt` | 230 | "Dina": openWakeWord front-end + our head streamed every 80 ms, asleep while the room is silent, two hits over the sensitivity's threshold. Mirrored in `scripts/wakeword/wakeword_core.py`. |
| `voice/SupertonicVoice.kt`, `SupertonicGuidance.kt`, `SupertonicText.kt`, `SpanishSpeech.kt` | 520 | Default voice (Supertonic 3 F2, ONNX Runtime; port of `supertonic` 1.3.1), numbers in words, sentences. The estimator graph is the *body* (`scripts/tts/supertonic_cfg.py`): `SupertonicGuidance` builds the conditioned + unconditioned rows for the first `guidedSteps` steps and one row for the rest. `PiperVoice` is the alternative. |
| `voice/SpeechPlanner.kt`, `SilenceTrim.kt`, `SpeechCache.kt`, `PolishedVoice.kt`, `CommonPhrases.kt` | 480 | Pure speech logic, all unit tested: pieces and pauses by punctuation (first sentence cut at a comma only if it leaves no gap), silence cut around each sentence (the model adds ~0.55 s before and ~0.7 s after), 16-bit LRU audio cache (memory + disk), the voice that applies both, fixed answers prewarmed in the background. |
| `voice/AudioOutput.kt`, `PlaybackClock.kt`, `SpeechStream.kt` | 220 | AudioTrack streaming with the pause each piece asks for, gap measurement, end of audio from `AudioTrack.getTimestamp` (head position + 30 ms without it). |
| `voice/DinaService.kt` | 355 | Foreground service: lifecycle, model loading, intents API. |
| `models/ModelInstaller.kt` | 180 | Models from APK assets (release, copied to `files/models/`; unused ones deleted) or external storage (debug). |
| `ui/theme/Theme.kt` | 300 | Design tokens: light/dark palettes, character colors, fonts, shapes, motion. Start any visual change here. |
| `ui/character/` | 1315 | Dina as a hand-drawn character (Canvas only): brush/boil primitives, poses per `VoiceState`, Brote and Musgo designs. |
| `ui/components/` | 940 | Hand-drawn cards, buttons, bubbles, icons, progress ring, micro-animations. |
| `ui/widgets`, `ui/screens` | 1340 | Tool cards and screens (Inicio voz/texto, Mis cosas, Ajustes, Diagnóstico). |
| `cpp/dina_llm_jni.cpp` | 315 | llama.cpp JNI (tokens reach Java as whole UTF-8 characters). |
| `cpp/prompt_cache.h` | 150 | Reuses prefill across calls via state snapshots and prefills the next prompt while the user speaks. Verify changes with `llm-bench`. |

Tests: `android/app/src/test/java/com/kakauet/dina/` (`TestTools.kt` gives an in-memory engine with a fake clock).
Evaluator and benchmark checks: `eval/` there (`EvalRunTest` runs only via `eval`; `Dina45Script.kt` scripts the model from the oracle;
`Rw200Calls.kt` reads RW200's JSON tool calls).
Training data: `data/` there (scenario generator, round-trip verification and render; `DataRunTest` runs only via `data`), `scripts/data/`
(Python stdlib; `python -m unittest discover -s scripts/data`) and `scripts/train/` (WSL).
Every PC tool lives in `scripts/<topic>/`: `charts`, `data`, `eval`, `models` (`prepare-models.ps1` stages the APK models;
Piper and Moonshine conversion), `train`, `tts` (Supertonic conversion and benches), `wakeword` (the "Dina" detector).
Benchmarks: `benchmark/realworld_v2/` (RW2, readable), `benchmark/dina_real/` (blind: see its README).
Screenshot tests live in `ui/screenshots/` there; `test` skips them, `screenshots` runs only them.
Docs: `docs/ARCHITECTURE.md` (and how to add a model), `docs/contrato.md`, `docs/motor.md`, `docs/datos.md`,
`docs/entrenamiento.md`, `docs/HISTORY.md` (results and lessons), `docs/ROADMAP.md`.
Models and where to get them: `models/README.md`. Licences: `THIRD_PARTY.md`.

## Do not read (token sinks)

- `android/third_party/llama.cpp/`: 200 MB vendored submodule (hidden from search via `.ignore`).
- `models/`, `data/`, `android/model-assets/`, `dist/`, `**/build/`: binaries and datasets.
- `eval-results/`: read `report.md` / `summary.json`; `turns.jsonl` only with `grep`.
- `benchmark/dina_real/frases.tsv`: never (blind test).
- `benchmark/realworld200/episodes/*.jsonl`: read single lines (`head -1`), never whole files.

## Rules

1. The model only interprets; `ToolEngine` decides and executes. Brains never touch state.
2. Spoken confirmations come from real tool results (`Responder`, `ToolPresentation`), never from model text.
3. The contract only grows: never change the meaning of an existing op, so training data keeps working.
   `Dina45Prompt` renders training data too: any change to it needs retraining.
4. Keep `ToolWorldJson` able to read existing user data (alarms, lists).
5. Nothing in `benchmark/` is used to generate training data or sent to an external API. `realworld200` and
   `dina_real` are never used to tune anything; RW2 is for iterating and regression.
6. Build constraints: AGP 8.13, compileSdk 36, minSdk 34, arm64 only, JDK 17.
   Compose BOM stays at 2026.06.01 (newer ones need AGP 9.1 and compileSdk 37).
7. New behaviour in `tools/`, `brain/`, `dialog/` or `core/` needs a JVM test.
8. Never run `sed -i` (or any bulk rewrite) over binary files: filter by extension first.
9. One code base, two editions. `BuildConfig.LITE` may only choose the brain (`BrainRegistry`) and defaults (`AppVariant`);
   do not branch behaviour on it anywhere else. A new voice or UI feature must work in both.
10. Never modify `models/tts/supertonic3-fp32` (read-only, verified against Hugging Face): `onnx2tf` overwrites its input, so
    always work on copies. The WSL `/tmp` is wiped: use `~/dina-tts`. From Git Bash call WSL with `MSYS_NO_PATHCONV=1` and script files.
