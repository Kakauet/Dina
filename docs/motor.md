# Motor de diálogo

Todo lo que un modelo pequeño hace mal y el código puede hacer bien. `ToolEngine` es el dueño del
estado del mundo; encima va **`dialog/`**, independiente del modelo. `brain/dina45/` solo contiene
el prompt, el códec, la gramática y las llamadas al modelo.

```
frase → Dina45Brain: prompt (StateSummary) → modelo → Dina45Codec → acciones parciales
      → dialog/Resolver (referencias, políticas) → Dialogue (pendiente, foco, deshacer)
      → ToolEngine → dialog/Responder (plantillas) [+ say filtrado] → voz
```

## Piezas

| Pieza | Qué hace | Detalle |
|---|---|---|
| `Intent` | Tipos del contrato y tabla `Ops` | de la tabla salen el códec y la gramática ([contrato.md](contrato.md)) |
| `Resolver` | Convierte el objetivo descrito en elementos concretos | etiqueta (clave normalizada sin artículos ni plurales, `SpanishText.key`), hora con o sin franja, franja (`tarde`), posición tal como se leyó, `@` (foco), `sonando`, superlativos («la próxima»), `*`. Resultado: 0, 1 o varios candidatos |
| Políticas | `ampm` (ask, next, mixed) y `bulk` (confirm con umbral N, direct) | parámetros de `Dialogue` |
| `Dialogue` | Estado del diálogo, **efímero** (no va a `ToolWorldJson`) | foco por dominio, lo pendiente, diario para deshacer, última lista leída, último cálculo |
| Foco | El último elemento creado, cambiado, consultado o nombrado | caduca a los 3 turnos o 10 min |
| Pendiente | Acción parcial + dato que falta o candidatos | vive 2 turnos (sobrevive a una interrupción) o 2 min; una respuesta corta se fusiona; `drop()` lo borra |
| Deshacer | Diario de operaciones inversas del último turno | un nivel; deshace el turno entero (varias acciones juntas); crear ↔ borrar, cambiar ↔ valor anterior |
| `StateSummary` | Bloque `[estado]` del prompt | con el presupuesto de [contrato.md](contrato.md#entrada-del-modelo) |
| `Responder` | Plantillas en español y filtro de `say` | cada variante dice todos los hechos del resultado ([contrato.md](contrato.md#charla-chistes-y-curiosidades)) |
| `FunBank` | Chistes y curiosidades | los elige el motor, nunca el modelo |
| `Grounding` | Frena productos que el modelo inventa | solo deja pasar productos dichos en la frase, el turno anterior o el estado; si faltan, pregunta. Se omite al reproducir etiquetas canónicas, pero se usa con modelos reales, incluido desarrollo |

Ampliaciones de la fase 3:

- Antes de las **5:00**, «mañana» al poner una alarma se interpreta como hoy y la respuesta lo
  explica. Los días del mes y las fiestas con nombre se resuelven en código.
- `conv` pregunta por lo que falta y usa `Units` para calcular; `Places` resuelve las ciudades
  de `time.now`. Son consultas y no cambian el diario de deshacer. La tarjeta de conversión
  muestra el valor y, cuando corresponde, el ingrediente.
- `undo()` abandona una pregunta pendiente más reciente que el último cambio antes de tocar ese
  cambio; pedir borrar un temporizador que suena lo silencia. Las etiquetas que solo repiten la
  duración o el tipo de elemento se descartan.

## Lo que comparte con el evaluador

El resolutor de selectores del oráculo, la normalización del español (`SpanishText`) y la
detección de afirmaciones falsas están en `dialog/` con sus tests, y el evaluador los usa desde
ahí. El benchmark no se vuelve más fácil: el oráculo describe el mundo, no la frase.

## Tests

- JVM por pieza (reloj y mundo de `TestTools`): cada forma de referencia con 0, 1 y varios
  candidatos; cada política; caducidad del foco y de lo pendiente; deshacer de cada operación;
  recortes del resumen.
- **Techo del diseño**: `.\dev.ps1 eval dina45-scripted v2` ejecuta `Dina45Brain` con un modelo
  guionizado que devuelve la salida perfecta del contrato en cada turno de RW2. En la fase 3:
  **100 %** de v2.0 (432/432; IC 95 % [99,1–100]) y v2.1 (496/496; [99,2–100]), incluida
  `conversiones` (64/64; [94,3–100]). Lo que
  falla con el modelo real es del modelo, no del motor ni del contrato. Cualquier cambio en el
  motor o en el contrato debe mantener este techo.
