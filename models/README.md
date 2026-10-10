# models/

Pesos de los modelos. Excluidos de Git por tamaño y licencia ([THIRD_PARTY.md](../THIRD_PARTY.md)).
`scripts/models/prepare-models.ps1` los enlaza en `android/model-assets/<edición>/`, que se empaqueta en la
APK de release (`.\dev.ps1 apk`). Las APK de depuración no los incluyen: se copian al móvil con
`.\dev.ps1 push-models [lite]`. Las dos ediciones solo difieren en el LLM.

## Modelos de la APK

| Ruta | Contenido | Origen | Licencia |
|---|---|---|---|
| `dina-4.5/model-Q4_K_M.gguf` | LLM de Dina: LFM2.5-1.2B-Instruct + LoRA, Q4_K_M (731 MB) | `dina-4.5-1.2b-Q4_K_M.gguf` de las [releases](https://github.com/Kakauet/Dina/releases), o `.\dev.ps1 train` ([docs/entrenamiento.md](../docs/entrenamiento.md)) | LFM Open License v1.0 ([LiquidAI/LFM2.5-1.2B-Instruct](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct)) |
| `dina-4.5-350m/model-Q8_0.gguf` | LLM de Dina Lite: LFM2.5-350M + LoRA, Q8_0 (379 MB) | `dina-4.5-350m-Q8_0.gguf` de las releases, o `.\dev.ps1 train base=350m` | LFM Open License v1.0 ([LiquidAI/LFM2.5-350M](https://huggingface.co/LiquidAI/LFM2.5-350M)) |
| `tts/supertonic3/` | TTS por defecto: Supertonic 3, estilo F2 (102 MB). Pesos de 8 bits, convoluciones 1×1 como MatMul y CFG separada del estimador (`onnx/guidance.bin`) | [Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3) @ `724fb5ab` en `tts/supertonic3-fp32/`, convertido con `.\dev.ps1 tts-models` (WSL, entorno `tts`) | OpenRAIL-M |
| `voice/piper-android/es_ES-sharvard-medium.ort` + `.onnx.json` | TTS alternativo: Piper Sharvard Medium en ORT | [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/es/es_ES/sharvard/medium), convertido con `scripts/models/prepare_piper_sharvard.py` y `scripts/models/moonshine-tools/` | CC BY 3.0 (ver THIRD_PARTY) |
| `voice/moonshine-base-es/` | STT opcional: Moonshine Spanish Base en ORT (`encoder_model.ort`, `decoder_model_merged.ort`, `tokenizer.bin`) | `pip install moonshine-voice` y `moonshine-voice download --stt --language es` | Moonshine Community License |
| `voice/openwakeword/` | Front-end del wake word: `melspectrogram.onnx`, `embedding_model.onnx` | [openWakeWord](https://github.com/dscripka/openWakeWord/releases) v0.5.1 | CC BY-NC-SA 4.0 |
| `wakeword/dina_wakeword_head.onnx` | Cabeza del wake word (MLP 1536 → 128 → 1, 0,8 MB) | Releases, o `.\dev.ps1 wake-train` y `export_head.py` ([scripts/wakeword/](../scripts/wakeword/README.md)) | CC BY-NC-SA 4.0 |

## Solo para las herramientas del PC

| Ruta | Uso |
|---|---|
| `tts/supertonic3-fp32/` | Grafos originales de Supertonic 3 (398 MB), de solo lectura. Las conversiones trabajan sobre copias. |
| `voice/piper/*.onnx` | Voces Piper para sintetizar el corpus del wake word. |
| `wakeword/dina_wakeword.npz` | Cabeza del wake word en NumPy, salida de `wake-train`. |
| `*/model-f16.gguf`, `dina-4/`, `dina-4-preview/` | GGUF F16 para recuantizar y modelos anteriores para comparar. |
| `train-<base>/` o `out=` | Salidas de `.\dev.ps1 train`: GGUF F16, cuantizaciones e informe. |
