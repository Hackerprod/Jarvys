# Informe S1 ? 2026-10-24

Implementada ?nicamente S1 en `C:\Projects\Jarvys-wt\single`, rama `feat/single-session`. **Kotlin/Java compilan; B9/B10/B11: 5 pruebas, 5 PASS, cero omitidas.** Sin commit, push, APK ni suite completa. `docs/SINGLE_SESSION.md` intacto. No se modificaron otros worktrees; los informes est?n en el directorio autorizado `single-validate`.

## Implementaci?n

- **Esquema:** nueva `jarvys-chat-v3.db`, Room v3 aditivo: conserva las 53 tablas anteriores y todas sus columnas; a?ade ocho columnas a `memory_entries`, `memory_evidence`, `memory_links`, `transcript_artifact_refs`, ?ndices y `message_fts` externo a Room. Ampl?a `memory_fts`. Registro nuevo `jarvys-workspaces-v2.db`; sin importaci?n ni copia de memoria.
- **Sesi?n:** arranque, intents, restauraci?n, selecci?n y `newChat()` vuelven a `jarvys-central`; normalizaci?n del selector de adjuntos y anclas inexistentes. Servicio normaliza las sesiones ordinarias; conserva los identificadores internos cuya retirada pertenece a etapas posteriores. Repositorio y `update("deleted")` rechazan borrar Central.
- **Reset:** fases persistidas con `AtomicFile`, creaci?n de Central, memoria inicialmente vac?a y siembra perezosa. Invalida notificaciones, PendingIntents y trabajos antiguos de memoria. Conserva ajustes. Tras el primer run correcto, avisa en el hilo y retira exclusivamente el inventario antiguo; nunca `g3`. Retirada reintentable y errores registrados sin invalidar el run completado.
- **Archivos:** `StorageGeneration` separa `filesDir` y `chatRoot`; almacenes, proyectos e identidades, m?dulos, previews, lector de conversaciones y FileProvider usan `g3`. Tambi?n runs/notas, contexto Crew, ?ndice y recibos privados de descarga. Los Downloads p?blicos se conservan.
- **Lecturas S1:** reacci?n por mensaje/?ndice; propiedad de artefactos desde `model_artifacts`; transcript seleccionado por grupos completos y orden l?gico; entradas por cursor y estado por IDs; incorporaci?n por rango SQL conservando el journal; ?ltimo ?ndice de usuario mediante `MAX`. No se adelantaron las retiradas de S2?S8.

## Validaci?n y comandos

Desde `C:\Projects\Jarvys-wt\single\app`:

```powershell
.\gradlew.bat tasks --all --console=plain | Select-String UnitTest
.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac --console=plain
.\gradlew.bat :app:testFullDebugUnitTest --tests com.jarvys.agent.SingleSessionResetTest --tests com.jarvys.agent.SingleSessionReadBoundariesTest --tests 'com.jarvys.agent.MainActivityWorkspaceRoutingTest.B10_startIntentNewChatAndRestoredPickerAlwaysUseCentral' -x :app:prepareApkFactoryTemplate --console=plain
```

- Descubrimiento: exit 0, tareas Full/Play identificadas.
- Compilaci?n final: **BUILD SUCCESSFUL in 2s**, 38 tareas up-to-date; la recompilaci?n efectiva anterior tambi?n pas?. [Salida](compile-full-debug-final.log).
- Contratos finales: **BUILD SUCCESSFUL in 40s**, 55 tareas; XML: **5 tests, 0 failures, 0 errors, 0 skipped**. [Salida](contracts-S1-final.log), [casos y recuento](contracts-final-summary.json).
- Regresi?n dirigida: 30 clases existentes seleccionadas por referencias a clases del diff, m?s las dos clases nuevas. Segunda tanda: **374 tests, 350 PASS, 24 fallos**. [Comando exacto](selected-tests-command.txt), [selecci?n](selected-existing-tests.txt), [salida](selected-tests-2.log).
- Contraste con `HEAD` limpio `47ac4557727cb6250a1d36b8a5df1669741fe66a`: **23/23 m?todos existentes fallan tambi?n antes de S1**, con las mismas causas. Se us? stash temporal dentro del mismo worktree y se verific? la restauraci?n byte a byte de los 63 archivos existentes en ese momento; stash retirado. [Comando](baseline-tests-command.txt), [salida](baseline-tests.log), [comparaci?n](baseline-comparison.json).
- Despu?s del contraste: **83/84 PASS** en contratos y pruebas afectadas por los ajustes finales de esquema/FTS/FileProvider/repositorio. El ?nico fallo fue la preparaci?n de B9, corregida y reejecutada con los cinco contratos finales en verde. El caso existente de FileProvider ya pasa. Quedan **22 fallos preexistentes documentados**; no se volvieron a ejecutar los 374 despu?s del ?ltimo ajuste exclusivo de B9. [Comando](post-baseline-tests-command.txt), [salida](contracts-S1-final-attempt.log).
- `git diff --check`: limpio. Comparaci?n JSON v2/v3: ninguna tabla o columna anterior retirada.

Se excluy? `:app:prepareApkFactoryTemplate` porque las tareas de test lo arrastran mediante assets y genera un APK. El dry-run confirm? la exclusi?n; no se ejecut? esa generaci?n.

Los 22 fallos restantes incluyen permisos de symlinks y archivos abiertos de Windows, `android.system.Os.rename` sin mock, sustituci?n de ra?z de ProjectScope y recuperaci?n HTML sin presentaci?n. Las trazas completas est?n en `baseline-results` y `selected-2-results`.

## Pruebas nuevas y adaptadas

- Nuevas: `SingleSessionResetTest` (dos B9: fases/reapertura/siembra; invalidaci?n/retirada/consumidores reales) y `SingleSessionReadBoundariesTest` (dos B11: equivalencia de grupos compactados y ausencia de lectura completa en checkpoints). B10 a?adido a `MainActivityWorkspaceRoutingTest`.
- Adaptados fixtures de artefactos, im?genes, thumbnails, Crew, proyectos y restauraci?n a `g3` y al ancla expl?cita `filesDir`.
- M0/repositorio/FTS: esquema v3, tablas globales nuevas, ?ndices externos y columnas FTS; exports publicados v1/v2 conservados. M1b distingue lectura completa de referencia y selecci?n compactada.
- Routing: expectativas de Central e independencia del estado UI entre pruebas. Eliminado solamente `emptyCentralSelectionPreservesHiddenLiveOwnerAndReturningRestoresItsRun`, porque exig?a navegar entre chats ordinarios, incompatible con S1. La prueba de borrado de Central ahora exige rechazo y verifica que sigue aceptando entregas.
- `ChatTestStore`: inicializaci?n de WorkManager para fixtures con Application b?sica. `FileProviderPathShadow`: adapta ?nicamente separadores del host en la comparaci?n de pertenencia de AndroidX; XML, creaci?n/resoluci?n de URI y lectura de bytes siguen siendo reales. Aplicado a B9 y al test existente de GeneratedImageStore; no cambia producci?n.

## Discrepancias encontradas y resoluci?n

Referencias sobre el ?rbol final salvo indicaci?n:

- `AgentForegroundService.java:585`: hab?a otra sobrecarga generadora de UUID, adem?s de la citada por el dise?o. Tambi?n se normaliz? a Central.
- `chatdb/PersonalMemoryFtsSql.kt:170?182`: abrir la base sembraba un documento t?cnico en `agent_docs`, contradiciendo B9 vac?o. El estado t?cnico se guarda ahora en `storage_meta`; los documentos personales siguen sembr?ndose s?lo en el primer uso.
- `DownloadStore.java:413,443` y `src/full/.../linux/CodingJobManager.kt`: consumidores adicionales de datos de conversaci?n. Recibos privados y contexto Crew migrados a `g3`; configuraci?n y descargas p?blicas conservadas.
- `SingleSessionReset.kt:94?95`: SQLite READONLY todav?a pod?a usar el handler Android que borra una base corrupta. Se usa handler no destructivo para preservar los datos antiguos hasta retirada.
- `ConversationReader.kt:1276`: preview reconstruido con `filesDir` expl?cito. El test B9 registra tambi?n la presentaci?n que produce el runtime; entregable y preview corresponden a filas distintas. El fallback antiguo sin presentaci?n falla tambi?n en HEAD y no se modific?.
- `FileProviderPathShadow.java:10`: AndroidX usa `/` literal y el JDK Windows devuelve `\`. Adaptaci?n exclusiva del entorno de tests, sin alterar el contrato Android ni omitir comprobaciones.

No se cambi? el dise?o ni se implementaron importadores o compatibilidad de chats legacy.

## L?mites de lo verificado

No se ejecutaron Play, dispositivo/emulador, APK, suite completa, run real con proveedor de modelo ni medici?n de rendimiento con un historial real extenso. B9 prueba el evento de ?xito y la retirada con archivos reales en Robolectric, no un reinicio de Android ni WorkManager con un worker realmente ejecut?ndose.

Si la base antigua est? corrupta, se preserva y se bloquea la fase de invalidaci?n al no poder enumerar sus sesiones; no se invent? una pol?tica para omitir la cancelaci?n por id que exige el dise?o. No se prob? ese escenario de corrupci?n ni fallos f?sicos de disco. La recuperaci?n comprobada interrumpe despu?s de cada fase persistida y reabre la base.

## Ficheros tocados

**64 archivos: 45 de producci?n/recursos, 18 de tests/helpers y 1 export de esquema.** Inventario completo: [S1-files.txt](S1-files.txt).

**Producci?n/recursos** (rutas relativas a `app/app/`):

```text
src/full/java/com/jarvys/agent/linux/CodingJobManager.kt
src/main/java/com/jarvys/agent/AgentForegroundService.java
src/main/java/com/jarvys/agent/AttachmentStore.java
src/main/java/com/jarvys/agent/ConversationMetadataAccess.java
src/main/java/com/jarvys/agent/ConversationModuleCapabilityAccess.kt
src/main/java/com/jarvys/agent/ConversationReader.kt
src/main/java/com/jarvys/agent/CrewCheckpointStore.java
src/main/java/com/jarvys/agent/CrewContextArtifacts.java
src/main/java/com/jarvys/agent/DeliveredArtifactStore.java
src/main/java/com/jarvys/agent/DownloadStore.java
src/main/java/com/jarvys/agent/GeneratedImageStore.java
src/main/java/com/jarvys/agent/HtmlThumbnailStore.java
src/main/java/com/jarvys/agent/LocalRunStore.java
src/main/java/com/jarvys/agent/MainActivity.kt
src/main/java/com/jarvys/agent/MainChatTranscriptStore.java
src/main/java/com/jarvys/agent/MemorySearchIndexRuntime.kt
src/main/java/com/jarvys/agent/MemoryStore.java
src/main/java/com/jarvys/agent/MessageReactionTool.java
src/main/java/com/jarvys/agent/PersonalMemoryConsolidationWorker.kt
src/main/java/com/jarvys/agent/PersonalMemoryExtractionWorker.kt
src/main/java/com/jarvys/agent/SingleSessionReset.kt
src/main/java/com/jarvys/agent/StorageGeneration.java
src/main/java/com/jarvys/agent/WorkspaceArtifactDelivery.java
src/main/java/com/jarvys/agent/WorkspacePreviewScreen.kt
src/main/java/com/jarvys/agent/WorkspaceStore.java
src/main/java/com/jarvys/agent/WorkspaceTools.java
src/main/java/com/jarvys/agent/apkfactory/FactoryInstallCoordinator.kt
src/main/java/com/jarvys/agent/chatdb/ChatDatabaseBridge.kt
src/main/java/com/jarvys/agent/chatdb/ConversationRepository.kt
src/main/java/com/jarvys/agent/chatdb/JarvysChatDatabase.kt
src/main/java/com/jarvys/agent/chatdb/MessageSearchIndex.kt
src/main/java/com/jarvys/agent/chatdb/ModelTranscriptRepository.kt
src/main/java/com/jarvys/agent/chatdb/PersonalAgentEntities.kt
src/main/java/com/jarvys/agent/chatdb/PersonalMemoryFtsSql.kt
src/main/java/com/jarvys/agent/coding/ProjectScope.java
src/main/java/com/jarvys/agent/coding/ProjectScopeIdentity.java
src/main/java/com/jarvys/agent/coding/ProjectScopeStore.java
src/main/java/com/jarvys/agent/connectors/ApprovalNotificationCenter.kt
src/main/java/com/jarvys/agent/proactive/ProactiveInteractionDispatcher.kt
src/main/java/com/jarvys/agent/proactive/ProactiveNotifier.kt
src/main/java/com/jarvys/agent/tasks/TaskNotifier.kt
src/main/java/com/jarvys/agent/workspaces/WorkspaceIdentity.java
src/main/java/com/jarvys/agent/workspaces/WorkspaceRegistry.kt
src/main/res/values/strings.xml
src/main/res/xml/generated_image_paths.xml
```

**Tests/helpers** (rutas relativas a `app/app/`):

```text
src/test/java/com/jarvys/agent/AgentRunRestorationComposeTest.kt
src/test/java/com/jarvys/agent/ChatTestStore.kt
src/test/java/com/jarvys/agent/CrewCheckpointRecoveryTest.java
src/test/java/com/jarvys/agent/CrewContextArtifactsRecoveryTest.java
src/test/java/com/jarvys/agent/DeliveredArtifactTest.java
src/test/java/com/jarvys/agent/FileProviderPathShadow.java
src/test/java/com/jarvys/agent/GeneratedImageStoreTest.kt
src/test/java/com/jarvys/agent/HtmlPreviewSnapshotTest.java
src/test/java/com/jarvys/agent/HtmlThumbnailStoreTest.java
src/test/java/com/jarvys/agent/MainActivityWorkspaceRoutingTest.kt
src/test/java/com/jarvys/agent/SingleSessionReadBoundariesTest.kt
src/test/java/com/jarvys/agent/SingleSessionResetTest.kt
src/test/java/com/jarvys/agent/WorkspaceArtifactDeliveryTest.java
src/test/java/com/jarvys/agent/chatdb/ConversationRepositoryTest.kt
src/test/java/com/jarvys/agent/chatdb/JarvysChatDatabaseM0Test.kt
src/test/java/com/jarvys/agent/chatdb/M1bRepositoriesTest.kt
src/test/java/com/jarvys/agent/chatdb/PersonalMemoryFts5Test.kt
src/test/java/com/jarvys/agent/coding/ProjectScopeExistingTest.java
```

**Esquema** (rutas relativas a `app/app/`):

```text
schemas/com.jarvys.agent.chatdb.JarvysChatDatabase/3.json
```

Historial de intentos y progreso: [stage-S1-progress.md](stage-S1-progress.md).
