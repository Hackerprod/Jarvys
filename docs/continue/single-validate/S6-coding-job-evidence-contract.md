# S6 — contrato B: submission y ejecución durable de Coding separadas

Estado: **contrato revisado; metadatos aislados validados (§12), productor integrado pendiente (§11), extracción IO y claim local validados focalmente (§§13–14)**.
Fecha: 2026-10-26. Lectura: árbol de trabajo actual de `continue/s6-cloud`.
Normativa: `docs/SINGLE_SESSION.md` §§7.3–7.5, B3/B4; `S6-admission-contract.md`;
`S6-linux-coding-admission-contract.md` §5, alternativa B.

## 1. Decisión propuesta y límites

Mantener `project_exec` no bloqueante. Su retorno prueba **aceptación durable de una solicitud**,
no lanzamiento, terminación, éxito del comando ni cumplimiento del objetivo del usuario.
La ejecución tiene un productor y una identidad propios por `jobId`; su duración no pertenece al
run que presentó la solicitud. No se transporta al worker una `EffectInvocation` que el registro sellará.

Una solicitud y su ejecución pueden aparecer en el **mismo episodio de la petición original**,
pero son dos clases distintas de evidencia, no dos comandos. `project_jobs read/list/wait` solo
leen el job existente; no crean una nueva ejecución ni trasladan su éxito al run del poll.
La cancelación es otra llamada/decisión causal, no reemplaza al job ni acredita que se terminó.

Este documento no cambia permisos, scopes, gates, políticas, timeouts, presupuestos ni retención de
contenido privado. No instala/ejecuta servicios, comandos o SDK. La aprobación del diseño no es
prueba de ejecución ni PASS de S6. Los otros productores de S6 siguen sujetos a B3/B4/B5.

## 2. Contraste con el código real

Rutas Full bajo `app/app/src/full/java/com/jarvys/agent/`:

- `flavor/CodingExecutionTools.kt:executeCommand`: valida argumentos, scope WRITE, versión,
  capacidad activa, toolchain y autorización Coding. ASK/ALLOW_ALWAYS conservan su recibo exacto.
  `jobs.start(..., ::admitLaunch) { completed(...) }` retorna un `Snapshot` y un `CoreToolResult.success`
  cuyo texto indica job aceptado. Ese `success` no es un recibo de ejecución.
- `linux/CodingJobManager.kt:start`: obtiene writer lease, genera UUID, persiste `LAUNCH_INTENT`,
  registra cancelación y despacha el executor. El worker puede correr/terminar antes del retorno.
  Hoy el journal **no** guarda source message, batch/call/run causal ni enlace a evidencia Room.
- `run`: valida antes del backend. En `beforeLaunch`, bajo `job.lock`, persiste `RUNNING` y después
  pone `launchAttempted=true`; todavía revalida antes de que `ProcessBuilder.start` ocurra.
  Por tanto `RUNNING`/`startedAt` prueban una intención de lanzamiento persistida, no por sí solos un
  proceso creado. Los efectos de preparación Linux anteriores a ese callback requieren la frontera
  adicional descrita en el contrato Linux (§2).
- `finish`: mantiene el writer lease hasta reconciliar versión y cleanup. Si falla `persist`, cambia
  **solo el Snapshot en memoria** a `UNCERTAIN`; el journal puede seguir en RUNNING. Finalmente llama
  `runCatching { completion(snapshot) }`. Ni la presencia del callback ni su Snapshot prueban un terminal
  durable; tampoco su ausencia prueba que no terminó el comando.
- `persist`: fichero temporal, `fd.sync()`, rename y sincronización del directorio; schemas actuales
  1/2. El journal conserva identidad de proyecto, owner, conversación y estado. Es privado y está fuera
  del proyecto editable. No es una transacción atómica conjunta con Room.
- `restoreRecord`: valida ruta/UUID/schema/identidad/log; terminales se recuperan, no terminales se
  presentan como INTERRUPTED/UNCERTAIN, y **nunca se relanzan ni se señalizan procesos restaurados**.
  `reconcile(scope)` puede actualizar la versión del proyecto bajo writer lease; esto no es reejecución.
- `CodingJobConversationGuard`: impide acceso al journal de una conversación eliminada. La proyección
  de memoria debe respetar también este guard y no resucitar un episodio olvidado/eliminado.

Rutas Main:

- `CoreToolRegistry.invoke` sella el contexto y llama `EpisodeRecorder.afterDispatch` al retornar.
- `EpisodeRecorder` obtiene el batch exacto mediante `committedCall(callId)` y escribe evidencia por
  conversación/batch/call/run. Esa identidad debe conservarse, no sustituirse por un lookup del último job.
- `PersonalAgentRepository.reconcileEpisodeRun` cierra intent/started por `run_id`; el reconciliador de
  procesos hace lo propio por proceso ajeno. Ambos deben distinguir explícitamente los jobs durables.
- La cola de narración hoy reconoce `tool_call`/`crew_result` OK. La nueva evidencia de job necesita
  inclusión explícita; no basta con agregar filas y asumir que ya serán narradas.

## 3. Identidades y esquema propuestos

### 3.1 Origen inmutable

`JobEvidenceOrigin` se construye localmente desde el checkpoint comprometido, antes de invocar
el productor. Campos: storage generation; conversación origen; source message; batch; call;
run causal; proceso causal; nombre de herramienta. Para tareas: task/occurrence/attempt cuando aplique,
con el mismo contrato de binding exacto, sin adivinar campos a partir de texto.

`jobId` lo sigue asignando **CodingJobManager**, una sola vez. El journal preserva además los campos
existentes owner, scopeId y durableIdentity. El binding es la tupla `(storageGeneration, jobId)`;
la atribución exige que el origen completo coincida. Un call distinto no puede adoptar el job.
Ningún campo de origen contiene comando, argumentos, rutas privadas, stdout/stderr o credenciales.
Los campos privados ya existentes del journal no se copian a `facts_json`.

### 3.2 Journal schema 3

Añadir, sin alterar el significado de los estados de UI actuales:

- `evidenceOrigin` completo e inmutable, `submissionId`, `evidenceMode = ENABLED | DISABLED`;
- `ownerProcessId` (UUID de instancia, no solo PID) y `managerInstanceId`, también en la reserva Room;
- `journalRevision` monotónica por job y `effectPhase` tipada;
- snapshot de autorización real usado para admitir preparación/ejecución, sin secretos;
- `submissionCommitted` y resultado terminal tipado dentro de la revisión persistida.

`persist` devuelve un `DurableJobCommit` interno **solo después** de fsync/rename/fsync de directorio.
Un commit identifica job, revisión, origen y estado ya persistidos; no se construye desde JSON de una
herramienta ni desde un Snapshot mutable. La recuperación produce el equivalente solo al validar
el fichero completo y su identidad. `finish` no publica un commit terminal cuando persistir falló.

Schemas 1/2 se siguen leyendo para UI/recuperación existente. Sin origen exacto no se crean episodios
atribuidos a un mensaje mediante búsquedas por nombre, comando, owner reciente o última llamada.
El diagnóstico de legado conserva incertidumbre y no habilita replay.

### 3.3 Room: relación explícita, no JSON usado como clave

Nueva tabla propuesta `coding_job_evidence_links` (migration por fijar durante implementación):

- PK `(storage_generation, submission_id)`, UUID asignado por el recorder antes de los gates;
- `job_id` nullable hasta que el manager lo asigna, con índice UNIQUE parcial
  `(storage_generation, job_id) WHERE job_id IS NOT NULL`;
- origen completo: conversación, source message, batch/call, `origin_run_id`, `origin_process_id`;
- identidad del owner/scope durable necesaria para comprobar binding, sin copiar projectRoot;
- `entry_id`, `submission_ordinal`, `execution_ordinal` con unicidad por job/rol;
- `submission_state`: RESERVED / ACCEPTED / REJECTED / UNCERTAIN;
- `owner_process_id`, `manager_instance_id`, `job_process_id`, revisión de journal proyectada,
  estado/phase observado, terminal proyectado;
- estado de proyección y exclusión/tombstone para memoria desactivada o entrada retirada.

La reserva existe con `job_id=NULL`. El binding hace CAS `job_id IS NULL -> UUID` sobre submissionId y
origen exactos; reintentar el mismo UUID es idempotente, otro UUID se rechaza. Tras binding, toda operación
del worker exige ambos IDs y el índice único impide compartir un job entre reservas.
Una invocación `project_exec` admite como máximo un job: constraint único sobre el origen completo
con call/batch, con NULLs tratados explícitamente para tareas. Una colisión con otro origen es error
fail-closed, no upsert silencioso. Un retry de **proyección** es idempotente; no es un retry de ejecución.

Filas `memory_evidence`:

| Rol | `ref_kind` / `ref_id` | Semántica |
|---|---|---|
| Solicitud | `coding_job_submission` / jobId; hasta asignarlo, ID exacto de invocation en el link reservado | Hecho administrativo; `outcome=NULL`, estado tipado en link/facts. ACCEPTED **no** es `ok` de comando. |
| Ejecución | `coding_job_execution` / jobId | Un único intent→started→terminal normalizado por job. `tool_name=project_exec`, `classification=WRITE`. |

El vínculo guarda el run **causal**. La fila de ejecución no usa el `run_id` de la llamada como dueño
de su ciclo de vida: `run_id=NULL`, owner durable en el link. Conserva el proceso real del job para
recovery. No se oculta un job colgante simplemente borrando runId: la tabla y reconciliador de jobs
son obligatorios y comprobados por tests. El schema existente permite outcome nulo, pero los nuevos
métodos deben validar esta semántica por rol; no ampliar los outcomes externos con “accepted”.

## 4. Protocolo de submission: dos almacenes, sin transacción distribuida ficticia

1. El registro/recorder asigna submissionId y prepara el origen exacto y una reserva con jobId nulo
   antes de gates/enqueue.
   No genera `started` del comando. Una denegación local se registra como submission rechazada;
   no produce fila de ejecución exitosa ni espera aprobación nueva inventada por el recorder.
2. Se ejecutan los gates existentes, incluyendo versión/scope/STOP y recibos ASK/ALLOW_ALWAYS.
   Aceptar el job no sustituye las revalidaciones posteriores del worker.
3. `CodingJobManager.start` obtiene el writer lease y asigna UUID como hoy. Un hook local de binding
   enlaza ese UUID con la reserva Room **antes** de persistir el primer journal. No se expone un callback
   que ejecute el comando ni un token de autoridad nuevo.
4. Se persiste journal schema3 LAUNCH_INTENT con origen. Un commit durable permite una transacción Room
   que marca submission ACCEPTED y crea la **única** fila `coding_job_execution(intent)` pendiente.
   Si la escritura Room falla, no se entrega al executor. Se registra fallo conservador en journal cuando
   sea posible y se libera lease. Un journal aceptado sin worker nunca autoriza lanzarlo al recuperar.
5. Solo tras esos compromisos se registra cancelación/enqueue, manteniendo la comprobación STOP.
   El orden exacto debe impedir un callback de cancelación reentrante que publique un estado contradictorio.
6. El retorno transporta `JobSubmissionReceipt(jobId, committedRevision, origin)` ligado al owner de la
   invocación. Si el worker ya terminó, sigue siendo recibo de submission; su terminal lo proyecta el job.

Fallo de enqueue después de aceptación: submission sigue describiendo que la solicitud se registró;
la ejecución se cierra con NOT_EXECUTED si el manager prueba que el worker nunca comenzó. Si la API del
executor permite ejecutar y luego lanzar excepción, no basta una excepción de enqueue para afirmar eso:
se necesita claim del worker y se conserva UNCERTAIN ante ambigüedad. No crear un segundo job como retry.

Los hooks Room operan fuera de `job.lock`, locks de STOP/política, `CodingJobConversationGuard` y
`ProotLauncher.launchLock`. El writer lease de proyecto puede seguir retenido como hoy. Ninguna
transacción Room abarca UI, red, ejecución ni espera de finalización de un job.

## 5. Productor worker y frontera fail-closed

El worker recibe un `JobEvidenceHandle` inmutable por job/origen, no la `EffectInvocation` de submission.
Es un identificador de persistencia, **no** una concesión de autoridad. Un claim durable/in-memory
coherente del worker impide dos dispatches incluso ante un executor de pruebas reentrante; tras restart
el manager conserva la prohibición actual de relanzar journals.

Antes de cualquier efecto de preparación Linux:

1. Revalidar los gates existentes y obtener el snapshot de autorización exacto.
2. Persistir journal de intención de efecto con phase `PREPARATION_INTENT` y ese snapshot.
3. Proyectar `coding_job_execution.started` y autorización por CAS de job/origen, o fallar cerrado.
4. Revalidar scope/lease/STOP/política de nuevo y entrar a la preparación real.

El backend necesita un seam `beforePreparationEffects` además de `beforeLaunch`: certificados,
temporales y cambios de modo ocurren antes del último callback actual. Instrumentar solo beforeLaunch
no satisface este contrato. Las revalidaciones finales dentro del launch lock se conservan; no se
introduce Room bajo ese lock. El callback de launch persiste el estado RUNNING como hoy y verifica el
mismo binding; se describe como **launch intent**, no como PID observado.

Admisiones repetidas del mismo job/snapshot son idempotentes y no duplican evidencia. Si cambia la
política/snapshot, no fusionar decisiones en una autorización ficticia: rechazar el dispatch adicional
con incertidumbre conservadora si ya hubo preparación. Ningún error borra el hecho de admisión previa.

## 6. Terminal durable y reconciliación

`finish` conserva writer lease hasta reap/cleanup y reconciliación de versión. Después de persistir un
terminal produce `DurableJobCommit`; fuera de locks solicita su proyección a Room. Si falla la
proyección, el terminal permanece en journal y un reconciliador vuelve a **leer/proyectar**, nunca ejecutar.
No depender exclusivamente del callback `completion`, actualmente envuelto en `runCatching`.

| Evidencia tipada persistida | Resultado de ejecución |
|---|---|
| SUCCEEDED, exitCode 0, sin cancelación/timeout/incertidumbre | `ok` del proceso terminado, no “build/tests/objetivo aprobado”. |
| FAILED con salida no cero conocida | `error`; no implica ausencia de modificaciones parciales. |
| NOT_STARTED y prueba completa de ausencia de preparación/dispatch | `not_executed`. |
| NOT_STARTED sin cobertura de preparación, CANCELLED/TIMED_OUT después de posible efecto | `uncertain`. |
| INTERRUPTED/UNCERTAIN, journal ilegible o terminal no persistido | `uncertain`; nunca replay. |
| LAUNCH_INTENT/RUNNING/CANCELLING de owner vivo | Seguir pendiente; nunca OK ni DEFERRED por ser asíncrono. |

El log faltante/incompleto se marca como fuente no disponible. Un terminal auténtico de proceso no
prueba el contenido del log ni el éxito de un objetivo semántico. Un journal corrupto no es un terminal
auténtico aunque quede una fila de `tool_results` o un texto de “éxito”.

### Liveness y concurrencia de creación/recovery

El journal schema3 y la reserva contienen el ownerProcessId/managerInstanceId que poseen la operación.
“No es mi UUID” no prueba que otro proceso esté muerto. El manager mantiene un lease de liveness
interproceso (lock de fichero privado por instancia), además de su mapa local
de submissions/jobs activos. Recovery ajeno solo declara owner muerto tras adquirir ese lease en
exclusiva; PID aislado, timeout, ausencia de `jobs[id]` en otro manager o retraso de IO no son prueba.
Esto no permite enviar señales ni recuperar autoridad sobre el proceso: solo serializa reconciliación.
La vida del lease cubre todos los productores, preparaciones, workers y procesos hijos hasta reap y
terminal/cleanup, o muerte real del owner. `manager.close()` no puede liberarlo si quedan efectos vivos;
no se hace unlink/recreación del lockfile mientras un owner lo retiene (evitar dos inodos con locks
independientes). El cierre ordenado espera/coordina los mecanismos actuales, sin añadir señales nuevas.
El orden de adquisición debe evitar cualquier espera de Room bajo ese lock.

Un escaneo concurrente con reserva/binding/persist/enqueue ve un owner vivo y mantiene pending. En el
mismo proceso, la transición de creación pertenece a su submissionId hasta enqueue o fallo confirmado;
se consulta ese estado bajo un lock corto y se proyecta fuera del lock. Si no se obtiene una prueba
concluyente de terminal/fallo, el reconciliador no fabrica un cierre. Un fallo persistente se reporta;
una repetición de IO/proyección no vuelve a llamar al executor. Un commit terminal válido sí puede
proyectarse aun con owner vivo, porque su revisión terminal es inmutable.

### Orden de recovery

- Reconciliar journal schema3 y links por identidad exacta antes de cerrar filas de jobs por proceso.
- Terminal durable válido: proyectar por CAS una sola vez. Revisión repetida no hace nada; revisión vieja
  no pisa una más nueva. Los terminales son inmutables; un commit contradictorio se reporta como conflicto,
  no sustituye silenciosamente un OK por otro resultado ni reabre el job.
- Journal no terminal de proceso demostrado muerto mediante el lease: proyectar UNCERTAIN sin ejecución. Incluso si startedAt falta,
  no inferir ausencia de preparaciones solo a partir del schema viejo.
- Reserva/link sin journal válido: mientras el owner está vivo o la creación está en curso, mantener
  pending; nunca enqueue/reconstruir comando. Tras muerte comprobada, submission UNCERTAIN y ejecución
  pendiente, si existe, UNCERTAIN. Con prueba durable de rechazo antes de enqueue puede ser NOT_EXECUTED.
- Journal válido sin proyección: reconstruir únicamente el vínculo ya contenido en ese journal; verificar
  generación, conversación activa, source/batch/call comprometidos y tombstones antes de escribir.
- Journal ilegible + link existente: con owner vivo se reporta y reintenta lectura, sin cerrar un job
  potencialmente activo. Tras muerte comprobada, cerrar incertidumbre de ese job exacto y preservar diagnóstico privado.
  Ilegible sin binding confiable: diagnóstico sin atribuirlo a una petición inventada.
- `reconcileEpisodeRun(originRunId)` cierra submissions no resueltas según su link, **no** ejecuciones de
  jobs. `reconcileForeignEpisodeProcesses` excluye explícitamente `coding_job_execution` y delega al
  reconciliador de jobs. Los jobs no quedan excluidos del recovery, solo de la regla equivocada de run.

Arranque, cierre de run y finalización de job invocan el mismo reconciliador idempotente; también el
worker de mantenimiento puede reparar proyecciones sin requerir el chat abierto. No depende de que el
usuario haga poll. Si todos los almacenes fallan, no se afirma evidencia durable: se conserva error
operativo y se evita nuevo efecto; al recuperar se usa lo efectivamente persistido.

## 7. Privacidad, memoria desactivada, narración y doble conteo

`evidenceMode` se fija en submission según la preferencia vigente. Con memoria desactivada no se crean
links/episodios de memoria: el journal operativo conserva su ciclo de vida y la ejecución sigue sus
permisos existentes. No activar backfill retrospectivo al habilitar memoria. Si se desactiva después,
no guardar nuevas narraciones; la política de reparación de filas ya existentes debe seguir la misma
regla de memoria del repositorio, sin usar esta extensión para revivir memoria eliminada.

Fuentes: puntero al resultado de submission exacto y `coding-job:<id>/log`; expansión por API de lectura
owner/scope existente y sus presupuestos/redacción. Nunca copiar comando, cwd, projectRoot o logs a facts.
La relación origin/job no concede a otro bot acceso a logs recuperados: mantiene `bindRecoveredOwners`
y las comprobaciones de identidad durable ya existentes.

Narrador/search:

- `coding_job_submission` se etiqueta como aceptación administrativa y no cuenta como comando ejecutado.
- `coding_job_execution` es la unidad de conteo de proceso: DISTINCT jobId, no número de polls, callbacks,
  fases de preparación ni revisiones de journal.
- Un episodio con job pendiente no puede narrarse como completado. Al terminal, la fila de ejecución
  invalida el snapshot de narración por la CAS existente y vuelve elegible el episodio conforme al outcome.
- Incluir explícitamente `coding_job_execution` en selección de evidencia normalizada y fuentes. No tratar
  `submission_state=ACCEPTED` como el predicado `outcome=ok` que habilita narración de efectos exitosos.
- Una llamada `cancel` puede aportar evidencia de solicitud de cancelación como efecto distinto, con
  `causes_cancel_of=jobId`, pero no incrementa el contador de comandos ni reemplaza su terminal.

## 8. API mínima propuesta y ownership de cambios

Nombres orientativos, pendientes de revisión; no están implementados:

1. `CoreTool.completionMode(arguments)`: SYNC por defecto, ASYNC_JOB_SUBMISSION para `project_exec`;
   wrappers propagan sin cambiar scope ni clasificarlo como READ.
2. `InvocationObserver.prepareJobSubmission(...) -> JobEvidenceOrigin` y
   `afterJobSubmission(origin, receipt, failure)`; recorder mantiene su binding comprometido.
3. `EffectInvocation` guarda el origen y valida `JobSubmissionReceipt` owner-bound. Ese receipt es
   administrativo separado, no `Outcome.OK`, no `Outcome.DEFERRED` y no MEMORY/READ_COMPLETED.
4. `CoreToolResult.jobSubmissionReceipt` opcional; todas las copias preservan sus flags y metadatos.
5. Registry ramifica **solo** ASYNC_JOB_SUBMISSION: reserva antes de llamar al tool, impide `admit` síncrono
   accidental y sella al retornar como hoy; finaliza submission por link/receipt comprometidos. No llama
   al finalizador síncrono del comando. En excepción consulta el link exacto: un fallo de presentación no
   puede convertir un job ya aceptado/activo en “no ejecutado”.
6. `CodingJobManager.start` agrega binding/observador durable y devuelve prueba de submission; su backend
   agrega seam de preparación. `persist` devuelve commit tipado; finalización/proyección no usa Snapshot
   como única prueba. Una ruta que no soporte el contrato falla cerrado para captura habilitada.
7. Repository/bridge agregan reserva/binding/aceptación/admisión/proyección CAS, tabla link y reconciliador
   de jobs. Migración compatible, retención/tombstones y Full/Play explícitos. Play no inventa jobs ni
   handlers nuevos: conserva capability unavailable.

No mantener un contexto mutable global/ThreadLocal; no cambiar seal para permitir callbacks tardíos;
no asignar owner por nombre de herramienta; no mapear submission a `ok` por texto. El handle del worker
solo opera sobre su job y debe rechazar cierres/admisiones tras terminal o con origen diferente.

### 8.1 Wrappers, tareas y límites del primer slice

El primer slice habilita evidencia asíncrona únicamente en los contextos Coding donde `project_exec`
ya está disponible. Verificación del código leído: `CodingExecutionTools.Tool` no sobreescribe
`unattendedAccess()` y hereda EXCLUDED; `CoreAgentRuntime.unattendedCapabilityAllowed` excluye ese
valor y `createScheduledTaskToolRegistry` lo filtra. `FlavorLinuxTools` crea las herramientas para el
bot/scope Coding seleccionado. **No cambiar EXCLUDED para conseguir que el recorder vea WRITE**:
`effectClass=WRITE` y `completionMode=ASYNC_JOB_SUBMISSION` son independientes de la elegibilidad
unattended. No se habilita project_exec programado/aprobado como parte de este diseño.

Esto debe comprobarse por contrato del registro, no solo por inspección. `ScheduledTaskBoundTool`,
wrappers de Coding, proactivo y reflexión propagan completionMode. `TaskTrackedTool` y el ejecutor de
acciones aprobadas **no pueden simplemente heredar la finalización síncrona** si llega ASYNC: antes de
crear intent síncrono/claim/dispatch rechazan esa combinación mientras no tengan un finalizador de
submission propio. El registro de tareas vigente debe continuar sin project_exec; una acción legacy
con ese nombre se rechaza por scope/catálogo actual, no se lanza ni se reinterpreta como nativa.
Esta restricción preserva disponibilidad actual y deja explícito el soporte asíncrono pendiente para
cualquier herramienta futura que sí sea elegible unattended; no se presenta como soporte B4 asíncrono.

Antes de habilitar alguna capacidad ASYNC en tareas, es obligatorio un bloque separado coherente:

- `TaskTrackedTool` reserva el **mismo** origen de submission task/occurrence/call/run, añade su writer
  administrativo al contexto y finaliza solo submission; no crea otro `tool_call` intent/started de
  comando ni llama `resolve` síncrono para convertir aceptación en efecto terminal.
- `TaskPendingActionExecution` captura completionMode antes del claim, persiste submissionId/jobId en
  el recibo del intento dueño, y usa un estado administrativo de aceptación separado de EXECUTED del
  comando. Repetir aprobación nunca crea otro job; resultado pendiente no vuelve a solicitar aprobación.
- `TaskPendingActionStore` recovery proyecta el vínculo por intento/job, no maps accepted→ok ni vuelve a
  ejecutar. `TaskRunLedger` distingue “submission aceptada” de “efecto terminado”: noDispatchVerified
  nunca es true después de transferir un job que puede arrancar; la política existente de retry no
  puede repetir submission por un timeout o fin del run.
- El worker Coding sigue siendo el único productor de `coding_job_execution`; los finalizadores de
  task/approval solo actualizan su submission, para evitar doble evidencia y doble conteo.

No implementar la mitad de esas ramas para hacer pasar un test sintético. El gate de cierre del primer
slice incluye demostrar la exclusión vigente de scheduled/pending y que ningún wrapper deja escapar
ASYNC hacia el finalizador síncrono. B4 actual de tareas síncronas permanece exigible e independiente.

## 9. Pruebas exigidas antes de implementación aceptada

Además de mantener `CodingJobRecoveryTest` y gates existentes:

1. Real `CodingExecutionTools` + `CodingJobManager`, backend fake con latches: retorno de submission
   durable, cero command-OK mientras pendiente; worker empieza antes/después del retorno indistintamente.
2. Loop/checkpoint real + recorder con batch/run: binding exacto; dos runs con mismo callId no se mezclan;
   callback/commit de otro job/owner no modifica filas. Poll repetido no crea comandos/episodios extra.
3. Dentro del primer efecto fake de preparación: journal de admisión y Room started ya durables; fallo
   en cada escritura evita el efecto. STOP/política revocados entre escritura y dispatch siguen bloqueando.
4. Muerte inyectada en cada frontera: reserva, journal inicial, aceptación Room, enqueue/claim worker,
   journal preparación, Room started, launch intent, proceso, terminal journal, proyección Room.
   Reapertura nunca ejecuta ni señaliza un job; el run terminado no cierra su ejecución viva.
   Usar latches entre reserva/binding/journal/enqueue y lanzar reconciliador concurrente: jobId nulo
   válido, binding único y ninguna transición terminal mientras el owner vivo crea el job. Verificar
   dos managers/procesos con lease vivo y muerto, sin decidir liveness por mero UUID/PID diferente.
   Inyectar `manager.close()` concurrente con preparación/proceso vivo: lease no liberado ni lockfile
   reemplazado antes del reap; el scanner no marca abandoned por el mero retorno de close.
5. Terminal journal persistido y callback perdido/fallido: recovery reproduce exactamente un terminal.
   Persist terminal fallido: Snapshot SUCCEEDED previo no acredita OK; journal pendiente queda incierto.
6. Log ausente, journal corrupto, schema1/2, identidad durable cambiada, conversación eliminada, entrada
   olvidada, storage generation distinta: sin éxito inventado, sin nueva atribución ni resurrección.
7. ASK aprobado/ALLOW_ALWAYS/DENY/STOP y preparación antes de launch; snapshot real, sin permiso nuevo.
8. Cancelación propia/repetida/terminal/recuperada: política actual preservada, cancel request distinto
   de command terminal; no doble conteo ni claim sobre jobs de otro owner.
9. Registro real de tareas excluye project_exec como antes; wrappers preservan completionMode y
   cualquier entrada ASYNC no soportada falla antes de claim/dispatch. Ningún ledger marca command-OK
   ni noDispatchVerified por recibir aceptación. No ampliar capacidades para ejecutar este test.
10. Memoria desactivada y activada durante job: no backfill oculto ni contenido privado en facts; journal
   operativo suficiente para no relanzar. Filtro/narración/retención distinguen ambos ref_kind.

Mutaciones focales: submission→OK comando; cerrar job por originRun; quitar fsync/commit proof;
aceptar callback Snapshot sin journal terminal; omitir preparación; lookup por último call/job;
replay en restore; contar polls como ejecuciones. Cada una debe romper su prueba correspondiente.

**Criterio de salida:** diseño revisado + parche acotado implementado + Full/Play compilados y pruebas
reales/mutaciones pertinentes ejecutadas cuando SDK esté autorizado. Este documento solo cubre diseño.

## 10. Adenda de cutover: el productor real es Crew, no el batch del capitán

Estado de esta adenda: diseño para revisión; **sin opt-in ni implementación de producción**.
La ejecución Full/Play del bloque de conexiones no valida este protocolo de jobs.

### 10.1 Hallazgos que corrigen el supuesto de origen de §§3/8

- `FlavorLinuxTools.createProfileForRuntime` añade `project_exec` al scope del bot Coding.
  `CoreAgentRuntime.runInternal` captura el catálogo del capitán para configurar Crew antes de
  instalar su `EpisodeRecorder`. El observer del chat no es un recorder del checkpoint del bot.
- `CoreAgentLoop.startPreparedTool` fija `toolLifecycle[callId]=STARTED` y actualiza el checkpoint
  antes de `registry.invoke(...,callId)`. Este STARTED prueba intención del call, no ejecución.
- `CrewManager` fija `checkpointScope(cycleId)` y persiste mediante
  `retainCheckpointForRun`, validando loop/token/cycle exactos. `CrewCheckpointStore.confirmDurable`
  entrega `ConfirmedSnapshot` con digest de payload, conversación, bot, scope, metadata y loop.
  No contiene un `MainChatTranscriptStore.CallBinding` ni un batch Room del capitán intercambiable.
- `WorkspaceRegistry.prepareAssignment` conserva `request_id`, `original_user_request`, misión,
  assignment y bot, pero **no** source message/batch/call del chat. El requestId recibido de
  `CoreAgentRuntime` es un ID de ejecución generado localmente, no un messageId. El texto copiado
  no es una llave de atribución. No existe hoy el binding central completo requerido por §3.

Por ello, un codec o una tabla que requieran siempre un batch central como si fuera el batch del
comando estarían modelando una identidad inexistente. Tampoco basta observar el último checkpoint.

### 10.2 Fuente discriminada y dos identidades causales

Separar el origen **del comando** de su eventual atribución **a la petición central**:

1. `CREW_CHECKPOINT`: storage generation, executionSessionId, botId, missionId, cycleId,
   scopeIdentity durable, checkpointPayloadSha256, callId y toolName. Todos se contrastan con el
   checkpoint confirmado, su call exacto pendiente/STARTED y el ciclo/owner de la invocación.
   Un cambio posterior del checkpoint no cambia este origen ya capturado. El digest es una prueba
   del contenido observado, no una autorización ni un orden monotónico.
2. Binding workspace opcional y completo: workspaceId, assignmentId y requestId; contrastar el
   registro de assignment, executionSessionId, misión, bot y scope. El Coding legado sin assignment
   no obtiene uno inventado. La ausencia explícita no se sustituye por el assignment más reciente.
3. Atribución central opcional **verificada**: centralConversationId, sourceMessageId,
   sourceBatchId, sourceCallId, sourceRunId, sourceProcessId y binding persistido al assignment.
   Es el call de delegación original, no el `callId` de `project_exec`. Ambos se conservan separados.

El batch del comando Crew queda ausente, no un UUID sintético que aparenta un batch comprometido.
Las variantes se validan completas: no aceptar media tupla central/workspace. Un decode de journal
produce un registro validado, nunca por sí mismo un `DurableJobCommit` ni una concesión de permisos.

Un origen Crew exacto sin binding central es **operacional no atribuido**, distinto de un journal
schema1/2 sin origen. El envelope distingue captura central habilitada, memoria OFF y ausencia de
binding central (por ejemplo modo OPERATIONAL_ONLY con motivo tipado); ausencia de binding no es
prueba de que el usuario desactivó memoria ni se trata automáticamente como corrupción. Conserva las capacidades existentes y la prohibición de replay, pero no crea
un episodio central ni recupera atribución por texto/tiempo. Para nuevas delegaciones instrumentadas,
la captura habilitada exige el binding exacto; no anunciar cobertura de atribución hasta implementarlo.

### 10.3 Seam nuevo obligatorio: call central → assignment, antes de spawn

El diseño concreto queda en [S6-central-workspace-causal-contract.md](S6-central-workspace-causal-contract.md).
Corrige dos supuestos: CentralWorkspace hoy sólo tiene execute2 y el fallback nativo admite antes de
sus guardas (no posee receipt productor); además el origen del call puede ser operacional aunque
memoria esté OFF. Hace falta execute4 explícito con admisión/backend receipt y fuente inmutable.

La solución mínima propuesta no reserva primero en Room: minting de `CentralInvocationSource` desde
checkpoint exacto antes de dispatch, transporte por EffectInvocation y parámetro tipado al backend,
y migración aditiva WorkspaceRegistry v1→2 para guardar fuente + assignment atómicamente en su
mismo SQLite. No Room bajo locks workspace/Crew/STOP, ni source fabricado desde requestId/texto.
La fuente se conserva en retries sin apropiación por otro call. Proyección a memoria es separada y
opcional, nunca condición para guardar procedencia operacional cuando OFF. Véanse las fronteras,
semántica de aceptación local y tests exigidos en el contrato enlazado.

### 10.4 Retirada, memoria OFF y conservación de identidad

El vínculo central debe verificar storage generation, fuente original y entry dueño activos. Una
fuente/episodio borrado, un assignment retirado o una conversación eliminada no se reconstruyen al
recuperar un journal. Respetar las protecciones de retirada/exclusión existentes por identidad, sin
introducir un subsistema universal de tombstones en este slice; un nuevo entry con el mismo título no es el anterior. La proyección no crea por defecto un
entry ausente usando el source pointer guardado en el journal.

Se fija el modo de evidencia al admitir submission; un job iniciado sin captura no hace backfill al
habilitar memoria. Transportar el ticket de memoria capturado en su fase, sin recapturarlo al terminar
ni mantener el lock del gate durante bridge/worker/lifecycle. La reparación estructural de evidencia
ya admitida no equivale a una narración nueva: OFF debe bloquear nuevas narraciones, y la resolución
conservadora de un job ya registrado debe preservar la política de bookkeeping del repositorio sin
revivir filas retiradas. El parche debe probar separadamente ambas rutas, no usar un booleano de UI
como permiso para volver a crear memorias.

### 10.5 Orden mínimo de implementación y pruebas adicionales

1. Tipos puros discriminados/codec/protocolo de revisiones y owner receipts, sin habilitar ASYNC en
   herramientas. Journal validado y commit fsynced son tipos distintos.
2. Slice CentralWorkspace de admisión/recibos backend explícitos y origen central→assignment;
   después proveedor de origen Crew confirmado, con tests de identidad y fallos entre reserva,
   preparación, binding y spawn. No claims de terminal Coding. El shape definitivo del codec no se
   congela antes de revisar este seam; los tipos puros previos no habilitan comportamiento.
3. Slice coherente de manager/backend/Room/registry: aceptación durable, preparación admitida,
   terminal fsynced, proyección/recovery, narrador y exclusiones de wrappers. Sólo entonces opt-in.

Pruebas adicionales a §9: callId reutilizado en otro cycle/bot/checkpoint no se mezcla; comando de
bot usa su checkpoint y no el batch del capitán; assignment legado no obtiene source central por
texto; retorno duplicado de delegación no roba binding; OFF/forget/reemplazo de generación entre
submission y terminal no recrean episode; callback tardío del job conserva ambas identidades causales.
No hay una decisión pendiente de permisos o producto: estas son garantías de procedencia y
persistencia que deben quedar revisadas antes de activar la implementación.

## 11. Adenda del productor: aceptación administrativa, preparación y terminal durable

Estado: **diseño revisable, sin implementación del productor ni opt-in ASYNC**. El proveedor Crew
se implementa y valida por separado antes de ampliar manager/backend/Room. Esta sección precisa
§§3–7; en particular §11.5 reemplaza cualquier lectura de `evidenceMode` que suspenda la auditoría
técnica determinista por apagar memoria. No añade permisos, ejecución automática ni estados de UI.

### 11.1 Pruebas distintas para submission y ejecución

API orientativa, pendiente de concretar junto al journal schema3:

- `SubmissionCommit(jobId, origin, revision)`: prueba administrativa de una solicitud persistida.
  El ACK al llamador referencia ese commit y la aceptación técnica correspondiente; no acredita
  preparación, creación de proceso, salida ni cumplimiento de misión.
- `DurableExecutionCommit(jobId, origin, revision, phase, terminalFacts)`: prueba de una revisión
  operacional persistida. Sólo un terminal válido permite normalizar el resultado del proceso.
  Una revisión de preparación/launch continúa siendo intención, no éxito.
- Un registro decodificado y validado no es ninguno de esos recibos. El productor de escritura
  o la recuperación validada son los únicos que pueden emitir prueba de durabilidad; no el JSON
  de una herramienta, un callback `Snapshot`, ni un constructor público de metadatos.

No reutilizar `CompletionSemantics.COORDINATION_ACCEPTED` para convertir enqueue en command-OK.
Esa semántica describe coordinación local síncrona en otros productores; el job necesita ACK
administrativo separado del resultado de ejecución. Incluso si el worker termina antes de retornar
`start`, el resultado de submission sigue siendo administrativo. Poll/read/wait no producen otra
unidad de ejecución ni adoptan el terminal en el run que consulta.

### 11.2 Journal y límites de la prueba de commit

El journal schema3 conserva origen Crew exacto, contexto de inputs confirmado, raíz de assignment
cuando exista, identidades de proceso/manager, revisión monotónica y fase de efecto. No reducir el
contexto plural a la raíz de delegación ni a un supuesto último input causal. El estado de UI actual
permanece separado de fases tales como preparación, intención de lanzamiento y terminal.

La escritura conserva el protocolo temporal → `fd.sync()` → rename → sincronización del directorio.
Sólo después de completar las tres fronteras se emite el commit tipado. Una excepción tras rename
no prueba que los bytes no llegaron a disco; conservar la revisión/candidato exactos como inciertos.
La reparación lee y valida lo persistido, nunca vuelve a entregar el comando al executor. Las pruebas
con backend de fallos distinguen error antes de bytes, después de sync, después de rename y en dirsync;
no se presentan como ensayos de corte eléctrico real ni garantía física del dispositivo.

La recuperación verifica fichero/ruta, UUID, generación, identidad durable, conversación activa,
origen completo, revisión y coherencia de fase/terminal. Schemas1/2 continúan como evidencia
operacional histórica sin origen nuevo: no atribuir mediante texto, timestamps o última llamada.
Fichero corrupto o terminal no acreditado conserva incertidumbre y no habilita replay.

### 11.3 Frontera real de preparación: incluye construcción lazy

El primer efecto no está necesariamente en `LinuxEnvironment.execute`:
`FlavorLinuxTools` obtiene `LinuxEnvironment.get(app)` dentro de la lambda backend. En arranque frío,
el constructor default crea `RootfsInstaller`, cuyo `init` puede crear directorios, renombrar o retirar
árboles previos/staging y persistir estado recuperado. Después, la ruta project crea temporales antes
de `beforeLaunch`. Que project evite certificados/chmod no elimina esos efectos de preparación.

Por tanto, el manager debe revalidar y persistir intención de preparación, proyectar su admisión
técnica y revalidar de nuevo **antes de llamar a `backend.execute`**, incluyendo cualquier factory
lazy que éste invoque. No construir anticipadamente un backend con efectos fuera de esa frontera.
Un seam colocado únicamente antes de `processTemp.mkdirs()` sería insuficiente en arranque frío.
La API concreta puede envolver la llamada backend completa; no requiere fingir un callback que el
backend legacy no pueda cumplir. Una ruta incompatible con la frontera no obtiene captura completa.

El callback `beforeLaunch` conserva sus validaciones finales y journal bajo los locks actuales, sin
introducir llamadas a Room dentro de `job.lock`, STOP/política o `ProotLauncher.launchLock`.
`RUNNING`/`startedAt` actuales se escriben antes de `ProcessBuilder.start`: son intención de lanzamiento,
no proceso observado. La preparación y su cleanup ya admitidos no desaparecen porque una validación
posterior impida crear proceso. NOT_STARTED no implica automáticamente ausencia de efectos.

### 11.4 Claim del worker, liveness y finalización

Orden requerido: reserva/binding exactos → journal submission acreditado → aceptación técnica e
intent de ejecución → claim/enqueue. Si falla la proyección técnica exigida antes de dispatch, no
se entrega al executor. Un fallo posterior de presentación no transforma un job aceptado en un
comando no ejecutado ni permite asignar un segundo job como retry.

Debe existir un claim de worker idempotente que distinga executor no iniciado de executor que
corrió y luego lanzó excepción. El catch de enqueue no puede llamar a un finalizador contradictorio
mientras el worker ya ejecuta. La identidad de job se asigna una sola vez.

El manager actual sólo tiene writer lease por proyecto; no posee aún el lease de liveness de §6.
El nuevo protocolo exige lock de fichero privado estable por instancia, processUUID y managerUUID;
no usar sólo PID ni ausencia de una entrada en otro mapa para declarar abandono. La reserva y el
worker retienen liveness hasta reap/cleanup. No unlink/recrear el lockfile ni liberar el owner mientras
queden productores o procesos vivos. Recovery no ejecuta ni señaliza procesos restaurados.

`finish` conserva el writer lease hasta reap/cleanup y reconciliación de versión. Persiste terminal,
obtiene commit y proyecta fuera de locks. Si persiste terminal pero pierde callback, recovery proyecta
la misma revisión una sola vez. Si falla persistir terminal, el Snapshot en memoria no acredita OK.
Un exitCode0 durable sólo confirma terminación de ese proceso, no que un objetivo/build/tests haya
quedado validado semánticamente.

### 11.5 Memoria OFF no elimina auditoría técnica

Conforme a SINGLE_SESSION §7.5A y al gate de memoria, separar explícitamente:

1. Journal operacional y auditoría causal técnica determinista de submission/admisión/terminal.
   Se conservan también con memoria OFF; no depender de un booleano global `evidenceEnabled` para
   omitir intent/started exigidos antes del efecto ni borrar su resultado técnico.
2. Creación de memorias semánticas, enlaces semánticos/narración sujetos a memoria y backfill. Aplicar el ticket
   capturado y las protecciones de retirada vigentes. OFF bloquea nuevas memorias semánticas/narraciones, no
   fabrica ausencia de ejecución. Habilitar después no autoriza backfill del job.

Ausencia de atribución central y memoria OFF son hechos distintos. Un job Crew operacional sin
assignment verificable conserva su identidad técnica; no recibe episodio central inventado. El
shape final debe distinguir estas dimensiones, en lugar de hacer que DISABLED elimine todo Room.
La reparación estructural de evidencia ya admitida no resucita una entrada semántica retirada.

Antes de integrar schema3 real, alinear también los tipos/codec de la foundation aislada:
`CodingJobEvidence.CaptureMode` conserva los nombres previos `CENTRAL_ENABLED`, `MEMORY_DISABLED`
y `CENTRAL_BINDING_ABSENT`. No interpretar `MEMORY_DISABLED` como prohibición de toda auditoría
Room ni reutilizar OPERATIONAL_ONLY como apagado de evidencia causal técnica. Esa foundation no
habilita actualmente el productor; su shape debe separar captura semántica, disponibilidad de
atribución y evidencia operacional conforme a esta adenda. OFF no equivale a fuente olvidada,
retirada o reemplazada: esos últimos estados impiden reconstruir memoria/atribución desaparecida,
mientras el journal operacional conserva sus hechos sin convertirse en permiso para recrearla.
No se cambia el codec en paralelo con el proveedor Crew; la alineación es gate previo a integrar
journal schema3 y debe tener pruebas de compatibilidad explícitas.

### 11.6 Oráculos mínimos antes de activar el productor

Además de §9 y del origen Crew/input-context:

- Dentro de una factory fría que simula el primer mkdir/rename, comprobar journal de preparación
  y admisión técnica ya comprometidos. Fallo de cualquiera impide incluso construir esa factory.
- Enqueue retorna antes/después del worker: siempre ACK administrativo, nunca command-OK. Executor
  que corre y luego lanza excepción no duplica dispatch ni cierra como NOT_STARTED un proceso vivo.
- PREPARATION_INTENT seguido de gate revocado antes de launch conserva posible efecto; no devuelve
  NOT_EXECUTED por el mero `startedAt=null`.
- Inyectar cada fallo de sync/rename/dirsync y callback perdido: proyección exacta e idempotente sin
  reenviar ejecución. Decoder por sí solo no puede acuñar commit.
- Scanner concurrente con reserva/enqueue y dos owners: un lease vivo conserva pendiente; sólo prueba
  de owner muerto permite reconciliar abandono, nunca replay ni señales.
- OFF durante submission/terminal: auditoría técnica íntegra, cero nueva narración/backfill. Olvido,
  retiro o cambio de generación no se resuelven recreando el episodio desde el journal.

Estos oráculos son requisitos pendientes, no resultados ejecutados. No se habilita ASYNC ni se cambia
un permiso para hacerlos pasar; el futuro corte manager/backend/Room/registry debe cerrarlos coherentemente.


## 12. Alineación del envelope aislado con la fuente Crew

El envelope interno mantiene `schema: 3` y exige ahora `envelopeVersion: 2`.
Esta versión corresponde a metadatos no conectados al journal operacional: no migra jobs
operacionales schemas 1/2, no crea recibos durables ni activa ASYNC. El decoder rechaza
explícitamente el shape anterior sin versión, en lugar de inventar procedencia.

- Crew conserva el `CrewInputContext` exacto, plural y con cobertura PARTIAL explícita.
  Las referencias deben apuntar a su sesión de ejecución; la procedencia histórica registrada
  puede identificar otro bot (por ejemplo, el emisor peer) y no se reescribe.
- `rootCentral` es raíz histórica, no causa exclusiva ni generación del almacenamiento técnico.
  `technicalStoreGeneration` es un UUID obligatorio de binding, incluso con memoria OFF;
  el futuro productor debe obtenerlo de la reserva Room real. El DTO no acredita esa reserva.
- `SemanticCapture` y presencia de raíz son dimensiones independientes. El valor observado
  en submission es inmutable entre revisiones, no permiso vivo: futuras escrituras deben
  comprobar OFF, reset y olvido. DISABLED no habilita backfill retrospectivo ni suprime auditoría.
- El codec exige arrays canónicos, identidades sin duplicados/solapamientos, campos específicos
  por canal, cobertura coherente e enteros exactos. El presupuesto de caracteres lo proporciona
  el llamador; la profundidad máxima deriva del shape, sin topes arbitrarios de inputs.
- La prueba UTF-8 preserva unidades surrogate aisladas, pares y Unicode ordinario. El encoder
  ya escapaba surrogates antes de este bloque: se añade cobertura, no se atribuye un bug corregido.

La validación de esta alineación se registra por separado del productor pendiente de §11.
Ni igualdad tras codec ni un terminal decodificado acreditan fsync, liveness o ejecución.

## 13. Siguiente corte: IO del journal operacional existente

Contrato revisado antes de implementación. Este corte extrae el writer usado por `persist`
del manager real; conserva JSON operacional schemas 1/2 y recuperación legacy. No escribe
schema3, añade atribución, activa ASYNC ni transforma el Snapshot público en recibo durable.

El manager congela una sola vez los bytes serializados bajo sus guardas actuales. El writer
retiene un candidato opaco con destino canónico, job/schema, identidad de intento y copia
exacta de bytes. Legacy no tiene revisión persistida: no inventarla con un contador del writer.
El futuro productor v3 tendrá que aportar su revisión y binding exactos.

La frontera positiva exige escribir el staged del mismo directorio, sincronizar fichero,
cerrarlo, renombrar al destino y sincronizar el directorio. Sólo entonces puede emitirse un
commit interno opaco asociado al candidato. Un decoder, Snapshot, existencia del fichero o
relectura coincidente no acuñan ese commit. No implica persistencia física universal del hardware.

El seam separa write/sync/close, rename y directory-sync para inyectar fallos verificables.
Un error conserva candidato y fronteras intentada/confirmada sin afirmar ausencia de bytes:
una excepción después de rename o de una sincronización puede dejar contenido persistido.
La limpieza no oculta la excepción primaria. No hay API para reintentar un candidato antiguo
ni replay automático; dos intentos se distinguen aunque sus bytes coincidan.

La serialización interna por raíz canónica no sustituye lease de liveness entre procesos,
CAS/revisión v3 ni protección de snapshots atrasados. Orden: job → conversación → writer;
el writer no llama de vuelta a job/conversación/Room. Se mantienen validaciones de rutas y
symlinks; el almacenamiento sigue fuera del proyecto editable.

Oráculos ejecutados: OPEN con fallo previo; WRITE/SYNC/CLOSE/RENAME/DIRSYNC antes/después de operación real; copia defensiva, bytes exactos,
orden sync/rename/dirsync, conservación del destino previo antes de rename, incertidumbre tras
rename, cleanup y excepción primaria, rechazo de rutas desviadas y serialización local. Las
pruebas de recuperación/assembly/tools afectados conservan contratos legacy. Lease, claim CAS,
preparación antes de factory lazy y proyección Room siguen requisitos del corte integrado §11.

## 14. Claim local del worker y fallo de transporte del executor

Contrato revisado antes de código. Corrige el caso real de un Executor que ejecuta el Runnable
y después lanza excepción, sin activar ASYNC ni declarar lease durable o CAS de journal.

Cada Job comienza RESERVED. El primer paso del Runnable gana RESERVED→CLAIMED; duplicados
concurrentes o tardíos retornan sin backend, finalización, cierre de lease ni countdown. El catch
de submission sólo puede finalizar como rechazado si gana RESERVED→REJECTED. Ese rechazo
prueba que este backend no obtuvo el claim, no ausencia de preparación previa ajena al manager.

La finalización tiene un único ganador desde CLAIMED o REJECTED hacia FINALIZING→FINISHED.
Todo setup posterior al claim queda bajo el try/finally responsable. Cleanup con finally anidados
intenta release/unregister y garantiza FINISHED/countdown aunque falle una de esas operaciones;
el callback ocurre fuera de locks y después de cleanup. No se atribuye release exitoso si falló.
Una llamada duplicada a finish no debe liberar recursos de otro productor.

Si execute() lanza después de claim, el catch no toca el snapshot ni cierra recursos del worker.
Devuelve por excepción tipada `EnqueueOutcomeUncertain` el jobId, estado observado y exitCode
observado, sin comando ni texto libre del executor. La herramienta conserva esos datos en error
estructurado, separando `submission_transport: uncertain` de `execution_state`. Un terminal ya
conocido no se degrada a UNCERTAIN; tampoco se convierte el error de transporte en command-OK.
La cancelación lanzada por el executor después de claim sigue esta misma distinción, no simula
cancelación previa al dispatch. La identidad permite consultar el job sin repetir el comando.

Oráculos ejecutados: reject-beforeclaim y Runnable retenido tardío, inline-run-then-throw,
worker vivo bloqueado antes del throw, Runnable duplicado simultáneo y después de finish,
cancelación antes de claim, callback reentrante que crea otro job. Comprobar estado real,
versión de scope y exclusión del writer, además del número de dispatch/callbacks. No usar
una espera negativa como única evidencia de una carrera ni ejecutar procesos/modelos reales.

## 15. Bundle integrado schema3, reserva técnica y owner lease

Estado: diseño revisado por partes; APIs concretas e implementación pendientes. No habilita
ASYNC ni convierte los checkpoints §§12–14 en productor completo.

### 15.1 Owner y retiro

El manager cacheado vive normalmente hasta terminar el proceso. processUUID identifica esa vida;
managerUUID distingue instancias. Antes de reservar una submission adquiere un pin sobre un
lease de fichero estable en `filesDir/jarvys/runtime-owners/coding/<managerUUID>.lock`, fuera de
`StorageGeneration`, árboles retirados y limpieza de huérfanos. Header técnico, sin comandos,
conversaciones ni contenido del usuario. No unlink/recreación por close, scanner o GC.

Quiesce es corto/no bloqueante: OPEN→QUIESCING rechaza nuevas reservas. Pins cubren reserva,
journal, enqueue, worker, reap/cleanup y el intento de proyección terminal. Sólo el último pin
permite cerrar lock/channel; el cache no reemplaza un manager mientras drena. Esperar su cierre
es operación externa fuera de callbacks/locks, no un método de bot ni señal adicional al proceso.
La cancelación explícita conserva los mecanismos autorizados actuales. Un lock ausente, corrupto,
contendido o solapado no acredita muerte. Recovery sólo puede concluir abandono al adquirir en
exclusiva el fichero/header exactos; nunca por PID, timeout o mapa local vacío.

### 15.2 Reserva y destino técnico

EXEC execute4 captura procedencia Crew exacta e inputContext mientras su invocación es válida;
el worker recibe historia inmutable y autoridad operacional vigente, no un witness sellado como
permiso futuro. jobId/submissionId se asignan una vez, antes de posibles resultados inciertos.

La reserva usa el UUID **real** del destino Room leído dentro de su transacción. Orden de locks:
worker del bridge → anchor de instancia → transacción. El caller no espera al bridge reteniendo
job, conversación, writer, política, launch o anchor. El resultado de reserva sólo se acredita
tras commit. Interrupción de la espera puede dejar la callable ejecutándose: conservar candidato
exacto/UNKNOWN, no enqueue, nuevo job o retry de comando por suponer rollback.

Se requieren dos tablas técnicas separadas de episodios: bindings de submission inmutables
(job/submission, generación técnica, origen exacto, owners, política semántica) y observaciones
por (job, revisión) con payload/hash exactos, fase y terminal. Un duplicate debe comparar todos
los campos, no considerar INSERT OR IGNORE como ACK por sí solo. No hay mensaje Central ficticio
ni FK obligatoria a memoria semántica. Root histórica opcional no sustituye inputs ni generación.
Memoria OFF no suprime estas filas técnicas.

### 15.3 Un journal y productor acreditado

Schema3 exterior contiene Snapshot operacional y envelope de evidencia v2, con identidades y
relación validadas en los mismos bytes. No escribir un sidecar causal después del journal UI.
El commit de IO debe ser issuer-bound y acuñado sólo por la frontera real sync/rename/dirsync;
ni DTO, decoder, Snapshot ni token extensible del writer legacy bastan como autoridad de proyección.
Las APIs compartidas main/Full deben resolver esa propiedad antes del wiring. Recovery acredita
lectura/identidad mediante un protocolo separado, nunca fabricando un commit de ejecución.
Schemas1/2 siguen legibles como historia operacional sin elevarlos a procedencia o liveness nueva.

Orden: reserva Room → journal submission → aceptación/intent técnico Room → claim/enqueue.
En worker: gates vigentes → journal PREPARATION_INTENT → admisión técnica Room → recheck
operacional → backend entero, incluida factory lazy. beforeLaunch conserva guards y journal
LAUNCH_INTENT bajo locks existentes; ninguna llamada Room dentro de esos locks. Tras reap/cleanup:
terminal en journal → proyección exacta fuera de locks → liberar pin. Un callback perdido no exige
repetir el comando: recovery proyecta el mismo job/revisión y conserva conflictos como incertidumbre.

### 15.4 Punto de admisión y cambio de generación

La admisión técnica durable exacta es el punto de linearización de esa fase, no una nueva API de
cancelación. Close/reopen del proveedor o forget de memoria no revocan retroactivamente trabajo
ya admitido. Se mantienen STOP, política, perfil, scope y lease antes de preparación/launch.
No se sostiene el anchor Room durante el backend ni se promete cero efectos tras un close.
Cada **nueva** admisión y proyección exige el mismo destino/UUID; jamás adoptar la base nueva,
recrear un root olvidado o rebautizar el journal histórico para completar una proyección.

Pruebas: close antes de admisión rechaza token viejo y no entra al backend; close después de
admisión permite continuar bajo guards operacionales, con journal original y sin proyección en
base reemplazada. Forget/retirada bloquean nuevos enlaces/narración, no convierten el comando
ya admitido en inexistente. El target semántico debe ser exacto y no estar eliminado; no resolver
por topic_key para resucitar una memoria durante recovery. ENABLED capturado no es permiso vivo;
DISABLED no autoriza backfill al reactivar memoria.

### 15.5 Gate del bundle

Antes de opt-in: factory fría observa journal/admisión antes del primer efecto; fallos e
interrupción real del bridge no encolan; pruebas de identidad/UUID/conflicto y reapertura sin
replay; dos managers/owners vivos y muertos; quiesce con pins; terminal durable y callback perdido;
OFF/forget/generación no resucitan semántica. Además deben cerrarse receipt administrativo del
Registry, exclusión scheduled/pending y wrappers, narración y las pruebas de §§9–11. Subpartes
verdes no autorizan por sí solas ASYNC ni un PASS final S6.

### 15.6 Destino semántico existente y jobs pendientes

La raíz histórica de assignment no es una causa exclusiva ni sustituye el contexto plural Crew
(incluidos sus inputs no resueltos/PARTIAL). El destino semántico, cuando exista, se captura como una
entrada **ya existente** y activa mediante el tuple exacto de raíz: conversación, batch, call y run,
fuente de la entrada y su revisión/fingerprint. Una raíz ausente, retirada, ambigua o de otra generación
no autoriza fabricar un episodio, adoptar el último episodio ni reconstruir memoria olvidada.

El ticket de memoria se captura en la misma invocación. `enabledAtCapture()` sólo describe esa captura;
no es permiso actual. El ticket no se serializa en el journal ni se reconstruye en recovery. La auditoría
técnica y sus cierres siguen siendo independientes del gate de memoria.

El link técnico de destino conserva por separado admisión y terminal confirmado. Mientras el job esté
pendiente, ese link excluye la entrada de la cola de narración y también de su CAS de commit: un job
nuevo admitido mientras el LLM narra invalida el commit antiguo. No basta filtrar sólo la consulta de
la cola. No se agregan roles de memoria durante submission/preparación.

Una vez confirmado el terminal técnico, se libera el bloqueo pending aunque el ticket ya no sea válido.
El enlace semántico es un paso separado: gate(ticket) → fence del destino → transacción, después de la
proyección técnica, nunca anchor → gate. Sólo el ticket vivo y la misma entrada/revisión/fingerprint
permiten insertar los roles de submission (sin resultado de ejecución) y execution terminal. Un fallo
semántico no modifica el resultado técnico del proceso. `SUPPRESSED` es persistente: activar memoria más
tarde, reiniciar o leer el journal no habilita backfill ni restaura un ticket.

El lector normalizado resuelve cada referencia por entrada/job/generación/revisión exactos. Transporta
metadatos de evidencia, no comandos privados, argumentos ni logs. La integración actual debe declarar
sus límites: una raíz Central no acredita por sí sola la cobertura de todos los inputs Crew para un
digest; esos episodios conservan cobertura estructural pero `episodesComplete=false` hasta resolver esa
cobertura. La elegibilidad previa del narrador que exige evidencia OK no se transforma en una narración
de éxito para jobs error-only. Estos límites no constituyen cierre de S6 ni habilitación ASYNC.


## 16. Estado de los gates tras los checkpoints ACK y live repair (27-10-2026)

Esta tabla es seguimiento del alcance ya definido en §§9, 11.6 y 15.5, no una ampliación
ni un juicio global de S6. Último corte: reparación live sobre `7e37b5f7`; 234 Full + 185 Play casos frescos
([manifiesto](S6-coding-live-repair-test-results.json)). Las evidencias de checkpoints anteriores
conservan su árbol y fecha propios; no se suman como una única suite final.

| Gate existente | Estado del checkpoint | Evidencia o cierre pendiente |
|---|---|---|
| ACK administrativo, wrappers y exclusión de tareas | Cerrado en pruebas focales | Registry real separa aceptación de command-OK; no se habilitan tareas ASYNC. |
| Fuente Crew exacta y contexto plural | Integración focal probada | Same-save/source y binding; falta cerrar replay del loop ante STARTED sin respuesta. |
| Claim local y preparación antes del primer efecto | Cerrado en pruebas focales | Manager real con backend/factory fake; no sustituye toda la matriz de revocaciones. |
| Liveness y quiesce | Parcial | Lease entre dos JVM host probado; recovery/scanner concurrente del productor sigue pendiente. |
| ACK post-commit/pre-entrega interrumpido | Cerrado en frontera específica | Mismo binding, cero enqueue/backend, pin hasta terminal real; no cubre pérdida de reserva previa. |
| Reserva o primer journal inciertos | Parcial | Reserva entregada/primer journal UNKNOWN reparado en manager real; ACK no entregado/callable pendiente siguen abiertos. |
| Terminal deseado y journal/proyección fallidos | Parcial | Store live repair exacto/CAS y manager preclaim probados; terminal deseado tras ejecución real y fallo final pendiente. |
| Recovery de otro owner/callback perdido | Pendiente | Muerte acreditada, terminal exacto, conflictos y cero replay/señales. |
| STOP/ASK/policy/cancel y fallos en cada frontera | Parcial | Regresiones focales verdes; falta matriz integrada completa de §§9–11. |
| Memoria OFF/olvido/generación y fuente semántica | Parcial | Consumidores sembrados y supresión probados; falta cadena con productor real hasta lectura/narración. |
| Narración/compaction pending y CAS | Cerrado en pruebas focales | Nuevo job o cambio invalida snapshot; omisiones no acreditan éxito global. |
| Cobertura de referencias externas retenidas | Pendiente | Roles de job/contexto Crew no equivalen por sí solos a hojas Central cubiertas. |
| Mutantes pertinentes y opt-in final | Pendiente | Sólo tras cerrar los gates anteriores y juicio independiente; catálogo permanece desactivado. |

S7/S8, equivalencia Engram y APK tienen gates separados. Las decisiones de producto y publicación
restringida pendientes no se consideran aprobadas por esta tabla.
