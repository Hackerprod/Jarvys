# Kelivo: modelo y operacion de la BD de chats (informe de arquitectura)

Repo: C:\Users\danil\AppData\Local\Temp\kelivo-ref (commit d4173e4). Rutas relativas a lib/core/ salvo indicacion.
Archivos clave: database/app_database.dart (950 l, esquema drift), database/chat_database_repository.dart (8313 l),
database/message_timeline_index.dart, database/generation_run*.dart, database/schema_migrations.dart,
services/chat/chat_service.dart (4565 l, caches), features/migration/hive_to_sqlite_migration_service.dart (Hive->SQLite),
features/home/controllers/latest_wins_checkpoint_writer.dart (flush de streaming).

## 1. Esquema exacto

Un unico archivo `kelivo.db` (app_database.dart:AppDatabase.databaseFileName). Fechas = INTEGER en microsegundos epoch
(MicrosecondDateTimeConverter). Booleanos = INTEGER 0/1. Nombres de tabla/columna en snake_case (drift).

### Tablas de chat
- conversation_rows: id TEXT PK; title TEXT; created_at, updated_at INT; is_pinned BOOL def 0; assistant_id TEXT null;
  truncate_index INT CHECK>=-1 def -1; version_selections_json TEXT def '{}' (mapa groupId->version elegida);
  summary TEXT null; last_summarized_message_count INT CHECK>=0 def 0; chat_suggestions_json TEXT def '[]';
  injected_memory_hash TEXT null; last_memory_extracted_order INT CHECK>=-1 def -1;
  v2: chat_model_provider, chat_model_id TEXT null; v3: extras_json TEXT def '{}'.
  Indices: idx_conversations_updated_at (updated_at DESC, id ASC); idx_conversations_assistant(assistant_id).
- message_rows (cada fila = UNA REVISION/version de un mensaje): id TEXT PK; conversation_id TEXT FK->conversation_rows
  ON DELETE CASCADE; role TEXT CHECK != ''; timestamp INT; model_id, provider_id TEXT null;
  total_tokens/prompt_tokens/completion_tokens/cached_tokens/duration_ms INT null CHECK>=0; is_streaming BOOL def 0;
  reasoning_start_at, reasoning_finished_at INT null; translation TEXT null; reasoning_segments_json TEXT null;
  group_id TEXT null; version INT CHECK>=0 def 0; message_order INT CHECK>=0 NOT NULL;
  v3: updated_at INT null (null = "nunca actualizado", leer como COALESCE(updated_at,timestamp)), sender_id TEXT null,
  extras_json TEXT def '{}' (tokens extra: firstTokenMs, reasoningTokens, cacheWrite, finishUsage).
  UNIQUE(conversation_id, message_order); UNIQUE(conversation_id, group_id, version).
  Indices: idx_messages_conversation_order(conversation_id,message_order,id); idx_messages_conversation_timestamp
  (conversation_id,timestamp,id); idx_messages_group(conversation_id,group_id,version,id);
  PARCIAL idx_message_rows_streaming(id) WHERE is_streaming=1.
  NO hay columna content: el texto vive solo en message_part_rows (test "message_rows no contiene content/reasoning_text
  shadow columns", streaming_checkpoint_test:565).
- message_part_rows (cuerpo del mensaje): part_id INT PK AUTOINCREMENT; conversation_id TEXT; revision_id TEXT
  (= message_rows.id); ordinal INT CHECK>=0; kind TEXT CHECK != ''; payload TEXT; created_at, updated_at INT.
  UNIQUE(revision_id, ordinal). FK revision_id->message_rows(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
  (customConstraints) + CHECK(updated_at>=created_at). Indice idx_message_parts_revision_ordinal(conversation_id,revision_id,ordinal).
- provider_artifact_rows: PK(revision_id, kind); conversation_id; payload TEXT; created/updated; FK revision_id->message_rows CASCADE
  deferred. Idx(conversation_id,revision_id,kind). Guarda opacos del proveedor (turno Claude, thinking recovery, firma
  Gemini, turno Responses, OCR de imagenes).
- message_prompt_rows: PK revision_id (FK cascade); conversation_id; payload (prompt congelado); carries_memory_snapshot BOOL.
- conversation_mcp_server_rows: PK(conversation_id, server_id); ordinal CHECK>=0; UNIQUE(conversation_id, ordinal); FK cascade.
- generation_run_rows: id PK; conversation_id FK cascade; target_revision_id FK->message_rows deferred; state TEXT CHECK IN
  (preparing, requesting, streaming, waiting_tool, completed, failed, cancelled, interrupted); state_revision INT>=0 (optimistic lock);
  checkpoint_seq INT>=0; error_code; created/updated/terminal_at. CHECKs: terminal_at null <=> estado activo; error_code solo en
  failed/cancelled/interrupted (1..128 chars). UNIQUE parcial idx_generation_runs_active_target(conversation_id,target_revision_id)
  WHERE state IN (activos) => una sola generacion activa por mensaje. Idx(state,updated_at,id).
- Assets: asset_rows (id PK, content_hash UNIQUE, path, byte_size, width/height>0 null, thumbnail_path, created_at, last_referenced_at,
  v3 extras_json); message_asset_rows PK(revision_id,asset_id,kind) FKs cascade, idx(asset_id,revision_id); asset_gc_rows
  (asset_id PK, not_before, attempts, generation); gc_audit_rows; asset_reference_dirty_rows(revision_id PK). Ficheros
  deduplicados por hash; GC diferido (_assetGcDelay 7 dias) con "claim" y revalidacion (chat_database_repository.dart:claimAssetGc/completeAssetGc).
- chat_storage_meta_rows (key PK, value): database_identity_v1, hive_migration_complete_v1, sandbox_path_migration_version,
  asset_reference_backfill_version (ChatStorageMetaKeys, repo l.8304).
- tombstone_rows (v3): PK(scope,entity_id), deleted_at, payload. Solo scope='conversation', se escribe en la misma transaccion
  que deleteConversation, poda a 90 dias. Preparado para sync multi-dispositivo.
- extension_entity_rows (v3): PK(kind,id), sort_order, owner_id, payload; "tabla anfitriona" para evitar migraciones futuras.
- Tablas de configuracion (no chat): assistant_rows, provider_rows, provider_group_rows, mcp_server_rows, world_book_rows,
  quick_phrase_rows, search_service_rows, tts_service_rows, instruction_injection_rows, assistant_tag_rows, preference_rows,
  assistant_memory_rows, memory_entry_rows (CHECKs de scope/type/status), user_profile_field_rows. Patron comun: id + sort_order +
  payload JSON + updated_at (el payload es un blob JSON, sin columnas normalizadas).
- Derivadas/reconstruibles (creadas por SQL crudo, no por drift): message_search_fts (FTS5), timeline_state/group_rows/revision_rows/selection_rows.

### PRAGMAs (app_database.dart:_openExecutor setup, 834-841)
journal_mode=WAL; foreign_keys=ON; busy_timeout=5000; synchronous=NORMAL (1) (justificado: FULL anade fsync en la ruta caliente de
streaming; tras perdida de energia solo se pierden tx desde el ultimo checkpoint y la recuperacion de runs lo tolera);
wal_autocheckpoint=1000 paginas; journal_size_limit=16 MiB. validateConnectionContract() (repo l.1433) relee cada PRAGMA tras abrir y
falla si no coinciden. Conexion de migracion aparte (_migrationSetup): sin WAL, synchronous=FULL, foreign_keys=ON.

### Version y migraciones
PRAGMA user_version = schema (currentSchemaVersion = 3; publishedSchemaVersions = {1,2,3}). v1 contrato inicial, v2 override de modelo
por conversacion, v3 columnas extras_json/updated_at/sender_id + tombstone + extension_entity. Migraciones: drift `stepByStep`
(app_database.dart:917-944), todas ADITIVAS (addColumn, createTable); los indices nuevos hay que crearlos a mano en el paso
(m.create). Reglas documentadas en schema_migrations.dart:27-54: columnas nuevas SIEMPRE al final de la tabla (el repo valida el
orden exacto de columnas, _validateRawSchema l.1009, para que ALTER ADD COLUMN == createAll); se congelan los JSON de esquema
publicados (drift_schemas/app_database/drift_schema_v{1,2,3}.json) y se generan los tests de cada version.
Una apertura normal NO migra: AppDatabase.acceptsLiveSchema solo admite 0 o la version actual; si no, StateError
'database_schema_version'. La migracion solo ocurre por SchemaMigrations.upgradeFileInPlace (conexion especial). Downgrade: onDowngrade
sin definir = error; version desconocida/futura = 'database_schema_too_new' sin tocar nada.

## 2. Modelado

- Conversacion: fila en conversation_rows. NO guarda lista de mensajes; messageIds se reconstruye con SELECT cuando hace falta
  (_conversationFromRow includeMessageIds:false en la ruta normal). Seleccion de version en JSON (version_selections_json).
  extras_json = punto de extension sin esquema con claves prefijadas ("workspace.cwd"); regla: si necesita indice/FK/CHECK se promueve a
  columna en una migracion aditiva.
- Mensaje / revision: un "mensaje logico" es un GRUPO (group_id; si group_id es NULL el grupo es el propio id). Cada version (regeneracion,
  edicion) es OTRA fila de message_rows con mismo group_id y version=max+1 (repo:_appendMessageVersion l.4990). El orden visual lo da
  message_order (global por conversacion, UNIQUE); el grupo se ancla en MIN(message_order) y se muestra la version seleccionada (o la
  ultima si la seleccionada no existe). Borrar la fila ancla reasigna su message_order al superviviente para que el grupo no salte al final
  (repo:_deleteMessages l.6322).
- Partes (message_part_rows): kind sealed class (message_part.dart): text (payload = texto plano), reasoning (texto), tool_call
  (payload = JSON del evento: nombre, args, resultado, items), assistant_round_end (JSON reasoningDetails), image/file (JSON
  {uri,mime,assetId,name,unavailable}), provider_auth_error, UnknownPart (kind futuro, round-trip), MalformedPart (payload que no
  parsea, se conserva crudo). Orden de intercalado = ordinal. No hay parte tool_result (test l.579): el resultado vive dentro del
  payload JSON del tool_call. Eventos de herramienta = partes tool_call; "toolEvents" es solo un overlay de compatibilidad que
  _applyToolEventsToParts fusiona por id (repo l.7246).
- Artefactos del proveedor: tabla aparte provider_artifact_rows (PK revision_id+kind) porque son opacos, por-revision y deben copiarse
  /heredarse al editar (appendMessageVersion los conserva o edita segun si cambia el texto).
- Normalizado: identidad, orden, version, tokens, timestamps, estado de streaming, assets (con hash), runs. Blob/JSON: payload de
  parte, version_selections_json, chat_suggestions_json, extras_json, reasoning_segments_json, todas las tablas de configuracion.
  Por que partes aparte: el texto cambia 4/seg en streaming; reescribir una fila ancha de mensaje seria caro y FTS necesita una fila
  por texto.
- Busqueda: FTS5 externo (content='message_part_rows', content_rowid='part_id', unicode61 remove_diacritics 2) + fallback LIKE para CJK;
  triggers indexan solo partes text de mensajes con is_streaming=0; trigger "finalize" indexa al pasar is_streaming 1->0, "unindex"
  al reabrir (repo:_ensureMessageSearchFts l.4090). Perezoso: se crea en la primera busqueda y se reconstruye si falta.
- Estado de generacion: tabla generation_run_rows con maquina de estados y bloqueo optimista (state_revision) en
  generation_run_commands.dart:transition (UPDATE ... WHERE id AND state=expected AND state_revision=expected; 0 filas => conflicto).

## 3. Repositorio (chat_database_repository.dart)

- Inicio de turno atomico: beginSendGeneration (l.4650) = UNA transaccion: append mensaje usuario + append mensaje asistente (vacio,
  is_streaming) + crear generation_run (+ consumir borrador del compositor). beginRegeneration / beginAssistantGeneration igual, con
  opcion truncateFuture (_truncateLinearMessageGroupsAfter).
- Append: _appendLinearMessageToConversation (l.4852): upsert conversacion + _nextMessageOrder (SELECT MAX+1) + INSERT message_row +
  _replaceMessageParts, todo en tx. Insertar tras un grupo (afterGroupId): _makeMessageOrderAfterGroup (l.6919) desplaza el sufijo con
  dos UPDATE set-based (primero +offset sobre el rango, luego -offset+1) para esquivar el UNIQUE inmediato.
- Streaming checkpoint: updateStreamingCheckpoint (l.6164) = una tx: (1) si el snapshot es streaming y la fila ya esta finalizada,
  descartar (evita "resurreccion" por flush tardio); (2) _updateMessageRow; (3) _replaceMessageParts; (4) GenerationRunCommands.checkpoint
  (checkpoint_seq estrictamente creciente, solo en estados activos). Se escribe SNAPSHOT COMPLETO de partes cada vez (no deltas).
  _replaceMessageParts (l.4900): borra ordinales sobrantes y hace batch de INSERT ... ON CONFLICT(revision_id,ordinal) DO UPDATE ...
  WHERE kind/payload/conversation_id/created_at distintos => las partes sin cambios no generan escritura ni trigger FTS.
- Flush/frecuencia: LatestWinsCheckpointWriter (features/home/controllers/latest_wins_checkpoint_writer.dart): cola de 1 elemento
  (solo el pendiente mas nuevo), intervalo minimo 250 ms entre INICIOS de escritura, serializado; errores de checkpoints intermedios se
  descartan (el siguiente los sustituye); finalize() cierra la cola, descarta pendiente, espera el en vuelo y hace la escritura final
  autoritativa. barrier() antes de ejecutar una herramienta. Un cierre (finalizeGenerationRun) pasa run a estado terminal y is_streaming=0
  en una tx.
- Lectura por ventana: loadLinearMessageWindow (l.2922): devuelve SLOTS (groupId, revisionId elegida, versionCount, logicalIndex) sin
  hidratar cuerpos; parametros beforeRevisionId/afterRevisionId/aroundRevisionId/fromStart/limit (def 40), un solo cursor permitido.
  Usa keyset por tupla (anchor_order, group_id) > (?,?) sobre timeline_group_rows, ORDER BY ... LIMIT; total de grupos desde
  timeline_state.group_count; logicalIndex = total - COUNT(sufijo) - 1; hasMoreBefore/After por aritmetica. Todo dentro de
  _db.transaction (snapshot consistente). Despues el servicio llama getMessagesByIds(revisionIds).
- Indice de timeline (message_timeline_index.dart): tablas derivadas timeline_state(group_count,message_count,ready),
  timeline_group_rows(conversation_id,group_id,anchor_order,version_count), timeline_revision_rows, timeline_selection_rows
  (desde json_each(version_selections_json)), mantenidas por 8 TRIGGERS (insert/delete/update de message_rows, orden, seleccion).
  Construccion perezosa por conversacion al primer acceso (ensureConversation, idempotente y con recheck en tx); reconstruible y se
  descarta en backups/restores (discardStatements). Fuente de verdad = message_rows + version_selections_json. SQL selectedRevision():
  version elegida si existe, si no la de mayor version.
- Anti N+1: _messagesFromRowsWithParts (l.7130) carga TODAS las partes de N mensajes con una sola query `revision_id IN (...)`
  ORDER BY ordinal y agrupa en memoria; getAllConversationSummaries lee conversation_mcp_server_rows en bloque y no carga messageIds;
  getToolEventsForMessages / getProviderArtifactsForMessages / getMessageCountsByConversation en lote; getMessagesByIds preserva el
  orden pedido. getMessagesRange usa LIMIT/OFFSET (solo para rangos puntuales).
- Contexto para el modelo: getSelectedContextMessages (l.2529) en una tx: aplica truncate_index, tope superior "through revision",
  toma las ultimas `limit` revisiones seleccionadas via timeline y solo entonces hidrata. getSelectedMessageProjections y
  searchMiniMapMatches usan CTE con ROW_NUMBER() OVER (PARTITION BY grupo) y GROUP_CONCAT de partes text.
- Conteos: getMessageCount, getMessageCountsByConversation, getConversationCount, getTotalMessageCount, conteos por kind de parte
  (text/tool_call/image/file), queryStatsAggregate (GROUP BY, strftime por dia, json_extract en extras_json).
- Borrado: deleteConversation = DELETE conversation_rows (CASCADE limpia mensajes/partes/artefactos/runs/assets-links) + tombstone +
  poda de tombstones, en tx con el borrador del compositor. deleteMessages (l.6322): tx que valida ids, reancla grupos, borra runs y
  artefactos de esas revisiones, luego mensajes (CASCADE partes), reescribe seleccion de versiones y devuelve conversacion actualizada.
  Assets huerfanos: scheduleUnreferencedAssetGc + claim/validate/complete (nunca borrar fichero en la tx).
- Otras: duplicateConversation, forkConversationWithVersions, mergeBackupSnapshot (ids determinísticos por fingerprint),
  updateConversationExtras (read-modify-write en tx), migrateSandboxPaths (reescribe URIs de adjuntos con recibo de version),
  resetStaleStreamingState (l.6838): en arranque, tx que pasa todos los runs activos a 'interrupted'(error_code app_restart) y
  is_streaming=0 (dispara FTS finalize).
- Observabilidad: ChatDatabaseObserver.measure(op, fn, resultCount) envuelve casi toda operacion (metricas por operacion).

## 4. Capa de servicio con caches (chat_service.dart)

- Ventanas de rendimiento (solo contexto): defaultTimelineInitialSlots=40, defaultHistoryPageSize=20, defaultLoadedWindowMax=360
  (la UI descarta extremos al superarlo; chat_controller.dart:380), defaultInitialMessageMin/Max=2/240, defaultInitialTextBudget=20000.
- Caches (l.106-150): _conversationsCache (id->Conversation resumen, cargado en init con getAllConversationSummaries, ordenado por
  updated_at; _sortedConversationsCache con revision), _messagesCache (conversacion->lista de mensajes ya cargados, parcial),
  _toolEventsCache y _providerArtifactsCache (por mensaje), _firstGroupIndicesCache, _messageCounts (perezoso por conversacion, NUNCA
  agregado global en arranque), _messageOrderIds (invariante: ausente o lista COMPLETA, nunca parcial), cache de hash de imagen (256, validado por longitud+mtime).
- Tope de cache de mensajes: 720 entradas y 8 MiB estimados (_enforceMessageCacheLimits l.1002); expulsa mensajes no-streaming de las
  conversaciones mas antiguas (LRU por orden de insercion del Map con _touchMessageCache); jamas expulsa mensajes en streaming ni
  conversaciones temporales. Al expulsar un mensaje se expulsan sus tool events/artefactos (_evictMessageCaches).
- loadTimelinePage (l.527): (1) conversacion temporal -> memoria; (2) resuelve conteo; (3) FAST PATH de cola desde cache si esta
  completa (_tryLoadCachedTailPage, con kill-switch timelineCacheFastPathEnabled y verificacion en debug contra BD); (4) si no,
  repo.loadLinearMessageWindow + getMessagesByIds; (5) _contextRevisions[conversationId] como contador de generacion: si cambia durante
  las lecturas asincronas (version seleccionada, mutacion) se REINTENTA la carga entera -> evita devolver paginas obsoletas.
- Invalidacion: _bumpContextRevision en mutaciones estructurales; _loadConversationsCache limpia todo; escrituras de checkpoint
  actualizan el cache (_replaceCachedMessage) tras confirmar la BD (write-through, la BD primero).
- Warmup: init() -> abre via ChatDatabaseGateway (lease), _migrateSandboxPaths versionado (no escanea filas en arranques normales),
  _loadConversationsCache, _resetStaleStreamingFlags, composerDrafts.initialize, mantenimiento de assets post-arranque (async).
  El indice de timeline de cada conversacion se construye solo al abrirla.
- ChatDatabaseGateway (chat_database_gateway.dart): singleton con leases contados; una sola AppDatabase por ruta; acquire serializa
  apertura/cierre; mismatch de ruta lanza error.

## 5. Migracion de datos legacy y verificacion

- Hive -> SQLite (features/migration/hive_to_sqlite_migration_service.dart:migrate l.569). Detecta conversations.hive/messages.hive/
  tool_events_v1.hive (check() l.224). Flujo: (a) backup opcional/ofrecido a ZIP (backupTo*), (b) abre las cajas Hive LAZY, (c) escribe a
  un archivo TEMPORAL `kelivo.db.migrating` con un repo nuevo (nunca toca la BD instalada), (d) lee todas las conversaciones tolerando
  registros ilegibles (se cuentan y saltan; Hive no se borra), (e) reparaciones: ids de mensaje duplicados, conversation_id incoherente,
  campos numericos sucios (legacy_record_sanitizer.dart), (group_id,version) duplicados -> se reasigna version=max+1, conversion de
  truncateIndex y versionSelections de indice legacy a modelo de grupos, decodifica marcadores de adjuntos embebidos en el texto
  (legacy_message_content_decoder.dart) a partes image/file, (f) escritura por lotes putMigrationBatch (una tx por lote),
  (g) _validate: compara conteo de conversaciones, mensajes, partes tool_call/image/file, un DIGEST (hash) de todo el texto
  esperado calculado de forma independiente vs el leido de la BD (getTextPartContentDigest, en isolate), validateAttachmentPartPayloads,
  PRAGMA integrity_check/foreign_key_check; (h) markMigrationComplete (recibo en chat_storage_meta_rows), checkpoint WAL,
  cierre, (i) _replaceSqlite: publica el .migrating como definitivo (el anterior queda como `.previous`), (j) en fallo borra
  el .migrating; Hive queda intacto. Estado de intento persistido (_beginAttempt/_recordStageBreadcrumb) para diagnostico tras crash.
- Retirada de Hive: LegacyDataRetirementService solo borra Hive tras accion explicita del usuario y si el recibo en BD
  (HiveMigrationMarker.isMigrationComplete, lectura read-only de la BD) lo permite; marcador `deleting` crash-resumable.
- Migracion de esquema SQLite instalada: ChatDatabaseRepository.migrateInstalledDatabase (l.359): si version==actual solo valida
  estructura (sin escrituras); si es antigua publicada: wal_checkpoint(TRUNCATE) -> copia `kelivo.db.premigrate-v<N>` con fsync del
  archivo y del directorio -> upgradeFileInPlace (onUpgrade + user_version en UNA tx de drift, atomico) -> _validateRawStructure
  (tablas requeridas, columnas en orden, PK, CHECKs, indices, FKs) -> si todo ok borra copia; si falla, restorePreMigrationBackup y
  'database_migration_failed:N'. La copia se deja a proposito si el proceso muere; DatabaseInstallationGate._sweepPreMigrationBackups
  completa el rollback en el siguiente arranque. classifyInstalledDatabase distingue usable / foreignSchema (nunca borrar: version mas nueva)
  / unusable (quick_check falla -> la copia es mejor).
- Instalacion/arranque (database_installation_gate.dart:ensureReady): recibo JSON con installationId+databaseId (UUID) y
  database_identity_v1 en BD para detectar BD intercambiada/duplicada; recoveryActionFor decide rebuildAutomatically /
  promptRemigration / promptUpgrade; rebuildFresh MUEVE la BD vieja a copia desplazada en vez de borrarla;
  StartupRecoveryService: exportDataCopy, repair, reset (siempre fail-closed con salida para el usuario).
- Backup/restore: createConsistentSnapshot (snapshot consistente de la BD abierta), prepareSnapshotForRestore, normalizeForwardCompatibleSnapshot
  (descarta lo desconocido si el manifiesto declara minimumReadableSchemaVersion=2), clasificacion BackupSchemaVerdict
  (current/needsUpgrade/forwardCompatible/forwardUndeclared/unreadable), recomputeAttachmentAvailability tras restaurar.

## 6. Concurrencia

- drift NativeDatabase.createInBackground: la conexion SQLite vive en UN isolate de trabajo; el isolate de UI solo envia consultas
  asincronas (hay una sonda probeExecutionIsolate que demuestra por SQL que las callbacks corren en el isolate de fondo).
  Una unica AppDatabase (gateway con leases). Dentro, las tx de drift se serializan en esa conexion; WAL permite lecturas
  concurrentes de OTRAS conexiones (p.ej. HiveMigrationMarker abre read-only mientras la app corre).
- Calculo pesado fuera del isolate de UI: digest de texto en isolate propio (_computeTextPartContentDigestInIsolate), construccion del
  indice de timeline "entera en el worker de SQLite", lecturas por ventana (nunca cargar toda la conversacion).
- Prevencion de carreras de aplicacion: cola latest-wins serializada para streaming; guard "no resucitar finalizado"; bloqueo optimista
  por state_revision y checkpoint_seq monotono; UNIQUE parcial de run activo por mensaje; contador _contextRevisions con reintento;
  _installation compartido en ensureConversation; apertura serializada con _opening/_closing en el gateway;
  sqlite_interrupt.dart permite interrumpir consultas largas.
- Tx: toda mutacion compuesta va en `_db.transaction`. FKs hijos->mensaje son DEFERRABLE INITIALLY DEFERRED para poder insertar
  parte antes que su mensaje dentro de la misma tx y para reordenados.

## 7. Estrategia de tests (test/core/database, ~55 archivos)

- Esquema/constraints: app_database_schema_test, app_database_constraints_test, app_database_migration_test (inspeccion de gate,
  rechazo de versiones 4..42 sin mutar, etc.), schema_migrations_test, generated_schema/schema_v1..v3.dart (generados con
  `drift_dev schema generate` desde los JSON congelados) y legacy_upgrade_startup_test (para CADA version publicada arranca una
  instalacion migrada desde Hive; test "every published schema has a generated schema to build from").
- Repositorio por funcion: linear_window, streaming_checkpoint (tardio no resucita, parts intercaladas, tool events, crash->FTS),
  delete, duplicate, fork, merge, clear, tombstone, snapshot, search, stats, asset_gc, order_constraint, parts_roundtrip,
  partial_update, message_updated_at, message_streaming_index, message_timeline_index, linear_message_behavior_matrix,
  generation_run_commands, forward_compat_normalize, startup_recovery/failure, database_installation_gate.
- BD real en archivo temporal (sqlite3 FFI), no mocks. Inyeccion de fallos con hooks debug (debugFailMessageIdsForTest, etc.) bajo assert().
- Benchmarks (test/perf/*_bench.dart: streaming_checkpoint, timeline_scale/growth/scroll) imprimen tiempos, sin expect, fuera del glob de CI.

## 8. Decisiones transferibles a Android vs NO transferir

TRANSFERIBLE (patron estandar, equivalente directo en Room/SQLiteOpenHelper):
1. Un archivo SQLite, WAL, foreign_keys=ON, busy_timeout, synchronous=NORMAL; verificar PRAGMAs tras abrir (Room: setJournalMode(WRITE_AHEAD_LOGGING) + callback onOpen con execSQL("PRAGMA foreign_keys=ON") y PRAGMA synchronous).
2. Revision como fila: message(id, conversation_id FK CASCADE, role, timestamp, group_id NULL, version, message_order, is_streaming, tokens..., updated_at, extras_json) con UNIQUE(conversation_id,message_order) y UNIQUE(conversation_id,group_id,version); grupo = COALESCE(group_id,id); seleccion de version en conversacion.
3. Cuerpo en tabla de partes (revision_id, ordinal, kind, payload TEXT) con UNIQUE(revision_id,ordinal); kinds desconocidos se conservan; resultado de herramienta dentro del payload del tool_call; texto/razonamiento como texto plano, adjuntos como JSON con assetId.
4. Artefactos opacos del proveedor en tabla propia (revision_id,kind,payload).
5. Assets deduplicados por content_hash + tabla de vinculos + GC diferido y revalidado fuera de la tx.
6. generation_run con maquina de estados, state_revision (optimistic lock), checkpoint_seq monotono y UNIQUE parcial por mensaje activo; al arrancar marcar activos como interrupted y limpiar is_streaming.
7. Streaming: escribir snapshots (no deltas) con coalescing latest-wins y intervalo minimo ~250 ms; upsert por (revision_id,ordinal) con WHERE de cambio; escritura final autoritativa; guard contra flush tardio; inicio de turno (usuario+asistente+run) en una sola tx.
8. Paginacion keyset por (anchor_order, group_id) + total de grupos, devolviendo slots sin cuerpos, luego hidratar con IN(...) en una sola query de partes (anti N+1). Room: @RawQuery / DAO con IN lista, transacciones @Transaction. Indice de timeline derivado con triggers (opcional; empezar sin el y anadirlo si MIN(message_order) GROUP BY resulta lento).
9. FTS5 externo sobre partes text con triggers que indexan solo mensajes finalizados; reconstruible y perezoso (Room: tabla virtual con @Fts4 solo FTS3/4, para FTS5 usar SQL crudo en callback onCreate/onOpen y no declararla como entity).
10. Migraciones aditivas por paso (version a version), columnas nuevas al final, user_version = version; extras_json por tabla como punto de extension; fixtures de esquema por version (Room: exportSchema=true + MigrationTestHelper con los JSON congelados).
11. Migracion segura: copia pre-migracion con fsync + wal_checkpoint(TRUNCATE) antes, validacion estructural despues, rollback automatico y barrido en el siguiente arranque; nunca abrir/borrar una BD con version MAS NUEVA; construir la BD nueva en archivo temporal y publicar al validar (conteos + digest de texto + integrity_check + foreign_key_check); la fuente legacy no se borra sin accion explicita.
12. Tombstones en la misma tx que el borrado (si habra sync), borrado de conversacion por CASCADE.
13. Cache en memoria como proyeccion write-through (BD primero), con contador de revision para reintentar lecturas obsoletas, sin expulsar mensajes en streaming; conteos perezosos por conversacion; ventanas de UI (40/20/360) son parametros de rendimiento, no limites de capacidad.
14. Tests con BD real en archivo temporal (Robolectric/instrumentacion), por operacion, incluyendo carreras de checkpoint tardio y recuperacion tras crash.

NO TRANSFERIR / especifico de Flutter-drift:
- drift (DSL de tablas, .g.dart, stepByStep generado, NativeDatabase.createInBackground e isolates, LazyDatabase); en Android Room ya ejecuta fuera del hilo principal (usar corutinas/executor, NUNCA queries en main).
- Hive (cajas lazy, adapters), shared_preferences, migrador Hive->SQLite y su UI: solo el PATRON (archivo temporal + validacion + publicacion) aplica.
- Validacion de columnas por orden exacto (_validateRawSchema) y los json de esquema de drift: en Room equivale a exportSchema + MigrationTestHelper.
- Gateway/lease singleton y sonda de isolate: Room ya garantiza una instancia por proceso si se usa singleton (Hilt/DI).
- Recibos de instalacion JSON, restore bundles, exportacion de copia desde pantalla de fallo previa a servicios, rutas de sandbox iOS/desktop (sandbox_path_resolver), paridad multi-plataforma (WAL en carpeta compartida, durabilidad fsync de directorio en macOS/Windows): en Android basta con filesDir + wal_checkpoint + copia.
- Tablas de configuracion tipo payload-JSON (assistant_rows, provider_rows ...) y extension_entity_rows: decision de producto, no de BD de chats.
- Complejidad de merge de backups con ids deterministas y cutover/rollback de restore: solo si se necesita importar backups.

## Tamanos/limites vistos (solo CONTEXTO, no copiar como topes de capacidad)
Ventanas: 40 slots iniciales, pagina historia 20, ventana cargada max 360. Cache: 720 mensajes / 8 MiB. Checkpoint streaming: 250 ms
min. WAL: 1000 paginas autocheckpoint, journal_size_limit 16 MiB, busy_timeout 5000 ms. Tombstones 90 dias. GC de assets 7 dias.
Hash de imagen cache 256. Ninguno limita cuantos mensajes/conversaciones/partes se guardan.
