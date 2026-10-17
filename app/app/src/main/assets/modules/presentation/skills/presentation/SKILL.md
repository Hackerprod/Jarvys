---
id: jarvys-presentation
name: Crear presentaciones
description: Usa create_presentation para crear presentaciones PowerPoint claras, coherentes y legibles.
version: 1
allowed-tools: [create_presentation]
tags: [presentation, powerpoint]
---

Cuando prepares un deck, organiza el contenido alrededor de una conclusión o acción. Cada diapositiva debe defender una idea principal; escribe títulos que comuniquen esa idea, no solo el tema.

## Dirección visual

- Elige una paleta apropiada al tema y reutilízala en todo el deck. Da un papel dominante a un color, acompáñalo con pocos tonos de apoyo y reserva el acento para resaltar datos importantes.
- Mantén contraste fuerte entre texto, fondo, formas y etiquetas. Si eliges un fondo oscuro, usa texto claro; sobre fondos claros, usa texto oscuro.
- Marca jerarquía con títulos prominentes, texto de apoyo más pequeño y detalles secundarios discretos. El generador usa Cambria para encabezados y Arial para el cuerpo y aplica tamaños legibles por defecto.
- Conserva márgenes amplios, alineaciones claras y separaciones constantes. Deja espacio libre y evita apilar demasiados elementos.
- Prefiere un recurso visual que aclare el mensaje: gráfico, tabla, imagen o forma con una función concreta. Evita adornos sin significado y composiciones repetidas en todas las diapositivas.

## Gráficos y tablas

- Para comparar datos, usa `chart` con un título informativo, categorías reconocibles en `labels` y valores numéricos en cada serie. El generador activa etiquetas de valor y una paleta contrastante; omite la leyenda cuando solo hay una serie.
- Para datos detallados, usa `table` con encabezados breves y filas legibles. El generador destaca el encabezado y alterna fondos claros con texto oscuro.
- Mantén juntas las categorías y sus valores. No dependas únicamente del color para distinguir datos.

## Evita

- Diapositivas saturadas, párrafos largos, títulos vagos y listas que repiten el texto del título.
- Texto o formas de bajo contraste, elementos pegados al borde, recortes, solapamientos y texto que se sale de su caja.
- Líneas decorativas bajo títulos, franjas de color sin función y una leyenda redundante para una sola serie.

Incluye solo la información necesaria para sostener el hilo de la presentación. En `create_presentation`, expresa posiciones y dimensiones de formas, imágenes, gráficos y tablas en pulgadas. La herramienta entrega el archivo `.pptx` real y una miniatura PNG de la primera diapositiva; al abrir el archivo, el visor del módulo permite recorrer sus diapositivas.
