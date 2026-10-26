# S6 — inventario de herramientas y recibos pendientes

Fecha: 2026-10-26. Rama de trabajo: `continue/s6-cloud`, baseline comunicado `42560900`.
**Inspección de fuente, no implementación ni PASS.** Se inspeccionó el árbol de trabajo mientras otros agentes editaban núcleo/gates; las observaciones describen ese snapshot, no una versión final validada. Este documento no cambia permisos ni clasificaciones de producto.

Referencias: `Continue.md`, `docs/SINGLE_SESSION.md` §7.5 (párrafo de clasificación y exclusiones, alrededor de líneas 426–427), S6 y B3; `S6-admission-contract.md`.

Alcance: filesystem cloud. Sin instalación/aceptación de licencias SDK, build Android, desktop, VPS, APK ni despliegue. No se ejecutaron tests en este inventario. No se afirma cobertura completa de S6 ni paridad Engram; la paridad completa es posterior. No se encontraron instrucciones adicionales `AGENTS.md` ni skills locales `.agents/skills` en el checkout inspeccionado.

## Actualización del checkpoint local 992d266d

El inventario inicial de las secciones siguientes se conserva como diagnóstico del punto de partida,
no como descripción de todos los candidatos actuales. Se cerraron estáticamente estos puntos:

- Workspace write/edit: admisión explícita antes del store y recibo local después de mutación e indexación.
- Workspace `/memory/` y vista memory-only: exclusión sensible a argumentos y recibo administrativo
  MEMORY distinto de READ; delete mantiene la exclusión. La normalización del store gobierna los alias.
- Router Coding: contexto explícito, sin segunda resolución/sellado; cursor es lectura. El productor
  Coding legacy aún no certifica una mutación con recibo propio.
- Wrappers de tareas, proactive y reflection: propagan clasificación sensible a argumentos y contexto.
  Claims/recovery conservan MEMORY y bloquean drift hacia WRITE externo.

Revisión independiente: sin bloqueos estáticos adicionales en ese corte; 15 tests JVM puros PASS.
Seis pruebas workspace y seis de MEMORY/claims están preparadas y revisadas, **no ejecutadas en Android**.
Linux síncrono y el contrato separado de submission/terminal Coding son trabajo posterior en curso;
ninguna fila abierta se considera resuelta por el mero inicio de su implementación.

## 1. Registro principal real

`CoreAgentRuntime.createTools()` ensambla estas familias, con condiciones de disponibilidad, profundidad y capabilities. La prueba debe activar las ramas; un runtime sin Context no enumera todo el producto.

| Productor | Nombres | Clasificación observada / decisión pendiente |
|---|---|---|
| `WorkspaceTools.createChat` | `ls`, `read`, `write`, `edit`, `preview_workspace`, `search_files`; `delete` según memoria; `deliver_file` según disponibilidad | write/edit/delete derivan WRITE de unattendedAccess. ls/read/search READ. Preview hereda READ aunque captura y entrega un artefacto; necesita clasificación explícita. |
| Nativas de chat/bots | `import_project_image`, `app_diagnostics`, `mascot_animation`, `list_bots`, `create_bot`, `compile_bot_mascot` | Import/create/compile explícitamente WRITE; diagnostics/list READ. Animation modifica UI efímera: documentar exclusión interna explícita, no tratar el default como decisión. |
| Rama de imágenes | `generate_image`, `generate_bot_icon`, `list_image_references` | Las dos primeras WRITE; listing READ. |
| Skills/web | `read_skill`, `web_search`, `fetch_url` | READ. |
| Delegación | `delegate_subtask` | Hereda READ; decisión de coordinación pendiente (§3). |
| Decisión/tareas | `request_user_decision`, `schedule_task`, `list_tasks`, `update_task`, `cancel_task`, `task_runs`, `run_task` | `TaskManagementTools.Tool`: list/runs READ; demás tareas WRITE. UserDecision excluida expresamente por la especificación. |
| Memoria personal | `user_profile_set`, `memory_save`, `memory_search`, `memory_get`, `memory_forget`, `memory_correct`, `history_search`, `history_read` | Mutaciones WRITE+MEMORY; search/get READ+MEMORY; history READ+NONE. Factory de consolidación añade `memory_consolidate`: WRITE+MEMORY. |
| Proactivo | `proactive_status` | READ. |
| Adaptadores dinámicos | `CoreConnectorTool`, `CoreMcpTool`, `ModuleTool` | operación.write, McpToolAccess y ModuleToolAccess determinan efecto. Comprobar declaración real de cada operación, no aceptar nombres arbitrarios por prefijo. |
| Full, `FlavorLinuxTools.createForRuntime` | `linux_status`, `linux_setup`, `linux_exec`, `linux_uninstall` | Todos heredan READ en el snapshot. Status es lectura; setup/exec/uninstall tienen efectos y gates reales. Play devuelve lista vacía. |

## 2. Superficies añadidas fuera de createTools

Un contrato que solo recorra `createTools()` no cierra el inventario.

| Superficie | Clasificación y riesgo |
|---|---|
| `CentralWorkspaceTool`, añadido por runtime | `effectClass(arguments)` WRITE para create/delegate/deliver/reply/message; otras acciones READ. Mantener matriz completa de acciones. |
| `CodingProjectTools.Tool`, herramientas de bot/proyecto y router | Marca TODOS los nombres WRITE: ls/read/coding_grep/coding_glob son lecturas. write/edit/coding_patch mutan; coding_adopt review es lectura y apply mutación. `result_cursor` recupera recibos, no repite la mutación. |
| `ApkFactoryTools.FactoryTool` | Marca todas las acciones WRITE. inspect/preview_status/install_status son lecturas; build/sign/preview/test/install/install_cancel requieren clasificación y semántica de resultado individual. Aceptar solicitud de instalación no prueba instalación terminada. |
| `ProjectImageTool` | WRITE explícito; generaciones/copias necesitan recibo propio. |
| Full `CodingExecutionTools.Tool` | Todos heredan READ: project_exec debe tratar ejecución; project_jobs cancel muta, list/read/wait leen; project_environment_status lee. Play no ofrece ejecución. |
| `CrewTools.captain` | crew_spawn, crew_send, crew_wait, crew_list, crew_stop; heredan READ. Decisiones pendientes en §3. |
| `CrewTools.bot` | msg_send, board_post, board_read, ask_chief, report_done; heredan READ. Decisiones pendientes en §3. |
| `MainChatTranscriptStore.recoveryTool` / `CrewContextArtifacts.recoveryTool` | read_conversation_artifact / read_crew_artifact: READ. |
| `MessageReactionTool` | Exclusión interna expresamente decidida por la especificación, aunque hoy se obtiene por READ heredado. |
| `ProactiveRespondCoreTool` | READ heredado; entrega una decisión al sink, no confundirla con entrega de notificación. Véase §3. |

## 3. Exclusiones explícitas y decisiones abiertas

La especificación excluye `PersistenceEffect.MEMORY` y coordinación **puramente interna**, citando `UserDecisionTool` y `MessageReactionTool`. No autoriza deducir que cualquier clase Crew/delegate/proactive es una lectura porque carece de override. Tampoco obliga a convertir toda coordinación en efecto externo.

- **UserDecision y Reaction:** exclusión ya decidida; manifest con razón explícita, sin episodio.
- **Crew:** crew_list/board_read son lecturas; crew_wait espera/consume coordinación. crew_spawn crea trabajo, crew_send puede reactivar un ciclo, crew_stop cancela, msg_send admite mensajes, board_post persiste un archivo, ask_chief envía/espera una pregunta, report_done publica resultado y termina ciclo. Falta decidir cuáles son coordinación interna excluida y cuáles merecen efecto propio. Incluso si se excluye la coordinación, los efectos de las herramientas hijas no pueden desaparecer ni contarse dos veces; la evidencia de misión `crew_result` tiene su contrato propio.
- **DelegateSubtask:** ejecuta runner/subrun con capabilities limitadas. Su recibo JSON describe estado de runtime y declara `task_verified=false`; COMPLETED no certifica efecto externo ni resultado de tarea. Una exclusión de coordinación requiere demostrar captura/binding de efectos hijos, no silenciarlos. No marcar la delegación OK por mero success del runner.
- **ProactiveRespond:** `execute` llama `decisionSink.submit`; `ProactiveAgentProcessor.review` posteriormente guarda auditoría y, si notify, añade mensaje en Central y puede llamar `ProactiveNotifier.post`. Clasificar la presentación de decisión como completitud de notificación sería falso. Decidir si la decisión es coordinación excluida y cómo representar, si corresponde, el efecto del productor posterior en su frontera real. notify=false no tiene el mismo resultado que notify=true.

Estas decisiones quedan **abiertas**, no implementadas ni resueltas mediante default READ.

## 4. Productores nativos sin recibo explícito de efecto

Devuelven `CoreToolResult.success` u otra factory legacy tras rutas con efectos:

- `WorkspaceTools.WriteFile.execute`, `EditFile.execute`, `DeleteMemoryFile.execute`.
- `WorkspaceTools.Preview.execute` -> `DeliverFileTool.deliveryResult`; `DeliverFileTool.execute/deliveryResult`.
- `ImportProjectImageTool.execute`, `ProjectImageTool.execute` (generación/copia), `CodexImageGenerationTool.execute`, `BotIconGenerationTool.execute`.
- Resultado de creación de `BotCreationTool`; `BotMascotTool.execute`.
- `CentralWorkspaceTool.execute`.
- `TaskManagementTools.success` usado por productores mutantes.
- `CodingProjectTools.Binding.receipt`, `Tool.execute` y adoption apply. Las páginas de recuperación también devuelven success, pero son lecturas.
- `ApkFactoryTools.FactoryTool.execute`.
- Full `LinuxSetupTool.execute`, `LinuxExecTool.execute`, `LinuxUninstallTool.execute`.
- Full `CodingExecutionTools.exec` y cancelación de jobs.
- Implementaciones mutantes de `CrewTools.run`; report_done usa `CoreToolResult.finish`.
- `DelegateSubtaskTool.receipt` devuelve success/failure de estado del subrun, no recibo de efecto.

En WRITE ya clasificada, el núcleo nuevo trata ausencia de recibo como **UNCERTAIN**, correctamente, nunca OK por success. En READ heredada errónea ni siquiera se registra episodio. No arreglar esto promoviendo automáticamente success/failure a recibo para todos los productores: las rutas administrativas, parciales, diferidas y de recuperación tienen significados distintos.

## 5. Fronteras y wrappers que no deben perderse

### Full Linux: no falso ALLOW

Linux setup/exec/uninstall y ejecución de proyecto poseen autorización real, pero no se identifican como connector/MCP/PolicyGatedTool. Cambiar solamente effectClass a WRITE permitiría que el default actual `hasCompleteEffectAdmissionCoverage()` los tratase como nativos cubiertos y admitiera ALLOW **antes** de su gate. Hace falta transporte explícito de invocación y snapshot en la frontera real; conservar STOP/política/leases. No afirmar cobertura por ausencia de marker interface.

### Memoria legacy

`PersonalMemoryTools.persistenceEffect` excluye explícitamente memoria. En cambio, `WorkspaceTools` write/edit/delete sobre `/memory/` conservan NONE; especialmente la vista memory-only de tareas puede registrar mantenimiento como episodio. DeleteMemoryFile necesita exclusión explícita; write/edit mixtas requieren clasificación por ruta o por vista del store sin excluir escrituras reales de proyecto. La API actual persistenceEffect sin argumentos requiere diseño deliberado; no ocultar esto marcando toda la familia MEMORY.

### Router de proyecto anidado

`CodingWorkspaceRouter.invoke` construye otro `CoreToolRegistry` y reenvía argumentos mediante invoke legacy, sin contexto externo explícito. Verificar propagación del recibo del productor, binding único y ausencia de doble conteo. `result_cursor` lee recibo, no vuelve a mutar. La clasificación excesiva de CodingProjectTools no debe contaminar lecturas del wrapper WorkspaceTools.

### Wrappers

Comprobar `CoreAgentRuntime.ScheduledTaskBoundTool`, `ScheduledTaskProcessor.TaskTrackedTool`, wrapper de `ProactiveAgentProcessor`, anónimo de `MemoryReflectionWorker`; además guard de crew, registry subset/rebinding y router. Delegar effectClass con/sin argumentos, persistenceEffect, cobertura real de admisión y contexto idéntico; redacción debe conservar receipt, success, flags, previewId y persistenceEvidence.

## 6. Contratos de prueba necesarios

1. Manifest exacto de nombre **y familia de implementación**, razón de exclusión y variantes de argumentos, recorriendo registros reales Full/Play con todas las ramas habilitadas. Un nombre/clase nuevo sin decisión explícita falla. No depender de un solo runtime sin Context.
2. Manifest adicional de herramientas añadidas al run, crew/proyecto, proactive y recovery. `ls/read/write/edit` tienen implementaciones distintas: comparar solo nombres es insuficiente.
3. Fixtures dinámicos connector/MCP/module verifican cada declaración de acceso contra effectClass, sin whitelist permisiva por prefijo.
4. Matrices de acciones de workspace, adoption/cursor, APK, project_jobs y memoria legacy. Lectura y memoria no generan episodios.
5. Equivalencia de wrappers en clasificación, exclusión, cobertura e identidad; preservación de todas las factories y copia/redacción.
6. B3 con WorkspaceStore temporal real y Room: started durable previo al efecto; recibo explícito completado -> OK; falta de recibo -> UNCERTAIN; fallo durable/denegación -> cero efectos. No reemplazar adaptadores reales por fake CoreTool para declarar B3 completo.
7. Gates Full con ASK/ALLOW/DENY, cancelación y excepción posterior: nunca falso native ALLOW ni éxito administrativo como efecto completado; distinguir job aceptado de comando terminado.
8. Mutaciones solo en copia temporal: herramienta sin inventariar/entrada eliminada debe fallar; perder callback/receipt o invertir clasificación/autorización debe romper prueba focal.

**Pendiente:** decisiones de §3, cambios de productores/inventario ejecutable, revisión independiente de gates y validación Android/Room/KSP cuando exista SDK autorizado. Este documento no certifica ninguno de ellos.
