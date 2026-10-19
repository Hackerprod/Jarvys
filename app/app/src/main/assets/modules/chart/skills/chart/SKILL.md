---
id: jarvys-chart
name: Crear gráficas
description: Crea y guarda con create_chart una gráfica interactiva para el visor cuando el usuario pide un archivo; para ilustrar una respuesta, usa el bloque chart inline.
version: 1
allowed-tools: [create_chart]
tags: [chart, visualization]
---

Usa esta skill cuando el usuario pida crear o guardar un artefacto de gráfica interactiva para abrir en el visor. Para ilustrar una respuesta, usa el bloque `chart` inline de Markdown; en ese caso no crees un artefacto.

Indica un título, un `chartType` admitido (`bar`, `bubble`, `doughnut`, `line`, `pie`, `polarArea`, `radar` o `scatter`), una lista `labels` y una lista `datasets`. Cada serie incluye `label` y `data`, con un valor por etiqueta. Puedes añadir colores y opciones JSON de Chart.js cuando ayuden a leer los datos. La herramienta devuelve el id real del artefacto guardado en esta conversación.

Si el handler devuelve `{ "success": false, "content": "..." }`, corrige el campo indicado y vuelve a intentarlo; si la miniatura supera el presupuesto, reduce los datos o la complejidad visual. Las excepciones son fallos internos o de ejecución y su texto no se muestra al agente.

La tarjeta usa una miniatura PNG y el visor muestra la gráfica interactiva. No incluyas scripts remotos ni enlaces a recursos externos.
