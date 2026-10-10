# RealWorld v2 (RW2)

Conjunto de desarrollo y regresión de Dina. **v2.1: 496 episodios** en 12 categorías: los 432 de v2.0
(614 turnos principales) y 64 de `conversiones`. Es independiente del contrato del modelo: cada episodio
define el estado del mundo, la hora, lo que dice el usuario y lo que debe ocurrir en el mundo (decisión,
estado final y hechos de la respuesta). Se evalúa con `.\dev.ps1 eval <brain> v2`.

## Categorías

| Categoría | Episodios | Qué mide |
|---|---:|---|
| `directas` | 40 | Órdenes en los 7 dominios, con estado previo y relojes variados |
| `consultas` | 40 | Hora, fechas, tiempo restante, listas: la respuesta debe dar el dato |
| `referencias` | 44 | «esa», «la de las 7», «la otra», «la primera que puse», «el que suena», superlativos |
| `ambiguedad` | 40 | Falta un dato o hay varios candidatos (preguntar nombrándolos), y casos que no son ambiguos |
| `pendiente` | 40 | Respuestas a una pregunta de Dina, confirmaciones y abandonos |
| `correcciones` | 40 | Dentro de la frase («a las siete… no, a las ocho») y tras ejecutar; deshacer |
| `multiaccion` | 40 | Varias acciones en una frase, en uno o varios dominios |
| `interrupciones` | 36 | Otra petición con algo pendiente y vuelta al hilo |
| `errores` | 39 | No existe, ya está así, duplicado, fuera de rango, nada sonando, fallos de permiso o transitorios |
| `fuera_de_alcance` | 39 | Música, llamadas, internet; negaciones («no pongas ninguna alarma»); capacidades |
| `charla` | 34 | Saludos, preguntas sobre Dina, frases sin petición, acción con coletilla |
| `conversiones` | 64 | Unidades, cocina, temperatura, datos que faltan, conversiones imposibles, hora de otras ciudades |

Subcategorías de `conversiones` (en `tags`): `longitud`, `masa`, `volumen`, `cocina`, `temperatura`,
`velocidad`, `superficie`, `tiempo`, `pendiente`, `seguimiento`, `imposibles`, `ciudades`, `distractores`.

Cortes transversales: 80 % español de España y 20 % de Latinoamérica; 131 episodios con ruido de STT
(`stt`: sin signos, repeticiones, frases cortadas); casos límite de reloj (`edge`: medianoche, fin de mes
y de año, cambios de hora) y tiempo entre turnos (`wait`).

## Formato

Una línea JSON por episodio en `episodes/NN_categoria.jsonl`:

```json
{"id":"ref-01","cat":"referencias","var":"es-ES","tags":["alarm"],"clock":"2026-10-05T21:30",
 "state":{"alarms":["mañana 07:00 'trabajo' rep=laborables","mañana 19:00 'gimnasio'"]},
 "turns":[{"u":"Cambia la de las siete de la tarde a las ocho.","do":["alarm.edit @19:00 at=20:00"]}],
 "why":"Referencia por hora con otra alarma a las 7:00; «las ocho» conserva la tarde."}
```

- `clock`: hora local de Madrid (`tz` opcional). `state`: estado inicial legible:
  alarmas `[día] HH:MM ['nombre'] [rep=diario|laborables|finde|lun,mie] [off|ringing]`,
  temporizadores `duración ['nombre'] [left=4m12s] [paused|ringing]`,
  cronómetros `['nombre'] transcurrido [paused]`, compra `'producto' [n=2] [unit=l] [done]`,
  volumen `"40"` o `"0 muted restore=60"`.
- Cada turno tiene `u` (lo que dice el usuario), opcionalmente `wait` (tiempo previo) e `inject` (fallo
  forzado de una herramienta, p. ej. `{"alarm":"permission_denied"}`), y una o varias opciones válidas en
  `x` (o una sola directamente en el turno).
- Una opción es `do` (acciones del oráculo), `ask` (`slot` que falta y `opts`, candidatos que la pregunta
  debe nombrar) o `kind` `answer` | `reject` | `chat` | `fail`. Admite `says` (hechos que la respuesta
  debe dar), `not` (lo que no debe afirmar), `pol` (válida solo con una política) y `then` (turnos que
  siguen si se toma esa opción).

Las acciones del oráculo no son el contrato de ningún modelo, sino la notación del estado esperado. El
evaluador las ejecuta en el `ToolEngine` real sobre una copia del mundo y compara mundos, no llamadas:
`alarm.add 08:15 day=mañana 'médico|medico'`, `alarm.edit @'gimnasio' at=20:00`, `timer.plus @'café' 2m`,
`list.add 'leche' n=2 unit=l`, `vol.up 10`, `time.weekday 2026-10-12`, `calc '17*23'`. Los objetivos son
selectores que deben coincidir con un único elemento: `@'etiqueta'`, `@07:30`, `@2` (posición), `@last`,
`@ringing` o ninguno (el único existente). `'a|b'` admite nombres alternativos.

Las conversiones usan `conv <cantidad> <de> <a> ['ingrediente']` (`conv 2 km m`, `conv 1 taza g 'harina'`);
el resultado lo calcula el código. Una taza son 240 ml; pasar de volumen a masa requiere un ingrediente
conocido. La hora de un lugar se escribe `time.now time 'lugar'`. Solo esta sección:
`.\dev.ps1 eval <brain> conv`.

## Puntuación

Cada turno se compara con la mejor de sus opciones válidas:

1. **Acción**: el estado del mundo coincide con el esperado (sin ids ni orden; nombres sin artículos ni
   plurales; tiempos ±1 s) y la decisión es correcta: preguntar cuando falta un dato, no ejecutar nada en
   la charla y no cambiar nada al rechazar.
2. **Respuesta**: contiene los hechos del resultado, comparados como hechos («6:00» = «las seis»;
   «cuatro minutos y doce segundos» = 252 s). Las preguntas deben ir al dato que falta y nombrar a los
   candidatos. No puede afirmar acciones no realizadas. La charla, en 40 palabras como máximo.

Un episodio es correcto si lo son todos sus turnos. Un fallo de acción detiene el episodio; un fallo solo
de respuesta no, para separar ambas medidas. El informe incluye las dos cifras, intervalos de Wilson al
95 % y cortes por categoría, variedad y ruido, y separa **v2.0** (432, comparable con las medidas
anteriores) de **v2.1** (496). El tono y la naturalidad no se puntúan.

## Políticas

Las opciones que dependen de una decisión de diseño llevan `pol`. Por defecto se acepta cualquiera; con
`policy=ampm:mixed+bulk:confirm` se puntúa una concreta.

| Política | Valores |
|---|---|
| `ampm`: hora de 1 a 12 sin «de la mañana/tarde» | `ask` (preguntar), `next` (próxima ocurrencia), `mixed` (despertar → mañana; si no, la próxima) |
| `bulk`: vaciar la lista o borrar 3 o más alarmas/temporizadores | `confirm` (pedir confirmación), `direct` |

Se aceptan ambas interpretaciones, sin política, en: aviso relativo como temporizador o alarma, «quítalo
de la lista» como borrar o marcar, «para» como pausar o quitar, «un poco» como ±5 a 20 de volumen.

## Validación

`BenchmarkV2Test` (incluido en `.\dev.ps1 test`) comprueba que:

- cada camino de cada episodio, con cada política, obtiene el 100 % con un oráculo sobre el `ToolEngine`
  real, y que no hay opciones indistinguibles;
- las acciones esperadas cambian el mundo cuando deben;
- cada hora, duración, número, producto y nombre de la expectativa aparece antes en la conversación (los
  valores derivados, como «media hora antes», están listados);
- ninguna frase de más de 3 palabras coincide con RW200.

Muestra para revisión humana: [revision/muestra.md](revision/muestra.md).

## Procedencia y contaminación

- Escrito a mano por Claude (Opus 5.5) en octubre de 2026, sin plantillas ni frases de RW200. Sesgo
  conocido: un solo autor; el español latinoamericano y el ruido de STT son imitados.
- No es ciego: se usa para iterar y como regresión. La prueba ciega es Dina-Real (`../dina_real/`).
- No se entrena con RW2 ni se generan datos a partir de él. Antes de entrenar, un filtro local elimina los
  ejemplos que comparten un 8-grama normalizado con RW2, RW200 o Dina-Real, o que son casi iguales a
  alguna de sus frases (MinHash de trigramas, Jaccard > 0,7). Las frases de los benchmarks no se envían a
  ninguna API.
- Los lotes de Dina 4.5 escritos por modelos de la misma familia (`refuerzo2`, `conversiones2`) se
  validan por ablación frente al conjunto de desarrollo y Dina-Real.
- v2.0 quedó fijada con la primera medida (5 de octubre de 2026); v2.1 (7 de octubre) añadió
  `conversiones`. Cualquier cambio crea una versión nueva y se registra en `docs/HISTORY.md`.
