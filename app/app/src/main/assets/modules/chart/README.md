# Módulo de gráficas

El módulo entrega un documento JSON `application/vnd.jarvys.chart+json`. Chart.js lo presenta tanto en el visor local como en un Canvas PNG que se pasa al anfitrión como miniatura. El visor y el generador usan la misma copia local de Chart.js; no hay CDN ni acceso de red.

## Librería y licencia

- Chart.js **4.5.1**, distribución UMD minificada en `vendor/chart.umd.min.js`.
- Licencia MIT, incluida en `vendor/CHARTJS-LICENSE.md`.
- Paquete npm fijado: `chart.js@4.5.1`.
- Fuente upstream: [Chart.js 4.5.1](https://github.com/chartjs/Chart.js/tree/v4.5.1).

## Contrato con el anfitrión

El handler llama a `Jarvys.artifactDeliverBytes(base64, name, artifactType, title, thumbnailBase64, thumbnailMimeType)`. Espera que el anfitrión devuelva JSON con el id persistido (`id`); ese id aparece en el resultado de la tool. El mismo artefacto JSON contiene la configuración que lee el visor.

La ilustración `viewer/thumbnail.svg` es un recurso de reserva del módulo. La tarjeta de cada artefacto debe usar el PNG dinámico que entrega el handler.
