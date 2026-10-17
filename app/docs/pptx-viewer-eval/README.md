# Evaluación local de visores PPTX

**Decisión: adoptar PptxGlimpse 5.3.1 como visor de pantalla completa y renderer de miniaturas.** Su bundle Vite de **1,197,989 B / 287,995 B gzip** dibuja gráficos, tablas y temas bajo la CSP actual. La falta de etiquetas de valor y la omisión de la imagen SVG de prueba quedan como límites conocidos. La validación inicial eligió PptxGlimpse para la vista principal y dejó `deck-renderer.js` en la miniatura; la reproducción posterior en Android confirmó que PptxGlimpse también funciona para el PNG de la tarjeta, así que el segundo renderer se retiró. El visor carga los bytes PPTX mediante la URL local permitida por el puente, usa controles propios y el harness no observó peticiones externas. La evidencia del WebView real está en [presentation-triage.md](../chat-db/presentation-triage.md).

## Paquetes empaquetados y licencias

El bundle incluye estos 17 paquetes. Todos declaran MIT; el archivo [`THIRD-PARTY-NOTICES.md`](../../app/src/main/assets/modules/presentation/vendor/THIRD-PARTY-NOTICES.md) conserva sus avisos íntegros.

| Paquete | Versión resuelta | Licencia |
|---|---:|---|
| `pptx-glimpse` | 5.3.1 | MIT |
| `@pptx-glimpse/document` | 0.16.0 | MIT |
| `@pptx-glimpse/editor` | 0.6.0 | MIT |
| `fflate` | 0.8.3 | MIT |
| `@xmldom/xmldom` | 0.9.12 | MIT |
| `rtf.js` | 3.0.9 | MIT |
| `fast-xml-parser` | 5.11.2 | MIT |
| `is-unsafe` | 2.0.2 | MIT |
| `xml-naming` | 0.3.0 | MIT |
| `fast-xml-builder` | 1.3.1 | MIT |
| `@nodable/entities` | 3.1.0 | MIT |
| `anynum` | 1.0.1 | MIT |
| `strnum` | 2.5.1 | MIT |
| `path-expression-matcher` | 1.6.2 | MIT |
| `opentype.js` | 1.3.4 | MIT |
| `tiny-inflate` | 1.0.3 | MIT |
| `string.prototype.codepointat` | 0.2.1 | MIT |

El inventario se obtuvo con [`audit-bundle-packages.cjs`](audit-bundle-packages.cjs), que lee las 21 directivas `//#region`, deriva los paquetes de sus rutas, barre imports desde los módulos incluidos y recoge los IDs `.pnpm` anidados y las dependencias runtime incluidas de opentype.js. Los IDs identifican `@xmldom/xmldom@0.9.12` y `rtf.js@3.0.9`; EMFJS y WMFJS son módulos distribuidos por `rtf.js`, no paquetes aparte. `is-unsafe@2.0.2` está inlined en el bloque del parser, y `xml-naming@0.3.0` en el builder, aunque no tengan regiones propias. `fflate` se importa y utiliza desde el módulo de documento incluido. El bloque precompilado de opentype.js contiene su cargador de fuentes, el inflater WOFF de `tiny-inflate@1.0.3` y el polyfill `string.prototype.codepointat@0.2.1`; este último se verifica por su guardia `String.prototype.codePointAt`. La metadata y licencia de cada uno se comprobó en el árbol resuelto del build; para los dos IDs `.pnpm`, se inspeccionaron tarballs npm fijados a esas versiones. El tarball de `@nodable/entities` omite `LICENSE`; se verificó el aviso en el `gitHead` publicado por npm. No se encontró `@resvg/resvg-wasm` en el bundle, aunque `pptx-glimpse` lo declara como dependencia MPL-2.0.

## Método

- Cinco `.pptx` creados por el `createPresentation` real de `logic/tools.js`: título/texto, gráfico de barras, tabla, imagen/forma y un deck de tres diapositivas con fondos claros y oscuro. El generador de fixtures solo sustituye la miniatura para no depender del visor durante la preparación de muestras.
- 35 renderizados comparativos: las cuatro primeras muestras y cada una de las tres diapositivas del caso de tema para cada renderer. La validación posterior del visor final añade un sexto fixture con un PNG real pasado a `create_presentation` como `data:image/png;base64,...`.
- Chrome headless 155.0.8059.79, Puppeteer Core, viewport **360×800 CSS px**, `deviceScaleFactor: 3`; cada PNG mide 1080×2400.
- La página de prueba usa una CSP equivalente a la del módulo (`script-src 'self' 'unsafe-inline'`, `connect-src 'self'`, `worker-src 'none'`; sin `unsafe-eval` ni CDN). Los paquetes y dependencias se sirvieron desde archivos locales extraídos de npm. El harness recoge `console.warn`/`console.error` y errores de página; la columna de consola indica los logs observados, y las fallas de carga/render se enumeran aparte.
- PptxGlimpse se compiló desde su entrada de navegador con Vite 8.3.4 en Node 24.14.0 (el paquete declara Node ≥22). `bundle-glimpse.cjs` produjo un único bundle de **1,197,989 B / 287,995 B gzip**, probado con el mismo harness y CSP. El bundle contiene `new XMLHttpRequest()` en la función de carga de fuentes de opentype.js, pero no hay llamadores de esa función en el camino `convertPptxToSvg` ejercitado y permanece inalcanzable ahí. La página usa `fetch` para leer la URL local del artefacto; `connect-src 'self'` y `blockNetworkLoads` bloquean conexiones externas. No se ejecutan `eval`, workers ni WASM; `@resvg/resvg-wasm` no está incluido en el bundle.
- El tiempo es `performance.now()` desde el inicio de la carga de la muestra hasta terminar el primer render. La mediana corresponde a las siete ejecuciones por renderer, incluidas las diapositivas de tema.

## Comparación medida

| Renderer | Licencia · versión · última publicación | Peso medido | Dependencias y uso offline/CSP | Render, contenido y decisión |
|---|---|---|---|---|
| Renderer anterior (`deck-renderer.js`, evaluación histórica) | Código del módulo; no añade licencia de terceros | 18,345 B; JSZip ya está en el módulo | JS local; no requiere red, workers ni `eval` | **6/7** renders; mediana **41.5 ms**; 0 logs de consola. Texto y temas claro/oscuro aparecen, pero las cajas de texto dejan demasiado espacio. Gráficos y tablas quedan vacíos con aviso. La muestra de imagen SVG falló al decodificarse en el harness. Fue retirado tras la validación Android; las capturas solo documentan la comparación. [texto](captures/deck-renderer-title-text-slide1.png) · [gráfico](captures/deck-renderer-bar-chart-slide1.png) · [tabla](captures/deck-renderer-table-slide1.png) · [imagen/forma](captures/deck-renderer-image-shape-slide1.png) · [tema oscuro](captures/deck-renderer-theme-light-dark-slide2.png) |
| [PptxViewJS](https://www.npmjs.com/package/pptxviewjs) | MIT · 1.1.9 · 2026-03-22 | UMD minificado 621,122 B / **151,127 B gzip**. Requiere Chart.js 4.5.1: 208,522 / **70,658 B gzip**. JSZip 3.10.1 ya existe: 97,630 / 28,470 B gzip. Incremento de assets si se vendorizara: 829,644 B / **221,785 B gzip**. | Peer deps: Chart.js `>=4.4.1`, JSZip `>=3.10.1`. Carga local sin red; no se detectaron `eval`, `new Function`, WASM ni workers. | **7/7**; mediana **62.6 ms**, gráfico **222.0 ms**; 0 logs de consola. Texto, tabla, imágenes, formas y tres colores de tema aparecen. **En el gráfico se ven categorías y cifras, pero no las barras**. No gana fidelidad. [texto](captures/pptxviewjs-title-text-slide1.png) · [gráfico](captures/pptxviewjs-bar-chart-slide1.png) · [tabla](captures/pptxviewjs-table-slide1.png) · [imagen/forma](captures/pptxviewjs-image-shape-slide1.png) · [tema claro](captures/pptxviewjs-theme-light-dark-slide1.png) · [tema oscuro](captures/pptxviewjs-theme-light-dark-slide2.png) |
| [pptx-preview](https://www.npmjs.com/package/pptx-preview) | npm indica ISC · 1.0.7 · 2025-10-17. El README restringe publicar el código en internet o modificarlo/re-marcarlo; no se incluyó archivo LICENSE en el paquete. No es una base publicable clara para esta app. | UMD 1,362,014 B / **421,998 B gzip** | UMD con JSZip, Lodash, tslib, uuid y ECharts. El bundle contiene dos usos de `new Function`; las muestras correctas pasaron bajo esta CSP, aunque esos caminos dinámicos no son admisibles si se ejecutan. | **4/7**; mediana **143.6 ms**; 0 logs de consola. Texto, tabla e imagen aparecen; dibuja barras, pero reemplaza las categorías por `0, 1, 2` y no etiqueta sus valores. El deck de temas reporta 0 diapositivas y falla. Queda descartado por licencia ambigua, etiquetas y temas. [texto](captures/pptx-preview-title-text-slide1.png) · [gráfico](captures/pptx-preview-bar-chart-slide1.png) · [tabla](captures/pptx-preview-table-slide1.png) · [tema/error](captures/pptx-preview-theme-light-dark-slide2.png) |
| [pptx-glimpse](https://www.npmjs.com/package/pptx-glimpse) | MIT · 5.3.1 · 2026-09-21 | Bundle Vite: **1,197,989 B / 287,995 B gzip** | Sus paquetes empaquetados y versiones están en la tabla anterior. `new XMLHttpRequest()` está presente en la función de carga de fuentes de opentype.js, sin llamadores desde el camino `convertPptxToSvg` evaluado; permanece inalcanzable en ese camino. La página usa `fetch` para leer el artefacto local de la lista blanca; no se observaron solicitudes externas bajo `connect-src 'self'` y `blockNetworkLoads`. No se ejecutan `eval`, workers ni WASM. Node 22+ es requisito de build, no de ejecución. | **7/7**; mediana **105.9 ms**, gráfico **172.3 ms**; 0 logs de consola. Texto, gráficos, tablas y temas se dibujan. Las barras y categorías aparecen, pero faltan los valores explícitos aunque el PPTX los incluye. La forma aparece; la imagen SVG se omite. **Adoptado** como visor final porque evita dejar gráficos y tablas vacíos. La validación del HTML/JS final capturó **8/8** diapositivas, incluido un PNG incrustado, más una captura adicional de controles en tema claro. [texto final](captures/final-viewer-title-text-slide1.png) · [gráfico final](captures/final-viewer-bar-chart-slide1.png) · [tabla final](captures/final-viewer-table-slide1.png) · [SVG omitido](captures/final-viewer-image-shape-slide1.png) · [PNG data URL](captures/final-viewer-image-png-data-url-slide1.png) · [tema oscuro final](captures/final-viewer-theme-light-dark-slide2.png) · [controles claros](captures/final-viewer-theme-light-dark-light-slide2.png) |
| [@silurus/ooxml](https://www.npmjs.com/package/@silurus/ooxml) | MIT · 0.88.0 · 2026-09-21 | Paquete unpacked 16,783,172 B / tarball npm 5,561,132 B. No publica bundle minificado; el grafo JS+WASM medido ocupa 16,071,949 B / **5,399,505 B gzip**. `pptx_parser_bg.wasm`: 1,826,626 B. | Sin dependencias npm, pero incluye parsers WASM y usa Worker incluso en modo `main`. La CSP del WebView desactiva workers y no habilita `wasm-unsafe-eval`. | **0/7**; mediana **88.6 ms**; 0 logs de consola; falla cada muestra con `Worker error`. Incompatible con el aislamiento actual y demasiado grande. [error del gráfico](captures/silurus-bar-chart-slide1.png) |

## Resultado visual por requisito

| Requisito | Evidencia medida |
|---|---|
| Título y texto | PptxViewJS y pptx-preview muestran el título, párrafo y viñetas; el renderer anterior deja cajas con espacio excesivo. [PptxGlimpse final](captures/final-viewer-title-text-slide1.png) · [renderer anterior](captures/deck-renderer-title-text-slide1.png) |
| Gráfico con categorías, valores y barras | PptxViewJS muestra `North/South/West` y `42/57/39`, pero omite las barras. PptxGlimpse dibuja las barras y categorías, pero no las etiquetas de valor; el visor propio deja el gráfico vacío. [PptxViewJS](captures/pptxviewjs-bar-chart-slide1.png) · [PptxGlimpse final](captures/final-viewer-bar-chart-slide1.png) · [visor anterior](captures/deck-renderer-bar-chart-slide1.png) |
| Tabla | PptxViewJS, PptxGlimpse y pptx-preview muestran encabezados, filas y valores; el visor propio dejaba el área vacía. [PptxViewJS](captures/pptxviewjs-table-slide1.png) · [PptxGlimpse final](captures/final-viewer-table-slide1.png) · [visor anterior](captures/deck-renderer-table-slide1.png) |
| Imagen y forma | PptxViewJS y pptx-preview muestran ambas; PptxGlimpse muestra la forma pero omite la imagen SVG sin error. La muestra PNG enviada como data URL sí aparece en el visor final. [SVG final](captures/final-viewer-image-shape-slide1.png) · [PNG data URL final](captures/final-viewer-image-png-data-url-slide1.png) · [PptxViewJS](captures/pptxviewjs-image-shape-slide1.png) |
| Temas claros/oscuros | PptxViewJS y PptxGlimpse muestran las tres diapositivas y sus fondos contrastan. pptx-preview no devuelve diapositivas para este deck; Silurus falla bajo la CSP. [PptxGlimpse claro](captures/final-viewer-theme-light-dark-slide1.png) · [PptxGlimpse oscuro](captures/final-viewer-theme-light-dark-slide2.png) · [PptxGlimpse tercer tema](captures/final-viewer-theme-light-dark-slide3.png) |
| Ajuste a 360×800 | PptxViewJS y pptx-preview dibujan 300×150 CSS px; PptxGlimpse final y el renderer anterior ajustan a 336×189 CSS px dentro del stage de 360 px. Los PNG son 1080×2400 a escala 3. [visor final](captures/final-viewer-title-text-slide1.png) |

Las 35 capturas comparativas están en `captures/`; sus estados, tiempos y métricas CSS están en `results.jsonl`. El visor final tiene nueve capturas: las ocho diapositivas de prueba y una captura adicional en tema claro con Anterior y Siguiente habilitados. Se tomaron con el HTML y JS de producción, su barra de controles, el mismo encabezado CSP y los bytes de cada PPTX entregados por la ruta local del harness. Cada archivo se leyó una vez, todos los renders tuvieron una imagen visible y el harness no observó peticiones HTTP externas. Los límites SVG/etiquetas siguen anotados arriba. Los estados y dimensiones medidos están en `final-viewer-results.json`.

## Reproducción

El harness carga los `.pptx` generados por `generate-fixtures.cjs`, sirve los candidatos desde `%LOCALAPPDATA%\Temp\jarvys-pptx-viewer-eval` y ejecuta Chrome instalado mediante Puppeteer Core. No se copian paquetes candidatos al módulo de producción. Para repetir la prueba del build de navegador, instala Vite y PptxGlimpse en el directorio temporal indicado y ejecuta el script antes del harness.

```powershell
$evalRoot = "$env:LOCALAPPDATA\Temp\jarvys-pptx-viewer-eval"
New-Item -ItemType Directory -Force -Path $evalRoot | Out-Null
npm pack pptxviewjs@1.1.9 --pack-destination $evalRoot
npm pack pptx-preview@1.0.7 --pack-destination $evalRoot
npm pack pptx-glimpse@5.3.1 --pack-destination $evalRoot
npm pack @silurus/ooxml@0.88.0 --pack-destination $evalRoot
foreach ($package in @("pptxviewjs-1.1.9", "pptx-preview-1.0.7", "pptx-glimpse-5.3.1", "silurus-ooxml-0.88.0")) {
  New-Item -ItemType Directory -Force -Path (Join-Path $evalRoot $package) | Out-Null
  tar -xzf (Join-Path $evalRoot "$package.tgz") -C (Join-Path $evalRoot $package)
}
node --experimental-vm-modules app/docs/pptx-viewer-eval/generate-fixtures.cjs
npm install --prefix $env:LOCALAPPDATA\Temp\jarvys-pptx-eval-runner --no-save puppeteer-core
npm install --prefix $env:LOCALAPPDATA\Temp\jarvys-pptx-glimpse-browser-build --no-save vite pptx-glimpse@5.3.1
node app/docs/pptx-viewer-eval/bundle-glimpse.cjs
node app/docs/pptx-viewer-eval/eval-server.cjs C:\Projects\Jarvys-wt\pptx2 $env:LOCALAPPDATA\Temp\jarvys-pptx-viewer-eval
node app/docs/pptx-viewer-eval/run-puppeteer.cjs
node app/docs/pptx-viewer-eval/run-final-viewer.cjs
```

La publicación del skill PPTX de Anthropic declara una licencia propietaria. La guía del módulo solo recoge principios generales parafraseados; no reutiliza texto, código, ejemplos ni paletas del material. Fuentes: [documentación de PptxViewJS](https://github.com/gptsci/pptxviewjs), [soporte de pptx-glimpse](https://glimpse.pptx.app/docs/feature-support) y [uso en navegador](https://glimpse.pptx.app/docs/browser), [licencia de `pptx-preview` publicada en npm](https://www.npmjs.com/package/pptx-preview).

## Composiciones del esquema libre (2026-10-17)

`generate-fixtures.cjs` genera cuatro PPTX mediante `logic/tools.js#createPresentation`; `composition-measurement-reports.json` guarda los informes que devolvió el handler. `run-final-viewer.cjs` abre cada archivo en el HTML y JavaScript del visor de producción bajo la CSP usada por el módulo. Las cuatro cargas pintaron su diapositiva, cada archivo se leyó una vez y no hubo peticiones externas.

- [Portada con runs y formas decorativas](captures/final-viewer-free-cover-slide1.png)
- [Dos columnas con imagen PNG data URL](captures/final-viewer-free-columns-slide1.png)
- [Gráfico nativo con tres insights](captures/final-viewer-free-chart-insights-slide1.png)
- [Título largo, subtítulo, texto y tabla](captures/final-viewer-free-table-long-title-slide1.png)

Chrome headless 155.0.8059.79, viewport 360×800 CSS px y `deviceScaleFactor: 3`. `composition-viewer-results.json` registra los cuatro resultados. El contrato Node también prueba un título de dos líneas que se solapa y desborda, y una versión corregida que no lo hace; ambos casos entregan un PPTX.
