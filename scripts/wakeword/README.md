# Wake word «Dina»

Entrenamiento y evaluación del detector. `wakeword_core.py` reproduce exactamente lo que ejecuta la app
(`voice/WakeWordDetector.kt`, `WakeGate.kt`, `WakeDecision.kt`):

1. **Front-end de openWakeWord** (congelado; `melspectrogram.onnx`, `embedding_model.onnx`): cada 80 ms
   (1.280 muestras), un mel de los últimos 110 ms y un embedding de los últimos 76 frames.
2. **Cabeza propia** (`dina_wakeword_head.onnx`): MLP `1536 → 128 → 1` sobre los últimos 16 embeddings (~2 s).
3. **Decisión**: dos pasos consecutivos sobre el umbral de la sensibilidad (Baja, Normal, Alta) y 2 s de
   bloqueo tras cada activación.
4. **Puerta de silencio**: tras 2 s de silencio (RMS por debajo de 3 × el ruido de fondo, acotado entre 40
   y 150) no se ejecuta ningún modelo. Al volver el sonido se reinicia el stream con los últimos 2,2 s,
   de modo que la puntuación es idéntica a la de un stream continuo.

El mel de openWakeWord normaliza cada llamada por su frame más fuerte, así que una ventana de 2 s calculada
de una vez no produce las mismas características que el stream. Por eso el entrenamiento y la evaluación
usan el stream de la app (`FrontEnd.features`); `test_pipeline.py` verifica que coinciden.

## Resultados

Split de test (voces, textos y sesiones no usados en entrenamiento ni calibración), con la política de la
app. Tomas reales: 24 grabaciones del propietario en 6 estilos. TTS: 60 voces × 14 condiciones, con y sin
petición a continuación. Falsas activaciones: 6,3 h de conversación sin «Dina» (5 h de MLS, la mitad sobre
ruido de DEMAND, y frases de la app).

| Detector | Tomas reales | TTS | Susurro TTS | Falsas/h |
|---|---:|---:|---:|---:|
| **Actual, Alta (0,95)** | **23/24** | **75 %** | 58 % | 2,2 |
| **Actual, Normal (0,998)**, por defecto | 16/24 | 51 % | 7 % | **0,16** |
| Actual, Baja (0,999) | 13/24 | 46 % | 3 % | 0,16 |
| App 2.2.1 (VAD + cabeza anterior), umbral 0,5 | 16/24 | 36 % | | 166 |
| App 2.2.1, umbral 0,999 | 8/24 | 13 % | | 24 |

Iguala o supera al detector anterior en aciertos con entre 15 y 1.000 veces menos falsas activaciones.
Limitación conocida: «Dina» dentro de una frase continua («Hola, Dina, pon…») se detecta solo en el 23 %
de los casos en Alta. Las tomas reales comparten sesión con las de entrenamiento y son de una sola voz;
la medida de referencia es la del móvil (Diagnóstico › Palabra «Dina» › Registrar intentos).

Coste en PC: 1,3 ms por paso de 80 ms; nada en silencio. Detalle en `reports/` (`training.json`,
`calibration.json`, `evaluation.json`, `old_detector.json`).

## Datos

En `data/wakeword/` (excluido de Git), con splits de entrenamiento, calibración y test que no comparten
voces, textos ni sesiones:

| Carpeta | Contenido | Script |
|---|---|---|
| `corpus/` | «Dina», palabras parecidas y frases cortas con voces Piper, Supertonic y Windows | `synthesize.py`, `synthesize_windows.ps1` |
| `speech/` | Frases de la app con voces TTS; las que contienen «Dina» son positivos de evaluación | `synthesize_speech.py` |
| `public/` | Voz: [Multilingual LibriSpeech](https://huggingface.co/datasets/facebook/multilingual_librispeech) en español (9/5/5 h, lectores disjuntos). Ruido: [DEMAND](https://zenodo.org/records/1227121) (cocina, salón, lavadora, cafetería, coche). CC BY 4.0 | `import_public.py` |
| `real/` | 24 tomas reales del propietario. Privadas | `real_corpus.py` |

Escenas: la palabra, con 14 aumentos (`augment.py`: susurro, distancia, grito, ruido, tono…), tras
silencio, ruido doméstico o conversación, y a veces seguida de una petición. Negativos: el audio previo a
la palabra, palabras parecidas en contexto y conversaciones largas (la mitad sobre ruido real). Dos rondas
de *hard negative mining* añaden los pasos que más confunden.

Audio público (~460 MB) en `data/wakeword/downloads/`: de MLS, `spanish/9_hours-…`, `spanish/dev-…` y
`spanish/test-…` (`.parquet`); de DEMAND, `ch01.wav` y `ch09.wav` de `DKITCHEN`, `DLIVING`, `DWASHING`,
`PCAFETER` y `TCAR` (`_16k.zip`) en `downloads/DEMAND/<SALA>/`.

## Reproducción

Requisitos: Python 3.12 en `.venv-wakeword` con `requirements.txt`, y los modelos de `models/voice/`
([models/README.md](../../models/README.md); `download_voices.py` descarga las voces Piper).

```powershell
.\dev.ps1 wake-synthesize      # corpus TTS, frases y audio público                ~1 h, una vez
.\dev.ps1 wake-train           # -> models/wakeword/dina_wakeword.npz              ~25 min
.\.venv-wakeword\Scripts\python.exe scripts/wakeword/export_head.py models/wakeword/dina_wakeword.npz models/wakeword/dina_wakeword_head.onnx
.\dev.ps1 wake-calibrate       # umbrales por sensibilidad -> reports/calibration.json
.\dev.ps1 wake-eval            # test, una sola vez -> reports/evaluation.json
```

Los umbrales se copian a `WakeSensitivity` (`voice/WakeDecision.kt`). Objetivos de calibración, en falsas
activaciones por hora de conversación: Alta 2, Normal 0,7, Baja 0,2. Tests:
`python -m unittest discover -s scripts/wakeword` desde la raíz.

La cabeza se entrena sobre el front-end de openWakeWord (CC BY-NC-SA 4.0) y se distribuye con la misma
licencia ([THIRD_PARTY.md](../../THIRD_PARTY.md)).
