# Corrección S1 — informe final

**Kotlin/Java compilan. B9/B10/B11: 6/6 PASS. Última tanda: 8/8 PASS, sin errores ni omitidos.** La selección dirigida tiene 41 casos distintos con último resultado PASS (combinando dos tandas, no una ejecución única de 41 en verde).

Trabajo en `C:\Projects\Jarvys-wt\single`, rama `feat/single-session`. Se conservaron los cambios S1 sin commit. Sin commit, push, APK ni suite completa. `docs/SINGLE_SESSION.md` intacto: SHA256 `68A6FB2318D1976349B1EA95C35D2CB7BC29DC2B069CB4CC02436BBFDED89ACE`.

## Correcciones por incidencia

1. **Arranque y barrera.** `FactoryInstallApplication.attachBaseContext` registra la barrera antes de los proveedores/WorkManager; `onCreate` lanza el reset en un executor y retorna. La barrera se completa sólo tras `created/registry_created/invalidated/done`. MainActivity suspende su inicialización; servicio espera antes de sus locks y canaliza errores por la admisión recuperable; workers esperan/reintentan o rechazan la generación vieja. Room, WorkspaceRegistry y StorageGeneration protegen accesos síncronos: esperan en worker y rechazan acceso temprano desde main. Se añadió `GeneratedImageFileProvider` porque el proveedor anterior accedía directamente a g3. La retirada se reanuda en worker después de liberar la barrera y usa un lock separado para el borrado.
2. **Base antigua corrupta.** La invalidación no abre ni consulta SQLite antigua. Cancela directamente los tokens de notificaciones publicadas y los trabajos de memoria por etiqueta/nombre; receptores y workers mantienen guardas de generación para tokens antiguos. La notificación del servicio actual se distingue por generación. Los bytes antiguos se conservan hasta la retirada autorizada por el primer run correcto.
3. **B9.** Nueva prueba de arranque que invoca Application productiva sobre el looper principal, retiene explícitamente el executor del reset y comprueba que retorna; una Activity real espera sin construir su store, y se admite al liberar el reset. Verifica también guardia del proveedor antes de onCreate, DB vieja corrupta intacta, fase done, token cancelado y Central vacía. El inventario de retirada es literal e independiente: attachments, delivered y sus thumbnails, generated, crew-context/checkpoints, workspaces, workspace-inspections, coding-projects, coding-project-state, runs, notes, memory/MemFS, index y download_results. Sin sleeps ni umbrales temporales como oráculo.

## Archivos y tests

Producción del arreglo (incluido el intento parcial reanudado): `SingleSessionReset.kt`, `StorageGeneration.java`, `apkfactory/FactoryInstallCoordinator.kt`, `MainActivity.kt`, `AgentForegroundService.java`, `chatdb/JarvysChatDatabase.kt`, `workspaces/WorkspaceRegistry.kt`, `PersonalMemoryExtractionWorker.kt`, `PersonalMemoryConsolidationWorker.kt`, `proactive/ProactiveWorker.kt`, `tasks/TaskTickWorker.kt`, `tasks/TaskManualRunWorker.kt`, `tasks/TaskPendingActionExecution.kt`, nuevo `GeneratedImageFileProvider.kt` y `AndroidManifest.xml`.

Tests añadidos/adaptados, sin eliminar casos:
- `SingleSessionStartupTest`: nuevo B9 determinista descrito arriba; usa API28 como los otros B9.
- `SingleSessionResetTest`: inventario literal, proveedor productivo y cancelación independiente de legacy.
- `GeneratedImageStoreTest`: apunta al proveedor productivo nuevo, manteniendo URI/bytes/permisos.
- `MainActivityWorkspaceRoutingTest` y `tasks/TaskManualRunWorkerTest`: fixtures esperan la barrera desde worker antes de construir stores.
- `AgentForegroundServiceMainThreadAdmissionTest`: fixtures AGENT/SYSTEM/USER usan Central y seleccionan mensaje por ID; conserva las comprobaciones de admisión fuera de main y de que SYSTEM no inicia un turno.

## Comandos y resultados

Desde `C:\Projects\Jarvys-wt\single\app`:

```powershell
.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac --console=plain
```

**BUILD SUCCESSFUL in 15s**, exit 0, 38 tareas. [Log final](stage-S1fix-compile-final.log). Se ejecutó tras el último cambio Java del servicio.

```powershell
.\gradlew.bat :app:testFullDebugUnitTest --tests com.jarvys.agent.SingleSessionResetTest --tests com.jarvys.agent.SingleSessionReadBoundariesTest --tests 'com.jarvys.agent.MainActivityWorkspaceRoutingTest.B10_startIntentNewChatAndRestoredPickerAlwaysUseCentral' --tests com.jarvys.agent.SingleSessionStartupTest --tests 'com.jarvys.agent.GeneratedImageStoreTest.fileProviderOnlyExposesThePrivateGeneratedSubtreeAndIsNotExported' --tests com.jarvys.agent.AgentForegroundServiceMainThreadAdmissionTest -x :app:prepareApkFactoryTemplate --console=plain --rerun
```

**BUILD SUCCESSFUL in 54s**, exit 0. XML: **8 tests, 0 failures, 0 errors, 0 skipped**. [Log](stage-S1fix-contracts-final.log), [resumen](stage-S1fix-final-summary.json), [XML](stage-S1fix-contracts-final-results/).

Regresión integrada anterior, acotada a clases afectadas:

```powershell
.\gradlew.bat :app:testFullDebugUnitTest --tests com.jarvys.agent.SingleSessionResetTest --tests com.jarvys.agent.SingleSessionReadBoundariesTest --tests com.jarvys.agent.MainActivityWorkspaceRoutingTest --tests com.jarvys.agent.SingleSessionStartupTest --tests 'com.jarvys.agent.GeneratedImageStoreTest.fileProviderOnlyExposesThePrivateGeneratedSubtreeAndIsNotExported' --tests com.jarvys.agent.AgentForegroundServiceMainThreadAdmissionTest --tests com.jarvys.agent.tasks.TaskWorkManagerTest --tests com.jarvys.agent.tasks.TaskManualRunWorkerTest --tests com.jarvys.agent.proactive.ProactiveWorkManagerTest --tests com.jarvys.agent.tasks.TaskPendingActionExecutionTest --tests 'com.jarvys.agent.chatdb.JarvysChatDatabaseM0Test.createsEveryDeclaredEntityTableAndEnforcesPragmaContract' --tests 'com.jarvys.agent.workspaces.WorkspaceRegistryTest.registryDoesNotMigrateLegacyFilesOrInventWorkOnOpen' -x :app:prepareApkFactoryTemplate --console=plain --rerun
```

**40/41 PASS** en 1m25s; único fallo: el nuevo B9 consultaba un listado que oculta Central sin mensajes. Se corrigió a lectura directa y pasó en la tanda final. Los 33 casos no repetidos no fueron afectados por el último cambio exclusivo de captura de excepciones del servicio; el test de servicio sí se repitió. [Log](stage-S1fix-selected-final.log), [último resultado por caso: 41/41](stage-S1fix-selected-latest.json).

`git diff --check`: limpio. Se excluyó siempre `prepareApkFactoryTemplate` en tests para evitar generar APK.

## Límites y fallos externos observados

- En la primera selección de `GeneratedImageStoreTest` reaparecieron dos fallos de privilegios para symlinks Windows, ya acreditados contra baseline S1. No se alteraron ni se volvieron a ejecutar sin necesidad.
- La selección adicional `Factory*ApplicationStartupTest` produjo 17 fallos en setup, antes de llamar a Application, por `FactoryFileShareOsShadow.mkdir -> Files.setAttribute(unix:mode)` no soportado en Windows; `FactoryPhotoApplicationStartupTest` produjo un error de inicialización por no tener métodos ejecutables. Esos archivos y el shadow no tienen diff respecto HEAD. No se ejecutó un nuevo HEAD para estos 18 ni se cuentan dentro de los 22 fallos baseline de S1. [Resultados](stage-S1fix-regression-attempt1/); comandos e historial en [progreso](stage-S1fix-progress.md).
- Robolectric API34 usa `AtomicFile.finishWrite -> File.renameTo`, que no reemplaza el destino existente en este Windows y sólo registra el fallo. Se confirmó por bytecode local y reproducción independiente. B9 usa API28, sin parchear producción para el host. [Evidencia y límite del diagnóstico](stage-S1fix-atomicfile-windows.txt).
- No se validaron dispositivo/emulador, ANR reales, persistencia AtomicFile API34 en Android real, WorkManager antiguo realmente activo durante actualización, cortes físicos de disco ni rendimiento con historial grande. La cobertura de entradas adicionales combina pruebas dirigidas e inspección; B9 demuestra dinámicamente Application, Activity y proveedor, no todos los componentes concurrentes posibles.
- Sin Play, APK, suite completa ni run real con proveedor de modelo. `gentle-ai` no está disponible en PATH; no se activó flujo de recibos ni se declara aprobación por recibos.
