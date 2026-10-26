# S6 — contrato propuesto para admisión Linux y Coding

Estado: **slice Linux síncrono implementado localmente (§9), revisión estática en curso; pruebas no ejecutadas**.
Fecha: 2026-10-26. Base inspeccionada: `bbde2531` más cambios locales compartidos.
Referencias: `S6-tool-inventory.md`, `S6-admission-contract.md`, `docs/SINGLE_SESSION.md`.
Rutas de producción relativas a `app/app/src/full/java/com/jarvys/agent/`, salvo indicación.

## 1. Conclusión y alcance mínimo

No basta con cambiar `effectClass()` a WRITE. Estas herramientas poseen gates propios pero no se
identifican como connector/MCP/PolicyGatedTool; el fallback nativo del núcleo podría inventar ALLOW
antes de preguntar al gate. Necesitan override explícito `execute(..., EffectInvocation)` y cobertura
fail-closed hasta conectar todas sus fronteras. El recorder observa; no concede permisos.

Hay dos bloques diferentes:

1. **Linux síncrono**: setup/exec/uninstall pueden transportar la misma invocación hasta los seams
   existentes, agregando cobertura de preparación y recibos locales.
2. **Coding asíncrono**: `project_exec` devuelve un job antes de su lanzamiento. La API actual sella
   `EffectInvocation` y finaliza la evidencia cuando retorna la herramienta. Hace falta un contrato
   de handoff durable, no conservar a escondidas el mismo callback sellado ni convertir aceptación
   del job en finalización del comando. Este punto debe resolverse antes de declarar cobertura.

Esta propuesta no instala Linux, ejecuta comandos, cambia políticas, acepta licencias ni amplía scope.
No introduce límites nuevos de tiempo, tamaño o metadatos. Los límites existentes se conservan.

## 2. Mapa de autoridad y efectos reales

| Productor | Autoridad actual | Frontera real / advertencia |
|---|---|---|
| `flavor/LinuxTools.kt:LinuxSetupTool` | `maintenanceAuthorization(SETUP)`; DENY; ASK mediante UserDecision; targets rootfs/workspace y estado inicial fijados; `admitMaintenance` con STOP/surface | `installForProbeAdmitted`, `probeAdmitted`, `markProbeSuccessAdmitted`, `markProbeFailureAdmitted`. Instalador revalida después de esperar su lock y entre fases. `already_ready` ejecuta probe y actualiza estado: no es no-op puro. |
| `LinuxExecTool` | `authorization()`; ASK/ALLOW_ALWAYS con recibo exacto; `autonomy.admit` valida instalación, generation y superficie | Primer `admitCommand()` antes de `recordAutomaticExecution` **no prueba dispatch**: auditoría puede fallar y retornar sin comando. `execAdmitted` entrega callback a `ProotLauncher` inmediatamente antes de `ProcessBuilder.start`; hay efectos de preparación anteriores que deben cubrirse por separado. |
| `LinuxUninstallTool` | Política propia UNINSTALL; selección explícita keep/delete workspace incluso bajo ALLOW cuando falta argumento; snapshot de targets/estado | `withAdmittedUninstall` adquiere ownership, marca uninstallInProgress y cambia generation. El callback recibido valida esa generación nueva, no la anterior. `uninstallAdmitted` suspende launches, autoriza, termina procesos y elimina rootfs; workspace es una segunda fase opcional. |
| `flavor/CodingExecutionTools.kt:executeCommand` | `scope.require(WRITE)`, versión exacta, `available(EXEC/JOBS, token)`, runtime/toolchain preparado; `projectAuthorization` y ASK/ALLOW_ALWAYS exacto | `jobs.start` persiste LAUNCH_INTENT y despacha worker. Worker repite preflight; backend llama beforeLaunch; manager persiste RUNNING antes del proceso y mantiene writer lease hasta reap. Aceptado no equivale a lanzado ni terminado. |
| `CodingExecutionTools.jobOperation(cancel)` | Capacidad seleccionada + bot interactivo + scope vigente; `jobs.owned(..., allowRecoveredAccess=false)` exige owner exacto y scope/durable identity | No usa Coding ALLOW/ASK/DENY: la autoridad es cancelar el job propio vivo. `requestCancel` cambia CANCELLING, intenta persistir y cancela token; puede ser no-op para terminal/recovered. No convertir esta autoridad en una nueva aprobación de ejecución ni señalar jobs recuperados. |
| `linux_status`, `project_environment_status`, `project_jobs list/read/wait` | Superficie/owner/scope actuales | Status de proyecto no ejecuta probe. `linux_status` puede invocar `runtime.probe()` (proceso de inspección); la ruta usa preparación Linux común. Mantener explícita esta diferencia: no afirmar que ambos son lecturas puras sin procesos/archivos. Read/wait no vuelven a lanzar el job. |

### Preparación antes del launch

`linux/LinuxEnvironment.kt:execute` hace, antes del callback final:

- `ensureCertificates` para ejecución principal;
- creación del workspace cuando corresponde, raíz temporal y directorio temporal por proceso;
- intento de chmod de binarios nativos para ejecución principal;
- construcción del plan; posteriormente limpieza del temporal en finally.

`ProotLauncher.kt:LinuxProcessExecutor.execute` llama `beforeLaunch` bajo `launchLock`, justo antes de
`ProcessBuilder.start`. Ausencia de ese callback **no prueba ausencia de todo efecto** en la ruta.
Propuesta: distinguir `beforePreparationEffects` de `beforeLaunch` y preservar el guard final de launch.
Ambos reutilizan la misma identidad y snapshot; la primera admisión persistida es idempotente. Una
alternativa conservadora para el primer slice es admitir después de la auditoría y justo antes de entrar
al runtime, describiendo el efecto como preparación/ejecución solicitada, nunca como proceso lanzado.
No declarar cobertura de fronteras exactas mientras esa distinción no exista.

## 3. Snapshots y transporte propuestos

`linux/LinuxExecAutonomy.kt` ya tiene:

- `Authorization`: snapshot de política + generation + owner del entorno.
- `ProjectAuthorization`: snapshot Coding, posible principal Linux heredado, generation/owner y revision.
- `allowFromApproval` / `allowProjectFromApproval`: recibos exactos del botón persistente, no una lectura
  posterior de política. El segundo elimina la herencia solo por el cambio explícito aprobado.
- `admit`, `admitProject`, `admitMaintenance`: validación bajo STOP y lock de política.
- `withAdmittedUninstall`: ownership exclusivo de una nueva generación durante eliminación.

Agregar una proyección inmutable de evidencia desde esos objetos, sin exponer stateToken, command,
paths privados, entorno o credenciales. `EffectInvocation.Admission` debe identificar autoridad
`linux`/`coding`, operación, revision real y origin real; incluir generation e identidad de herencia
como hechos estructurados no privados si el contrato de persistencia lo requiere. No concatenar
comandos o contenido del proyecto en esos campos. El objeto autorizado original sigue siendo la
fuente de validación; la proyección no lo reemplaza.

Secuencia para una frontera síncrona:

1. Intent y binding exacto ya persistidos por recorder.
2. Gate/policy/scope/STOP actuales validan y producen decisión: ALLOW, ASK_APPROVED, ASK_PENDING o DENY.
3. Fuera de locks de política/STOP: `invocation.admit(snapshot)` persiste started o falla cerrado.
4. Revalidación final existente de autoridad/scope/STOP; después, efecto.
5. El productor local emite receipt propio sobre resultado tipado, no interpreta texto localizado.

DENIED humano se distingue de ASK pendiente mediante decisión tipada, igual que conectores/MCP.
ALLOW_ALWAYS solo puede usar el recibo guardado por su callback. Los cambios posteriores de snapshot
se rechazan; no mezclar admisiones distintas en una invocación compuesta.

Para uninstall, no reutilizar `admitMaintenance` con la generación anterior dentro del callback de
eliminación. Conservar `admitPolicy` que entrega `withAdmittedUninstall`. Separar la reserva de ownership
(interna) de terminación de procesos/eliminaciones reales, sin perder la generación que posee la operación.

## 4. Recibos: hechos disponibles y significado permitido

Hoy las herramientas devuelven factories legacy `success`/`failure`, sin EffectInvocation receipt.
Hay tipos de dominio útiles: `LinuxInstallState`, `LinuxProbeResult`, `LinuxExecResult`,
`CodingJobManager.Snapshot/State/RecoveryEvidence`. La proyección JSON no debe convertirse en autoridad.

| Hecho local | Resultado propuesto |
|---|---|
| Rechazo/STOP antes de cualquier preparación o dispatch, probado por frontera instrumentada | NOT_EXECUTED; decisión exacta si existe. |
| Setup alcanza READY, probe listo y persistencia final terminada | OK del setup observado, no garantía de futuros comandos. |
| Setup devuelve FAILED tipado después de efectos parciales | ERROR conocido; partial no se borra. Excepción/cancelación con alcance desconocido -> UNCERTAIN. |
| `already_ready` tras probe/mark | Resultado tipado del chequeo/mantenimiento realizado; nunca NOT_EXECUTED por comparar texto. |
| LinuxExecResult exitCode=0, no cancelled/timedOut, ejecución acabada | OK de finalización del proceso; **no** prueba que tests/build/objetivo del usuario hayan pasado. |
| Exit no cero conocido | ERROR; puede haber efectos parciales. |
| Timeout/cancelación/excepción posterior a posible preparación/launch | UNCERTAIN respecto del efecto total. No inferir rollback. |
| Uninstall termina ambas fases solicitadas | OK de eliminación solicitada. Rootfs eliminado pero workspace pendiente/falla -> ERROR/UNCERTAIN según hecho local, nunca NOT_EXECUTED. |
| Job LAUNCH_INTENT/RUNNING o CANCELLING | No es completitud. Receipt administrativo de aceptación/handoff, no OK de comando ni finalización de cancelación. |
| Job terminal SUCCEEDED con estado final durable | OK de proceso únicamente, ligado al job original y no al poll. |
| Job FAILED conocido | ERROR; logs/fuentes necesarios para cualquier conclusión más fuerte. |
| Journal corrupto, INTERRUPTED, UNCERTAIN, terminal no persistido, resultado perdido | UNCERTAIN y no replay. |
| Cancelar job ya terminal bajo lock | NOT_EXECUTED de esta cancelación, sin tocar evidencia original. |

`RootfsInstaller.install` permite limpieza/bookkeeping de una fase ya iniciada tras fallo o revocación.
Eso no es una nueva autorización de fase; no esconder esos efectos parciales. Un fallo del writer de
admisión inicial debe ocurrir antes de directorios/descarga/publicación de fallo, como ya pretende su
admisión inicial fuera del try de instalación.

## 5. Handoff asíncrono Coding: decisión necesaria antes de implementar

No pasar `EffectInvocation` tal cual al worker: `CoreToolRegistry.invoke` llama `seal()` y finaliza el
recorder al retornar. Con `jobs.start` asíncrono, un callback tardío fallaría o encontraría evidencia ya
terminal. Tampoco emitir `Outcome.DEFERRED` terminal como sustituto de un job en ejecución: ese outcome
se usa para una acción aún no despachada esperando disponibilidad/aprobación.

Alternativa A — transferencia de propiedad durable, pendiente de decisión del núcleo:

- Antes del enqueue, crear vínculo durable entre identidad de invocación existente y jobId exacto,
  conversation/batch/call/run/proceso + owner/durable project identity. Nunca buscar el último job/call.
- Retornar un recibo administrativo **JOB_ACCEPTED** con handle de evidencia pendiente. No cambiar el
  significado no bloqueante actual ni esperar arbitrariamente a la terminación.
- Transferir a un único productor worker un handle inmutable de persistencia; la facade de llamada se
  sella, pero no se marca terminal la evidencia transferida. El handle **no concede autoridad**: conserva
  autorización/scope/STOP y revalida en cada frontera de runtime.
- Worker persiste started antes del primer efecto cubierto; after launch/finish registra resultado por
  CAS de esa identidad, una sola vez. Completion posterior no modifica episodios ajenos ni terminales.
- `reconcileEpisodeRun(runId)` actual cierra intent/started al terminar el run. Debe reconocer el
  ownership durable del job aún activo y no cerrarlo por el mero retorno del run; no basta con cambiar
  el seal de la facade. La reconciliación por proceso sí puede declarar incertidumbre tras muerte real.
- Mantener writer lease de proyecto hasta cleanup, job journal fsync y reglas de recuperación actuales.
  Tras muerte del proceso: incertidumbre sin relanzar. Lecturas de journals viejos no reabren evidencia.
- El callback `completion` actual está envuelto en `runCatching`; no puede ser la única garantía durable
  de finalización. Reconciliar desde journal terminal tipado y vínculo exacto en startup si falla la
  escritura de evidencia, sin ejecutar nuevamente el comando.

Alternativa B — evidencia separada de submission y de ejecución/terminal:

- El episodio de la llamada sólo acredita la aceptación durable de una solicitud (`job_submission`),
  con semántica explícita distinta de comando lanzado o completado. No usar un OK indiferenciado de
  ejecución para ese enqueue.
- El worker es productor de evidencia propia ligada al jobId exacto y a la solicitud causal; persiste
  su propio intent/started en la frontera real y su resultado terminal desde el journal. La autoridad
  sigue siendo el snapshot autorizado, scope/owner y STOP; una fila de submission no autoriza ejecución.
- Narración, búsqueda y retención deben distinguir ambas clases y evitar doble conteo del mismo
  comando. El retorno del run puede cerrar la solicitud sin cerrar una ejecución activa separada.
- Recovery reconstituye hechos comprobados desde vínculos exactos, sin crear completitud a partir de
  un job aceptado ni reejecutar el comando. El fallo del callback final necesita reconciliación durable.

**Decisión de continuación:** se elige la alternativa B (submission y ejecución terminal separadas)
como diseño interno para el siguiente bloque, sin implementación asíncrona en este slice. Requiere
concretar ownership, estados, causalidad, reconciliación y compatibilidad antes de producir cambios. No bloquear indefinidamente `jobs.start`
para evitar diseñarlas ni introducir un tiempo de espera arbitrario.

Esta extensión necesita ownership explícito de núcleo/recorder/repository y schema si se decide un
vínculo nuevo. **La alternativa B está elegida como diseño, sin implementación asíncrona todavía.** El parche acotado §9 cubre
Linux síncrono y dejar Coding indicado como gap, sin promoverlo a native ALLOW ni declarar S6 completo.

## 6. Cancelación de job: contrato separado

`CodingJobManager.cancel` resuelve job mediante owner exacto, scope y conversación activa; los journals
recuperados nunca son handles de proceso. Extender con un callback de admisión scoped inmediatamente
antes de `requestCancel` que emita una decisión de autoridad `coding_job_owner`, no un snapshot de
`project_exec` que no gobierna esta acción. ALLOW, si se usa el enum existente, significa únicamente
este contrato de owner válido; no política nativa implícita.

Necesita resultado local discriminado (`ALREADY_TERMINAL`, `CANCEL_REQUESTED`, `TERMINAL_CONFIRMED`,
`PERSISTENCE_UNCERTAIN`) en vez de deducirlo de JSON o de una lista vacía. Mantener la comprobación
atómica con el cambio de estado; callback durable fuera de job.lock, seguido de revalidación bajo lock.
Si el job termina durante esa ventana, no señalarlo y registrar el no-op explícito conservadoramente
sin reabrir el comando. STOP/cleanup automático no debe producir un segundo episodio de herramienta.

`requestCancel` hoy ignora fallos de persistir CANCELLING; por eso el snapshot devuelto no acredita por
sí solo un recibo durable de cancelación. No afirmar cleanup terminado hasta que el proceso se reap y
la evidencia terminal esté persistida. No agregar una nueva tarjeta de aprobación a una cancelación
que el contrato existente permite al propietario.

## 7. Locks, hilos, scope y retries

- Nunca Room dentro de lock de política/STOP ni callbacks UI. Proyectar snapshot antes y persistir fuera;
  revalidar después sin sustituir las comprobaciones actuales.
- Installer callbacks suceden bajo su lock; process beforeLaunch bajo launchLock; Coding añade job.lock,
  scope lease y conversation guard. Dibujar/verificar orden antes de mover callbacks: el writer Room no
  debe volver a entrar a runtime/installer/jobs ni esperar al hilo que aguarda su resultado.
- No eliminar launchLock/suspensión de launches para «arreglar» un deadlock: protege uninstall frente a
  procesos nuevos. Evitar envolver UI, red, descargas o proceso completo en transacciones Room.
- Contexto/handle explícito cruzando executor; no singleton mutable ni ThreadLocal único.
- Mantener capability selection, bot interactivo, owner por generación, project WRITE lease,
  expected_scope_version, validated cwd/mount, environment generation y principal heredado.
- No reintentos nuevos. La captura no repite setup/exec/cancel, no relanza LAUNCH_INTENT recuperados y no
  transforma salida truncada o timeout en éxito. Las fases/revalidaciones existentes pueden repetir
  admisión idempotente del mismo snapshot, no duplicar evidencia.

## 8. Archivos y pruebas de un futuro parche

Mapa inicial de archivos candidatos; el slice §9 modifica sólo LinuxTools y su fixture, no runtime ni locks:

- `flavor/LinuxTools.kt`, `linux/LinuxExecAutonomy.kt`: clasificación explícita, snapshots de evidencia,
  transporte, callbacks y outcomes síncronos.
- `linux/LinuxRuntime.kt`, `linux/LinuxEnvironment.kt`, `linux/RootfsInstaller.kt`,
  `linux/ProotLauncher.kt`: cobertura preparación/launch y preservación de locks/cleanup.
- `flavor/CodingExecutionTools.kt`, `linux/CodingJobManager.kt`: clasificación dependiente de action,
  cancelación tipada y handoff pendiente de contrato aprobado.
- `flavor/FlavorLinuxTools.kt`: comprobar binding owner/scope/completion y wrappers; Play sigue vacío.
- Núcleo/recorder/repository: solo si se aprueba handoff asíncrono, con ownership separado.

Extender fixtures existentes `LinuxToolsTest`, `CodingExecutionToolsTest`, `LinuxExecAutonomyTest`,
`CodingJobRecoveryTest` y tests runtime/installer/launcher:

1. ASK aprobado/rechazado, ALLOW, DENY, allowAlways exacto, herencia Coding y ABA/revocación de política.
2. Recorder+Room reales con gateway/backend fake: intent durante gate; started antes de preparación y
   proceso; writer fallido impide efectos; nunca false ALLOW.
3. Auditoría automática falla antes runtime: no started de comando. Preparación hecha pero launch falla:
   no NOT_EXECUTED falso. Ready/probe/partial install/uninstall con outcomes tipados.
4. Revocación tras callback durable y antes de efecto; STOP al esperar locks; ninguna nueva ejecución.
5. Executor demorado: aceptación no terminal; callback fuera del hilo original; closure sellada no
   reutilizada. Muerte entre journal/started/launch/terminal/episode update; recovery no replay.
6. Cancel owner ajeno, scope antiguo, recovered, terminal, carrera con finish, persist failure y reap;
   no duplicar episodios de cancelación automática.
7. Status/read/wait no atribuyen ejecución nueva; salida del proceso es untrusted y exit0 no certifica
   resultados de pruebas o archivos. Preservar metadatos y filtros de invocación.
8. Mutantes temporales: omitir admisión de preparación/launch, tomar policy actual en lugar del recibo,
   marcar job aceptado OK o reabrir terminal deben romper las pruebas respectivas.

**Validación realizada:** lectura de fuente y referencias, `git diff --check` del documento. Sin Gradle,
SDK, proceso Linux, efecto externo, acceso remoto ni pruebas ejecutadas. No implica PASS de S6.


## 9. Slice síncrono autorizado: frontera conservadora elegida

Implementación local en `LinuxTools.kt` y pruebas focales en `LinuxToolsTest.kt` (no ejecutadas):

- `linux_setup`, `linux_exec`, `linux_uninstall` declaran WRITE y transportan el contexto explícito de
  invocación. La decisión procede del snapshot real Linux (connector, operación, revisión y origen),
  nunca del ALLOW por defecto del adaptador CoreTool. Los argumentos, paths y command no se proyectan.
- Exec: tras auditoría automática exitosa, validación y STOP, antes de entrar `execAdmitted`, se llama
  autoridad → writer durable → autoridad. Esto incluye certificados, mkdir/temp/chmod y proceso.
  `started` acredita admisión de preparación, **no prueba que ProcessBuilder haya arrancado**.
- Setup: autoridad → writer → autoridad antes de install/probe, también `already_ready`. Callbacks de
  installer/probe/mark continúan siendo autoridad-only; no Room dentro del lock del installer.
- Uninstall: dentro de `remove` de `withAdmittedUninstall`, que ya está fuera del policyLock, se llama
  callback de propietario nuevo → writer → callback de propietario nuevo. No se usa la generación vieja
  de `admitMaintenance`. Las fases delete rootfs/workspace siguen con su validación original.
- BeforeLaunch sigue siendo exclusivamente autoridad, sin Room bajo launchLock. No se modifica runtime,
  policyLock, STOP, scope, generación, permisos, auditoría ni cleanup. No hay retry nuevo.
- Recibos locales: ready/probe exitoso y uninstall completado => OK de esa operación; exec exit0 sin
  timeout/cancel => OK de terminación de proceso, no de pruebas ni intención del usuario. Exit no cero
  => ERROR; timeout/cancel o fallo posterior a admisión => UNCERTAIN. No deducir efectos del texto.
- `linux_status` conserva su comportamiento READ heredado y declara cobertura incompleta: **gap aún
  abierto**, no afirmación de ausencia de efectos. `runtime.probe()` prepara archivos y ejecuta PRoot;
  sólo tiene gate de superficie/token inicial, sin política exec. Está pendiente decidir contrato nativo
  específico de status/probe; no se hereda permiso exec ni se elimina el probe en este slice. La revisión
  detecta además `shell -lc`, HOME root y PATH guest mutable en el probe: el texto fijo del comando
  no acredita lectura pura, pues perfiles login/binarios modificables pueden producir otros efectos.

Pruebas añadidas: writer falla antes de entrar preparación exec; auditoría fallida sin admisión;
revocación tras writer impide preparación (resultado conservador uncertain); setup ya instalado admite
antes de probe; writer fallido impide instalación/probe; uninstall usa propietario de nueva generación y
writer fallido no entra runtime; ASK/deny exactos con exit no cero y uninstall parcial tras revocación
(10 pruebas nuevas, incluidas selección negativa explícita DENY sin cambiar policy y dismissal
que permanece ASK_PENDING). Validación únicamente estática/whitespace; SDK y suite siguen pendientes.
