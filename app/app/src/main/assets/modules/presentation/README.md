# Módulo de presentaciones

`create_presentation` produce un `.pptx` nativo con PptxGenJS. Además de texto, formas e imágenes incorporadas, admite gráficos de barras y tablas editables de PowerPoint. El handler entrega los bytes del archivo mediante `artifact.deliver` y conserva la miniatura PNG de la primera diapositiva que aparece en la tarjeta del chat. La miniatura usa PptxGlimpse, el mismo renderer del visor.

## Defaults de diseño

- Encabezados Cambria y cuerpo Arial, con tamaño de título destacado y texto de apoyo legible.
- Fondo casi blanco por defecto; el color del texto se elige por luminancia para conservar contraste en fondos claros y oscuros.
- Paleta consistente para series de gráficos y colores de gráfico más luminosos sobre temas oscuros.
- Gráficos nativos con etiquetas de categorías y valores, título cuando se aporta y sin leyenda redundante en una serie.
- Tablas con encabezado oscuro, texto claro y filas alternadas claras con texto oscuro.
- Alineación, márgenes y espacio entre elementos definidos en el módulo.

La herramienta espera que el agente formule títulos que expresen la conclusión y mantenga una idea central por diapositiva. El manifiesto y `skills/presentation/SKILL.md` describen esas pautas junto con los tipos de contenido admitidos.

## Visor

El visor visible usa PptxGlimpse 5.3.1. Lee los bytes del `.pptx` real entregado por el puente con lista blanca existente, convierte las diapositivas a SVG localmente y permite recorrerlas con controles propios. El bundle se sirve desde `vendor/pptx-glimpse.bundle.js`; no usa CDN, workers, WASM ni `eval` en el camino `convertPptxToSvg`. La CSP del visor no se modificó.

El bundle incluye `new XMLHttpRequest()` dentro de la función de carga de fuentes de opentype.js, pero esa función no tiene llamadores en el camino `convertPptxToSvg` ejercitado y permanece inalcanzable en la ruta usada. La página carga el artefacto con `fetch` desde la URL local incluida en la lista blanca; `blockNetworkLoads` y `connect-src 'self'` impiden las conexiones externas. El handler vuelve a usar `convertPptxToSvg` sobre los bytes recién generados y pide solo la diapositiva 1 (`slides: [1]`), decodifica su SVG en canvas y entrega el PNG con el contrato actual.

La elección mejora el uso real frente al render actual: en la evaluación medida, PptxGlimpse dibujó gráficos, tablas y temas en las siete ejecuciones de diapositivas, mientras el visor anterior dejaba gráficos y tablas vacíos. Se conservan dos límites observados: no dibuja las etiquetas de valor explícitas de los gráficos y omite la imagen SVG de la muestra. La tabla completa de resultados y capturas está en [`app/docs/pptx-viewer-eval/README.md`](../../../../../../docs/pptx-viewer-eval/README.md).

## Dependencias incluidas

| Componente | Versión | Uso | Licencia |
|---|---:|---|---|
| PptxGenJS | 4.0.1 | Generación del archivo PowerPoint | MIT, `vendor/PPTXGENJS-LICENSE.md` |
| JSZip | 3.10.1 | Archivo ZIP usado para generar el PowerPoint con PptxGenJS | MIT, `vendor/JSZIP-LICENSE.md` |
| pptx-glimpse | 5.3.1 | Render del visor y de la miniatura a SVG | MIT, `vendor/PPTX-GLIMPSE-LICENSE.md` |
| `@pptx-glimpse/document` | 0.16.0 | Código de documento del renderer | MIT |
| `@pptx-glimpse/editor` | 0.6.0 | Código de edición del renderer | MIT |
| `fflate` | 0.8.3 | Compresión y descompresión ZIP incluida en chunks | MIT |
| `@xmldom/xmldom` | 0.9.12 | DOM XML incluido en un chunk precompilado | MIT |
| `rtf.js` | 3.0.9 | Módulos EMFJS y WMFJS incluidos en un chunk | MIT |
| `fast-xml-parser` | 5.11.2 | Lectura XML del PPTX | MIT |
| `is-unsafe` | 2.0.2 | Comprobaciones de seguridad XML inlined por el parser | MIT |
| `xml-naming` | 0.3.0 | Validación de nombres XML inlined por el builder | MIT |
| `fast-xml-builder` | 1.3.1 | Serialización XML | MIT |
| `@nodable/entities` | 3.1.0 | Decodificación de entidades XML | MIT |
| `anynum` | 1.0.1 | Lectura de valores numéricos XML | MIT |
| `strnum` | 2.5.1 | Lectura de valores numéricos XML | MIT |
| `path-expression-matcher` | 1.6.2 | Expresiones de selección XML | MIT |
| `opentype.js` | 1.3.4 | Métricas y lectura de fuentes | MIT |
| `tiny-inflate` | 1.0.3 | Descompresión de tablas WOFF dentro de opentype.js | MIT |
| `string.prototype.codepointat` | 0.2.1 | Polyfill Unicode dentro del código de opentype.js | MIT |

Los 17 paquetes del bundle son MIT; sus textos íntegros de licencia y avisos de copyright están en [`vendor/THIRD-PARTY-NOTICES.md`](vendor/THIRD-PARTY-NOTICES.md). `is-unsafe` está inlined en el código del parser y `xml-naming` en el builder. `tiny-inflate` y `string.prototype.codepointat` son dependencias de opentype.js cuyos códigos de descompresión WOFF y polyfill están incluidos. `@resvg/resvg-wasm` 2.6.2 aparece como dependencia declarada por PptxGlimpse y usa MPL-2.0, pero no está en el bundle comprobado: el código ni el WASM del paquete se incluyen.

Las versiones y licencias se verificaron en los `package.json` y archivos de licencia del build. `@xmldom/xmldom` y `rtf.js` están embebidos en chunks precompilados; para ellos se verificaron los metadatos y avisos de los tarballs npm en las versiones exactas indicadas por sus IDs internos. El tarball de `@nodable/entities` no contiene `LICENSE`; su aviso se leyó en el commit `gitHead` publicado por npm. Los únicos runtime dependencies de opentype.js son `tiny-inflate` y `string.prototype.codepointat`; se comprobaron sus versiones, licencias y código incluido en el chunk. PptxGlimpse se compiló con Vite 8.3.4 y su runtime no se descarga en la aplicación. El bundle mide **1,197,989 B (287,995 B gzip)**.

Para regenerar el bundle con `app/docs/pptx-viewer-eval/bundle-glimpse.cjs`, instala `pptx-glimpse@5.3.1` y Vite 8.3.4 en el directorio de build temporal y ejecuta:

```powershell
$buildRoot = "$env:LOCALAPPDATA\Temp\jarvys-pptx-glimpse-browser-build"
$packageRoot = "$env:LOCALAPPDATA\Temp\jarvys-pptx-viewer-eval\pptx-glimpse-5.3.1\package"
node app/docs/pptx-viewer-eval/bundle-glimpse.cjs $buildRoot $packageRoot
Copy-Item "$packageRoot\dist\browser.js" "app/app/src/main/assets/modules/presentation/vendor/pptx-glimpse.bundle.js" -Force
```

El script normaliza los comentarios `//#region` a rutas `node_modules/...` para que el bundle no publique la ruta temporal absoluta de la máquina de build. Los scripts y capturas de evaluación quedan en `app/docs/pptx-viewer-eval/`.

## Reglas de diseño y licencia de la fuente

Las pautas del skill local resumen y parafrasean recomendaciones generales consultadas en el [skill PPTX de Anthropic](https://github.com/anthropics/skills/blob/main/skills/pptx/SKILL.md). Su `LICENSE.txt` declara condiciones propietarias; por eso no se copió código, texto, ejemplos ni paletas, y solo se conservaron ideas generales de diseño en redacción propia. En el directorio actual no existen los archivos independientes `pptxgenjs.md` ni `html2pptx.md`; la guía de PptxGenJS está integrada en `SKILL.md`.

Fuentes: [SKILL.md](https://github.com/anthropics/skills/blob/main/skills/pptx/SKILL.md) · [LICENSE.txt](https://github.com/anthropics/skills/blob/main/skills/pptx/LICENSE.txt).
