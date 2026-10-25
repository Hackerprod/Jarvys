# Corrección S1 — progreso

## Estado
- Leídos completos el informe del juez, el informe S1, la última entrega y la brief original.
- Cambios S1 sin commit presentes y preservados. No commit, push, APK ni edición de docs/SINGLE_SESSION.md.
- Ruta: implementación delegada a un único writer para la corrección transversal y B9; compilación, pruebas y revisión del resultado a cargo del agente principal.
- `gentle-ai review mode status` no se puede ejecutar: comando no disponible en PATH. No se ha activado ningún flujo de recibos ni se declara aprobación.

## Trabajo pendiente
1. Sustituir runBlocking del Application productivo por inicialización asíncrona y admisión de consumidores tras una barrera real.
2. Independizar invalidación de la lectura de la base antigua corrupta; conservar sus bytes hasta retirada.
3. B9: Application productiva, consumidor temprano, corrupción y lista de árboles literal independiente.
4. Compilar Kotlin/Java y ejecutar B9/B10/B11 y regresión existente dirigida a clases modificadas.

## Validación
Aún no ejecutada. No se ha comprobado en dispositivo, con APK ni suite completa.

## Reanudación actual
- El informe de progreso existente sólo declara trabajo pendiente; se continúa desde ese estado sin descartar S1.
- Copia de seguridad del diff previo y de archivos modificados/no rastreados en stage-S1fix-before.patch y stage-S1fix-before/.
- SHA256 inicial de docs/SINGLE_SESSION.md: 68A6FB2318D1976349B1EA95C35D2CB7BC29DC2B069CB4CC02436BBFDED89ACE.

## Auditoría del estado parcial
- Se encontró un intento anterior más avanzado que el informe: barrera CompletableDeferred por filesDir, arranque asíncrono y eliminación de lectura legacy en invalidación. Se continúa y audita, sin descartar esos cambios.
- Diseño en curso: Activity suspende; stores síncronos sólo esperan fuera del hilo principal; servicio espera antes de sus locks; workers actuales esperan/reintentan y workers antiguos no acceden a datos nuevos.
- Inventario verificado por el escritor: thumbnails está dentro de delivered/<sesión>/thumbnails; checkpoints están dentro de crew-context. B9 construirá rutas literales independientes, incluyendo esos descendientes.
- B9 probará onCreate productivo con executor retenido de forma determinista, consumidor temprano suspendido, base corrupta preservada y cancelación de tokens.
- Compilación y pruebas aún pendientes; no se declara éxito.
## Reanudación 2026-10-24 (sesión actual)
- Releídos enteros juez, brief, informe S1 y última entrega. Detectado y conservado intento parcial del arreglo, ya con start asíncrono e invalidación sin lectura legacy.
- Un escritor continúa código y B9; una exploración de sólo lectura audita entradas Android y posibles bloqueos. El agente principal ejecutará build/tests y consolidará evidencias.
- `gentle-ai` sigue ausente del PATH; no se activa review mode ni se declara aprobación por recibos.
## Avance: cobertura de entradas
- Auditoría confirma espera antes de locks en admisión del servicio, provider Room y registro workspaces; Activity retrasa inicialización de consumidores. Workers de memoria descartan generación vieja y esperan en la actual; tareas/proactivo esperan o reintentan.
- Encontrado acceso directo pendiente: FileProvider de imágenes generadas. El escritor añade guardia del proveedor productivo para lectura/query/delete y comprueba getType.
- B9 de arranque será una clase pequeña `SingleSessionStartupTest` con looper principal pausado, Application productiva y Activity temprana; evita cambiar la regla de ejecución en worker de los otros B9.
- Pendientes build y pruebas; todavía no se declara validación en verde.
- Se detectó además orden de arranque Android: los proveedores/WorkManager pueden existir antes de onCreate. Se registra la barrera en attachBaseContext y se inicia el trabajo en onCreate para cubrir esa ventana sin bloquear main.
- La invalidación ya no consulta SQLite antigua; los tokens no publicados de receptores quedan neutralizados por guardas de generación. La notificación FGS nueva se distingue por generación y se conserva.
- Retirada auditada: se libera la barrera después de done; resumeRetirement sigue en worker, y el borrado usa retirementLock separado del lock del estado.
## Implementación lista; validación en curso
- Writer terminó barrera en attachBaseContext/onCreate, proveedor de imágenes productivo gateado, B9 startup determinista con Activity real y DB corrupta, inventario literal independiente; GeneratedImageStoreTest usa proveedor productivo.
- `git diff --check` limpio antes de compilar.
- En ejecución desde app: `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac --console=plain`. Log: stage-S1fix-compile.log.
- Primer build falló por firma Kotlin del nuevo proveedor: superclass openFile devuelve ParcelFileDescriptor nullable. Corregido el tipo; mismo comando en ejecución otra vez, log stage-S1fix-compile-2.log. No otros errores reportados por ese intento.
- Compilación Kotlin/Java PASS: `BUILD SUCCESSFUL in 49s`, 38 tareas, exit 0 (stage-S1fix-compile-2.log).
- Ejecutándose contratos + proveedor con comando exacto:
```powershell
.\gradlew.bat :app:testFullDebugUnitTest --tests com.jarvys.agent.SingleSessionResetTest --tests com.jarvys.agent.SingleSessionReadBoundariesTest --tests 'com.jarvys.agent.MainActivityWorkspaceRoutingTest.B10_startIntentNewChatAndRestoredPickerAlwaysUseCentral' --tests com.jarvys.agent.SingleSessionStartupTest --tests com.jarvys.agent.GeneratedImageStoreTest -x :app:prepareApkFactoryTemplate --console=plain --rerun
```
Log stage-S1fix-contracts.log. Exclusión APK mantenida.
## Primera tanda de pruebas
- 12 tests: 7 PASS, 5 FAIL. B9 fases/retirada y B11 pasan. XML conservados en stage-S1fix-contracts-attempt1/.
- Pendientes de corregir: fixture GeneratedImageStoreTest todavía busca ComponentName FileProvider antiguo; B10 prepara MemoryStore en main antes de completar arranque asíncrono; B9 productivo detecta reset detenido en created (excepción de worker pendiente de exponer/diagnosticar).
- Otros 2 fallos: GeneratedImageStoreTest.productionContextFilesDirMayHavePlatformSymlinkedAncestor y symlinksAtOrBelowGeneratedRootRemainRejected, ambos por privilegios de symlinks Windows; son parte de los fallos baseline ya acreditados, no se alterarán para ocultarlos.
- Segunda tanda (7 tests focales): 6 PASS; ya pasan B10 y proveedor. Sólo B9 startup falla: barrera completa sin excepción, pero estado leído sigue created. Se investiga persistencia AtomicFile del entorno SDK34/Windows; no se atribuye todavía a entorno sin evidencia. Log stage-S1fix-contracts-2.log y XML stage-S1fix-contracts-attempt2/.
- Regresión dirigida en ejecución (sin Startup mientras se diagnostica), log stage-S1fix-regression.log:
```powershell
.\gradlew.bat :app:testFullDebugUnitTest --tests com.jarvys.agent.AgentForegroundServiceMainThreadAdmissionTest --tests com.jarvys.agent.tasks.TaskWorkManagerTest --tests com.jarvys.agent.tasks.TaskManualRunWorkerTest --tests com.jarvys.agent.proactive.ProactiveWorkManagerTest --tests com.jarvys.agent.tasks.TaskPendingActionExecutionTest --tests 'com.jarvys.agent.chatdb.JarvysChatDatabaseM0Test.createsEveryDeclaredEntityTableAndEnforcesPragmaContract' --tests 'com.jarvys.agent.workspaces.WorkspaceRegistryTest.registryDoesNotMigrateLegacyFilesOrInventWorkOnOpen' --tests 'com.jarvys.agent.apkfactory.Factory*ApplicationStartupTest' -x :app:prepareApkFactoryTemplate --console=plain --rerun
```
## Diagnóstico y regresión
- AtomicFile API34 confirmado en bytecode local: finishWrite usa File.renameTo; en Windows no reemplaza archivo destino existente y sólo registra error, sin lanzarlo. Repro JShell: state=created, state.new=done, renameTo=false, state sigue created. B9 startup se alinea con API28 (los otros B9), sin modificar producción para el host. Persistencia API34 en Windows queda no validada.
- Regresión inicial: 40 tests, 18 PASS, 22 FAIL. XML stage-S1fix-regression-attempt1/.
- Cuatro fallos de fixtures afectados que se corrigen: servicio usa sesiones ordinarias ya normalizadas a Central; tres TaskManualRunWorkerTest construyen LocalRunStore en main antes de reset y requieren espera de fixture desde worker.
- Otros 18 resultados no prueban el arreglo: 17 Factory*ApplicationStartupTest fallan en setup por FactoryFileShareOsShadow.mkdir -> Files.setAttribute(unix:mode), no soportado Windows, ANTES de llamar Application; FactoryPhotoApplicationStartupTest no tiene métodos @Test ejecutables. Verificado diff vacío en esos archivos y shadow respecto HEAD; no se alteraron. No se hizo ejecución nueva de HEAD para estos 18; no confundir con los 22 baseline de S1 ya acreditados.
- Corregidos fixtures MainActivity/TaskManualRunWorker para esperar barrera antes de construir stores; servicio adapta AGENT/SYSTEM/USER a Central y selecciona mensaje por ID, conservando comprobaciones de admisión y no-ejecución SYSTEM.
- Reejecución integrada de contratos y regresión viable en Windows en curso, log stage-S1fix-selected-final.log. Incluye MainActivityWorkspaceRoutingTest completa; excluye repetir los fallos ambientales ya diagnosticados de Factory y symlinks. Todavía no hay resultado final.
- Revisión final detectó que Deferred puede propagar una excepción checked a Java; drainSession sólo capturaba RuntimeException. Se amplía a Exception y se canaliza como IllegalStateException por el fallo de admisión existente. Pendiente recompilar Java y repetir test servicio después de tanda integrada actual.
- Tanda integrada: 41 tests, 40 PASS. Único fallo B9 startup en assertion final: listAllConversationsSync oculta conversaciones sin mensajes mediante EXISTS; el reset, corrupción preservada, token cancelado y Activity admitida ya pasaban. Fixture ahora consulta directamente Central y verifica kind=central y message_count=0. XML stage-S1fix-selected-attempt1/.
- Compilación final en ejecución tras catch Exception del servicio: mismo comando Kotlin/Java, log stage-S1fix-compile-final.log. Pendiente última ejecución de contratos + proveedor + servicio.
