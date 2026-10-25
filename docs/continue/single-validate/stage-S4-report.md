# Informe etapa S4 — Workspaces invisibles + Crew/bots (opcion A)

Worktree `C:\Projects\Jarvys-wt\single` (rama feat/single-session, HEAD 2ed9ea6d). Sin commit, sin push, sin APK, `docs/SINGLE_SESSION.md` intacto. `git add -N` solo marca ficheros nuevos como "intent-to-add" (el `git mv` del Paso 0 y los `git rm` dejan esos cambios en el indice).

## 1. Resultado por apartado

### Paso 0 — visor de snapshots extraido (antes de borrar nada)
- `WorkspacePreviewScreen.kt` -> `ArtifactSnapshotPreviewScreen.kt` (git mv). `ArtifactSnapshotPreviewScreen(token, sessionId)` solo tiene la rama de artefacto entregado (`resolvePreview` + `findChatFile(session,"delivered",...)`); reutiliza `createPreviewWebView` y `WorkspacePreviewContent` sin cambios. Retirados `readOnlyPreviewWorkspace` y la rama de proyecto de workspace del composable.
- `ARTIFACT_PREVIEW` repuntado a `ArtifactSnapshotPreviewScreen`; despues se borraron `WORKSPACE_PREVIEW` y `WORKSPACE_SESSION_PREVIEW`. `MainActivity` usa una unica funcion `previewRoute(session, token)`: solo snapshots y artefactos de modulo tienen visor; cualquier otro token no abre nada.
- Verificado con `ArtifactHtmlPreviewUiTest` (10 tests en verde tras anadir el await de almacenamiento, ver 4).

### Borrado de pantallas/rutas de workspace
- Borrados (main): `WorkspaceListScreen`, `WorkspaceDetailScreen`, `WorkspaceRoutes` (`WorkspaceFilesRoute`), `WorkspaceUiState`, `WorkspaceTimelineProjection`, `WorkspaceAssignmentSelector`, `WorkspaceCoordinationIndicator`, `WorkspaceProjection`, `WorkspaceCatalogStatusSignature` (+ `ConversationListRow`/`ConversationRowMenuAction`, que vivian en WorkspaceListScreen).
- `AppNavigationBackPolicy`: fuera `WORKSPACES`, `COORDINATED_WORKSPACE`, `COORDINATED_FILES`, `WORKSPACE_PREVIEW`, `WORKSPACE_SESSION_PREVIEW`, `CREW_EMPTY`; `rootRoutes = {chat}`.
- `MainActivity`: sin `workspaceChatState`, catalogo, dialogo de correccion, `applyWorkspaceControl`, `workspaceHasActiveWorkForUi`/`workspacePauseEnabledForUi`; `crewSessionId` sale del argumento de ruta.
- `ChatComposer` pierde `inputEnabled/disabledHint/recipientContent/showModelSelector/showContextActions/showMicrophoneButton/separateStopAction/stopActionEnabled/stopActionLabel`; `JarvysRouteTopBar`/`JarvysShellFrame` pierden las acciones de menu de workspace.
- `WorkspaceChanges` (en WorkspaceCoordinator.kt): sin flujo de catalogo (`catalogRevision`, `observeCatalogSignature`, firma); `detailChanged` unificado en `changed`; `snapshotSaved(session, mission)`.
- Strings huerfanas: 52 nombres eliminados de `values` y `values-es` (workspace_*, drawer_chat_actions) calculados como "usados en HEAD y no usados ahora". Anadidos (en/es): `crew_mission_unavailable`, `mission_card_result_ready`.

**Grep de rutas `workspace*` en el grafo de navegacion**
- `AppNavigationBackPolicy.registeredRoutePatterns` ahora: chat, settings*, providers*, mcp*, skills, modules, connectors*, memory*, tasks*, bots, `crew/{missionId}?session={session}`, `crew/{missionId}/bot/{botId}?session={session}`, artifact-preview, module-artifact-viewer. Ninguna `workspace*`.
- Test `AppNavigationBackPolicyTest` compara el grafo REAL de `MainActivity` con esa lista y afirma `actualGraphRoutes.none { it.startsWith("workspace") }` (en verde).
- Grep en `src/main` y `src/test` de `workspace-preview|workspace-session-preview|"workspace/|COORDINATED_|WORKSPACE_PREVIEW|Routes.WORKSPACES`: sin resultados (solo aparecen nombres de herramienta `workspaces`, carpetas `workspaces` en disco y los testTag `workspace-preview-*` del visor).

### Tarjeta de mision (`mission_ref`)
- `workspaces/MissionRef.kt`: `MissionRef(workspaceId, executionSessionId, missionId, assignmentId, resultId?, resultSha256?)`, parte de mensaje `mission_ref` (JSON); `fromJson` rechaza referencias incoherentes (sesion != sesion del workspace, ids en blanco). `MissionRefResolver.resolve` deriva el estado de WorkspaceRegistry (workspace + asignacion) + `crew_missions` (snapshot; si lo escribio otro proceso se muestra `interrupted()`).
- Escritura (`WorkspaceMissionCards`): mensaje `assistant` en Central con texto para el modelo + parte `mission_ref`, `stableId` idempotente. Se escribe (a) en `WorkspaceCoordinator.delegate` tras `bindBot` (tarjeta de delegacion) y (b) en `WorkspaceResultDelivery.notice` solo cuando el notice se inserta por primera vez (tarjeta de resultado con `resultId`/`sha256`). Tras escribir se refresca la vista visible de Central (`refreshVisibleCentral`). El contenido bruto del worker no se copia (comprobado en test).
- Proyeccion: `ConversationReader` convierte esos mensajes en eventos `mission_ref` (`AgentRunUiEvent.missionRef`, `canRegenerate=false`); `AgentRunUiState` los trata como tipo duradero (3 conjuntos `durableKinds` + identidades). Los mensajes con referencia NO entran en el contexto del modelo ni en la vista "activa" de mensajes (`loadConversationContext`, `readActiveConversationMessages`); `readConversationMessages` (flujo crudo) los incluye marcados con `"type":"mission_ref"`.
- UI: `MissionRefCard` pinta `CrewMissionCard` con el snapshot derivado (se recalcula con `WorkspaceChanges.revision`); si no resuelve muestra "no disponible" y sigue siendo pulsable; el resultado anade el rotulo "resultado entregado". `ConversationTimeline` y `ChatCanvasContract` (canvas experimental) abren la mision con `onOpenMission(MissionRef)`.

### Navegacion de mision
- `CrewNavigation.kt`: `CrewNavigationRoutes.mission(id, session)` y `.bot(missionId, botId, session)`; ambas rutas llevan `session`. Nueva interfaz `CrewSessionAccess` (resolve/board/manager/missions/stopAll/resumeBot) y `CrewSessionGate`: sesion no registrada, ausente o mision inexistente => `CrewMissionUnavailable` ("no disponible"), nunca Central. El argumento se reenvia de mision a bot.
- `MainActivity`: `routeCrewSession` (del argumento) decide `AgentRunUiState.showSession`; misiones = grabadas (`readCrewMissionSnapshots`) + vivas (las vivas ganan; las de otro proceso se muestran interrumpidas); `resolve()` valida `isExecutionSession` + `WorkspaceRegistry.getWorkspace` y prepara el Crew de la sesion.
- `CrewMissionNotifier`: `EXTRA_OPEN_MISSION` + `EXTRA_OPEN_MISSION_SESSION` (dos extras). `MainActivity` los procesa en onCreate (solo si no hay savedInstanceState: una actividad recreada restaura el objetivo no consumido y NO reprocesa el intent de lanzamiento) y en onNewIntent; estado guardado en `onSaveInstanceState`.
- Detener/reanudar bot: `CrewBotDetailScreen` -> `manager.stop`, `access.resumeBot` (-> `CoreAgentRuntime.resumeCrewBot` + keepalive). "Detener todo" de una mision ahora solo para sus bots (antes llamaba tambien a `stopCurrentWork` de Central).
- `BotsCatalogScreen` sin `conversationId` (usa `profileCapabilities(context, null)`). CrewMode: el selector sale de `CrewMissionScreen` y vive en el catalogo de Bots (Ajustes > Bots); la fila "Crew" del composer abre Bots.

### `WorkspaceTools.Preview` (B13)
- `path` obligatorio (schema `required=["path"]`); sin rama sin-path ni "live preview"; la rama con `path` sigue entregando el artefacto de conversacion. `LocalRunStore.verifiedPreview` y `ConversationReader.verifiedTimelinePreview` ya solo aceptan snapshots (un id de workspace grabado deja de ser "preview" -> mensaje "no longer available").

## 2. Comandos y resultado REAL
Entorno: `JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'`, `ANDROID_HOME=$env:LOCALAPPDATA\Android\Sdk`, `ANDROID_SDK_ROOT=$env:ANDROID_HOME`, cwd `C:\Projects\Jarvys-wt\single\app`. Scripts en `single-validate\s4_compile_all.ps1`, `s4_tests.ps1`, `s4_gradle.ps1`.

- Compilacion completa (log `stage-S4-compile-final.log`):
  `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac :app:compilePlayDebugKotlin :app:compilePlayDebugJavaWithJavac :app:compileFullDebugUnitTestKotlin :app:compileFullDebugUnitTestJavaWithJavac --console=plain` -> **BUILD SUCCESSFUL**.
- Tests (siempre `.\gradlew.bat :app:testFullDebugUnitTest --rerun --tests "*<clase>" ... -x :app:prepareApkFactoryTemplate --console=plain`; nunca suite completa; comandos exactos al inicio de cada `stage-S4-*.log`):
  - `stage-S4-final-1.log`: 25 clases, 292 tests, 6 fallos: 3 `HtmlPreviewSnapshotTest` + 1 `WorkspaceArtifactDeliveryTest` (identicos en HEAD, ver 5) y 2 mios (ToolTimelinePersistenceTest/ToolTimelineRecoveryTest) que corregi y repeti -> `stage-S4-final-1b.log` **BUILD SUCCESSFUL**.
  - `stage-S4-final-2.log`: 42 clases (UI/composer/shell/bots/canvas/lector/runtime/tareas...), 367 tests, 26 fallos; contra HEAD identicos (ver 5).
  - `stage-S4-final-3.log`: `WorkspaceCoordinatorIntegrationTest` (incluye el test nuevo de tarjeta por delegacion) **BUILD SUCCESSFUL**.
  - `stage-S4-tests-html.log`: `ArtifactHtmlPreviewUiTest` 10/10 en verde (visor extraido).
  - Nuevos en verde: `MissionRefTest` (6), `MissionRefCardComposeTest` (3), 3 B12 en `MainActivityWorkspaceRoutingTest`, `CrewC1ComposeTest` (nav con `session`), `AppNavigationBackPolicyTest`, `HtmlPreviewSnapshotTest.explicitProjectPreviewPersistsOwnedSnapshotAndNeverReturnsAWorkspaceReference` (B13).

## 3. Tests anadidos / borrados / adaptados
**Anadidos**
- `workspaces/MissionRefTest` (B12): una tarjeta por delegacion con `executionSessionId`/mission/assignment; la tarjeta no entra en contexto/vista activa; tarjeta de resultado con id+sha una sola vez y sin texto del worker; resolucion desde registro+snapshots (nulo si falta workspace/asignacion/mision; snapshot de otro proceso -> INTERRUPTED); referencia incoherente rechazada.
- `ui/chat/MissionRefCardComposeTest` (B12): tarjeta resuelta abre con la sesion de la referencia; no resuelta dice "no disponible" y es pulsable; rotulo de resultado.
- `MainActivityWorkspaceRoutingTest` (B12, actividad real): notificacion -> mision -> bot -> recreacion conserva la sesion; el intent de lanzamiento abre la mision una vez y la recreacion no lo reprocesa; referencias no resolubles (workspace sin registrar, sesion vacia, mision inexistente, id de conversacion) muestran "no disponible" y no caen a Central.
- `WorkspaceCoordinatorIntegrationTest.delegationAddsOneMissionCardThatPointsAtTheWorkspaceExecutionSession`; `CrewC2Test` (extras del PendingIntent de la notificacion: `EXTRA_OPEN_MISSION` + sesion, sin `EXTRA_OPEN_CHAT_SESSION`).
**Borrados (sin sujeto)**: WorkspaceCatalogPageTest, WorkspaceChatProjectionTest, WorkspaceDetailComposeTest, WorkspaceFilesPagingComposeTest, WorkspaceInternalCommunicationUiTest, WorkspaceListScreenComposeTest, WorkspacePagingComposeTest, WorkspacePagingPolicyTest, WorkspaceUiStateTest, WorkspaceCatalogStatusSignatureTest, WorkspaceCoordinationPluralResourcesTest, ChatComposerWorkspaceControlTest, MainActivityWorkspacePreviewGestureTest (gestos del preview de proyecto), WorkspacePreviewRecompositionTest (preview de proyecto). En `MainActivityWorkspaceRoutingTest` se quitaron 4 tests del chat de workspace (pausa/actividad/pista de correccion/controles) y en `CrewMissionPersistenceTest` el de la proyeccion de progreso del chat de workspace.
**Adaptados**: AppNavigationBackPolicyTest (rutas, solo raiz `chat`, sin `workspace*`), CrewC1ComposeTest (rutas con `session`, "no disponible"), CrewTaskPresentationTest (el selector CrewMode ya no esta en la mision), ArtifactHtmlPreviewUiTest (nuevo visor + espera de almacenamiento), ToolTimelineRecoveryTest (sin preview de proyecto + espera de almacenamiento), ToolTimelinePersistenceTest (dos tests obsoletos reescritos: un id de workspace ya no es preview), HtmlPreviewSnapshotTest (B13), MainChatToolContinuityTest (sin llamada `preview_workspace` sin path), AgentMessagingPipelineTest/ConversationVisibilityTest (sin la proyeccion de workspace), CentralAgentInputCatalogTest y WorkspaceCoordinationDeliveryTest/WorkspaceChangesTest (sin `catalogRevision`), WorkspaceResultDeliveryTest y WorkspaceCoordinatorIntegrationTest (los mensajes "mission_ref" no cuentan como respuestas/entradas), AppLanguagePolicyTest (ruta del fichero movido).

## 4. Hallazgos de la implementacion
- **Bug latente corregido**: `onNavControllerReady` es un `SideEffect` que corre antes de que el NavHost fije el grafo; navegar ahi lanza `IllegalArgumentException: Navigation graph has not been set` (lo vio el test de arranque en frio con mision). El consumo de `pendingTaskId` y `pendingMission` se movio a un hook `onNavigationReady` que se dispara cuando `currentBackStackEntry != null` (esto tambien afecta al arranque en frio por notificacion de tarea).
- Varios tests de preview/almacenamiento fallan en HEAD por la barrera de S1 (`Conversation storage is still starting`) cuando se ejecutan en el hilo principal sin esperar a `SingleSessionReset`. En los dos que tocaba mi diff (ArtifactHtmlPreviewUiTest, ToolTimelineRecoveryTest) anadi un `@Before` que espera la barrera fuera del hilo principal.

## 5. Fallos y comparacion contra HEAD (copia exportada en `single-validate\stage-S4-head-export`, `git archive --format=zip HEAD`)
Ninguno de estos se declara "preexistente" sin haber corrido la misma clase en esa copia:
- `HtmlPreviewSnapshotTest`: 3 fallos identicos en HEAD (2 por `createSymbolicLink` sin privilegio en Windows, 1 `missingToolPresentationRecoversExplicitSnapshot...` esperando previewId y obteniendo null en la misma linea logica). Log `stage-S4-head-html.log`.
- `WorkspaceArtifactDeliveryTest.unsafePathsSymlinks...` (symlink): identico en HEAD (`stage-S4-head-B.log`).
- `CentralWorkspaceCoordinationToolTest.schemaExposesOnlyExactCoordinationFieldsAndPreservesExistingActions`: identico en HEAD (`stage-S4-head-b2.log`).
- `ArtifactHtmlPreviewUiTest` (10), `PreviewMobileViewportIntegrationTest` (12), `PreviewResponsePolicyTest` (13): en HEAD fallan los mismos 10/12/13 con el mismo mensaje (barrera de almacenamiento). Mi `ArtifactHtmlPreviewUiTest` queda en verde con el `@Before`; las otras dos clases NO las modifique y siguen fallando igual que en HEAD.
- Lote C y final-2 (AppLanguagePolicyTest paridad de claves, CrewC0Test x2, DeliveredFileUiTest x5, MemorySearchToolTest x5, WorkspaceStoreTest x1, BotIdentityUiTest x7, CrewMissionTitleRuntimeTest x2, ToolTimelinePersistenceTest/MainChatToolContinuityTest ya corregidos): conjunto de fallos HEAD (24-26) == mio salvo dos diferencias dependientes del orden de ejecucion (`CrewMissionTitleRuntimeTest.effectiveCaptain...`, `DeliveredFileUiTest.apkDownload...`); ejecutadas solas (`stage-S4-flaky-mine.log` vs `stage-S4-flaky-head.log`) fallan exactamente los mismos 7 tests en ambos. Ningun fallo nuevo mio quedo sin corregir.
- Regresion propia detectada y corregida (no se llamo preexistente): `WorkspaceCoordinatorIntegrationTest` (2 fallos al aparecer la tarjeta como mensaje assistant; en HEAD pasa) y `MainChatToolContinuityTest` (usaba `preview_workspace` sin path).

## 6. Discrepancias documento-vs-codigo y como se resolvieron
1. `WorkspaceControlOwners.kt` figura para borrar (§5.1/6.3) pero lo usan `WorkspaceCoordinator`, `WorkspaceResultDelivery` y `WorkspaceCoordinationDelivery` (`registerLocalReceipt`, `cancelPending`): se conserva. `WorkspaceCatalogStatusSignature.kt` tambien figura para borrar y lo referenciaba `CoreAgentRuntime.persistCrewSnapshot` (fuera del grep del doc): se borro junto con el flujo `catalogRevision`.
2. §6.3 pide conservar `WorkspaceProjection.projectAssignment` como base de la tarjeta: la tarjeta se deriva directamente de registro + snapshot (`MissionRefResolver`) y `projectAssignment` ya no tenia consumidores; se borro todo `WorkspaceProjection`.
3. El doc habla de proyectar `workspace_result_notice` (`ConversationReader.kt:~469`): ese item sigue siendo solo auditoria (se mantiene el `-> Unit`); la tarjeta de resultado es un mensaje assistant con parte `mission_ref` escrito al insertarse el notice. Es mi interpretacion de "mensaje con referencia en Central tras el wake"; si se prefiere que lo escriba el propio modelo, habria que cambiar el escritor, no el resto.
4. §6.2 mantiene `CREW_EMPTY`: sin ningun punto de entrada (Central ya no tiene misiones) se elimino; la fila "Crew" del composer abre Bots.
5. `EXTRA_OPEN_MISSION` se implemento como dos extras (mision + sesion).
6. Aceptacion "detener/reanudar bot": solo cableado y compilado (ver 7).

## 7. Que NO verifique
- Nada en dispositivo/emulador real ni con APK (no hay APK): notificacion real en segundo plano, apertura desde el hilo con datos reales, `delegate` real de un bot con modelo real, `WebView` real (Robolectric no ejecuta Chromium).
- Detener/reanudar bot de extremo a extremo con un `CrewManager` vivo: solo el cableado (`manager.stop`, `resumeCrewBot`) compila; no hay test nuevo de esos botones.
- La tarjeta viva (cambios de estado mientras corre el bot) solo en el test de composicion con resolver inyectado; no con bots reales.
- El modo canvas experimental (`ChatCanvasContract`): se anadio `missionRef` a `canOpen`/accion permitida, sin test de render JS.
- `PreviewMobileViewportIntegrationTest` y `PreviewResponsePolicyTest` (cubren el `WebView` movido) siguen fallando en HEAD y en mi rama por la barrera de S1; el codigo movido no cambio salvo el composable.
- Variante Play: solo compile de main (Kotlin+Java); no ejecute tests Play. Tests: solo las clases de las listas de 2; nunca suite completa.
- Rendimiento de la tarjeta (consulta a registro + `readCrewMissionSnapshots` por tarjeta visible y por cambio de `WorkspaceChanges`): no medido.

## 8. Restos candidatos a S8 (no tocados a proposito)
- Consultas de solo lectura `countPendingAgentInputsByWorkspace` / `countStartedQuestionBlockerRepliesByWorkspace` (+Sync y sus tests de repositorio) sin consumidor de produccion tras borrar el sidebar.
- `WorkspaceCoordinator.enqueueNativeControl/submitNativeControl` (controles nativos de UI) sin llamador de UI; se conservan por ser infraestructura del §5.1 y estar cubiertos por tests existentes.
- `WorkspaceStore.hasIndexHtml/resolvePreviewPath` y la rama `workspace` de `WorkspacePreviewContent` (solo los usan tests existentes de respuesta del preview); `AgentRunUiEvent.previewIsCurrent` + string `chat_open_current_preview`.
- Parametros `crewMode`/`onCrewModeChange` de `ChatComposer` (sin uso interno, se mantienen por compatibilidad con ~8 tests).

---

# Ronda 2 (correcciones tras el juez; verificadas en codigo)

1. **Cobertura del visor extraido restaurada** (sustituye lo dicho arriba sobre los tests de gestos/recomposicion "borrados sin sujeto"):
   - `MainActivityArtifactPreviewGestureTest` (actividad real, tarjeta HTML ENTREGADA en Central -> `ARTIFACT_PREVIEW`): gestos vertical/diagonal/horizontal/fling, tap/cancel/multitouch, Back al hilo con borrador intacto + gesto del drawer, rotacion (restaura historial/scroll, 1 `restoreCalls`, sin recarga), pausa/reanudacion y politica del navegador, y `deliveredHtmlCardUsesItsOwningArtifactRouteAcrossRecreationBackAndReopen` (ruta con sessionId/artifactId, bytes del snapshot aunque el proyecto cambie, recreacion con 1 restore, Back, reabrir). 6 tests.
   - `ArtifactSnapshotPreviewRecompositionTest`: no recarga ni pierde historial/scroll al recomponer; cambiar de snapshot usa su propio cliente y restaura solo su historial. 2 tests.
   - Barrera de S1: `@Before` anadido a `PreviewMobileViewportIntegrationTest` y `PreviewResponsePolicyTest` (`runBlocking { SingleSessionReset.awaitReady(context) }`); ahora pasan (antes 12 y 13 rojos, identicos en HEAD). `ArtifactHtmlPreviewUiTest` ya tenia su espera y pasa 10/10.
2. **Tarjetas recuperables**: `WorkspaceMissionCards.append` es idempotente por stableId y comprueba existencia antes de escribir; `WorkspaceResultDelivery.notice` ya no condiciona a `inserted`; `reconcile()` recupera la tarjeta de delegacion de cada asignacion BOUND (`announceDelegations`). Un fallo de escritura se reporta y se reintenta en la siguiente pasada. Test: `MissionRefTest.reconciliationRecoversCardsLostAfterTheDelegationAndTheNotice` (notice sin tarjeta -> reconcile x2 -> exactamente una de delegacion y una de resultado).
3. **Re-resolucion de tarjetas**: `MissionRefCard` solo se vuelve a resolver con cada `WorkspaceChanges.revision` mientras la mision pueda cambiar; una mision resuelta sin bots ni mision activos queda "settled". Deuda: si el usuario reanuda un bot de una mision ya asentada, su tarjeta (si sigue compuesta) no se refresca hasta recomponerse; cada resolucion sigue abriendo el registro y leyendo los snapshots de la sesion (sin consulta por mision).
4. **Comentarios corregidos** (`WorkspaceMissionCards`, `LocalRunStore.appendMissionReferenceMessage`): la tarjeta se EXCLUYE del contexto del modelo.
6. **`onNewIntent`**: tarea y mision solo navegan con `currentBackStackEntry != null` (si no, quedan pendientes para el hook post-grafo); un intent nuevo reemplaza `pendingMission` en vez de quedar capturado por un objetivo viejo. Test: `B12_aLaterIntentReplacesAMissionTargetThatIsStillWaitingForTheGraph`.

**Comandos/resultados (ronda 2)**
- Compilacion (`stage-S4-compile-r2.log`): el comando del brief (Full+Play main y Full tests) -> BUILD SUCCESSFUL. `:app:compilePlayDebugUnitTestKotlin :app:compilePlayDebugUnitTestJavaWithJavac` (`stage-S4-compile-play-tests.log`) -> BUILD SUCCESSFUL. (Un primer intento fallo por OOM del daemon de Kotlin al coincidir varios Gradle; repetido con un solo daemon.)
- `stage-S4-r2-final.log` (`--rerun`, 16 clases: MainActivityWorkspaceRoutingTest, MainActivityArtifactPreviewGestureTest, ArtifactSnapshotPreviewRecompositionTest, PreviewMobileViewportIntegrationTest, PreviewResponsePolicyTest, ArtifactHtmlPreviewUiTest, MissionRefTest, MissionRefCardComposeTest, WorkspaceResultDeliveryTest, WorkspaceCoordinatorIntegrationTest, AppNavigationBackPolicyTest, SingleSessionStartupTest, CrewC1ComposeTest, CrewC2Test, WorkspaceChangesTest, HtmlPreviewSnapshotTest): 148 tests, 3 fallos = los 3 de `HtmlPreviewSnapshotTest` ya comparados con HEAD (2 symlink sin privilegio + `missingToolPresentation...`, mismos en `stage-S4-head-html.log`). Todo lo demas en verde.