# Informe S1 (en curso)

Fecha: 2026-10-24. Worktree: C:\Projects\Jarvys-wt\single, rama feat/single-session.
Estado inicial: git status limpio. Leído completo docs/SINGLE_SESSION.md. No se encontró informe previo.
Ruta: delegated direct, un escritor de implementación; agente principal coordina validación y revisión. Sin SDD.
Restricciones: solo S1, documento intacto, sin commits/push/APK; nunca suite completa.

## Progreso
- Especificación revisada: esquema v3 aditivo, reset por fases, almacenamiento g3 y lecturas S1; retiradas de S2..S8 excluidas.
- Implementación delegada en curso; aún no hay compilación ni pruebas acreditadas.
- Receipt review: herramienta gentle-ai no encontrada en PATH; no se activó ningún modo de revisión.

## Pendiente
Implementar y revisar S1 completo; descubrir tareas Gradle; compilar Kotlin/Java FullDebug; ejecutar B9/B10/B11 y seleccionar tests existentes desde git diff --name-only y sus referencias; registrar resultados exactos, discrepancias y límites.

## Validación preparada
- Ejecutado: `.\gradlew.bat tasks --all --console=plain | Select-String UnitTest` (desde `app`), exit 0. Tareas confirmadas: `:app:testFullDebugUnitTest`, `:app:testPlayDebugUnitTest`.
- Detectado en `app/app/build.gradle.kts:158`: merge*Assets depende de `prepareApkFactoryTemplate`, que genera APK interno. Se excluirá `:app:prepareApkFactoryTemplate` de las pruebas para cumplir NO APK. Se verifica el grafo con --dry-run.
- Aún no se han ejecutado tests ni compilación.

## Hallazgos de integración
- El Application real está en `FactoryInstallCoordinator.kt:400`; la inicialización automática de WorkManager puede preceder a Application.onCreate. Reset requiere también guardas de generación en workers.
- `PersonalMemoryConsolidationWorker.kt` es trabajo de memoria adicional al de extracción: cancelación del unique work y generación necesarias para no repoblar memoria antigua.
- `NotificationManager.cancelAll()` por sí solo no invalida PendingIntent; se requiere cancelar tokens activos y rechazar acciones antiguas en receptores. Hallazgos remitidos al escritor.
- Dry-run de `:app:testFullDebugUnitTest --tests com.jarvys.agent.MainChatTurnContextProviderTest -x :app:prepareApkFactoryTemplate --dry-run --console=plain`: BUILD SUCCESSFUL; sin tareas de APK. Log: test-task-dry-run.log.

## Implementación en progreso (sin validar todavía)
- StorageGeneration creado: filesDir explícito separado de chatRoot jarvys/g3; migrados los primeros almacenes/consumidores y FileProvider.
- Esquema v3 aditivo en implementación; conservadas columnas existentes.
- Discrepancia comprobada: `PersonalMemorySearchIndex.saveMode` escribía `agent_docs` al abrir la base, antes de initialize(). Para cumplir B9 (memoria vacía tras reset) el metadato técnico se traslada a storage_meta. No implica copiar memoria.
- Revisión incremental detectó y comunicó: preservar fin de línea original; identidad call_id en referencias de artefactos; borrado FTS durante CASCADE; ancla filesDir para fixtures y consumidores.

## Reset en revisión incremental
- Ya existe SingleSessionReset.kt con fases created/registry_created/invalidated/done y retiro tras primer COMPLETED Central, AtomicFile de estado e inventario explícito de árboles antiguos.
- En revisión: recuperación de backup AtomicFile tras corte, exclusión absoluta de g3, serialización del retiro, PendingIntent y guardas de workers. No está acreditado por tests todavía.
- Inventario amplió rutas a MemorySearchIndexRuntime, flavor Full CodingJobManager y recibos privados DownloadStore; archivos públicos Downloads permanecen fuera de retirada.

## Compilación 1
Comando exacto desde app: `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac --console=plain` (salida capturada en compile-full-debug-1.log).
Resultado: BUILD FAILED in 1m 15s, único error Kotlin SingleSessionReset.kt:144 unresolved reference exists (AtomicFile). Corrección solicitada. KSP generó schema 3; warning sobre índice source_conversation_id de MemoryJob ya existente, pendiente contraste final.

## Compilación 2 y esquema
- Comando: `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac --console=plain`
- Resultado: BUILD SUCCESSFUL in 1m 38s; 38 actionable tasks: 11 executed, 27 up-to-date. Log: compile-full-debug-2.log.
- Comparación automatizada exports Room 2.json/3.json: cero tablas retiradas, cero columnas retiradas. Nuevas tablas Room: memory_evidence, memory_links, transcript_artifact_refs. Ocho columnas memory_entries añadidas: event_at, kind, narration, source_conversation_id, source_message_id, through_seq, title, topic_key. message_fts es externo a Room.
- Pruebas aún pendientes; no se declara S1 terminado.

## Pruebas seleccionadas
- Selección de regresión en selected-existing-tests.txt: contratos existentes directos de clases afectadas, derivados de git diff --name-only y búsqueda de referencias; inventario trazable en affected-test-references.json. No es suite completa.
- Primer comando contratos en ejecución: `.\gradlew.bat :app:testFullDebugUnitTest --tests com.jarvys.agent.SingleSessionResetTest --tests com.jarvys.agent.SingleSessionReadBoundariesTest --tests com.jarvys.agent.MainActivityWorkspaceRoutingTest.B10_startIntentNewChatAndRestoredPickerAlwaysUseCentral -x :app:prepareApkFactoryTemplate --console=plain` (contracts-S1-1.log).
- Adaptación: eliminado un test emptyCentralSelectionPreservesHiddenLiveOwnerAndReturningRestoresItsRun; exigía navegación entre conversaciones ordinarias, incompatible con S1. B10 incorporado al test existente de routing con infraestructura UI.

## Contratos intento 1
No ejecutó pruebas: compileFullDebugUnitTestKotlin falló por SingleSessionResetTest.kt:147 (ProviderInfo nullable en fixture de FileProvider). Error remitido para corregir assertion de fixture; no es PASS de contratos. Log contracts-S1-1.log. Main Kotlin/Java recompilaron sin error.

## Discrepancia adicional encontrada
- `AgentForegroundService.java:586` (línea original) tenía otra sobrecarga startRealAgent que generaba UUID de sesión, además del fallback :1277 citado en diseño. Se normalizó también ese camino a Central; se conservan execution/proactive/tasks por pertenecer a etapas posteriores.
- Contratos intento 2 en ejecución; mismo comando exacto que intento 1, salida contracts-S1-2.log.

## Contratos intento 2
Compilación de tests completada; 5 tests ejecutados, 5 fallidos (BUILD FAILED in 1m 26s).
- B10 y B11: integración reset llama WorkManager antes de inicialización, IllegalStateException WorkManagerImpl:179. Se corrige inicialización, sin omitir cancelación.
- B9: fixture getDatabasePath antepone databases a ruta absoluta Windows; ruta inválida Room .lck. Se corrige fixture.
- Estos fallos se tratan como propios de integración/fixtures nuevos, no se declaran preexistentes.
XML archivados en contracts-2-results; log contracts-S1-2.log.
- Revisión SQL identificó discrepancia de longitud de IDs opacos Unicode (Kotlin UTF-16 vs SQLite length codepoints). Se preservará identidad sin reconstruir keys mediante length SQL; misma prueba B11 incluirá ID suplementario.

## Contratos intento 3
Main compiló. Tests no llegaron a ejecutarse: SingleSessionReadBoundariesTest.kt:65 helper de referencia llama función suspend sin ser suspend; corrección solicitada. BUILD FAILED in 22s, log contracts-S1-3.log.
Correcciones integradas previas: WorkManager Configuration.Provider en Application, inicialización fixture ChatTestStore si falta; rutas absolutas Windows en contexto de B9; claves opacas transcript desde filas persistidas sin length SQL.

## Contratos intento 4
5 tests ejecutados: 4 PASS, 1 fallo.
- B10 PASS (arranque/intent/newChat/selector adjunto recreado/ancla inexistente; normalización serviceSession).
- B11 PASS (2 tests): equivalencia grupos compactados, incluido ID Unicode; checkpoints/reacciones/archive/markThrough/latest index sin hidratar todo el hilo.
- B9 fases recuperables y memoria perezosa PASS.
- B9 retirada llegó a WorkspaceStore(Context) y falló por AndroidKeyStore ausente en JVM; fixture adaptado a TestSecretStoreRule existente, manteniendo ruta productiva Context.
Log contracts-S1-4.log; XML contracts-4-results.

## Regresión dirigida en ejecución
32 clases: 2 nuevas de B9/B11 + 30 existentes seleccionadas (B10 en MainActivityWorkspaceRoutingTest). Comando exacto expandido guardado en selected-tests-command.txt; salida selected-tests-1.log. No suite completa, no APK. Main/tests congelados durante ejecución.

## Regresi?n dirigida intento 1
374 tests ejecutados, 43 fallidos; BUILD FAILED in 3m 49s. Main y tests compilaron. XML completos y trazas archivados en selected-1-results. Incluye fixtures a?n anteriores a g3, expectativas incompatibles con Central indeleble/transcript por grupos, y candidatos ambientales de symlinks/archivos abiertos de Windows a?n NO verificados contra baseline. B9 retirada falla en configuraci?n de root FileProvider del contexto de prueba; en correcci?n. No se declara terminada S1.

## Revisi?n de reset y almacenamiento
Auditor?a de consumidores no encuentra nuevas omisiones g3 ni anclas por profundidad. Detectados para corregir: apertura READONLY vieja usa handler Android destructivo ante corrupci?n; se conservar? cancelaci?n por id prescrita y handler no destructivo. Retirada reanudada no debe bloquear Application.onCreate si falla I/O (registrar y reintentar). Aviso antes de borrar debe decir que se va a retirar, no declarar retirada ya completada. Baseline pendiente para 21 m?todos candidatos (lista baseline-test-candidates.txt); no se atribuyen todav?a a entorno.

## Regresi?n dirigida intento 2
374 tests, 350 PASS y 24 fallos; BUILD FAILED in 3m 26s. B10/B11 y B9 fases PASS; B9 FileProvider sigue fallando. Archivos XML/trazas selected-2-results. Quedan 23 m?todos existentes para contraste baseline y B9 para correcci?n. Cambios nuevos/trackeados registrados con SHA256 en pre-baseline-hashes.json antes de stash.

## Baseline comprobado y restaurado
HEAD sin cambios: 23 m?todos seleccionados, 23 fallos (BUILD FAILED in 40s), mismos m?todos/causas que regresi?n2. Incluye FileProvider, symlinks/handles Windows, Os.rename sin mock, ProjectScope root replacement y recuperaci?n HTML sin presentaci?n. Comando exacto baseline-tests-command.txt; XML baseline-results; baseline-comparison.json. Stash restaurado y los 63 archivos cambiados/nuevos coinciden byte a byte con pre-baseline-hashes.json; no archivos adicionales. B9 nuevo sigue pendiente y no se exime por baseline.

## Validaci?n post-baseline
84 tests: 83 PASS, 1 fallo B9 en preview recuperado (:168). FileProvider URI real y apertura/bytes ya PASS con adaptaci?n separador host s?lo fixture; retirada y preservaci?n anteriores tambi?n PASS. B9 preview necesita investigaci?n, no se elimina assertion. Log contracts-S1-final-attempt.log, XML contracts-final-attempt-results. Resto contratos y esquema/FTS/repositorio PASS.
