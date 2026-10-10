# data/

Datos locales generados por las herramientas del repositorio. Excluidos de Git.

| Carpeta | Contenido | Origen |
|---|---|---|
| `batches/<lote>/` | Lotes de entrenamiento y desarrollo: escenarios, frases, verificación y ejemplos con el prompt de la app. Dina 4: `piloto1`, `piloto2`, `correccion1`. Dina 4.5: los anteriores más `refuerzo1`, `conversiones1`, `refuerzo2` y `conversiones2`. | `scripts/data/pipeline.py`, `.\dev.ps1 data` ([docs/datos.md](../docs/datos.md)) |
| `wakeword/corpus/`, `wakeword/speech/` | Audio sintético para el detector «Dina»: la palabra, palabras parecidas y frases de la app. | `.\dev.ps1 wake-synthesize` |
| `wakeword/downloads/`, `wakeword/public/` | Voces (Multilingual LibriSpeech) y ruido (DEMAND) públicos. | `scripts/wakeword/import_public.py` |
| `wakeword/real/` | Grabaciones reales del propietario. Privadas. | — |

Ningún archivo de `benchmark/` se usa para generar datos ni sale del PC. El pipeline descarta en local
las frases demasiado parecidas a las de los benchmarks.
