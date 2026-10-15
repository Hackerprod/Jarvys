# Módulos de presentación (guía de diseño e implementación)

Estado: **diseño aprobado, sin implementar** (2026-10-14). Prioridad acordada: migración SQLite del ledger → agente personal → este sistema. Este documento es la fuente de verdad para quien lo implemente (arquitecto, Codex) y para quien lo valide (Opus).

## 1. Objetivo

Hoy el chat solo sabe mostrar de forma rica un HTML (card + preview), imágenes generadas y archivos. El agente debe poder crear y mostrar **muchos más formatos** (PowerPoint, gráficas, canvas, diagramas, mapas…) mediante un sistema **modular y genérico**: añadir un formato nuevo = añadir un módulo, **sin tocar el core**.

Un módulo reúne tres cosas:

1. **Cómo producirlo**: herramientas (tools) que el módulo declara + una skill que enseña al agente a usarlas.
2. **Qué entiende**: los tipos de artefacto que crea o muestra.
3. **Cómo mostrarlo**: tarjeta en el chat (miniatura, título, acciones) + visor a pantalla completa.

## 2. Decisiones del dueño (no reabrir sin preguntarle)

- Visor JS **aproximado** de PowerPoint es suficiente al principio (no se exige fidelidad alta).
- **Solo módulos internos** por ahora (incluidos en la app). No instalables por terceros. Mantener, aun así, el aislamiento (ver §6).
- El **contenedor Linux (PRoot) NO puede ser dependencia**: es solo para devs bajo petición; un usuario medio no debe instalar Linux para que funcionen los módulos. Prohibido apoyarse en python/node/shell del contenedor.
- **Las tools de módulos no se codifican en el core.** Está descartado un `create_presentation` en el core. El módulo **declara su esquema de tools** (análogo a cómo MCP declara las suyas) y aporta la lógica restante.
- Sin hardcodeos por herramienta/módulo en lógica genérica; sin topes numéricos inventados; tests no debilitados (reglas globales del proyecto).

## 3. Cómo está hoy (puntos de contacto verificados en el código)

| Pieza | Dónde | Observación |
|---|---|---|
| Despacho de tarjetas del chat | `app/app/src/main/java/com/jarvys/agent/ui/chat/ConversationTimeline.kt` (~358-374) | `when(event.kind)` fijo: `user_decision`, `delivered_file`, `generated_image`, tarjeta de tarea programada. |
| Tipos de adjunto | `ChatAttachment.java` (`Kind { IMAGE, FILE }`) | Solo dos tipos. |
| Entrega de archivos | `DeliverFileTool.java` (`deliver_file`) | HTML es un caso especial: captura estática de assets, `HtmlPreviewDescriptor` (`html-snapshot:<uuid>`), miniatura `HtmlArtifactThumbnail.kt`, pantalla `WorkspacePreviewScreen.kt`, políticas `PreviewResponsePolicy.kt`. |
| Tools de terceros (patrón a reutilizar) | `CoreMcpTool.java` implementa `CoreTool`; registro en `CoreAgentRuntime.java` (~781, `McpServerToolRegistry.all()`); definición `McpToolDefinition` en `mcp/McpServerModels.kt` (~86) con `inputSchema`, `access` (READ/WRITE), `annotations`, política heredada. | El core no sabe qué hace cada tool: valida con el esquema, aplica política de autonomía y ejecuta. |
| Política de autonomía (Permitir/Preguntar/Denegar) | `connectors/AutonomyPolicyRow.kt`, `ActionPolicyDefaults.kt`, cola de aprobaciones de tareas programadas | Genérica por `connectorId` + `operation.name`. |
| Acoplamiento a eliminar | `CoreAgentRuntime.java` ~1567, ~1631, ~1640: `instanceof CoreMcpTool` para aplicar la política | Hay que extraer una interfaz (§5.1). |

## 4. Arquitectura

### 4.1 Manifiesto del módulo (`manifest.json`)

```jsonc
{
  "schemaVersion": 1,
  "id": "com.jarvys.module.presentation",   // también connectorId para políticas
  "version": "1.0.0",
  "name": "Presentaciones",
  "artifactTypes": [
    { "type": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "viewer": "viewer/deck.html", "thumbnail": "thumb" }
  ],
  "tools": [                                   // MISMO formato conceptual que MCP
    { "name": "create_presentation",           // nombre lo decide el módulo, nunca el core
      "description": "…",
      "inputSchema": { "type": "object", "properties": { … }, "required": [ … ] },
      "access": "write",                       // read | write → alimenta política y modo de ejecución
      "handler": "logic/tools.js#createPresentation" }
  ],
  "skills": [ "skills/presentation/SKILL.md" ], // enseñan al agente a usar las tools (sin shell/Linux)
  "capabilities": [                            // lo que el handler puede pedir al anfitrión
    "workspace.read", "workspace.write", "artifact.deliver"
  ]                                            // red: NINGUNA por defecto
}
```

### 4.2 Piezas genéricas del core (lo ÚNICO que el core aporta)

1. **`ModuleRegistry`**: carga manifiestos incluidos en la app, valida el esquema del manifiesto, expone módulos por id y por tipo de artefacto.
2. **`ModuleTool`**: un único adaptador `CoreTool` para TODAS las tools de módulos (equivale a `CoreMcpTool`). Declaración (`ToolSpec`) construida desde el manifiesto; argumentos validados con el validador genérico existente (`ToolArgumentValidator`); `access` decide `ExecutionMode` y `UnattendedAccess`.
3. **Runtime JS en sandbox** que ejecuta los `handler`s con una **API anfitriona mínima y acotada** (ver §4.4).
4. **Evento genérico `artifact`** en el chat y tabla `artifacts` en SQLite (§7).
5. **Registro de visores**: dado un artefacto, el core elige módulo por `artifactType` y delega la tarjeta y el visor.

Nada de esto menciona pptx, gráficas, ni ninguna tool concreta.

### 4.3 Política: el módulo se comporta como un conector

El módulo = `connectorId`; cada tool = operación. Las filas Permitir/Preguntar/Denegar y la cola de aprobación para tareas programadas **ya son genéricas**: los módulos aparecen en Conectores con sus políticas sin código nuevo. El valor por defecto sale de `access` (read → permitir, write → preguntar, igual que MCP).

### 4.4 Runtime JS y API anfitriona

- **WebView aislado** como motor principal: tiene Blob/TextEncoder/Canvas/DOM, que necesitan librerías como pptxgenjs, JSZip, Chart.js, Mermaid. Dos modos: **fuera de pantalla** (generar) y **visible** (visor).
- **QuickJS** (p. ej. Zipline o quickjs-kt) es una opción **posterior**, solo para lógica sin pantalla; no incluir en la primera fase. Antes de elegir wrapper, verificar en la versión vigente que expone límite de memoria e interrupción por tiempo.
- API anfitriona (puente con lista blanca; cada llamada comprueba que el módulo declaró la capacidad): `workspace.readFile/writeFile` (ámbito de la conversación), `artifact.deliver(path|bytes, type, title, manifest?)`, `progress(text)`, `log`. Sin red, sin acceso a memoria, credenciales, otras conversaciones ni skills de usuario.
- **Ciclo de vida**: el WebView fuera de pantalla necesita anfitrión con UI. Para herramientas de módulos en tareas programadas sin la app visible hay que decidir: servicio con WebView, o diferir. Decisión pendiente (§9).

### 4.5 Producir sin Linux

- **PowerPoint**: (opción 1, preferida) `pptxgenjs` + JSZip empaquetados como asset del módulo, ejecutados en WebView fuera de pantalla; (opción 2) escritor OOXML mínimo en Kotlin (un `.pptx` es un zip de XML) para presentaciones simples. Ninguna depende de python ni de shell.
- **Visor de pptx**: visor JS aproximado (librería de renderizado pptx→HTML/canvas) en el WebView del visor; miniatura = primera diapositiva.
- **Otros formatos**: gráficas (Chart.js o Vega-Lite), diagramas (Mermaid), canvas, markdown enriquecido — cada uno es una carpeta de módulo con manifest + visor + skill.

### 4.6 Tarjeta y visor

- La tarjeta en el chat muestra miniatura + título + icono de tipo + acciones (Abrir, Descargar, Compartir). **No** montar WebViews vivos en la lista perezosa: miniatura hasta tocar (como ya hace `HtmlArtifactThumbnail`). El visor se abre en pantalla completa.
- Descargar/compartir reutilizan los flujos existentes (`ChatFileTransfers`, `ChatFileProvider`).

## 5. Refactors previos en el core (fase 1)

1. **Interfaz `PolicyGatedTool`** (política efectiva, ejecución en tarea programada, requester) implementada por `CoreMcpTool` y `ModuleTool`; sustituye los tres `instanceof CoreMcpTool` de `CoreAgentRuntime`. Sin cambio de comportamiento para MCP (tests existentes de MCP/políticas deben seguir pasando).
2. **Evento `artifact` genérico**: migrar `delivered_file` (imagen, archivo, HTML) a módulos internos sin cambiar lo que ve el usuario. Esto demuestra la interfaz antes de añadir formatos.
3. `ChatAttachment.Kind` deja de ser la fuente de verdad del tipo de presentación; el tipo viene de `artifact_type`.

## 6. Seguridad

- Aunque hoy solo hay módulos internos, el handler y el visor corren **aislados**: WebView sin acceso a archivos fuera del bundle del módulo y del artefacto, sin red salvo capacidad declarada, puente con lista blanca, `PreviewResponsePolicy` como base (CSP estricta).
- Cada capacidad se valida en el anfitrión, no en el JS. Un handler no puede escribir fuera del ámbito de la conversación ni acceder a otros chats.
- Si algún día se admiten módulos de terceros: firma/verificación del paquete, permisos mostrados al usuario y sin código nativo. **Fuera de alcance ahora.**

## 7. Relación con la migración SQLite (`app/docs/chat-db/`)

- `delivered_files` se generaliza a **`artifacts`**: `artifact_type`, `module_id`, `manifest_json` (opaco por naturaleza, justificado), `title`, `thumbnail_ref`; idempotencia por `(conversation_id, artifact_id)` con comparación de contenido; los **archivos siguen en disco** (DB guarda ruta relativa, sha, tamaño, mime en `file_refs`). Se hace en M1b (rama `feat/chat-sqlite`).
- La implementación de módulos (fases 1-4) va **después** de M3/M4 de la migración, porque la fase 1 reescribe la misma proyección del timeline que M3/M4.

## 8. Fases, entregables y criterios de aceptación

| Fase | Entregable | Aceptación (lo que Opus valida) |
|---|---|---|
| 1 | `PolicyGatedTool`, evento `artifact`, `ModuleRegistry`, `ModuleTool`; HTML/imagen/archivo migrados a módulos internos | Comportamiento idéntico al actual (tests existentes de preview, `ToolTimeline*`, MCP y políticas pasan sin debilitarse); `grep` no encuentra nombres de tools de módulos en el core; el `when(kind)` del timeline ya no enumera formatos. |
| 2 | Host de WebView (fuera de pantalla y visible) con puente y capacidades; cargador de manifiestos con validación | Un handler solo puede lo que declaró; test de intentos denegados (red, ruta fuera de ámbito, otro chat); aislamiento verificado. |
| 3 | Primeros módulos: **gráficas**, **diagramas (Mermaid)**, **presentaciones** | Un agente crea una presentación solo con las tools declaradas por el módulo, sin Linux; la tarjeta muestra miniatura; el visor abre; descargar/compartir funcionan; política Permitir/Preguntar/Denegar aparece en Conectores sin código específico. |
| 4 | Skills empaquetadas por módulo | El agente descubre y usa las tools correctas guiado por la skill; sin referencias a shell/Linux. |

Pruebas mínimas por fase: unitarias del registro/validación de manifiestos, del adaptador `ModuleTool` (esquema, política, ejecución en tarea programada), del puente (capacidades), de proyección del timeline (evento `artifact` genérico, persistencia/restauración desde SQLite), y de UI (tarjeta, visor, ausencia de WebView vivo en la lista).

## 9. Decisiones abiertas

1. Herramientas de módulos en **tareas programadas sin la app visible**: ¿servicio con WebView o diferir?
2. Librería concreta del visor pptx (fidelidad vs tamaño) y de generación (pptxgenjs vs escritor OOXML nativo).
3. Cuándo introducir QuickJS (solo si aparece un módulo con lógica sin pantalla).
4. Convención de nombres de tools de módulos para evitar colisiones (prefijo por módulo, como el namespace de MCP en `McpServerToolRegistry.namespace`).

## 10. Riesgos

- WebViews pesan: nunca vivos en la lista; límite de WebViews fuera de pantalla simultáneos controlado por el anfitrión.
- Crecimiento del catálogo de tools visible al modelo con muchos módulos: mismo problema que MCP; mitigar con ámbito de skills.
- Alcance sin fin: respetar las fases; cada formato nuevo es un módulo, no una excepción en el core.

## 11. Guía para el validador (Opus)

Comprobar, con evidencia archivo:línea: (a) ningún nombre de tool de módulo ni tipo de formato concreto en el core; (b) `ModuleTool` es el único adaptador y comparte `PolicyGatedTool` con MCP; (c) las políticas por tool aparecen vía el sistema genérico de conectores; (d) el handler/visor están aislados y el puente aplica la lista blanca de capacidades; (e) nada depende del contenedor Linux; (f) los tests existentes no se debilitaron; (g) los artefactos persisten en la tabla `artifacts` con archivos en disco; (h) restauración del chat tras reinicio muestra las tarjetas igual.
