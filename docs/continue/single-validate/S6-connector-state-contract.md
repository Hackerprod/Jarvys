# S6 — estado local de conectores y eventos durables

Estado: **adapter/bootstrap aislados implementados y revisados: 67 pruebas Full + 67 Play PASS; boundary SDK24/34, 4 + 4 PASS adicionales. Wiring operativo, Room/reset real y poda siguen pendientes**.
Fecha: 2026-10-26. Fuentes: árbol de trabajo actual; `S6-connector-audit-contract.md` §C y §2;
`docs/SINGLE_SESSION.md` §§7.3–7.5. No cambia permisos ni políticas, no instala SDK, no publica código.

## 1. Problema verificado y semántica que se conserva

`ConnectorStateStore` vive en `connectors/ConnectorRegistry.kt`. Hoy `setConnected` hace
SharedPreferences.apply y después Room únicamente para false→true. `activate` cambia la preferencia
antes de `runtime.connect`; su rollback a false no tiene evento. `disconnect` primero llama al store:
un fallo de ese método puede impedir llegar a `runtime.disconnect`. `unregister` ejecuta ambos bajo
`registry.lock`. UI `ConnectorsDeviceDetailScreen` llama estas rutas directamente desde permisos,
onConnect/onDisconnect y settings. `ModulesScreen` también cambia conexión, y `ModuleRegistry` usa
`connectIfUnselected`; no basta corregir un único botón.

`calculateState` combina elección local con readPermissions y `connectionAccessGranted`. La elección
no demuestra concesión OS, OAuth/handshake remoto ni autorización de una operación. El gate vuelve a
validar estado/permisos/política antes de dispatch. Se preservan esas comprobaciones y las diferencias
entre `connect`, `connectFromSystemSettings` y `connectIfUnselected`.

Room `connector_state_events` usa UUID TEXT PK, connected y timestamp. El recorder busca solo
connected=1 y ordena timestamp/UUID, por lo que puede adjuntar una concesión obsoleta aun si existiera
revocación posterior. Además escribe `connector_event` con authorization=allow: eso confunde historia
local con admisión efectiva.

**Hecho nuevo definido:** transición de **elección local** (`selected=true/false`), con razón tipada.
Una observación de permiso revocado es otro hecho: no cambia automáticamente la elección local ni
se etiqueta como “el usuario desconectó”. Una desconexión local no revoca permisos OS/remotos por sí
sola. No agregar solicitudes de autorización ni una nueva política de conexión.

## 2. Comparación de alternativas y mínimo recomendado

Garantías necesarias: elección+evento comprometidos juntos; baja efectiva aunque Room audit falle;
orden durable independiente de reloj/UUID; replay con identidad exacta; UI sin IO bloqueante; ninguna
preferencia/grant histórico sustituye al gate. No es necesario crear una segunda autoridad de permisos,
un historial paralelo permanente ni habilitar escritores multiproceso.

| Alternativa | Garantía / coste | Juicio de alcance |
|---|---|---|
| **Prefs existentes + secuencia + outbox por clave, un commit** | Estado local y evento se guardan en una edición; Room no está en el camino de baja. Requiere writer único, snapshot confirmado separado y gestión de commit(false). XML se reescribe internamente, pero no se reconstruye un array de toda auditoría. | **Recomendada como mínimo S6**, sujeta a las pruebas de abajo. Conserva ubicación/backup y lectores existentes con una corrección del deny overlay. |
| Room authoritative con tabla state+outbox/cache | Transacción SQL fuerte y secuencia fácil; si Room audit está caído/corrupto tampoco puede persistir baja. Precisa overlay inmediato y una segunda fuente durable de revocación que venza al Room viejo al reiniciar. | Viable, pero ese protocolo de dos autoridades no es menor que mantener Prefs como fuente local. No elegido para el fix mínimo. |
| SQLite operacional propio + state/event/outbox | Transacción por fila, backlog escalable y mejor soporte multiproceso; exige nuevo lifecycle, schema, reset/backup, inicialización/cache y migrar lectores directos. | Alternativa si se demuestra que el backlog o multiproceso lo exige; **no introducir ahora** solo por preferencia arquitectónica. |

Los manifests inspeccionados no declaran `android:process`; la opción mínima exige un writer único
por ámbito de SharedPreferences con lock dedicado compartido entre instancias. No afirmar coherencia
multiproceso de SharedPreferences. Si aparece un consumidor en otro proceso, la prueba de inventario
debe fallar y se necesita ownership IPC/otro store antes de habilitarlo.

### 2.1 Formato Prefs propuesto

En `jarvys_connectors`, manteniendo los campos existentes:

- `connected_<id>` conserva la elección y ausencia vs false explícito;
- `connection_store_epoch` y `connection_next_seq` persistentes;
- por conector, `connection_revision_<id>`, último operationId y último snapshot completo del evento;
- `connection_pending_<epoch>:<seq>` guarda **un** evento con eventId, seq, connectorId, previous/selected,
  razón tipada, operationId/parentId y observedAtMs;
- phase ACTIVATING/READY y ownerProcess/operationId para una activación pendiente;
- identidad del destino Room/proyección confirmada y estado de bootstrap.

**Una sola edición `.commit()` off-main** escribe elección, revisión, evento pendiente y phase.
Si commit devuelve true se publica el snapshot confirmado y se permite continuar el alta. Si devuelve
false, la vista SharedPreferences en memoria puede haber cambiado: no usarla como prueba de commit ni
llamar runtime.connect. Mantener el snapshot confirmado anterior y un deny overlay hasta reparación;
no afirmar rollback en disco. La baja ya es efectiva por overlay y cleanup independiente.

No hay array global de eventos, ni append del histórico de acciones de autonomía. El outbox contiene
solo transiciones de conexión aún no proyectadas, y se drena tras commit Room. SharedPreferences sí
reescribe su XML: durante una caída larga de Room el backlog puede crecer, y esta opción no promete
coste constante ni un límite artificial. No descartar eventos por tamaño ni asumir “drain rápido” como
garantía. Medir la prueba de backlog; si resulta inadecuada, reconsiderar el store transaccional separado.

### 2.2 Identidad, replay y commits fallidos

`eventId=storeEpoch:sequence`, con secuencia monotónica asignada bajo el writer lock; UUID operationId
es identidad, no orden. Cada request conserva expectedRevision/epoch del conector. Repetición del mismo
operationId con payload idéntico es replay; payload distinto es conflicto. Si hubo una decisión posterior,
un request viejo se devuelve SUPERSEDED en vez de reaplicarse. No necesita conservar todos los UUID en
Prefs para siempre: expectedRevision más último recibo/snapshot evitan que un callback viejo reactive.
Una edición fallida no reutiliza su secuencia con payload diferente: reintenta el mismo evento o reserva
una secuencia posterior que preserve el registro dudoso; no sobreescribir una key pendiente desconocida.

### 2.3 SharedPreferences.commit(false): estados candidatos y commits ajenos

Una fachada por caller no basta. Debe existir **un** coordinador/snapshot confirmado por namespace de
preferencias en el proceso; instancias de registro y lectores directos lo comparten. El outbox que
consume el proyector durante ese proceso procede de commits confirmados por ese coordinador, no de
enumerar a ciegas la vista mutable de SharedPreferences tras un fallo.

SharedPreferences.commit(false) puede dejar modificaciones en memoria, y un commit posterior de
política/audit/cleanup puede escribirlas a disco. No se puede impedir esto prometiendo “rollback”.
El formato debe hacer seguro ese candidato aunque termine persistido por otra operación:

- El primer commit de alta incluye **siempre ACTIVATING** y su owner/operationId. No existe un true
  utilizable sin phase; con commit false no se ejecuta runtime.connect ni se publica READY. Si otra
  escritura hace durable ese candidato, el siguiente arranque lo detecta como activación inconclusa
  y mantiene deny hasta rollback/reparación; no lo convierte en disponibilidad ni grant.
- READY solo se escribe después de que runtime.connect retornó y CAS comprobó que la intención sigue
  vigente. Si ese commit final devuelve false, el resultado es **ACTIVATED_PERSISTENCE_UNCERTAIN**,
  no fracaso de runtime ni conexión durable confirmada. En el proceso sigue deny hasta reparación.
  Si READY termina realmente en disco por un commit posterior, al reiniciar acredita únicamente
  aquella elección/activación local que sí ocurrió; se revalidan permisos/gates actuales. No se
  promete que un intento de persistencia incierto jamás podrá aparecer durable después.
- Una baja/rollback fallida deja candidato false y phase no disponible; cualquier persistencia posterior
  de ese candidato sigue siendo restrictiva. Una alta nueva solo lo reemplaza por request explícito
  más reciente, nunca por callback/retry de una operación antigua.
- El high-water de secuencia en memoria incluye candidatos dudosos; no reciclar su número para otro
  payload. Un retry confirma el mismo evento. Al reiniciar, el XML persistido y sus pending keys fijan
  el high-water de nuevo, sin atribuir durabilidad a candidates que no están allí.
- Cleanup del outbox elimina únicamente keys con commit Room ya confirmado y payload idéntico; no
  escribe READY ni borra phase/deny/candidatos ajenos. Puede incidentalmente persistir otros cambios
  de Prefs: por eso la seguridad depende de las fases, no de asumir aislamiento entre editores.

El lock de escritura es propio del store, nunca actionPolicies.lock/STOP/registry.lock. No se llama Room
mientras se retiene. Las ediciones de otras preferencias conservan sus claves; no usar clear ni volcar
un mapa viejo completo que borre políticas o eventos de otra operación.

## 3. Interfaz compatible y ejecución fuera de main

`ConnectorConnectionPreferences.isConnected/hasConnectionChoice` siguen siendo lecturas rápidas del
snapshot confirmado más deny overlay. Constructor no espera Room ni migra auditoría. Inicialización
operacional off-main, estados INITIALIZING/READY/FAILED; antes de READY no habilitar dispatch/defaults.
`connectIfUnselected` espera inicialización antes de decidir si faltaba elección.

`setConnected` síncrono se conserva para callers off-main/tests, con guard de hilo. En producción,
`setConnected(true)` delega al protocolo del coordinador: conservar la firma no permite saltar
ACTIVATING/runtime/CAS/READY ni publicar disponibilidad por un write directo. El adaptador falso de
pruebas puede conservar su implementación simple, sin presentarse como prueba del store real. UI usa
`requestConnect/requestDisconnect/requestUnregister`, operationId y resultado observable. El coordinador
posee la operación fuera de la composición; IO en executor/corrutina dedicada, ActivityResult/settings y
UI vuelven a main. No relajar el guard del bridge para ocultar llamadas UI bloqueantes.

Resultados internos distinguen APPLIED_DURABLE, DISCONNECTED_PERSISTENCE_PENDING, FAILED_CONNECT,
SUPERSEDED, ACTIVATED_PERSISTENCE_UNCERTAIN y AUDIT_PROJECTION_PENDING. No mostrar “conectado” por enqueue de IO, ni revertir una elección
committed porque falló únicamente su proyección Room.

Inventario de consumidores obligatorio: `NotificationBuffer.kt` lee directamente
`connected_notifications`. Debe consultar el snapshot/deny overlay compartido (o una fachada equivalente),
para que una baja sea efectiva antes del commit off-main y commit(false) no habilite captura por la vista
mutable de Prefs. En `NotificationBuffer.capture`, preservar la rama previa e independiente de
`ProactiveNotificationDispatch`: el deny del conector solo controla su buffer, no desactiva proactividad
por otra autoridad. El grep de producción Main/Full también encuentra `GmailConnector.kt` en la
resolución de contactos: su lambda `connected_contacts` debe usar la misma fachada, conservando el
permiso Contacts existente. `ConnectorStateStore` es el único otro lector/escritor directo hallado.
Un test de inventario debe detectar nuevos lectores `connected_*` fuera de estas rutas. Mantener
`permission_asked` y ActionPolicyDefaults intactas.

Serializar por conector y generación de definición. Locks de catálogo/STOP/política solo capturan y
revalidan snapshots; ningún IO, runtime.connect/disconnect ni await UI bajo ellos. `unregister` marca
exclusión/toma identidad y suelta lock antes de persistencia/cleanup. No tocar una definición reemplazada.

## 4. Protocolos de alta, baja y rollback

### Alta explícita

1. Capturar operationId, definición y epoch de intención. Mantener gates actuales de readPermissions
   y la excepción explícita de notification settings; la auditoría no agrega permisos.
2. Off-main, commit único de elección/outbox `selected=true`, evento `LOCAL_CONNECT_SELECTED` y
   `activation_phase=ACTIVATING`, ligados al operationId. Esto acredita una elección, no un handshake.
3. Invocar `runtime.connect` fuera de locks. Mientras ACTIVATING, no exponer disponibilidad efectiva
   que permita dispatch antes de completar esta ruta; la UI puede mostrar operación pendiente.
4. Éxito: CAS del mismo operationId/epoch a READY y publicar estado calculado con permisos actuales.
   El cambio de phase es operacional: no inventa un segundo grant local ni un permiso remoto.
   Commit READY fallido se maneja como ACTIVATED_PERSISTENCE_UNCERTAIN (§2.3), no como excepción de
   runtime que dispare un rollback de causa falsa.
5. Fallo: primero aplicar deny overlay; después commit `selected=false` y evento
   `CONNECT_ROLLBACK`, con parent_operation_id del alta. Cleanup desconexión según runtime existente.
   Si ya hubo disconnect posterior, el rollback viejo no pisa la decisión más nueva; conserva diagnóstico
   del intento, no añade un estado falso. Una excepción de auditoría Room no provoca rollback.

READY indica que la ruta local de activación retornó, no disponibilidad remota perpetua. Al reiniciar
no llamar `runtime.connect` para reparar eventos: la recuperación de auditoría no produce efectos de
conexión. Un ACTIVATING abandonado se resuelve conservadoramente a false con razón
`ACTIVATION_INTERRUPTED` cuando se comprueba que ya no existe su operación viva; nunca se anuncia READY
por leer selected=true. No modificar las reglas actuales de reactivación/defaults fuera de esa reparación.

### Baja/revocación local: nunca subordinada al audit

Al recibir requestDisconnect, marcar inmediatamente deny overlay + nuevo epoch de intención en memoria;
el estado visible y las comprobaciones de dispatch dejan de tratarlo como disponible. No esperar a Room
ni al actor de IO para cerrar la admisión de nuevas operaciones. Las operaciones ya admitidas conservan
sus reglas STOP/cancelación existentes: no prometer rollback de efectos previos.

Después, off-main y sin esperar proyección de auditoría:

- intentar el commit único de elección/outbox `selected=false` + evento `LOCAL_DISCONNECT_SELECTED`;
- intentar `runtime.disconnect` **aunque esa escritura falle**, en finally/ruta independiente;
- mantener deny overlay y publicar DISCONNECTED aun con error de persistencia o cleanup;
- tras commit durable, limpiar solo el overlay correspondiente a ese epoch, nunca uno posterior.

Room caído/corrupto o bridge que lance no impiden la baja: el outbox retiene su evento y el proyector
reintentará. Si **también falla el store local**, la baja sigue efectiva en el proceso, se reporta
persistencia no confirmada y se reintenta guardar false; no anunciar durabilidad inexistente. Si toda
escritura disponible falla y el proceso muere, no es posible garantizar una revocación durable: se
expone esa limitación, no se simula atomicidad ni éxito con SharedPreferences.apply. No introducir un
fallback que vuelva a usar una preferencia true vieja como autoridad durante ese proceso.

Un alta anterior cuyo runtime termine después de una baja no puede publicar true: epoch/CAS rechaza
su callback y se programa cleanup de esa activación obsoleta. No lanzar connect de nuevo por fallar
la proyección. El retry de guardar false conserva operationId, epoch y expectedRevision originales:
si hubo una alta posterior, su CAS falla/SUPERSEDED y no revoca esa nueva decisión. La limpieza tardía
solo puede alcanzar el handle/token de la activación que la causó. Si runtime no ofrece handles, se
serializan connect/cleanup/disconnect por generación de definición **antes** de permitir otra alta;
no ejecutar un disconnect viejo genérico contra una sesión recién conectada.
Si runtime no admite connect/disconnect concurrentes, serializar lifecycle pero aplicar
el deny overlay de inmediato; una operación de red previa puede tardar en terminar, sin permitir nuevas
herramientas entre tanto. No esperar Room antes del cleanup ni sostener locks del gate mientras espera.

`unregister` conserva false/has_choice aunque desaparezca la definición; el evento indica UNREGISTER,
no decisión humana. Reinstalar el mismo ID no resetea la elección por accidente. IDs retirados
`email`/`sms_draft` mantienen su exclusión explícita actual, sin importarlos como conectados nuevos.

### Repeticiones y carreras

Reintento con el mismo operationId solo es idempotente si conector, estado, razón y causalidad coinciden;
contenido distinto es conflicto. Dos requests iguales consecutivos con IDs distintos pueden devolver
UNCHANGED sin nuevo evento de **transición**, manteniendo su recibo de operación si hace falta dedupe
tras recreación UI. No convertir false sin elección previa en “unchanged”: debe fijar has_choice=true,
para impedir que connectIfUnselected lo reconecte por defecto. El evento debe identificar ese primer
false explícito. Un comando de default usa CAS has_choice=false; nunca gana contra una baja explícita.

Un resultado UNCHANGED no consume secuencia de transición, pero conserva expectedRevision y el
operationId del request. Un replay después de otra revisión se responde SUPERSEDED, nunca aplica de
nuevo. El último snapshot/recibo durable permite resolver retries inmediatos y los eventos históricos
confirmados se consultan en Room cuando estén disponibles; no añadir un historial creciente en Prefs.

## 5. Proyección Room y orden sin grants falsos

Proyector único/reentrante serializado toma snapshot de keys pendientes off-main, sin lock de política,
y lee en orden seq. En una transacción Room inserta por eventId con comparación de payload; conflicto
falla el lote y conserva fuente. **Después** del commit Room, otro commit Prefs elimina solo esas keys
si aún contienen el mismo payload. No borrar el estado/último snapshot ni decisiones nuevas. Si ese
cleanup commit devuelve false, no afirmar limpieza durable: Room ya tiene los eventos, y reapertura
puede repetir las keys antiguas idempotentemente. Error Room no consume ningún evento. Nunca llamar
Room con el writer lock Prefs retenido. No usar un watermark alto como único criterio de entrega.

Schema Room nuevo conserva `store_epoch`, `event_seq`, `event_kind=LOCAL_CHOICE`, selected, reason,
operationId/parentId y observed_at_ms. Índice UNIQUE(epoch,seq), orden por seq dentro de la generación
activa. Epochs distintos no se ordenan lexicográficamente; reset/migración identifica explícitamente
el epoch activo. El timestamp se muestra, pero no ordena causalidad ni autoriza la llamada.

`PersonalAgentRepository` ya no filtra connected=1 para decidir el estado más reciente. Puede consultar
el último evento de **cualquier valor**; si es false no adjunta un true anterior. La evidencia se llama
`connector_event` histórico de elección local, con **outcome=NULL y authorization=NULL**,
`evidence_class=local_choice` y hechos selected/reason/seq. No es un recibo de efecto externo ni
activa narración de WRITE exitoso por sí mismo.
No convertir local true en `allow`, `ask_approved`, permiso Android o acceso remoto confirmado.

**Lag de proyección:** incluso “último evento Room” puede estar obsoleto. En la admisión el gate captura
un `LocalConnectionObservation(epoch, seq, selected, operationId)` del mismo snapshot operacional que
usó para validar conexión; no es una nueva autorización. Recorder enlaza ese ID exacto si está proyectado,
o guarda referencia/indicador de historia pendiente, **sin fallback al último grant disponible**.
Si falta snapshot confiable, no adjunta grant. La decisión de efecto sigue siendo la admisión ALLOW/ASK
real y su revalidación; falta de un evento histórico no bloquea disconnect ni inventa autoridad.

No buscar por timestamp<=now: un reloj que retrocede no elimina una revocación. No mutar retrospectivamente
una admisión anterior cuando se observa una baja posterior. Mismo evento se enlaza una sola vez por
episodio mediante unicidad de entryId/refKind/eventId. Reconciliar la referencia pendiente solo añade el
hecho exacto observado, nunca una elección más nueva atribuida al pasado.

## 6. Bootstrap, compatibilidad, permisos observados y reset

La phase ACTIVATING guarda ownerProcessId (UUID de arranque) y operationId. En el modelo single-process
verificado, bootstrap único del proceso ocurre antes de aceptar requests; en ese arranque un owner de
una ejecución previa es abandonado. Otras fachadas creadas **dentro del mismo proceso** reutilizan el
coordinador y su mapa de operaciones: no convierten una activación lenta viva en abandonada por no verla
localmente. No basta un UUID distinto si se habilita otro proceso; en ese caso se requiere lease/IPC
verificable antes de recovery y la opción mínima queda bloqueada. No agregar timeouts arbitrarios para
declarar abandono. Añadir una prueba con dos fachadas, connect retenido por latch y bootstrap concurrente.

- Inicializar store local fuera de main antes de habilitar dispatch/defaults. Importar una vez el
  snapshot de preferencias connected_* válidas, conservando ausencia vs false explícito.
- Por cada elección importada crear `BOOTSTRAP_OBSERVED_LOCAL_CHOICE` con nueva seq y tiempo de
  observación, no fecha inventada de concesión. Un true legacy usa phase `LEGACY_OBSERVED`, no READY
  fabricado ni ACTIVATING nuevo: conserva la elección y los checks de disponibilidad existentes, sin
  ejecutar connect ni afirmar que observamos su handshake pasado. Leer bajo serialización de migración
  para que no compita con writes nuevos; después las mutaciones pasan por el store local.
- Crear baseline/outbox y marker en el mismo commit Prefs, conservando las claves de elección fuente.
  Si falla commit/proyección, reintentar con la identidad estable; nunca generar otro baseline como si
  fuera una nueva concesión. Un tipo legacy inválido se conserva y diagnostica; no se interpreta como
  true ni se marca bootstrap completo de ese registro.
- Mantener campos de política y permission_asked intactos. El constructor no mezcla migración audit
  con actionPolicies.lock; el bloque de migración Autonomy tiene contrato separado.
- Eventos Room legacy se conservan con namespace/origen LEGACY_UNKNOWN_ORDER y seqNULL. Empates UUID/
  timestamps no tienen causalidad recuperable; no fabricar una secuencia histórica “real”. La nueva
  observación bootstrap sirve de baseline local, no prueba que un antiguo grant siguiera vigente.
- Permiso OS o connectionAccessGranted revocado se refleja al calcular estado y en el gate actual.
  Una observación opcional `PERMISSION_OBSERVED_REVOKED/RESTORED` usa su propio tipo e identidad;
  no llama setConnected(false/true) ni crea LOCAL_CHOICE/grant. No inferir concesión por volver de settings.
- Room guarda un `projection_generation` generado al crear/restaurar ese destino, no un UUID de orden.
  Prefs conserva la generación de destino confirmada. Si cambia o falta, ignorar acknowledgments del
  destino anterior y reproyectar keys pendientes más el último snapshot por conector con IDs originales.
  El histórico ya drenado vive en Room/backup: si se perdió, declarar historia anterior no disponible,
  no inventarla ni reconstruirla desde la elección actual. Nunca llamar runtime.connect para reparar.
- Usar backup/reset de las preferencias existentes, con namespaces de generación definidos; no añadir
  otro archivo DB. Un reset explícito cambia storeEpoch y projectionGeneration y no importa outbox
  retirado. Las reglas de qué elecciones sobreviven al reset siguen las existentes y se prueban; no
  reactivar defaults ni borrar elección false por limpiar solo auditoría.

Memoria desactivada no convierte a la auditoría en autoridad: la elección operacional sigue necesaria
para controlar conectores. El enlace a episodios/narración respeta preferencias y eliminación vigentes;
no usar recuperación de eventos para reconstruir memoria olvidada ni copiar contenido de conectores.

## 7. UI y mapa de cambios mínimos

- `ConnectorStateStore`: fachada cacheada + store local/outbox; no Room directo en setConnected.
- `ConnectorRegistry`: coordinador por conector, epoch/deny overlay, lifecycle callbacks CAS; separar
  unregister del lock; snapshot de observación local en admisión sin cambiar autonomía.
- `NotificationBuffer` y Full `GmailConnector` reader de contactos: fachada compartida y deny overlay,
  preservando permisos y separación proactiva descrita en §3.
- `ConnectorsScreen` y `ModulesScreen`: requests asíncronos; ActivityResult y settings en main; feedback
  estructurado. No llamar el método síncrono desde Compose/launcher. Recreación conserva operationId,
  no un objeto ConnectorDefinition viejo. Una respuesta de permiso tardía se ignora si fue cancelada o
  superada por disconnect; permiso OS concedido no elimina un deny local.
- `ModuleRegistry.connectIfUnselected`: espera bootstrap y CAS de ausencia, sin reactivar false explícito.
  `ModulesScreen.LaunchedEffect` también llama installAndConnectUnselected: una coroutine Compose
  sigue en main si no cambia dispatcher, por lo que mover esa ruta a IO además del Switch. Los callers
  worker de CoreAgentRuntime/ScheduledTaskProcessor conservan semántica off-main. Startup de Application
  y otros editores de jarvys_connectors comparten la disciplina de candidates, no crean otra autoridad.
- Bridge/repository/migration Room: proyección idempotente con secuencia, enlace histórico exacto y
  consulta sin filtro connected=1/UUID temporal. No tocar gates para suplir una fila faltante.
- Arranque/maintenance: inicialización y reparación del outbox off-main con diagnóstico/retry, sin depender
  de que el usuario abra UI. No instalar WorkManager recurrente arbitrario; usar triggers existentes y
  trabajo único de reparación con su backoff, más intentos tras nuevos commits/reapertura.

No introducir cambios de producto en qué conectores están disponibles, defaults ALLOW/ASK/DENY,
permisos pedidos, OAuth, fallback de intents ni alcance de herramientas. Los runtimes de prueba que
implementan ConnectorConnectionPreferences siguen siendo compatibles; no sustituyen la prueba real
Android/Room del store nuevo ni del hilo UI.

## 8. Pruebas y mutaciones de aceptación

1. Con reloj fijo/retrocediendo: connect→disconnect→reconnect se ordena por seq; revocación posterior
   vence al antiguo true. Replay mismo operationId/id exacto no duplica; payload conflictivo se rechaza.
2. Commit Prefs falla antes/durante escritura y altera vista en memoria: no declarar durabilidad; alta no ejecuta
   runtime.connect, baja aplica deny+cleanup y comunica persistencia pendiente.
3. Room falla/inserta parcialmente/conflicto; commit Room seguido de crash/commit(false) de cleanup;
   backlog de muchos eventos: no pérdida/duplicados ni array global/caps nuevos, medir coste XML real.
4. Alta runtime falla: rollback false durable con causa; Room caído no impide rollback. Disconnect
   durante connect lento impide publicación true tardía y conserva cleanup. Retry false antiguo tras
   reconnect nuevo no lo revoca; cleanup de handle antiguo no desconecta sesión nueva.
5. Unregister concurrente y reemplazo de definición; prueba de locks que detecte Room/runtime IO bajo
   registry/policy lock. Sin deadlock al callback de un runtime y sin tocar definición ajena.
6. UI real con guard de main del bridge activo: botones, callbacks de permisos/settings y recreación;
   resultado pendiente/errores no se anuncian como conectado; baja no espera proyección audit.
   Notificaciones y contactos usan snapshot compartido aun con commitfalse; proactividad separada intacta.
7. Bootstrap false/ausente/true/tipo inválido, marcador de prefs fallido, dos instancias, default contra
   elección explícita, rollback abandoned y reapertura: sin concesión inventada ni reconexión de replay.
   Commitfalse de alta seguido de commit de política/cleanup y muerte: ACTIVATING no habilita; READY
   incierto solo puede provenir de runtime realmente retornado. Dos fachadas no promueven candidates
   ni declaran abandonada una activación viva.
8. Room lag: revocación operacional no proyectada, recorder no enlaza grant anterior; luego exact replay
   completa referencia. Dos llamadas del mismo episodio no duplican el mismo evento.
9. Permiso OS revocado/restaurado sin cambio de elección; grant histórico jamás sustituye autorización
   actual. Notification settings conserva su flujo especial sin suponer permiso tras abrir Activity.
10. Reset/generación, Room reconstruido, memoria apagada y entrada olvidada: eventos operacionales no
    reejecutan conexiones ni recrean episodios retirados. Cambiar projectionGeneration con ACK previo
    alto reenvía pendientes/últimos snapshots, no omite la baja ni inventa historial drenado perdido.
    Fallo total de storage no reporta éxito durable.

Mutaciones: separar commit state/event; consumir outbox antes commit Room; ordenar por UUID/reloj;
filtrar solo connected=1; quitar deny overlay; publicar callback antiguo después de disconnect; omitir
cleanup cuando falla log; habilitar default mientras bootstrap pendiente; enlazar último grant en lag.

**Validación actual:** lectura de fuentes y diseño; sin ejecución Android ni prueba de durabilidad.
La revisión independiente y el parche con pruebas reales son requisitos antes de declarar cierre S6.

### Revisión independiente del diseño (2026-10-26)

`s6_connector_audit` revisó la versión Prefs y cerró las observaciones de commit(false)/commits ajenos,
lectores Main/Full y proactividad independiente, epochs de callbacks/retry/cleanup, bootstrap/liveness,
projectionGeneration y phase LEGACY_OBSERVED. La firma setConnected no permite bypass del coordinador.
No quedaron bloqueos de diseño identificados para iniciar el parche acotado. Esto no valida el código
futuro: transporte de snapshot al gate/recorder, API UI, atomicidad/reapertura y tests Android requieren
revisión y ejecución independientes al implementar.

## 9. Adenda de API para la foundation pura

API conceptual revisada, no listado literal de firmas implementadas. El código concreto usa
`writeCandidate`/`writeProof` y `activationReturned`/`activationFailed`; §10 identifica el corte validado.

Estado: **contrato revisado; no constituye validación del adapter operativo**. La primera
implementación queda desconectada de producción hasta cerrar el protocolo de prueba durable
indicado abajo y el wiring completo de lectores, registry, UI, bootstrap y proyección.

### 9.1 Coordinador, snapshots e intenciones

Un `ConnectionStateCoordinator` por raíz canónica de filesDir y namespace posee el writer.
La foundation pura depende de `Storage`, reloj y generador de IDs, sin runtime ni executor
de lifecycle. Expone beginIntent, persistSelection y finishActivation con token/CAS; una
fachada futura process-owned aporta submit, executor y serialización de lifecycle. `DefinitionToken(connectorId, definitionGeneration)` identifica
la definición concreta; un callback no opera sobre una definición reemplazada por compartir ID.

`ReadView` es inmutable y contiene bootstrap (`INITIALIZING`, `READY`, `FAILED`), elecciones
confirmadas, intenciones vigentes y deny overlay. `ConfirmedChoice` conserva `hasChoice`,
`selected`, revisión, phase, último evento confirmado y último recibo. Las phases son `NONE`,
`LEGACY_OBSERVED`, `ACTIVATING` y `READY`. Las lecturas son rápidas, sin IO ni writer lock.
La elección efectiva requiere bootstrap READY, selected, phase READY/LEGACY_OBSERVED y ausencia
de deny; sigue sin acreditar permisos, disponibilidad remota ni autorización de herramientas.

- `beginIntent(Command): RequestToken` realiza sólo validación/publicación corta en memoria.
  Command contiene operationId, connectorId, DefinitionToken, expectedRevision,
  expectedIntentEpoch y tipo CONNECT/DEFAULT_CONNECT/DISCONNECT/UNREGISTER/SETTINGS_CONNECT.
  La baja y unregister aplican deny inmediatamente, sin esperar writer, runtime o proyección.
  DEFAULT_CONNECT conserva CAS de ausencia de elección. Un callback de permiso conserva su
  token original; no crea otra intención para vencer una baja posterior.
- En esa fachada futura, `submit(token, Lifecycle): CompletionStage<Result>` pertenece al
  proceso; cancelar un
  observador UI no cancela la operación aceptada. Lifecycle connect/disconnect se ejecuta fuera
  de locks y serializado por generación de definición cuando no existen handles independientes.
- El CAS previo a IO no basta: después del commit se publica mediante merge/CAS sobre el
  **último** ReadView. Puede añadirse el hecho histórico efectivamente comprometido, pero jamás
  reemplazar intents/deny por la copia capturada antes de IO. Una baja concurrente sigue vigente.
- Un callback antiguo no publica READY; su cleanup no puede alcanzar una activación posterior.
  Sin handles, termina ese cleanup antes de comenzar otra alta del mismo lifecycle.
  Un ActivationPermit emitido por el core sólo acredita sus precondiciones en ese instante:
  no concede permisos ni garantiza vigencia al dispatch; la fachada revalida token/gates.

`Result` expresa hechos ortogonales, no un único boolean de éxito:

| Campo | Valores / significado |
|---|---|
| disposition | ACCEPTED, UNCHANGED, SUPERSEDED, CONFLICT; ACCEPTED no significa efecto completado |
| activation | NOT_REQUESTED, NOT_STARTED, RETURNED, FAILED |
| persistence | CONFIRMED o UNCERTAIN para la mutación/recibo en cuestión |
| cleanup | NOT_REQUIRED, DONE o FAILED |
| snapshot / projectionPending | Estado efectivo observado e historia aún no proyectada |

Primer commit ACTIVATING incierto implica NOT_STARTED y cero llamadas runtime.connect, no
FAILED ni ACTIVATED_PERSISTENCE_UNCERTAIN. READY incierto después de retorno real combina
RETURNED + UNCERTAIN y mantiene deny. UNCHANGED con recibo no confirmado no promete dedupe
durable. Los resultados de §3 pueden derivarse de estos hechos; fallo de proyección no sustituye
el resultado de persistencia ni provoca rollback de una activación correcta.

### 9.2 Storage, reservas y candidatos inciertos

`Storage.load(): StoredImage` se usa off-main durante bootstrap único. `Storage.commit(Mutation)`
retorna CONFIRMED o UNCERTAIN; una excepción tras comenzar la escritura no prueba rollback.
Mutation especifica sólo claves a escribir/eliminar: elección, revisión, phase, recibo,
storeEpoch/high-water y pending event se editan juntos cuando corresponde. No clear ni reemplazo
de un mapa completo de preferencias. El adapter no publica snapshots ni convierte la vista
mutable SharedPreferences en confirmed state tras un fallo.

`Event` es inmutable: eventId=storeEpoch:sequence, epoch, sequence, connectorId, elección previa,
selected, razón, operationId, parentOperationId y observedAtMs. La igualdad compara payload
completo; un hash no sustituye esa comparación. Secuencia ordena dentro del epoch, no UUID/reloj.

`UncertainCandidate` es separado de `ConfirmedChoice` y `confirmedPending`:

- Un retry todavía vigente puede confirmar **la misma elección y evento juntos**, con identidad
  y payload originales. Nunca recicla secuencia para otro payload.
- Si fue superado, conserva registro exacto y reserva high-water; no reaplica elección/phase.
  No se promueve a lastChoice ni se envía como transición confirmada a la proyección normal.
- Un commit posterior sólo del evento no prueba que la elección anterior se comprometió con él.
  No rescatarlo como LOCAL_CHOICE confirmado ni reinterpretar todos los eventos como solicitudes.
- Reopen confirma bytes existentes, no atomicidad histórica ni el resultado comunicado por un
  commit anterior. La mera presencia de una pending key no promueve un candidato incierto.
- La retención conservadora puede mantener registros sin resolver; no se promete historia completa,
  coste acotado ni descarte por tamaño. Tampoco se añade ahora un tipo diagnóstico público.

**Gate previo al adapter operativo: prueba durable de procedencia.** El formato inicial debe ser
seguro como candidato por defecto. Un marcador escrito sólo después de commit(false) es insuficiente:
un editor ajeno podría persistir el candidato antes de ese marcador. La prueba de confirmación sólo
puede añadirse después de un commit inicial realmente confirmado; un crash antes de esa prueba puede
mantener incertidumbre conservadora. El protocolo exacto, sus fases de escritura y recovery deben
cerrarse mediante revisión y tests antes del wiring. La foundation modela las pruebas y candidatos
por separado; no afirma que un modelo en memoria resuelva esta persistencia. No se introduce un
nuevo schema diagnóstico ni autoridad de permisos como atajo.

### 9.3 Proyección, acknowledgment y reset

`prepareProjection(targetGeneration): ProjectionBatch` captura exclusivamente pending confirmados
más últimos snapshots confirmados si cambió el destino. Batch conserva sourceEpoch,
targetGeneration y eventos exactos. `ProjectionPort.project(batch): ProjectionAck` ejecuta la
transacción Room idempotente; un conflicto de payload falla el lote completo. Ack identifica la
generación real y los eventos exactos insertados/verificados, no sólo un watermark.

`ackProjection(batch, ack)` valida epoch, generación y payload antes de eliminar exclusivamente
las pending keys correspondientes mediante commit Prefs. Cleanup incierto conserva pending en la
vista confirmada para replay. Nunca se llama Room mientras se retiene writer lock.

El fence de reset/reemplazo del destino es real y común a ambas rutas: orden único
**resetFence → writer**. ACK retiene resetFence desde la validación del destino hasta finalizar
el commit de cleanup. Reset/reemplazo se serializa con ese mismo fence durante el reemplazo,
no sólo al publicar después un nuevo token. Sus operaciones Room no retienen writer lock.
`beginIntent` no toma resetFence: una baja no espera mantenimiento ni IO. Un token de generación
sin esta serialización tiene una carrera check/use y no satisface el contrato.

`view.observation(connectorId)` devuelve la referencia exacta del ReadView ya capturado para
validar conexión en admisión; no vuelve a leer el coordinador entre el gate y la captura del ID:
epoch, sequence, selected y operationId. El recorder enlaza ese ID o registra referencia pendiente,
sin buscar un grant anterior ni sustituir la admisión real. Recuperar proyección no recrea episodios
eliminados ni efectos de conexión.

### 9.4 Orden de implementación y pruebas mínimas

1. Tipos, coordinator puro y fakes con fallos, sin activar ninguna fachada de producción.
2. Cerrar prueba durable de procedencia, adapter y tests de persistencia/reopen bajo revisión.
3. Integrar conjuntamente registry, lectores directos, UI, bootstrap y enlace exacto Room; ninguna
   etapa operativa admite raw prefs como autoridad ni setConnected(true) fuera del protocolo.

Además de §8, la foundation prueba: baja durante commit y merge sin pérdida de deny; primer commit
incierto con runtimeCalls=0; recibo UNCHANGED incierto; candidato superado conservado sin reuso,
pérdida ni promoción por load; retry vigente sin duplicar evento; callbacks/cleanup antiguos;
y ACK bloqueado por reset real hasta terminar cleanup. Tests de foundation no sustituyen tests
Android, atomicidad de SharedPreferences, UI main-thread ni el gate pendiente de procedencia durable.

### 9.5 Propuesta de prueba durable en dos commits

**Revisión estática conforme; protocolo aún no implementado ni probado en SharedPreferences.**
Esta propuesta concreta el gate de §9.2 sin convertir la foundation en un adapter operativo.

1. El primer commit escribe conjuntamente elección/evento/phase y su versión, siempre como
   candidato seguro por defecto. Sólo un retorno TRUE real permite producir prueba de esa
   mutación. Retorno false/excepción incierta jamás produce prueba por inferencia.
2. El segundo commit escribe una prueba explícita ligada al payload exacto del evento,
   epoch/sequence, revisión y elección/phase/version de mutación efectivamente confirmadas.
   No vuelve a aplicar elección ni phase. No basta un hash ni una referencia que pueda apuntar
   a otra versión mutable. Si este commit devuelve false y un editor ajeno persiste después
   esa prueba, sigue siendo veraz: el primer commit realmente retornó TRUE.
3. `PersistenceResult` distingue `choiceConfirmed` de `proofConfirmed`. Una baja puede tener
   elección durable y prueba pendiente; su deny/cleanup no espera esa prueba. Para alta se
   adopta el criterio conservador de no iniciar runtime hasta confirmar la prueba, además
   del CAS y los gates actuales. No confundir ACCEPTED con activación completada.
4. Un crash entre primer TRUE y prueba conserva incertidumbre, aunque se hayan escrito bytes.
   Una prueba histórica sólo acredita su versión exacta: al bootstrap, la elección CURRENT
   debe coincidir con esa versión y su prueba para obtener el crédito correspondiente.
   Una prueba antigua true no acredita un candidato actual ni elimina deny posterior.
5. Si una elección fue superada, una prueba tardía sólo puede acreditar la historia original
   cuando consta primer TRUE. Nunca reaplica choice/phase. Si el primer resultado fue incierto,
   no se reescribe una elección superada para fabricar confirmación; se conserva el registro
   incierto y su reserva. El writer único serializa las mutaciones de elección.
6. READY es una mutación operacional distinta de ACTIVATING y necesita identidad de versión
   propia aunque no cree un segundo evento de elección. La prueba de ACTIVATING no acredita
   READY durable. Las confirmaciones de phase y su prueba se distinguen de las del evento;
   no se reutiliza la revisión/proof de otra phase para habilitar disponibilidad. Un READY
   incierto mantiene el resultado RETURNED + incertidumbre y deny definidos anteriormente.

El coste incluye un segundo commit de prueba por mutación que requiera confirmación acreditada;
no se promete un único IO para todo el protocolo. Se conserva atomicidad de elección+evento en
el primer commit. Antes de wiring se requieren tests de crash entre fases, commits de política
ajenos entre ambas escrituras, segundo commit false seguido de persistencia ajena, prueba vieja
junto a candidato nuevo, prueba histórica tardía tras baja y READY sin prueba propia. Ninguno
de estos escenarios puede habilitar runtime por mera presencia de un evento o proof ajeno.

### 9.6 Precisiones de revisión de la foundation

Estas reglas completan la API; no implican wiring ni validación del adapter:

- La revisión factual avanza si una mutación antigua realmente se confirmó mientras llegaba
  otra intención. Su publicación conserva la intención y deny más recientes mediante merge;
  no oculta el hecho confirmado ni restaura el snapshot antiguo completo.
- Un token cuyo expectedRevision ya no coincide devuelve SUPERSEDED y NOT_ATTEMPTED para
  persistencia. No existe rebase implícito del token ni reaplicación automática de la decisión.
  Una nueva intención explícita puede capturar la nueva revisión por el protocolo habitual.
- Las revisiones reservadas tienen high-water monotónico separado de la revisión factual
  confirmada. Un intento incierto no permite reutilizar su revisión/identidad para otro payload.
- Retry de resultado inicial desconocido conserva el mismo CAS, mutación y secuencia; si ya no
  es vigente no escribe. Si consta primer commit TRUE, retry sólo intenta su prueba exacta,
  sin repetir el commit de elección ni alterar choice/phase. Esto también permite acreditar
  historia confirmada luego superada, pero nunca habilitar su estado viejo.
- READY de la definición generation A no satisface un connect para generation B aunque
  connectorId y selected coincidan. La nueva definición requiere su propio runtime.connect
  y protocolo de activación; no se devuelve UNCHANGED usando READY de otro lifecycle.
- La generación de destino observada no equivale a la última generación cuyo acknowledgment
  se confirmó durablemente. Restore conserva ambas distinciones. Si el destino/ACK durable es
  desconocido, se reproyectan los últimos snapshots confirmados además de pending confirmados;
  no se infiere entrega por recordar un target observado.
- Si el reemplazo del destino tiene resultado incierto, queda bloqueado el ACK del destino
  antiguo. El fence no puede desbloquear cleanup antiguo sólo porque el reemplazo no reportó
  éxito; primero debe establecerse con certeza cuál destino/generación permanece vigente.

### 9.7 Límite histórico del corte puro `12f81df7`: reapertura no es recovery completo

La foundation actual prueba retry exacto dentro del mismo proceso, pero **no soporta reanudar
un candidato vigente tras reapertura**: el epoch de intención restaurado invalida el request
anterior. Esa ruta devuelve SUPERSEDED, conserva el candidato/reserva y no ejecuta IO ni runtime.
Es un comportamiento restrictivo del corte puro, no una recuperación operacional terminada.
El snapshot restaurado no acredita soporte completo de bootstrap, import o reparación.

El import de baseline legacy también queda explícitamente no soportado/rechazado por este core;
no se convierte una preferencia antigua en prueba confirmada mediante defaults del modelo.

Antes del wiring, adapter/bootstrap deberán implementar y probar recuperación con identidad exacta:
ACTIVATING abandonado se resuelve a false sin runtime.connect; retry de baja y retención incierta
no pierden registros ni reactivan decisiones superadas. No añadir un reattach genérico de true
que vuelva a conectar automáticamente para eludir SUPERSEDED. La prueba durable, la importación
legacy y esa recuperación siguen siendo gates abiertos; los tests del core no los cierran.


## 10. Checkpoint histórico de foundation aislada `12f81df7`

`ConnectionStateCoordinator` y sus 29 pruebas constituyen una base pura sin caller de producto.
Compilaron main/tests Full y Play; 29/29 por sabor, cero fallos/errores/skips, además de ejecución
standalone Java 8/JUnit. [Resultados y hashes](S6-connection-foundation-test-results.json).

La revisión independiente corrigió factual revision/mutation frente a proof histórico, CAS sin rebase,
replay de payload completo, deny concurrente, generación de definición, identidad de destino/ACK,
reemplazo de destino incierto, conflictos al restaurar y obligaciones de cleanup. Las pruebas cubren
esos casos; no prueban SharedPreferences, Room, UI ni lifecycle real.

No existe todavía adapter durable ni owner por raíz conectado a producto. Importación legacy y
recuperación cross-process quedan bloqueos explícitos antes del wiring: el candidato de reapertura
se retiene y no se reautoriza automáticamente. La siguiente etapa debe implementar y probar esos
protocolos; este corte no cierra conexión/outbox ni S6 y no modifica permisos.


## 11. Checkpoint histórico aislado de adapter y bootstrap

El adapter implementa candidate/proof separados, codec versionado estricto, claves namespaced y
clave legacy compatible de elección `connected_<id>`, sin borrar políticas ni preferencias ajenas. La conversión Base64 usa la API Android disponible en
el mínimo soportado; el codec puro trabaja con bytes UTF-8 estrictos, longitudes y EOF comprobados.
El owner por raíz se adquiere fuera de main antes de canonicalizar; snapshot/encolado no hacen IO.
Cancelar un observador no cancela el bootstrap compartido.

Bootstrap usa planes opacos y orígenes tipados. Importa elección legacy acreditada como observación,
no como autorización ni llamada a runtime. Reintenta la misma identidad sólo si source/version/CAS
siguen exactos. ACTIVATING abandonado y READY sin prueba propia se recuperan mediante una nueva
baja local tipada, conservando el candidato original incierto; no se atribuye a un usuario nuevo.
Una reparación/importación incierta mantiene INITIALIZING global y devuelve INCOMPLETE, sin permitir
defaults ni acciones nuevas. No hay READY parcial para operaciones cuya persistencia está pendiente.
Un valor legacy inválido se conserva con diagnóstico/deny del ID: no bloquea indefinidamente los
registros válidos ya acreditados y sólo una elección explícita válida puede repararlo. Retirados
email/sms_draft quedan excluidos también cuando sus claves anteriores ya no existen.

Resultado: cinco clases, **67/67 Full y 67/67 Play**, cero fallos/errores/skips. Incluye 30 pruebas
foundation, 15 bootstrap, 13 storage con modelo de fallos, 2 boundary SharedPreferences y 7 owner.
Además se repitieron los 2 métodos boundary en SDK24 y SDK34: **4/4 por sabor**, separados del bloque
anterior (no son ocho métodos nuevos). Main/tests compilan y Room schema5 no cambia.
[Resultados aislados](S6-connection-adapter-test-results.json) ·
[Compatibilidad boundary](S6-connection-android-compat-test-results.json).

La revisión y el preflight detectaron/corrigieron: confianza en proof visible sólo en caché tras
commit fallido, pérdida del header conocido durante retry, estado READY histórico frente a candidato
posterior, ausencia de tombstones retirados y conversión incorrecta de NOT_ATTEMPTED a UNCERTAIN en
cleanup. La aserción de rechazo sin IO se mantuvo; el intento fallido previo quedó separado.
El selector Bootstrap se añadió tras detectar que el primer preflight no lo había incluido.

Límites: el modelo de memoria/disco simula fallos/piggyback/reapertura; Robolectric prueba la frontera
SharedPreferences, **no un crash real ni fsync de dispositivo**. Destino del adapter aún fijo; reset y
revalidación Room reales requieren fence integrado. No hay poda activa: deben definirse keep-set,
identidades de replay y medición del backlog antes de wiring para no convertir prefs en un historial
permanente. Registry/UI/readers continúan sin usar esta fachada. No PASS conexión ni S6, sin APK.
[Plan de proyección pendiente](S6-connection-projection-plan.md).


## Proyección Room y retención — checkpoint focal posterior

Destino real con generación durable en `storage_meta`, evidencia privada por instancia y fence
compartido; UNBOUND no inventa generación. Bootstrap y baja local no esperan Room ni su fence.
ACK sólo tras transacción exacta e idempotente; payload distinto aborta todo el lote. Cierre/apertura
invalida tokens aun con la misma generación durable; handles simultáneos legítimos no se confunden.
Migración aditiva 5→6 preserva filas legacy sin fabricar orden/proof; migraciones 3→6 comparten SQL
entre SupportSQLiteDatabase y SQLiteDriver. Schema1–5 permanecen byte a byte intactos.

Poda atómica con snapshot cacheado versionado y keep-set: current, último recibo, snapshot confirmado
para proyección, incertidumbres y dependencias. Conserva floors sin convertirlos en proof. Un commit
posterior materializa candidatos que antes fallaron sin bytes, sin inventar current/proof. Sin ACK o
con incertidumbre el backlog puede crecer; no es un historial confirmado permanente ni costo constante.

Full **140/140** y Play **140/140**, cero fallos/errores/skips: 280 ejecuciones, 138 métodos nominales,
nueve clases. Dos métodos boundary se ejecutan en SDK24/34 y cuentan como cuatro casos por flavor.
Main/tests compilan; KSP6 generado. [Resumen seguro](S6-connection-projection-test-results.json).
Primer run conservado: 138 casos, dos fallos M0. Detectó la entrada SQLiteDriver faltante de migración
y el rechazo indebido del segundo handle para verificar FKs; se corrigió producto, no se redujeron
asertos. Rerun completo de nueve selectores PASS. Revisión estática y de evidencia independiente.

Alcance: Room/Robolectric y simulación de persistencia incierta, no crash/fsync de dispositivo. No
activa Registry/UI/readers ni borra aún su enlace histórico de grant; esos cambios deben entrar juntos
con observación causal, lifecycle y callbacks protegidos contra ABA. Sin suite global, lint, APK,
PASS S6 o equivalencia Engram. Publicación del fixture rechazado y decisión `linux_status` pendientes.
