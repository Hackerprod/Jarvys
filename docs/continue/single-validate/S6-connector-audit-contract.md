# S6 — revisión independiente de auditoría y eventos de conector

Fecha: 2026-10-26. Inspección de `992d266d` y árbol compartido. **Contrato propuesto, no parche ni PASS Android.**
Alcance: `connectors/Autonomy.kt`, `ConnectorRegistry.kt`, `ConnectorsScreen.kt`, puente Room,
`PersonalAgentRepository` y pruebas focales. No SDK, credenciales, publicación ni efectos externos.

## Hallazgos comprobados

### A. Migración de auditoría puede borrar datos no importados

`SharedPreferencesConnectorAutonomyStore.migrateAuditToRoom()` usa `readAudit()`, cuyo `runCatching`
convierte JSON malformado en lista vacía. Después elimina `autonomy_audit` y marca la migración completa.
Un elemento no objeto se omite; los campos `optString`/`optLong` aceptan registros incompletos. Dos IDs
vacíos colisionan en `INSERT OR IGNORE`, y el origen también se elimina. Estos caminos pierden datos.
El modelo legacy normal genera UUID (`AutonomyAuditRecord.id`) y serializa todos sus campos: no existe
necesidad probada de aceptar IDs vacíos como un registro válido. No inventar IDs aleatorios al reintentar.

Las inserciones son transacciones individuales. Un fallo a mitad conserva el origen y el flag sin
marcar; los IDs estables hacen el replay idempotente, pero no se valida que un ID existente tenga el
mismo contenido. `commit()` del borrado/flag no se comprueba. El `runCatching` del constructor oculta
fallos sin diagnóstico y no hay retry de migración en append/read de esa instancia.

### B. I/O Room bajo lock de política y en UI

Constructor y `recentAudit()` ejecutan Room dentro de `actionPolicies.lock`. `ChatDatabaseBridge.execute`
espera a su executor; no se debe extender el lock de autorización sobre esa espera. No se afirma un
ciclo de deadlock reproducido: el problema comprobado es el orden de locks y bloqueo prolongado.

El puente prohíbe llamadas bloqueantes desde main thread. `ConnectorsScreen` llama `registry.connect`
desde callbacks de permiso/onConnect; `ConnectorStateStore.setConnected` invoca inmediatamente
`recordConnectorStateEventSync`. La primera conexión cambia SharedPreferences y después puede lanzar
por ese guard. Reintentar ya encuentra `wasConnected=true` y omite el evento. La inicialización del
store de auditoría desde UI también puede fallar silenciosamente su migración por el mismo guard.
Las pruebas JVM deshabilitan el guard habitual y por sí solas no prueban que UI sea segura.

### C. Historial de conexión incompleto y grants obsoletos

`setConnected` registra solo false→true; no registra desconexión. En `activate`, la preferencia cambia
antes de `runtime.connect`; si este falla, el rollback false tampoco queda registrado. Una elección
local de conexión no demuestra handshake remoto ni permiso OS vigente.

`PersonalAgentRepository` busca el último evento **filtrado por connected=1**, de modo que una
revocación posterior sería ignorada incluso si se empezara a guardar. Adjunta el grant al crear intent,
antes de la admisión efectiva; ese dato sólo puede ser histórico, nunca autoridad de la llamada.
La evidencia del gate real sigue siendo la única autorización por invocación.

La consulta ordena timestamp e ID UUID. Dos eventos con mismo timestamp pueden devolver un grant
anterior como estado reciente. Cambiar sólo el filtro a “último evento” no arregla ese empate.

## Parche mínimo recomendado: separar dos bloques

### 1. Migración audit, sin cambiar producto ni permisos

1. Parser puro estricto del array completo: JSON válido, cada elemento objeto, campos/tipos requeridos,
   ID no vacío. Cualquier fallo conserva **el string original entero** y no pone flag; diagnóstico
   estructural sin contenido privado. No conservar solo los elementos que se pudieron parsear.
2. Migrar lote completo en una transacción Room, fuera de policy/STOP locks y fuera de main thread.
   ID ya existente sólo es replay si los campos coinciden; conflicto conserva origen y falla la
   transacción. Repetir el lote no duplica filas.
3. Después del commit Room, borrar el origen y marcar la migración; comprobar resultado del commit de
   preferencias. Un fallo entre ambos pasos debe poder repetir de forma idempotente sin pérdida.
   `SharedPreferences.commit(false)` puede cambiar la vista en memoria aunque falle disco. Después
   de importar todo a Room, ese fallo significa limpieza no confirmada, no pérdida de auditoría: la
   copia Room ya es durable. No prometer rollback del source ni encadenar restores que también fallan.
   Un solo commit remove+flag es suficiente tras import durable; el reinicio puede repetir el origen
   antiguo idempotentemente. Distinguir import completo de cleanup confirmado en el resultado.
4. Serialización de migración por ámbito de preferencias con lock dedicado, nunca policy lock.
   Retomar migración fallida en acceso posterior off-main; no declarar lista vacía definitiva porque
   la migración estuvo pendiente. `recentAudit` sólo usa lock para fallback en memoria, no para Room.
5. Mantener límites por registro ya definidos y lectura SQL con LIMIT solicitado; no truncar historial.
   La exclusión legacy de conectores retirados debe seguir siendo explícita y probada.

Una interfaz interna de destino de auditoría facilita tests de fallo/retry sin SDK, sin sustituir la
integración Room real requerida al validar. No usar fire-and-forget como prueba de migración completa.

### 2. Eventos conexión, contrato durable antes de implementación

- UI debe solicitar operación off-main y recibir éxito/error sin relajar el guard del puente. Botones
  repetidos, permiso denegado, rollback de connect, desconexión y actividad recreada necesitan pruebas.
- Definir el evento como cambio de elección/estado local; no llamarlo grant remoto comprobado. Registrar
  ambos valores, incluyendo rollback, con un orden durable, y preservar revocación aun si falla el log.
  Jamás impedir desconectar para poder guardar evidencia; la pérdida de evidencia debe quedar explícita.
- Preferencia y evento en stores distintos requieren recuperación/outbox o estado authoritative en Room
  con proyección compatible a preferencias. Reordenar dos writes no consigue atomicidad por sí solo.
- Al enlazar evidencia, consultar el evento más reciente aplicable de cualquier estado y adjuntar grant
  sólo si es connected; o adjuntar transición tipada sin inventar autorización ALLOW. Guard histórico y
  decisión actual son hechos separados. Evitar repetir el mismo evento por cada call del episodio.
- Para empates, `rowid DESC` sirve de parche local para orden de inserción en una tabla append-only sin
  reconstrucción/VACUUM, pero no debe presentarse como identidad durable universal. Esta tabla tiene
  TEXT PRIMARY KEY; SQLite advierte que rowid sin alias INTEGER PRIMARY KEY puede cambiar con VACUUM.
  Una secuencia persistente explícita es el contrato más robusto; la migración no puede reconstruir con
  certeza la causalidad histórica perdida entre UUID/timestamps empatados. No inventarla.
  Fuente: [SQLite: Rowid Tables](https://www.sqlite.org/rowidtable.html).
- Revocación OS observada no es llamada a setConnected; no confundir preferencia local, permiso OS y
  conexión remota. El estado calculado actual y revalidación del gate siguen bloqueando dispatch.

## Pruebas mínimas antes de cierre

Auditoría: JSON malformado; elemento no objeto; campo/tipo ausente; ID vacío; duplicado idéntico vs
conflictivo; fallo Room en mitad del lote; crash después Room/antes preferencias; commit preferencias
fallido; dos instancias concurrentes; retry posterior; 200 registros y paginado; conectores retirados;
ningún Room bajo policy lock; llamada UI no bloqueante; error de migración no borra origen ni pone flag.

Eventos: connect/disconnect/reconnect con reloj fijo; connect runtime falla y rollback; Room falla en
alta y baja; repetición y carrera concurrente; permission revoked; evento antiguo no autoriza una llamada;
no falsificar connected tras un rechazo; no deadlock unregister (hoy posee registry.lock); consulta no
elige grant anterior a revoke; conservación tras reapertura/migración; timestamps que retroceden se
tratan como orden observado, no como certeza causal por reloj.

## Validación realizada

Lectura de código y pruebas existentes. Se ejecutó una reproducción SQLite aislada con grant y revoke
en el mismo timestamp: filtro actual y arreglo ingenuo con UUID devuelven grant obsoleto; desempate de
inserción devuelve revoke. **No** es prueba Android/Room ni valida una solución durable de eventos.
`AutonomyAuditPersistenceTest` actual cubre 200 inserts/paginado, no migración defectuosa ni UI.
`EpisodeNarrationTest` cubre que un grant no vuelve narrable un WRITE fallido, no revocación/orden.

## Revisión del candidato audit (posterior al contrato)

El parche candidato separa el motor puro de migración, lote Room transaccional y cleanup de preferencias;
la inicialización ya no abre Room, los accesos no poseen policy lock, y UI carga en IO con error/retry.
La revisión detectó que JSONArray ignoraba cola y diferencias de leniencia Android; el candidato pasó
al esquema legacy de siete campos con validación léxica, claves únicas, enteros Long exactos y EOF.
Esto preserva datos originales ante errores, no normaliza ni trunca registros válidos.

Se prepararon `AutonomyAuditThreadingTest` (constructor con guard main-thread realmente habilitado,
Room real en worker, policy lock libre, migración/reapertura) y `ConnectorAuditHistoryUiTest` (expansión
de opciones avanzadas, carga off-main, error visible y retry exitoso sin acciones de conexión).
Ambas pruebas Android **no ejecutadas** por SDK pendiente. El cierre de auditoría no cierra el gap de
conexión/eventos descrito arriba: `setConnected`, su caller UI y el orden de revocaciones siguen aparte.
