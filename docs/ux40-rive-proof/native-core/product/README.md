# Contrato local de nueve estados: prueba de host

Dos escenas originales, Nimbo (nube con gotas articuladas) y Folio (cuaderno con ala de página y cinta), se compilan con `BotMascotSceneCompiler.compileProduct`. Este escritor Kotlin no usa Rive CLI, RML, backend ni binarios compilados por el editor. Las fixtures originales están en `app/app/src/test/resources/bot-mascot-scenes/`; el autor reproducible está en `scenes/author_scenes.py`. No son plantillas cargadas por el producto.

## Contrato v1

La raíz JSON exige `contract: "bot-mascot-v1"`. Una geometría compartida y 18 timelines: Idle, Thinking, Working, Queued, WaitingProvider, WaitingUser, Done, Error, Interrupted, más cada nombre terminado en Reduced. Los valores mode son 0–8 en ese orden, nunca ordinales de un enum externo. Un ViewModel MascotState contiene mode:number y reducedMotion:boolean, una instancia Default y asociación al artboard Mascot. MascotController usa condiciones modernas sin inputs clásicos. Los IDs/layout proceden de las definiciones MIT públicas fijadas en [SOURCES.md](../../local-writer/SOURCES.md), no de ingeniería inversa del compilador privado.

Cada estado resetea exactamente los mismos targets en frame 0. Los nueve modos normales exigen datos de movimiento después del redondeo a Float32; cada reducido contiene un solo valor constante por target y no repite. La aplicación debe restringir mode a enteros 0–8. Valores externos fuera del contrato no tienen semántica de transición garantizada.

Las cotas del núcleo se mantienen: 128 KiB de fuente, 64 KiB de binario, 96 nodos, profundidad 8, 64 tracks por estado, 64 keys por track y 600 frames. El contrato de producto permite hasta 1.152 tracks/73.728 keys agregados, siempre subordinado a los límites de bytes y del parser. El parser de escenas conserva strings de 256 caracteres; el parser compartido solo amplía explícitamente hasta 1.200 para metadatos acotados. No admite scripts, URLs, archivos externos o cargas de código.

## Evidencia independiente

Pasada focalizada final: 93 pruebas Play y 93 Full, cero fallos, errores u omitidas. Incluye 33 métodos del compilador, 24 de persistencia, 14 de la nueva herramienta/creación visual, 14 de creación existente y ocho del catálogo por variante. Se verificaron XML nuevos y los dos forks con guard instalado. No son suites agregadas. Los tests de persistencia usan el adaptador de fsync de host documentado en `app/BOT_MASCOTS.md`; no se ejecuta fsync Android.

- Nimbo: 15.471 bytes, SHA-256 `83473fed7db18da9a3ad4302b403a29b5a9e2396f4b49801a5499cf5e57e8b77`.
- Folio: 15.264 bytes, SHA-256 `1d3b4f3443084472677c51f38e41f743018fa206358c9cb1890a5456df6dda0b`.
- Un decoder independiente compara todos los registros con las escenas fuente: geometría, IDs de componentes, timelines, keys y 36 condiciones modernas. Sus 17 mutaciones controladas de path/ToC/orden/índices son rechazadas. Es una prueba del checker, no una campaña de fuzzing del runtime.
- El runtime oficial WASM `@rive-app/canvas-advanced` 2.44.1 importa los bytes reales exportados por Kotlin: 306 transiciones dirigidas entre 18 estados por mascota, 76 midpoints lineales entre ambos recursos, instancias independientes, entrada inicial Idle y poses reducidas correctas desde frame 1 y estables durante 180 frames.
- 72 capturas mediante Canvas2D oficial y adaptador Canvas de host. 36 comparaciones de píxeles confirman movimiento normal y estabilidad reducida; cada mascota tiene nueve poses reducidas con hashes RGBA distintos. Ningún píxel visible toca el borde; el margen mínimo en esas capturas es 29 px. La inspección muestra siluetas distintas y símbolos visibles. Esto no verifica todo el recorrido de movimiento ni legibilidad a tamaño mínimo en teléfono.

Los scripts aceptan `RIVE_WASM_DIR`, `RIVE_PRODUCT_SCENES`, `RIVE_PRODUCT_OUTPUT` y, para renderizar, `CANVAS_DIR`. Usar las versiones y preparación local documentadas en [local-writer](../../local-writer/README.md). Trabajar en un directorio temporal: copiar estos scripts, ejecutar el autor para crear las dos escenas y apuntar OUTPUT a las salidas reales del test Kotlin. El autor debe reproducir los JSON de las fixtures. No sustituir la salida Kotlin por bytes de otro generador.

## Gate abierto

Todo lo anterior es evidencia de host. No acredita importación, representación, fsync, GPU, lifecycle, memoria, fluidez, tamaño final de APK o páginas de 16 KiB en Android. Tampoco prueba inferencia IA offline. El código de reproducción Android todavía debe integrarse y aceptarse; el PNG/glyph continúa siendo el respaldo. UX40 permanece abierto.

Fuentes oficiales: [formato .riv](https://rive.app/docs/runtimes/advanced-topic/format), [runtime MIT fijado](https://github.com/rive-app/rive-runtime/tree/6f3510dcc545bc8b2a78f1004a06929d17cd022b), [Android 11.14.1](https://github.com/rive-app/rive-android/tree/11.14.1). Los avisos MIT del núcleo siguen vigentes.
