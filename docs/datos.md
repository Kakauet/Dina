# Datos de entrenamiento

Cómo se generan los datos de Dina. Las etiquetas siempre salen del motor: los modelos de lenguaje
solo escriben frases, las interpretan de vuelta y las puntúan. Código: `scripts/data/`
(orquestador en Python, solo biblioteca estándar) y los pasos Kotlin de
`android/app/src/test/java/com/kakauet/dina/data/` (`.\dev.ps1 data`). Los lotes viven en
`data/batches/<lote>/` (fuera de Git).

## Reparto del trabajo

| Paso | Quién | Por qué |
|---|---|---|
| Escenarios: reloj, estado con distractores, intenciones canónicas, trayectoria multiturno | Código (Kotlin, sobre `ToolEngine`) | Etiquetas verdaderas por construcción |
| Ficha en español sin la formulación | Código | Controla qué dice la frase sin dictarla |
| **Frases** (3–5 variantes por ficha y persona, como se dirían en voz alta) | **Modelo escritor** | Lo único que aporta un modelo: cómo habla la gente |
| Ida y vuelta: frase + estado → acciones del contrato, ejecutadas y comparadas | Modelo de **otra familia** + `ToolEngine` | Descarta frases que no dicen lo que pide la ficha |
| Naturalidad (1–3) y respuestas de charla | Juez de una tercera familia | Quita lo peor y rechaza respuestas que afirman hechos |
| Ruido de reconocimiento de voz | Reglas en código (~35 %) | Imita lo que hace el reconocedor de Android |
| Etiquetas, respuestas de Dina y render | Código | Ningún modelo escribe etiquetas |
| Informe por lote | Código | Diversidad, rechazos, cuotas |

Pasos del orquestador: `scenarios` (Kotlin) → `generate` → `roundtrip` → `verify` (Kotlin) →
`judge` → `contamination` → `select` (con ruido) → `render` (Kotlin) → `report`. Cada paso se
puede reanudar: salta lo que ya está hecho.

## Lotes con agentes

Los datos los escriben agentes de ayuda; ningún paso llama a una API:

```powershell
python scripts/data/pipeline.py next --batch <lote> --n 2000
```

`next` junta lo que ya han escrito los agentes, exporta la siguiente tanda en archivos
`in-NN.txt` y lista qué modelo debe hacer cada uno. El reparto está en `scripts/data/agents.json`:
escritores Luna 6 (60 %), Sol 6.1 y Sol 6 (20 % cada uno); la ida y vuelta la hace otra familia y
el juez es de una tercera (Claude Sonnet si escribe Codex), así que escritor, comprobador y juez
nunca son de la misma familia. Un lote puede tener sus propios escritores: `next --writers
claude-haiku-5.5,claude-sonnet-5.5` en la primera llamada los guarda en `writers.json` del lote
(así se escribieron `refuerzo2` y `conversiones2`, comprobados y juzgados por Codex).
`scripts/data/codex.md` es el prompt para que Codex haga su parte; los grupos `[claude]` los
hacen subagentes de Claude Code. Cuando `next` dice `listo`, el lote se termina con
`pipeline.py run --batch <lote>`.

## Conjunto de desarrollo

Sale de una familia de modelos que no escribe frases de entrenamiento y de personas reservadas
(`personas.json`, `reserved=true`), así que los agentes no lo escriben. Mide si el modelo
generaliza fuera del estilo de los escritores: `.\dev.ps1 eval dina45 dev:piloto1+piloto2`,
puntuado por resultado como RW2.

## Chistes y curiosidades

Los bancos de `fun.joke()` y `fun.fact()` son texto en
`android/app/src/main/resources/dina/fun/` (`chistes.txt`, `curiosidades.txt`; una línea cada
uno, `#` para comentarios). Para añadir: que un modelo los escriba (aptos para todos los públicos,
de una a tres frases y máximo 40 palabras, que se entiendan al oírlos una vez; las curiosidades,
ciertas y que no caduquen) y que un juez de otra familia se quede solo con los buenos.

## Privacidad y contaminación

- No se envía nada de RW200, RW2 ni Dina-Real a ningún modelo ni API. Los prompts son sintéticos
  (ficha, persona, contrato).
- Filtro local, sin modelos: se descarta toda frase con un 8-grama normalizado en común con un
  benchmark o casi igual a alguna de sus frases (MinHash de trigramas de caracteres,
  Jaccard > 0,7). Solo informa de cuántas descarta, nunca de cuáles.
- Las fichas de entrenamiento salen de plantillas de código distintas de las de Dina-Real.

## Cuotas de la matriz

Las de `ScenarioGenerator.PHENOMENA` (fenómeno × dominio × complejidad del estado; objetivos en
`scripts/data/pipeline_targets.py`), con estado no vacío en ~70 % de los ejemplos, 20 % de
variantes latinoamericanas y ~35 % con ruido de reconocimiento. Cada lote termina con el informe de
diversidad: secuencias de acciones por celda, casi duplicados, las dos primeras palabras en ≤ 5 %
de las frases y la cuota de cada escritor y persona.

## Lo aprendido

- **Más variedad pesa más que más datos.** Con un solo escritor, doblar los datos subió el
  desarrollo solo de 76 a 82 %. El lote `correccion1`, escrito por otras familias y dirigido a los
  fallos, llevó RW2 de 62 a 71 %.
- **La diversidad se mide, no se supone**: secuencias de acciones distintas, no filas.
