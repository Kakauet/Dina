# Componentes de terceros

El código propio de Dina es MIT ([LICENSE](LICENSE)). Lo que sigue es de terceros y conserva su
licencia. Comprobado en la fuente de cada uno en octubre de 2026; las licencias de los modelos
cambian a veces, así que vuelve a mirarlas antes de redistribuirlos.

## Código y bibliotecas

| Componente | Uso | Licencia | Fuente |
|---|---|---|---|
| llama.cpp / ggml (`b10687`, submódulo) | Inferencia del LLM por JNI | MIT, © The ggml authors | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) |
| ONNX Runtime Android 1.23.0 | Supertonic en el móvil (y en el PC para `latency`; los scripts de `scripts/tts/` usan `onnx` y `onnxruntime` de Python, ambos MIT) | MIT, © Microsoft | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) |
| Moonshine Voice 0.1.0 (`ai.moonshine:moonshine-voice`) | STT opcional y motor de la voz Piper | MIT, © Useful Sensors, Inc. (Moonshine AI) | [moonshine-ai/moonshine](https://github.com/moonshine-ai/moonshine) |
| `scripts/models/moonshine-tools/*.py` | Conversión de modelos a ORT | MIT, © Useful Sensors, Inc. (copiados de Moonshine) | ídem |
| `supertonic` 1.3.1 | `SupertonicVoice.kt` y `SupertonicText.kt` son un port a Kotlin de su código de inferencia | MIT, © Supertone Inc. | [supertone-inc/supertonic](https://github.com/supertone-inc/supertonic) |
| Jetpack Compose (ui, foundation, Material 3), AndroidX (core, activity, lifecycle) | UI y Android | Apache 2.0 | [developer.android.com/jetpack/androidx](https://developer.android.com/jetpack/androidx) |
| Kotlin, kotlinx.coroutines | Lenguaje y concurrencia | Apache 2.0 | [JetBrains](https://github.com/Kotlin/kotlinx.coroutines) |
| OkHttp 4.12 | Dependencia de Moonshine Voice | Apache 2.0 | [square/okhttp](https://github.com/square/okhttp) |
| Fredoka, Nunito | Tipografías de la UI | SIL Open Font License 1.1 (textos en `android/app/src/main/assets/licenses/`) | [Fredoka](https://github.com/hafontia/Fredoka-One), [Nunito](https://github.com/googlefonts/nunito) |

Solo para tests y herramientas del PC (no van en la APK): JUnit 4.13 (EPL 1.0), Robolectric 4.17
(MIT), Roborazzi 1.76 (Apache 2.0), org.json 20250517 (dominio público). El entrenamiento usa
PyTorch, Transformers, TRL, PEFT y Unsloth, y el wake word openWakeWord (código Apache 2.0),
piper-tts, NumPy, SciPy y scikit-learn; ninguno se redistribuye.

## Modelos (no están en el repositorio)

| Modelo | Uso | Licencia | Fuente |
|---|---|---|---|
| LFM2.5-1.2B-Instruct (Liquid AI) | Base de Dina 4 y Dina 4.5 | **LFM Open License v1.0**: basada en Apache 2.0; uso comercial gratis solo si la entidad factura menos de 10 M USD al año. Quien distribuya el modelo o un derivado debe entregar una copia de la licencia y marcar los cambios | [Hugging Face](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct), [licencia](https://www.liquid.ai/lfm-license) |
| Dina 4.5 1.2B (LoRA fusionado, GGUF Q4_K_M) | Cerebro de la app | Derivado de LFM2.5: LFM Open License v1.0 | este proyecto |
| Supertonic 3 (Supertone) | Voz por defecto (pesos cuantizados a 8 bits aquí, convoluciones 1×1 reescritas como MatMul y la guía sin clasificador separada del estimador: obra derivada, con las mismas condiciones de uso) | **BigScience OpenRAIL-M**: libre, con restricciones de uso (anexo A) que deben trasladarse a quien lo reciba; su `LICENSE` va dentro de la APK | [Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3) |
| Piper `es_ES-sharvard-medium` | Voz alternativa | **CC BY 3.0** según su ficha (dataset Sharvard, Universidad de Edimburgo); ojo: es un ajuste fino de la voz `en_US-lessac-medium`, cuyo dataset (Blizzard 2013 Lessac) solo permite **uso no comercial de investigación** | [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/es/es_ES/sharvard/medium) |
| Moonshine Spanish Base | STT opcional | **Moonshine Community License**: no es MIT; el uso comercial exige registrarse, atribuir («Powered by Moonshine AI») y facturar menos de 1 M USD al año | [moonshine-ai/moonshine `LICENSE`](https://github.com/moonshine-ai/moonshine/blob/main/LICENSE) |
| openWakeWord: `melspectrogram.onnx`, `embedding_model.onnx` | Front-end del wake word | **CC BY-NC-SA 4.0** (no comercial) según su README, que cubre todos los modelos incluidos; el embedding deriva de `speech_embedding` de Google (Apache 2.0) | [dscripka/openWakeWord](https://github.com/dscripka/openWakeWord) |
| Cabeza del detector «Dina» (`dina_wakeword_head.onnx`) | Detector de «Dina» | Entrenada sobre el front-end anterior: se distribuye como **CC BY-NC-SA 4.0**. Los datos son síntesis local con voces Piper, Supertonic y de Windows (fichas de cada voz en `scripts/wakeword/reports/voice_licenses/`), grabaciones del propietario, que no se publican, y audio público: lectores de [Multilingual LibriSpeech](https://huggingface.co/datasets/facebook/multilingual_librispeech) en español (Pratap et al., 2020, **CC BY 4.0**) y ruido de [DEMAND](https://zenodo.org/records/1227121) (Thiemann, Ito y Vincent, 2013, **CC BY 4.0**) | `scripts/wakeword/reports/voice_sources.json`, `scripts/wakeword/import_public.py` |

### Consecuencia práctica

Dina es un proyecto personal y no comercial. La APK completa reúne modelos con licencias **no
comerciales** (openWakeWord y la cabeza del wake word; la voz Piper por su origen) o con
condiciones (LFM, Moonshine, OpenRAIL-M). Quien quiera usar Dina con fines comerciales tiene que
sustituir esos modelos o conseguir las licencias correspondientes; el código MIT no cambia eso.
