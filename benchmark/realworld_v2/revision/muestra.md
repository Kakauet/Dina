# RW2: muestra para revisión

44 episodios (4 por categoría, elegidos al azar con semilla fija). Para cada uno: ¿la frase
expresa exactamente lo esperado?, ¿falta alguna respuesta válida?, ¿suena natural?
Anota `OK`, `CORREGIR: …` o `QUITAR: …` debajo de cada episodio.

Notación: `alarm.add 08:15 day=mañana 'médico'` crea una alarma; `@'gimnasio'`, `@19:00`, `@2`, `@last` señalan
un elemento existente; `ask` = Dina debe preguntar por ese dato; los hechos (`time:`, `dur:`, `num:`, `word:`)
son lo que la respuesta debe decir. Detalle completo en [../README.md](../README.md).

### amb-18 · ambiguedad · es-MX · alarm, stt
jueves 22/10/2026 21:00 · estado: alarmas: mañana 07:00 'escuela'

- 👤 «pónmela más temprano»
  - ✅ pregunta por **time**
- 👤 «a las seis y cuarto»
  - ✅ hace `alarm.edit @'escuela' at=06:15`

*Por qué:* Cambio sin cantidad: preguntar a qué hora.

Revisión: 

### amb-19 · ambiguedad · es-ES · alarm
lunes 26/10/2026 12:00 · estado: vacío

- 👤 «Pon una alarma el jueves.»
  - ✅ pregunta por **time**
- 👤 «A las nueve de la noche.»
  - ✅ hace `alarm.add 21:00 day=jue`

*Por qué:* Día sin hora.

Revisión: 

### amb-26 · ambiguedad · es-ES · calculator, stt
miércoles 04/11/2026 12:00 · estado: vacío

- 👤 «cuánto es»
  - ✅ pregunta por **expr**

*Por qué:* Pregunta sin contenido.

Revisión: 

### amb-35 · ambiguedad · es-ES · shopping
martes 17/11/2026 18:00 · estado: compra: 'leche entera' · 'leche de avena' · pan

- 👤 «Quita la leche.»
  - ✅ pregunta por **target** nombrando word:entera, word:avena
- 👤 «La de avena.»
  - ✅ hace `list.del @'leche de avena'`

*Por qué:* Nombre que encaja con dos productos.

Revisión: 

### cha-02 · charla · es-ES · smalltalk, stt
martes 06/10/2026 18:00 · estado: vacío

- 👤 «qué tal estás»
  - ✅ charla (breve, sin acciones)

*Por qué:* Charla breve.

Revisión: 

### cha-07 · charla · es-ES · greeting
domingo 11/10/2026 23:15 · estado: vacío

- 👤 «Buenas noches, Dina.»
  - ✅ charla (breve, sin acciones)

*Por qué:* Despedida: no crear una alarma por su cuenta.

Revisión: 

### cha-10 · charla · es-ES · smalltalk, stt
miércoles 14/10/2026 19:00 · estado: vacío

- 👤 «cuéntame un chiste»
  - ✅ charla (breve, sin acciones)

*Por qué:* Breve.

Revisión: 

### cha-34 · charla · es-AR · smalltalk
domingo 27/12/2026 21:00 · estado: vacío

- 👤 «Sos re copada, Dina.»
  - ✅ charla (breve, sin acciones)

*Por qué:* Cumplido rioplatense.

Revisión: 

### con-06 · consultas · es-MX · datetime, edge, stt
viernes 01/01/2027 00:20 · estado: vacío

- 👤 «qué fecha es hoy»
  - ✅ hace `time.now date`

*Por qué:* Recién empezado el año.

Revisión: 

### con-08 · consultas · es-ES · timer
viernes 09/10/2026 21:30 · estado: temporizadores: 20m left=13m05s

- 👤 «¿Cuánto queda?»
  - ✅ hace `timer.get`

*Por qué:* Consulta elíptica con un único temporizador.

Revisión: 

### con-19 · consultas · es-ES · datetime
jueves 10/12/2026 12:00 · estado: vacío

- 👤 «¿En qué día cae el uno de enero?»
  - ✅ hace `time.weekday 2027-01-01`

*Por qué:* Fecha del año siguiente.

Revisión: 

### con-35 · consultas · es-ES · datetime, stt, edge
domingo 28/03/2027 03:10 · estado: vacío

- 👤 «dime la hora»
  - ✅ hace `time.now time`

*Por qué:* Justo después del cambio a horario de verano.

Revisión: 

### cor-19 · correcciones · es-ES · volume, inline
jueves 22/10/2026 23:00 · estado: volumen: 70

- 👤 «Bájalo a veinte... bueno, a veinticinco.»
  - ✅ hace `vol.set 25`

*Por qué:* «Bueno» como corrección.

Revisión: 

### cor-27 · correcciones · es-MX · shopping, after, stt
miércoles 04/11/2026 18:00 · estado: compra: tortillas

- 👤 «agrega frijoles»
  - ✅ hace `list.add 'frijoles|frijol'`
- 👤 «ah no ya tenía bórralos»
  - ✅ hace `list.del @'frijoles'`

*Por qué:* Plural en el pronombre.

Revisión: 

### cor-29 · correcciones · es-ES · volume, after
viernes 06/11/2026 21:00 · estado: volumen: 20

- 👤 «Pon el volumen a cincuenta.»
  - ✅ hace `vol.set 50`
- 👤 «Uf, muy alto, bájalo un poco.»
  - ✅ opción A: hace `vol.down 10`
  - ✅ opción B: hace `vol.down 5`
  - ✅ opción C: hace `vol.down 15`
  - ✅ opción D: hace `vol.down 20`

*Por qué:* Corrección relativa.

Revisión: 

### cor-38 · correcciones · es-AR · shopping, after
martes 08/12/2026 13:00 · estado: vacío

- 👤 «Agregá frutillas.»
  - ✅ hace `list.add 'frutillas|frutilla'`
- 👤 «No, pará, eran arándanos.»
  - ✅ hace `list.edit @'frutillas' name='arándanos'`

*Por qué:* Sustituir el producto recién añadido.

Revisión: 

### dir-01 · directas · es-ES · alarm
lunes 05/10/2026 22:40 · estado: alarmas: mañana 06:45 'trabajo' rep=laborables

- 👤 «Ponme una alarma mañana a las ocho y cuarto de la mañana para el médico.»
  - ✅ hace `alarm.add 08:15 day=mañana 'médico|medico'`

*Por qué:* Alarma con día, hora con cuarto y etiqueta, con otra alarma ya puesta.

Revisión: 

### dir-10 · directas · es-ES · timer
lunes 05/10/2026 13:30 · estado: temporizadores: 40m 'horno' left=12m

- 👤 «Pon un temporizador de doce minutos para la pasta.»
  - ✅ hace `timer.add 12m 'pasta'`

*Por qué:* Temporizador con etiqueta cuando ya hay otro en marcha.

Revisión: 

### dir-15 · directas · es-ES · timer
viernes 23/10/2026 17:00 · estado: vacío

- 👤 «Cuenta atrás de tres minutos.»
  - ✅ hace `timer.add 3m`

*Por qué:* «Cuenta atrás» como temporizador.

Revisión: 

### dir-25 · directas · es-ES · shopping, stt
domingo 18/10/2026 11:20 · estado: vacío

- 👤 «apunta una docena de huevos»
  - ✅ hace `list.add 'huevos|huevo' n=12`

*Por qué:* «Una docena» como cantidad.

Revisión: 

### err-19 · errores · es-ES · timer, not_found
viernes 23/10/2026 14:00 · estado: temporizadores: 20m 'arroz' left=12m

- 👤 «Elimina el temporizador de la pasta.»
  - ✅ opción A: responde sin tocar nada; debe decir neg
  - ✅ opción B: pregunta por **target** nombrando word:arroz

*Por qué:* Etiqueta equivocada: avisar u ofrecer el que hay.

Revisión: 

### err-24 · errores · es-ES · alarm, not_found
miércoles 28/10/2026 21:00 · estado: alarmas: 2026-10-30 07:00 'viaje'

- 👤 «Quita la alarma de mañana.»
  - ✅ opción A: responde sin tocar nada; debe decir neg
  - ✅ opción B: pregunta por **target** nombrando time:07:00/word:viaje

*Por qué:* La única alarma es de pasado mañana.

Revisión: 

### err-25 · errores · es-ES · volume, injected
jueves 29/10/2026 20:00 · estado: volumen: 70

- 👤 «Pon el volumen al cuarenta.» *(fallo forzado: {'volume': 'unavailable'})*
  - ✅ hace `vol.set 40`; y el fallo se informa
- 👤 «Bueno, déjalo.»
  - ✅ responde sin tocar nada

*Por qué:* Fallo y abandono: no reintentar por su cuenta.

Revisión: 

### err-26 · errores · es-ES · alarm, invalid_state, stt
viernes 30/10/2026 21:00 · estado: alarmas: mañana 08:00 'clase' off

- 👤 «desactiva la alarma de las ocho»
  - ✅ opción A: responde sin tocar nada; debe decir word:ya estaba|ya esta|desactivada|apagada
  - ✅ opción B: hace `alarm.off`

*Por qué:* Ya estaba desactivada.

Revisión: 

### fue-04 · fuera_de_alcance · es-ES · internet
jueves 08/10/2026 21:00 · estado: vacío

- 👤 «¿Qué tiempo va a hacer mañana en Sevilla?»
  - ✅ rechaza (no puede)

*Por qué:* Necesita internet: no inventar el tiempo.

Revisión: 

### fue-10 · fuera_de_alcance · es-ES · capability, stt
miércoles 14/10/2026 11:00 · estado: vacío

- 👤 «sabes hacer cuentas»
  - ✅ opción A: responde sin tocar nada; debe decir yes
  - ✅ opción B: pregunta por **expr**

*Por qué:* Capacidad dentro del alcance.

Revisión: 

### fue-18 · fuera_de_alcance · es-ES · device, stt
jueves 22/10/2026 23:30 · estado: vacío

- 👤 «apaga el móvil»
  - ✅ rechaza (no puede)

*Por qué:* Control del sistema.

Revisión: 

### fue-19 · fuera_de_alcance · es-ES · device
viernes 23/10/2026 21:00 · estado: volumen: 40

- 👤 «Sube el brillo de la pantalla.»
  - ✅ rechaza (no puede)

*Por qué:* Parecido a «sube el volumen», pero no: no tocar el volumen.

Revisión: 

### int-11 · interrupciones · es-ES · alarm, chat
jueves 15/10/2026 22:30 · estado: vacío

- 👤 «Pon una alarma.»
  - ✅ pregunta por **time**
- 👤 «Gracias por todo, eres un sol.»
  - ✅ charla (breve, sin acciones)
- 👤 «A las seis de la mañana.»
  - ✅ hace `alarm.add 06:00 day=mañana`

*Por qué:* Charla en medio; lo pendiente no se pierde.

Revisión: 

### int-14 · interrupciones · es-ES · timer, volume, stt
domingo 18/10/2026 19:00 · estado: temporizadores: 50m 'horno' left=21m; volumen: 55

- 👤 «cuánto queda del horno»
  - ✅ hace `timer.get @'horno'`
- 👤 «vale oye y el volumen a cuánto está»
  - ✅ hace `vol.get`
- 👤 «ponle cinco minutos más al horno»
  - ✅ hace `timer.plus @'horno' 5m`

*Por qué:* Referencia explícita tras cambiar de tema.

Revisión: 

### int-17 · interrupciones · es-ES · timer, alarm
martes 20/10/2026 21:00 · estado: vacío

- 👤 «Pon un temporizador.»
  - ✅ pregunta por **duration**
- 👤 «Y una alarma también.»
  - ✅ pregunta
- 👤 «El temporizador de cinco minutos y la alarma a las ocho de la mañana.»
  - ✅ hace `timer.add 5m`, `alarm.add 08:00`

*Por qué:* Dos peticiones pendientes resueltas a la vez.

Revisión: 

### int-20 · interrupciones · es-ES · timer, stt
viernes 23/10/2026 13:00 · estado: vacío

- 👤 «pon un temporizador»
  - ✅ pregunta por **duration**
- 👤 «eh no espera que hora es»
  - ✅ hace `time.now time`
- 👤 «de 10 minutos»
  - ✅ hace `timer.add 10m`

*Por qué:* Interrupción transcrita sin signos.

Revisión: 

### mul-15 · multiaccion · es-ES · timer
lunes 19/10/2026 13:00 · estado: temporizadores: 20m 'lentejas' left=12m

- 👤 «Añade cinco minutos al de las lentejas y pon otro de tres para el pan.»
  - ✅ hace `timer.plus @'lentejas' 5m`, `timer.add 3m 'pan'`

*Por qué:* Modificar uno y crear otro.

Revisión: 

### mul-32 · multiaccion · es-ES · shopping
sábado 07/11/2026 10:00 · estado: compra: leche n=1

- 👤 «Pon dos de leche más y añade cereales.»
  - ✅ hace `list.edit @'leche' n=3`, `list.add 'cereales|cereal'`

*Por qué:* Sumar a una cantidad existente.

Revisión: 

### mul-34 · multiaccion · es-ES · datetime, calculator
lunes 09/11/2026 09:00 · estado: vacío

- 👤 «¿Qué día es hoy y cuánto es 15 por 12?»
  - ✅ hace `time.now date`, `calc '15*12'`; debe decir day:lunes/date:2026-11-09, num:180

*Por qué:* Dos consultas.

Revisión: 

### mul-40 · multiaccion · es-PE · shopping, stt
lunes 14/12/2026 12:30 · estado: vacío

- 👤 «anota arroz pollo y ají amarillo»
  - ✅ hace `list.add 'arroz'`, `list.add 'pollo'`, `list.add 'ají amarillo|aji amarillo'`

*Por qué:* Producto compuesto al final.

Revisión: 

### pen-01 · pendiente · es-ES · alarm
miércoles 07/10/2026 20:00 · estado: vacío

- 👤 «Pon una alarma para el viernes.»
  - ✅ pregunta por **time**
- 👤 «A las siete y media de la mañana.»
  - ✅ hace `alarm.add 07:30 day=vie`

*Por qué:* La respuesta completa la hora y conserva el día pedido antes.

Revisión: 

### pen-12 · pendiente · es-ES · calculator
domingo 18/10/2026 11:00 · estado: vacío

- 👤 «Calcula una cosa.»
  - ✅ pregunta por **expr**
- 👤 «Mil doscientos entre doce.»
  - ✅ hace `calc '1200/12'`

*Por qué:* Número compuesto en letra.

Revisión: 

### pen-31 · pendiente · es-ES · stopwatch
sábado 28/11/2026 10:00 · estado: cronómetros: 'abdominales' 3m · 'carrera' 21m

- 👤 «Reinicia el cronómetro.»
  - ✅ pregunta por **target** nombrando word:abdominales, word:carrera
- 👤 «El primero.»
  - ✅ hace `sw.reset @1`

*Por qué:* Respuesta por posición.

Revisión: 

### pen-34 · pendiente · es-ES · timer
martes 01/12/2026 14:00 · estado: vacío

- 👤 «Pon un temporizador.»
  - ✅ pregunta por **duration**
- 👤 «Como diez minutos, más o menos.»
  - ✅ hace `timer.add 10m`

*Por qué:* Respuesta con aproximación.

Revisión: 

### ref-09 · referencias · es-ES · stopwatch
lunes 12/10/2026 19:00 · estado: cronómetros: 'lectura' 10m · 'ejercicio' 30m

- 👤 «Detén el de lectura.»
  - ✅ hace `sw.pause @'lectura'`

*Por qué:* «Detén» con dos cronos en marcha (Dina 3 arrancaba uno nuevo).

Revisión: 

### ref-28 · referencias · es-ES · timer
martes 27/10/2026 13:00 · estado: temporizadores: 25m 'arroz' left=18m · 8m 'huevos' left=6m

- 👤 «Cancela el más corto.»
  - ✅ hace `timer.del @'huevos'`

*Por qué:* Superlativo sobre el tiempo restante.

Revisión: 

### ref-37 · referencias · es-ES · stopwatch
miércoles 11/11/2026 10:00 · estado: cronómetros: 'plancha' 5m paused · 'pasos' 18m

- 👤 «¿Cuánto marca el que está corriendo?»
  - ✅ hace `sw.get @'pasos'`

*Por qué:* Referencia por estado en una consulta.

Revisión: 

### ref-40 · referencias · es-MX · alarm
lunes 16/11/2026 21:30 · estado: alarmas: mañana 06:15 'chamba' · mañana 07:00 'gym'

- 👤 «La del gym muévela a las siete y media.»
  - ✅ hace `alarm.edit @'gym' at=07:30`

*Por qué:* Etiqueta en inglés coloquial; cambio de hora.

Revisión: 
