Trabajas en la raíz de este repositorio (asistente de voz Dina). Tarea: escribir y comprobar los datos del lote `<LOTE>` con subagentes. No cambies código, no hagas commits, no leas nada de `benchmark/` y no leas más archivos de los que se indican aquí.

Repite este bucle:

1. Desde la raíz del repo: `python scripts/data/pipeline.py next --batch <LOTE>`. Junta lo ya hecho, exporta lo siguiente y lista los archivos por hacer agrupados como `[quién] modelo (razonamiento)`.
2. Por cada archivo de un grupo `[codex]`, lanza un subagente con ese modelo y ese razonamiento (`luna-6` → Luna 6 en xhigh; `sol-6.1` → Sol 6.1 en medium; `sol-6` → Sol 6 en medium). Tantos en paralelo como puedas. Dale solo esto:
   > Lee `<ruta>/in-NN.txt` y sigue sus instrucciones al pie de la letra. Escribe tu respuesta en `<ruta>/out-NN.txt`. La primera línea debe ser exactamente `modelo: <modelo>`; después, solo lo que pide el archivo, sin comentarios ni explicaciones. Escribe cada línea tú mismo: nada de scripts, plantillas ni bucles. No leas otros archivos.
3. Cuando estén todos los `out-NN.txt`, vuelve al paso 1.

Para cuando `next` diga `listo` o solo queden grupos `[claude]` (esos los hace Claude Code). Si `next` avisa de que un `out-NN.txt` no se usa (otro modelo), bórralo y que lo rehaga el modelo que pide. Si un comando falla, para y avisa con el error.

Al terminar, responde en español en dos líneas: cuántos archivos hizo cada modelo y si algo falló.
