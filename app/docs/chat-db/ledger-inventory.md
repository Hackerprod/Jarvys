# Inventario del ledger JSONL de conversaciones (Jarvys) — base para migrar a SQLite normalizado

Worktree `C:\Projects\Jarvys-wt\ux-fixes`, rama `fix/ux-menu-actions-freeze`, HEAD `2952a28`. Solo lectura; los cambios sin commitear (workspaces/*, LocalRunStore) se leyeron tal cual.
Rutas abreviadas: `LRS` = `app/app/src/main/java/com/jarvys/agent/LocalRunStore.java`; `MCTS` = `MainChatTranscriptStore.java`; `CLP` = `ConversationLedgerPager.java`; `AFS` = `AgentForegroundService.java`; `MA` = `MainActivity.kt` (todas bajo `.../com/jarvys/agent/`).

## 0. Ficheros físicos

| Fichero | Escritor | Notas |
|---|---|---|
| `files/jarvys/conversations/<sessionId>.jsonl` | solo `LRS.appendSessionRowLocked` (LRS:4618-4661) y `LRS.appendResultRowLocked` (LRS:949-963) | append + `fsync` por fila; filas `crew_snapshot` y de resultados con `losslessResultRecord` (escapa surrogates sueltos, LRS:965-976) |
| `files/jarvys/conversations/<sessionId>.meta.json` (opcional; + `.new`, `.bak`) | `ConversationMetadataStore.update` (ConversationMetadataStore.java:124-161) | JSON único `{title,pinned,archived,deleted,privateCode}`; escritura atómica `.new`→rename; si falta se usan valores por defecto; `deleted=true` resetea el resto (línea 132) = **tombstone** |
| `files/jarvys/runs/<runId>/steps.jsonl` | `LRS.beginRun/appendJson/recordSummary/finishRun` (LRS:139-163, 4772-4855, 5162-5175) | ledger de runs de dispositivo (no es la conversación); `session_id` liga run→chat (LRS:1765-1790). Fuera del alcance principal pero lo usa `collectConversationHistory` y `deleteConversation` |
| Fuera de alcance (otros JSONL): `proactive/events.jsonl`, `proactive/decisions.jsonl`, `tasks/*.jsonl` | ProactiveEventStore.kt:16, ProactiveDecisionAuditStore.kt:39, TaskStore.kt:23, TaskRunLedger.kt:47, TaskPendingActionStore.kt:52 | no son el ledger de conversaciones |

Validación de id: `[A-Za-z0-9_.-]{1,100}` (ConversationMetadataStore.java:54-61); anti-symlink en `conversationFile` (LRS:4759-4770).
Sesiones especiales: `jarvys-proactive` (ProactiveConversation.kt:11), `jarvys-tasks` (ScheduledTaskConversation.kt:6), `jarvys-central` (WorkspaceIdentity.java:5), ejecución de workspace `WorkspaceIdentity.executionSessionId` (WorkspaceIdentity.java:16-20). `MessageReactionTool.isOrdinaryChat` excluye proactive/tasks (MessageReactionTool.java:53-56).

## A. Catálogo de tipos de fila

Leyenda semántica: **AO** = append-only con valor propio; **MK** = marcador que reinterpreta filas previas; **ST** = estado (último gana); **J** = diario estricto de workspace.

### A.1 Filas sin `type` (mensajes por `role`)

| Fila | Campos (tipo) | Escribe | Lee/proyecta | Semántica |
|---|---|---|---|---|
| `role=user` | `content` str, `messageId` uuid, `attachments?` [ChatAttachment{id,name,mimeType,sizeBytes,kind,relativePath}], `proactiveThreadKey?`, `proactiveReplyToMessageId?`, `interruptRequestId?` + `mainChatAcknowledgementRequired?` bool, `timestamp` double(s) | `appendConversationMessage` (LRS:1168-1203); `appendProactiveUserMessage` (LRS:1260-1279); `dispatchInterruptRequest` (messageId=requestId, LRS:2431-2435) | `loadConversationContext` (LRS:2850-2906), `projectConversationTimeline` (LRS:4351-4358), pager ancla de turno (CLP:921-925), `readUserMessagesAfter` (LRS:3168-3205), `readInputStatus` (LRS:2548-2593), `readConversationRowByMessageId` (LRS:1337-1364), `latestUserMessageId` (LRS:2925-2952) | AO. Adjuntos solo en chat ordinario y sin threadKey (LRS:1172-1176, 1714-1720) |
| `role=assistant` | `content`, `messageId`, `status?` (COMPLETED/FAILED/PARTIAL/STOPPED/...), `runId?`, `userMessageId?` (owner), `durationMs?`, `proactiveThreadKey?`, `beforeMessageId?`, `timestamp` | `appendConversationMessage` (LRS:1188-1193); `appendAssistantMessageBefore` (LRS:2717-2737, desde AFS:1279) | contexto modelo, timeline (LRS:4359-4377), `isLatestActiveAssistant` (LRS:2294-2313), `findUserMessageBeforeAssistant` (LRS:2263-2281), `assistantOwnerUserMessageId` (LRS:2284-2292) | AO. `status` FAILED/PARTIAL/STOPPED resuelve el input del owner (LRS:2580-2586, 2644-2650). Filas de estado se excluyen del modelo (LRS:2892, 2908-2915) pero **sí cuentan** en el índice |
| `role=assistant,type=proactive_message` | + `proactiveDecisionId`, `proactiveThreadKey`, `suggestedReplies` [{label,text}], `status=COMPLETED` | `appendProactiveAssistantMessageIfAbsent` (LRS:1211-1257; idempotente por decisionId) | timeline (LRS:4363-4367), `claimProactiveSuggestedReply` (LRS:1282-1320) | AO; es mensaje (cuenta para índice y modelo) |
| `role=session_title` | `content` (título), `timestamp` | `appendConversationTitleIfAbsent` (LRS:4700-4717) | `readConversationTitleLocked` (LRS:4725-4742, primer no vacío) | ST "primero gana"; `.meta.json.title` tiene prioridad (LRS:4685-4691) |

### A.2 Marcadores e invalidaciones de mensajes

| `type` | Campos | Escribe | Lee | Semántica |
|---|---|---|---|---|
| `assistant_regenerated` | `messageId`, `runId?`, `userMessageId?` (copiados del mensaje, LRS:1824-1842) | `appendAssistantRegenerated` (LRS:1425) ← MA:1065 | `hiddenAssistantIds` (LRS:3130-3141); invalida compactación previa (LRS:2865-2871); invalida turno de herramientas del owner (LRS:4247-4251, 4011-4012) | MK |
| `assistant_deleted` | idem | `appendAssistantDeleted` (LRS:1429) ← MA:1043 | idem | MK |
| `assistant_translation` | `messageId`, `language`, `content` | `appendAssistantTranslation` (LRS:1366-1379) ← MA:992 | timeline: solo la última por messageId y posterior al último `hidden` (LRS:4258-4267, 4507-4513) | ST (última gana) |
| `assistant_translation_hidden` | `messageId`, `runId?`, `userMessageId?` | `appendAssistantTranslationHidden` (LRS:1433) ← MA:1030 | `hiddenTranslationIds` (LRS:3143-3151), timeline | MK posicional (oculta traducciones con rowIndex ≤ marcador) |
| `message_reaction` | `version=1`, `messageId` (stableMessageId, puede ser `legacy-<uuid>`), `emoji` (""=quitar) | `setMessageReaction` (LRS:2316-2358) ← MessageReactionTool.java:139 | `readMessageReactions` (LRS:2360-2369; último gana), `TimelineProjectionMarkers` | ST |
| `proactive_reply_used` | `messageId` | `claimProactiveSuggestedReply` (LRS:1310-1316) | timeline (`consumedProactiveReplyMessageIds`, LRS:4124-4126, 4366) | MK (consume todas las quick replies) |

### A.3 Decisiones, entregas, imágenes

| `type` | Campos | Escribe | Lee | Semántica |
|---|---|---|---|---|
| `user_decision_request` | `decisionId`, `title`, `body`, `options` [{id,label,description,role}], `allowDismiss` | `appendUserDecisionRequest` (LRS:1382-1407) ← UserDecisionTool.kt:39 | timeline (LRS:4520-4521, 4527-4550) | AO |
| `user_decision_resolution` | `decisionId`, `status`, `optionId?`, `optionLabel?` | `appendUserDecisionResolution` (LRS:1409-1423) ← UserDecisionTool.kt:52 | marcadores (LRS:4118-4123; última gana entre páginas) | MK/ST |
| `delivered_file` | `artifact` {ChatAttachment} | `appendDeliveredFile` (LRS:1438-1459; idempotente por artifact.id, conflicto si difiere) ← DeliverFileTool.java:59, ConnectorArtifactAccess.java:64, WorkspaceArtifactDelivery.java:122, WorkspaceTools.java:793 | timeline (LRS:4399-4402), `findChatFile` (LRS:1461-1502), `verifiedPreview` (LRS:2047-2066), privacidad (LRS:1639-1667) | AO + índice de propiedad |
| `generated_image` | `imagePath?`, `prompt`, `revisedPrompt`, `size`, `mimeType`, `status` COMPLETED/FAILED, `error?` | `appendGeneratedImageEvent/Failure` (LRS:1535-1570) ← CodexImageGenerationTool.java:125,157 | timeline (LRS:4403-4409), `ownsGeneratedFile` (LRS:1504-1532), `readConversationImageReferences` (LRS:1678-1712; asocia al último user previo) | AO |

### A.4 Entrada diferida (interrupt/steering)

| `type` | Campos | Escribe | Lee | Semántica |
|---|---|---|---|---|
| `main_interrupt_request` | `requestId`, `content`, `skills` [str], `memoryDisabled` | `appendInterruptRequest` (LRS:2390-2404) ← AFS:616 | `readPendingInterruptRequests` (LRS:2740-2791), `findPendingInterruptRequest` (LRS:2442-2458), timeline como `pending-<id>` QUEUED si no despachado (LRS:4378-4383) | AO; "despacho" = fila `user` con `interruptRequestId` (LRS:2431) |
| `main_chat_input_incorporated` | `messageIds` [str] | `markConversationMessagesIncorporated` (LRS:2461-2490) ← MCTS:141, MainChatTurnContextProvider.java:54, AFS:1295 | `readInputStatus`, `readPendingInterruptRequests` | MK; exclusivo con resolved (LRS:2475-2476, 2507-2508) |
| `main_chat_input_resolved` | `messageIds` [str] | `markConversationMessagesResolved` (LRS:2493-2522) ← AFS:653,835,857,880,895,1035,1307,1408,1445 | idem | MK |

### A.5 Protocolo del modelo (`model_*`, solo `MCTS.append`, MCTS:225-229, `schemaVersion=1`; validado en LRS:2963-2971)

| `type` | Campos | Escribe (MCTS) | Lee | Semántica |
|---|---|---|---|---|
| `model_tool_calls` | `batchId` uuid, `userMessageId`, `messageIndex` int, `assistantText`, `assistantProgress=true`, `calls` [{id,name,arguments(obj; o `{path..., _retained_arguments}` si >8000 chars, MCTS:147-156)}], `providerOutputItems` [] | MCTS:100-104 | `MCTS.restore` (MCTS:238-325), timeline progreso + "not started/interrupted" (LRS:4422-4451), `readAssistantProgress` (LRS:2803-2848), resolución de lote (LRS:2140-2229, 1935-1996) | AO; callId único en el run (MCTS:94-96) |
| `model_tool_started` | `batchId`, `callId` | MCTS:109-112 | restore (STARTED), timeline | MK estado |
| `model_tool_not_started` | `batchId`, `callId` | MCTS:115-118 | restore (NEVER_LAUNCHED), timeline (quita de started, LRS:4306-4309) | MK estado |
| `model_tool_result` | `batchId`, `callId`, `toolName`, `lifecycle` (RESULT/INTERRUPTED_UNCERTAIN/NEVER_LAUNCHED/...), `output` (≤8000 + referencia artefacto) | MCTS:130-134 | restore (primera gana, MCTS:267), timeline (LRS:4452-4491), resultado durable de actividad (LRS:4311-4328) | AO |
| `model_artifact` | `artifactId` (sha256), `artifact` {path,sha256,chars,bytes} | MCTS:183 | `MCTS.refreshOwnership` (MCTS:39-56; cuota 16 MiB) | AO (propiedad) |
| `model_error` | `errorCode`, `userMessageId`, `messageIndex` | MCTS:75-77 | restore (MCTS:272-277); **descartado** por `loadConversationContext` (LRS:2901) | AO |
| `model_provider_output_items` | `userMessageId`, `messageIndex`, `items` [{kind,value,provider?,api?,model?}] (ProviderOutputItemsCodec) | MCTS:81-84 | `restoreProviderOutputItems` (MCTS:327-339; último por userMessageId) | AO |

### A.6 Presentación de herramientas (UI) y marcadores de reflexión

| `type` | Campos | Escribe | Lee | Semántica |
|---|---|---|---|---|
| `tool_presentation` | `schemaVersion=1`, `userMessageId`, `toolName`, `stage` tool_result/tool_error, `callId`, `detail` (retain), `auditDetail` (≤4000), `previewExpected?`, `previewId?`, `batchId?` (resuelto) | `appendConversationToolPresentation` (LRS:1845-1865) ← AFS:1498 | timeline (última por `toolRowKey`, LRS:4294, 4457) | ST por (batch,call) |
| `tool_activity` | `schemaVersion=1`, `userMessageId`, `batchId` (lote fuente), `activity` {schemaVersion,eventId,executionId,callId,toolName,displayName,skillId,skillName,stage,detail,previewId,auditDetail,reflectionSource,timestampMillis}, `timestamp` | `appendToolActivity` (LRS:1867-1893) ← AFS:1352 | timeline agrupa por owner/executionId (LRS:4275-4286, 4410-4421); `ToolActivity.project` | AO (eventos de ciclo de vida) |
| `reflection_tool` | `userMessageId`, `toolName`, `callId`, `source`, `stage`, `marker`, `batchId?` | `appendReflectionToolEvent` (LRS:2097-2116) ← AFS:1354,1501 | `buildReflectionPayload` (LRS:3264-3300), restore legado (MCTS:314-323), timeline legado (LRS:4452+) | AO |

### A.7 Compactación y memoria (reflexión)

| `type` | Campos | Escribe | Lee | Semántica |
|---|---|---|---|---|
| `compaction` | `summary`, `firstKept` (índice de mensaje), `trigger`, `mode`, `summarizedMessages`, `timestamp`; **sin `compactionId`** | `appendCompaction` (LRS:4602-4616) ← ConversationCompactor.java:136 | `loadConversationContext` (última; LRS:2862-2879), timeline (LRS:4492-4496; oculta si hay invalidación posterior en la página), `pendingCompactionIds` | MK de frontera. Id derivado `legacy-<timestamp>-<rowIndex>` (LRS:3467-3470). El índice es la posición en `readConversationRows` tras reordenar steering, omitiendo filas result-only estrictas y líneas no parseables. `session_title` sí ocupa una posición aunque no tenga `conversation_items`; cada marcador `messageIds[]` ocupa una posición aunque actualice varios mensajes. M2 conserva ese índice en el sufijo de `compaction_id` para lectores posteriores. |
| `compaction_reflection_consumed` | `compactionIds` [] (legado: `compactionId` o nada = limpiar todo) | LRS:4591-4600 | `pendingCompactionIds` (LRS:3449-3455) | MK |
| `reflection_checkpoint` | `throughMessageId`, `reflectionId`, `completedAssistantSteps`, `result`, `acknowledgedCompactionIds` | LRS:3353-3367 ← MemoryReflectionCoordinator.java:59,106 | `buildReflectionPayload` (último checkpoint/commit) | MK frontera |
| `reflection_commit` | + `summary`, `status`, `trigger`, `revisionIds` [long] | LRS:3370-3389 ← MemoryReflectionCoordinator.java:132 | idem + timeline (LRS:4497-4506), `hasReflectionCommit` (LRS:3427-3433) | MK + AO |
| `reflection_event` | `reflectionId`, `summary`, `status`, `revisionIds` | LRS:3404-3418 ← MemoryReflectionCoordinator.java:141,184 | timeline | AO |
| `reflection_undo` | `reflectionId` | LRS:3420-3425 ← MA:1155 | timeline (status "undone", LRS:4252, 4502) | MK |

No hay otras filas de "memoria" en el ledger: la memoria vive en otro almacén; aquí solo checkpoint/commit/event/undo + `reflection_tool`.

### A.8 Crew y workspaces

| `type` | Campos | Escribe | Lee | Semántica |
|---|---|---|---|---|
| `crew_snapshot` | `crewSchemaVersion` (1..4 aceptadas, CrewMissionSnapshot.java:137-139), `missionId`, `conversationId`, `processId`, `title`, `originalInstructions`, `titleSource`, `status`, `synthesis`, `startedAtMillis`, `finishedAtMillis`, `bots` [{id,cycleId,resultEvidence?,roleId,roleName,name,colorKey,mission,status,error,result,waitingReason,phase,lastProgress,lastProgressAtMillis,tools[],resumeRequired,canResume,recoveryNote,startedAtMillis,finishedAtMillis}], `messages` [CrewMessage{id,conversationId,from,to,type,text,refs[],timestampMillis,coordination?,inputBinding?,origin?,activity?}] (CrewMissionSnapshot.java:84-131; CrewMessage.java:65-70) | `appendCrewMissionSnapshot` (LRS:175-196; dedupe por digest **en memoria**) ← CoreAgentRuntime.java:621, WorkspaceCoordinator.kt:299, `recoverCrewMissions` (LRS:1140-1148, marca interrumpidas) | `readCrewMissionSnapshots` (LRS:1056-1122, caché incremental por offset) ← CoreAgentRuntime.java:565, WorkspaceCoordinator.kt:166, WorkspaceProjection.kt:23,41; timeline (última por misión, posición = fila de la última, LRS:4219-4230, 4514-4519) | ST (último por misión) |
| `crew_result_evidence` (J) | `crewResultSchemaVersion`, `evidenceId`, `conversationId`, `missionId`, `botId`, `cycleId`, `reportedStatus`, `resultText`, `producedAtMillis`, `contentSha256` (CrewResultEvidence.java:53-59) | LRS:207-220 ← CoreAgentRuntime.java:507 | strict scan (LRS:828-836) | J inmutable |
| `workspace_result_reference` / `workspace_result_notice` (J) | `workspaceResultSchemaVersion`, `referenceId`, `evidenceId`, `contentSha256`, `ownerCentralSessionId`, `workspaceId`, `executionSessionId`, `assignmentId`, `requestId`, `missionId`, `botId`, `cycleId`, `observedAssignmentRevision`, `reportedStatus`, `producedAtMillis` (WorkspaceResultReference.java:72-84) | LRS:254-302 ← WorkspaceResultDelivery.kt:55,66 | strict scan; notice en timeline solo en central y re-verificado (LRS:4384-4398) | J; reference en ledger de ejecución, notice en central |
| `crew_coordination_evidence` (J) | `crewCoordinationSchemaVersion`, `evidenceId`, `conversationId`, `missionId`, `botId`, `cycleId`, `messageId`, `kind`, `text`, `refs`[], `producedAtMillis`, `contentSha256` | LRS:336-349 ← CoreAgentRuntime.java:522 | strict scan | J |
| `workspace_coordination_reference` / `_notice` (J) | como result_reference + `messageId`, `kind` (WorkspaceCoordinationReference.java:73-85) | LRS:383-431 ← WorkspaceCoordinationDelivery.kt:52,60 | strict scan | J |
| `workspace_coordination_reply_intent` (J) | `reference`{obj}, `referenceId`, `executionSessionId`, `replyId`, `replyKey`, `text`, `centralRequestId`, `originalUserRequest`, `mailboxMessageId`, `payloadSha256`, `preparedAtMillis` | LRS:466-487 ← WorkspaceCoordinationDelivery.kt:159,184 | strict scan | J (una por evidencia) |
| `workspace_coordination_reply_state` (J) | `referenceId`, `executionSessionId`, `replyId`, `payloadSha256`, `state` PREPARED→STARTED→ACCEPTED_LOCAL, `stateAtMillis` | LRS:490-521 ← WorkspaceCoordinationDelivery.kt:190,196 | strict scan con máquina de estados (LRS:754-766) | MK estado monotónico |
| `workspace_input_incorporation` (J) | `receiptId`, `sourceKind`, `sourceId`, `sourceReferenceId`, `mailboxMessageId`, `ownerCentralSessionId`, `workspaceId`, `executionSessionId`, `assignmentId`, `requestId`, `missionId`, `botId`, `admissionCycleId`, `firstIncorporationCycleId`, `sourcePayloadSha256`, `deliveredContentSha256`, `transportKind`, `transportSha256`, `checkpointPayloadSha256`, `checkpointSavedAtMillis`, `recordedAtMillis` (WorkspaceInputIncorporation.java:111-123) | LRS:579-603 ← WorkspaceInputIncorporationDelivery.kt:111 | strict scan (primera observación gana, LRS:907-914) | J |

Las filas J son "result-only" (`isResultOnlyRow`, LRS:2973-2983) y se **excluyen** de todo lector tolerante (`isConversationTimelineLedgerRow`, LRS:3099-3102) salvo `workspace_result_notice` en el timeline (LRS:3105-3108). Mensajes humanos en central/ejecución: WorkspaceCoordinator.kt:213,217 (filas user/assistant normales).

### A.9 Invariantes de integridad

1. **messageId**: UUID v4 al escribir (LRS:1182). Filas legado sin `messageId` → id derivado distinto según consumidor: reacciones/timeline `legacy-` + `UUID.nameUUIDFromBytes(session+"\0"+index)` (MessageReactionTool.java:59-63); reflexión `legacy-<index>` (LRS:3234-3235, 3281). `index` = ordinal entre filas user/assistant con `content` (incluye ocultas y de estado).
2. **Índice de mensaje**: lo usan `compaction.firstKept`, `model_*.messageIndex`, `ConversationTurn.originalMessageIndex`, reacciones. El modelo lo calcula tras reordenar steering (LRS:2851, 2886-2891); el timeline lo calcula **antes** de reordenar (LRS:3913-3919, 3987-3989); el pager lo arrastra con un índice de prefijo (CLP:217-223) que solo expone si hay filas legado. `ConversationCompactor` usa `conversationMessageCount` (LRS:4552-4566, orden ledger) y `latestUserMessageIndex` (LRS:2917-2923, orden reordenado).
3. **Orden**: append físico. Único reordenamiento: assistant con `beforeMessageId` se mueve justo antes de la fila destino (LRS:3110-3128), aplicado en lectura por casi todos los lectores (no por `readConversationRowsInLedgerOrder`, ni por el pager antes de calcular índices).
4. **Owner**: `assistant.userMessageId` = mensaje user que poseía el turno; tras steering cambia al último input incorporado (AFS:1286-1296). `model_tool_calls/tool_activity/tool_presentation/reflection_tool/model_error/model_provider_output_items` llevan `userMessageId`; los marcadores copian `runId/userMessageId` del mensaje (LRS:1830-1838).
5. **batchId/callId/executionId**: `batchId` uuid por TOOL_CALLS (MCTS:91); clave canónica `batchId/callId`; fallback `owner/callId` o `legacy-row:<identityHash>` si ambiguo (`toolRowKey`, LRS:3472-3489). `tool_activity.batchId` se resuelve escaneando hacia atrás: primera actividad con mismo executionId, si no la llamada más reciente con ese callId (LRS:1935-1996). `tool_presentation/reflection_tool` resuelven batch por (owner,callId) más reciente (LRS:2140-2229).
6. **Lote completo**: un `model_tool_calls` es completo si hay `model_tool_result` para todos sus callIds (MCTS:283); los incompletos se reconstruyen con resultado sintético INTERRUPTED_UNCERTAIN/NEVER_LAUNCHED (MCTS:300-306).
7. **Idempotencia**: proactive por `proactiveDecisionId` (LRS:1224-1233), delivered_file por artifact.id (LRS:1444-1449), crew snapshot por digest (solo en proceso), filas J por id con comparación exacta de payload.
8. **Tombstone**: `.meta.json deleted=true` bloquea toda escritura (LRS:4620) y lectura (`conversationLedgerSnapshot`, LRS:2538-2545); el borrado físico es best-effort (LRS:1573-1608).
9. **Snapshot de lectura**: lectores toman (fileKey,length,mtime) y reintentan si cambió (`LedgerSnapshot.matches`, LRS:3709-3716); escritores bajo `SESSION_TITLE_LOCK` estático (LRS:53).

## B. Quién toca el ledger fuera de LocalRunStore

Nadie abre `conversations/*.jsonl` directamente salvo `ConversationMetadataStore` para `.meta.json`. Todo pasa por la API de `LocalRunStore`. "Full" = la llamada parsea el ledger entero (`readConversationRowsFromSnapshot`/`readConversationRows`); "cola" = lector inverso con corte temprano; "estricto" = scan completo validado (filas J).

| Fichero:línea | API | Lectura |
|---|---|---|
| AFS:1134, 1237 | `loadConversationContext` | full (modelo) |
| AFS:1135; ConversationCompactor.java:113; MA:964 | `conversationMessageCount` | full streaming |
| AFS:636-669, 838-896, 1282, 1309, 1645 | `readRecentConversationTimeline` | página tail (40 turnos) |
| AFS:722-825 (`readPendingInterruptRequests`), 648/761/823/877/891 (`dispatchInterruptRequest`), 616 (`appendInterruptRequest`) | interrupts | pending por sesión = full; sin sesión = **full de todas las conversaciones** (LRS:2787-2791) |
| AFS:650, 833, 853-878, 894, 1033-1034, 1246, 1397, 1437 | `appendConversationMessage` | escritura |
| AFS:653…1445 | `markConversationMessagesResolved` | cola filtrada (`readInputStatus`) |
| AFS:1212, 1219/1248, 1224, 1230-1231, 1272, 1279, 1295, 1315, 1352-1354, 1395, 1498-1501, 1592-1593 | readConversationMessage (cola), latestUserMessageId (cola), readConversationAttachments (cola), privacidad (cola), IncorporatedThrough (cola), appendAssistantMessageBefore, readAssistantProgress (cola), appendToolActivity/ReflectionTool/ToolPresentation (cola + caché), proactiveThreadKeyForUserMessage (cola) | — |
| CoreAgentRuntime.java:275, 316, 346, 400, 426, 507, 522, 565, 621, 832, 1896-1897, 1920 | crea `MainChatTurnContextProvider`, `MainChatTranscriptStore`, `ConversationCompactor`, `MessageReactionTool`; evidencias crew (estricto); snapshots (incremental); privateCode (meta) | — |
| MCTS:46 (`readModelTranscriptRows`, **full** en cada `archive`/recovery), 68-69 (cola + full), 141, 228 | transcript del modelo | full |
| MainChatTurnContextProvider.java:54, 59-60, 65-66, 80-82 | steering | cola |
| ConversationCompactor.java:89 (`latestUserMessageIndex` full), 113, 136 | compactación | full |
| MemoryReflectionCoordinator.java:48-184 | `buildReflectionPayload` (full), checkpoints, commit, events, `hasReflectionCommit` (full) | full |
| MessageReactionTool.java:36 (full), 139 (`setMessageReaction`, full) | reacciones | full |
| UserDecisionTool.kt:39, 52 | decisiones | escritura |
| MA:924 (`listConversationSessionIds` + `advanceReflectionCheckpoint` → **full de todas**), 957, 992, 1030, 1042-1043, 1060-1065 (full ×3), 1092/1385/1450/1503/2241 (páginas), 1155, 1360/1478-1479/1546/1630 (título; `readFirstUserMessage` lee hasta el primer user), 1534 (`recoverCrewMissions`), 1544-1576 (título), 1614-1626 (pin/archive/rename/delete/meta), 1660 (`listConversations` → `collectConversationHistory`: **full de todas** para el drawer, LRS:5019-5049) | UI | mixta |
| AttachmentDraftViewModel.kt:50 (`cleanupOrphanAttachments`: streaming de **todas**, LRS:1722-1758), 265 | adjuntos | full |
| ChatFileProvider.java:34,39; ui/chat/ChatFileTransfers.kt:50-51; HtmlThumbnailRenderer.kt:77; HtmlThumbnailStore.java:186; WorkspacePreviewScreen.kt:65; ConnectorArtifactAccess.java:81,97 | `findChatFile`/`ownsGeneratedFile` | cola |
| CodexImageGenerationTool.java:125,157; DeliverFileTool.java:55-59; WorkspaceTools.java:788-793; WorkspaceArtifactDelivery.java:122; ImportProjectImageTool.java:44-68 | escrituras de artefactos / meta | — |
| ImageReferenceListingTool.java:54; ImageReferenceResolver.java:55 | `readConversationImageReferences` | full streaming |
| CoreAgentModel.java:131; WorkspaceStore.java:198 | privateCode (meta) | — |
| WorkspaceResultDelivery.kt:39-131; WorkspaceCoordinationDelivery.kt:38-257; WorkspaceInputIncorporationDelivery.kt:52-151 | diarios J | **estricto full por cada llamada** (varias por operación) |
| workspaces/WorkspaceCoordinator.kt:166, 213, 217, 299; workspaces/WorkspaceProjection.kt:23, 41, 60 | snapshots, mensajes central, página workspace | incremental / página |
| proactive/ProactiveAgentProcessor.kt:183, 188, 196; proactive/ProactiveInteractionDispatcher.kt:18-60 | título, proactive msg (full), claim (full), `readConversationTimeline` (**full**) | full |
| tasks/ScheduledTaskProcessor.kt:128-133, 261-262; tasks/TaskNotifier.kt:42, 261-262; tasks/TaskManagementTools.kt:41 | sesión `jarvys-tasks`: proactive msg, `readConversationTimeline` (**full**) | full |

Borrado/tombstone: `LRS.deleteConversation` (LRS:1573-1608) ← MA:1620: marca meta `deleted`, invalida cachés (LRS:1577-1580; CLP:153-158), borra .jsonl, runs, GeneratedImageStore, DeliveredArtifactStore, AttachmentStore, adjuntos importados de WorkspaceStore y `CrewContextArtifacts` (artefactos de `model_artifact`, CrewContextArtifacts.java:56-62). `CrewCheckpointStore.deleteConversation` (CrewCheckpointStore.java:329) es otra cosa (contexto crew), no el ledger.
Rename/título: `renameConversation/setConversationPinned/Archived` → `.meta.json` (LRS:1614-1624); título generado como fila `session_title`.
Limpieza de huérfanos: `cleanupOrphanAttachments` (LRS:1722-1758) usa referencias de todos los ledgers.
Export/import/backup: **no encontrado** ningún export/import de conversaciones; `android:allowBackup="false"` (AndroidManifest.xml:60). Sin `android:process` en el manifest → un solo proceso; ya existe precedente SQLite: `WorkspaceRegistry.kt:580` (`SQLiteOpenHelper`).

## C. Qué lee el modelo (no debe cambiar)

1. **`LRS.loadConversationContext`** (LRS:2850-2906), llamado en AFS:1236-1243 (run real; quita el último user si coincide con displayGoal) y AFS:1134 (compactación manual):
   - filas = snapshot validado, sin filas J (también sin notices), **reordenadas por steering** (LRS:2994-2996).
   - `messages` = user/assistant con `content` (incluye ocultas y filas de estado para el índice).
   - compactación = la última `compaction`, anulada si hay `assistant_regenerated/deleted` posterior (LRS:2865-2871). Si existe: turno resumen + `firstKept`.
   - para cada mensaje con índice ≥ firstKept: omite assistant ocultos y de estado (STOPPED/PARTIAL/FAILED/ERROR/…, LRS:2908-2915); assistant con `model_provider_output_items` del mismo owner → `assistantReply` con items (LRS:2893-2896); user con adjuntos (`messageWithAttachments`).
   - grupos de herramientas insertados **en la posición de la fila `model_tool_calls`** (LRS:2899-2903) vía `MCTS.restore`; turnos MODEL_ERROR descartados (LRS:2901).
2. **`MCTS.restore`** (MCTS:238-325): estado por lote INTENT/STARTED/NEVER_LAUNCHED; resultado = primer `model_tool_result` del callId y solo si `toolName` coincide (MCTS:298); se omite el lote si **completo** y su última actividad `< compactionRow` y `messageIndex < firstKept` (MCTS:283-285) — un lote cuya intención precede al resumen pero cuya ejecución lo sigue **sobrevive**. Errores de decodificación → resumen sintético. `reflection_tool` legado (sin llamada estructurada) tras la compactación → resumen sintético (MCTS:314-323). `model_error` solo si `i >= compactionRow && messageIndex >= firstKept`.
3. **`restoreProviderOutputItems`** (MCTS:327-339): último por `userMessageId`, filtrado por compactación.
4. **`readModelTranscriptRows`** (LRS:2956-2961): filas `model_*` (orden reordenado) para la propiedad de artefactos (MCTS:39-56).
5. **Compactación** (ConversationCompactor.java:96-148): calcula `firstKept` = min(originalMessageIndex conservados, índice del user actual) y lo persiste; el índice debe seguir significando lo mismo tras migrar.
6. Otros contextos de modelo: `buildReflectionPayload` (LRS:3217-3328; checkpoint por `throughMessageId`, sanea texto tras conectores) para memoria; `MessageReactionTool.prepareModelMetadata` (MessageReactionTool.java:66-87) mapea `originalMessageIndex`→stableMessageId; steering vía `readUserMessagesAfter` + `handledConversationMessageIds` (MainChatTurnContextProvider.java:65-98).

Invariantes exigidos: orden idéntico tras reordenar; mismo conteo de índice (incluidas ocultas/estado/proactivas); lotes agrupados en su posición; primera-gana para resultados; última-gana para provider items; misma regla de invalidación de compactación; mismos ids legado.

## D. Qué lee la UI

- **Página**: `readConversationTimelinePage` (LRS:3940-4025): `CLP.readTail/readPrevious` (CLP:57-100) devuelve líneas desde la N-ésima fila `role=user` con content contando hacia atrás (anclas, CLP:179-201; si no hay más, desde offset 0); cursor = **offset en bytes** (`olderCursor`); `messageIndexBeforeStart` solo si hay filas legado (CLP:217-223). Turnos: 40 iniciales, 20 más antiguos (LRS:3907-3908).
- Proyección `projectConversationTimeline` (LRS:4206-4525): por página: delivered_files propios (solo de la página, LRS:4212-4218), crew último por misión, ocultos, invalidaciones de turno de herramientas por owner (posicionales), `reflection_undo`, traducciones, agrupación `tool_activity` + resultados durables, presentaciones/llamadas/resultados por `toolRowKey`, interrupts pendientes, reacciones, notices de workspace (re-verificados estrictamente, LRS:4389-4391), compacciones, reflexión, decisiones.
- **Ids de evento UI** = `offset*1_000_000+1` (LRS:4338) → dependen del offset en bytes.
- Estado entre páginas: `ConversationTimelinePage` (LRS:3492-3518) devuelve `hiddenAssistantMessageIds`, `hiddenTranslationMessageIds`, `translationMessageIds`, `invalidatedToolTurnMessageIds`, `projectionMarkers` = {resolvedDecisions, consumedProactiveReplyMessageIds, reactionsByMessageId, latestCrewSnapshots} (LRS:3525-3585); la página nueva gana (`withOlderPage`, LRS:3558-3569). La UI los pasa a la siguiente página antigua (MA:1493-1512 `loadOlderConversationTimeline`, 2241-2244 workspace) y `AgentRunUiState.prependPersistedSession` filtra eventos antiguos con los sets (AgentRunUiState.kt:846-871).
- Limitaciones actuales (estado solo de página, no global): `ownedDeliveredFiles` para previews, `undoneReflectionIds`, `latestInvalidationRow` de compactación, `translationHiddenAt`, agrupación de `tool_activity` y búsqueda de `model_tool_result` — si una ejecución cruza la frontera de página (steering crea anclas a mitad de run) el resultado puede diferir de la vista completa. El reordenamiento steering no se aplica si el destino cae en otra página.
- Cachés: `TIMELINE_PAGE_CACHE` 8 MiB (LRS:58-59, 4136-4174), `CLP.PAGE_CACHE` 4 MiB/32 páginas + `PREFIX_INDEXES` 16 ficheros con checkpoints cada 1 MiB (CLP:28-33, 227-262), `TOOL_BATCH_CACHES`/`TOOL_ACTIVITY_BATCH_CACHES` 16 ficheros×128 pares (LRS:60-62), `CREW_SNAPSHOT_CACHE` 16 (LRS:57). Todas invalidadas en `appendSessionRowLocked` (LRS:4634-4660) y en borrado.
- Vista completa (sin paginar): `readConversationTimeline` (LRS:3911-3922) para proactive y tasks (ProactiveAgentProcessor.kt:196; ProactiveInteractionDispatcher.kt:36,60; ScheduledTaskProcessor.kt:133; TaskNotifier.kt:42). Workspace: WorkspaceProjection.kt:60 (página) y MA:2241 (antiguas).
- Drawer: `listConversations/listRecentRuns` → `collectConversationHistory` (LRS:4984-5063) lee todos los ledgers y runs.

## E. Esquema relacional propuesto (estilo Kelivo)

Principios: un contador `seq` monotónico por conversación (equivale a la posición en el ledger; asignado en la misma transacción); cada tabla tipada lleva `seq`; una "espina" ligera `conversation_items` permite paginar y proyectar en orden sin uniones costosas. JSON solo para payloads opacos (argumentos de herramienta, provider output items, sub-objetos de evidencia inmutables si se decide).

```sql
conversations(id TEXT PK, kind TEXT CHECK(kind IN('chat','proactive','tasks','central','workspace_exec')),
  title TEXT, generated_title TEXT, pinned INT, archived INT, private_code INT,
  created_at INT, updated_at INT, last_message_at INT, message_count INT, next_seq INT,
  legacy_ledger_bytes INT, legacy_ledger_sha256 TEXT, migrated_at INT)
tombstones(scope TEXT, entity_id TEXT, deleted_at INT /*ms aproximados*/, payload TEXT, PK(scope,entity_id)) -- sin FK
-- AFTER DELETE ON conversations: INSERT INTO tombstones ... SELECT ... WHERE NOT EXISTS; deleted_at usa julianday('now')
conversation_items(conversation_id, seq, item_kind TEXT, item_key TEXT,
  sort_seq INT, sort_sub INT, turn_anchor_seq INT,          -- orden con steering y ancla de turno
  PK(conversation_id, seq))   IDX(conversation_id, sort_seq, sort_sub)
messages(conversation_id, message_id TEXT, stable_id TEXT, seq INT, message_index INT,
  role TEXT CHECK(role IN('user','assistant')), content TEXT, status TEXT, run_id TEXT,
  owner_user_message_id TEXT, duration_ms INT, before_message_id TEXT,
  proactive_thread_key TEXT, proactive_reply_to_message_id TEXT, proactive_decision_id TEXT,
  interrupt_request_id TEXT, ack_required INT, legacy_id INT, created_at REAL,
  hidden_kind TEXT NULL, hidden_seq INT NULL,               -- regenerated/deleted (columna, no fila)
  translation_hidden_seq INT NULL, suggested_replies_consumed_seq INT NULL,
  input_state TEXT NULL CHECK(IN('incorporated','resolved')), input_state_seq INT NULL,
  PK(conversation_id, message_id))
  IDX(conversation_id, seq) ; IDX(conversation_id, role, seq) ; IDX(conversation_id, owner_user_message_id)
  IDX(conversation_id, message_index) ; UNIQUE(conversation_id, stable_id)
  UNIQUE(conversation_id, proactive_decision_id)
message_attachments(conversation_id, message_id, ordinal, attachment_id, name, mime_type, size_bytes, kind, relative_path, PK(...,ordinal))
  IDX(conversation_id, attachment_id)
message_translations(conversation_id, message_id, seq, language, content)  IDX(conversation_id, message_id, seq)
message_reactions(conversation_id, target_stable_id, emoji, seq, PK(conversation_id,target_stable_id))
proactive_suggested_replies(conversation_id, message_id, ordinal, label, text)
user_decisions(conversation_id, decision_id, seq, title, body, allow_dismiss, status, option_id, option_label, resolved_seq, PK(conversation_id,decision_id))
user_decision_options(conversation_id, decision_id, ordinal, option_id, label, description, role)
interrupt_requests(conversation_id, request_id, seq, content, memory_disabled, dispatched_message_id, dispatched_seq, PK(...))
  IDX parcial (conversation_id) WHERE dispatched_seq IS NULL
interrupt_request_skills(conversation_id, request_id, ordinal, skill_id)
tool_batches(conversation_id, batch_id, seq, owner_user_message_id, message_index, assistant_text,
  assistant_progress INT, provider_output_items_json TEXT /*opaco*/, PK(conversation_id,batch_id))
  IDX(conversation_id, owner_user_message_id, seq)
tool_calls(conversation_id, batch_id, ordinal, call_id, name, arguments_json TEXT /*opaco*/,
  lifecycle TEXT /*INTENT|STARTED|NEVER_LAUNCHED*/, lifecycle_seq INT, PK(conversation_id,batch_id,call_id))
  IDX(conversation_id, call_id)
tool_results(conversation_id, batch_id, call_id, seq, tool_name, lifecycle, output, PK(conversation_id,batch_id,call_id)) -- primera gana
tool_activities(conversation_id, seq, event_id, owner_user_message_id, source_batch_id, execution_id, call_id, tool_name,
  display_name, skill_id, skill_name, stage, detail, preview_id, audit_detail, reflection_source, timestamp_millis)
  UNIQUE(conversation_id, event_id) ; IDX(conversation_id, owner_user_message_id, execution_id, seq)
tool_presentations(conversation_id, seq, owner_user_message_id, batch_id, call_id, tool_name, stage, detail, audit_detail, preview_expected, preview_id)
  IDX(conversation_id, batch_id, call_id, seq)
reflection_tool_markers(conversation_id, seq, owner_user_message_id, batch_id, call_id, tool_name, source, stage, marker)
model_errors(conversation_id, seq, owner_user_message_id, message_index, error_code)
model_provider_outputs(conversation_id, seq, owner_user_message_id, message_index, items_json TEXT /*opaco*/)
model_artifacts(conversation_id, artifact_id, seq, path, sha256, chars, bytes, PK(conversation_id,artifact_id))
delivered_files(conversation_id, artifact_id, seq, name, mime_type, size_bytes, kind, relative_path, PK(conversation_id,artifact_id))
generated_images(conversation_id, seq, image_path, prompt, revised_prompt, size, mime_type, status, error)  IDX(image_path)
compactions(conversation_id, compaction_id TEXT /*persistir el id legado calculado*/, seq, summary, first_kept,
  summarized_messages, trigger, mode, created_at, PK(conversation_id,compaction_id))
compaction_reflection_acks(conversation_id, compaction_id, seq, source TEXT /*consumed|checkpoint|commit*/)
reflections(conversation_id, seq, kind TEXT /*checkpoint|commit|event*/, reflection_id, through_message_id,
  completed_assistant_steps, result, summary, status, trigger, undone_seq INT NULL)
reflection_revisions(conversation_id, reflection_seq, ordinal, revision_id)
crew_missions(conversation_id, mission_id, last_snapshot_seq, schema_version, process_id, title, original_instructions,
  title_source, status, synthesis, started_at_ms, finished_at_ms, PK(conversation_id,mission_id))
crew_bots(conversation_id, mission_id, ordinal, bot_id, cycle_id, role_id, role_name, name, color_key, mission, status, error,
  result, waiting_reason, phase, last_progress, last_progress_at_ms, resume_required, can_resume, recovery_note,
  started_at_ms, finished_at_ms, result_evidence_id NULL, evidence_content_sha256 NULL)
crew_bot_tools(conversation_id, mission_id, bot_id, ordinal, tool)
crew_messages(conversation_id, mission_id, ordinal, message_id, from_bot, to_bot, type, text, timestamp_ms, origin,
  coordination_evidence_id NULL, reference_message_id NULL, evidence_content_sha256 NULL)
crew_message_input_bindings(conversation_id, mission_id, message_id, schema_version, source_kind, source_id,
  owner_central_session_id, workspace_id, execution_session_id, assignment_id, input_mission_id, bot_id,
  mailbox_message_id, source_payload_sha256, delivery_sha256, admission_cycle_id)
crew_message_activities(conversation_id, mission_id, message_id, schema_version, event_id, execution_id, call_id,
  tool_name, display_name, skill_id, skill_name, stage, detail, preview_id, audit_detail, reflection_source, timestamp_millis)
crew_message_refs(conversation_id, mission_id, message_id, ordinal, ref)
-- Diarios J (UNIQUE/FK sustituyen las validaciones del scan estricto)
crew_result_evidence(conversation_id, evidence_id, seq, mission_id, bot_id, cycle_id, reported_status, result_text BLOB /*UTF-16 sin pérdida*/, produced_at_ms, content_sha256, PK(conversation_id,evidence_id))
workspace_result_refs(conversation_id /*ledger dueño*/, reference_id, is_notice INT, seq, evidence_id, content_sha256,
  owner_central_session_id, workspace_id, execution_session_id, assignment_id, request_id, mission_id, bot_id, cycle_id,
  observed_assignment_revision, reported_status, produced_at_ms, PK(conversation_id, reference_id, is_notice))
crew_coordination_evidence(conversation_id, evidence_id, seq, mission_id, bot_id, cycle_id, message_id, kind, text BLOB, produced_at_ms, content_sha256, PK(conversation_id,evidence_id))
crew_coordination_evidence_refs(conversation_id, evidence_id, ordinal, ref, PK(conversation_id,evidence_id,ordinal))
workspace_coordination_refs(... igual que result_refs + message_id, kind)
workspace_coordination_replies(conversation_id, reference_id PK, reply_id, evidence_id, reply_key, text BLOB,
  central_request_id, original_user_request, mailbox_message_id, payload_sha256, prepared_at_ms, state, state_at_ms, seq)
  UNIQUE(conversation_id, reply_id) ; UNIQUE(conversation_id, evidence_id)
workspace_coordination_reply_transitions(reply_id, seq, state, state_at_ms)
workspace_input_incorporations(conversation_id, receipt_id, seq, source_kind, source_id, source_reference_id,
  mailbox_message_id, owner_central_session_id, workspace_id, execution_session_id, assignment_id, request_id,
  mission_id, bot_id, admission_cycle_id, first_incorporation_cycle_id, source_payload_sha256, delivered_content_sha256,
  transport_kind, transport_sha256, checkpoint_payload_sha256, checkpoint_saved_at_ms, recorded_at_ms,
  PK(conversation_id,receipt_id))
  UNIQUE(conversation_id, mailbox_message_id) -- dedupe por ledger; no implica unicidad cross-ledger
```

Mapeo fila → tablas:

| Fila ledger | Destino |
|---|---|
| user/assistant (+proactive_message) | `messages` (+ `message_attachments`, `proactive_suggested_replies`); `conversation_items(kind='message')`; `sort_seq/sort_sub` = destino de `beforeMessageId` |
| session_title | `conversations.generated_title` (primera no vacía) |
| assistant_regenerated/deleted | `messages.hidden_kind/hidden_seq` + item `invalidation` (necesario: la posición relativa a compactación y a filas de herramientas del owner importa) |
| assistant_translation / _hidden | `message_translations` / `messages.translation_hidden_seq` |
| message_reaction | `message_reactions` (estado actual; el historial no lo lee nadie) |
| proactive_reply_used | `messages.suggested_replies_consumed_seq` |
| user_decision_request / resolution | `user_decisions`+`user_decision_options` / columnas `status…resolved_seq` (última gana) |
| main_interrupt_request | `interrupt_requests`(+skills); despacho = `dispatched_message_id` |
| main_chat_input_incorporated/resolved | `messages.input_state(_seq)` (o para requestId aún no despachado: columna en `interrupt_requests`); la columna conserva `incorporated`/`resolved`, mientras `conversation_items.item_kind` conserva el nombre de fila del ledger `main_chat_input_incorporated`/`main_chat_input_resolved` |
| model_tool_calls / started / not_started / result | `tool_batches`+`tool_calls` / `tool_calls.lifecycle` / `tool_results` |
| model_artifact, model_error, model_provider_output_items | tablas homónimas |
| tool_activity, tool_presentation, reflection_tool | tablas homónimas |
| delivered_file, generated_image | tablas homónimas |
| compaction, compaction_reflection_consumed | `compactions`, `compaction_reflection_acks` |
| reflection_checkpoint/commit/event, reflection_undo | `reflections`(+revisions), `reflections.undone_seq` |
| crew_snapshot | `crew_missions/bots/messages` (estado último; `last_snapshot_seq` conserva la posición de la tarjeta) |
| filas J | tablas J |

En `file_refs`, `sha256=''` representa un digest desconocido, compatible con un digest conocido cuando coinciden `relative_path` y `bytes`; un digest posterior completa el valor. Digests conocidos distintos o tamaños distintos para la misma ruta son conflicto. La recuperación de interrupciones y los marcadores globales comparten una sola proyección de solicitudes pendientes, filtrada por los estados `resolved`/`incorporated`.

Índices clave: cola por conversación `(conversation_id, seq DESC)` en `conversation_items`; paginación hacia atrás = `SELECT seq FROM messages WHERE conversation_id=? AND role='user' AND seq<?cursor ORDER BY seq DESC LIMIT n` y luego rango de items; por messageId `PK(conversation_id,message_id)` y `UNIQUE(stable_id)`; por owner/batch/call los índices de `tool_*`. Repositorio transaccional: una transacción por operación lógica (p. ej. dispatch interrupt = insertar mensaje + marcar request; cierre de turno = resultados + input_state), `BEGIN IMMEDIATE`, WAL.

## F. Riesgos y casos límite

1. **Filas corruptas/truncadas**: los lectores tolerantes saltan líneas no parseables (LRS:3028-3031); los estrictos fallan todo el ledger (LRS:926-927). Un append normal tras una cola rota **concatena** con la línea rota (appendSessionRowLocked no verifica la cola, LRS:4627-4630) → se pierde también la nueva fila. Migración: importar con informe de líneas descartadas, conservar el .jsonl original (no borrar) y decidir política distinta para sesiones J (fail-closed).
2. **Ids legado**: filas sin `messageId` → persistir `stable_id` (`legacy-<uuid>` de reacciones) y el `legacy-<index>` de reflexión; `compactionId` **nunca se escribe** y se deriva de `timestamp`+posición en `readConversationRows` tras reordenar steering (LRS:3467-3470). La posición no es `conversation_items.seq`: `session_title` cuenta pese a no tener item y un marcador de entrada cuenta una vez pese a su lista de IDs; las filas result-only estrictas y las líneas inválidas no cuentan. M2 calcula y persiste el sufijo original dentro de `compaction_id`, para que el lector lo recupere sin reconstruir la posición desde el spine.
3. **Dos índices de mensaje** (modelo reordenado vs timeline orden ledger, A.9.2): persistir `message_index` según la regla del modelo y verificar con tests de paridad que coincide con el del timeline para todos los ledgers existentes.
4. **Ids de UI y cursor basados en offset de bytes** (LRS:4338, CLP `olderCursor`): cambiar a `seq`; AgentRunUiState deduplica por id (AgentRunUiState.kt:855) → riesgo de duplicados si conviven eventos de ambas fuentes durante la transición.
5. **Volumen ~48 MB** (dato del encargo, no verificado aquí): la importación debe ser streaming, por conversación, en transacciones acotadas, reanudable (`legacy_ledger_bytes`/sha) y fuera del hilo UI; hoy varias rutas leen todos los ledgers (drawer, `readPendingInterruptRequests()`, `cleanupOrphanAttachments`, MA:924).
6. **Workspace/crew/proactive/tasks**: sesiones especiales con reglas propias (sin adjuntos ni reacciones; central solo muestra notices verificados); filas J requieren texto UTF-16 exacto con surrogates sueltos (LRS:965-976; test `exactUtf16TextSurvivesUtf8LedgerWithoutReplacement`) → TEXT de SQLite vía Android puede reemplazarlos: usar BLOB o escape propio. Dedupe de crew snapshots hoy solo en memoria (LRS:190-195) → los ledgers tienen snapshots repetidos; solo importar el último por misión pero conservar su `seq`.
7. **Concurrencia**: un solo proceso Android, pero múltiples instancias `LocalRunStore` y hilos (servicio, UI, WorkManager) coordinados por `SESSION_TITLE_LOCK` estático y el bloqueo `ConversationMetadataStore.LOCK`. La migración debe ocurrir con el servicio parado o bajo un cerrojo que bloquee escrituras JSONL; después, escritura dual o corte limpio (no ambos fuentes vivas sin reconciliar). `AgentForegroundService` reanuda interrupts al arrancar (AFS:722-825): debe leer de DB ya migrada.
8. **Borrado**: el tombstone `.meta.json deleted` gana siempre; importar a la tabla `tombstones` y no resucitar ledgers medio borrados. `deleted_at` conserva milisegundos aproximados calculados con `julianday('now')`; el trigger `AFTER DELETE ON conversations` inserta el tombstone en la transacción del CASCADE. Los triggers también rechazan tombstones duplicados, `INSERT OR REPLACE` de conversaciones existentes y cambios del ID.
9. **Paridad de proyección**: los límites de página actuales (D) producen diferencias leves; si la DB proyecta globalmente la UI puede cambiar (mejora) — documentarlo. El modelo no debe cambiar: test de oro que compare `loadConversationContext` JSONL vs DB por conversación.
10. **Orden de timestamps**: `timestamp` en segundos double y `timestampMillis`/`producedAtMillis` en ms; no ordenar por timestamp, solo por `seq`.

## G. Tests que dependen del formato JSONL

Escriben/leen el .jsonl a mano (deben migrar a fixtures de DB o a un importador de fixtures JSONL):
`ConversationLedgerPagerTest.java` (todo el pager por bytes; se reescribe/retira), `LocalRunStoreHotPathTest.java` (Files.write fila cruda :160; métricas de escaneo), `LocalRunStoreCrewSnapshotReadTest.java` (nuevo, sin commitear), `LocalRunStoreMessageMetadataTest.kt` (legacy :129-142), `LocalRunAttachmentRecoveryTest.java` (:29-36, 134), `MessageReactionTest.kt` (:33, 209, 350 legado), `MainAgentInteractionTest.kt` (:769-770 reescribe líneas), `MainChatTurnContextProviderTest.kt` (:109-110 appendText), `MainChatToolContinuityTest.java` (:380), `HtmlThumbnailStoreTest.java` (:136, 309, 336-340), `ConversationCompactionTest.kt` (:130, 255), `CrewMissionPersistenceTest.kt` (:37), `WorkspaceChatTimelinePagingTest.kt` (nuevo, :29), `WorkspaceResultLedgerTest.java`, `WorkspaceCoordinationLedgerTest.java`, `WorkspaceInputIncorporationLedgerTest.java` (corrupción de cola, duplicados JSON, UTF-8 inválido, symlinks: semántica fail-closed a reformular), `WorkspaceResultDeliveryTest.kt` (:137), `WorkspaceCoordinationDeliveryTest.kt` (:197), `workspaces/WorkspaceCoordinatorIntegrationTest.kt` (:536), `proactive/ProactiveAgentProcessorTest.kt` (:690).
Usan `appendModelTranscriptRow/readModelTranscriptRows` directamente (forma de fila `model_*`): `CodexReasoningPersistenceTest.java` (:181, 209), `ToolActivityRecoveryTest.kt` (:16-20, 82), `ToolTimelinePersistenceTest.java` (:127-169), `HtmlPreviewSnapshotTest.java` (:317-326), `MainChatToolContinuityTest.java` (:125, 486), `MainAgentInteractionTest.kt` (:1440).
Solo API de `LocalRunStore` (deberían pasar si la API se mantiene; revisar aserciones sobre ficheros): resto de los 63 tests que referencian `LocalRunStore`/`ConversationLedgerPager` (p. ej. AgentRunUiStateTest, ToolTimelineRecoveryTest, UserDecisionHistoryTest, MemoryReflectionTest, ConversationVisibilityTest, DeliveredArtifactTest, ConnectorArtifact*Test, tasks/ScheduledTaskProcessorTest, ui/chat/*).
Estimación: ~20 clases con dependencia directa del formato (reescritura de fixtures), ~6 con forma de fila `model_*` (adaptar a API tipada), ~35 solo API (sin cambios si se conserva la fachada). Los tests de proactive/tasks que mencionan `.jsonl` (TaskStoreTest, TaskTickEngineTest, etc.) usan otros JSONL, no el ledger.
