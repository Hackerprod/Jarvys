# Diseño de chatdb sobre SQLite

> **Política de versiones (2026-10-16):** v1 quedó publicada con sqlite2 (`20f48cd`). v2 conserva v1 como histórico y aplica un reset destructivo solo al abrir v1, por decisión del dueño; no se recuperan sus chats ni datos. Todo cambio posterior de esquema requiere subir la versión y añadir una migración o acordar explícitamente otro reset.

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

Los repositorios M1 (`ConversationRepository`, `ModelTranscriptRepository`, `MemoryTimelineRepository`, `ConversationArtifactsRepository`, `StrictJournalRepository`) implementan escrituras y lecturas tipadas. `ConversationReader` compone esas lecturas bajo una cerca de lectura. M4c completó la proyección DB del timeline para mensajes, actividad de herramientas, progreso, compaction/reflection, decisiones, interrupts, snapshots Crew, artifacts y notices de workspace. Los IDs que expone la UI son el `seq` físico y las páginas usan keyset. `LocalRunStore` ya no contiene lectores, escritores ni fallbacks de ledger JSONL conversacional; mantiene únicamente `runs/*/steps.jsonl` y otros datos no conversacionales. Véase [`cutover-inventory.md`](cutover-inventory.md) y [`m4c-triage.md`](m4c-triage.md).

## Etapas

- **M0:** Room, esquema, triggers, PRAGMAs y tests de constraints/esquema.
- **M1:** repositorios transaccionales, lecturas por ventana y proyecciones tipadas con DB real.
- **M3:** `ConversationReader` y sus lecturas compuestas sobre SQLite.
- **M4a — limpieza:** retirar importador, selector de fuente, staging y pruebas de paridad con JSONL; dejar el esquema v1 sin columnas/tablas de importación.
- **M4b — cableado:** conectar `LocalRunStore` como fachada sobre repositorios y `ConversationReader`; retirar `ConversationLedgerPager` y las cachés por bytes del ledger de conversaciones.
- **M4c — pruebas y medición:** reescribir pruebas dependientes del JSONL con fixtures de DB, limpiar código que quede sin uso y medir en teléfono apertura, envío con historial grande y memoria.

La sustitución de almacenes de `runs`, `proactive` y `tasks` queda fuera de esta etapa.

## Estado de M4c — corte de conversaciones (2026-10-15)

El inventario de corte actualizado está en [`cutover-inventory.md`](cutover-inventory.md). `ChatDatabaseBridge` abre Room perezosamente en su executor, conserva el primer error de apertura, lo informa una vez vía `AgentErrorReporter` y rechaza llamadas bloqueantes desde main/lease y reentrada desde su executor. El timeline proyecta los eventos persistidos con `seq` como ID y paginación keyset; una lectura completa mantiene una cerca y carga las tablas laterales una vez. `MainChatTranscriptStore` incluye los artefactos aún no confirmados al contar la cuota del checkpoint. Se eliminaron la caché/pager por bytes y el código JSONL conversacional de `LocalRunStore`; los archivos JSONL independientes de runs, proactive y tasks permanecen fuera del corte.

Se añadió `ChatTestStore`, una regla única con DB Room real por directorio temporal, worker para llamadas bloqueantes y cierre del bridge/executor antes de borrar el fixture. Se portaron a ese helper los 26 tests con `LocalRunStore(File)` identificados en HEAD y se preservaron sus aserciones de producto. STOP ahora se persiste fuera del hilo principal en una transacción terminal, con reporter de errores; el drawer solo incluye conversaciones vivas de DB y resúmenes de runs que apuntan a ellas. La admisión y despacho de steering comparte una transacción; el ACK sigue ligado a la incorporación al transcript. El detalle de fixtures, casos retirados y categoría D está en [`m4c-triage.md`](m4c-triage.md).

No se importan conversaciones del almacenamiento anterior. Room parte vacío; los archivos históricos `conversations/*.jsonl` y `*.meta.json` se dejan sin leer ni borrar. Las columnas de `conversations` y `tombstones` pasan a ser autoridad para título, fijado, archivado, privacidad y borrado. Los bytes de adjuntos y artefactos siguen en disco, con su referencia y metadatos en SQLite. No se ejecutaron compilación ni tests en esta pasada; quedan para el arquitecto. Las trazas Windows de los fallos de categoría D no estaban en el inventario de fallos disponible y no se generaron nuevas al no ejecutar tests.

Los `.jsonl` que pertenecen a los diarios de conversación de `jarvys-central`, `jarvys-workspace-*`, `jarvys-proactive` y `jarvys-tasks` entran al corte aunque esos IDs representen workspaces, conversaciones proactivas o tareas programadas. Los diarios de runs (`runs/*/steps.jsonl`), los eventos/decisiones proactivos que no son mensajes, los estados de tareas y los almacenes de memoria no conversacionales quedan fuera.
