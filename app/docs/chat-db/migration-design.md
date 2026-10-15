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

Los repositorios M1 (`ConversationRepository`, `ModelTranscriptRepository`, `MemoryTimelineRepository`, `ConversationArtifactsRepository`, `StrictJournalRepository`) implementan escrituras y lecturas tipadas. `ConversationReader` compone esas lecturas bajo una cerca de lectura y ofrece la superficie que se conectará desde la fachada de la app en M4b. Sus métodos proyectan contexto del modelo, mensajes activos, ventanas/timeline UI, decisiones, interrupciones, reflexiones, compactaciones, crew y ownership de artefactos. La semántica directa de repositorios se mantiene en sus tests M1; `ConversationReaderDatabaseTest` cubre las proyecciones del lector sobre una DB real.

## Etapas

- **M0:** Room, esquema, triggers, PRAGMAs y tests de constraints/esquema.
- **M1:** repositorios transaccionales, lecturas por ventana y proyecciones tipadas con DB real.
- **M3:** `ConversationReader` y sus lecturas compuestas sobre SQLite.
- **M4a — limpieza:** retirar importador, selector de fuente, staging y pruebas de paridad con JSONL; dejar el esquema v1 sin columnas/tablas de importación.
- **M4b — cableado:** conectar `LocalRunStore` como fachada sobre repositorios y `ConversationReader`; retirar `ConversationLedgerPager` y las cachés por bytes del ledger de conversaciones.
- **M4c — pruebas y medición:** reescribir pruebas dependientes del JSONL con fixtures de DB, limpiar código que quede sin uso y medir en teléfono apertura, envío con historial grande y memoria.

La sustitución de almacenes de `runs`, `proactive` y `tasks` queda fuera de esta etapa.
