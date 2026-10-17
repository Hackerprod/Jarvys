# Módulo de gráficas

El módulo entrega un documento JSON `application/vnd.jarvys.chart+json`. Chart.js lo presenta tanto en el visor local como en un Canvas PNG que se pasa al anfitrión como miniatura. El visor y el generador usan la misma copia local de Chart.js; no hay CDN ni acceso de red.

`create_chart` admite `bar`, `bubble`, `doughnut`, `line`, `pie`, `polarArea`, `radar` y `scatter`. Si `chartType` no está en esa lista, el handler devuelve `{ success: false, content }` con el campo y las opciones válidas. Si la miniatura rebasa el presupuesto que admite la tarjeta, también devuelve un resultado corregible e indica reducir datos o complejidad visual.

## Librería y licencia

- Chart.js **4.5.1**, distribución UMD minificada en `vendor/chart.umd.min.js`.
- Licencia MIT, incluida en `vendor/CHARTJS-LICENSE.md`.
- Paquete npm fijado: `chart.js@4.5.1`.
- Fuente upstream: [Chart.js 4.5.1](https://github.com/chartjs/Chart.js/tree/v4.5.1).

## Contrato con el anfitrión

El manifiesto declara `generatedThumbnail: true`: el permiso indica que el handler entrega sus propios píxeles, no una ruta de imagen del bundle. El handler llama a `artifactDeliverBytes(base64, name, artifactType, title, thumbnailBase64, thumbnailMimeType, deliveryId)` y recibe el id persistido. Cada solicitud crea una identidad de entrega; el anfitrión conserva esa identidad para que reintentar la misma entrega sea idempotente.

La tarjeta decodifica con objetivo 640×360 píxeles y el decodificador actual admite hasta el doble por lado para pantallas de alta densidad. La miniatura se rasteriza a ese máximo de 1280×720 con `devicePixelRatio: 1`; el anfitrión limita el base64 y los bytes recibidos al presupuesto RGBA de esa superficie.

## Convención de errores de handlers

Devuelve `{ success: false, content: "..." }` para errores de entrada que el agente pueda corregir e indica el campo que debe cambiar. Reserva las excepciones para fallos internos o de ejecución: el anfitrión no entrega su texto al agente.
