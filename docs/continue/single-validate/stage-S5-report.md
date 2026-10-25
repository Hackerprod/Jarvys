# Informe S5: herramientas de memoria, `history_*`, retirada de `scope` y retencion

Estado: implementada y compilando (Full y Play, main y tests). Sin commit, sin push, sin APK, `docs/SINGLE_SESSION.md` intacto. Un solo daemon de Gradle por vez.

## Comandos y resultado real
- Compilacion (brief): `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac :app:compilePlayDebugKotlin :app:compilePlayDebugJavaWithJavac :app:compileFullDebugUnitTestKotlin :app:compileFullDebugUnitTestJavaWithJavac --console=plain` -> BUILD SUCCESSFUL (stage-S5-compile-4.log y stage-S5-compile-final.log).
- Tests propios: `:app:testFullDebugUnitTest --rerun --tests "*SingleSessionMemoryRetentionTest" "*MessageHistoryFts5Test" "*PersonalMemoryFts5Test" "*StorageWorkspaceComposeTest" "*AppNavigationBackPolicyTest" "*PersonalAgentBehaviorTest" "*MainChatToolContinuityTest" "*M1bRepositoriesTest" -x :app:prepareApkFactoryTemplate` -> BUILD SUCCESSFUL: 8+1+3+4+5+20+17+16 tests, 0 fallos (stage-S5-tests-final.log).
- Tests existentes que cubren clases del diff (lotes b1, b2, b4): 12 + 21 + 4 clases. Fallos y su acreditacion en la seccion "Fallos" mas abajo.
- No se ha ejecutado la suite completa.

## Por apartado
1. `scope` y repositorio (`PersonalAgentRepository.kt`, `PersonalMemoryFtsSql.kt`, `PersonalAgentEntities.kt`, esquema `3.json` regenerado por KSP en sitio, sin migracion): columna `memory_entries.scope` soltada; `user_directives.scope` y `memory_jobs.scope` permanecen. Upsert por `topic_key` normalizado (trim, minusculas, espacios a `-`) con revision (`topic_update`); dedupe por texto exacto sin clave; enlaces `supersedes` / `related` / `compatible` / `conflicts_with` en `memory_links`; `memory_correct` inserta la fila nueva con enlace `supersedes` y mueve la evidencia; la consolidacion diaria escribe `kind='digest'`. Busqueda: bm25 ponderado (title 5, text 1, topic_key 3) sobre FTS5; FTS4 con las mismas columnas; LIKE sobre titulo, texto y clave; filtros `kind`, `from`/`to`, `tool`, `connector`, `outcome`, `source_message_id`; `query` opcional si hay filtro (SQL directo sobre `memory_entries`/`memory_evidence`, funciona con narracion vacia). Un episodio sin titulo recibe una etiqueta derivada de la evidencia (tipo, fecha, herramienta x llamadas, resultados). El recibo de `memory_save` devuelve entradas solapadas (mismo `kind`, FTS) para enlazar en la llamada siguiente.
2. Herramientas (`PersonalMemoryTools.java`, `ChatDatabaseBridge.kt`): `memory_save` (+`kind`, `topic_key`, `title`, `event_date`, `supersedes_id`, `related_ids`, `relation`), `memory_search` (extractos, filtros), `memory_get` (`expand=evidence|source`), `history_search` / `history_read`. Los extractos y paginas se reparten el presupuesto `CorePromptBudget.toolResultsPerTurnChars` (sin numeros nuevos). El modelo no puede escribir `kind` episode/digest.
3. `history_*` (`MessageHistoryRepository.kt`, `SearchTerms.kt`): `message_fts` (FTS5) si existe, LIKE sobre `message_parts` si no (el SQLite del framework de Robolectric no trae FTS5). Cursor opaco ligado a (conversacion, consulta, filtros) con la `seq` maxima del primer pagina; otra consulta con ese cursor se rechaza (`history_cursor_invalid`). Mensajes regenerados/borrados (`hidden_kind`) no aparecen ni se leen. `history_read` pagina por `offset` sin partir pares sustitutos.
4. Retencion (`MainChatTranscriptStore.java`, `ModelTranscriptRepository.kt`, `LocalRunStore.java`, `MemoryTimelineRepository.kt`, `ConversationCompactor.java`, `ProtocolEntities.kt`, `ArtifactRetentionRepository.kt`, `StoragePressure.java`, `CrewContextArtifacts.java`): `retain`/`archive` devuelven `Retained(texto, artifactIds)`. El productor real escribe `transcript_artifact_refs` en la misma transaccion que la fila: `tool_result`, `tool_call_args`, `batch_text` y `compaction` (la compactacion recoge los ids de su fuente archivada). `tool_results.file_artifact_id` pasa a ser el primero. Cuota de 16 MiB (Java y Room) retirada; el limite de 256 Ki por artefacto queda como truncado explicito (`bounded`). `model_artifacts.state` / `state_reason`: `retained`, `bounded`, `expelled`, `unavailable`. Politica: antes de escribir se pregunta al dispositivo (`StorageManager.getAllocatableBytes`, o `StatFs` por debajo de Android 8) o se detecta ENOSPC; con presion se expulsan, del mas antiguo, solo los artefactos fuera del cierre protegido (la propia sentencia UPDATE reevalua el cierre); si no queda nada expulsable el artefacto nuevo pasa a `unavailable` (motivo `storage`), se levanta el aviso (`storage_meta.storage_low_warning`) y el run sigue con la vista previa inline; un write posterior sin presion limpia el aviso. Cierre protegido = para cada `memory_evidence` de una entrada activa con `ref_kind` tool_call/tool_result: artefactos de resultado y argumentos de esa llamada, `batch_text` del lote y artefactos `compaction` de toda compactacion con `first_kept` mayor que el `message_index` del lote. El texto del modelo lleva `state=` (retenido, truncado, no disponible) y `read_conversation_artifact` devuelve `state=` o el motivo (expulsado/no disponible).
5. Ajustes > Almacenamiento (`ui/settings/StorageWorkspace.kt`, `SettingsWorkspace.kt`, `AppNavigationBack.kt`, `MainActivity.kt`, strings en `values` y `values-es`): fila en la seccion Dispositivo, pantalla con el aviso de almacenamiento bajo, recuento de los cuatro estados, protegidos y bytes.
6. Pantalla de memoria (`PersonalMemoryScreen.kt`): los episodios se ven con fecha, titulo o etiqueta de evidencia y estado de narracion.
7. Reglas de recuperacion (`AgentDocDefaults.CORE_RULES_V3`, editado en sitio): MEMORY (topic_key, supersedes/related) y RECALL (buscar antes de responder sobre el pasado; admitir que no hay registro).

## Grep de aceptacion de `memory_entries.scope`
Patron: `(memory_entries|memory_fts)[^"\n]*\bscope\b|\bscope\b[^"\n]*(memory_entries|memory_fts)|\bm\.scope\b|getScope\(\)` sobre `app/app/src/main` -> sin resultados. `scope` restante en `PersonalAgentRepository.kt`: solo `user_directives` (lineas 213, 256, 276, 685, 709, 734, 740) y `memory_jobs` (826, 831). En `3.json`, `scope` solo en `tombstones`, `user_directives` y `memory_jobs`.

## Tests
Anadidos:
- `SingleSessionMemoryRetentionTest` (8): B1 (upsert por clave tras compactar, revision, prompt y busqueda; solapados + supersedes + correct), B2 (history_search/read tras compactar, paginas de lectura, cursor de 3 paginas, cursor ajeno rechazado, regenerado oculto), B5 (episodios sin narracion por tool/connector/fechas sin query, etiqueta derivada, ciclo none->pending->failed->done, extractos vs get completo), B14 (productor real hasta Room: tools con argumentos, resultado y texto de lote largos y una compactacion; presion simulada que expulsa solo lo no referenciado y borra sus ficheros; sin nada expulsable -> `unavailable` + aviso, cierre protegido intacto, episodio/evidencia/mensajes/resultados inline intactos; expand=source con estado; recuperacion limpia el aviso; olvidar el episodio libera el cierre) y artefacto `bounded`.
- `MessageHistoryFts5Test` (1, FTS5 real con SQLite incluido): ranking, paginas, oculto, actualizacion.
- `PersonalMemoryFts5Test` (+1): titulo/clave por encima del cuerpo, filtro de `kind`, actualizacion de clave.
- `StorageWorkspaceComposeTest` (4): aviso y cuatro estados, sin aviso con espacio, fila en Ajustes -> STORAGE, aviso en espanol.
Adaptados: `PersonalMemoryFts5Test` (sin columna `scope`, columna de score), `AppNavigationBackPolicyTest` (ruta `settings/storage`), `MainChatToolContinuityTest:402` (`archive(...).text`), `M1bRepositoriesTest.modelProtocolUsesFirstResults...` (la asercion de cuota de 16 MiB ya no existe por diseno; ahora asegura que el artefacto de 16 MiB es `retained`).
Borrados: ninguno.
Controles negativos (mutaciones temporales, revertidas): (1) `nextExpellable` sin el cierre protegido -> B14 falla; (2) el productor no escribe las refs de resultado -> B14 falla.

## Fallos de tests existentes y comparacion con HEAD
Export de HEAD 5ac859b7 fuera del repo: `C:\Projects\Jarvys-wt\single-validate\stage-S5-head-export` (git archive). Mismas clases ejecutadas alli (stage-S5-head-tests-1/2/3.log):
- `MemoryScopeRuntimeTest` (6), `MemorySearchToolTest` (5), `CrewContextArtifactsRecoveryTest.tamperingAndSymlink...` (1): mismos 12 fallos en HEAD (rename atomico/symlinks de Windows). Preexistentes.
- `MainAgentInteractionTest` (21) y `DeliveredArtifactTest.symlinkFilesAndParents...` (1): mismos fallos en HEAD. Preexistentes.
- `WorkspaceResultLedgerTest.deletedCentralIsNeverRecreated` (1): mismo fallo en HEAD (invariante S1 "Central cannot be deleted"). Preexistente.
- `M1bRepositoriesTest` (1): NO era preexistente (pasaba en HEAD); causado por la retirada de la cuota; test adaptado, ahora verde.
- No tocados por el diff y no ejecutados: resto de la suite.

## Tabla Engram (F de `engram-features.md`)
| F | Estado |
|---|---|
| F01 guardar | Cubierta S5 (title, kind, topic_key, event_date, candidatos). Sin idempotencia por operation_id, sin proyecto/sesion: descartadas |
| F02 upsert por topic_key | Cubierta S5 (normalizacion propia sin tope de 120) |
| F03 dedupe por hash+ventana | Parcial: dedupe por texto exacto sin ventana; contador de revision si, `duplicate_count` descartado |
| F04 tipos | Cubierta S5 (`kind` fact/preference/decision/note; episode/digest los escribe el runtime, S6) |
| F05 `<private>` | Descartada (privacidad retirada, P4) |
| F06 tope 50000 bytes | Descartada (sin topes inventados; el unico limite es el de un artefacto) |
| F07 mem_update parcial | Descartada: `memory_correct` + upsert por clave cubren el caso |
| F08 soft/hard delete | Cubierta soft (`memory_forget`); hard delete descartado |
| F09 pin/unpin | Descartada (doc 7.7) |
| F10 suggest_topic_key | Descartada (el modelo propone la clave, doc 7.7) |
| F11 busqueda | Cubierta S5: bm25 ponderado, filtros, extractos, respaldo LIKE, anotacion de enlaces. No: boosts de pin/recencia, `match_mode`, prioridad de clave exacta con `/` |
| F12 get | Cubierta S5 (texto completo, revision, enlaces, evidencia). `include_history` por cursor: no |
| F13 mem_context | Queda para S7 (`<recent_episodes>`) |
| F14 timeline | Descartada (sesion unica; history_* cubre) |
| F15 divulgacion progresiva | Cubierta S5 (search -> get -> expand evidence/source; history_search -> history_read) |
| F16 search_prompts | Cubierta S5 via `history_search` (`role=user`) |
| F17 sesiones start/end | Descartada (doc 7.7) |
| F18 session_summary | Descartada; el equivalente es compactacion + digest (S6) |
| F19 proyectos | Descartada (proyecto unico) |
| F20 save_prompt / F23 captura de prompt | Descartadas (el ledger de mensajes ya lo guarda; `source_message_id` en la entrada) |
| F21 stats | Parcial: resumen de artefactos en Ajustes > Almacenamiento; stats de memoria descartadas |
| F22 nudge | No cubierta; las reglas MEMORY/RECALL de `agent_docs` cubren el comportamiento. Candidata a S7 |
| F24 captura pasiva | Ya existia equivalente (extraccion offline); cursor incremental en S6 |
| F25 visibilidad / versiones | Cubierta S5 (`deleted_at`, `memory_revisions`) |
| F26 needs_review / F27 decay / F28 review | Descartadas |
| F29 consolidate | Existente (consolidacion diaria, ahora `kind=digest`); plan-confirmar descartado |
| F30 vocabulario de relaciones | Parcial: related, compatible, conflicts_with, supersedes; `part_of` reservado para S6; scoped/not_conflict descartadas |
| F31 candidatos al guardar | Cubierta S5 como lista en el recibo (sin filas `pending` ni stopwords) |
| F32 judge / F33 compare | Cubierta sin herramienta de juicio: `supersedes_id`/`related_ids`/`relation`; confianza y razon no expuestas. compare descartada |
| F34 politica de preguntar al usuario | No incluida en `agent_docs` |
| F35 anotacion de relaciones | Cubierta S5 (`links` con `superseded_by`, etc.) |
| F36 doctor | Descartada |
| F37 export/import | No asignada a ninguna etapa S5-S8; pendiente |
| F38 git sync, F39 cloud, F40 HTTP, F41 TUI | NO-PORTABLE, descartadas |
| F42 Obsidian | Descartada |
| F43 hooks de IDE | Descartada; inyectar contexto al inicio / tras compactar -> S7 |
| F44 perfiles de herramientas | Descartada (memoria solo en `depth==0`, ya existia) |

## Discrepancias documento vs codigo y como se resolvieron
- `memory_evidence` no tenia columna para filtrar por conector sin parsear JSON (`facts_json`); anadida `connector` (indexada). Cambio aditivo en v3 en sitio.
- Documento 7.10 dice "toda fila de `memory_evidence`" protege; decision: solo las de entradas activas (una entrada olvidada o sustituida sin mover su evidencia no protege; `memory_correct` mueve la evidencia a la entrada nueva).
- `AgentDocDefaults.CORE_RULES_V3` editado en sitio (rama no publicada) en vez de crear V4; instalaciones de desarrollo con V3 previo conservan el texto viejo.
- 6.4 ("enlace Ver en Memoria desde el mensaje del hilo") no esta en la lista de S5 del 9 ni en el brief; no se implemento. Es decision del dueno.
- El indice FTS4 de una base creada antes de S5 conserva su forma antigua (solo `text`) hasta reinstalarse; no hay bases publicadas.
- Los artefactos de detalle de actividad/presentacion de herramientas (`LocalRunStore.appendToolActivity`/`appendConversationToolPresentation`) no tienen dueno en `transcript_artifact_refs`: se expulsan primero bajo presion (salvo que compartan id con un resultado protegido, ids por contenido).
- Recuperacion de espacio solo en el momento de escribir un artefacto (o al fallar la escritura); no hay barrido en segundo plano al recibir la senal de almacenamiento bajo del sistema.
- `MemoryTool` registra `history_*` junto a `memory_*` (solo `depth==0`, chat ordinario y memoria global activada).

## Lo que NO verifique
- Comportamiento en dispositivo: `StorageManager.getAllocatableBytes` (semantica exacta del umbral bajo), ENOSPC real, FTS5 de `message_fts` en el SQLite del framework de Android (aqui solo con SQLite incluido en JVM y con LIKE en Robolectric), rendimiento con miles de mensajes.
- Variante Play en tests (solo compilada), UI real de `MainActivity` (navegacion a Almacenamiento comprobada por politica de rutas y por Compose del contenido, no por el NavHost completo), `Th1VisualCaptureTest` pasa pero no se reviso visualmente.
- Resto de la suite (miles de fallos de entorno en Windows segun el brief).
- Interaccion con S6/S7: el productor de episodios y la reconciliacion no existen aun; los tests insertan filas de episodio/evidencia a mano (permitido: solo las refs de artefactos deben venir del productor real, y lo hacen).
- La eliminacion fisica del fichero tras `expel` se comprueba en el directorio temporal de Robolectric; si el proceso muere entre marcar `expelled` y borrar el fichero queda un fichero huerfano (sin barrido).

## Ficheros tocados
Main: `AppNavigationBack.kt`, `ConversationCompactor.java`, `CoreAgentRuntime.java`, `CrewContextArtifacts.java`, `LocalRunStore.java`, `MainActivity.kt`, `MainChatTranscriptStore.java`, `PersonalMemoryTools.java`, `StoragePressure.java` (nuevo), `chatdb/{ArtifactRetentionRepository,ChatDatabaseBridge,ConversationModelTurnWriter,MemoryTimelineRepository,MessageHistoryRepository(nuevo),ModelTranscriptRepository,PersonalAgentEntities,PersonalAgentRepository,PersonalMemoryFtsSql,ProtocolEntities,SearchTerms(nuevo)}.kt`, `memory/PersonalMemoryScreen.kt`, `ui/settings/{SettingsWorkspace,StorageWorkspace(nuevo)}.kt`, `res/values{,-es}/strings.xml`, `schemas/.../3.json`.
Tests: ver seccion Tests.
