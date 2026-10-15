# Diseño: migración del ledger de conversaciones a SQLite (patrón Kelivo)

> **Estado del esquema M0 (2026-10-14):** la v1 de Room está implementada, regenerada y fijada; el esquema v1 aún no está publicado en un APK, por lo que M1a puede ajustar sus claves sin una migración. El build del arquitecto regenerará el hash tras estos cambios. Cualquier cambio estructural posterior a la publicación requiere subir la versión, añadir una migración explícita y actualizar deliberadamente el identityHash fijado por el test.

Fuentes: `kelivo-db-report.md` (arquitectura de referencia) y `ledger-inventory.md` (46 tipos de fila de Jarvys, escritores/lectores, esquema propuesto, riesgos, tests). Este documento fija las decisiones que guían la implementación y el contrato de migración.

## 1. Alcance
- DENTRO: el ledger por conversación `files/jarvys/conversations/<id>.jsonl` + `.meta.json` (mensajes, marcadores, herramientas, protocolo del modelo, compactación/reflexión, crew snapshots, diarios estrictos de workspace/crew, interrupts, decisiones, reacciones, traducciones, imágenes/archivos entregados).
- FUERA (etapa posterior, mismo patrón): `runs/<runId>/steps.jsonl`, `proactive/*.jsonl`, `tasks/*.jsonl`.
- Archivos (adjuntos, imágenes, previews, artefactos del modelo, entregas) SE QUEDAN EN DISCO. La DB guarda metadatos y referencia (ruta relativa, sha256, tamaño, mime) con tabla de vínculos y GC de huérfanos fuera de la transacción (como `asset_rows`/`message_asset_rows` de Kelivo).

## 2. Tecnología
- Una sola base `jarvys-chat.db`, Room (androidx.room) sobre SQLite del sistema. Motivo: es el estándar de Android (equivalente a drift en Kelivo), con `exportSchema=true` + `MigrationTestHelper` para fijar cada versión de esquema.
- PRAGMAs verificados en `onOpen`: `journal_mode=WAL`, `foreign_keys=ON`, `busy_timeout=5000`, `synchronous=NORMAL`, `wal_autocheckpoint`, `journal_size_limit`. La app corre en un único proceso: una instancia singleton; lecturas concurrentes por WAL; todas las consultas fuera del hilo principal (ejecutor propio), nunca en main.
- Esquema versionado con `user_version`; migraciones aditivas; nunca abrir/borrar una BD de versión más nueva.

## 3. Modelo (normalizado, sin blobs genéricos)
Identidad y orden:
- `conversations` (id, kind, title, generated_title, pinned, archived, private_code, created/updated/last_message_at, message_count, next_seq, import_* de trazabilidad); `tombstones(scope, entity_id, deleted_at, payload)` no tiene FK y conserva el borrado tras CASCADE.
- Contador `seq` monótono por conversación (equivale a la posición en el ledger), asignado en la misma transacción que el insert. Espina `conversation_items(conversation_id, seq, item_kind, sort_seq, sort_sub, turn_anchor_seq)` para paginar/proyectar en orden, incluido el reordenado por steering (`beforeMessageId`).
- Los ids de UI pasan a ser `seq` (no offset de bytes); cursores = `seq` (keyset), como `loadLinearMessageWindow` de Kelivo.

Mensajes y cuerpo (como `message_rows` + `message_part_rows`):
- `messages` (PK conversation_id+message_id; role, status, run_id, owner_user_message_id, before_message_id, proactive_*, interrupt_request_id, timestamps; marcadores como COLUMNAS: `hidden_kind/hidden_seq`, `translation_hidden_seq`, `suggested_replies_consumed_seq`, `input_state/input_state_seq`; `stable_id` y `message_index` persistidos para ids legado y firstKept).
- Cuerpo en `message_parts` (ordinal, kind, payload): Jarvys no versiona mensajes; regenerar oculta el mensaje anterior y crea otro, y `messages.updated_at` acompana el streaming/snapshot.
- Tablas tipadas para: adjuntos, traducciones, reacciones, quick replies, decisiones+opciones, interrupts (+skills), lotes de herramientas, llamadas, resultados, actividades, presentaciones, marcadores de reflexión, errores de modelo, provider output items, artefactos del modelo, entregas, imágenes generadas, compactaciones (+acks), reflexiones (+revisiones), crew (misiones, bots, mensajes, refs), y los 9 diarios estrictos (UNIQUE/FK sustituyen al scan estricto; texto UTF-16 exacto como BLOB).
- JSON opaco SOLO donde el contenido es genuinamente opaco: argumentos de herramienta y provider output items. Crew `inputBinding` y `activity` se normalizan en tablas hijas con sus campos conocidos.
- Todos los `conversation_items` y eventos tipados que exponen timestamp guardan `created_at REAL` en segundos; `seq` sigue siendo el orden y los campos `*Millis` conservan milisegundos.
- Los `seq` propios de filas tipadas tienen FK CASCADE a `conversation_items`; secuencias opcionales de estado como `hidden_seq`, `dispatched_seq` y `last_activity_seq` son referencias lógicas para que borrar un marcador no elimine su entidad propietaria. M1 valida que esas posiciones pertenezcan a la conversación y tendrá test de repositorio.
- `file_refs` identifica cada archivo por `relative_path` UNIQUE y puede indexar `sha256` sin unicidad; sus FKs desde historial usan `SET NULL`. El borrado fisico de archivos ocurre fuera de la transaccion SQLite.
- Los notices/reply intents del ledger central guardan `evidence_id` como referencia logica al ledger de ejecucion; la verificacion cruzada queda en el repositorio porque ambos imports y borrados son independientes.
- Estado de ejecución: tabla `generation_run`/estados activos con optimistic lock y UNIQUE parcial por mensaje activo, y al arrancar los runs activos pasan a `interrupted` (patrón Kelivo) — sustituye los marcadores de recuperación actuales cuando se aborde runs.
- Las claves de los diarios J que se deduplican por ledger incluyen `conversation_id`: `crew_result_evidence` y `crew_coordination_evidence` usan PK `(conversation_id,evidence_id)`, sus referencias hijas usan `(conversation_id,evidence_id,ordinal)`, y `workspace_input_incorporations` usa `(conversation_id,receipt_id)`. Los `evidence_id` que enlazan ledgers siguen siendo referencias lógicas y se verifican junto con `execution_session_id`/`conversation_id`; así, una colisión de ID entre ledgers no descarta una fila legítima ni deja su espina sin dueño.

## 4. Repositorio
- `ConversationRepository` (Kotlin) con operaciones lógicas transaccionales (BEGIN IMMEDIATE): append mensaje, dispatch de interrupt (mensaje + marca), cierre de turno (resultados + input_state), regenerar/borrar/traducir/reaccionar, decisiones, compactación, reflexión, crew snapshot, diarios de workspace, borrado con tombstone + CASCADE.
- Lecturas por ventana (keyset, solo "slots" sin cuerpos) + hidratación por lote con `IN (...)` (sin N+1). Conteos y proyecciones globales en SQL (se acaban las vistas por página con marcadores arrastrados).
- `LocalRunStore` conserva su API pública como fachada delgada sobre el repositorio (los ~35 tests que solo usan la API no cambian).
- Cachés como proyección write-through (DB primero), con contador de revisión de contexto y reintento de lecturas obsoletas; ventanas de rendimiento 40/20; caché acotada por memoria; nunca expulsa mensajes en streaming. Streaming: coalescing latest-wins ≥250 ms con snapshots completos y escritura final autoritativa.

## 5. Invariantes que NO pueden cambiar (de ledger-inventory §A.9/§C)
Historial que ve el modelo idéntico (orden tras steering, índice de mensaje incluyendo ocultos/estado/proactivos, lotes en posición de `model_tool_calls`, primera-gana en resultados, última-gana en provider items, anulación de compactación por regenerar/borrar). Riesgos críticos fijados: (1) `compactionId` nunca se escribió: se calcula como hoy (`legacy-<timestamp>-<rowIndex>`) y se PERSISTE; (2) dos índices de mensaje (modelo vs timeline): se fija UNO persistido (`message_index`, regla del modelo) y se verifica paridad.

## 6. Migración de datos (patrón Kelivo)
- Por conversación, perezosa y también en segundo plano con prioridad baja, en streaming (nunca cargar el ledger entero), transacciones por lote, reanudable (`import_*` + checkpoint), fuera del hilo principal, con el servicio de agente sin escribir en esa conversación durante el corte.
- Se construye en archivo temporal/tablas de staging y se publica al validar: conteos por tipo, DIGEST del texto de mensajes, `integrity_check`/`foreign_key_check`, comparación de paridad de `loadConversationContext` (JSONL vs DB) y de proyección de timeline.
- Reparaciones y reporte: filas corruptas/truncadas (se cuentan y reportan, no se pierden en silencio), ids legado, snapshots de crew duplicados (se importa el último por misión conservando su `seq`), tombstones (`.meta.json deleted` gana; M2 importa tombstones a la tabla sin FK y no resucita ids).
- M2 expande el `compaction_reflection_consumed` legado sin ids a todos los ids de compactacion anteriores en esa conversacion.
- Los triggers se instalan al crear la BD y se reaplican idempotentemente en `onOpen`; cada migracion futura que reconstruya sus tablas tambien debe llamar `DatabaseTriggers.create`. La primera-gana de incorporations/resultados usa `INSERT OR IGNORE` en M1; el test de import idempotente se implementa junto al repositorio M1.
- El JSONL original NO se borra: queda como respaldo de solo lectura hasta que el dueño lo apruebe expresamente. Una vez migrada, la DB es la única fuente de verdad (sin doble escritura).
- Datos reales para prueba: los 4 ledgers del teléfono (48 MB, 36 MB, 9+9 MB) se copian al PC por adb y sirven de golden test local.

## 7. Etapas (cada una: Codex implementa → yo compilo/mido → Opus revisa → PASS para pasar)
- M0 Fundaciones: dependencias Room/KSP, `JarvysChatDatabase`, entidades y DAOs, PRAGMAs, `exportSchema`, tests de esquema/constraints/migración con BD real.
- M1 Repositorio: operaciones transaccionales para TODOS los tipos de fila + lecturas por ventana + proyecciones en SQL; tests por operación (BD real) y carreras (checkpoint tardío, append concurrente).
- M2 Importador: streaming reanudable + validación + reporte; golden tests sobre los ledgers reales y sintéticos de borde (corruptos, legado, UTF-16, 48 MB).
- M3 Lectores: `loadConversationContext`, `MainChatTranscriptStore.restore`, timeline/páginas, drawer, interrupts, adjuntos huérfanos, diarios estrictos pasan a DB; se elimina `ConversationLedgerPager` y todas las cachés por bytes; test de oro de paridad del modelo.
- M4 Escritores: todos los `append*` a transacciones; `LocalRunStore` como fachada; corte limpio.
- M5 Tests y limpieza: reescritura de ~20 clases dependientes del formato JSONL a fixtures de DB; retirada de código muerto; medición en teléfono (apertura del chat, envío con chat grande, memoria).
- M6 (posterior): runs/proactive/tasks al mismo patrón; FTS5 de búsqueda; export/backup si se quiere.

## 8. Cambios de comportamiento aceptados
- La UI proyecta globalmente (no por página): desaparecen las discrepancias entre vista paginada y completa; ids de UI = `seq`.
- Los diarios estrictos pasan de "fail-closed por scan de todo el fichero" a restricciones UNIQUE/FK/CHECK + validación transaccional equivalente (se documentará caso por caso en M1).

## 9. Decisiones que necesito del dueño
1. Room como tecnología (recomendado) frente a SQLiteOpenHelper manual.
2. Alcance de esta migración = solo el ledger de conversaciones (incluye crew/workspace); runs/proactive/tasks después.
3. JSONL conservado como respaldo de solo lectura hasta que lo apruebes.
4. Aceptar la proyección global de UI (mejora) y los ids por `seq`.
5. Mientras tanto: terminar y entregar el arreglo del OOM/carga lenta del Workspace (en curso) como versión intermedia.
