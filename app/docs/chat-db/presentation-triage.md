# Presentation timeline test triage

## Migrated assertions

The presentation timeline now exposes durable presentation output through the generic `artifact` event. Tests that previously selected durable output using the legacy `delivered_file` or `generated_image` event kind now select `artifact`; their content, preview, file-transfer, deletion, state-restoration, and metadata assertions remain in place.

| Test | Migrated checks |
|---|---|
| `AgentRunRestorationComposeTest.restoredDeliveredPreviewHidesOnlyItsToolResultBeforeGrouping` | Card selection and projected-card assertion; preview-result suppression and tool-group checks retained. |
| `AgentRunUiStateTest.toolResultUsesLatestSharedSessionSnapshotAfterConcurrentSessionWrites` | Artifact presence assertion across live, session, and worker snapshots; steering and session-ownership checks retained. |
| `ArtifactHtmlPreviewUiTest.restoredHtmlCardEnglishLightAt320dpAnd200PercentKeepsThumbnailAndIconActions`, `restoredHtmlCardEnglishDarkAt320dpAnd200PercentKeepsThumbnailAndIconActions`, `restoredHtmlCardSpanishLightAt320dpAnd200PercentKeepsThumbnailAndIconActions`, `restoredHtmlCardSpanishDarkAt320dpAnd200PercentKeepsThumbnailAndIconActions` | Shared restored-card selector migrated (four invocations); preview, thumbnail, and icon-action checks retained. |
| `ArtifactHtmlPreviewUiTest.thumbnailPointersExportRealImmutableBytesThenOpenAndShareThroughTheTransferViewModel`, `missingDeliveredSourceHasNoPreviewOrExportControls`, `resolvingHtmlCardNeverFlashesFilenameMimeOrStaticCaptionInObservedFrames`, `nonHtmlApkAndBinaryHtmlKeepDownloadShareWithoutAnHtmlPreviewAction` | Four selectors; immutable-byte transfer, missing-source, observed-frame, and non-HTML checks retained. |
| `ConversationVisibilityTest.errorsFinalAnswersDecisionsAndUsefulToolResultsStayVisible` | Replaced a kind-only legacy sample with `deliveredFileEvent`, which constructs a durable generic artifact carrying attachment metadata; visibility assertion retained. |
| `GeneratedImageStoreTest.imageRowsRoundTripAndConversationDeletionRemovesOnlyThatImageDirectory`, `liveImageEventRefreshesFromLedgerWithoutDuplicationAndFailedRowsRestore` | Seven event selectors/counts; image path, prompt, revised prompt, MIME, status/error, file deletion, deduplication, restoration, and conversation-deletion checks retained. |
| `HtmlThumbnailCardUiTest.longFilenameEnglishLightKeepsOnlyTheEdgeToEdgeThumbnailAtFont200`, `longFilenameSpanishDarkKeepsOnlyTheEdgeToEdgeThumbnailAtFont200`, `deliveredHtmlCardInAnotherConversationHasNoPreviewOrExportControls` | Two selector sites (the long-filename helper runs twice); thumbnail and cross-conversation privacy checks retained. |
| `HtmlThumbnailLifecycleUiTest.disposingACardCancelsItsPendingRendererAndIgnoresALateVisualCallback`, `backgroundingCancelsAndResumeStartsAFreshRendererWithoutUsingStalePixels`, `replacingTheConversationReleasesTheOldRendererBeforeAdmittingTheNewOne`, `deletingAConversationWhileItsRenderIsPendingCannotRecreateItsFilesOnLateCompletion`, `repeatedUnrelatedRecompositionKeepsOnePendingWebViewAndOneQueueSlot`, `twoVisibleCardsSerializeRenderersAndCancellingTheFirstAdmitsTheWaitingCard`, `missingVisualCompletionTimesOutToAnHonestFallbackAndReleasesTheNativeRenderer` | Shared fixture selector site migrated (10 invocations across seven tests); renderer lifecycle and cancellation checks retained. |
| `DeliveredFileUiTest.restoredFileShowsMetadataAndWorkingScopedControlsInLightTheme`, `restoredFileShowsMetadataAndWorkingScopedControlsInDarkTheme`, `deliveredImageRestoresAsImageAndViewerHasDownloadShareWithoutTinyFilename`, `undecodableImageStillExposesOriginalFileActions`, `missingRestoredFileHasHonestUnavailableStateWithoutEnabledDownload` | Four selector sites; metadata, image viewer, transfer controls, unavailable state, and file ownership checks retained. |
| `MainActivityDownloadFlowTest.saveUsesDownloadsWithoutPickerAndDuplicateTapsWriteOnceThenExplicitOpenGrantsReadOnly`, `shareOriginalUsesTranscriptScopedReadOnlyProviderWithoutExportOrPicker`, `switchingChatAndRotationDuringCopyKeepsSourceAndSelectedConversation`, `cancelAnActiveCopyCleansPendingOutputAndRecoversDownloadControl`, `destinationFailureIsNotReportedAsSuccessAndRetryUsesTheSameChat`, `legacyPermissionDenialUsesActualResultCallbackAndNeverClaimsSaved`, `staleSourceAfterConversationDeletionFailsBeforePublicStorageMutation` | Shared generated-image fixture selector site migrated (seven invocations); transfer-flow checks retained. |
| `MainActivityImageDownloadGestureTest.realImageViewerSaveGestureExportsAndOpenIsOnlyLaunchedAfterTheOpenGesture` | Generated-image fixture selector; save/open gesture behavior retained. |

In total, 24 event-selector expressions were migrated, and one kind-only visibility sample now uses the generic event factory. No tests were removed or weakened. Schema tests that refer to the physical `generated_images` table remain unchanged; they do not select timeline event kinds.

## WebView real de módulos y lienzo

El AVD `JarvysModuleWebView34` (Android 14 / API 34, Google APIs x86_64; System WebView 113.0.5672.136) reprodujo el fallo antes de la corrección. Los diagnósticos instrumentados mostraron `Resource denied: data://no-host ... (main frame)` y HTTP 403. `loadDataWithBaseURL` entrega el documento bootstrap inicial como una carga `data:` aun cuando recibe una base HTTPS; el filtro del runtime aplicaba la allowlist de subrecursos al documento principal y lo bloqueaba antes de cargar `logic/tools.js`. El error CSP aparecía como consecuencia. `blockNetworkLoads` permaneció activo.

El runtime ahora deja pasar una sola carga inicial `data:` de main frame, creada por el propio anfitrión. Los subrecursos y las navegaciones siguientes siguen sujetos al filtro del bundle, la CSP y la red bloqueada. Los mensajes de fallo registran recurso/motivo o el tipo seguro de excepción; consola cruda, parámetros, queries y fragmentos no se conservan.

`WebViewModuleRuntimeInstrumentedTest` ejecuta `create_chart` y `create_presentation` contra los assets de producción en Chromium del sistema, comprueba la entrega del JSON/PPTX y valida miniaturas PNG decodificables de 1280×720 con píxeles no vacíos. `ChatCanvasWebViewInstrumentedTest` carga HTML, CSS y JS de producción con red deshabilitada. Esa prueba encontró además que `canvas.js` no pasaba el objeto global a su factory, lo que causaba un `ReferenceError` al inicializar el scroll; el factory ahora recibe el global.

Estas pruebas usan el AVD y son distintas de los tests JVM/Robolectric. Para instalar el APK debug, que el proyecto produce sin firma, se firmaron copias temporales de los APK en `%LOCALAPPDATA%\Temp`; no se cambió la configuración ni el artefacto firmado del proyecto. No hubo cambios al esquema Room v2.

## P4 autonomy default and Wizard

Modules and actions without an explicit saved choice use the single policy selected in the setup Wizard, which defaults to `ALLOW`. Modules remain in their Modules workspace, outside Connectors; `ConnectorRegistry` and `ActionPolicyDefaults` provide the shared default to those action families. Explicit per-tool choices remain authoritative.

MCP keeps the saved initial policy for each server. `COMMON_DEFAULT` follows the Wizard choice, `EXPLICIT_CURRENT` and `LEGACY_UNKNOWN` use the server's saved value, and `INVALID` fails closed to `DENY`; saved per-tool policies still take precedence. The server editor again exposes the initial-policy selector with Allow, Ask, and Deny. A new server starts at the current Wizard default, and the selection becomes explicit only after the user changes it and confirms Save.

The original coverage is restored in `McpInitialToolPolicyTest`, `McpNavigationQ1Test`, `Ux21ConnectorPermissionInteractionTest`, and `McpActionLeaseIntegrationTest`. The lease test covers both a saved server-level Ask and the additional Wizard Ask fallback without materialized per-tool rows. `SetupWizardPreferencesTest` also verifies that persisted action defaults are read from their owning preferences even when the global preference binding is absent. If the connector policy store fails, the displayed policy remains restricted (`ASK`); action admission still fails closed through its saved snapshot. `AppNavigationBackPolicyTest` lists the workspace-session preview and module-artifact-viewer routes, matching the actual navigation destinations.

## Markdown en streaming con bloques inline

La fuente etiquetada de `multiplatform-markdown-renderer` `0.37.0` confirma que `rememberMarkdownState(content)` crea un estado nuevo al cambiar el contenido; ese estado empieza en `Loading` y se parsea en `Dispatchers.Default`. La opción `immediate = true` llama a `parseBlocking()` durante la composición en el hilo principal. Además, `collectAsState()` todavía entrega el último estado recogido durante el primer frame tras un delta, así que el parseo inmediato no evita por sí mismo el frame de carga. `AssistantMarkdown` usa ahora el parseo asíncrono predeterminado y conserva el último `State.Success` mientras llega el resultado; si el parseo termina en error, muestra el texto como texto plano. Fuentes: [MarkdownState.kt de v0.37.0](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/v0.37.0/multiplatform-markdown-renderer/src/commonMain/kotlin/com/mikepenz/markdown/model/MarkdownState.kt#L34-L64), [Markdown.kt de v0.37.0](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/v0.37.0/multiplatform-markdown-renderer/src/commonMain/kotlin/com/mikepenz/markdown/compose/Markdown.kt#L85-L130).

`AssistantMarkdown` mantiene una única instancia de `MarkdownComponents`; el callback del fence lee `streaming` desde un `CompositionLocal`, evitando cambiar la identidad de componentes al promover el mensaje. `MarkdownFenceSource` determina desde el AST si la cabecera terminó, buscando un hijo EOL o contenido después de `FENCE_LANG`. Durante el stream, los lenguajes registrados permanecen en un placeholder de una sola línea hasta cerrar el fence; los desconocidos usan ese placeholder solo mientras la cabecera sigue incompleta y luego se muestran como código mientras crece su contenido. Los renderers registrados conservan el mismo contenedor al cerrar el fence. El test Compose `assistantMarkdownStreamingRetainsRenderedContentAcrossDeltasAndPromotion` mide carga, placeholder, montajes visuales y de fila, altura intermedia y movimiento de la fila con fences `chart` y `svg`.

`InlineMessageBlocksTest.incompleteSpecialFencesWaitOnlyWhileStreamingAndThenFallBackToCode` cubre una cabecera desconocida incompleta y una completa; `openKotlinFenceUsesCodeInsteadOfAStreamingPlaceholder` comprueba que un fence abierto `kotlin` elige la ruta de código durante el stream. No se debilitó ningún test.

### Medición de `8fe9ebd` y del arreglo

Métricas Robolectric del test Compose con ocho deltas, dos visuales y promoción. La base se ejecutó en el worktree temporal `C:\jvf` preparado desde `8fe9ebd`, añadiendo el mismo test y observadores que solo cuentan eventos, sin aplicar el arreglo. Sus dos fallos son el test nuevo de flicker y el caso del prefijo desconocido añadido arriba. El test aislado de la solución pasó.

| Medición | `8fe9ebd` | Arreglo |
|---|---:|---:|
| Composiciones de carga, tras parsear el texto inicial → final | 1 → 10 | 0 → 0 |
| Composiciones del placeholder | 8 | 5 |
| Montajes / parseos de chart | 5 / 5 | 1 / 1 |
| Montajes / parseos de SVG | 3 / 3 | 1 / 1 |
| Fallbacks a código durante la escritura | 2 | 0 |
| Montajes de la fila durante stream y promoción | 1 | 1 |
| Altura mínima observada tras mostrar contenido | 0 px | 122 px |
| Recorrido del borde inferior de la fila / reversiones | 452 px / 9 | 396 px / 0 |

El desplazamiento del borde acompaña el crecimiento del contenido; las reversiones cuentan movimientos hacia atrás entre frames, que aparecían durante los vaciados en la base. El arreglo mantiene una altura visible mínima y elimina esas reversiones.

## Contrato del módulo de presentaciones

El contrato Node de `create_presentation` conserva las comprobaciones anteriores del renderer: texto y formas de la segunda diapositiva, colores, aviso ante `graphicFrame`, ajuste al área del visor, contador, entrega al visor de los bytes del PPTX y PNG de miniatura. El fixture ahora suma una cuarta diapositiva para cubrir gráfico y tabla OOXML nativos, sus valores/categorías y el contraste sobre tema oscuro. El valor esperado de texto `24324A` cambió a `101828` porque ese es el nuevo color de tinta predeterminado con contraste para fondos claros. También se comprueba que el skill del módulo quede por debajo del límite real de lectura de 256 KiB. La aserción anterior de `grid-template-rows` se cambió y el nodo `screen-title` se quitó del fixture; las demás comprobaciones de contenido y renderizado se conservaron.

La expectativa del título del renderer se mantiene en el tamaño promovido por el nuevo default (`48px`, antes `40px`); junto con el cambio de color de tinta, la prueba sigue comprobando una salida concreta y legible.

### Visor final PptxGlimpse

Se adoptó PptxGlimpse 5.3.1 para la vista de pantalla completa. El contrato Node de `create_presentation` conserva las aserciones previas de generación, OOXML, miniatura PNG y renderer de miniaturas; además, ahora comprueba que la URL del artefacto entrega al visor los mismos bytes PPTX, que `convertPptxToSvg` recibe esos bytes y produce la lista de diapositivas, que los controles Anterior/Siguiente cambian la diapositiva y el contador, y que el ajuste de imagen cabe en el stage. Un fallo al decodificar la diapositiva debe dejar un aviso localizado visible y retirar la imagen rota. La aserción anterior `grid-template-rows: auto minmax(...) auto auto` se cambió para comprobar `grid-template-rows: minmax(0, 1fr) auto auto`, y se quitó el nodo `screen-title` del fixture porque el visor ya no lo contiene.

`run-final-viewer.cjs` usa el HTML y JavaScript de producción con el encabezado CSP actual y un endpoint local que simula la ruta de artefacto allowlisted del WebView. Las capturas cubren las cinco muestras de evaluación, sus tres diapositivas de tema y un fixture adicional con PNG incorporado mediante el data URL que acepta `create_presentation`; son ocho diapositivas más una captura en tema claro con controles habilitados. Todas mostraron imagen, cada PPTX se leyó una vez y el harness no observó peticiones HTTP externas. `deck-renderer.js` continúa probado para generar la miniatura de la tarjeta; no se carga como segundo visor. No hubo cambios a Kotlin, CSP, permisos ni Room v2.

El contrato Node comprueba que los datos de gráfico vacíos, los valores no finitos, las longitudes incompatibles, el texto desbordado y los visuales fuera de la diapositiva produzcan errores accionables sin entregar un artefacto. Los placements explícitos se conservan si no pisan texto ni otro visual; los placements automáticos de tabla reservan el rectángulo de un gráfico explícito y el margen inferior. El caso chart+table que cabe sigue exigiendo ambos elementos. `assertSlideElementsWithinHeight` comprueba x, y, ancho, alto y solapes entre cajas del PPTX, permitiendo únicamente los pares intencionales de forma y etiqueta con límites idénticos.

Cambios explícitos de contrato y razón:

- Se retiró la expectativa **“dense content must still deliver”**: texto que no cabe ahora falla antes de entregar un PPTX; `fit: 'shrink'` mantiene el contenido dentro de cajas medibles y los visuales necesitan su propio hueco.
- Se sustituyó **“visuals are omitted”** por dos comprobaciones: un visual explícito no solapado permanece en su coordenada y un visual automático evita su rectángulo; si una coordenada explícita pisa viñetas, el caso debe fallar y no entregar un artefacto.
- Se conserva **“empty datasets are ignored”** solo para un dataset vacío junto a otro dataset válido, acorde con el filtro que construye el gráfico. Un gráfico con todos los datasets vacíos produce el error accionable de falta de datos.
- Se cambió el formato esperado `#,##0` por `General` para no forzar separadores de millares y conservar la presentación de valores fraccionarios; el contrato exige exactamente `General` y conserva `0.35` y `3.5` en la caché OOXML.
- Se sustituyó la aserción de alturas siempre mayores que cero: las líneas horizontales y verticales de usuario pueden tener una dimensión cero, mientras que el área completamente cero sigue siendo inválida. Las cajas de chart/table, texto e imágenes aún requieren dimensiones positivas.
- La altura de texto se estima con interlineado `1.15`, derivado de las métricas hhea de Arial/Helvetica `(ascent + descent + lineGap) = 2355/2048`; las cajas del cuerpo usan `normAutofit` nativo como respaldo. La medición convierte márgenes en puntos a pulgadas y serializa los mismos puntos en el PPTX.

El contrato también cubre 20 líneas, texto junto a chart, líneas horizontal/vertical, ancho exacto `12192000/914400`, el caso de chart explícito en `y=2.5,h=2` y el de `y=4,h=2` con tabla automática, valores finitos/no finitos y límites x/ancho. El cambio requiere volver a ejecutar `ModuleBundleIntegrationTest`.
