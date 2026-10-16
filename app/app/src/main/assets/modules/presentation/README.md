# Módulo de presentaciones

La generación usa el bundle de navegador de PptxGenJS y ejecuta en el WebView del anfitrión. Escribe texto, formas e imágenes incorporadas en un archivo `.pptx`; JSZip 3.10.1 lee ese OOXML para dibujar una miniatura PNG de la primera diapositiva y para el visor local.

El visor propio interpreta el orden de diapositivas, el tamaño de página, formas rectangulares/elípticas/líneas, rellenos y trazos sólidos, texto sencillo e imágenes embebidas. No implementa temas, gráficos OOXML, grupos, tablas, recortes de imagen, transiciones ni todo el sistema de fuentes de PowerPoint. Es una vista aproximada del contenido que crea esta herramienta, no un renderizador general de presentaciones.

## Elección de librerías

- Generación: **PptxGenJS 4.0.1**, bundle UMD minificado `vendor/pptxgen.min.js`, licencia MIT en `vendor/PPTXGENJS-LICENSE.md`.
- Lectura ZIP: **JSZip 3.10.1**, bundle UMD minificado `vendor/jszip.min.js`, licencia MIT en `vendor/JSZIP-LICENSE.md`.
- Fuentes upstream: [PptxGenJS 4.0.1](https://github.com/gitbrent/PptxGenJS/tree/v4.0.1) y [JSZip 3.10.1](https://github.com/Stuk/jszip/tree/v3.10.1). JSZip ofrece MIT o GPL-3.0-or-later; este módulo usa la alternativa MIT y conserva el texto de licencia.
- Renderizado: `viewer/deck-renderer.js`, una implementación local pequeña para el subconjunto de OOXML producido por PptxGenJS. Se eligió para evitar añadir una biblioteca grande de renderizado PPTX→HTML/canvas y mantener legible la selección concreta de formas/texto/imágenes admitidos.
- Los assets quedan dentro del módulo, con versiones fijas; no se carga CDN ni se usa runtime de Node, Linux o shell en la app.

## Contrato con el anfitrión

El handler llama a `Jarvys.artifactDeliverBytes(base64, name, artifactType, title, thumbnailBase64, thumbnailMimeType)`. Espera JSON de confirmación con el id persistido (`id`); ese id aparece en el resultado de la tool. El base64 del archivo es el PPTX generado y el base64 de miniatura sale de dibujar la primera diapositiva del ZIP generado.
