# Módulo de presentaciones

La generación usa el bundle de navegador de PptxGenJS y ejecuta en el WebView del anfitrión. Escribe texto, formas e imágenes incorporadas en un archivo `.pptx`; JSZip 3.10.1 lee ese OOXML para dibujar una miniatura PNG de la primera diapositiva y para el visor local.

El visor propio interpreta el orden de diapositivas, el tamaño de página, formas rectangulares/elípticas/líneas, rellenos y trazos sólidos, texto sencillo e imágenes embebidas. No implementa todo el sistema de temas/fuentes, gráficos OOXML, grupos, tablas, recortes de imagen ni transiciones de PowerPoint. Es una vista aproximada del contenido que crea esta herramienta, no un renderizador general de presentaciones.

## Elección de librerías

- Generación: **PptxGenJS 4.0.1**, bundle UMD minificado `vendor/pptxgen.min.js`, licencia MIT en `vendor/PPTXGENJS-LICENSE.md`.
- Lectura ZIP: **JSZip 3.10.1**, bundle UMD minificado `vendor/jszip.min.js`, licencia MIT en `vendor/JSZIP-LICENSE.md`.
- Fuentes upstream: [PptxGenJS 4.0.1](https://github.com/gitbrent/PptxGenJS/tree/v4.0.1) y [JSZip 3.10.1](https://github.com/Stuk/jszip/tree/v3.10.1). JSZip ofrece MIT o GPL-3.0-or-later; este módulo usa la alternativa MIT y conserva el texto de licencia.
- Renderizado: `viewer/deck-renderer.js`, una implementación local pequeña para el subconjunto de OOXML producido por PptxGenJS. Se eligió para evitar añadir una biblioteca grande de renderizado PPTX→HTML/canvas y mantener legible la selección concreta de formas/texto/imágenes admitidos.
- Los assets quedan dentro del módulo, con versiones fijas; no se carga CDN ni se usa runtime de Node, Linux o shell en la app.

## Fidelidad y elección del visor

El visor lee el `.pptx` entregado por el handler y dibuja su OOXML localmente; no usa un modelo paralelo. El defecto de texto era de unidades: OOXML expresa `sz="3000"` como 30 pt, que son 381000 EMU. El canvas recibía `381000px` antes de aplicar la escala EMU y WebView limitaba la fuente. Ahora el renderer convierte el tamaño, los límites y los insets a píxeles CSS antes de medir y pintar; resuelve colores sRGB y `schemeClr` desde el tema del archivo, y aplica ajuste de líneas, alineación vertical y rotación básica.

El subconjunto soportado cubre formas rectangulares/elípticas/líneas, rellenos y trazos sólidos, texto sencillo e imágenes incorporadas. `p:graphicFrame` (incluidos gráficos y tablas OOXML) no se dibuja y el visor muestra una advertencia en la diapositiva afectada. El esquema actual de `create_presentation` no declara gráficos; el handler no los crea.

Se mantiene el renderer propio para el subconjunto que genera esta tool: son 18,345 bytes y reutiliza JSZip 3.10.1, ya empaquetado. Un renderer general añadiría una pila de dependencias para mostrar objetos OOXML que la tool actual no genera. No se añadió dependencia ni permiso.

## Contrato con el anfitrión

El manifiesto declara `generatedThumbnail: true`: el permiso indica que el handler entrega sus propios píxeles, no una ruta de imagen del bundle. El handler llama a `artifactDeliverBytes(base64, name, artifactType, title, thumbnailBase64, thumbnailMimeType, deliveryId)` y recibe el id persistido. Cada solicitud crea una identidad de entrega; el anfitrión conserva esa identidad para que reintentar la misma entrega sea idempotente.

La tarjeta decodifica con objetivo 640×360 píxeles y el decodificador actual admite hasta el doble por lado para pantallas de alta densidad. La primera diapositiva se rasteriza a ese máximo de 1280×720; el anfitrión limita el base64 y los bytes recibidos al presupuesto RGBA de esa superficie.
