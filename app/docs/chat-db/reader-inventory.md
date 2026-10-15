# Inventario de lecturas de chatdb

## Fuente de verdad

SQLite es la única fuente de conversaciones desde el primer arranque. No hay migración: los `.jsonl` antiguos se ignoran y permanecen intactos en el dispositivo. `ConversationReader` ofrece la API de lectura que la fachada `LocalRunStore` conectará en M4b.

## Superficie de lectura

| API | Resultado y semántica visible | Cobertura actual |
|---|---|---|
| `loadConversationContext`, `readModelTranscript` | Restaura turnos del modelo según steering, ocultación, estado de assistant, resultados de herramientas, provider output y resumen de compactación. El primer resultado de cada llamada gana; provider output conserva el más reciente por dueño. | Tests de `ModelTranscriptRepository` y `MemoryTimelineRepository`; `ConversationReaderDatabaseTest` cubre compactación y primera-gana. |
| `readConversationMessages`, `readActiveConversationMessages`, `readMessage`, `readMessageAttachments` | Lectura por mensaje; la vista activa omite assistant regenerados/eliminados, mantiene mensajes terminales y devuelve adjuntos tipados. | `ConversationRepositoryTest`; `ConversationReaderDatabaseTest` cubre ocultación. |
| `readUserMessagesAfter`, `latestUserMessageId`, `latestUserMessageIndex`, `firstUserMessage`, `findUserMessageBeforeAssistant`, `assistantOwnerUserMessageId`, `proactiveThreadKeyForUserMessage`, `isLatestActiveAssistant` | Identidades y relaciones entre mensajes, con seguimiento de mensajes de usuario posteriores y ownership assistant. | `ConversationRepositoryTest`; `ConversationReaderDatabaseTest` cubre mensajes de seguimiento. |
| `readTailWindow`, `readWindowBefore`, `readRecentConversationTimeline`, `readConversationTimelineBefore` | Ventanas keyset y eventos de mensaje/traducción en orden visible. La traducción oculta y el assistant oculto no se muestran; las reacciones se proyectan al mensaje de usuario y las respuestas proactivas informan si se consumieron. | `ConversationRepositoryTest`, `ConversationReaderPerfTest` y `ConversationReaderDatabaseTest` cubren ventanas, traducciones, reacciones y respuestas proactivas. El compositor de eventos aún no incluye todas las filas de herramientas, reflexión, decisiones, crew o workspace. |
| `readConversationContext` | Cadena acotada para display: hasta los últimos 16 mensajes activos, máximo 3000 unidades UTF-16 por mensaje y 16000 en total. | `ConversationReaderDatabaseTest` fija el truncamiento por unidades UTF-16. |
| `messageCounts`, `listConversations` | Conteos y resumen de conversaciones, con filtros por tipo. No combina ejecuciones de `runs/<id>/steps.jsonl`. | `ConversationRepositoryTest`; prueba específica del resumen pendiente en la fachada. |
| `pendingInterruptRequests`, `readInterruptRequest` | Solicitudes, skills, dispatch, ACK requerido y estados incorporado/resuelto. | `ConversationRepositoryTest`; `ConversationReaderDatabaseTest` cubre dispatch, ACK e incorporación. |
| `readDecisions` | Tarjetas con opciones y última resolución. | `ConversationRepositoryTest`; `ConversationReaderDatabaseTest` cubre opciones y resultado. |
| `pendingCompactionIds`, `buildReflectionPayload`, `hasReflectionCommit`, `readCrewMissionSnapshots`, `recoverCrewMissions`, `globalTimelineMarkers` | Estado de compactaciones y ACK, payload de reflexión y snapshots de crew. | `M1bRepositoriesTest` y tests de memoria cubren semántica de repositorio; lector directo pendiente para payload de reflexión y recuperación de crew. |
| `strictJournals` | Lectura fail-closed de evidencias, referencias, notices, replies e incorporations tipadas de workspace/crew. | `StrictJournalRepositoryTest` verifica validación, primera-gana y preservación de UTF-16. |
| `findChatFile`, `unreferencedFileRefs`, `artifactsForConversation`, `ownsGeneratedFile`, `conversationHasAttachments`, `conversationHasPrivateImagesOrAttachments`, `readConversationImageReferences` | Ownership y metadatos de adjuntos/artefactos; los bytes y la recolección física siguen en filesystem. | `M1bRepositoriesTest`; integración de GC y filesystem pendiente. |

## Fuera de este lector

- `runs/<runId>/steps.jsonl`, `proactive/*.jsonl`, `tasks/*.jsonl` y el journal `memory.json` de `MemoryStore` son almacenes separados.
- El compositor `AgentRunUiEvent` cubre mensajes y traducciones. Las filas tipadas restantes siguen accesibles por sus repositorios y se conectarán según las necesidades de la UI.
- La limpieza física de archivos huérfanos debe coordinar referencias de todas las conversaciones y ejecutarse fuera de la transacción SQLite.

## Próximas etapas

- **M4b:** cablear `LocalRunStore` como fachada sobre repositorios y `ConversationReader`; eliminar `ConversationLedgerPager` y cachés por bytes del ledger.
- **M4c:** reescribir tests dependientes del JSONL con fixtures de DB, limpiar código restante y medir apertura, envío con historial grande y memoria en teléfono.
