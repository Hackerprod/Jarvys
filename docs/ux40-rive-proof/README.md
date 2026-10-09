# UX40: prototipos de mascotas Rive creados por el agente

Prueba de viabilidad del 9 de octubre de 2026. Estos son prototipos para demostrar autoría de gráficos y animaciones propios; no constituyen los diseños finales ni una función integrada en Jarvys.

## Qué se demostró

- **Lumen:** robot linterna flotante, con 18 figuras vectoriales y movimiento de aletas, ojos, cuerpo y propulsión.
- **Brisa:** búho de hojas plegadas, con 46 figuras, trazados originales, alas articuladas y señales propias de estado.
- Dos escenas independientes: geometría y coreografía diferentes, sin limitarse a recolorear una plantilla.
- Rive CLI oficial **1.5.1** compiló archivos `.riv` reales. Cada uno contiene un artboard, un view model, una máquina de estados y 18 timelines: nueve modos y nueve variantes estáticas.
- Sin scripts de runtime, shaders, imágenes, fuentes, audio ni otros recursos externos. Los generadores Python solo escriben el RML; no se ejecutan dentro de los archivos Rive.

Los nueve modos son reposo, pensamiento, trabajo, espera del usuario, finalizado, error, interrumpido, en cola y espera del proveedor. La aplicación anfitriona determina el estado. Una animación no ejecuta tareas ni demuestra por sí misma que exista trabajo en curso.

Lumen mantiene estáticas las esperas y la interrupción; reposo/pensamiento/trabajo tienen bucles y finalización/error tienen gestos breves. Brisa añade movimientos ambientales suaves a las esperas. Todos los modos tienen una pose de movimiento reducido. El estado accesible y el nombre deben seguir existiendo fuera de la animación.

## Contrato de datos

- Artboard: `Mascot`.
- View model: `MascotState`; instancia por defecto: `Default`.
- `mode`: número entero 0–8, siguiendo el orden anterior.
- `reducedMotion`: booleano; `true` selecciona una pose estática.
- Máquina de estados: `MascotMotion` en Lumen y `MascotController` en Brisa.
- El anfitrión debe validar los valores y mantener una instancia mutable independiente por bot. Reescribir el mismo modo no es un evento de reinicio.

## Evidencia y límites

Los archivos exportados midieron 8.020 bytes (Lumen) y 16.621 bytes (Brisa). Sus hashes están en `evidence/host-results.json`. Compilación e inspección no reportaron errores, avisos ni problemas.

Se comprobaron capturas nativas del CLI, lectura de bindings y poses reducidas distintas. Las nueve comparaciones estáticas de cada mascota conservan los píxeles entre los tiempos observados. Se verificó además movimiento posterior al blend de entrada en los seis bucles de Brisa.

Un segundo runtime oficial, `@rive-app/canvas-advanced` **2.44.1** como WASM en Node, importó los bytes `.riv` exportados. Por mascota se probaron 72 cambios ordenados entre modos diferentes, nueve cambios a movimiento reducido y vuelta, y un escenario de independencia entre dos instancias. Un binario inválido fue rechazado.

Las aserciones de nombres comprueban el destino de las transiciones; no demuestran igualdad exhaustiva con una instancia nueva tras cada reentrada. La prueba estática de WASM compara transforms seleccionados en dos momentos, complementada por pistas constantes y capturas. No certifica cada propiedad en cada frame.

**Pendiente:** ejecución real en Android, ciclo de vida, rendimiento del teléfono, consumo, incremento final del APK y recorrido completo de creación desde Jarvys. El CLI publicado es para Linux x86_64, macOS Apple Silicon y Windows; hace falta resolver un host compatible o una vía oficial de compilación para Android. `rive-android` reproduce `.riv`, no compila estas escenas RML.

## Reproducción local

Instalar el CLI desde las instrucciones oficiales y fijar la versión 1.5.1 para reproducir este ensayo. Se necesitan Python con Pillow y Node; para la prueba independiente, el paquete oficial `@rive-app/canvas-advanced@2.44.1`. No se incluyen ejecutables ni dependencias de terceros.

Desde esta carpeta:

```sh
rive sources/lumen --verify --format=json
rive sources/lumen --once --format=json
rive sources/brisa --verify --format=json
rive sources/brisa --once --format=json
python3 tests/validate_lumen.py
python3 tests/capture_brisa.py
python3 tests/validate_brisa.py
node tests/validate_live_runtime.mjs
```

`RIVE_CLI` puede señalar un ejecutable/wrapper existente. `RIVE_WASM_DIR` puede señalar el directorio del paquete oficial con `canvas_advanced.mjs` y `rive.wasm`; por defecto se busca en `node_modules/@rive-app/canvas-advanced`. Las pruebas generan resultados en `artifacts/`, excluido de Git, y no publican en Rive ni inician sesión. Los logs conservan fallos de validación; un retorno exitoso del compilador no sustituye inspeccionar bindings y píxeles.

## Derechos, herramientas y gates de distribución

La geometría, RML y generadores de estos prototipos son de creación propia, sin recursos artísticos de terceros. Se preservan aquí las fuentes y pruebas. Los `.riv` compilados, herramientas, cachés y configuración local no se incluyen en este respaldo mientras se aclara el modo de distribución.

La licencia [MIT del runtime Android](https://github.com/rive-app/rive-android/blob/master/LICENSE) no acredita la misma licencia para el compilador/editor. La documentación oficial permite autoría local y CI, pero no demuestra por sí sola permiso general para redistribuir el CLI u ofrecerlo como servicio de generación multiusuario. Un build local sin marca tampoco acredita derecho de distribución comercial sin splash. Los [términos de Rive](https://rive.app/docs/legal/terms-of-service) contemplan aceptación por uso; las condiciones de publicación y plan deben verificarse antes de producción. No se ofrece una garantía jurídica.

Fuentes: [CLI](https://rive.app/docs/cli/overview), [plataformas e inicio](https://github.com/rive-app/rive-docs/blob/main/cli/getting-started.mdx), [comandos y publicación](https://github.com/rive-app/rive-docs/blob/main/cli/reference/commands.mdx), [Android](https://rive.app/docs/runtimes/android/android), [planes](https://rive.app/pricing).
