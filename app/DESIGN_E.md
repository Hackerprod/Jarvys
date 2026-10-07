# Etapa E — diseño de cliente MCP, conectores, skills y UI Compose

**Estado:** solo diseño. No se modificó código de Etapas A–D.  
**Directorio de trabajo confirmado:** `/root/Jarvys/app`.  
**Referencias:** implementación Python de Artemis en `/root/Jarvys/.tmp/artemis`, SDK oficial MCP Java leído en su README y especificaciones MCP 2025-11-25/2024-11-05, más README y capturas públicas de Kelivo usadas solo como referencia visual.

## 1. Estado real del producto Jarvys antes de E

### UI actual

- `app/src/main/java/com/jarvys/agent/MainActivity.java:22-47` es una Android `Activity` Java; crea toda la pantalla mediante `LinearLayout`, `TextView`, `RadioGroup`, `EditText`, `Button` y `ScrollView` (`:49-185`). No hay Kotlin ni Jetpack Compose en los sources de app.
- La única pantalla agrupa estado/permiso de overlay, selección de OpenAI Codex/OpenRouter, modelo, OAuth, campo enmascarado para OpenRouter, objetivo de tarea, ejecución y prueba manual (`MainActivity.java:70-185`). La tarea termina lanzando `AgentForegroundService` y moviendo la Activity detrás (`:208-239`). No existe una pantalla de conversación persistida ni un navegador MCP/skills.
- `AgentForegroundService.java:185-205` arranca una corrida con el objetivo, instancia `ToolRegistry`/`ProviderAgentModel`/`AgentLoop`, y proyecta progreso al overlay. La UI actual consulta un resumen final estático (`MainActivity.java:323-339`); no está conectada a un flujo observable de mensajes/progreso de chat.
- El STOP es un overlay de sistema real con `WindowManager` y `TYPE_APPLICATION_OVERLAY`, creado en `StopOverlayService.java:54-108`; su callback cierra el latch primero (`:83-88`). Es una infraestructura de seguridad separada de la pantalla de la Activity.
- Ya existen `ProviderSettings` (`ProviderSettings.java:7-39`), `SecretStore` con `EncryptedSharedPreferences`/Android Keystore (`SecretStore.java:9-23`), `CodexOAuthManager`, y los clientes OpenAI/OpenRouter. Estos servicios y el AgentLoop/driver son el núcleo reutilizable; el trabajo E diseña nuevas capas alrededor de ellos.
- Android Gradle Plugin es 8.13.2, wrapper Gradle 8.13, `compileSdk=36`, `minSdk=24`, `targetSdk=35`; la app compila Java 8 (`build.gradle.kts`, `app/build.gradle.kts:5-40`). Las dependencias actuales de UI/seguridad son `androidx.security:security-crypto:1.1.0-alpha06` y `androidx.browser:browser:1.8.0` (`app/build.gradle.kts:43-46`). No hay plugin Kotlin ni Compose.

### Inventario fijo de herramientas

`ToolRegistry.EXPECTED_INVENTORY` contiene las 51 tools/acciones del producto fijo (`app/src/main/java/com/jarvys/agent/ToolRegistry.java:20-47`). `all()`/`names()` conservan el inventario completo (`:50-56`), mientras `toolsForOperator()` selecciona explícitamente la superficie propia del Operator (`:58-70`). La integración MCP debe **sumar** una segunda superficie dinámica; no editará ese set esperado ni reemplazará las 51 herramientas.

## 2. Cliente MCP Java: protocolo y conexión Android

### Evidencia Artemis verificada

1. El `ActionSession` actual no es un cliente hacia MCP servers externos: crea un par cliente/servidor **en memoria** con `create_connected_server_and_client_session`, encola llamadas en un owner task y ejecuta `session.call_tool` (`/root/Jarvys/.tmp/artemis/artemis/mcp/action_session.py:15-47,50-58,77-116,143-178,216-245`). Es la sesión interna del grafo para actuar sobre el teléfono.
2. El `action_server.py` crea un `FastMCP`, deriva los tools de `wire_dialects()`/capacidades del actuator y añade tools internos `observe_screen`, `take_screenshot`, `get_ui_hierarchy` y extensiones del actuator (`artemis/mcp/action_server.py:15-30,69-90,92-191`). No implementa persistencia/configuración de servidores MCP remotos en la APK.
3. El CLI Artemis ofrece **stdio** o **sse**, con host/port para SSE (`artemis/interfaces/cli/commands/mcp.py:694-724,827-855`). Sus snippets de stdio lanzan un Python externo (`command=python_exe`, `args=["-m","mcp_server"]`, `PYTHONPATH` y `cwd`) (`:64-75`). El server público `mcp_server/server.py` ejecuta FastMCP con ese transport seleccionado (`:82-103`). El `uv.lock` fija `mcp==1.29.0` (`uv.lock:2553-2574`).
4. El servidor MCP superior de Artemis registra cinco herramientas (`mcp_server/tools/__init__.py:17-28`): `mobile_run_task`, `mobile_manage_task`, `mobile_get_device_state`, `mobile_inspect_trace`, `mobile_diagnose`. Son tools para conducir/diagnosticar Artemis desde un cliente MCP; no son integraciones locales de Android.
5. La base del actuator separa capacidades físicas de tools de extensión (`artemis/mcp/actuators/base.py:15-29,40-55`); la clasificación fija/backend-independent/extensiones está en `artemis/mcp/action_manifest.py:61-138,162-179`. Esto sirve de precedente para separar inventario fijo y extensiones, pero no es ya un registry de MCP servers remotos.

### Transporte propuesto

| Transporte MCP | Decisión E | Razón y comportamiento |
|---|---|---|
| **Streamable HTTP**, MCP 2025-11-25 | Transporte remoto primario | Es el estándar vigente: un endpoint con POST y GET opcional SSE. Implementar POST JSON-RPC y aceptar respuestas JSON **o** `text/event-stream`; GET SSE opcional para notificaciones del server. |
| **HTTP+SSE legado**, MCP 2024-11-05 | Adaptador de compatibilidad | Artemis 1.29.0 documenta/lanza `transport="sse"`, que usa el SSE endpoint, recibe el evento `endpoint` y luego POST a la URL de mensajes. Se soporta para conectar un Artemis host remoto sin exigir que actualice su transporte. |
| **stdio / JSON-RPC por pipes** | No soportado por la APK | La especificación define que el cliente lanza un proceso server; los snippets reales de Artemis requieren ejecutar Python local. La APK no incluye runtimes genéricos Python/Node ni un launcher de procesos de usuario. No se presentará como conexión soportada ni se intentará ejecutar `command` recibido desde UI. Para un server stdio se necesita su propio endpoint HTTP/SSE remoto o un puente Android expresamente desarrollado y revisado. |

La especificación actual define JSON-RPC UTF-8, los transportes stdio y Streamable HTTP, y declara que HTTP+SSE es el transporte de la versión anterior: [MCP 2025-11-25 Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports). El flujo legado está especificado en [MCP 2024-11-05 Transports](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports).

La decisión evita depender directamente del bundle MCP Java SDK en esta APK: su README declara Java 17+, Reactor y JDK `HttpClient` como transporte remoto por defecto; Jarvys tiene `minSdk 24`, source Java 8 (`app/build.gradle.kts:5-40`) y la API de Android 36 no lista `java.net.http.HttpClient` en `/opt/android-sdk/platforms/android-36/data/api-versions.xml`. El plan es un cliente MCP acotado a Android sobre `HttpURLConnection` + `org.json`, que cumple el wire format y lifecycle de la especificación sin copiar implementación de terceros. El SDK se estudió, pero no se añadió como dependencia sin probar una adaptación Android compatible.

Android aplica cleartext por aplicación, por lo que permitir HTTP requiere `usesCleartextTraffic`; el cliente MCP conserva el gate por configuración (HTTPS por defecto, HTTP loopback/LAN literal o toggle de confianza por servidor). Las llamadas OAuth siguen exigiendo endpoints HTTPS, y nunca se transportan credenciales dentro de la query URL.

### Lifecycle, discovery y uso de tools

Para cada server habilitado en una corrida:

1. Validar/normalizar URL; exigir HTTPS por defecto. HTTP loopback/LAN literal se permite; HTTP a IP/host no local solo después de que el usuario active explícitamente **Confiar en servidor MCP inseguro** para ese server. Ese consentimiento permite cleartext también para su destino configurado; nunca incluir tokens en la URL.
2. En transporte Streamable HTTP, enviar `initialize` JSON-RPC con la versión más nueva soportada `2025-11-25`, identidad `Jarvys`, versión de la app y capabilities **solo** de funciones implementadas; enviar `notifications/initialized` tras negociar. Conservar `MCP-Session-Id` devuelto y añadir `MCP-Protocol-Version` a posteriores requests.
3. Ejecutar `tools/list` con paginación `nextCursor`; conservar `inputSchema`, `outputSchema`, `description` y servidor propietario sin alterar el JSON Schema recibido. Si el server anuncia `tools.listChanged`, refrescar tools de forma atómica.
4. Exponer tools remotos al modelo como tools dinámicas, namespaced para que no colisionen: `mcp_<aliasSeguro>_<hashCorto>__<toolNameNormalizado>`. El mapping guarda `serverId` y el nombre original, sensible a mayúsculas, para realizar `tools/call` con la identidad original. La longitud/carácter del nombre adaptado debe cumplir los límites del proveedor LLM; un nombre que no pueda adaptarse se muestra no disponible con su razón.
5. Enviar `tools/call{name,arguments}` y trasladar `isError`, errores JSON-RPC, `structuredContent`, bloques text/image/resource-link a `ToolResult`. Las imágenes permitidas se agregan como adjuntos multimodales en el siguiente turno; outputs grandes se limitan antes de incorporarlos al prompt. Una tool recién descubierta queda deshabilitada hasta que el usuario la habilite expresamente.
6. Si un SSE está habilitado, parsear eventos con reconexión/respuesta correspondiente; legacy SSE sigue su handshake `endpoint` y POST de MCP 2024-11-05. Timeouts cancelan por la conexión/token del servidor; una desconexión no se interpreta como que el usuario canceló un tool.
7. Si una sesión HTTP devuelve 404 por `MCP-Session-Id` expirado, cerrar y reiniciar handshake/list. Al desconectar o hacer STOP, cerrar GET SSE/HTTP activos, borrar tools dinámicas de ese servidor en la generación de corrida y mantener las 51 fijas.

El lifecycle initialize → initialized y negociación de capabilities son MUST en MCP: [Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle). `tools/list` admite paginación, `tools/call` retorna content/structured results y las tools tienen `inputSchema`: [Server Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools). El servidor debe ser tratado como no confiable: el spec indica que annotations de tools no son confiables por defecto; Jarvys muestra server/tool, origen y progreso en UI, y el usuario puede deshabilitar tools o servidores.

### Cambios de integración que el futuro código MCP requeriría

| Archivo actual | Punto de integración E (a aprobar antes de editar) |
|---|---|
| `ToolRegistry.java` | Mantener `EXPECTED_INVENTORY`/51 fija y su aserción exacta. Añadir `McpServerToolRegistry` separada y un proveedor `toolsForOperator(dynamicTools)`, no insertar/remover claves base. Namespacing y registros de llamada quedan en el layer dinámico. |
| `AgentLoop.java` | Abrir sesiones de los servidores conectados/habilitados antes de construir la lista de tools, manejar notificación `tools/list_changed`, cerrar sesiones en `finally`, y dejar sin sesión MCP el caso no configurado. |
| `ProviderAgentModel.java` / `OperatorAgent.java` | Fusionar tool schemas fijos y MCP dinámicos por corrida; convertir function call namespaced en llamada MCP y devolver resultado/error al siguiente turno. |
| `ToolContext.java` / `ToolResult.java` | Añadir un invoker dinámico MCP y conservar `serverId`, nombre wire, error de protocolo vs `isError` de ejecución y content blocks sin aplanar imágenes a basura Base64. |
| `SecretStore.java` / nuevo `McpServerRepository` | Mantener metadata de URL/alias/transporte/estado como datos no secretos; bearer y OAuth tokens MCP guardarlos en `EncryptedSharedPreferences`, referenciados por server. No reutilizar credenciales OpenAI/OpenRouter para servidores MCP. |
| `AgentForegroundService.java` / `StopController.java` | Integrar sesiones con el run token para que STOP cierre requests y stream activos además del loop; no modificar la semántica STOP ya aprobada. |

**Alcance stdio explícito:** no implementar stdio en Etapa E de la APK. Artemis lo usa para ejecutar su `mcp_server` Python con `command`/`cwd` (`artemis/interfaces/cli/commands/mcp.py:64-75,827-855`). La app no debe ejecutar esos comandos. Para probar Artemis vía MCP, levantar el servidor con su modo SSE legacy en un host alcanzable desde el teléfono, o exponerlo por Streamable HTTP con versión futura.

## 3. Conectores: definición y estado real

La búsqueda en el código Python de Artemis no encontró un tipo/registry llamado `Connector`/`connectors` ni una abstracción general de integración externa. No se documenta “email”, “calendar” u otras APIs de usuario como conectores Artemis. Lo que sí existe es:

- routing de **LLM providers** (modelos OpenAI/Anthropic/OpenRouter/Ollama/Google, etc.), no conectores de datos personales (`artemis/llm/router.py:15-20,37-48,87-103`);
- drivers/actuators para dispositivos (`artemis/drivers/factory.py:34-72`, `artemis/mcp/actuators/base.py:15-29`);
- tools que hacen acciones, logs, OCR, tareas/diagnóstico y tools de extensión de actuator (`artemis/mcp/action_manifest.py:106-138,162-179`). Ninguno define ciclo de vida de una integración Gmail/Calendar.

**Definición propuesta nueva para Jarvys (no port):** un *connector* es un adaptador first-party, instalado como código/paquete revisado de Jarvys, que integra una API de servicio externa mediante endpoints y esquema de autenticación propios, y expone operaciones tipadas al agente. No es un MCP server remoto ni un skill:

- MCP server = proceso/servicio independiente que publica tools por protocolo MCP; herramientas descubiertas son dinámicas.
- Connector = integración nativa de Jarvys con una API de servicio; sus tools vienen de código declarado en la app y su modelo de autenticación/consentimiento se define por servicio.
- Provider = servicio que produce completions/embeddings del modelo (`ProviderSettings` actual); no es por sí mismo un connector de tareas.

La propuesta es un contrato `ConnectorDefinition` (id, nombre, versión, descripción, scopes/capacidades, campos de configuración no secreta, referencias a secretos, lifecycle `connect/disconnect`, tools/schemas y handlers Java), almacenado en un `ConnectorRegistry` separado de `ToolRegistry` MCP y del inventario fijo. Credenciales en `SecretStore`; revocar/olvidar borra tokens del connector. La UI de connectors debe comenzar con estado vacío y no mostrar Gmail/Calendar ficticios. **No se seleccionaron servicios externos concretos**, por lo que el diseño propone la infraestructura solamente; implementar connectors funcionales requerirá aprobar la primera lista de servicios y sus scopes/APIs.

## 4. Skills: ausencia en Artemis y formato nuevo propuesto

La búsqueda de `skill`, `skills`, `SKILL.md` en sources/documentación Artemis no encontró un objeto reutilizable ni formato de skills. Artemis sí tiene prompts fijos por agent/role, descripciones de tools y configuración de agentes, pero eso no es un skill importable. Por ello cualquier skills subsystem es **capacidad nueva de Jarvys**, no port.

### Propuesta de formato inicial

Markdown con frontmatter YAML restringido y versionado; sin código ejecutable:

```markdown
---
id: com.example.android-settings
name: Navegar Ajustes Android
description: Guía para localizar opciones comunes de Ajustes usando la jerarquía visible.
version: 1
allowed-tools:
  - click
  - swipe
  - get_ui_hierarchy
tags: [android, settings]
---

## Instrucciones
Texto de guía reutilizable; no concede permisos ni crea tools.
```

`id` estable y único que también nombra el directorio; `name`/`description` requeridos; `version: 1`; `allowed-tools` opcional y limitado a nombres de tools core/workspace o namespaces MCP ya habilitados; `tags` opcional. El parser acepta solo YAML simple del frontmatter (escalares/lista plana), rechaza keys desconocidas o tipos incorrectos y no ejecuta Jinja, shell, Python o referencias remotas. El body son instrucciones de contexto, no código.

### Almacenamiento y uso propuesto

- Skills empaquetados viven bajo `app/src/main/assets/skills/`; el skill-creator se siembra en almacenamiento privado y Skills importados o creados por el agente viven en `filesDir/skills/<id>/`. El agente accede a esa zona global mediante `/skills/`, distinta del workspace de conversación. `ls/read/write/edit` aplican límites de bytes y canonicalización; `SKILL.md` se valida al escribir, los recursos se escriben antes y el entrypoint al final, y `SkillRepository` serializa la escritura con el rescan. Skills nuevos se habilitan por defecto. El selector de archivos importa `.md`; GitHub debe resolver un `SKILL.md`.
- Las skills habilitadas se incluyen en el agente; el usuario puede deshabilitarlas o escoger un subconjunto para una corrida. Prompt assembly añade título/ID y texto dentro de un bloque claramente delimitado como instrucciones del skill; policy de sistema/STOP/tool permisos tiene precedencia.
- Skills no registran handlers. El filtro `allowed-tools` solo reduce herramientas ya expuestas por `ToolRegistry`, workspace o MCP; no puede crear permisos ni saltar auth ni activar server apagado. Tool names no resueltos dejan el skill con error de validación.
- `SkillRepository`: listar, parsear/validar, detalle, enable/disable, importar, crear/actualizar por workspace y borrar skills importadas. El receipt del skill-creator guarda el hash del asset; una actualización reemplaza solo un archivo que aún coincide con el hash previamente sembrado y conserva cualquier edición del usuario.

El frontmatter se inspira en la forma Markdown+metadata común a los sistemas de skills, pero el schema anterior es propuesta nueva propia de Jarvys; no se atribuye a Artemis ni se declara compatible con un formato Claude Code hasta comprobar formalmente su schema/version.

## 5. UI nativa Kotlin + Jetpack Compose

### Referencia visual y límites de reutilización

Kelivo declara en su README que es Flutter, muestra una interfaz conversacional clara con app bar de conversación/modelo, tarjetas de estado/“Deep Thinking”, bloques de contenido y compositor fijo inferior; otra captura organiza operaciones de agente como tarjetas/timeline compactas con nombre de operación, archivo, duración y salida. Véanse solo las capturas públicas [chat](https://github.com/Chevey339/kelivo/blob/master/docx/screenshot_1.png), [timeline de tools](https://github.com/Chevey339/kelivo/blob/master/docx/screenshot_2.png), [workspace/terminal](https://github.com/Chevey339/kelivo/blob/master/docx/screenshot_3.png) y [web search](https://github.com/Chevey339/kelivo/blob/master/docx/screenshot_4.png), enlazadas desde [Kelivo README](https://github.com/Chevey339/kelivo#-screenshots). El mismo README identifica Flutter y la licencia del repositorio como AGPL-3.0.

Jarvys toma solo principios de layout: conversación como foco, app bar compacta, estado/progreso en tarjetas legibles, tool invocations agrupadas por paso y compositor persistente. No copiar widgets, código Dart, assets, ilustraciones, iconos o diseño pixel-perfect de Kelivo; toda la implementación Compose y tokens visuales serán originales de Jarvys. Screenshot 3 de terminal/workspace no se porta a Stage E (Jarvys no ejecuta workspace shell); screenshot 4 solo inspira cards con referencias si un MCP result incluye resource links.

### Mapa de pantallas

| Destino Compose | Contenido y acciones | Origen/reuso Stage A–D |
|---|---|---|
| **Inicio / conversaciones** | Lista de tareas/corridas recientes, botón nueva tarea, estado del provider actual, indicador de server/skill activo. Abrir run reanuda su conversación local si existe. | Nuevo: hoy `MainActivity` es una sola página; `LocalRunStore` ya guarda runs/steps pero no una lista de conversaciones con mensajes. |
| **Chat + AgentRunScreen** | Mensajes user/assistant, input inferior, adjuntos, seleccionar provider/model, habilitar skills/MCP tools; panel expandible Plan/turnos; cards de herramientas con server, args/result, duración, estado y contenido text/image/resource. Botón cancelar visible y live progress. | Reemplaza la lista de controles actuales; reutiliza `AgentLoop`, `AgentRunResult`, `LocalRunStore`, `ProviderSettings`, `SecretStore`; añade modelo de Conversation/Message si se persiste chat, no sobrescribir los ledgers de step.
| **Providers** | Tabs OpenAI Codex OAuth / OpenRouter API key, modelo, conexión/expiración, iniciar/terminar OAuth, status; key enmascarada, acción guardar/olvidar, no se lee de vuelta a UI. | Mapea los controles actuales de `MainActivity.java:70-132`; reusa `CodexOAuthManager`, `ProviderSettings`, `SecretStore` sin cambiar sus contratos.
| **MCP Servers** | Lista de configs: alias, URL, Streamable HTTP/legacy SSE, auth, estado disconnected/connecting/ready/error, tools count. Detalle muestra serverInfo/instructions, tools name/description/schema, toggle de exposición por tool; agregar/editar/desconectar/borrar/test connection. | Nuevo `McpServerRepository`/`McpConnectionManager`; fixed tools se muestran aparte y nunca se borran al desconectar.
| **Connectors** | Catalog/configuration de integraciones nativas revisadas; inicialmente empty state “No hay connectors configurados” más descripción de qué es connector y botón añadir cuando existan definiciones reales. Config de scopes, cuenta conectada, revoke/delete. | Nuevo; no ofrecer integraciones de ejemplo como si funcionaran.
| **Skills** | Browser buscar/filtrar por tags, habilitar por corrida/assistant, ver metadata/body, importar `.md`, activar/desactivar, borrar/actualizar; mostrar tools permitidos y validación de schema. | Nuevo `SkillRepository`; no confundir skills con server MCP ni modificar tools fijas.
| **App settings / permisos** | Permiso Overlay STOP, Accesibilidad activa, versión mínima screenshot API 30+, datos/red/provider y explicación de contenido que se envía al LLM/server MCP seleccionado. | Reusa estado actual de `MainActivity.java:188-239,313-339` y `ArtemisAccessibilityService.getInstance()`.
| **Overlay STOP** | Botón rojo flotante siempre visible, status breve y accesible mientras AgentForegroundService corre. | Mantener `StopOverlayService`/WindowManager y el callback sin cambios semánticos, migrar el contenido visible a un `ComposeView` con `LifecycleOwner` apropiado; el onClick invoca primero el mismo latch síncrono. Esto requiere integrar Lifecycle Compose en un Service y probar el mismo comportamiento STOP. |

### Migración y puntos de integración (para código posterior a aprobación)

1. Introducir Kotlin Android plugin + Compose compiler plugin correspondiente a la versión Kotlin, Compose BOM/Material 3, `activity-compose`, Navigation Compose y Lifecycle ViewModel Compose; versión exacta pinada mediante una build con AGP 8.13.2, Gradle 8.13, compileSdk 36. Mantener el resto de lógica Java e integrar Kotlin a través de APIs públicas/Java-friendly. Hoy no hay Kotlin/Compose en `app/build.gradle.kts:1-46`.
2. Reemplazar contenido de `MainActivity.java` por `MainActivity.kt` conservando FQCN de manifest y flujos actuales OAuth/provider/goal; evitar dos clases con mismo package/name. `SecretStore`, `ProviderSettings`, `CodexOAuthManager`, `AgentLoop`, `ToolRegistry`, driver y Services permanecen Java.
3. Añadir `JarvysUiViewModel`/`RunStateRepository` para publicar `StateFlow<RunUiState>` (o equivalente lifecycle-aware) desde `AgentForegroundService`/AgentLoop; hoy progress listener actualiza overlay y resultado se conserva en `lastRunReport`, no existe stream para chat UI (`AgentForegroundService.java:185-205`; `AgentRunResult.java:5-40`). Compose no debe sondear static fields ni ejecutar llamadas de red en Main thread.
4. MCP: nuevos `McpServerRepository`, `McpConnectionManager`, transport clients y OAuth por-server. Cambios puntuales requeridos en `AgentLoop` (descubrir/cerrar sesiones por corrida), `ProviderAgentModel` (fusionar schema por role), `ToolRegistry` (composición fijo+dinámico sin cambiar `EXPECTED_INVENTORY`), `ToolContext` (dispatcher MCP) y `SecretStore` (serverId→OAuth/API secrets cifrados). Lista de MCP tools dinámica se reconstruye para cada conexión/notifications `list_changed`.
5. Conectores/skills: añadir `ConnectorRegistry` y `SkillRepository` como capas separadas. `ToolRegistry` mantiene invocación unificada y procedencia (`core`, `mcp server`, `connector`), pero MCP dynamic tools y connector tools no forman parte de los 51 nombres base. Skill seleccionada solo altera contexto; no muta el registry.
6. `StopController` y `CancellationToken` no se reemplazan. `StopOverlayService` conserva tipo WindowManager/foreground y callback STOP; la integración visual planificada es ComposeView con owner de lifecycle, sin mover el latch a una coroutine ni debilitar su sincronía.

## 6. Propuesta de alcance E y cuestiones para aprobar

1. **Transports**: Streamable HTTP y legacy HTTP+SSE sí; stdio se declara explícitamente unsupported en Android porque requiere lanzar un proceso MCP. ¿Alcanza este alcance para servers externos que Jarvys pueda conectar por URL?
2. **Auth MCP**: incluir bearer token por server y OAuth 2.1 discovery/PKCE/resource parameter en credenciales separadas y cifradas. Implementar client-ID preregistrado y Dynamic Client Registration si el AS lo ofrece; Jarvys no tiene un dominio HTTPS propio donde hospedar un Client ID Metadata Document. Para servers OAuth que solo permiten ese método, la conexión requiere configuración/pre-registro del propietario del server.
3. **Connectors**: Artemis no tiene concepto general ni servicios concretos; la definición arriba es propuesta. ¿Apruebas un registry vacío + UI de arquitectura, dejando lista de integraciones Gmail/Calendar/otras para aprobación posterior?
4. **Skills**: formato Markdown/frontmatter, user import local y no ejecutable es propuesta nueva; ¿apruebas el schema definido y las fuentes locales iniciales (assets + file picker), sin GitHub import en primer port?
5. **Herramientas externas**: mostrar servidor/schema, habilitar por tool y preservar resultados text/image/resource links; anotaciones server-side son no confiables. No autoaprobar primera conexión. Agregado aprobado: toggle explícito “Confiar en servidor MCP inseguro” permite HTTP/IP custom más allá de loopback/LAN solo por consentimiento manual por servidor.
6. **Overlay Compose**: propuesta conservar la ventana/servicio actual, pero renderizar su contenido con `ComposeView` y un `LifecycleOwner` del Service; el STOP handler se mantiene sincrónico y debe repetir la validación física de Etapa A.
7. **Estado efectivo de E**: no se escribió código de Etapa E; los cambios enumerados a archivos A–D son puntos de integración anticipados, no aplicados. Implementación debe empezar solamente tras aprobar `DESIGN_E.md` y resolver scope de conectores/skills/transport/auth anterior.

## 7. Fuentes verificadas

- Artemis internal in-memory MCP session: `artemis/mcp/action_session.py:15-47,50-58,77-116,143-200,216-245`.
- Artemis action server, capability-filtered tools/extensions: `artemis/mcp/action_server.py:15-30,69-90,92-191`; actuator extensions: `artemis/mcp/actuators/base.py:15-29,40-55`.
- Artemis CLI modes/config: `artemis/interfaces/cli/commands/mcp.py:64-75,694-724,827-855`; MCP stdio server: `mcp_server/server.py:15-17,82-103`; MCP version `mcp==1.29.0`: `uv.lock:2553-2574`; exported remote agent tool names: `mcp_server/tools/__init__.py:17-28`.
- Fixed tool inventory/integration seam: `app/src/main/java/com/jarvys/agent/ToolRegistry.java:21-47,58-70`; AgentLoop/provider role and services: `AgentLoop.java:15-62`, `ProviderAgentModel.java:21-47`, `AgentForegroundService.java:185-205`.
- Existing UI/provider/overlay: `MainActivity.java:22-47,49-185`; `ProviderSettings.java:7-39`; `SecretStore.java:9-23`; `StopOverlayService.java:16-50,54-108`.
- MCP standards: [2025-11-25 Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), [Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle), [Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools), [Authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization), and [legacy 2024-11-05 HTTP+SSE](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports).
- Official Java SDK compatibility reference: [modelcontextprotocol/java-sdk README](https://github.com/modelcontextprotocol/java-sdk) (Java 17+, default JDK HttpClient transport). Proposed Android implementation avoids assuming that server-oriented/JDK transport works in this app.
- Visual reference only: [Kelivo README/screenshots](https://github.com/Chevey339/kelivo#-screenshots), [Kelivo LICENSE](https://github.com/Chevey339/kelivo/blob/master/LICENSE). README labels Flutter; reference is layout inspiration only, no code/assets copied.
- Android Compose setup reference (dependency/plugin verification for later implementation): [Jetpack Compose setup](https://developer.android.com/develop/ui/compose/setup).
- Architecture-pattern study only (no code/assets copied due GPL-3.0): [gpt_mobile MCP manager](https://github.com/Taewan-P/gpt_mobile/blob/main/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt), `McpToolMapper.kt`, `McpOAuthClient.kt`. Patterns reviewed: per-server session ownership, bounded cursor/tool discovery, transport/session validation and separate OAuth client.
- Architecture-pattern study only (no code/assets copied due AGPL-3.0): [RikkaHub MCP manager/session registry/config and settings UI](https://github.com/rikkahub/rikkahub/tree/master/app/src/main/java/me/rerere/rikkahub/data/ai/mcp). Patterns reviewed: config/status/session/OAuth responsibilities split; Compose server cards, per-server settings, per-tool enable switches, expanded schema detail.
