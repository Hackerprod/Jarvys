# P2: módulos de gráficas y presentaciones

## Módulos y tools

Los dos módulos se registran desde sus manifiestos bajo `app/src/main/assets/modules/`:

| Módulo | Tool declarada | Artifact type | Skill |
|---|---|---|---|
| `com.jarvys.module.chart` | `create_chart` | `application/vnd.jarvys.chart+json` | `jarvys-chart`, desde `skills/chart/SKILL.md` |
| `com.jarvys.module.presentation` | `create_presentation` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | `jarvys-presentation`, desde `skills/presentation/SKILL.md` |

Las tools entregan artefactos con `artifact.deliver`. La carga de skills y la disponibilidad en `allowed-tools` se resuelven genéricamente desde los manifiestos. La miniatura PNG usa el almacén existente y la referencia `artifacts.thumbnail_ref` de Room v2; el esquema de Room no cambió. La tarjeta, el visor y Guardar/Compartir siguen los flujos genéricos.

## Composición de presentaciones

`create_presentation` recibe `{ title, theme?, slides }`. Cada diapositiva contiene opcionalmente `background` y `notes`, más un `elements` ordenado. Cada elemento tiene coordenadas explícitas `x`, `y`, `w` y `h` en pulgadas sobre `LAYOUT_WIDE` (13.333 × 7.5 in). Los elementos admitidos son `text`, `shape`, `image`, `chart` y `table`. `manifest.json` enumera propiedades y tipos aceptados, y `skills/presentation/SKILL.md` explica cómo componer distintos diseños.

El handler no crea títulos, huecos, tamaños por rol ni ubicaciones automáticas. Las propiedades se validan con errores identificados por diapositiva, índice y tipo de elemento. La validación rechaza propiedades desconocidas, valores no finitos, cajas negativas o fuera del lienzo, datos de gráfico incompatibles, imágenes que no se decodifican y tablas mal formadas. Una caja de texto cuyo contenido se desborda o dos elementos que se cruzan producen mediciones, no un error de generación.

Tras construir el PPTX, el handler mide el texto con `canvas.measureText` en el WebView real, ajustando líneas al ancho y a la fuente declarada. El contenido JSON de la respuesta al modelo incluye el id del artefacto y un informe por diapositiva y elemento: caja pedida, altura necesaria y disponible para texto, desbordes, intersecciones, elementos fuera del lienzo, menor tamaño de fuente y contraste cuando los colores se conocen. El agente usa estos hechos para corregir la siguiente versión. La miniatura sigue mostrando la primera diapositiva; el host entrega texto y no admite que un módulo devuelva imágenes adicionales al modelo sin cambiar el contrato genérico, así que no se añadieron miniaturas adicionales.

Si no se proporciona `theme`, PptxGenJS conserva sus valores neutrales. Si se proporciona, el handler aplica `fonts.headFontFace`, `fonts.bodyFontFace` y el fondo global; `theme.colors` pasa a ser la paleta de los gráficos. PptxGenJS 4.0.1 no expone reemplazo simple del esquema de colores OOXML, así que el agente coloca los hexadecimales que eligió en cada elemento de texto o forma. El módulo no impone colores. Los data URLs SVG se informan como no compatibles con el visor/miniatura.

## Runtime y artefactos

- **Gráficas:** Chart.js **4.5.1**, bundle UMD local minificado, sin CDN. El handler produce un artefacto JSON y captura el canvas como PNG.
- **PPTX:** PptxGenJS **4.0.1** y JSZip **3.10.1**, bundles de navegador locales y minificados. El WebView aislado construye el `.pptx`; PptxGlimpse **5.3.1** renderiza la miniatura y el visor de pantalla completa.
- **Seguridad:** no se descargan bibliotecas ni se relaja la CSP. El aislamiento del WebView se mantiene; el anfitrión Kotlin no contiene lógica específica de presentaciones.

Los paquetes conservan sus licencias MIT y avisos junto a los bundles. Los tamaños exactos por archivo y total están en [ASSET-SIZES.md](../app/src/main/assets/modules/presentation/ASSET-SIZES.md).

## Cobertura y límites

- `ModuleBundleIntegrationTest` ejecuta `module-handler-contract.cjs`, que usa los bundles reales, descomprime el PPTX y verifica posiciones explícitas, texto/runs, formas, imágenes, gráficos, tablas, notas, validación, mediciones y el contrato de artefacto + miniatura + informe. Node no tiene canvas de navegador; su doble de `measureText` se documenta dentro del contrato y solo comprueba la estructura. La medición real se verifica con el handler offscreen en Android WebView.
- El harness de `docs/pptx-viewer-eval/` genera decks distintos con el handler de producción y abre los PPTX en el HTML/JavaScript del visor bajo la CSP de producción. Las capturas de las composiciones y el informe de evaluación quedan en `docs/pptx-viewer-eval/captures/`.
- `ModuleCapabilityBridgeTest` cubre la entrega binaria y el rechazo de miniaturas que no sean imágenes. `DeliveredArtifactTest.moduleArtifactThumbnailAndBytesRestoreFromTheExistingRoomReference` verifica restauración de bytes, miniatura y referencia Room v2.
- El visor/miniatura es un renderizador aproximado de PowerPoint; el resultado visual puede variar con las fuentes y el software de presentación. Las cajas y las métricas informadas permiten al agente detectar problemas antes de entregar otra versión.

No se añadieron tools de módulos a tareas programadas; continúan diferidas.
