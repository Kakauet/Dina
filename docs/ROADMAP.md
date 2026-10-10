# Hoja de ruta

**Versión actual: app 2.4 con Dina 4.5** (octubre de 2026), en dos ediciones: Dina (1.2B: RW2 v2.0 86,1 %,
conversiones 95,3 %, Dina-Real 85,0 %) y Dina Lite (350M: 79,9 % y 79 %), las dos con 0 afirmaciones falsas.
La 2.4 habla antes y sin huecos (voz recortada, guía solo en los primeros pasos, caché de audio). Lo hecho
hasta aquí está en [HISTORY.md](HISTORY.md).

## Pendiente de la 2.4

Medido solo en el PC; falta comprobarlo en el S24 (y Lite en un móvil modesto):

- Latencia real («Primera síntesis» y «TOTAL» en Diagnóstico, con las etapas de la voz) y RAM (PSS)
  frente a la 2.3 (en el PC, misma medida: la voz de la 2.3 ocupa 151–159 MB al cargar y ~305 de pico; la de la 2.4, 145 y 270).
- `AudioTrack`: si `AudioTrack.getTimestamp` da el fin del audio («Fin del audio visto por: timestamp»),
  si hay huecos entre frases («Huecos entre frases» debería ser 0 ms) y si la escucha de seguimiento se abre sin
  recoger la cola de la voz.
- Si en ARM va mejor `MatMul` + 8 bits que la convolución de antes, y si merece la pena `MatMulNBits`
  (`.\dev.ps1 tts-models nbits8`: en el PC, -30 % de tiempo y mejor calidad, pero +58 MB de RAM).
- Lite: los hilos por defecto salen de medidas del PC; hay que repetirlas en un móvil con núcleos grandes y
  pequeños.
- Decisiones de oído: la guía solo en los primeros pasos (por defecto: 8 pasos en Dina y 4 en Dina Lite, con guía completa), pausas por
  puntuación, corte de la primera frase, márgenes del recorte.

## Siguiente

Fallos que Dina 4.5 aún comete en RW2 (el motor ya hace lo correcto con ellos):

- **Peticiones imposibles parecidas a las que sí hace** («pon una alarma en el móvil de mi mujer»,
  «¿puedes ver lo que hay en la nevera?»): intenta actuar en vez de decir que no puede. Los «no
  puedo» bajaron al 2,5 % de los ejemplos; hace falta un lote con más.
- **Números dichos con palabras**: «trescientos sesenta» como 300, «treinta y siete y medio» como 35,5.
- **Cambios relativos**: «media hora antes» como +30 minutos; «un cuarto para las siete» como 4:45.
- **Multiacciones largas** en las que se deja una parte, y «para el crono y dime cuánto llevo».
- Las categorías más flojas siguen siendo interrupciones (69 %), multiacción (78 %) y, en
  Dina-Real, directas, correcciones y errores (67–70 %, con solo 6–16 episodios cada una).

Puertas para un modelo nuevo: superar a Dina 4.5 en RW2 v2.0 sin bajadas significativas por
categoría (McNemar pareado, p < 0,05), 0 afirmaciones falsas y una sola medida de Dina-Real al final.

## Ideas

- LiteRT/XNNPACK para Supertonic quedó en pausa: en el PC no bajó la latencia y subió la RAM (XNNPACK en x86
  convierte a fp32 los pesos de 8 y 16 bits). Habría que medirlo en ARM antes de retomarlo (los scripts de conversión se quitaron tras la app 2.4; están en el historial de Git).
- Probar el detector de «Dina» en el móvil (Sensibilidad y Diagnóstico › Registrar intentos).
- Más variedad de frases (otras familias de modelos, frases reales con permiso) antes que más
  volumen.
- Un reconocimiento de voz más preciso en español sin salir del dispositivo.
