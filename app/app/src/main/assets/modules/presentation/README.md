# Módulo de presentaciones

`create_presentation` produce un `.pptx` nativo con PptxGenJS. Además de texto, formas e imágenes incorporadas, admite gráficos de barras y tablas editables de PowerPoint. El handler entrega los bytes del archivo mediante la capacidad `artifact.deliver` y conserva la miniatura PNG que aparece en la tarjeta de chat.

## Defaults de diseño

- Encabezados Cambria y cuerpo Arial, con tamaño de título destacado y texto de apoyo legible.
- Fondo casi blanco por defecto; el color del texto se elige por luminancia para conservar contraste en fondos claros y oscuros.
- Paleta consistente para series de gráficos y colores de gráfico más luminosos sobre temas oscuros.
- Gráficos nativos con etiquetas de categorías y valores, título cuando se aporta y sin leyenda redundante en una serie.
- Tablas con encabezado oscuro, texto claro y filas alternadas claras con texto oscuro.
- Alineación, márgenes y espacio entre elementos definidos en el módulo.

La herramienta espera que el agente formule títulos que expresen la conclusión y mantenga una idea central por diapositiva. El manifiesto y `skills/presentation/SKILL.md` describen esas pautas junto con los tipos de contenido admitidos.

## Visor y evaluación

El visor permanece sin cambios tras comparar cuatro candidatos contra el mismo conjunto de archivos. Ninguno mostró gráficos, tablas, temas y contenido con fidelidad suficiente para ser adoptado dentro del WebView aislado. La tabla con licencias, versiones, tamaño, dependencias, errores, tiempos y capturas está en [`app/docs/pptx-viewer-eval/README.md`](../../../../../../docs/pptx-viewer-eval/README.md).

El visor actual lee el `.pptx` entregado y representa localmente un subconjunto de OOXML: texto simple, formas rectangulares/elípticas/líneas, rellenos y trazos sólidos e imágenes incorporadas. Gráficos, tablas y otros `graphicFrame` muestran un aviso y quedan vacíos. En la comparación también se observan cajas de texto con demasiado espacio y el render de la imagen de prueba falla en el harness headless. Las capturas explican por qué el resultado todavía no equivale a un visor completo de PowerPoint.

La miniatura se sigue generando desde los bytes del `.pptx` entregado con `viewer/deck-renderer.js`; el contrato Node verifica que recibe esos mismos bytes y produce un PNG. Como el renderer comparte el subconjunto descrito, una diapositiva inicial cuyo elemento principal sea un gráfico o una tabla también tendrá esa limitación en su miniatura.

## Dependencias incluidas

| Componente | Versión | Uso | Licencia |
|---|---:|---|---|
| PptxGenJS | 4.0.1 | Generación del archivo PowerPoint | MIT, `vendor/PPTXGENJS-LICENSE.md` |
| JSZip | 3.10.1 | Lectura OOXML local para miniatura y visor | MIT, `vendor/JSZIP-LICENSE.md` |

Los candidatos de renderizado probados no se empaquetan. No se añadió dependencia de runtime, CDN, permiso de red, worker ni esquema Room. Los scripts y capturas de evaluación quedan en `app/docs/pptx-viewer-eval/`.

## Reglas de diseño y licencia de la fuente

Las pautas del skill local resumen y parafrasean recomendaciones generales consultadas en el [skill PPTX de Anthropic](https://github.com/anthropics/skills/blob/main/skills/pptx/SKILL.md). Su `LICENSE.txt` declara condiciones propietarias; por eso no se copió código, texto, ejemplos ni paletas, y solo se conservaron ideas generales de diseño en redacción propia. En el directorio actual no existen los archivos independientes `pptxgenjs.md` ni `html2pptx.md`; la guía de PptxGenJS está integrada en `SKILL.md`.

Fuentes: [SKILL.md](https://github.com/anthropics/skills/blob/main/skills/pptx/SKILL.md) · [LICENSE.txt](https://github.com/anthropics/skills/blob/main/skills/pptx/LICENSE.txt).
