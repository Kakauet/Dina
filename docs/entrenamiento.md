# Entrenamiento

## Dónde

En local, en una **RTX 4060 de 8 GB** (WSL, entorno conda `ml`: torch, transformers, trl, peft,
unsloth). `.\dev.ps1 train` entrena, exporta a GGUF F16 y cuantiza con la llama.cpp de la app.

```powershell
.\dev.ps1 train data=data/batches/piloto1+data/batches/piloto2+data/batches/correccion1+data/batches/refuerzo1+data/batches/conversiones1+data/batches/refuerzo2+data/batches/conversiones2 out=models/dina-4.5 quants=Q4_K_M
.\dev.ps1 train base=350m smoke=1     # 5 pasos con unos pocos ejemplos: comprueba toda la cadena
```

Dina 4.5 se entrenó con esos siete lotes. Antes se volvieron a renderizar `piloto1`, `piloto2` y
`correccion1` con el motor actual (`.\dev.ps1 data render batch=<lote>`), y los cuatro lotes nuevos
se cerraron con `pipeline.py run` y sus informes revisados.

El entrenador excluye de **entrenamiento** los antiguos `no(zonas)` y los `say` que mencionan
«cuerpo» o «internet» sin que la frase actual pregunte por ese tema. Conserva los datos canónicos
y el conjunto de desarrollo. `train-report.json` registra los descartes de cada lote.

Opciones para comparar técnicas, con semilla fija:

- `epochs=`, `lr=` y `rank=` controlan épocas, tasa de aprendizaje y rango LoRA.
- `weights=1+1+1+2+2` asigna un peso a cada lote en el orden de `data=`. Un peso entero repite
  ejemplos; la parte fraccionaria toma una muestra reproducible. El desarrollo nunca se pondera.
- `dedup=1` elimina pares exactos prompt/salida repetidos entre lotes y ejemplos de entrenamiento
  cuyo prompt también está en desarrollo. Se aplica **antes** de los pesos, para que estos sigan
  teniendo efecto. El desarrollo se conserva entero.
- `prepare_only=1` muestra los recuentos sin cargar el modelo, entrenar ni cuantizar.

Pruebas de la preparación, sin GPU: `python -m unittest discover -s scripts/train`.

Dina 4 (2.967 ejemplos, 2 épocas): 28 minutos en el 1.2B, ~640 tokens/s y 10 GiB de memoria
reservada (con memoria compartida). Dina 4.5 (7.736 ejemplos, 2 épocas): 54,5 minutos en el 1.2B
(~810 tokens/s) y 28,9 en el 350M.

## Cómo

- SFT con LoRA r=32 sobre todas las capas lineales (atención, convoluciones y MLP); lote efectivo
  de 16 (4 × 4 en el 1.2B), 2 épocas, tasa de aprendizaje 2e-4.
- Pérdida solo sobre la salida del modelo y `<|im_end|>`, no sobre el prompt.
- El punto de control se elige con el **conjunto de desarrollo** del pipeline, nunca con RW2 ni con
  Dina-Real.
- Mismo prompt en entrenamiento y en la app: lo renderiza `Dina45Prompt` (Kotlin) al generar los
  datos, y un test comprueba que es idéntico byte a byte al que manda la app.
- Un turno de historial (`[antes]`). Sin él (`history=0`), el 350M quedó prácticamente igual
  (desarrollo 80,0 frente a 80,8 %; RW2 52,3 frente a 51,9 %).
- El 1.2B queda entre 3 y 8 puntos por encima del 350M en acción con los mismos datos; la app usa
  el 1.2B.

Salida en `models/<nombre>/`: `model-f16.gguf`, las cuantizaciones y `train-report.json` (datos,
pasos, pérdidas y coincidencia exacta en desarrollo).

En la fase 3, la comparación de cada Q4_K_M usa RW2 v2.0 (432 episodios) y RW2 v2.1 (496),
la categoría `conversiones` y `dev:piloto1+piloto2`; Dina 4 con el motor actual es la referencia.
Se informa del IC de Wilson al 95 % y de McNemar pareado por categoría (p < 0,05 para una bajada
significativa). Una pasada de corrección usaría únicamente errores en escenarios de entrenamiento;
Dina 4.5 no la necesitó: el primer entrenamiento con los siete lotes y los ajustes de Dina 4 pasó
todas las puertas y se eligió sin más iteraciones (docs/HISTORY.md).
Dina-Real se mide **una sola vez al final**, con el candidato elegido; nunca decide ajustes.

Comparación local por episodios (solo extrae ids y aciertos de `turns.jsonl` con `rg`):
`python scripts/eval/compare.py eval-results/v2/<referencia> eval-results/v2/<candidato>`.
La sección nueva se informa aparte si la referencia solo tenía v2.0.

## Cuantización y validación del GGUF

1. Exportar a GGUF F16 con `convert_hf_to_gguf.py` de la llama.cpp vendorizada (misma versión
   que la app).
2. Cuantizar con `llama-quantize` (`android/build/llama-tools`): el 1.2B en Q4_K_M, Q5_K_M y
   Q8_0; el 350M en Q8_0 y Q6_K.
3. Evaluar **cada GGUF** con `.\dev.ps1 eval dina45 v2 model=…` y con el conjunto de desarrollo.
   Se acepta la cuantización más pequeña que pierda como mucho 1,5 puntos de acción frente al F16.
4. `.\dev.ps1 llm-bench` con el GGUF elegido: exactitud de `PromptCache` y latencia en el PC.

Una cifra medida en Python o en bf16 nunca es una cifra de la app: solo cuenta el GGUF con la
llama.cpp de la app.
