# UX40: núcleo de escritura local en Kotlin

Checkpoint del 9 de octubre de 2026. El serializador ya existe en el código Android como Kotlin puro, pero **todavía no está conectado a herramientas, almacenamiento ni UI**. No se añade una dependencia Rive ni se activa una mascota. Este resultado no cierra UX40 ni acredita ejecución en Android.

## Qué se ha comprobado

- `BotMascotSceneCompiler` compila escenas JSON originales y produce `.riv` 7.4 sin CLI, procesos externos, red o plantillas de personajes. Un nombre distinto no selecciona un diseño: geometría, jerarquía y keyframes proceden de la escena suministrada.
- Dos fixtures originales, Miga y Tallo, producen exactamente los mismos bytes que el [escritor de referencia](../local-writer/README.md): 1.303 y 1.158 bytes, respectivamente. Sus hashes y complejidad constan en `evidence/kotlin-writer-results.json`. Las fixtures solo están en recursos de prueba.
- 21 métodos JUnit por variante, Play y Full, ejecutados de nuevo con cero fallos, errores u omisiones. Se cubren UTF-8/JSON estrictos, claves duplicadas incluso escapadas, tipos y campos, referencias, jerarquía, valores finitos, cotas, orden, determinismo y paridad binaria, incluido el redondeo de radios positivos diminutos a float. No son las suites completas.
- Los archivos exportados por esas pruebas Kotlin, no archivos Python sustitutos, se importaron en el runtime oficial `@rive-app/canvas-advanced` 2.44.1. Se comprobaron seis transiciones por mascota, reset de todos los nodos animados, interpolación, ciclos, instancias separadas y rechazo de cuatro binarios corruptos/de versión.
- Doce frames reales mediante Canvas2D oficial y adaptador Canvas de host; seis comparaciones de píxeles confirman movimiento en Idle/Active y cero cambios entre frames 1 y 181 en Reduced. El [validador y renderizador reproducibles](../local-writer/) se reutilizaron con los outputs Kotlin. No fue una prueba de navegador ni de Android.

## Contrato acotado y límites

Grupos, elipses, rectángulos redondeados y rellenos sólidos; transforms x/y/rotación/escala con interpolación lineal. Artboard Mascot de 256×256, controlador MascotController, tres timelines Idle/Active/Reduced, inputs clásicos mode y reducedMotion. Este controlador técnico compartido no impone la silueta o coreografía del personaje.

Cotas: 128 KiB de JSON, 64 KiB de salida, 96 nodos, profundidad de jerarquía 8, 64 tracks por estado, 64 keyframes por track, 600 frames y nombres de hasta 80 bytes UTF-8. El parser añade límites estructurales antes de construir el modelo. No admite código, rutas, URLs, recursos externos, fuentes, scripts ni un binario Rive arbitrario de entrada.

Todavía faltan el contrato de nueve modos reales y sus poses reducidas, ViewModels modernos, creación/edición transaccional del bot, validación del runtime Android, ciclo de vida, accesibilidad y medidas reales de APK/memoria/fluidez. La prueba WASM no sustituye estos gates. La inferencia del modelo y la compilación local son etapas diferentes; no se ha acreditado inferencia offline.

## Reproducción

Ejecutar las tareas `:app:testPlayDebugUnitTest` y `:app:testFullDebugUnitTest` filtrando `com.jarvys.agent.BotMascotSceneCompilerTest`. La variable de entorno `JARVYS_MASCOT_OUTPUT_DIR` elige una carpeta de salida; por defecto se utiliza el directorio de resultados del módulo. El test exporta las dos fuentes compiladas y sus hashes para validación independiente.

Copiar los validadores del directorio `local-writer` a un directorio temporal, conservar sus escenas originales y apuntar su directorio `artifacts` a los outputs Kotlin. Seguir los requisitos de runtime y Canvas descritos allí. La evidencia saneada se encuentra en `evidence/`; no se distribuyen ejecutables de proveedor, caches, claves, informes operativos ni APK en este checkpoint.

## Fuentes y licencia

La implementación utiliza el [formato público Rive](https://rive.app/docs/runtimes/advanced-topic/format) y los IDs/layout del [runtime público MIT](https://github.com/rive-app/rive-runtime/tree/6f3510dcc545bc8b2a78f1004a06929d17cd022b), documentados con precisión en [SOURCES.md](../local-writer/SOURCES.md). Los avisos MIT se conservan en el código, `app/NOTICE.md` y el asset de licencia. No se porta el compilador RML/CLI propietario ni se presupone que su licencia sea MIT.
