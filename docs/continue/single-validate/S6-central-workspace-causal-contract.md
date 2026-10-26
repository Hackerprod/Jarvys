# S6 — CentralWorkspace: admisión explícita y origen operacional de assignments

Estado: slice implementado; Full677/Play673 focales PASS y revisión independiente. No cierre global S6 ni opt-in Coding ASYNC.
Dependencia de `S6-coding-job-evidence-contract.md` §10. No cambia capacidades, permisos,
políticas, acciones, límites de respuesta ni autoridad de controles nativos.

## 1. Estado real y objetivo acotado

`CentralWorkspaceTool` sólo implementa execute2. Su clasificación por argumentos marca
create/delegate/deliver/reply/message como WRITE. Al ser un CoreTool nativo, el default execute4
admite ALLOW/native_contract antes de sus validaciones y devuelve un resultado sin recibo productor,
que termina UNCERTAIN. No es un adapter fail-closed de PolicyGatedTool, pero tampoco demuestra la
frontera real de cada mutación ni el significado de su resultado.

El callback `Backend.admission: Runnable` se usa antes de mutaciones, lecturas y divulgación, y dentro
de transacciones/monitores. Verifica token, Central y no-Crew. No reemplazar mecánicamente ese callback
por un writer Room: introduciría IO de bridge bajo locks y convertiría lecturas en efectos.

El slice debe cerrar conjuntamente:

1. Execute4 explícito, clasificación de efectos y recibos emitidos por los productores reales.
2. Fuente central inmutable del call, transportada explícitamente hasta el assignment.
3. Persistencia atómica del assignment con esa fuente operacional, independiente de memoria ON.
4. Pruebas de fallos, repetición y autorización actual, sin habilitar nada nuevo a Coding/tasks.

## 2. Dos callbacks distintos, no un Runnable ambiguo

El adapter conserva un guard puro/rápido de STOP, identidad de sesión y ámbito. Separar en el
contrato backend:

- `checkAccess()`: la validación existente, válida también para lectura y antes de divulgación.
  Dentro de locks debe ser exclusivamente validación local; sin bridge, listeners ni UI.
- `admitMutation(descriptor)`: callback tipado antes de la primera mutación real. Construye la
  decisión desde el contrato nativo efectivamente comprobado (Central, no-Crew, acción exacta y
  scope vigente), sin deducir ALLOW de un success/JSON. Persiste admisión fuera de locks de SQLite,
  workspace, Crew/cycle y STOP. Repeticiones del mismo descriptor son idempotentes; cambios de
  owner/acción no se fusionan. Si falla, no se entra en el efecto.
- `completeMutation(proof)`: prueba tipada local de lo que el productor confirmó. No recibe el texto
  de respuesta para inferir estados. Ningún fallo posterior borra la admisión ya ocurrida.

Después de admitir fuera de locks se adquieren los locks existentes y se revalidan identidad,
revisión y cancelación antes de mutar. Una revocación entre pasos sigue bloqueando. Si sólo queda
probada la intención, resultado conservador; no fabricar NOT_EXECUTED después de un posible efecto.
Los nuevos writers de admisión/completion de este protocolo no esperan a Room bajo monitores/gates.
No se afirma que el backend histórico ya sea globalmente Room-free: `appendDeliveredFile` tiene
su transacción existente bajo DELIVERY_ACTION_LOCK/token gate. Este slice no refactoriza esa ruta
ajena por conveniencia; coloca los nuevos writers fuera de esos locks y prueba esa frontera. Si la
integración revela una inversión concreta, se revisa separadamente antes de habilitarla.

## 3. Semántica mínima de recibos por operación

| Operación | Hecho que puede confirmar el productor | Lo que nunca acredita |
|---|---|---|
| create | Registro workspace creado o referencia idempotente a uno existente | Misión ejecutada |
| delegate | PREPARED/STARTED/BOUND exacto; sólo BOUND acredita aceptación local de ese bot/assignment | Trabajo terminado, build correcto o efecto futuro Coding |
| deliver | Entrega local concreta/recibo de adjunto persistido según gateway existente | Lectura por el usuario o envío externo adicional |
| reply/message | Estado durable del input exacto; accepted-local e incorporación son estados distintos | Consumo del modelo si sólo se aceptó mailbox; éxito de la misión |
| list/read/inspect/read_result/inbox/read_message | Lectura y sus marcadores operacionales existentes | WRITE externo por llamar a checkAccess o guardar recibos internos de lectura |

### API propuesta de receipt (acuerdo técnico antes de fuente)

Añadir a `EffectInvocation.Receipt`:

```java
enum CompletionSemantics {
  EXTERNAL_EFFECT, LOCAL_RESOURCE_CREATED, COORDINATION_ACCEPTED,
  LOCAL_DELIVERY_CONFIRMED, EXISTING_REFERENCE
}
enum EffectReferenceKind { WORKSPACE, ASSIGNMENT, WORKSPACE_INPUT, DELIVERY }
// Value inmutable: kind, scopeId, id. Sólo IDs operacionales, no rutas/texto/argumentos.
Receipt receipt(Outcome outcome, CompletionSemantics semantics, EffectReference reference);
```

El método pertenece a la invocación y sella el recibo con su owner opaco. El constructor legacy de
Receipt sigue siendo unbound y usa EXTERNAL_EFFECT sin referencia. `receipt(outcome)` conserva el
comportamiento actual. La referencia es metadata local, no autoridad ni un decoder de JSON de tools.

| Semántica | Combinación válida |
|---|---|
| EXTERNAL_EFFECT | Reglas actuales de outcome/admission; default compatible |
| LOCAL_RESOURCE_CREATED | OK + admisión + resultado success + referencia al workspace nuevo |
| COORDINATION_ACCEPTED | OK + admisión + success + referencia exacta; BOUND para delegate, aceptación local durable para input |
| LOCAL_DELIVERY_CONFIRMED | OK + admisión + success + referencia de entrega local confirmada |
| EXISTING_REFERENCE, fastpath | NOT_EXECUTED + sin admisión + referencia original; success administrativo permitido |
| EXISTING_REFERENCE, carrera | UNCERTAIN + admisión previa + referencia original; nunca nuevo OK |

La última fila cubre precheck sin resultado → admit fuera de locks → otra invocación crea el registro
→ la transacción descubre duplicate. No borrar admisión para fabricar NOT_EXECUTED, ni repetir la
mutación. El recibo acredita una referencia existente, no la ausencia de cualquier efecto previo.

`resolve` valida owner, snapshot de Admission y combinación semántica/outcome/flags; conserva la
metadata sólo si es válida. Un receipt foreign/inconsistente normaliza a UNCERTAIN sin conservar una
semántica confirmada falsa. Las copias de CoreToolResult preservan receipt, flags y demás metadata.

`EpisodeRecorder` añade facts desde el receipt normalizado: completion_semantics, referencia tipada
(kind/scope/id) y goal_verified=false para actos locales. No interpreta JSON del resultado. El
narrador distingue creación local, aceptación de coordinación, entrega y referencia existente de
trabajo completado; ningún OK anterior acredita objetivo/build/mission/Coding terminal. Añadir tests
que hagan fallar el cambio COORDINATION_ACCEPTED→misión terminada y duplicate→nuevo OK.

EXISTING_REFERENCE requiere una ruta demostrada sin nueva mutación: no basta encontrar la misma
key/ID. Un assignment PREPARED existente puede continuar con su primer markStarted/spawn/BOUND;
debe admitir ese trabajo y conservar la fuente original. Lo mismo ocurre con reply PREPARED o
message cuyo protocolo permite reintentar la admisión exacta al mailbox. STARTED/BOUND que retorna
sin acción nueva sí puede ser referencia existente, con el outcome condicionado a admisión previa.
Deliver puede crear staging/copiar/hashear antes del dedupe de attachment: ID igual no demuestra
NOT_EXECUTED; si hubo preparación admitida, conservar UNCERTAIN + referencia original.

Un duplicate terminal sin nueva acción devuelve referencia a la operación original, no un segundo
spawn ni una nueva evidencia de ejecución. PREPARED/STARTED sin BOUND no acreditan aceptación efectiva del worker;
conservar pendientes/incertidumbre conforme al protocolo vigente. Mantener key/payload/revisión y
estados inciertos/reintentos actuales. No ampliar reply a trabajadores detenidos ni convertir message
en corrección nativa.

## 4. Fuente central: minting antes de dispatch, no argumentos del modelo

Nuevo DTO local `CentralInvocationSource` (nombre provisional):

- storageGeneration;
- ownerCentralSessionId y sourceMessageId;
- sourceBatchId y sourceCallId;
- sourceRunId y sourceProcessId.

Minting sólo desde el checkpoint comprometido del call: `MainChatTranscriptStore.committedCall`
provee batchId/callId; el contexto de ejecución aporta owner message/run/process. Comprobar la
fuente completa contra el checkpoint de esa invocación y conservarla inmutable. Ni un JSON del
modelo ni un requestId aleatorio son fuentes alternativas. La fuente no concede permiso de efecto.

Extraer esta parte del recorder como proveedor de origen operacional reutilizable. El runtime actual
sí instala EpisodeRecorder para main+owner aunque memoria esté OFF; el repositorio decide si persiste
episodios. Obtener procedencia no debe depender de que ese recorder persista o esté habilitado. El resultado puede
transportarse por EffectInvocation sin generar filas de memoria. No capturar nuevamente un mensaje
más reciente al ejecutar el callback del backend.

`CentralWorkspaceTool.execute4` recibe esa fuente en el mismo contexto de invocación y la entrega
explícitamente al backend delegate. El requestId existente sigue identificando la petición del
runtime; no se renombra a messageId ni cambia su semántica.

## 5. Persistencia mínima: assignment + source en el mismo store operacional

`WorkspaceRegistry` usa SQLite operacional v1 y su onUpgrade actual rechaza migraciones. Añadir
migración aditiva v1→2: columnas nullable de la fuente anterior en assignment (o relación 1:1 dentro
del MISMO SQLite si las restricciones lo justifican). Filas legacy permanecen null; conservar todos
los IDs, textos, estados, revisiones y controles. No reescribir el journal global ni copiar texto a
la procedencia nueva.

`prepareAssignment` recibe el DTO ya validado y escribe assignmentId generado + fuente en la misma
transacción SQLite. Así no se necesita una reserva distribuida Room↔workspace para conocer el ID.
La reserva Room propuesta inicialmente en la adenda Coding queda reemplazada por esta atomicidad
operacional; Room sólo proyecta después, por identidad exacta y fuera del lock.

- Para una nueva delegación instrumentada, no separar INSERTassignment de INSERTsource. Fallo en
  cualquiera impide una fila parcialmente atribuida y no permite spawn.
- Duplicate de key/payload devuelve el assignment y su fuente originales. Si el call de retry es
  distinto, puede referenciar ese resultado idempotente, pero no sobrescribir/apropiarse su fuente.
  Los conflictos de request/payload existentes siguen siendo conflictos.
- Comparar generación vigente y tuple workspace/assignment/mission/bot en las fronteras actuales;
  reset no permite reutilizar una prueba de una generación anterior. No obtener una prueba nueva
  mediante un lookup por texto o por "último assignment".
- Sólo después de la preparación/binding operacional se ejecuta markStarted/spawn con las garantías
  actuales de no replay y cancelación del bot exacto. Un fallo de proyección de memoria no convierte
  un assignment BOUND en NOT_STARTED ni habilita otro spawn.
- Legacy sin fuente queda explícitamente no atribuido. No bloquear las capacidades existentes por
  ausencia de atribución histórica, ni inventar backfill a partir de originalUserRequest.

No se exige una memory row para persistir la fuente operacional cuando memoria está OFF. La
proyección a episodios y la narración son otra fase: respetan modo/ticket de memoria, tombstones,
fuente borrada, generación y owner. Un journal/assignment conservado no autoriza resucitar memoria
olvidada. Usar las identidades y protecciones de retirada existentes; este slice no crea un
subsistema universal nuevo de tombstones. La reparación de bookkeeping existente no es una narración nueva.

## 6. Conexión con el comando Coding real

El proveedor Crew comprueba `CrewCheckpointStore.ConfirmedSnapshot` exacto: executionSession,
bot, scopeIdentity, cycle, misión, callId/toolName pendiente/STARTED y payloadSha256 del fichero
confirmado. Relaciona ese comando con el assignment persistido y su fuente central del apartado 5.

Conservar dos calls: el call central de delegación y el call Crew de project_exec. No atribuir al hijo
el batch del capitán, no asumir que el digest del checkpoint es una revisión monotónica, y no leer
metadata mutable después de capturar la prueba. Crew sin assignment/fuente verificables conserva
journal operacional, sin episodio central inventado.

## 7. Pruebas necesarias y orden

Antes de activar Coding async:

- Registro real + MainChat checkpoint + CentralWorkspace execute4: fuente exacta y writer durable
  antes del primer efecto; failure writer impide mutación; STOP durante la separación de locks bloquea.
- Cada mutación real del backend tiene cobertura; lecturas/checkAccess no adquieren falsa autoridad.
- Los nuevos callbacks bajo SQLite/Crew lock sólo revalidan; test de otro hilo/bridge detecta inversión
  de locks de los writers nuevos, sin fingir que el delivery histórico ya carece de acceso Room.
- Duplicate PREPARED continúa primera aceptación con admisión; duplicate terminal sin efectos es
  referencia; delivery con staging antes de dedupe nunca se etiqueta NOT_EXECUTED por ID igual.
- Create/delegate/reply/message/deliver: recibo local correcto; fallos parciales conservadores;
  no aceptación→misión terminada ni doble conteo de retry/poll.
- Migración v1→2 conserva filas byte-significativas y legacy null; fuente+assignment atómicos;
  mismo key/payload no cambia owner; misma key con payload conflictivo sigue rechazada.
- Dos llamadas/mensajes/runs con nombres iguales no comparten fuente; memoria OFF conserva
  procedencia operacional y audit causal técnico, sin narración/semántica automática. ON sin backfill
  y ausencia de resurrección tras forget/reset siguen como requisitos del futuro opt-in Coding;
  este slice no declara validación universal de esas rutas.
- Crew exacto enlaza su call a la delegación original; checkpoint/cycle/bot ajeno se rechaza.

Primero revisar este seam y semántica de receipts; luego implementarlo como slice coherente y
probarlo. Los tipos Coding pueden prepararse aisladamente después del acuerdo, pero el opt-in
ASYNC sólo ocurre con journal, frontera de preparación, recovery y proyección completos.


## 8. Identidad física y frontera de la fuente (acuerdo de revisión)

`StorageGeneration.NAME = g3` identifica el layout, no una encarnación de la base. La fuente nueva
usa el UUID durable de identidad de la base actual y una evidencia privada del lifecycle del
provider, capturados junto al checkpoint comprometido antes del dispatch. Reutilizar esa identidad
no concede permisos de conexión ni convierte su evidencia en autorización de efectos.

La validación de vigencia y la inserción atómica de assignment + fuente comparten el fence del
provider, con orden de adquisición **lifecycle → workspace → SQLite operacional**. No ejecutar
bridge Room, listeners, callbacks de admisión persistente ni spawn dentro de esa sección. Una
comprobación de generación antes del lock, sin compartirlo con close/open, no cierra la carrera.
Las pruebas deben cubrir reemplazo antes de la sección y close concurrente durante la inserción.

El fence protege esa atribución operacional concreta, no todo el trabajo posterior. Una vez
persistida, la fuente es histórica e inmutable; spawn, entrega y coordinación conservan sus
fronteras de autoridad/cancelación existentes y sus estados inciertos, sin exigir que leer metadata
histórica mantenga una lease viva. Separar la sección de persistencia de la de spawn no debe
permitir replay: revalidar el estado/revisión bajo el lock y mantener el CAS de transición.

`SingleSessionReset` prepara el layout nuevo al arrancar y retira únicamente el almacenamiento
anterior; no implementa un reset destructivo en caliente de g3. No se afirma atomicidad global entre
un reset inexistente y todos los efectos. Si se añade ese reset, deberá participar en el fence del
provider e invalidar/cancelar owners, no limitarse a comparar la cadena g3.


## 9. Precisiones de implementación del slice CentralWorkspace

- `WorkspaceMutationContext` fija acción y workspace, conserva el witness operacional y separa
  `admitMutation()` (writer durable fuera de locks) de confirmaciones locales sin IO. Estas últimas
  pueden registrar metadata inmediatamente después del commit mientras el productor conserva su
  monitor; **no invocan Room, listeners ni callbacks de admisión**. Registry finaliza evidencia después.
- Backend legacy conserva su operación mediante sobrecarga conservadora: admite antes de entrar
  al backend, sin inventar un completion receipt ni cobertura completa. Success legacy queda
  UNCERTAIN. La ruta instrumentada valida argumentos y scope antes de admitir.
- La entrega usa `DeliveredArtifactCommit.inserted`, decidido en la misma transacción Room que
  inserta/coalesce el artefacto. No se infiere nuevo efecto mediante lookup previo o JSON. El retry
  después de staging conserva referencia existente con UNCERTAIN, nunca segundo OK de entrega.
- El witness usa identidad de instancia/generación Room real, no el nombre estático de directorio
  `g3`. Su lease protege únicamente assignment + source INSERT: anchor → workspace → SQLite.
  No se mantiene durante bridge, spawn o listeners, y no promete atomicidad global reset/efecto.
  `requireInvocationCurrent()` es un guard local de invocación, no reemplaza ese lease.
- El source guardado en assignment es **raíz histórica de delegación**, no causa exclusiva de todos
  los comandos futuros. Replies, supervisor messages y native corrections pueden incorporarse
  dentro de un mismo ciclo o reanimarlo. El checkpoint conserva incorporaciones históricas y otros
  inputs con provenance; no hay actualmente un enlace causal por model-turn a cada TOOL_CALLS.
  El futuro opt-in Coding debe conservar contexto de inputs exacto/plural del checkpoint y distinguir
  esa raíz de las contribuciones posteriores. Ningún campo `requestId` se convierte en message ID,
  ninguna corrección UI se atribuye a un mensaje Central inexistente y no se busca el último mensaje.
