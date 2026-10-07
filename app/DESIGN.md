# Jarvys / Artemis Android — diseño de port (Fase 1)

**Estado:** diseño original aprobado con decisiones de producto posteriores (Etapa A en progreso).  
**Fuentes examinadas:** `/root/Jarvys/.tmp/artemis` (referencia Python, solo lectura) y este proyecto Android Java.  
**Alcance original:** agente operado desde la propia APK/dispositivo, sin depender de ADB ni de un proceso host para operar. La semilla original se identificaba como `artemis-accessibility-helper`, `applicationId com.artemis.helper`; la decisión aprobada de Fase 2 cambia la identidad de producto a Jarvys (`com.jarvys.agent`) y deja las clases heredadas de accesibilidad en `com.artemis.helper`.

## 1. Hallazgos de arquitectura que condicionan el port

1. La semilla heredada exponía HTTP loopback en `127.0.0.1:18888`; el contrato está documentado en §2 sólo como referencia histórica.
2. Decisión de producto cerrada: producción usa in-process contra `AccessibilityService`, sin HTTP, token ni ADB. El código heredado de servidor no se arranca.
3. El protocolo de seguridad existente está diseñado para confianza host→teléfono: `TokenReceiver` exige `WRITE_SECURE_SETTINGS`, y Python entrega el secreto usando `adb shell am broadcast`. Una app ordinaria no puede auto-concederse ese permiso. Por tanto el flujo de token de host **no puede ser requisito** para la app autónoma en el teléfono; si el cliente interno usa HTTP hay que resolver autenticación propia (token generado/guardado internamente o identidad de proceso), manteniendo los endpoints protegidos. La llamada directa in-process evita exponer esas operaciones al resto de apps y es la opción propuesta.
4. «Agente en el teléfono» no determina si el LLM también se ejecuta localmente. El código Python integra proveedores/servicios LLM. El cliente/modelo, sus credenciales, política de red y soporte de tool-calling Android siguen siendo decisiones pendientes; no se asume inferencia offline.
5. Hay una incompatibilidad de seguridad funcional que debe resolverse antes de prometer STOP literal e inmediato: Android `AccessibilityService.dispatchGesture()` no ofrece un handle cancelable para retirar de forma arbitraria un gesto ya despachado. Un nuevo gesto cancela el anterior pero también inyecta entrada y puede causar una acción. El diseño sí puede bloquear inmediatamente toda acción futura y cancelar tareas/esperas/LLM; no puede garantizar que un gesto ya inyectado cese instantáneamente sin validar una cancelación segura en dispositivo.

## 2. Servidor HTTP del helper: contrato comprobado

### Arranque y bind

- `ArtemisAccessibilityService.onServiceConnected()` crea `new CommandServer(this, DEFAULT_PORT)` y lo arranca en thread `ArtemisCommandServer`; `DEFAULT_PORT=18888`, `PROTOCOL_VERSION=2`.
- `CommandServer` crea `ServerSocket`, hace bind explícito a `InetAddress.getByName("127.0.0.1")`, puerto 18888, backlog 50. Tiene pool cached para clientes. El loopback no escucha en interfaces LAN.
- HTTP acepta `GET`/`POST`, cierra conexión por respuesta; timeout de socket 10 s; cuerpo máximo 4 MiB y se lee por `Content-Length` en bytes UTF-8. Respuestas JSON usan `application/json; charset=utf-8`; XML, `application/xml; charset=utf-8`. También acepta JSON-RPC line-delimited directamente sobre TCP (no HTTP).

### HTTP endpoints (rutas exactas y parámetros)

Toda ruta salvo `/` y `/ping` exige token válido. Errores JSON tienen `{ "success": false, "error": "..." }`; falta/token erróneo responde HTTP 401, ruta desconocida 404, cuerpo demasiado grande 413, error interno 500.

| Request | Acceso | Respuesta / efecto |
|---|---|---|
| `GET /ping` o `GET /` | Público | JSON health: `success`, `service`, `version_code`, `version_name`, `protocol_version`, `port`, `auth_required`, `token_set`, `authenticated`; con token correcto agrega `package` y `activity`. El endpoint no filtra esos dos campos sin auth. |
| `GET /snapshot[?fields=xml,elements,tree&include_invisible=1]` | Token | Snapshot JSON atómico. Incluye screenshot como JPEG Base64 (`screenshot_base64`), `has_screenshot`, dimensiones y representación de jerarquía seleccionada. `fields` admite `xml`, `elements`, `tree`; por defecto snapshot sólo configura XML. `include_invisible=1|true` habilita invisibles; default visibles. Captura screenshot vía `takeScreenshot` en Android 11+; en Android <11 o error reporta ausencia/error, no garantiza captura. |
| `GET /dump[?format=xml&fields=...&include_invisible=...]` | Token | Default JSON con `xml`, `elements`, `tree`, dimensiones/rotación, estadísticas y éxito/error. `format=xml` responde XML. `fields` escoge las representaciones. |
| `GET /dump_xml`, `GET /hierarchy.xml` | Token | XML de jerarquía UIAutomator. |
| `GET /hierarchy[?fields=...&include_invisible=...]` | Token | Alias JSON de `/dump` con opciones de dump. |
| `POST /action` o `POST /rpc` | Token | Body JSON con `cmd` más argumentos; JSON resultante de ejecutar comando. |

El token se acepta en HTTP en `X-Artemis-Token` (case-insensitive al leer header), como query `?token=...`, o —para comandos con body— en JSON `token`. Si se envían header y query prevalece header. `/ping` responde aunque el token falte o sea erróneo y refleja `authenticated=false`; **no** requiere auth.

### Comandos de `/action` verificados en `CommandServer.executeCommand`

`ping`; `dump`/`dump_ui`; `snapshot`; `dump_xml`; `tap{x,y,timeout}`; `double_tap{x,y,timeout}`; `long_press{x,y,duration}`; `swipe{x1,y1,x2,y2,duration}`; `type{text,append}`; `clear`; `clipboard{text}`; `global{action}`. Acciones globales reconocidas por `GestureController`: `back`, `home`, `recents`, `notifications`, `quick_settings`, `power_dialog`, `toggle_split_screen`, `lock_screen` (API P+) y `take_screenshot` (API P+). Coordenadas negativas de tap/long_press fallan; duración de long-press queda acotada 500–5000 ms y swipe 50–5000 ms en helper. Las llamadas síncronas esperan callback con timeout.

### Token, provisión y diferencia de contexto

- `TokenReceiver` acepta acción explícita `com.artemis.helper.SET_TOKEN`, extra `token`; el receiver del Manifest está exportado y protegido por `android.permission.WRITE_SECURE_SETTINGS`.
- `TokenStore` mantiene token en memoria de proceso, no persistente; comparación en tiempo constante (`MessageDigest.isEqual`). Sin token asignado, incluso token provisto devuelve no autenticado; el servidor responde 401 en rutas protegidas. Reinicio/rebind borra el token.
- `helper_manager.py` crea el token del host con `secrets.token_hex(24)` (48 hex), lo persiste bajo temp de Artemis (modo 0600), lo entrega por `adb shell am broadcast -n com.artemis.helper/.TokenReceiver -a com.artemis.helper.SET_TOKEN --es token ...`, y usa `adb forward tcp:0 tcp:18888`. Reenvía token al adjuntarse y tras 401. Esto prueba por qué el túnel/token host no se puede reutilizar literalmente desde una app no privilegiada.

## 3. Mapeo de módulos Python a Java propuesto

Paquete de código nuevo: `com.jarvys.agent`. Las clases de `com.artemis.helper` quedan como infraestructura de accesibilidad heredada. Este mapeo describe responsabilidades y estado por etapa.

| Python (solo lectura) | Java propuesto | Responsabilidad/port |
|---|---|---|
| `artemis/drivers/base.py` | `com.jarvys.agent.device.BaseDeviceDriver`, `ScreenData`, `SwipeDirection`, `KeyCode` | API común descrita §4; concretada por `AccessibilityDriver`. |
| `artemis/drivers/android/adb_driver.py` | `AccessibilityDriver` + `AndroidAppActions` | Sustituye transporte ADB por AccessibilityService/Android APIs; capacidades imposibles se declaran, no se simulan. |
| `artemis/clients/accessibility_client.py` | `HelperHttpClient` (opcional) y `AccessibilityDriver` | Contrato de datos/helper. En APK misma app: preferir acceso in-process; el adaptador HTTP es para compatibilidad/diagnóstico. |
| `artemis/runtime/helper_manager.py` | `HelperRuntime` | No porta ADB install/forward. Comprueba service conectado y expone disponibilidad; auth interna sólo si se conserva HTTP. |
| `artemis/graph/graph.py` | `AgentLoop` + `RunCoordinator` | Planner → convergence → Perception → Operator → execution_check → Validator → Summarizer → convergence. Etapa C prueba este camino con fake local determinístico, sin red. |
| `artemis/graph/perception.py` | `PerceptionEngine` | Captura screenshot+hierarchy, dimensiones, settling heuristic, OCR/fusión opcional y snapshot de estado. |
| `artemis/graph/state.py` | `AgentState`, `ActionRecord`, `RunSignals` | Estado tipado por turno; flags sticky para STOP/señales externas; datos de pantalla, plan/historial y resultado. |
| `artemis/graph/visibility.py` | `StateAccessPolicy` + validación de transiciones en `AgentLoop` | Manifiestos de lectura/escritura por nodo y errores de acceso/escritura inválidos; mantener explícito, no usar dict libre sin validar. |
| `artemis/graph/checkpoints.py` | `CheckpointCoordinator`, `CheckpointLedger`, `RunOutcome` | Cola, ejecuciones async, ledger append-only, harvest/settlement y resultado. Revisar alcance de auditoría y almacenamiento local antes de port completo. |
| `artemis/controllers/device_controller.py` | `DeviceController` | Contrato de alto nivel y normalización `ScreenDataResponse`, `TapOutput`, bounds. |
| `artemis/controllers/unified_controller.py` | `UnifiedController` | Conversión de coordenadas/selectores, dispatch driver, selección de app y acciones scoped. Grabación está fuera de alcance. |
| `artemis/controllers/types.py` | `ActionRequest`, `CoordinateSelector`, `SwipeRequest`, `Bounds`, `TapOutput` | Tipos de requests; selector distingue píxeles/porcentaje/elemento. |
| `artemis/core/tool_declaration.py` | `ToolSpec`, `ToolRegistry` | Registro con schema, fuente y estado implementado/no soportado; verifica igualdad del inventario esperado para detectar omisiones/duplicados. |
| `artemis/mcp/action_specs.py`, `artemis/mcp/action_manifest.py`, `artemis/mcp/action_types.py` | `DeviceActionCatalog`, `ActionSpec`, `ActionResult` | Fuente canónica de dialectos Operator/JSON/wire, parámetros y compatibilidad; conservar index-or-coordinate (0–1000), descripciones objetivo y distinción de acciones declaradas vs sólo wire. |
| `artemis/tools/base.py`, `tool_wrapper.py`, `index.py` | `AgentTool`, `ToolRegistry`, `ToolResult` | Declaraciones, disponibilidad, invocación/validación, trazas y resultados. Mantener todos los tools aprobados; ver §6. |
| `artemis/agents/operator/operator.py`, `prompts.py` | `OperatorAgent`, `PromptBuilder`, `HistoryContextBuilder` | Construye contexto de observación/plan/historial, llama al modelo con tool loop y emite decisiones estructuradas. |
| `artemis/agents/validator/validator.py` y `validator/{execution_loop,action_execution,precondition_xml,precondition_pixel,categories}.py` | `ActionValidator`, `ActionExecutor`, `XmlPreconditionValidator`, `VisualPreconditionValidator`, `ValidationFailure` | Validar precondición/target antes de actuar; reparar/coordenadas auto-correctas sólo donde equivalencia esté implementada. |
| `artemis/agents/explorer/explorer.py`, `tool_declarations.py`, `perception_tools.py`, `geometry.py`, `screen_index.py`, `tiers.py`, `universal_runner.py`, `native_runner.py` | `MultimodalTargetResolver`, `UnifiedController`, provider vision | Búsqueda estructural primero, luego OCR/objeto visual con coordenadas normalizadas, hit-test y validación visual antes de despachar targets por coordenadas. |
| `artemis/tools/mobile/read_hierarchy.py` | `ReadHierarchyTool` | Jerarquía XML vigente, usa capturador local. |
| `artemis/tools/mobile/launch_app.py` | `LaunchAppTool` + `InstalledAppResolver` | Nombre de app→paquete requiere resolver on-device (inventario y, si procede, modelo); ejecución por intents/PackageManager. |
| `artemis/tools/wait_tool.py` | `WaitTool` | Espera cancelable por STOP, con misma semántica temporal. |
| `artemis/tools/history/__init__.py` | `SearchHistoryTool`, `ReplayStepsTool`, `GetStepScreenshotTool` | Consulta/replay de pasos persistidos. |
| `artemis/agents/summarizer/summarizer.py`, `agents/flash/summarizer.py`, `agents/flash/context_compressor.py`, `memory/step_memory.py` | `AsyncHistorySummarizer`, `HistoryCompressor`, `StepMemoryStore` | Cola asíncrona y resúmenes visuales; el loop no debe esperar al resumen para continuar. Estado actual de Python despacha summary de transición al servicio compartido, no necesariamente una llamada bloqueante por paso. |
| `artemis/data_engine/{engine,models,storage,trace,history_reader}.py` | `RunStore`, `StepRecord`, `TraceRecorder`, `HistoryReader` | Persistencia local por corrida, screenshot pre/post, XML/OCR, acción y resultado. |
| `artemis/agents/{planner,operator,validator,summarizer,diagnoser,explorer,history_analyzer,outputter,hopper,image_processor,object_detector,video_analyzer,log_analyzer,checker}/**` | `AgentModel`, `ProviderAgentModel`, `ActionValidator`, `AsyncHistorySummarizer`; tools auxiliares en `ToolRegistry` | Etapa D usa proveedores reales para Planner/Operator/Summarizer y visión/Diagnoser contextual. Subagentes con plumbing no portado permanecen explicitamente no soportados. |
| `artemis/tools/{command_tool,diagnostic_tool,explorer_tool,scratchpad,video_tool,log_tool,object_detection_tool,image_processor_tool,committee_tool,diagnoser_submit_answer_tool}.py` | `ToolRegistry` | Los nombres auditados están en `EXPECTED_INVENTORY`; handlers no disponibles se marcan `UNSUPPORTED` y responden con error explicativo. |

### Ciclo de control que se preserva

Código Python verificado en `graph.py`: Planner → converge → Perception → Operator → execution_check → Validator cuando hay decisiones → Summarizer → converge, que continúa o finaliza según plan/halt/checks. Etapa C validó este ciclo con respuestas scripted sin red; Etapa D reemplaza ese runner por `ProviderAgentModel`, que usa el proveedor elegido. Cada turno captura, ejecuta, persiste pre/post, despacha el resumen en segundo plano y converge. El ciclo mantiene límite de turnos, latch STOP y ToolRegistry con inventario auditado.

**Límites expresos de Etapa C:** Planner/Operator/Summarizer son deterministic/fake local; no se ejecutan Checker/checkpoints/final-verification porque el plan scripted no declara check items. No se afirma paridad con el ledger de checkpoints Python. `ActionValidator` valida estructura, target índice/coordinate y árbol actual; VLM pixel repair, OCR y Explorer se difieren a Etapa D y sus tools registradas devuelven unsupported.

La cifra README «~3–5 s por paso» es descripción de performance del original, no garantía transferible al teléfono. Debe medirse con el proveedor/modelo y dispositivo elegidos.

## 4. Interfaz Java equivalente a `BaseDeviceDriver`

Referencia exacta: `/root/Jarvys/.tmp/artemis/artemis/drivers/base.py`. Contrato Java propuesto: `public interface BaseDeviceDriver extends AutoCloseable` (operaciones posiblemente bloqueantes ejecutadas fuera del main thread; todos los métodos de acción deben recibir/verificar cancelación). Métodos abstractos del source y métodos base concretos se enumeran todos:

```java
String deviceId();
ScreenSize screenSize();
void connect() throws DriverException;
void disconnect();
ScreenData getScreenData(boolean skipSettling) throws DriverException;
boolean tap(int x, int y, int durationMs, int times, int delayMs, CancellationToken token);
boolean longPress(int x, int y, int durationMs, CancellationToken token);
boolean swipe(int startX, int startY, int endX, int endY, int durationMs, CancellationToken token);
boolean swipeDirection(SwipeDirection direction, int durationMs, CancellationToken token);
boolean inputText(String text, boolean clearExisting, CancellationToken token);
boolean pressKey(KeyCode key, CancellationToken token);
boolean launchApp(String packageName, CancellationToken token);
boolean stopApp(String packageName, CancellationToken token);
String getCurrentPackage();
String executeShell(String command, Duration timeout) throws UnsupportedOperationException;
void startVideoRecording(Path outputDir, CancellationToken token);
String stopVideoRecording();
boolean waitForDelay(Duration duration, CancellationToken token);
boolean tapNormalized(int normX, int normY, boolean longPress, int durationMs,
                      int times, int delayMs, CancellationToken token);
boolean swipeNormalized(int[] startNorm, int[] endNorm, int durationMs, CancellationToken token);
ElementMatch findElement(String resourceId, String text, int index, ScreenData screenData);
boolean tapElement(String resourceId, String text, int index, boolean longPress,
                   int durationMs, CancellationToken token);
```

Semántica obligatoria para conservar del source:

- `deviceId()` y `screenSize()` son propiedades del driver; tamaño es ancho/alto en píxeles físicos.
- `getScreenData(skipSettling)` debe retornar bytes y Base64 de screenshot, XML opcional, `uiElements`, width/height, timestamp y platform. La decisión final rechaza MediaProjection completamente: usar exclusivamente `AccessibilityService.takeScreenshot()` mediante `HierarchyDumper.dumpAtomicSnapshot()` (API 30+). En API <30, fallo puntual, `FLAG_SECURE` o payload/dimensiones inválidos, propagar error explícito; no fallback, dimensiones ficticias ni datos parciales presentados como observación válida.
- `tap` conserva duración default source 100 ms, `times=1`, `delayMs=100`; `longPress` default 1000 ms; `swipe` y `swipeDirection` default 800 ms. La abstracción concreta decide límites API con error visible, no silencioso.
- `inputText(text, clearExisting=true)` mantiene reemplazo frente a append. `pressKey` mantiene `KeyCode` (`HOME`, `BACK`, `ENTER`, `DELETE`, `POWER`, `APP_SWITCH`, `VOLUME_UP`, `VOLUME_DOWN`) y entrada `String` o entero si el backend lo soporta. `launchApp` acepta package name; `stopApp` equivale force-stop en Python y necesita capacidad privilegiada (ver §6).
- `executeShell(command, timeout=15s)` conserva contrato semántico si existe backend shell habilitado; Android SDK normal no da shell arbitrario a app ordinaria. No devolver éxito vacío: si no hay backend autorizado, lanzar UnsupportedOperationException con explicación.
- Grabación: fuera del alcance autorizado. `startVideoRecording` y `stopVideoRecording` deben lanzar `UnsupportedOperationException` explícita; no solicitar MediaProjection ni simular una ruta/archivo.
- `waitForDelay(1.0s)` duerme cancelablemente y retorna true sólo si vence normalmente; ante STOP termina cuanto antes con false/cancelled.
- Normalización **del BaseDeviceDriver** es 0–1000 inclusive como escala de entrada, convierte `int(norm * size / 1000.0)` y clamp `[0,size-1]`; long press y tap respetan los parámetros restantes. Swipe normalizado aplica igual para cuatro coordenadas.
- `findElement` busca `resource_id` como substring en `resource_id` o `resource-id`; text case-insensitive substring sobre `text` o `content-desc`. Devuelve el match de índice entre matches (no índice global), error en no encontrado/fuera de rango. Bounds prioriza `parsed_bounds` con `left/top/right/bottom`, luego bounds lista/tupla `[left,top,right,bottom]`, luego regex `"[x,y][x,y]"`; el punto es centro entero de bounds frescos; sólo sin bounds conserva `center` precomputado.
- `tapElement` ejecuta búsqueda y toca centro; retorna false sin tocar ante error/sin center; soporta long press y duración. El targeting visual/coords fallback que README describe es una capa Explorer/validator superior, NO lo implementa este método base. El port total debe implementar y mantener también esa capa (§3/§6).

Tipos asociados propuestos: `ScreenData` conserva `byte[] screenshotBytes`, `String screenshotBase64`, nullable `uiHierarchyXml`, lista de `Map<String,Object>`/DTO inmutables, dimensiones, timestamp y `platform`; `ElementMatch` lleva nodo, `int[] center` nullable y error nullable. Se recomienda DTOs tipados para evitar mapas sin esquema, aunque se proyecten a JSON al enviar tools al modelo.

## 5. STOP flotante: mecanismo diseñado y límite probado

### Camino de corte que se propone

Componentes nuevos: `AgentForegroundService` (dueño de ejecución de larga duración), `StopOverlayService` (overlay), `StopController` (latch compartido), `CancellationToken`, y control de dispatch dentro de `ArtemisAccessibilityService`/`GestureController`.

1. Antes de iniciar cualquier corrida, lanzar servicio foreground propio de agente y solicitar `SYSTEM_ALERT_WINDOW` mediante Settings (`ACTION_MANAGE_OVERLAY_PERMISSION`); iniciar overlay `TYPE_APPLICATION_OVERLAY` (API 26+) con botón STOP, visible sobre otras apps. Si permiso/overlay no está disponible, no iniciar agente en modo unattended. (El Manifest actual no declara `SYSTEM_ALERT_WINDOW`; el foreground service actual es el servicio A11y del helper, no el agente.)
2. Un `StopController` singleton del proceso guarda `AtomicBoolean stopped`, `AtomicLong runGeneration`, `AtomicReference<Future<?>> agentFuture` y referencia al `AgentForegroundService`. Al iniciar una corrida nueva, genera token/generation nuevo; toda tool y todo dispatch deben verificarlo.
3. El handler del botón en main thread hace **primero** `compareAndSet(false,true)` (cierre de la puerta global); invalida generation, cancela inmediatamente `Future.cancel(true)`, detiene el executor/corrutina, cancela timers, requests HTTP/LLM si el cliente permite abort, vacía cola de acciones no despachadas y manda `stopSelf()`/finaliza notificación. No espera respuesta/modelo/otro paso.
4. `ActionExecutor` toma un lock/gate común al servicio de accesibilidad y comprueba `stopped` + generation inmediatamente antes de cada acción y al encolar callback; STOP marca la bandera bajo ese mismo lock. Acciones queued nunca se despachan después del latch. El servicio debe rechazar en handler cualquier request que ya estuviera en cola. Cada iteración del loop, parser de tool-call, wait, OCR, I/O y resultado LLM vuelve a comprobar token; salidas tardías se descartan y nunca reabren la corrida.
5. Debe existir prueba de concurrencia para las carreras «STOP antes del dispatch» y «STOP durante gesture callback», que verifica no hay nueva acción tras latch ni una corrida vieja reanudada.

### Límite que impide afirmar cumplimiento literal sin más decisión

`Future.cancel(true)` interrumpe hilo Java cooperativo, no revierte efectos ya enviados al framework Android. La implementación existente `GestureController.dispatchSynchronous()` espera en `CountDownLatch` y si se interrumpe retorna false, pero no tiene handle de gesto cancelable ni cancela el gesto de framework. `AccessibilityService.dispatchGesture()` documenta que una nueva gesture dispatch cancela gestos activos; para provocarlo habría que despachar otro gesto, que también inyecta input, puede producir click/touch, y no es un mecanismo seguro de STOP universal. `onInterrupt()` no es una API contractual para cancelar una gesture en curso. Por tanto el diseño garantiza corte síncrono de loop/requests futuros, pero **no promete** corte físico inmediato de una gesture ya despachada. Para aceptar el requisito literal, se necesita validar una estrategia Android segura en hardware/API objetivo o aprobar que gesture activa se deje terminar (la mayor duración de gesto actual del helper puede llegar a 5 s). Esto se plantea como decisión bloqueante, no se disfraza como «STOP inmediato».

También debe decidirse qué detener: `STOP` detiene solamente el agente Jarvys y su cola; no cierra a la fuerza otra app objetivo ni desactiva el AccessibilityService del sistema. La frase «deshabilita cualquier acción pendiente del AccessibilityService» aquí significa latch de acciones de automatización del agente/gestos en cola, no desconectar globalmente el servicio (lo cual no tiene API de autorrevocación confiable y podría interferir con ajustes del usuario).

## 6. Lógica/tooling no declarados 1:1 y aprobaciones necesarias

No es honesto afirmar equivalencia total únicamente con los nueve archivos Java sembrados. Antes de código, estas son las diferencias/cuestiones abiertas para aceptar o rechazar:

1. **Fuera del Android SDK normal:** Python `execute_shell`, force-stop arbitrario `stop_app`, enumeración exacta de paquetes por shell y algunos keycodes usan ADB/shell. Una aplicación Android ordinaria no puede hacer `am force-stop` ni shell general. Alternativas reales dependen de Device Owner/privileged build/root/ADB host, que contradicen el objetivo sin host si se elige ADB. Propuesta: implementar capacidades públicas (intent launch, global actions, texto vía AccessibilityNodeInfo, app actual desde AccessibilityEvent) y devolver Unsupported para operaciones sin equivalente; esto no preserva 1:1 `stop_app`/`execute_shell`.
2. **Captura de video:** fuera de alcance por decisión final del dueño. Los métodos se mantienen en la interfaz por compatibilidad y lanzan `UnsupportedOperationException`; no se solicita MediaProjection ni se genera un archivo falso.
3. **Screenshot en sistemas Android viejos/seguros:** MediaProjection está rechazada por completo. `takeScreenshot()` no disponible (API <30), fallo puntual o contenido protegido produce error explícito; no hay fallback ni ScreenData incompleto.
4. **Token loopback:** host secret 48 hex, `WRITE_SECURE_SETTINGS` receiver y `adb forward` no se pueden auto-provisionar de forma normal desde la misma app. Producción es in-process; no se porta el broadcast host.
5. **LLM, credenciales y red:** Python tiene múltiples providers/runners (Universal y Gemini nativo), respuestas multimodales, streaming/tool calls y selectores de modelos/perfiles. No se ha escogido API Java compatible ni almacenamiento de credenciales. Sin ello el loop no puede razonar aunque el control de dispositivo exista. Portar SDK Python/LangChain/LangGraph tal cual está fuera de Java puro; un cliente REST Java propio no sería equivalencia hasta cubrir schemas, tool cycles, multimodalidad, cancellation y errores.
6. **Multimodal Targeting:** En Etapa D, `ToolRegistry` conecta `ask_explorer`, `ask_perception_tool`, `detect_objects`, `get_ocr_list`, `ocr_recognition` y `object_detection` al resolver structural-first + visión del proveedor seleccionado. `ActionValidator` solicita validación visual para targets coordinate antes del dispatch; una negativa o respuesta malformada bloquea la acción. `inspect_region` entrega la región recortada como imagen adicional al siguiente turno. La resolución estructural es local; las detecciones/OCR son provider vision y necesitan un modelo que acepte imágenes.
7. **Conjunto real de tools:** el catálogo de acciones en `artemis/mcp/action_specs.py` declara en dialecto Operator `click`, `input_text`, `swipe`, `press_key`, `manage_app`, `wait_for_delay`, `long_press`; sólo declaración JSON/Flash `click_sequence`; wire-only `wait_for_text`, `open_link`, `erase_one_char`, `focus_and_clear_text` (no se deben anunciar al LLM si el perfil original no los anuncia). La acción Operator admite targets por índice de elemento o coordenadas normalizadas para click/long-press/text; swipe además direction/start/end/target bounds, mientras wire se reduce a coordenadas. El grafo monta esas acciones según capacidades del backend, más `save_note`, `read_note`, `list_notes`, `update_note`, `append_note`, `ask_diagnoser`, `run_adb_command`, `manage_task`, `ask_explorer`, `search_history`, `replay_steps`, `get_step_screenshot`, y opcional `video_analyzer`; monta `analyze_task_output`. Explorer declara adicionalmente `ask_perception_tool`, `detect_objects`, `get_ocr_list`, `ask_image_processor`, `inspect_region`, `submit_answer`. `tools/mobile` contiene `get_ui_hierarchy`, `ocr_recognition`, `search_logs`, `read_logs`, `launch_app`; `wait_tool.py` define `wait` (tool distinto del action `wait_for_delay`). También hay herramientas de `video_tool`, `log_tool`, `object_detection_tool`, `image_processor_tool`, `committee_tool` y `diagnoser_submit_answer_tool`, según agente/feature/config. El inventario completo habilitado depende de contexto, configuración, backend y perfil; estos nombres no son todos simultáneamente tools del Operator. Antes de codificar debe congelarse el inventario completo de cada agente/perfil; cualquier tool omitida deberá anotarse individualmente (nombre, Python source, motivo y reemplazo/ausencia), no eliminarse silenciosamente.
**Inventario congelado para Etapa C:** `ToolRegistry.EXPECTED_INVENTORY` contiene 51 entradas y el constructor falla si falta o se duplica algún nombre. Acciones: `click`, `click_sequence`, `long_press`, `input_text`, `swipe`, `press_key`, `manage_app`, `wait_for_delay`, `wait_for_text`, `open_link`, `erase_one_char`, `focus_and_clear_text`. Grafo/notas/history: `save_note`, `append_note`, `read_note`, `list_notes`, `update_note`, `search_history`, `replay_steps`, `get_step_screenshot`, `report_task_status`, `ask_explorer`, `ask_diagnoser`, `ask_committee`, `run_adb_command`, `manage_task`, `analyze_task_output`, `video_analyzer`. Mobile/Explorer/diagnostic: `get_ui_hierarchy`, `launch_app`, `wait`, `ask_perception_tool`, `detect_objects`, `get_ocr_list`, `ask_image_processor`, `inspect_region`, `submit_answer`, `ocr_recognition`, `search_logs`, `read_logs`, `analyze_logs`, `object_detection`, `spawn_log_reader`. Internas/auxiliares: `observe_screen`, `take_screenshot`, `video_analyzer_pure`, `extract_segment_metadata`, `spawn_sub_agent`, `analyze_audio_only`, `execute_python`, `submit_result`. Cada tool expone fuente, schema y estado; servicios diferidos producen unsupported, no valores simulados.

8. **Agentes y persistencia:** Planner, Operator, Validator y Summarizer usan el proveedor real seleccionado en Etapa D; Explorer/Diagnoser están disponibles como herramientas parciales basadas en el contexto actual. Checker/checkpoints/final review aún no se ejecutan: el Planner Java no genera check-items `assert`/`verify`, por lo que la corrida normal no tiene evidencia a auditar. Log Reader/Logcat y Committee siguen declarados unsupported, no se simulan.
9. **Resumen de historial asíncrono:** debe mantenerse best-effort/async para no bloquear el próximo paso; usar Executor separado y almacenamiento versionado/cola local. Exacta paridad con visual-transition lens requiere mismos insumos de pre/post imagen, acción, outcome/focus y summary service/LLM. El tiempo 3–5 s no es requisito medible aún.
10. **Overlay:** requiere autorización especial del usuario en Settings y puede haber reglas de distribución Android/Play si se publica. No se ha confirmado distribución ni si es aceptable bloquear ejecución cuando la autorización SYSTEM_ALERT_WINDOW no está concedida.
11. **STOP durante gesto en vuelo:** descrito §5 es limitación técnica sin solución segura demostrada. La aprobación debe expresar si STOP inmediato significa cancelar pensamiento/tareas y acciones aún no enviadas, o también cancelar físicamente touch ya inyectado; para lo segundo primero hay prueba técnica de API/dispositivo necesaria.

## 7. Superficie Java existente revisada (sin cambios)

- `ArtemisAccessibilityService`: singleton de servicio, estado de foreground package/activity y APIs in-process; el servidor heredado no se arranca.
- `CommandServer`: HTTP + raw TCP JSON, endpoints y comandos arriba.
- `TokenReceiver` / `TokenStore`: sesión token host protegida y de memoria.
- `GestureController`: dispatch síncrono de gestos, acciones globales, ACTION_SET_TEXT, clipboard.
- `HierarchyDumper`, `A11yNode`, `XmlUtils`, `DisplayUtils`: árbol semántico/XML/JSON, bounds visibles, snapshots/screenshot API y dimensiones.

Los nombres arriba son del árbol real de `app/src/main/java/com/artemis/helper`; no se propone reemplazarlos por clases con otro nombre sin necesidad. Java nuevo de agente vive en `com.jarvys.agent` para distinguir automatización/agente del bridge de accesibilidad.

## 8. Decisiones aprobadas para Fase 2

1. Transporte in-process únicamente en producción; `applicationId=com.jarvys.agent`, etiqueta Jarvys, paquetes nuevos `com.jarvys.agent.*`; clases de accesibilidad heredadas pueden permanecer en `com.artemis.helper`.
2. `stop_app`/`execute_shell`: usar sólo capacidades públicas disponibles; operaciones imposibles lanzan `UnsupportedOperationException` explicativa.
3. Proveedores LLM aprobados para Etapa D: OpenAI ChatGPT OAuth estilo Codex (PKCE, access/refresh tokens rotables, JWT `chatgpt_account_id`, Bearer a `chatgpt.com/backend-api/codex/responses`; sin `sk-svcacct`) según decisión del dueño del producto; OpenRouter Bearer key cifrada con `EncryptedSharedPreferences`.
4. Captura exclusivamente con `AccessibilityService.takeScreenshot()` API 30+; si no funciona, error explícito. MediaProjection rechazada por completo. Grabación de video fuera de alcance con métodos `UnsupportedOperationException`.
5. Port completo del inventario real de agentes/tools; toda imposibilidad Android se documenta explícitamente, sin omisión silenciosa.
6. STOP síncrono cierra latch, loop y acciones futuras; acción ya despachada puede terminar (límite Android aceptado).
7. Overlay de `SYSTEM_ALERT_WINDOW` obligatorio; sin permiso se rechaza el inicio.

Entrega incremental aprobada: Etapa A identidad + esqueleto/STOP; B driver/perception; C catálogo/loop con LLM fake; D OAuth/OpenRouter y agentes/targeting. Esperar revisión del usuario al completar cada etapa antes de iniciar la siguiente. Esta aprobación no implica que Etapas B–D estén implementadas.

## 9. Implementación Etapa D

- `CodexOAuthManager` reproduce las constantes y parámetros de `lib/auth/auth.ts` en los dos repos estudiados: `CLIENT_ID=app_EMoamEEZ73f0CkXaXp7hrann`, authorize/token URLs, redirect `http://localhost:1455/auth/callback`, scope, state y PKCE S256. Intercambia code y rota refresh token, extrae el claim `https://api.openai.com/auth.chatgpt_account_id` y usa el OAuth Bearer más headers Codex contra `https://chatgpt.com/backend-api/codex/responses`.
- `SecretStore` persiste access/refresh/account ID y API key OpenRouter exclusivamente en `EncryptedSharedPreferences` con `MasterKey` Android Keystore. La UI no vuelve a mostrar la clave OpenRouter; el campo se limpia tras guardar. OAuth se completa en Custom Tab con callback loopback; si el puerto 1455 está ocupado ofrece pegar la URL completa y valida `state` antes del exchange.
- `OpenRouterClient` usa Chat Completions con Bearer key y modelo editable. `ProviderAgentModel` implementa plan JSON, tool decisions y resumen async; `AgentLoop` usa el provider seleccionado. La herramienta visual adjunta screenshot(s) como imágenes para los modelos multimodales. El fake determinístico de Etapa C ya no forma parte del runtime UI de Etapa D.
- `MultimodalTargetResolver` hace búsqueda estructural primero y usa el proveedor para `ask_explorer`, OCR, detección, audit de coordenadas y precondición visual de targets; sólo funciona con modelos que aceptan imágenes. `ask_diagnoser` es parcial (captura + historial, sin logs/video). `Checker` no se ejecuta en el flujo estándar porque el plan Java no crea check-items; `ask_committee`, Logcat y `video_analyzer` quedan registrados como `UNSUPPORTED` con motivo.
- Roles de ejecución: `PlannerAgent`, `OperatorAgent`, `SummarizerAgent`, `ActionValidator` y `MultimodalTargetResolver` están conectados al `ProviderAgentModel`; la lista de tools enviada al Operator es la superficie real de su rol, mientras `ToolRegistry` retiene las 51 entradas del inventario completo con estado. Prueba de catálogo en JVM: 25 implementadas, 10 parciales y 16 explícitamente no soportadas. No se hicieron llamadas reales durante compilación/pruebas locales.
- Exclusión aprobada: `ask_image_processor`, `execute_python` y `submit_result` permanecen registrados como `UNSUPPORTED`, porque la implementación Artemis ejecuta Python arbitrario en un Jupyter aislado y Jarvys Java no aporta ese sandbox. `inspect_region` sí tiene implementación Java para crop/zoom. Video continúa fuera de alcance según §6.
