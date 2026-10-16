---
id: jarvys-presentation
name: Crear presentaciones
description: Genera archivos PowerPoint con el módulo de presentaciones.
version: 1
allowed-tools: [create_presentation]
tags: [presentation, powerpoint]
---

Cuando necesites entregar una presentación PowerPoint, usa la herramienta `create_presentation`.

Incluye un título y una lista `slides` en el orden deseado. Cada diapositiva puede tener `title`, `body`, `bullets`, `backgroundColor`, `shapes` e `images`. Las posiciones y dimensiones de formas e imágenes se expresan en pulgadas. Una imagen se aporta como `dataUrl` y queda incorporada al archivo.

La herramienta crea y entrega un archivo `.pptx` real y devuelve el id del artefacto guardado en esta conversación. La tarjeta muestra una miniatura PNG de la primera diapositiva; al abrir el artefacto, el visor local permite recorrer las diapositivas.
