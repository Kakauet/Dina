# Dina-Real (prueba ciega)

100 situaciones (144 frases) dictadas por personas reales con el teclado del móvil. Mide lo que RW2 no
cubre: cómo se expresa un usuario real y cómo llega la frase tras el reconocimiento de voz. No se usa para
ajustar nada.

| Archivo | Contenido | Acceso |
|---|---|---|
| `fichas.md` | Situaciones en texto llano, sin frases de ejemplo | Quien dicta |
| `dictado.txt` | Plantilla de dictado, una línea por frase (`F001a: `) | Quien dicta |
| `frases.tsv` | `frase_id`, `persona`, `frase` (salida del dictado). Excluido de Git | Solo el evaluador |
| `fichas.jsonl` | Estado, reloj y resultado esperado en formato RW2 (`"u":"F001a"` como hueco) | Solo el evaluador y su test |

## Protocolo de dictado

1. Copiar `dictado.txt` al móvil y abrirlo en una app de notas. Leer solo `fichas.md`: ni `fichas.jsonl`
   ni los episodios de RW2, porque conocer la respuesta esperada cambia la forma de hablar.
2. Para cada ficha, dictar con el micrófono del teclado (Gboard) lo que se le diría a Dina. Las fichas con
   varias letras (a, b, c) forman una conversación.
3. No corregir la transcripción: los errores del dictado forman parte de la medida. Una sola toma por frase.
4. Importar el archivo: `python benchmark/dina_real/importar.py dictado.txt <nombre>`. Cada persona
   aporta episodios independientes.

Duración aproximada: 25-30 minutos por persona.

## Reglas

- Ni `frases.tsv` ni los fallos de este conjunto se usan para escribir prompts, datos, plantillas o reglas
  del motor. `DinaRealTest` solo valida las fichas con frases de relleno.
- Las frases no se envían a ninguna API ni entran en datos de entrenamiento.
- Solo se evalúan modelos ya entrenados (`.\dev.ps1 eval dina45 dina-real model=…`), una vez por modelo.
  En `docs/HISTORY.md` se registran la cifra global y por categoría; los fallos concretos no se revisan.
