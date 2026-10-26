# S6 — contrato de cutover operativo de conexión

Estado: cutover integrado con validación focal Full/Play (955 ejecuciones, cero fallos/skips).
No es PASS S6 ni paridad Engram. [Evidencia y límites](S6-connection-cutover-test-results.json).
No amplía permisos ni capacidades.

## Corte único

Sustituir juntos el writer de elección `apply`, lifecycle del Registry, callbacks UI, defaults,
lectores NotificationBuffer/Gmail y enlace causal de evidencia. No activar una segunda fachada
mientras un writer antiguo siga cambiando `connected_*`. Política y `permission_asked_*` conservan
su autoridad propia; una elección no sustituye permiso Android/OAuth ni autorización de operación.

## Fachada, bootstrap y baja temprana

- Un único handle de proceso, provisto por `FactoryInstallApplication`, se obtiene sin canonicalizar
  rutas, leer preferencias ni abrir Room en main. Wrappers de test inyectan el mismo handle; no crean
  ledgers independientes. Un provider ausente falla cerrado, nunca vuelve a leer raw preferences.
- Worker adquiere el owner canónico y arranca bootstrap. ON antes de READY devuelve NOT_READY y no
  abre OAuth/permisos. DEFAULT espera READY y exige ausencia real; UNKNOWN/invalid/false no son ausencia.
- OFF instala inmediatamente deny y stamp local, sin esperar writer, Room o runtime. Handoff al core
  y publicación READY son atómicos respecto nuevas bajas, mediante lock corto sólo de memoria.
  Callbacks, listeners, IO y actualización externa de estados quedan fuera de ese lock.
- Ledger OFF sólo se retira tras confirmación exacta del mismo stamp; resultado incierto no lo consume.
  No sobrevive muerte de proceso antes del commit: UI muestra pendiente/no confirmado, nunca durabilidad
  falsa. No se inventa otro protocolo durable cuando las preferencias están corruptas.
- Proyector independiente, coalescido, con trabajo único de reparación/backoff más triggers tras
  commits y reapertura. No abre Room desde la cola de bajas ni reconecta runtimes en recovery.

## Requests, permisos y lifecycle

- Capturar request opaco con ID, handle/generación de registro y CAS de elección/intención ANTES de
  pedir permisos/OAuth. Callback resuelve ese request, nunca crea uno nuevo ni rebasa CAS.
- UI puede conservar sólo ID opaco a través de recreación y resolverlo en el owner; proceso muerto o
  request sustituido devuelve ausencia, sin reconexión. Se retienen pendientes/inflight, no UUID eternos.
- Operación aceptada es propiedad del proceso. Cancelar observador/composición no cancela la operación.
  APIs síncronas sólo en workers; UI observa futures y publica resultados en main sin bloquearlo.
- Permiso denegado puede abortar únicamente el token actual sin intento de candidate IO/activación y
  con baseline factual/proof/deny exacto. Elimina ese token, conserva intent floor y nunca restaura un
  token/permit anterior. Intervención posterior, reset o cambio de registro impide restauración.
- Lane runtime por ID sobrevive a generaciones: serializa connect/cleanup, no la baja local. Cleanup
  obsoleto usa la instancia capturada y termina antes de que empiece una alta posterior; no callbacks
  genéricos tardíos que desconecten la nueva. No mantener locks Registry/lane durante preferencias/Room.
- `SETTINGS_CONNECT` conserva semántica existente: selección y runtime locales antes del grant,
  PERMISSION_REVOKED hasta acceso real, luego refresh sin repetir connect. No concede permiso OS.
- Registro nuevo no altera elección durable por ID ni ejecuta connect automáticamente. El código actual
  rechaza duplicate ID; reemplazo es unregister(false) y nuevo register. No introducir hot replacement.

## Admisión y observación causal

- Un snapshot validado por conector fija epoch, mutation/revision/intent, handle de registro, stamp de
  fachada y observación inmutable obtenida del MISMO ReadView. No comparar el View completo si cambia
  otro conector. Fijar antes de ASK/preparación y revalidar ese mismo pin antes/después del callback
  durable y de cada efecto; OFF→ON no hereda aprobación ni sustituye el pin.
- Admission transporta nullable Observation(connectorId, storeEpoch, sequence, operationId, selected),
  con igualdad por valor. No proviene de argumentos del modelo ni autoriza por sí misma.
- El intent no busca grants históricos. En la transacción de admisión del intent exacto se adjunta
  una fila connector_event por llamada, con outcome local_choice y authorization NULL. Hereda
  batch/run/attempt/action/process del intent; facts incluye observed_for_call_id. No deduplicar entre
  llamadas sólo porque observan el mismo evento. Connector conocido distinto rechaza toda la TX;
  desconocido puede tomar el ID exclusivamente del DTO local.
- Referencia epoch:sequence válida aunque proyección siga pendiente; nunca fallback al último true.
  at_ms es observación de admisión, no fecha inventada del evento original. Proyectar después no crea
  episodios ni resucita memoria olvidada.

## Pruebas antes de activar

Callbacks tardíos tras OFF/unregister/rotación; ausencia frente a false y bootstrap pendiente;
permiso denegado/abort frente a IO incierto; cleanup viejo bloqueado y nueva alta; listeners compartidos
sin rawprefs ni recursión Registry; proactividad independiente conservada; UI sin IO bloqueante ni
éxito al enqueue; settings pregrant; pin ABA durante ASK y callback Room; causalrows exactas, rollback
por mismatch, ninguna autorización ficticia y ningún episodio recreado por replay. Full/Play afectados,
revisión independiente y manifiesto del árbol final antes de afirmar implementación operativa.
