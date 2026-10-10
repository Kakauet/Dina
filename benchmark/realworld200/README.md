# RealWorld200 (RW200)

Primer benchmark de Dina, congelado en su versión 1: 200 episodios y 377 turnos en 7 dominios (alarmas,
temporizadores, cronómetros, compra, volumen, fecha y hora, cálculo). Sustituido por RW2
(`../realworld_v2/`); se conserva como referencia histórica.

Un episodio pasa solo si cada turno toma la decisión correcta (actuar, preguntar o rechazar), ejecuta las
acciones esperadas, deja el estado esperado y responde de acuerdo con el resultado real.

## Contenido

- `episodes/episodes_*.jsonl`: los episodios, con `initial_state`, `clock_start`, los turnos del usuario y,
  por turno, `expected` (decisión, acciones, resultados, `state_after` y requisitos de la respuesta).
- `schema/episode.schema.json`: esquema de un episodio.
- `manifest.json`, `selection.json`: identidad y hashes de la versión congelada.

## Reglas

1. No se usa para entrenar, generar datos ni ajustar hiperparámetros.
2. La versión 1 no se modifica; una corrección implicaría una versión nueva.

## Evaluación

`.\dev.ps1 eval dina45 rw200` importa los episodios sin modificarlos (`Rw200` y `Rw200Calls` en `eval/`) y
puntúa por estado y por hechos de la respuesta. RW200 sobrestima los fallos (episodios generados por
plantilla, el mismo reloj en todos, respuestas comparadas por subcadena), por lo que el desarrollo se mide
con RW2.

| Modelo | Éxito por episodio |
|---|---:|
| Dina 2 350M | 37,5 % |
| Dina 2.5 350M | 38,0 % |
| Dina 3 1.2B (app 2.0) | 42,5 % |
| Dina 3 1.2B (app 2.1) | 61,0 % |
