# S6 — contrato propuesto de admisión y resultado por invocación

Estado: **diseño para el siguiente bloque; no implementación ni validación Android**.
Fecha: 2026-10-26. Referencia normativa: `docs/SINGLE_SESSION.md` §§7.3, 7.5 y contratos B3/B4/B5.
Este documento define criterios de revisión independiente. No amplía permisos, alcance de herramientas ni autoridad del usuario.

## 1. Problemas observados en el código actual

- `CoreToolRegistry.invoke` notifica `beforeDispatch` antes de ejecutar el adaptador, pero la aprobación del conector/MCP sucede dentro de ese adaptador. Ese hook no demuestra admisión del efecto.
- `EpisodeRecorder` lee la política configurada y deja ASK como `ask_pending`; no recibe la decisión aprobada. Herramientas nativas sin gate acaban también en `ask_pending` por `UNSPECIFIED`.
- `CoreToolResult.success` expresa éxito de la llamada, no necesariamente efecto: los adaptadores también devuelven success para aprobación pendiente y espera de app visible. No existe aún un recibo general tipado de efecto/autorización.
- Los wrappers reconstruyen `CoreToolResult` al redactar contenido y pueden perder metadatos nuevos. El scheduler interpreta algunos estados JSON propios; esto no debe convertirse en análisis heurístico de texto externo.
- Una excepción posterior a un dispatch puede dejar un efecto real; no equivale a error sin efecto. Tampoco una fila de `tool_results` (incluidos placeholders) prueba éxito.

## 2. Invariantes obligatorias

### I1. Identidad y aislamiento

Cada invocación tiene un contexto/recibo propio, no mutable entre llamadas ni reutilizable por nombre de herramienta. Se conserva la identidad durable ya introducida:

- chat: conversación + batch comprometido + call ID + run UUID;
- tarea: run UUID existente y evidencia por llamada;
- aprobación: action ID + attempt UUID, distinto en cada intento.

El callback captura esa identidad al construir la invocación. No consulta el «último» lote ni el ID global de una llamada. Una aprobación repetida no altera la evidencia del intento que obtuvo el claim.

### I2. Registro fail-closed antes del efecto

1. Se crea `intent` antes del claim/dispatch, con la clasificación WRITE y sin afirmar aprobación no recibida.
2. El gate autoritativo valida política, concesión, argumentos congelados, STOP y alcance usando sus mecanismos existentes.
3. Inmediatamente antes de un dispatch real, el contexto recibe la admisión tipada y confirma de forma durable `started` y su autorización exacta.
4. Si esa escritura falla o no encuentra la fila exacta esperada, **no se ejecuta el efecto**. El error no se silencia ni convierte en un resultado de éxito.

Preferir callback en la frontera policy/STOP existente antes del efecto, verificando el orden de locks y ejecutores. No relajar esa frontera solo para evitar instrumentarla.

No introducir una transacción Room que abarque UI, red o ejecución de herramientas. No introducir deadlocks entre locks de STOP/política y el executor de Room. Si el callback durable queda fuera del lock de autoridad, debe mantenerse la revalidación final existente antes del dispatch; haber guardado `started` no otorga permiso. Una carrera de cancelación puede dejar evidencia conservadora `uncertain`, nunca autorizar un efecto tras revocación.

El overload nuevo (p. ej. `execute4`) no puede marcar admisión nativa por defecto para un wrapper de herramienta gated. Todo wrapper gated debe propagar explícitamente el contexto; si falta soporte, fallar cerrado antes de delegar, no usar un falso `allow`. El inventario debe incluir runtime, proactive, workspace y tasks.

Para WRITE nativa sin gate, la frontera es la validación existente inmediatamente anterior a su ejecución; autorización `allow` significa admisión por ese contrato, no un permiso nuevo. No usar un no-op de callback como sustituto silencioso de evidencia requerida.

### I3. Snapshot real de autorización

El productor entrega la decisión usada en la admisión, no una nueva lectura de configuración al terminar:

- snapshot ALLOW admitido -> `allow`;
- snapshot ASK con aprobación válida y admitida -> `ask_approved`;
- ASK en cola sin dispatch -> `ask_pending` + `not_executed`;
- DENY sin dispatch -> `deny` + `not_executed`.

El botón «permitir siempre» usa el recibo exacto ALLOW persistido por el gate; no se debe tratar un cambio de política concurrente como aprobación. Una acción aprobada de tarea conserva su recibo de aprobación pero sigue sujeta a revalidación actual: el booleano `approvedPendingAction` por sí solo no demuestra que se despachó. El recorder es observador, nunca autoridad.

### I4. Resultados normalizados, no regex ni traducción de lenguaje natural

Mantener el dominio existente: `intent`, `started`, `ok`, `error`, `uncertain`, `not_executed`, `deferred`.

| Hecho tipado | Resultado de evidencia |
|---|---|
| Resultado explícito de efecto completado, sin incertidumbre | `ok` |
| Resultado explícito de fallo conocido | `error` (no implica que no hubo efectos parciales) |
| No hubo dispatch y existe prueba del productor | `not_executed`, con razón tipada |
| Aprobado, pendiente de app visible | `deferred` |
| Cancelación/excepción/pérdida de recibo después de posible dispatch | `uncertain` |
| Cancelación/rechazo antes de cualquier dispatch confirmado por el contexto | `not_executed` |
| Muerte del proceso con intent/started sin cierre | reconciliación `uncertain` |

Antes de usar la ausencia de `authorizeEffect` como prueba de no-op en conectores, inventariar TODOS los runtimes: algunos pueden ejecutar directamente o delegar en implementaciones por defecto. Solo un adaptador con cobertura completa de sus fronteras puede devolver `not_executed` cuando terminó sin admisión. El `JSONObject` arbitrario que hoy envuelve CoreConnectorTool no es un recibo confiable.

No inferir `not_executed` por ausencia de callback cuando un adaptador legacy no instrumentado pudo ejecutar. No inferir `ok` por texto, placeholder, HTTP sin validación del proveedor ni mero `CoreToolResult.success` de un resultado administrativo. Un resultado de cero elementos afectados puede ser una operación válida completada: **no** convertir automáticamente `count=0` en `not_executed`. Un no-op solo se clasifica como no ejecutado cuando el productor lo declara explícitamente. Los elementos afectados siguen perteneciendo a la fuente, no al contador de llamadas.

Para operaciones compuestas/reintentos: conservar el hecho de que ya hubo una admisión. Si una suboperación posterior es denegada, no sobrescribir el conjunto como `not_executed`. Un error parcial explícito puede ser `error` con hechos de parcialidad; si no se sabe qué ocurrió, `uncertain`. Nunca crear reintentos como consecuencia del recorder/reconciliador.

### I5. Callbacks y repetición

La primera admisión durable cambia intent->started. Las admisiones posteriores del mismo efecto lógico (subrequests/reintentos ya permitidos por el gate) no crean episodios duplicados ni reabren terminales. Cada dispatch sigue revalidando autoridad. Si cambia el snapshot entre subrequests, conservar hechos exactos o rechazar la mezcla; no inventar una autorización única distinta de la usada. El diseño final debe declarar cuál de esas dos opciones aplica.

La finalización es compare-and-set sobre la identidad exacta y estado pendiente; callbacks tardíos no cambian terminales ni efectos de otro run. La actualización de autorización y resultado invalida narración sin borrar texto/título corregidos por humanos.

### I6. Propagación explícita y concurrencia

Pasar el contexto tipado por APIs y wrappers. No usar un singleton mutable, variables globales ni un `ThreadLocal` como transporte único: MCP/red/reintentos pueden cambiar de hilo. Si hay callbacks simultáneos, serializar las transiciones del recibo y hacer idempotente su persistencia. Restaurar cualquier scope existente sin filtrar estado a la llamada siguiente.

La metadata debe sobrevivir `complete`, `preview`, `finish`, `persisted`, redacción de salida y wrappers. Preferible una operación `withContent`/copia que conserve success, flags, previewId, persistenceEvidence y recibo tipado. Una salida externa no puede inyectar un recibo confiable: el recibo lo construye el adaptador/gate local, no se deserializa indiscriminadamente del JSON del proveedor.

### I7. Compatibilidad, permisos y privacidad

Mantener las comprobaciones de política, grants, revisión de task, claim durable, memoria habilitada, exclusión `PersistenceEffect.MEMORY` y clasificación sensible a argumentos. No modificar la política para producir evidencia; no solicitar aprobaciones extra ni ampliar alcance delegado. No guardar argumentos privados, tokens o contenido de conectores en hechos de admisión. No aceptar licencias SDK, desplegar, firmar ni publicar APK como parte de este bloque.

## 3. Superficies exactas propuestas

Rutas relativas a `app/app/src/main/java/com/jarvys/agent/` salvo indicación.

| Archivo / método | Cambio a diseñar e implementar |
|---|---|
| `CoreTool.java`: `execute` overloads | Entrada explícita de contexto por invocación; fallback legacy no debe fingir una admisión gated. Mantener progress y clasificación. |
| `CoreToolResult.java`: factories / constructor | Recibo/outcome tipado opcional y copia de contenido que preserve todos los flags/metadatos. No sustituir indiscriminadamente semántica de success. |
| `CoreToolRegistry.java`: `InvocationObserver`, `invoke` | Crear/transmitir contexto de llamada; intent previo; cierre único según recibo/dispatch real; manejo conservador de excepciones. |
| `EpisodeRecorder.java`: `beforeDispatch`, `afterDispatch` | Consumir identidad comprometida y hechos de admisión, no reconsultar política. No marcar ASK aprobado antes del callback. |
| `chatdb/PersonalAgentRepository.kt`: `recordEpisodeToolStarted`, `finishEpisodeToolEvidence` | Transición atómica exacta de autorización+started/resultado; conservar hechos y terminales e invalidar narración. |
| `chatdb/ChatDatabaseBridge.kt`: métodos correspondientes | Propagar firmas Java/Kotlin compatibles, sin romper callers existentes. |
| `connectors/ConnectorRegistry.kt`: `ConnectorInvocationContext`, `invoke`, `invokeForScheduledTask`, `invokeInternal`/función local `authorizeEffect` | Transportar observador; emitir snapshot y approvedAsk desde la frontera real. Diferenciar validación inicial de dispatch real y cubrir intent de Android y callbacks preparados. |
| `CoreConnectorTool.java`: `execute`, `executeScheduledTask` | Pasar contexto explícito; producir resultado tipado local, no transformar toda devolución administrativa en efecto ok. |
| `mcp/McpWriteApprovalCoordinator.kt`: `executeWithAdmission`, `executeForScheduledTask`, `McpActionAdmission.admit` | Capturar baseline+approvedAsk y notificar antes de cada dispatch admitido. |
| `mcp/McpConnectionManager.kt`: `callTool`, ruta scheduled, `callToolWithSingleRefresh`, `executeGitHub` y callbacks beforeDispatch | Mantener contexto en reintentos y cruces de hilo; diferenciar preflight/read de dispatch de escritura; no contabilizar un preflight como efecto completado. |
| `CoreMcpTool.java`: `execute`, `executeScheduledTask` | Traducir errores/receipts locales preservando comprobación de envelope y contexto. |
| `PolicyGatedTool.java`: `executeScheduledTask` | Sobrecarga/contrato explícito del contexto compatible con los adaptadores. |
| `ModuleTool.kt`: `execute` overloads, `executeScheduledTask`, `visibleAppRequiredResult` | Propagar via ConnectorInvocationContext; resultado pending/deferred tipado; preservar metadata al interpretar envelope del módulo. |
| `CoreAgentRuntime.java`: `ScheduledTaskBoundTool.execute`, `defer`, `executeScheduledTaskAction`; wrappers de autoridad | Revalidar scope igual que hoy, transportar contexto y no perder receipt al diferir. |
| `tasks/ScheduledTaskProcessor.kt`: `TaskTrackedTool.execute` | Reusar contexto; no marcar started antes de gate como si fuera dispatch; no depender de texto para resultado; copiar metadata al redactar. |
| `tasks/TaskPendingActionExecution.kt`: `execute`, `recordEvidence`, `finishEvidence` | Mantener intent anterior al claim, transición started al dispatch real, estados según recibo durable; preservar intento dueño y recovery. |
| `tasks/TaskPendingActionStore.kt`: claim/recovery/recibos | Solo cambios necesarios para conservar metadatos tipados compatibles; no reejecutar claims inciertos. |
| `proactive/ProactiveAgentProcessor.kt`: wrapper `execute`; `MemoryReflectionWorker.java`: wrappers `execute` | Propagar clasificación, contexto y receipt si delegan; redacción no puede borrar metadata. Exclusiones existentes permanecen. |

`CoreAgentLoop`/`MainChatTranscriptStore` deben conservar su checkpoint antes de invocación y binding exacto ya implementados. No modificar su identidad para resolver autorización.

## 4. Plan de pruebas y criterios de aceptación

### B3 — productores reales

Ampliar `EpisodeRecorderTest`/`EpisodeRunIdentityTest`, aprovechando fixtures de `connectors/ConnectorActionAdmissionTest`, `connectors/ConnectorRegistryTest`, `mcp/McpActionLeaseIntegrationTest`, `mcp/McpWriteApprovalCoordinatorTest` y `modules/ModuleToolTest`.

- `CoreConnectorTool` con Gmail `trash_messages` y gateway fake; `CoreMcpTool` con conexión fake; `ModuleTool` real; `WorkspaceTools.createChat`/WriteFile con WorkspaceStore temporal real. No sustituir adaptadores por un fake CoreTool para afirmar B3 completo.
- Dentro del punto de efecto fake, consultar Room: started, WRITE, binding exacto y autorización correcta ya existen.
- ALLOW, ASK aprobado, permitir siempre, DENY, ASK rechazado/cancelado, scope cambiado antes de dispatch, callback persistencia fallido: contar efectos (cero donde corresponde).
- Excepción tras admisión, resultado explícito failure, efecto parcial seguido de fallo, placeholder/interrupción, reintento autorizado, cambio de hilo MCP; nunca ok inventado ni nueva ejecución.
- Lectura y memoria no crean episodio. Operación válida con cero elementos no se confunde con no-dispatch.

### B4 — tareas reales

Extender `tasks/ScheduledTaskProcessorTest`, `tasks/TaskPendingActionExecutionTest`, `tasks/TaskPendingActionStoreTest`, `ScheduledTaskBoundToolTest`.

- ASK no aprobado -> not_executed/ask_pending; approved + visible-app wait -> deferred; ALLOW y approved dispatch con snapshot real.
- intent antes claim; muerte entre claim y callback; ausencia de recibo durable; recovery sin reejecución.
- Repetición en CLAIMED y EXECUTED produce nuevo intento no ejecutado sin alterar evidencia del dueño.
- PARTIAL/EFFECT_OCCURRED, INTERRUPTED, RETRYABLE, permanentFailure posteriores no borran efectos previos.
- Wrappers de redacción conservan recibo, flags y persistenceEvidence.

### B5 e inventario

- Reutilizar `SingleSessionMemoryRetentionTest` y `EpisodeNarrationTest`, ahora alimentados por episodios del productor real: filtros tool/connector/fecha sin query y estados none->pending->failed->done; no pérdida tras fallo del narrador.
- Nuevo contrato de `CoreAgentRuntime.createTools()` real para Full y Play: mapa esperado de nombre/clase/exclusión, con familias dinámicas conector/MCP/módulo verificadas contra sus declaraciones de acceso. El conjunto de nombres debe coincidir: una herramienta nueva sin decisión explícita debe fallar.
- Argumentos READ/WRITE para herramientas mixtas; equivalencia de wrappers; exclusiones internas y MEMORY explícitas, no derivadas accidentalmente del default READ.

### Mutaciones y límites de evidencia

Mutaciones solo en copia temporal: quitar callback fail-closed, invertir ASK/ALLOW, descartar receipt en wrapper, marcar exception postdispatch ok, sustituir lookup exacto por call ID, omitir una herramienta del inventario. Cada una debe romper su prueba focal.

Compilar Full/Play y ejecutar pruebas afectadas cuando exista SDK autorizado. Hasta entonces, revisión de fuente y tests puros standalone no constituyen PASS Android/Room/KSP ni aprobación final de S6. Antes de editar, fijar ownership de archivos compartidos y acordar la representación del recibo; después, revisión independiente de las invariantes anteriores.
