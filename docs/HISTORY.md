# Historia de Dina

Cómo llegó Dina hasta aquí y qué se aprendió por el camino. Todas las cifras son del evaluador del
repositorio (`.\dev.ps1 eval`) sobre el GGUF cuantizado que se instala en el móvil, con la
llama.cpp de la app; los intervalos son de Wilson al 95 %.

## Modelos

| Modelo | Base | Cómo funciona | Datos | RW2 | Dina-Real |
|---|---|---|---|---:|---:|
| Dina 2 | LFM2.5-350M | Llamadas JSON | 5.816 conversaciones | 35,6 % | – |
| Dina 2.5 | LFM2.5-350M | Llamadas JSON | 5.000 conversaciones | 38,7 % | – |
| Dina 3 (app 2.0) | LFM2.5-1.2B | Llamadas JSON, atajos y plantillas | 6.000 conversaciones | 45,4 % | 42,0 % |
| Dina 3 (app 2.1) | LFM2.5-1.2B | Sin atajos; respuestas con todos los datos | 6.000 conversaciones | 56,3 % | 54,0 % |
| Dina 4 Preview | LFM2.5-1.2B | Contrato + motor de diálogo | 1.275 ejemplos | 62,0 % | 62,0 % |
| Dina 4 (app 2.2–2.2.2) | LFM2.5-1.2B | Contrato + motor de diálogo | 2.967 ejemplos | 70,1 % | 74,0 % |
| **Dina 4.5** (app 2.3) | LFM2.5-1.2B | Contrato + motor ampliados (conversiones, ciudades) | 7.736 ejemplos | **86,1 %** | **85,0 %** |

Dina 2, 2.5 y 3 escriben llamadas a herramientas en JSON y redactan la respuesta. Dina 4 solo
escribe acciones de un contrato compacto; el motor de diálogo resuelve, ejecuta y responde con
plantillas. Con el mismo LFM2.5-1.2B, RW2 sube de 56,3 a 70,1 %. Dina 4.5 es el mismo diseño con
datos dirigidos a los fallos de Dina 4 y escritos por más familias de modelos: 86,1 % en RW2 v2.0.

## Versiones de la app

| Versión | Qué trajo |
|---|---|
| 1.x | Wake word propio, Android STT o Moonshine, llama.cpp nativo, Piper. Cerebro: Dina 3. |
| 2.0 | Repositorio nuevo: motor de herramientas en Kotlin puro (`ToolEngine`), interfaz `Brain`, un único camino de turno y UI en Jetpack Compose con el personaje dibujado a mano. |
| 2.1 | Dina 3 sin atajos y respuestas que dicen todos los datos del resultado: RW2 45,4 → 56,3 %. |
| 2.2 | Dina 4, voz Supertonic 3 y prefill mientras el usuario habla. |
| 2.2.1 | Pulido sin cambiar el modelo: errores del motor, la voz y el ciclo de vida corregidos, textos de la app revisados, Ajustes reordenado, pipeline de datos solo con agentes y documentación al día. |
| 2.2.2 | Detector «Dina» nuevo: front-end de openWakeWord en streaming exacto cada 80 ms, cabeza entrenada con ese stream, voces reales (MLS) y ruido real (DEMAND), puerta de silencio que lo duerme y sensibilidad Baja/Normal/Alta calibrada. En test: tus tomas 16/24 (Normal) y 23/24 (Alta) con 0,16 y 2,2 falsas activaciones/h, frente a 16/24 y 166/h del anterior. Lección: el mel de openWakeWord normaliza cada llamada, así que hay que entrenar con las características exactas de la app. |
| 2.3 | Dina 4.5 y conversiones de unidades (también de tazas a gramos en la cocina), hora de otras ciudades, fiestas con nombre y días del mes, «mañana» de madrugada, freno a productos inventados y Dina nunca dice ser una persona. RW2 v2.0 72,5 → 86,1 % y Dina-Real 74,0 → 85,0 %. |
| 2.4 | Voz más rápida y dos ediciones. Supertonic: sin los ~0,55 s de silencio inicial y ~0,7 s final que el modelo añade a cada frase, guía sin clasificador solo en los primeros pasos (desarrollada en el banco; los pasos por defecto no se han tocado hasta elegirlos de oído), convoluciones 1×1 como MatMul, caché de audio, primer trozo corto y pausas por puntuación. Edición **Dina Lite** con Dina 4.5 350M. Pasos por defecto: 8 en Dina (con selector 4/8 en Ajustes › Voz) y 4 en Dina Lite (sin selector). Primer sonido en el PC: de ≈1,7 s a ≈0,8 s con los mismos 8 pasos de la 2.3 y ≈0,54 s con 4. |

## Dina 4

### Medir primero

Antes de tocar el modelo se rehízo el evaluador en Kotlin, con el `ToolEngine` y la llama.cpp de
la app, y se crearon dos benchmarks nuevos:

- **RW2**: 432 episodios en 11 categorías, 20 % en variantes latinoamericanas y 123 con ruido de
  reconocimiento. Se usa para iterar.
- **Dina-Real**: 100 situaciones con 144 frases dictadas; prueba ciega, nunca se usa para ajustar
  nada.

El evaluador nuevo mostró que el anterior contaba como fallos aciertos reales, y que los atajos de
la app 2.0 hacían fallar 12 episodios sin arreglar ninguno. Quitarlos y decir todos los datos en las
respuestas fue la app 2.1.

### Diseño

Contrato compacto ([contrato.md](contrato.md)) y motor de diálogo ([motor.md](motor.md)). Con un
modelo perfecto (`dina4-scripted`), el diseño llega al **99,5 %** de RW2: todo lo que falla después
es del modelo. El prompt ocupa 121 tokens de mediana (máximo 199) y 90 de ellos son fijos.

### Datos y entrenamiento

Datos generados por modelos de varias familias y verificados en `ToolEngine` ([datos.md](datos.md));
LoRA sobre LFM2.5-1.2B en una RTX 4060 ([entrenamiento.md](entrenamiento.md)).
`policy=ampm:mixed+bulk:direct`; episodios en %.

| Modelo | Ejemplos | Desarrollo | RW2 | Dina-Real | Afirmaciones falsas |
|---|---:|---:|---:|---:|---:|
| Dina 4 Preview 350M (Q8_0) | 1.275 | 80,8 | 51,9 | 53,0 | 0 |
| Dina 4 Preview 1.2B (Q4_K_M) | 1.275 | 82,3 | 62,0 | 62,0 | 0 |
| Dina 4 350M (Q8_0) | 2.967 | 87,7 | 65,7 | 64,0 | 0 |
| **Dina 4 1.2B (Q4_K_M)** | 2.967 | 93,8 | 70,8 | 74,0 | 0 |

El 350M queda entre 3 y 8 puntos por debajo en acción; la app usa el 1.2B. Con el prefill
mientras el usuario habla, RW2 pasa de 70,8 a **70,1 %** [66–74] (cambia un poco la aritmética) y
Dina-Real se queda en **74,0 %** [65–82], con 0 afirmaciones falsas en 132 turnos.

RW2 de Dina 4 por categoría:

| Categoría | Éxito | Categoría | Éxito |
|---|---:|---|---:|
| Fuera de alcance | 94,9 % | Ambigüedad | 65,0 % |
| Charla | 91,2 % | Referencias | 59,1 % |
| Directas | 87,5 % | Correcciones | 57,5 % |
| Consultas | 82,5 % | Multiacción | 57,5 % |
| Errores | 74,4 % | Pendiente | 57,5 % |
| | | Interrupciones | 47,2 % |

Pregunta cuando debe en el 73,6 % de los casos y cambia algo sin que tocara en el 12,7 % de los
turnos en que no debía actuar. En la charla funciona, pero a veces se inventa capacidades
(«organizar la agenda») y repite «no tengo cuerpo ni internet».

### Latencia

Fin de la transcripción → primer audio, en el PC (`.\dev.ps1 latency`, mediana de 24 turnos):

| Tramo | Sin optimizar | App 2.2 |
|---|---:|---:|
| Prefill | 700 ms | 111 ms |
| Decodificación | 226 ms | 134 ms |
| Primera síntesis | 1.549 ms (respuesta entera, 8 pasos) | 346 ms (primera frase, 3 pasos) |
| **Total** | **2.533 ms** | **612 ms** |

Cómo: el prompt hasta la frase se precalcula mientras el usuario habla (`PromptCache::warm`), la
gramática prueba primero el token más probable, el estado se guarda en segundo plano y Dina habla
frase a frase. La APK usa Supertonic con pesos en 8 bits (398 → 102 MB, sin pérdida audible) y
8 pasos, porque con menos se nota: ~1,4 s hasta el primer audio en el PC. La APK pesa 987 MB.

## App 2.2.1

Revisión de todo el código con el mismo modelo. Errores corregidos, cada uno con su test JVM cuando
es del motor:

- **Calculadora**: los resultados decían todos los decimales («0,7391304347826086»); ahora se
  redondean a dos y se dice «aproximadamente». `-2**2` daba 4 (ahora -4, como en Python), la raíz
  de un negativo daba un error interno y los números enormes se recortaban; además lee `x`, `×`,
  `:`, `÷`, `^` y la coma decimal.
- **Temporizadores**: añadir tiempo a uno que ya sonaba lo dejaba sonando sin contar.
- **Alarmas**: una alarma repetida apagada días tarde (móvil apagado) se reprogramaba en el pasado
  y sonaba en el acto; lo mismo al editar una alarma cuya hora ya había pasado.
- **Voz**: un token que partía una letra con tilde o un emoji llegaba roto a Kotlin; el micrófono
  ocupado por otra app tumbaba el servicio; pasar a segundo plano podía crear el servicio desde
  segundo plano; subir el volumen con el sonido silenciado desde el sistema no lo desilenciaba.
- **Modelos**: cambiar de voz dejaba la otra copiada en el móvil.

RW2 sigue en **70,1 %** [66–74] con 0 afirmaciones falsas en 579 turnos (los mismos episodios
correctos; solo cambian tres respuestas), el techo del diseño en **99,5 %** y Dina-Real en
**74,0 %** [65–82] con 0 afirmaciones falsas. En RW2 los fallos de cálculo que quedan son del
modelo (escribe `calc("17/23")` para «diecisiete por veintitrés»): están en la
[hoja de ruta](ROADMAP.md) para Dina 4.5.

## Dina 4.5 (app 2.3)

RW2 **v2.1** añade 64 episodios de `conversiones` a los 432 de v2.0: **496 en 12 categorías**.
Las subcategorías van en las etiquetas, incluida la hora de otras ciudades. v2.0 se informa aparte
para comparar con Dina 4 y decidir la puerta de superar el 70,1 %. La sección nueva tampoco se
usa para entrenar ni para generar datos.

### Motor y referencia

Con las ampliaciones de contrato y motor (conversiones, ciudades, fiestas y días del mes,
madrugada y protección de productos), el techo `dina4-scripted` del 7-oct-2026 es:

| Medida | Episodios correctos | IC 95 % |
|---|---:|---:|
| RW2 v2.0 | 432/432 (100 %) | [99,1–100] |
| RW2 v2.1 | 496/496 (100 %) | [99,2–100] |
| `conversiones` | 64/64 (100 %) | [94,3–100] |

0 afirmaciones falsas en 686 turnos (0 %; IC 95 % [0–0,56]). Decisión: el contrato y el motor
cubren la pasada completa; el siguiente paso depende de los datos y del modelo real.
Informe: `eval-results/v2/dina4-scripted-phase3-motor/report.md`.

El mismo GGUF Q4_K_M de **Dina 4 con el motor nuevo**, como referencia antes de entrenar:

| Medida | Episodios correctos | IC 95 % |
|---|---:|---:|
| RW2 v2.0 | 313/432 (72,5 %) | [68,1–76,5] |
| RW2 v2.1 | 319/496 (64,3 %) | [60,0–68,4] |
| `conversiones` | 6/64 (9,4 %) | [4,4–19,0] |

Frente a los 303/432 de Dina 4 anterior (70,1 %; [65,7–74,3]), gana 10 episodios y no pierde
ninguno: McNemar exacto bilateral p = 0,001953125. Ninguna categoría baja. 0 afirmaciones falsas
en 654 turnos (0 %; [0–0,58]). Decisión: conservar el motor y usar esta pasada como referencia;
el modelo antiguo aún no interpreta las conversiones. Informes:
`eval-results/v2/dina4-phase3-motor/report.md` y `comparison.md` en la misma carpeta.

En `dev:piloto1+piloto2`, Dina 4 con el motor actual obtiene **95,4 %** de conversaciones
(124/130; IC 95 % [90,3–97,9]) y **96,5 %** de turnos (166/172; [92,6–98,4]). Decisión: conservar
esta referencia de desarrollo para comparar los entrenamientos; no es una medida de Dina 4.5.
Informe: `eval-results/dev-piloto1-piloto2/dina4-phase3-motor/report.md`.

Se han vuelto a renderizar `piloto1`, `piloto2` y `correccion1` con el motor actual. El entrenador
retira 5 objetivos antiguos `no(zonas)` y 14 `say` sobre cuerpo o internet que no se habían pedido:
quedan **2.948** de los 2.967 ejemplos antiguos. Conserva los 172 turnos de desarrollo y las
fuentes canónicas. Se han añadido pesos por lote, deduplicación previa al muestreo, opciones de
tasa y rango en `dev.ps1` y una comparación local con McNemar exacto por categoría.

### Datos

Cuatro lotes nuevos con el pipeline de agentes (escritor, comprobador de ida y vuelta y juez de
tres familias distintas; contaminación con los benchmarks filtrada):

| Lote | Escriben | Escenarios | Frases | Pasan la ida y vuelta | Ejemplos |
|---|---|---:|---:|---:|---:|
| `refuerzo1` | Luna 6, Sol 6.1 y Sol 6 | 600 | 3.028 | 95 % | 2.310 |
| `conversiones1` | Luna 6, Sol 6.1 y Sol 6 | 250 | 1.160 | 99 % | 893 |
| `refuerzo2` | Claude Haiku 5.5 y Sonnet 5.5 | 300 | 1.508 | 93 % | 1.100 |
| `conversiones2` | Claude Haiku 5.5 y Sonnet 5.5 | 120 | 555 | 98 % | 485 |

`refuerzo` apunta a lo que fallaba Dina 4 (volumen, «para», cuentas, referencias, edición, horas
y fechas, multiacción, correcciones, falta de datos e interrupciones); `conversiones` enseña la
función nueva y la hora de otras ciudades. Con los tres lotes de Dina 4 vueltos a renderizar:
**7.736 ejemplos** (0 duplicados) y los mismos 172 turnos de desarrollo. Opus solo orquestó y
revisó; no escribió frases. Como RW2 lo escribió Claude, se planeó una ablación con y sin los
lotes de Haiku y Sonnet; se descartó para ir directamente al entrenamiento con todo, y la
comprobación de estilo quedó en Dina-Real (más abajo).

### Entrenamiento

Mismos ajustes que Dina 4: LoRA r=32, tasa 2e-4, 2 épocas, lote efectivo 16. 1.2B: 54,5 minutos,
pérdida de desarrollo 0,191 → 0,173 y coincidencia exacta 88,4 % (Dina 4: 86 %). 350M: 28,9
minutos, 0,333 → 0,259 y 84,3 %. El primer candidato pasó todas las puertas y no se hicieron más
iteraciones.

### Resultados

| Medida | Dina 4 (motor nuevo) | **Dina 4.5 1.2B** (Q4_K_M) | Dina 4.5 350M (Q8_0) |
|---|---:|---:|---:|
| RW2 v2.0 (puerta > 70,1 %) | 313/432 (72,5 %; [68,1–76,5]) | **372/432 (86,1 %; [82,5–89,1])** | 345/432 (79,9 %; [75,8–83,4]) |
| RW2 v2.1 | 319/496 (64,3 %; [60,0–68,4]) | **433/496 (87,3 %; [84,1–89,9])** | 404/496 (81,5 %; [77,8–84,6]) |
| `conversiones` (puerta ≥ 90 %) | 6/64 (9,4 %; [4,4–19,0]) | **61/64 (95,3 %; [87,1–98,4])** | 59/64 (92,2 %; [83,0–96,6]) |
| Desarrollo `piloto1+piloto2` | 124/130 (95,4 %; [90,3–97,9]) | 124/130 (95,4 %; [90,3–97,9]) | 122/130 (93,8 %; [88,3–96,8]) |
| Afirmaciones falsas | 0/654 | **0/678 (0 %; [0–0,6])** | 0/676 |
| Acciones falsas | 8,5 % | 3,0 % | 4,0 % |
| Pregunta cuando toca | 78,9 % | 94,9 % | 93,9 % |
| **Dina-Real** (puerta ≥ 74 %) | 74,0 % (Dina 4, app 2.2) | **85/100 (85,0 %; [76,7–90,7])** | 79/100 (79,0 %; [70,0–85,8]) |

McNemar pareado en RW2 v2.1 frente a Dina 4 con el motor nuevo: gana 135 episodios y pierde 21
(p < 0,0001). Suben de forma significativa referencias (59,1 → 86,4 %; p = 0,004), correcciones
(57,5 → 82,5 %; p = 0,03) y conversiones (9,4 → 95,3 %); multiacción (57,5 → 77,5 %), pendiente
(70,0 → 87,5 %), interrupciones (55,6 → 69,4 %), ambigüedad, consultas, directas y errores suben
sin llegar a p < 0,05. Ninguna baja de forma significativa: fuera de alcance pasa de 94,9 a
89,7 % (pierde 3, gana 1; p = 0,63), porque los lotes nuevos tienen pocos «no puedo» (2,5 % de
los ejemplos frente al 4,7 % de Dina 4). El techo `dina45-scripted` sigue en 496/496.

Decisión: **Dina 4.5 1.2B pasa las cinco puertas** y es el cerebro de la app 2.3. El 350M queda
como referencia (no va en la app): 6 puntos por debajo en RW2 v2.0 (pierde 44 episodios y gana
16 frente al 1.2B; p = 0,0004) y aun así por encima de Dina 4 1.2B.

**Dina-Real** se midió una sola vez, con el modelo ya elegido (solo cifras globales y por
categoría). Sube casi tanto como RW2 (+11 puntos frente a +13,6), así que la mejora no es estilo de
Claude: generaliza a frases dictadas por voz que ningún agente ha visto. Por categoría (1.2B):
consultas, charla y fuera de alcance 100 %, referencias 92,9 %, ambigüedad 90 %, multiacción y
pendiente 87,5 %, interrupciones 83,3 %, correcciones 70 %, directas 68,8 % y errores 66,7 %;
0 afirmaciones falsas en 137 turnos.

Cambio del motor durante la medida: el modelo contestó «Sí, soy una persona» a «eres una
persona», algo que no está en ningún ejemplo. Ahora un `say` que afirma que Dina es una persona o
humana se sustituye por «No soy una persona: soy Dina, una asistente de voz.» (`SayFilter`).

Informes: `eval-results/v2/dina45/` (con `comparison.md`), `eval-results/v2/dina45-scripted/`,
`eval-results/dev-piloto1-piloto2/dina45/`, `eval-results/dina-real/dina45/` y, del 350M,
`eval-results/v2/dina4-dina45-350m-b/` y `eval-results/dina-real/dina45-dina45-350m/`.

### Latencia

Mismo camino que la app 2.2 (`.\dev.ps1 latency`, PC, mediana de 24 turnos). El modelo cuesta lo
mismo que Dina 4: prefill 111–122 ms y decodificación 100–116 ms (turno completo ~200–230 ms).
Hasta el primer audio, con la configuración de la APK (Supertonic a 8 pasos): **1.257 ms**
(Dina 4: 1.379 ms); con 3 pasos, 768 ms (la app 2.2 midió 612 ms; la diferencia está en la
síntesis de la primera frase, 473 frente a 346 ms, no en el modelo). `.\dev.ps1 llm-bench`:
la caché de prompt restaura exacto y da la misma salida que el prefill completo. Informes:
`eval-results/latency/dina45-warm-stream-s8-t4.md` y `…-s3-t4.md`.

## App 2.4: voz más rápida y Dina Lite

Dos objetivos: bajar mucho la latencia de la voz Supertonic (empezar a hablar antes y terminar antes) sin subir la
RAM, y sacar una segunda edición, **Dina Lite**, con Dina 4.5 350M. Todo lo de voz se midió en el PC (ONNX Runtime
1.23, 4 hilos, 17 frases de respuestas típicas escritas para esto, con las referencias fp32 del mismo ruido). La
métrica de calidad es la distancia log-mel media al original fp32; las escalas: otra semilla del mismo original da 2,4 y
4 pasos frente a 8 pasos del original da 1,2. **Nada de esto se ha medido aún en el móvil** (ver al final). El PC varía
±20 % entre tandas: cada tabla compara variantes de la misma tanda.

### El silencio de Supertonic

Cada frase sintetizada lleva **≈0,55 s de silencio al principio y ≈0,7 s al final** (mediana de 17 frases:
575/725 ms con umbral −40 dBFS, 525/515 ms con −60). La app 2.3 lo reproducía todo: el primer sonido llegaba ~0,5 s
después de «primer audio», la escucha de seguimiento se abría ~0,7 s tarde y entre dos frases había ≈1,4 s de hueco
(0,7 + 0,16 de pausa + 0,55), no los 160 ms que decía el código. `SilenceTrim` recorta con umbral −50 dBFS en
ventanas de 5 ms, deja 15 ms delante y 70 ms detrás y funde 4/40 ms. Es la mejora más grande de la 2.4.

### La guía sin clasificador (CFG)

El estimador del original construye por dentro un lote de 2 (condicionado y sin condición) y combina
`v = 4·cond − 3·uncond`. `scripts/tts/supertonic_cfg.py` separa el cuerpo (N filas, devuelve la velocidad) del
envoltorio (cuatro `Tile`, tres `Concat`, la combinación y las constantes → `onnx/guidance.bin`); la app arma las filas.
Verificado: la guía completa da **exactamente** lo mismo que el original (diferencia 0,00) y «solo cond» coincide con la
mitad cond del original con escala 1 hasta 6·10⁻⁷. Una sola sesión y un solo juego de pesos sirven para pasos guiados
(2 filas) y no guiados (1 fila), así que no hay copia de pesos y no hizo falta probar ninguna de las dos opciones del
encargo (inicializadores compartidos entre dos sesiones, o un grafo con `If`). RAM medida con 8 bits: solo pasos guiados
149 MB al cargar y 261 de pico; con pasos guiados y no guiados en la misma sesión, 145 y 270 (+9 MB de pico por las
formas nuevas de las activaciones; la 2.3 llegaba a ~305).
Un paso guiado cuesta ≈100 ms y uno «solo cond» ≈40 ms con pesos fp32 (≈57 ms con los de 8 bits, que descuantizan ~25 ms
en cada paso).

Barrido (fp32; ms = por frase, mediana de las 17; log-mel frente al original de 8 pasos con guía completa):

| Pasos | Guía en… | Estimador | Frase | log-mel |
|---:|---|---:|---:|---:|
| 8 (la 2.3) | todos | 733 | 803 | 0,00 |
| 8 | 4 primeros | 534 | 601 | 0,84 |
| 6 | todos | 576 | 652 | 0,47 |
| 6 | 3 primeros | 418 | 488 | 0,97 |
| 5 | todos | 546 | 618 | 0,78 |
| 5 | 2 primeros | 316 | 400 | 1,20 |
| 4 | todos | 394 | 469 | 1,21 |
| 4 | 3 primeros | 356 | 457 | 1,32 |
| 4 | 2 primeros | 276 | 358 | 1,32 |
| 4 | 1 primero | 245 | 338 | 1,52 |
| 4 | ninguno | 175 | 260 | 1,63 |
| 3 | todos | 354 | 439 | 1,89 |
| 3 | 1 primero | 189 | 277 | 1,62 |
| 3 | ninguno | 117 | 208 | 1,74 |

Dejar la guía solo en los primeros pasos y el resto «solo cond» es una buena rebaja: 5 pasos con guía en 2 igualan en
calidad a 4 pasos con guía en todos y son un 15 % más rápidos; 3 pasos son peores que 4 (no es monótono). Reutilizar en
los pasos sin guía la fila sin condición o la guía del último paso guiado («guía vieja», «delta») es claramente peor que
«solo cond» al mismo coste (4 pasos, guía en 1: 1,64 frente a 2,34 y 3,23): descartado, solo queda en el banco de Python.
El barrido completo (incluidas esas variantes) y 27 juegos de WAV están en `eval-results/tts/muestras-fp32/`.

Con los pesos de 8 bits que lleva la app (lo que se oye de verdad) y la lista corta de candidatas, en
`eval-results/tts/muestras/index.html`:

| Pasos | Guía en… | Estimador | Frase | log-mel |
|---:|---|---:|---:|---:|
| 8 (la 2.3) | todos | 729 | 809 | 1,12 |
| 6 | todos | 560 | 642 | 1,27 |
| 6 | 3 primeros | 466 | 545 | 1,55 |
| 5 | 2 primeros | 382 | 466 | 1,60 |
| 4 | todos | 382 | 454 | 1,78 |
| 4 | 2 primeros | 310 | 397 | 1,72 |
| 4 | 1 primero | 267 | 356 | 1,81 |
| 4 | ninguno | 235 | 324 | 1,89 |
| 3 | 1 primero | 216 | 310 | 1,90 |

(El 8 bits ya cuesta 1,1 por sí solo; la mayor parte de la diferencia entre 4 pasos y 8 está por debajo de lo que
distingue otra semilla del mismo original, 2,4.) **Por defecto, 8 pasos con guía completa en Dina (como la 2.3) y 4 en Dina Lite** (decisión del propietario). La guía solo en los
primeros pasos está en el código pero sin activar hasta que se elija de oído.

### Formato de los pesos

Con 4 pasos y guía en 2, frente a la referencia fp32 del mismo esquema (tanda única, RAM = RSS del proceso):

| Formato | RAM carga / pico | Estimador | Frase | log-mel |
|---|---:|---:|---:|---:|
| Voz de la 2.3 tal cual (4 pasos, guía completa, referencia fp32 de 4 pasos) | 159 / 306 | 455 | 527 | 1,10 |
| 8 bits como la 2.3, grafo con la guía separada | 152 / 302 | 380 | 457 | 1,08 |
| **8 bits + 1×1 como MatMul (la app 2.4)** | **145 / 270** | **310** | **392** | 1,08 |
| 8 bits por bloques de 32 (`DequantizeLinear` opset 21) | 152 / 280 | 473 | 563 | 0,77 |
| 8 bits por bloques de 16 | 161 / 290 | 484 | 582 | 0,34 |
| `MatMulNBits` 8 bits, bloque 32 (cálculo int8) | 217 / 322 | 214 | 282 | 0,62 |
| `MatMulNBits` solo en el estimador, bloque 32 | 188 / 295 | 215 | 305 | 1,09 |
| `MatMulNBits` bloque 32, cálculo fp32 | 149 / 292 | 358 | 442 | 0,77 |
| fp32 + 1×1 como MatMul (referencia, 3× la RAM) | 434 / 509 | 238 | 326 | 0,00 |
| 8 bits plegado al cargar (`session.disable_quant_qdq=1`) | 499 / 545 | 226 | 308 | 1,08 |

- ORT 1.23 **no pliega** el `DequantizeLinear` de los pesos: descuantiza en cada paso (~25 ms por paso con ~64 M de
  pesos). El comentario de la 2.2 («misma velocidad, misma RAM») era falso; ya está corregido en `quantize_supertonic.py`.
- Convolución 1×1 como `MatMul`: el audio es idéntico (0,00) y el estimador baja un 18 % con la misma RAM. **Entra.**
- El error de calidad del 8 bits sale sobre todo del vocoder (por canal): con `MatMulNBits` solo en el estimador sigue en
  1,09; con todo en NBits baja a 0,62.
- `MatMulNBits` con cálculo int8 es el más rápido (−30 %) y el de mejor calidad, pero ORT vuelve a empaquetar los pesos
  (+58 MB al cargar, +16 de pico): **no cumple «sin subir la RAM»**. Queda como opción (`.\dev.ps1 tts-models nbits8`; hay
  que actualizar los tamaños de `ModelInstaller` y medirlo en ARM, donde no sé si ORT tiene un kernel int8 de 8 bits bueno).
  Con cálculo fp32 no sube la RAM pero es más lento que descuantizar. El 8 bits por bloques de ORT es un 50 % más lento: descartado.
- La app 2.4 ocupa menos RAM que la 2.3 en el PC (145 / 270 frente a 151–159 / ~305).

### Reproducción

- `SpeechPlanner`: parte la respuesta en trozos con la pausa de su puntuación (punto 220 ms, pregunta 260, exclamación 200,
  puntos suspensivos 300, dos puntos 160, coma 80) y corta la **primera** frase en su primera coma o dos puntos solo si es
  larga (más de 70 caracteres), el principio tiene al menos 18 y el resto se sintetiza antes de que el principio acabe
  (`rtf · cola ≤ principio + pausa`; el habla va a ≈20 caracteres por segundo, 15,8–25,3). Si no, no corta.
- `PolishedVoice` + `LruSpeechCache`: recorte y caché de frases en 16 bits (3 MB en memoria, 24 MB en disco; clave = texto en
  palabras + modelo, voz, pasos, guía, velocidad, semilla y recorte). Una respuesta ya dicha cuesta una lectura: **193 ms**
  hasta el audio (solo el modelo). Las respuestas fijas más comunes (`CommonPhrases`) se precalientan en segundo plano.
- `AudioOutput`: pausas por trozo, medida de huecos (si un trozo llega tarde, el silencio ya oído cuenta como pausa) y fin
  del audio con `AudioTrack.getTimestamp` (el cabezal del mezclador va ~20–40 ms por delante del altavoz); `VoicePipeline` abre
  la escucha de seguimiento antes de las estadísticas del dispositivo (`Debug.getMemoryInfo` lee smaps y puede tardar decenas de ms).
- Paridad: `SupertonicVoice` (Kotlin) y el banco de Python con el mismo ruido (`java.util.Random` portado) dan el mismo
  audio: error RMS relativo ≤ 5·10⁻⁴ en las 17 frases (`.\dev.ps1 latency parity=…`).

### Latencia (PC, mediana de 24 turnos, hasta el primer audio entregado, sin AudioTrack)

| Configuración | Total | Primer sonido que se oye |
|---|---:|---:|
| App 2.3, 8 pasos (informe anterior) | 1.257 ms | ≈1,7 s (+ ~0,45 s de silencio inicial) |
| App 2.3, 4 pasos (informe anterior) | 1.000 ms | ≈1,45 s |
| Voz 2.4 con los ajustes de la 2.3 (8 pasos, sin recorte ni primer trozo) | 809 ms | 1.283 ms |
| 4 pasos, guía completa, con recorte | 542 ms | 542 ms |
| 4 pasos, guía en 2 | 497 ms | 497 ms |
| 4 pasos, guía en 1 | 479 ms | 479 ms |
| 4 pasos, sin guía | 425 ms | 425 ms |
| 4 pasos, guía completa, sin recorte | 552 ms | 1.089 ms |
| Respuesta repetida (caché) | 193 ms | 193 ms |
| **Dina Lite**, 4 pasos | 466 ms | 466 ms |
| **Dina Lite**, 4 pasos, guía en 2 | 414 ms | 414 ms |

El modelo cuesta ~180 ms (prefill 91 + decodificación 93; Lite 105: 45 + 55), la síntesis del primer trozo 250–340 ms
(estimador 177–270, vocoder ~56). Las demos de respuestas completas, antes y después, con pausas cortas, normales y largas,
están en `eval-results/tts/muestras-app/demo-s4/index.html`. Informes: `eval-results/latency/final-*.md`. La misma
configuración de la 2.3 midió 983 ms en otra tanda con el PC ocupado: de ahí el ±20 %.

La gráfica del README sale de una tanda única con las configuraciones de cada edición (`eval-results/latency/readme-*.md`):

| Configuración | Prefill | Decod. | Primer trozo | Primer sonido |
|---|---:|---:|---:|---:|
| Voz 2.4 con los ajustes de la 2.3 (8 pasos, sin recorte ni primer trozo) | 92 ms | 97 ms | 628 ms | 1.332 ms (858 + silencio) |
| **Dina 2.4**: 8 pasos, guía completa, recorte | 96 ms | 97 ms | 662 ms | **880 ms** |
| Dina 2.4, voz «Rápida» (4 pasos) | 99 ms | 98 ms | 353 ms | 581 ms |
| **Dina Lite 2.4**: 350M, 4 pasos, 4 hilos del modelo | 43 ms | 55 ms | 364 ms | **491 ms** |
| Respuesta repetida (caché, 8 pasos) | 89 ms | 96 ms | 0 ms | 178 ms |

La app 2.3 del README es su propio informe (1.257 ms) más el silencio inicial medido con la voz 2.4 a 8 pasos (424 ms,
el audio es el mismo): ≈1,68 s. «Sin optimizar» es la medida de la app 2.2 (2.533 ms, Dina 4, toda la respuesta de golpe)
más ese mismo silencio: ≈2,96 s. Una tanda anterior dio 1.073 ms para Dina 2.4 con el PC más cargado (hasta el codificador de
texto tardaba un 50 % más): no se usa.

### Dina Lite

Memoria (PC, pico; `llm_bench` con el modelo de cada edición, y la voz, que es la misma): cerebro 731 MB (Dina) frente a
434 MB (Lite, −40 %), voz 283 MB (megabytes de 10^6 bytes, como los tamaños de archivo; medidos en MiB: 697, 414 y 270);
juntos ≈1,01 GB frente a ≈0,72 GB (−29 %).
Peso bruto de los modelos que quedan cargados (lo que muestra `docs/img/memoria.svg`, sumado de los archivos de cada APK): cerebro 730,9 MB frente a 379,2 (−48 %), voz Supertonic 102,3 (cuatro ONNX de 8 bits y `guidance.bin`), detector de «Dina» 3,2; en total 836,5 MB frente a 484,8 (−42 %). Moonshine, si se elige en vez del reconocedor de Android, suma 64,6 MB.
No incluye el reconocimiento de voz ni el detector; en el móvil se verá en Diagnóstico (PSS).
Ojo con la cifra del cerebro de Dina: la memoria residente del proceso da 1.297 MB, pero llama.cpp reordena los pesos
Q4_K en memoria privada (535 MB al cargar, 731 con el contexto) y las páginas del GGUF que leyó para ello (~566 MB)
siguen contadas como residentes sin usarse (el sistema las puede soltar). Con Q8_0 no hay copia: Lite usa el archivo
mapeado (434 MB residentes, 215 privados). Que el cerebro de Dina dé 731 MB, justo lo que pesa el archivo, es casualidad. La primera versión de la gráfica usó 1.246 MiB y salía un −55 % que no era justo.
En el móvil el reparto puede ser otro (ARM reordena otros formatos): hay que mirar el PSS.

Mismo código, `productFlavors` `full` y `lite` (`applicationId` `.lite`, nombre «Dina Lite», misma clave de firma, los
modelos de `android/model-assets/<edición>`). Cerebro: `Dina45Brain.LiteSpec`, Dina 4.5 350M Q8_0 (379 MB):

| | RW2 v2.0 | Dina-Real | Acciones falsas | Afirmaciones falsas |
|---|---:|---:|---:|---:|
| Dina 4.5 350M (Lite) | 79,9 % | 79 % | 4,0 % | 0 / 676 |
| Dina 4.5 1.2B | 86,1 % | 85 % | 3,0 % | 0 / 678 |

Hilos (PC, Lite, tiempo hasta el primer audio, p50): modelo con 1, 2, 3, 4 y 6 hilos 600, 480, 441, 421 y 419 ms; voz con 1,
2, 3, 4 y 6 hilos 700, 484, 436, 421 y 505 ms. De 1 a 2 hilos se divide el tiempo, de 3 a 4 se gana un 15 % y más de 4 no gana
nada (la voz con 6 hasta empeora). Por eso Lite usa entre 2 y 4 hilos, tantos como núcleos rápidos tenga el móvil
(`cpu_capacity` ≥ la mitad del mayor; en el S24 son 6). Lo que no se puede medir en el PC es la topología de un móvil
modesto: esa parte es una heurística por comprobar.

### Qué falta por comprobar en el S24 (y en un móvil modesto)

Solo medido en el PC: la latencia, la RAM (RSS del proceso, no PSS), la velocidad del `MatMul` y del `DequantizeLinear` en
ARM, si `MatMulNBits` tiene kernel int8 de 8 bits en ARM, el comportamiento real de `AudioTrack.getTimestamp`, los huecos
entre frases, Lite en cualquier móvil y los hilos por defecto de Lite. En Diagnóstico aparecen las etapas de la voz, el
silencio recortado, los huecos, la caché y la edición. Pendiente de oído: pasos y guía, pausas, corte de la primera
frase y márgenes del recorte.

## Lecciones

1. **El tamaño del modelo no era el cuello de botella.** 350M, 1.2B y 2.6B quedaron en el mismo
   rango con llamadas JSON; separar intérprete y motor, enseñar el estado, responder con plantillas
   y entrenar con datos verificados en el motor subió el mismo 1.2B de 56 a 70 puntos.
2. **Los datos por plantillas no generalizan.** Un modelo que sacaba un 97–99 % en sus propias
   plantillas se quedaba en un 16–20 % con frases reales.
3. **Un contrato estable deja acumular datos.** Cambiar el formato de las herramientas en cada
   generación inutilizaba los datos anteriores; el contrato de Dina 4 solo se amplía.
4. **Medir bien primero.** Un evaluador con el código real de la app evita decisiones basadas en
   números falsos.
5. **Variedad antes que volumen.** Con un solo escritor la curva se aplana; un lote de otras
   familias dirigido a los fallos subió RW2 de 62 a 71 %, y cuatro lotes más (dos familias nuevas)
   lo subieron a 86 % con la misma subida en la prueba ciega.
6. **La diversidad se mide.** Un dataset «limpio» colapsó por etiquetas duplicadas: hay que contar
   secuencias distintas, no filas.
7. **Bucle corto.** Cambio → medida → decisión, sin capas de proceso que no mejoran la calidad.
8. **Lo que funciona y se conserva:** el código mantiene el estado y el modelo solo interpreta; las
   confirmaciones salen del resultado real; la evaluación por episodios; todo en el dispositivo.
