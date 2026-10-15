# Diseño de chatdb sobre SQLite

> **Estado del esquema v1 (2026-10-15):** la versión aún no se publica. M4a elimina campos y tablas del importador; el build del arquitecto regenerará el esquema Room y fijará el `identityHash` en `JarvysChatDatabaseM0Test`.

## Decisión de almacenamiento

SQLite es la única fuente de verdad desde el primer arranque. No hay migración de conversaciones antiguas: los `.jsonl` existentes se ignoran y no se borran. Las conversaciones empiezan vacías en SQLite.

El alcance de `chatdb` incluye mensajes, marcadores, protocolo del modelo, compactaciones y reflexiones, crew snapshots, diarios estrictos de workspace/crew, interrupciones, decisiones, reacciones, traducciones, adjuntos y artefactos. Los bytes de archivos permanecen en disco; SQLite guarda metadatos y referencias. `runs/<runId>/steps.jsonl`, `proactive/*.jsonl`, `tasks/*.jsonl` y `MemoryStore` son almacenes distintos.

## Esquema y semántica

- `conversations` guarda metadatos, contadores y `next_seq`; `tombstones` conserva borrados independientemente del CASCADE. No existen checkpoints, recibos ni staging de importación.
- `conversation_items(conversation_id, seq, item_kind, item_key, sort_seq, sort_sub, turn_anchor_seq)` es la espina ordenada. `seq` es monótono y asigna el cursor de lectura; `sort_seq/sort_sub` reflejan el orden visible cuando un mensaje se inserta antes de otro por steering.
- `messages` y sus tablas hijas guardan cuerpos, adjuntos, traducciones, reacciones, respuestas sugeridas, interrupciones y decisiones. Los repositorios conservan primera-gana, última-gana, ocultación, ACK y límites de texto que ven el modelo y la UI.
- Los artefactos normalizados usan claves, índices y FKs; JSON se conserva solo para payloads genuinamente opacos. Los diarios estrictos preservan bytes UTF-16 en BLOB.
- Toda escritura lógica —incluida la asignación de secuencia y sus marcadores— ocurre en una transacción Room.

## Concurrencia y lecturas

- Room escribe en un solo hilo dedicado. El pool propio de solo lectura usa handles independientes sobre WAL; nunca inicia una transacción Android en esos handles.
- Una proyección multi-consulta lee `PRAGMA data_version` antes y después. Si cambió, repite una vez bajo el lease escritor para obtener una vista consistente y garantizar progreso. Las lecturas dentro de un lease escritor reutilizan su conexión y ven sus propias escrituras.
- El lease captura el hilo y dispatcher propietarios. Un salto de hilo/dispatcher se rechaza antes de usar la conexión y revierte la transacción. Los `ThreadContextElement` solo instalan/restauran el contexto; la validación se hace al usar el lease y el hook de contexto no lanza.
- Se mantienen los tests de lease, lectura durante escritura, `data_version`, salto de dispatcher, rollback, `ChatReadProgressTest` y las demás pruebas de concurrencia existentes.

## Repositorios y lector

Los repositorios M1 (`ConversationRepository`, `ModelTranscriptRepository`, `MemoryTimelineRepository`, `ConversationArtifactsRepository`, `StrictJournalRepository`) implement escrituras y lecturas tipadas. `ConversationReader` compone esas lecturas bajo una cerca de lectura. M4b añadió `ChatDatabaseBridge` y conectó varios grupos de `LocalRunStore`; la fachada aún conserva código legacy JSONL y rutas conversacionales pendientes, que ahora fallan cerradas. Véase [`cutover-inventory.md`](cutover-inventory.md). El timeline DB proyecta mensajes y traducciones; faltan eventos de tools/progreso, reflexión, decisiones, crew, artefactos y notices de workspace. `ConversationReaderDatabaseTest` cubre las proyecciones existentes sobre una DB real.

## Etapas

- **M0:** Room, esquema, triggers, PRAGMAs y tests de constraints/esquema.
- **M1:** repositorios transaccionales, lecturas por ventana y proyecciones tipadas con DB real.
- **M3:** `ConversationReader` y sus lecturas compuestas sobre SQLite.
- **M4a — limpieza:** retirar importador, selector de fuente, staging y pruebas de paridad con JSONL; dejar el esquema v1 sin columnas/tablas de importación.
- **M4b — cableado:** conectar `LocalRunStore` como fachada sobre repositorios y `ConversationReader`; retirar `ConversationLedgerPager` y las cachés por bytes del ledger de conversaciones.
- **M4c — pruebas y medición:** reescribir pruebas dependientes del JSONL con fixtures de DB, limpiar código que quede sin uso y medir en teléfono apertura, envío con historial grande y memoria.

La sustitución de almacenes de `runs`, `proactive` y `tasks` queda fuera de esta etapa.

## Estado de M4b — corte de conversaciones (2026-10-15)

El inventario de corte está en [`cutover-inventory.md`](cutover-inventory.md). Estado de esta revisión: cableado parcial; M4b no está completo. `ChatDatabaseBridge` abre Room perezosamente en su executor y rechaza llamadas bloqueantes desde main/lease. `LocalRunStore(Context)` enruta a DB metadata/tombstones, mensajes/contexto/timeline keyset, historial de conversaciones, model transcript tipado, memoria/compaction/reflection, operaciones proactive/crew, diarios estrictos workspace/crew, artifacts/imágenes, translations/decisions/reactions, interrupts y hide/delete/regenerate. `conversationFile()` falla cerrado para impedir que se abran ledgers históricos; siguen parsers/helpers/cachés legacy y falta la transacción compuesta de model checkpoint+ACK. Los estados incorporated/resolved, sus lecturas auxiliares y `claimProactiveSuggestedReply` ya usan DB. No se han retirado Pager ni cachés. La pantalla informa errores de DB en ciertos caminos y puede restaurar timeline vacío; la recuperación de fallo de apertura no se ha verificado de extremo a extremo.

En la revisión actual se reescribieron cero tests y se eliminaron cero tests. La clasificación concreta para el trabajo pendiente de M4c está en `cutover-inventory.md`; no se considera que el formato JSONL histórico sea necesario para la fuente nueva.

No se importan conversaciones del almacenamiento anterior. Room parte vacío; los archivos históricos `conversations/*.jsonl` y `*.meta.json` se dejan sin leer ni borrar. Las columnas de `conversations` y `tombstones` pasan a ser autoridad para título, fijado, archivado, privacidad y borrado. Los bytes de adjuntos y artefactos siguen en disco, con su referencia y metadatos en SQLite.

Los `.jsonl` que pertenecen a los diarios de conversación de `jarvys-central`, `jarvys-workspace-*`, `jarvys-proactive` y `jarvys-tasks` entran al corte aunque esos IDs representen workspaces, conversaciones proactivas o tareas programadas. Los diarios de runs (`runs/*/steps.jsonl`), los eventos/decisiones proactivos que no son mensajes, los estados de tareas y los almacenes de memoria no conversacionales quedan fuera.
