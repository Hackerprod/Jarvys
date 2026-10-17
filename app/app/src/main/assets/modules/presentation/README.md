# Módulo de presentaciones

`create_presentation` produce un `.pptx` nativo con PptxGenJS. Además de texto, formas e imágenes incorporadas, admite gráficos de barras y tablas editables de PowerPoint. El handler entrega los bytes del archivo mediante `artifact.deliver` y conserva la miniatura PNG de la primera diapositiva que aparece en la tarjeta del chat.

## Defaults de diseño

- Encabezados Cambria y cuerpo Arial, con tamaño de título destacado y texto de apoyo legible.
- Fondo casi blanco por defecto; el color del texto se elige por luminancia para conservar contraste en fondos claros y oscuros.
- Paleta consistente para series de gráficos y colores de gráfico más luminosos sobre temas oscuros.
- Gráficos nativos con etiquetas de categorías y valores, título cuando se aporta y sin leyenda redundante en una serie.
- Tablas con encabezado oscuro, texto claro y filas alternadas claras con texto oscuro.
- Alineación, márgenes y espacio entre elementos definidos en el módulo.

La herramienta espera que el agente formule títulos que expresen la conclusión y mantenga una idea central por diapositiva. El manifiesto y `skills/presentation/SKILL.md` describen esas pautas junto con los tipos de contenido admitidos.

## Visor

El visor visible usa PptxGlimpse 5.3.1. Lee los bytes del `.pptx` real entregado por el puente con lista blanca existente, convierte las diapositivas a SVG localmente y permite recorrerlas con controles propios. El bundle se sirve desde `vendor/pptx-glimpse.bundle.js`; no usa CDN ni requiere red, workers, WASM o `eval` en el camino `convertPptxToSvg`. La CSP del visor no se modificó.

La elección mejora el uso real frente al render actual: en la evaluación medida, PptxGlimpse dibujó gráficos, tablas y temas en las siete ejecuciones de diapositivas, mientras el visor anterior dejaba gráficos y tablas vacíos. Se conservan dos límites observados: no dibuja las etiquetas de valor explícitas de los gráficos y omite la imagen SVG de la muestra. La tabla completa de resultados y capturas está en [`app/docs/pptx-viewer-eval/README.md`](../../../../../../docs/pptx-viewer-eval/README.md).

`viewer/deck-renderer.js` queda para producir la miniatura de la tarjeta a partir de los bytes del PPTX. El usuario ve un solo visor: la vista de pantalla completa la sirve PptxGlimpse; conservar el renderer pequeño para la miniatura evita añadir a ese flujo la conversión más pesada que el chat no necesita.

## Dependencias incluidas

| Componente | Versión | Uso | Licencia |
|---|---:|---|---|
| PptxGenJS | 4.0.1 | Generación del archivo PowerPoint | MIT, `vendor/PPTXGENJS-LICENSE.md` |
| JSZip | 3.10.1 | Lectura local para la miniatura | MIT, `vendor/JSZIP-LICENSE.md` |
| pptx-glimpse | 5.3.1 | Render del visor final a SVG | MIT, `vendor/PPTX-GLIMPSE-LICENSE.md` |
| `@pptx-glimpse/document` | 0.16.0 | Dependencia empaquetada del renderer | MIT |
| `@pptx-glimpse/editor` | 0.6.0 | Dependencia empaquetada del renderer | MIT |
| `fast-xml-parser` | 5.11.2 | Lectura XML del PPTX | MIT, `vendor/FAST-XML-PARSER-LICENSE.md` |
| `opentype.js` | 1.3.4 | Métricas y render de fuentes | MIT, `vendor/OPENTYPE-LICENSE.md` |
| `@resvg/resvg-wasm` | 2.6.2 | Dependencia declarada por el paquete | MPL-2.0; el build del navegador usado aquí la excluye por tree-shaking y no carga WASM |

Las versiones y licencias de las dependencias se comprobaron en los `package.json` del árbol npm resuelto para el build. PptxGlimpse se compiló con Vite 8.3.4; el paquete de runtime no se descarga en la aplicación. El bundle mide **1,199,469 B (288,157 B gzip)**.

Para regenerar el bundle con `app/docs/pptx-viewer-eval/bundle-glimpse.cjs`, instala `pptx-glimpse@5.3.1` y Vite 8.3.4 en el directorio de build temporal y ejecuta:

```powershell
$buildRoot = "$env:LOCALAPPDATA\Temp\jarvys-pptx-glimpse-browser-build"
$packageRoot = "$env:LOCALAPPDATA\Temp\jarvys-pptx-viewer-eval\pptx-glimpse-5.3.1\package"
node app/docs/pptx-viewer-eval/bundle-glimpse.cjs $buildRoot $packageRoot
Copy-Item "$packageRoot\dist\browser.js" "app/app/src/main/assets/modules/presentation/vendor/pptx-glimpse.bundle.js" -Force
```

Los scripts y capturas de evaluación quedan en `app/docs/pptx-viewer-eval/`.

## Reglas de diseño y licencia de la fuente

Las pautas del skill local resumen y parafrasean recomendaciones generales consultadas en el [skill PPTX de Anthropic](https://github.com/anthropics/skills/blob/main/skills/pptx/SKILL.md). Su `LICENSE.txt` declara condiciones propietarias; por eso no se copió código, texto, ejemplos ni paletas, y solo se conservaron ideas generales de diseño en redacción propia. En el directorio actual no existen los archivos independientes `pptxgenjs.md` ni `html2pptx.md`; la guía de PptxGenJS está integrada en `SKILL.md`.

Fuentes: [SKILL.md](https://github.com/anthropics/skills/blob/main/skills/pptx/SKILL.md) · [LICENSE.txt](https://github.com/anthropics/skills/blob/main/skills/pptx/LICENSE.txt).
