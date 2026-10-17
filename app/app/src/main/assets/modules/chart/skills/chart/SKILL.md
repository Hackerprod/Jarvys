---
id: jarvys-chart
name: Crear gráficas
description: Crea gráficas interactivas con el módulo de gráficas.
version: 1
allowed-tools: [create_chart]
tags: [chart, visualization]
---

Cuando necesites mostrar datos como gráfica, usa la herramienta `create_chart`.

Indica un título, un `chartType` admitido (`bar`, `bubble`, `doughnut`, `line`, `pie`, `polarArea`, `radar` o `scatter`), una lista `labels` y una lista `datasets`. Cada serie incluye `label` y `data`, con un valor por etiqueta. Puedes añadir colores y opciones JSON de Chart.js cuando ayuden a leer los datos. La herramienta devuelve el id real del artefacto guardado en esta conversación.

Si el handler devuelve `{ "success": false, "content": "..." }`, corrige el campo indicado y vuelve a intentarlo; si la miniatura supera el presupuesto, reduce los datos o la complejidad visual. Las excepciones son fallos internos o de ejecución y su texto no se muestra al agente.

La tarjeta usa una miniatura PNG y el visor muestra la gráfica interactiva. No incluyas scripts remotos ni enlaces a recursos externos.
