# Contrato del cerebro

Lo que escribe el modelo de Dina 4.5 (y antes Dina 4) y cómo lo usa el motor. Principios: dos capas (lo canónico se
guarda; el texto del modelo se renderiza), el modelo extrae y el código calcula, una forma por
intención y acciones parciales. El contrato solo se amplía: nada de lo que ya existe cambia de
significado, así los datos de entrenamiento se acumulan entre versiones.

## Salida del modelo

Una línea por acción, `dominio.verbo(objetivo?, valor?, clave=valor…)`. La gramática GBNF se genera
de la tabla `Ops` (`dialog/Intent.kt`), así que la salida siempre se puede leer. Greedy, máximo
48 tokens (120 si empieza por `say(`); una acción típica ocupa de 8 a 22 tokens.

| Dominio | Ops |
|---|---|
| `alarm` | `add(hora, day=, repeat=, "etiqueta")` · `edit(a?, at=, day=, repeat=, label=)` · `del(a?)` · `off(a?)` · `on(a?)` · `get(a?)` · `list()` · `snooze(dur?)` |
| `timer` | `add(dur, "etiqueta")` · `pause(t?)` · `resume(t?)` · `del(t?)` · `get(t?)` · `list()` · `plus(t?, dur)` · `minus(t?, dur)` · `edit(t?, left=, label=)` |
| `sw` | `add("etiqueta")` · `pause(c?)` · `resume(c?)` · `reset(c?)` · `restart(c?)` · `del(c?)` · `get(c?)` · `list()` |
| `list` | `add("producto", n=, unit=)` · `del(p?)` · `edit(p?, n=, unit=, name=)` · `check(p?)` · `uncheck(p?)` · `list()` |
| `vol` | `set(n?)` · `up(n?)` · `down(n?)` · `mute()` · `unmute()` · `get()` |
| `time` | `now(hora\|fecha\|dia, "lugar")` · `weekday(día)` · `until(día)` |
| `calc` | `calc("12*7")` |
| `conv` | `conv(cantidad, de, a, "ingrediente")` |
| `fun` | `joke()` · `fact()` |
| Control | `stop()` (silencia lo que suena) · `ask()` · `no(tema)` · `say("…")` · `undo()` · `drop()` · `yes()` · `nope()` |

Decisiones de diseño:

- **Borrar todo es `del(*)`**, no `delall`/`clear`/`cancel_all`: una forma por intención. El
  motor confirma o no según la política `bulk`.
- **`stop()` es de control**: «para» o «cállate» con algo sonando no dice qué suena; el motor lo
  sabe.
- **`sw.restart`** («empieza de cero y sigue») junto a `reset` («ponlo a cero»): solo se
  diferencian si el cronómetro está en pausa (`reset` lo deja parado). Se mantienen las dos
  porque el usuario las distingue.
- **`list.check/uncheck`** («ya tengo la leche») y `edit(name=)`; `get(a?)` para «¿a qué hora
  suena la del gimnasio?».

Valores: horas `7:00` / `7:00 tarde` / `19:00`; días `hoy`, `mañana`, `pasado`, `lunes`…, `25/12`,
`+3`; repetición `diario`, `laborables`, `finde`, `lun,mie`; duraciones `10m`, `1h30m`, `45s`.
Objetivo: vacío, `@` (foco), `"texto"` (etiqueta o producto), hora, franja (`tarde`),
`#2`/`#último` (posición tal como se leyó), `#próximo`, `sonando`, `*`.

Ampliaciones para Dina 4.5 (fase 3):

- `time.now` admite un lugar opcional: el motor resuelve la ciudad y calcula allí la hora o la
  fecha. El modelo escribe el lugar, no calcula diferencias horarias.
- Un día del mes sin mes (`day=15`) significa la siguiente fecha con ese día, incluido hoy; se
  saltan los meses que no lo tienen. Las fiestas de fecha fija se escriben por su nombre:
  `navidad`, `nochebuena`, `nochevieja`, `año nuevo`, `reyes`, `san valentín`, `san juan` y
  `halloween`. La fecha sale de `Day.HOLIDAYS`, no de un cálculo del modelo.
- `conv` admite cantidades decimales, negativas y fracciones como `1/3`. `AMOUNT` representa la
  cantidad y `MEASURE` las unidades publicadas en `Units.CODES`; el código convierte y redondea.
  Puede preguntar por cantidad, unidad de origen, destino o ingrediente. Si no se dice destino,
  usa la equivalencia cotidiana de `Units.defaultTarget`, o pregunta si no hay una.
- Una taza son **240 ml**, una cucharada 15 ml y una cucharadita 5 ml; pintas y galones son de
  Estados Unidos. Pasar entre volumen y masa requiere un ingrediente de `Units.INGREDIENTS`;
  la respuesta dice «unos» cuando usa esas equivalencias de cocina. Dimensiones incompatibles o
  ingredientes desconocidos se rechazan con una explicación del motor.

Detalles:

- Argumentos separados por `", "`; los días de `repeat=` van con coma sola (`repeat=lun,mie`);
  `repeat=no` quita la repetición.
- `day=` también selecciona en `alarm.del/off/on/get` («quita las de mañana»). El día `ayer` solo
  sirve para detectar fechas pasadas.
- `at=` (en `alarm.edit`) es una hora nueva o un desplazamiento `+15m` / `-30m`.
- `?` como valor: el dato falta y Dina lo pregunta. Sin el argumento obligatorio
  (`alarm.add()` sin hora, `timer.add()` sin duración) también pregunta.
- `#próximo`: la alarma que antes suena o el temporizador al que menos le queda.
- `list.edit(p, n=+1)` suma a lo que hay.
- Franja: el modelo la escribe solo si se dice o si el contexto es despertarse («despiértame a
  las 7» → `7:00 mañana`). Si no, `7:00`, y el motor aplica la política `ampm`.
- `get` con varios candidatos los lee todos.

## Charla, chistes y curiosidades

- **`fun.joke()` y `fun.fact()`**: el motor elige un chiste o una curiosidad de sus bancos
  (`dialog/FunBank.kt`, recursos `dina/fun/chistes.txt` y `curiosidades.txt`, unos 300 de cada) y no
  repite hasta agotar el mazo. En `[antes]` se resumen como `chiste` / `curiosidad`.
- **`say` con personalidad**: el modelo decide cuándo habla. Sin petición, `say("…")` es toda la
  respuesta; tras acciones, una coletilla opcional (`alarm.add(10:00, "médico")` +
  `say("¡Suerte en el médico!")`) que va después de lo que confirma el motor y antes de su
  pregunta, si la hay. Los datos siempre salen de los resultados.
- **Filtro de `say`** (`SayFilter`): en turnos solo de charla deja pasar números normales y hasta
  50 palabras; con acciones, 25 palabras y sin números. Siempre bloquea horas, afirmaciones de haber
  hecho algo y nombres de las cosas del usuario, salvo, en una coletilla, los que escriben las
  acciones de ese mismo turno.

## Entrada del modelo

```
<sistema, fijo>                                         ~70 tokens, siempre en caché
[estado]                                                ≤ 120
hora: lunes 5 oct, 22:40
alarmas: #1 mañana 6:45 «trabajo» lun-vie · #2 sáb 10:00 «yoga» (desactivada)
temporizadores: «pasta» quedan 4:12
compra: leche · huevos ×6 · pan (hecho)
volumen: 40
foco: alarma #1                          pendiente: ninguno
[antes]                                                 ≤ 60
usuario: ¿qué alarmas tengo?
dina: alarm.list() → 2 alarmas
[ahora]                                                 ≤ 40
pon la primera a las siete
```

- **Presupuesto: ≤ 290 tokens.** Medido sobre RW2 con el tokenizador de LFM2.5: p50 122, p95 174,
  máximo 199 (90 fijos). El sistema está siempre en caché; todo lo anterior a `[ahora]` se
  precalcula mientras el usuario habla.
- **Recortes del estado** (`StateSummary`): listas de más de 6 elementos muestran 5 y «y N más»;
  solo los dominios con algo; el volumen solo si se habló de él o está silenciado. Los números
  `#n` aparecen solo cuando el usuario acaba de oír una lista.
- **Historial**: un turno, comprimido a acciones y resultado. Lo que depende de turnos anteriores
  llega por el foco o lo pendiente, que ya están en el estado.

## Quién decide qué

| Situación | Decide | Cómo |
|---|---|---|
| Falta un dato obligatorio («pon una alarma») | Código | Acción parcial → pregunta por plantilla y queda **pendiente** en el estado |
| El usuario responde a lo pendiente («a las ocho») | Modelo + código | El modelo escribe `alarm.add(8:00)`; el código lo fusiona con lo pendiente |
| Objetivo no dicho con 0 / 1 / varios candidatos | Código | 0 → «No tienes alarmas»; 1 → actúa; varios → pregunta con los candidatos |
| Hora 1–12 sin mañana ni tarde | Código | Política `ampm` |
| Borrar varios («vacía la lista») | Código | Política `bulk` |
| No se sabe qué quiere («hazme una cosa») | Modelo | `ask()` → el código pregunta |
| Fuera de alcance («pon música») | Modelo | `no(música)` → plantilla honesta |
| Corrección tras ejecutar («no, a las ocho») | Modelo | `alarm.edit(@, at=8:00)` |
| «Deshaz eso» | Modelo | `undo()` → el código revierte con su diario |
| Interrupción con algo pendiente | Modelo + código | Ejecuta la nueva petición; lo pendiente se conserva unos turnos |
| Charla («¿qué tal estás?») | Modelo | `say("…")`, la única salida de texto libre |

Políticas de la app (`policy=ampm:mixed+bulk:direct` en el evaluador):

| Política | Valores | En la app |
|---|---|---|
| `ampm` (hora 1–11 sin franja) | `ask` · `next` (la próxima) · `mixed` (la próxima, salvo para despertarse: por la mañana) | **`mixed`** |
| `bulk` (borrar varios) | `confirm` (más de N) · `direct` | **`direct`**: borra, dice cuántos y `undo()` lo recupera |

Fecha y hora: hora, fecha y día actuales (también de los lugares conocidos), qué día cae una fecha
y cuánto falta. «Qué fecha será dentro de N días» sigue fuera de alcance. `no(zonas)` se conserva
en el contrato para leer datos antiguos; no se enseña en el entrenamiento de Dina 4.5.

## Datos canónicos

Cada ejemplo guarda el reloj, el estado inicial, los turnos anteriores (acciones canónicas y
resultados), la frase y las acciones canónicas esperadas como estructuras, no como texto. Desde ahí
se renderizan el prompt y la salida. El oráculo del evaluador (`eval/EvalWorld.kt`) resuelve
selectores y ejecuta en `ToolEngine`: es la base del render y de la verificación.
