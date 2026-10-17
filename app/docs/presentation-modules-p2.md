# P2: módulos de gráficas y presentaciones

## Módulos y tools

Los dos módulos están registrados solo por sus manifiestos bajo `assets/modules/`:

| Módulo | Tool declarada | Artifact type | Skill |
|---|---|---|---|
| `com.jarvys.module.chart` | `create_chart` | `application/vnd.jarvys.chart+json` | `jarvys-chart`, desde `skills/chart/SKILL.md` |
| `com.jarvys.module.presentation` | `create_presentation` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | `jarvys-presentation`, desde `skills/presentation/SKILL.md` |

Las tools escriben artefactos con `artifact.deliver`; el manifiesto declara el visor, la ruta de miniatura, el handler y la skill. La carga de skills de módulos y la disponibilidad de sus tools en `allowed-tools` se resuelven genéricamente desde esos manifiestos. Las tools nuevas son de escritura y reciben `Preguntar` como política inicial en Conectores.

La miniatura rasterizada queda en el almacén de archivos existente y su `file_refs.id` se asocia con `artifacts.thumbnail_ref` de Room v2. No se cambió el esquema. La tarjeta muestra ese archivo restaurado desde SQLite; abrirla usa el visor declarado. Guardar y compartir siguen usando el flujo genérico de archivos del chat.

## Implementación y librerías

- **Gráficas:** Chart.js **4.5.1**, bundle UMD local minificado, sin CDN. El handler crea un gráfico sin animación, serializa sus datos como artefacto JSON y captura el canvas como PNG.
- **PPTX:** PptxGenJS **4.0.1** y JSZip **3.10.1**, ambos bundles de navegador empaquetados localmente y minificados. El handler los ejecuta dentro del WebView aislado, genera el archivo PPTX y entrega los bytes junto con una miniatura PNG.
- **Visor PPTX:** `viewer/deck-renderer.js`, renderer local propio de **18,345 bytes**. Recorre el orden definido en OOXML y dibuja formas básicas, texto e imágenes incorporadas con canvas. Resuelve colores sRGB y colores base del tema; no reproduce tablas, grupos, transiciones, gráficos OOXML ni todas las reglas tipográficas de PowerPoint. La primera diapositiva usa el mismo renderer para producir la miniatura.
- PptxGenJS y JSZip se distribuyen con sus licencias MIT junto a los bundles; Chart.js conserva su licencia en el módulo. Los README de cada módulo apuntan a las versiones upstream fijadas.

La app no descarga bibliotecas ni usa runtime Node, Linux, Python o shell. No se añadieron tools de módulos a tareas programadas; continúan diferidas.

## Visor: causa corregida y decisión

El Node handler genera el texto y los rellenos dentro del XML real de cada diapositiva; la pérdida ocurría en el visor al asignar tamaños OOXML en EMU directamente como `font` CSS del canvas (por ejemplo, `sz="3000"` se convertía en `381000px`). WebView limitaba esa fuente antes de transformar el canvas. El renderer propio ahora pasa texto y medidas a píxeles CSS, resuelve sRGB y colores de tema desde el `.pptx`, y dibuja desde los bytes entregados. Las formas con texto de una línea admiten ajuste, alineación vertical y giro básico.

La tool actual no declara ni genera gráficos OOXML. El renderer no interpreta `p:graphicFrame`; si una diapositiva incluye un gráfico, una tabla u otro objeto integrado, muestra una advertencia visible en el visor.

La decisión es mantener el renderer propio para el subconjunto que produce la tool y no añadir otra biblioteca de visualización general. No se modificó el permiso aislado del WebView. La miniatura continúa renderizando la primera diapositiva con el mismo renderer.

## Peso

Medición sobre `app-full-debug-unsigned.apk` creado con `:app:assembleFullDebug`:

| Assets nuevos | Bytes sin comprimir | Bytes comprimidos en APK |
|---|---:|---:|
| Gráficas | 220,620 | 88,591 |
| Presentaciones | 434,655 | 165,949 |
| **Total añadido** | **655,275 (0.655275 MB)** | **254,540 (0.254540 MB)** |

El APK fullDebug resultante mide **44,968,068 bytes (44.968 MB)**. El delta comprimido de la tabla suma las entradas `assets/modules/chart/` y `assets/modules/presentation/` directamente desde el ZIP del APK. Los bundles JS fijados representan 577,162 bytes del tamaño bruto total.

## Cobertura y límites de prueba

- `ModuleBundleIntegrationTest` carga los manifiestos reales, comprueba herramientas, visores, miniaturas, skills, la aparición genérica en Conectores y la política inicial `Preguntar`. Ejecuta ambos handlers con un motor JavaScript Node instalado solo para el test: PPTX usa PptxGenJS, JSZip y el renderer OOXML empaquetados; el test confirma el ZIP, `[Content_Types].xml`, `ppt/slides/slide1.xml` y texto de la primera diapositiva. Chart.js se carga y se comprueba como bundle, pero el test sustituye el contexto canvas porque Node no aporta un canvas de navegador.
- `ModuleCapabilityBridgeTest` cubre la entrega binaria, la declaración de artefacto y el rechazo de una miniatura que no es imagen.
- `DeliveredArtifactTest.moduleArtifactThumbnailAndBytesRestoreFromTheExistingRoomReference` verifica persistencia y restauración del archivo, miniatura y referencia Room v2.
- Robolectric aquí no ejecuta el JavaScript real de una página WebView. La política de aislamiento del WebView conserva sus tests existentes; la generación/visor con Canvas real se debe confirmar en el móvil siguiendo la lista siguiente.

## Prueba manual en móvil

1. En un chat nuevo, pide una gráfica de barras con al menos dos categorías. Confirma que la tool devuelve el id real, la tarjeta muestra la miniatura, se abre el visor, y Guardar/Compartir producen un archivo legible.
2. Pide una presentación con dos diapositivas, texto y una forma. Confirma que el `.pptx` abre en PowerPoint o equivalente, la tarjeta muestra la primera diapositiva, el visor permite avanzar/retroceder y Guardar/Compartir conservan el `.pptx`.
3. En **Conectores**, cambia cada módulo por separado a **Permitir**, **Preguntar** y **Denegar**; confirma que las tools respetan cada política y que ninguna tool aparece para tareas programadas.

Riesgos restantes: el visor PPTX es aproximado; el test Node no sustituye la validación de Chart.js sobre el Canvas del WebView del móvil; el camino de bytes conserva el límite de entrega existente de conectores (8 MiB por archivo), sin introducir otro límite para P2.
