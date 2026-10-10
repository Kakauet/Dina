# Arquitectura de la app Android

La app separa el modelo de todo lo demás: herramientas, diálogo, voz y UI.
Todo el código está en `android/app/src/main/java/com/kakauet/dina/`.

```
ui/            Jetpack Compose. Solo observa estado y envía órdenes.
  theme/       Tokens de diseño: paletas clara y oscura, colores del personaje,
               Fredoka + Nunito, formas, espaciado y movimiento (DinaTheme).
  character/   Dina como personaje dibujado a mano (Brote o Musgo).
  components/  Piezas dibujadas a mano: tarjetas, botones, bocadillos, iconos.
  widgets/     Temporizador, alarma, cronómetro, compra, volumen.
  screens/     Inicio (voz/texto), Mis cosas, Ajustes, Diagnóstico.
      │ StateFlow                          │ DinaService.sendText(...), tools.execute(...)
      ▼                                    ▼
core/          ConversationController: un turno = brain + registro de mensajes,
               widget y métricas. DinaStore: estado observable.
      │                                    ▲
      ▼                                    │ transcripción / audio
brain/         Interfaz Brain + BrainRegistry        voice/   VoicePipeline: micrófono, «Dina»,
               (1.2B o 350M según la edición).         VAD, STT, TTS trozo a trozo.
  dina45/      Dina45Brain: prompt con estado            DinaService: ciclo de vida Android,
               visible, códec y gramática GBNF.          carga de modelos.
config/        AppVariant: qué edición es (Dina o Dina Lite) y sus ajustes por defecto.
      │ acciones del contrato
      ▼
dialog/        Motor de diálogo: referencias y foco (Resolver), pendiente, deshacer y
               políticas (Dialogue), plantillas (Responder), estado (StateSummary).
models/        ModelInstaller: copia los modelos de la APK a almacenamiento interno.
      │ ToolCommand tipado
      ▼
tools/         ToolEngine: estado persistente y reglas (Kotlin puro, con tests).
               AndroidTools: AlarmManager, AudioManager, SharedPreferences.
llm/           NativeLlmEngine: llama.cpp por JNI (TextGenerator).
cpp/           JNI + PromptCache (reutiliza el prompt de sistema y precalcula el siguiente).
```

## Reglas

1. **El modelo solo interpreta y el código ejecuta.** Ningún cerebro toca el estado
   directamente: traduce su salida a acciones o a `ToolCommand`, y el motor decide si son válidas
   y qué pasa.
2. **Las confirmaciones salen del resultado real.** `Responder` y `ToolPresentation` crean la
   respuesta y el widget a partir del resultado, nunca a partir del texto del modelo.
3. **Un único camino para los turnos.** Voz, texto y evaluador pasan por
   `ConversationController.runTurn`.
4. **La UI no conoce el formato del modelo.** Solo ve tipos de `tools/` y `core/`.
5. **El estado del diálogo es efímero**: `ToolWorldJson` sigue leyendo los datos de la app 1.4.

## Cómo funciona un turno

1. Mientras el usuario habla, `Dina45Brain.prepare` renderiza el prompt hasta la frase y
   `PromptCache::warm` lo procesa: sistema fijo, `[estado]` (reloj, alarmas, temporizadores,
   compra… resumidos por `StateSummary`) y `[antes]` (el turno anterior comprimido).
2. Llega la transcripción: solo quedan por procesar `[ahora]` y la frase.
3. El modelo escribe una línea por acción (`alarm.add(7:00, day=mañana)`) con una gramática GBNF
   generada de la tabla `Ops`, así que la salida siempre se puede leer.
4. `Dina45Codec` la convierte en acciones parciales; `Dialogue` y `Resolver` resuelven referencias
   («la primera», «esa»), completan lo pendiente y aplican las políticas.
5. `ToolEngine` ejecuta; `Responder` dice los hechos del resultado. El texto libre del modelo
   (`say`) solo se usa en la charla o como coletilla, y pasa un filtro que bloquea horas,
   afirmaciones de haber hecho algo y nombres de las cosas del usuario.
6. La voz sintetiza y reproduce el primer trozo mientras prepara los siguientes (siguiente sección).

Contrato completo en [contrato.md](contrato.md) y motor en [motor.md](motor.md).

## Rendimiento del modelo

`cpp/prompt_cache.h` guarda instantáneas del estado del modelo tras el prompt de sistema y tras
el prefijo precalculado, y las restaura cuando el nuevo prompt empieza igual. LFM2.5 es híbrido
(atención + convoluciones), así que no basta con recortar la KV cache: hay que restaurar el estado
completo. Si mientras el usuario habla solo ha avanzado el reloj (≤ 3 s, mismo minuto), el turno
reutiliza el prefijo preparado.

`.\dev.ps1 llm-bench` lo mide en el PC con el modelo real y comprueba que restaurar una
instantánea da un resultado idéntico a recalcular con los mismos cortes. En el PC, el prefill que
queda tras la transcripción baja de ~700 ms a ~110 ms. En el móvil se ve en Diagnóstico.

## La voz: de la respuesta al altavoz

`SpeechPlanner` parte la respuesta en trozos (frases; la primera, si es larga, en su primera coma o dos
puntos, pero solo cuando el resto se sintetiza antes de que termine el principio) y le pone a cada trozo la
pausa que pide su puntuación. `PolishedVoice` envuelve la voz y hace dos cosas con cada trozo: recorta el
silencio (Supertonic deja ~0,55 s antes y ~0,7 s después de cada frase; `SilenceTrim`) y lo guarda o recupera de
`LruSpeechCache` (16 bits, unos MB en memoria y una carpeta en disco, clave = texto dicho en palabras + modelo,
voz, pasos, guía, velocidad, semilla y recorte). `AudioOutput` escribe los trozos en un único `AudioTrack`, mide
los huecos si un trozo llega tarde y da el audio por terminado cuando `AudioTrack.getTimestamp` dice que se ha
oído el último fotograma (`PlaybackClock`); entonces `VoicePipeline` abre la escucha de seguimiento, antes de
calcular las estadísticas del dispositivo (que pueden tardar decenas de ms).

`SupertonicVoice` ejecuta el estimador en N filas. La guía sin clasificador del original construía por dentro un
lote de 2 (condicionado + sin condición) y combinaba `4·cond − 3·uncond`; `scripts/tts/supertonic_cfg.py` saca ese
envoltorio del grafo (el audio con guía completa es idéntico al original, bit a bit) y `SupertonicGuidance` lo
hace en la app: los `guidedSteps` primeros pasos van con 2 filas y el resto con 1, sin segunda sesión ni más RAM.

## Modelos en el móvil

`ModelInstaller` copia una vez los modelos de la APK a `files/models/` (inferencia y voz los
abren por ruta). Cada modelo tiene su propio nombre de archivo, porque la comprobación rápida solo
compara tamaños. Solo se copia lo que está en uso (la voz elegida, Moonshine si está activado) y,
al terminar una copia completa, se borra lo demás: la otra voz, Moonshine si se desactivó y los
modelos de versiones anteriores. La APK de depuración no lleva modelos: `.\dev.ps1 push-models` los copia una
vez al almacenamiento externo de la app y se usan desde ahí.

| Modelo | Archivo | Tamaño |
|---|---|---:|
| Dina 4.5 1.2B (Q4_K_M), edición **Dina** | `llm/dina-4.5-1.2b-Q4_K_M.gguf` | 731 MB |
| Dina 4.5 350M (Q8_0), edición **Dina Lite** | `llm/dina-4.5-350m-Q8_0.gguf` | 379 MB |
| Supertonic 3 F2 (8 bits, 1×1 como MatMul, guía separada) | `tts/supertonic3-v2/` | 102 MB |
| Piper Sharvard (alternativa) | `tts/es_es/piper-voices/` | 77 MB |
| Moonshine Spanish Base (opcional) | `stt/base-es/` | 65 MB |
| Detector de «Dina» | `wake/` | 3,2 MB |

## Añadir un modelo nuevo

1. Crear `brain/<dinaN>/` con una clase que implemente `Brain` y un `BrainSpec` que indique el
   GGUF, su tamaño, la cuantización y el contexto.
2. En `runTurn`: construir el prompt, llamar a `llm.generate`, traducir la salida a acciones de
   `dialog/` (como Dina 4.5) o a `ToolCommand`, ejecutar y devolver un `TurnResult`.
3. Ponerlo en `BrainRegistry` (`forEdition`; `default` es el de la edición compilada) y añadir el GGUF a
   `scripts/models/prepare-models.ps1` y a `models/`: el script comprueba que el tamaño del archivo es el
   que dice el `ModelSpec`.
4. Medirlo con `.\dev.ps1 eval` en RW2 y en el conjunto de desarrollo antes de cambiar la app.

`Dina45BrainTest` muestra cómo probar un cerebro sin teléfono: un `TextGenerator` falso con salidas
guionizadas y un `ToolEngine` real en memoria.

## Dos ediciones

Dina y Dina Lite salen del mismo código con dos *product flavors* de Gradle (`full` y `lite`). Solo cambian
`BuildConfig.LITE` (que elige el cerebro en `BrainRegistry` y los ajustes por defecto en `AppVariant`), el
`applicationId` (`com.kakauet.dina` y `com.kakauet.dina.lite`, así que conviven), el nombre visible y los modelos
que lleva la APK (`android/model-assets/full` y `lite`, preparados por `prepare-models.ps1 -Variant`). Las dos
se firman con la misma clave. Lite pone los hilos del modelo y de la voz según los núcleos rápidos del móvil
(los que tienen al menos la mitad de la capacidad del mayor, `cpu_capacity`; entre 2 y 4, porque en el PC más de
4 hilos no ganan nada) en vez de los 6 y 4 del S24 Ultra. Diagnóstico muestra la edición y esos núcleos.

## Interfaz y personaje

Todo lo visual está en `ui/` y sale de `ui/theme/Theme.kt`. Ajustes va de lo que más se toca a lo
que menos: permisos que falten, escucha, voz, apariencia, conversación, datos y «Acerca de»; lo
técnico (hilos, motor de la voz, registro de la palabra «Dina») está en Diagnóstico. Las preferencias de aspecto
(personaje y tema) se guardan en `ui/UiPreferences.kt`, aparte de los ajustes del asistente.

El personaje (`ui/character/`) se dibuja solo con `Canvas` y `Path`:

- `Sketch.kt`: ruido determinista, splines Catmull-Rom y trazos de pincel de grosor
  variable. También la textura de papel: grano y sombreado a lápiz.
- `Pose.kt`: una pose son unos 20 números (ojos, boca, cejas, inclinación, hojas…).
  `choreograph` decide la pose de cada `VoiceState` según el reloj y el nivel de audio.
  Al cambiar de estado se interpola desde la última pose dibujada.
- `Rigs.kt`: los dos diseños. Cada uno precalcula sus trazos fijos para un tamaño,
  uno por cada dibujo del «line boil» (3 dibujos a 10 fps).
- `DinaCharacter.kt`: el composable. El reloj y el nivel de audio solo se leen en la fase
  de dibujo, así que animar a 60 fps redibuja el lienzo sin recomponer la pantalla.
  Con «quitar animaciones» del sistema conserva las expresiones y la boca, sin movimiento
  de reposo ni boil.

Para añadir un diseño: una clase `Rig` nueva en `Rigs.kt` y una entrada en
`CharacterDesign`. La cara, los extras y la animación son comunes.

El icono de la app también es el personaje. `.\dev.ps1 icons` dibuja sus capas (fondo,
figura y versión monocroma para los iconos temáticos) con el mismo código y las guarda en
`res/mipmap-*`. El manifiesto tiene un `activity-alias` lanzador por diseño;
`ui/LauncherIcon.kt` deja activo el del personaje elegido cuando la app pasa a segundo plano.

### Capturas en el PC

`.\dev.ps1 screenshots` ejecuta los tests de `ui/screenshots/` con Robolectric (gráficos
nativos, SDK 34 porque los SDK 35+ necesitan JDK 21) y Roborazzi. Genera en `screenshots/`:

- láminas y PNG por estado de cada personaje;
- un GIF de un turno completo;
- todas las pantallas en claro y oscuro, con datos de ejemplo.

`.\dev.ps1 test` las excluye para seguir siendo rápido.

## Tests

```powershell
.\dev.ps1 test
```

Cubren el motor de herramientas (temporizadores, alarmas con repetición, cronómetros, compra,
volumen, fecha y calculadora), la compatibilidad con el estado de la app 1.4, el motor de diálogo
pieza a pieza, el códec, el prompt y la gramática de Dina 4.5, el controlador de conversación, la voz
(números en palabras, frases) y la validación de los benchmarks con el oráculo.
