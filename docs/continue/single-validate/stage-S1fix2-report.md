# S1fix2 — progreso

Worktree `C:\Projects\Jarvys-wt\single`, rama `feat/single-session`. Se leyeron completos los informes S1, S1fix y `stage-S1-judge2-last.txt`. Se preservan todos los cambios previos sin commit; copia byte a byte inicial en `stage-S1fix2-before/` y hashes en `stage-S1fix2-before-manifest.json`. No commit/push/APK ni cambios a `docs/SINGLE_SESSION.md` (SHA256 inicial 68A6FB2318D1976349B1EA95C35D2CB7BC29DC2B069CB4CC02436BBFDED89ACE).

## Confirmado en código

1. MainActivity consume fotos/documentos con picker null y ViewModel ausente; skills llama un repositorio aún no inicializado. TakePicture presenta el mismo descarte. Se implementa retención persistible y drenaje después de inicializar los consumidores y seleccionar Central en el ViewModel.
2. onSaveInstanceState escribe campos aún vacíos mientras onCreate espera awaitReady. Se adelanta la restauración puramente en memoria antes de suspender, incluida normalización del picker.
3. StartupTest llama start antes de Application.onCreate y enmascara la ausencia de la llamada productiva. Se sustituirá sólo su executor; Application deberá agendar el primer reset. Pendiente control negativo retirando temporalmente la llamada, con restauración exacta.

## Estado actual

Ruta delegated direct: explorador de entradas adicionales de Activity/servicio/receptor y un único escritor del arreglo y tests. Sin SDD. Las tres incidencias se han verificado además directamente por el agente principal. Implementación en curso; compilación y tests todavía NO ejecutados en esta ronda. `gentle-ai` no está en PATH; no se activó flujo de recibos ni se declara aprobación (unmanaged).

## Pendiente

Revisar cambios; compilar Kotlin/Java; ejecutar B9/B10/B11/Startup y tests existentes acotados a clases afectadas; control negativo de Application; actualizar comandos exactos, resultados y límites. No ejecutar suite completa. No repetir fallos de symlinks ya acreditados.

## Inspección adicional

- Cámara: callback TakePicture comparte descarte por ViewModel ausente; incluido en corrección. La recuperación completa del archivo de cámara tras muerte del proceso es un límite preexistente distinto y se está acotando el cambio.
- Permiso de notificaciones: refreshProactiveStatus guarda el acceso a preferencias no inicializadas y se repite al inicializar.
- onNewIntent: conserva el Intent con setIntent antes del retorno temprano; inicialización lee el último Intent.
- Servicio: onStartCommand conserva payload en cola; drainSession (AgentForegroundService.java:221–223) espera antes de admisión.
- ProactiveActionReceiver/TaskActionReceiver: payload en worker y barrera en LocalRunStore. ApprovalActionReceiver usa estado en memoria. TaskPendingActionReceiver/TaskRearmReceiver usan almacenamiento de tareas fuera de g3 y ejecución posterior protegida.
- Activities Factory/receptor de instalación: coordinadores propios sin lectores de conversación aplazados por esta barrera. Notification listener: executor propio, configuración/memoria y programación proactiva.

Inspección estática, no prueba exhaustiva de todos los componentes concurrentes. El informe final distinguirá estas conclusiones de las intercalaciones ejecutadas en Robolectric.

## Primera compilación del arreglo

Desde `C:\Projects\Jarvys-wt\single\app`: `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac --console=plain` → exit 0, BUILD SUCCESSFUL in 12s, 38 tareas (3 ejecutadas). Log: `stage-S1fix2-compile.log`. Avisos de Java source/target 8/deprecaciones; ningún error. Tests nuevos todavía en preparación. Se verificó por código que el drenaje ocurre después de switchSession(Central).

---

# Cierre S1fix2 (segundo implementador)

## Estado final
Compilacion y tests en verde. Sin commit, push ni APK. `docs/SINGLE_SESSION.md` intacto (SHA256 68A6FB23...ED89ACE). Los cambios de MainActivity (retencion y drenaje de resultados tras la barrera, restauracion en memoria antes de suspender, camara, onNewIntent) ya estaban hechos por el implementador anterior; yo los revise contra el diff y los deje tal cual.

## Cambios por incidencia
1. Resultados de selectores/skills/camara: MainActivity retiene fotos, documentos, skills y resultado de camara (`pendingPhotoUris`, `pendingFileUris`, `pendingSkillUris`, `pendingCameraResult`) y los procesa en `consumePendingActivityResults()` solo cuando `activityResultsReady` (tras inicializar attachmentDrafts, skillRepository y seleccionar Central). Se persisten en el Bundle.
2. onSaveInstanceState: `restoreConversationUiState()` restaura antes de suspender en awaitReady (borrador, skills, titulo, picker normalizado a Central, pendientes, ruta de camara), de modo que una recreacion durante la espera guarda el estado real.
3. Test de arranque: `SingleSessionReset.executorForTests` (internal, solo sustituye el executor productivo; `start(context)` sigue siendo la unica llamada) y la Application (`FactoryInstallCoordinator.kt:420`) realiza la primera llamada.

## Tests (SingleSessionStartupTest, 5 tests)
- B9 adaptado: executor retenido via `executorForTests`; ya no llama start antes de Application.onCreate.
- Nuevos: fotos y documentos (resultado real de Activity entregado con reset retenido, doble recreacion, borrador y picker sobreviven, adjunto copiado tras liberar), skill (importacion espera al repositorio y el pendiente se conserva en el Bundle), camara (resultado y destino sobreviven).
- Arreglos de aislamiento solo en el test: se usa `controller`-reflection para `onSaveInstanceState` (protegido) y un `@Before` que vacia `FileProvider.sCache` y `ViewModelProvider.AndroidViewModelFactory._instance` (estaticos que retienen el Application/dataDir del primer test del sandbox; causaban que los adjuntos cayeran en el directorio de otro test). No afecta a produccion.
- Control negativo: quitada temporalmente `SingleSessionReset.start(this)` de FactoryInstallCoordinator.kt:420 -> los 5 tests fallan ("Production Application must schedule the first reset" / "Application startup queued reset..."). Linea restaurada byte a byte (hash identico). Log: stage-S1fix2-negative-control.log.

## Comandos ejecutados (desde C:\Projects\Jarvys-wt\single\app, JAVA_HOME/ANDROID_HOME fijados)
- `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac --console=plain` -> exit 0, BUILD SUCCESSFUL (stage-S1fix2-compile-final.log).
- `.\gradlew.bat :app:testFullDebugUnitTest --rerun --tests com.jarvys.agent.SingleSessionResetTest --tests com.jarvys.agent.SingleSessionReadBoundariesTest --tests 'com.jarvys.agent.MainActivityWorkspaceRoutingTest.B10_startIntentNewChatAndRestoredPickerAlwaysUseCentral' --tests com.jarvys.agent.SingleSessionStartupTest --tests com.jarvys.agent.AttachmentDraftViewModelTest -x :app:prepareApkFactoryTemplate --console=plain` -> BUILD SUCCESSFUL: ResetTest 2/2, ReadBoundaries 2/2, B10 1/1, StartupTest 5/5, AttachmentDraftViewModelTest 10/10 (stage-S1fix2-tests-final.log).
- Intentos previos fallidos (attempt1..8) fueron errores del propio test (visibilidad protected, FileProvider cache-path, estaticos entre tests, URI file:// rechazada por AttachmentStore); ya resueltos.

## Otros caminos revisados (estatico, ver seccion "Inspeccion adicional")
Camara (incluida), permiso de notificaciones, onNewIntent, AgentForegroundService (drainSession espera), receptores Proactive/Task/Approval/TaskPending/TaskRearm, Factory, notification listener.

## NO verificado
- Suite completa ni otras clases de tests; GeneratedImageStoreTest (fallos de symlinks de Windows ya acreditados contra baseline) no se ejecuto.
- Muerte real de proceso en dispositivo/emulador: solo Robolectric (recreacion + resultado real del registry de Activity), no restauracion tras kill.
- La recuperacion completa del archivo de camara tras morir el proceso queda como limite preexistente (solo se restaura la ruta si esta en el cache de camara).
- Revision de otros componentes concurrentes: inspeccion estatica, no pruebas.