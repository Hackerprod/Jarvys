# S6 — Procedencia de llamada Crew y contexto de inputs

Estado: costura de procedencia implementada, ampliación focal Full/Play PASS y revisión independiente completada para este bloque;
**sin opt-in Coding async**. El dispatch interno transporta y verifica ahora el binding preparado;
no se alteran permisos ni se habilita ejecución asíncrona nueva. Complementa `S6-coding-job-evidence-contract.md` §10 y
`S6-central-workspace-causal-contract.md` §9. No añade permisos, reintentos, timeouts ni superficies.

## 1. Qué prueba cada identidad

- **Raíz del assignment:** `CentralInvocationSource` guardada atómicamente con el assignment.
  Es procedencia histórica de la delegación, no el disparador de todos los comandos posteriores.
- **Llamada Crew:** identidad del checkpoint durable exacto y de una llamada concreta dentro de él.
  El worker real es la fuente operacional; no se inventa un batch Central para su transcript.
- **Contexto de inputs:** referencias plurales a inputs incorporados/conocidos en ese snapshot.
  No prueban causalidad exclusiva, comprensión del modelo, obediencia, consumo actual ni éxito.
  Un reply, un supervisor message o una corrección nativa pueden llegar en un mismo ciclo o iniciar
  otro. Ni el último input ni la raíz sustituyen esa distinción.

Una fuente Central opcional no es autoridad de ejecución. Un input de UI nativa conserva su control
operacional; no se le fabrica un messageId Central. No se consultan mensajes «latest» para atribuir.

## 2. Envelope inmutable por llamada, antes de ejecutar

Reutilizar el tipo `CrewCheckpointStore.ConfirmedSnapshot`: su `payloadSha256` corresponde al
payload de bytes confirmado por el store, no a una nueva serialización de objetos mutables.
La costura implementada devuelve esa confirmación directamente del mismo save/sync del checkpoint
STARTED mediante el retorno confirmado del store y `persistStarted`. No hace un `confirmDurable`
posterior que pueda seleccionar un checkpoint más reciente. La confirmación acredita esos bytes,
no que se ejecutara un efecto; los paths de observación/recovery conservan su semántica existente.

La costura debe enlazar una única vez la invocación viva con:

- identidad vigente de almacenamiento/owner y ejecución: session, bot, mission, cycle y scope;
- `payloadSha256` del snapshot confirmado;
- `callId` y nombre exacto de herramienta;
- contexto de argumentos descrito en §3;
- raíz histórica opcional y referencias de inputs descritas en §4.

`ConfirmedSnapshot` no acredita un DB UUID ni una generación Room vigente. `CrewInvocationSource`
no fabrica `storageGeneration` desde `g3`, el scope o la generación histórica del root Central.
La conversión a `CodingJobEvidence.CrewSource` queda pendiente hasta que journal/proyección capturen
su identidad de almacenamiento real; no usar el DTO de foundation como atajo de ese requisito.

En el snapshot debe existir **una sola llamada** con ese ID y nombre, y su lifecycle debe ser
**STARTED**, exactamente. INTENT, RESULT, NEVER_LAUNCHED e INTERRUPTED_UNCERTAIN no sirven. La fuente
actual muestra el orden `startPreparedTool`: STARTED → persistir checkpoint → `registry.invoke`.
La implementación conserva ese orden y comprueba el snapshot contra la llamada preparada antes de
su dispatch, rechazando IDs ambiguos o llamadas con resultado. La ampliación focal pasó en ambos sabores;
no equivale a validar la preparación/lanzamiento o journal Coding posteriores.

El envelope se conserva inmutable durante la invocación. No se actualiza cuando el checkpoint avance,
entre otro input o termine otro tool. No se resuelve nuevamente al encolar un job o al completarlo.
Confirmación/identidad fallida no produce witness válido; preservar el estado incierto existente sin
reinterpretarlo como permiso para repetir. Un resultado de decode o DTO construido públicamente no
es un witness de durabilidad ni de pertenencia a la invocación.
La vista pública legacy `ConfirmedSnapshot.loop` no constituye una frontera de inmutabilidad profunda;
como defensa los consumidores probatorios usan `loopForEvidence()`, reconstruido desde el payload privado verificado,
no esa vista ni una nueva serialización posterior a su modificación. Esta defensa y sus tests no
afirman haber demostrado una explotación de la implementación anterior.


## 3. Argumentos y ventana comparación–ejecución

El checkpoint actual serializa los argumentos mediante `CrewCheckpointStore.sanitizeJson`. Por eso
igualar el mapa raw con su representación guardada no demuestra identidad de ambos.

La costura conserva en RAM copias **profundamente inmutables** de los mapas completos RAW y
EFFECTIVE: claves, valores primitivos, null, objetos y listas anidadas. `coerceAndValidate` transforma
RAW en `PreparedToolCall.arguments` efectivos, mientras el checkpoint actual guarda RAW sanitizado.
La relación entre ambos se acredita en ese mismo PreparedToolCall, no revalidando más tarde contra
un schema/catalog «latest». La proyección guardada se compara con RAW; el dispatch, con EFFECTIVE.
La comparación de la proyección guardada es estructural: mismas claves, listas ordenadas y valores
JSON. Números se comparan por valor decimal exacto (`BigDecimal` a partir de su representación), de
modo que una diferencia de wrapper numérico o escala no confunda un round-trip JSON; esto no aplica
tolerancias aproximadas ni modifica los argumentos efectivos. Claves adicionales, texto diferente o
valores numéricos distintos rechazan la correspondencia. Se usa el mismo productor de sanitización,
no orden incidental de serialización ni igualdad de identidad entre mapa RAW y su proyección.

La misma copia EFFECTIVE profundamente inmutable llega a `execute`, con sus valores/tipos preparados
sin coerción posterior. El transporte exige la identidad de ese mapa y de la herramienta concreta
además del snapshot opaco: no valida una copia para ejecutar otra mutable. El binding se recibe
explícitamente en el overload de Registry; no se reconstruye por callId ni por schema «latest».
La revisión y las pruebas de la costura cubren la ventana comparación–ejecución; los resultados
Full/Play y los límites del harness están detallados en §7.

La proyección del mapa hacia el checkpoint usa el mismo productor y versión de sanitización que el
store. Sólo acredita la representación sanitizada. No se añade texto de comandos, secretos, copias
raw ni hashes persistentes de credenciales al envelope. La igualdad de argumentos raw es una
comprobación de la invocación viva, no una promesa de recuperarlos tras reiniciar.

Reinicio no crea un nuevo contexto de ejecución desde STARTED, no recrea autorización y no reenvía
la herramienta. La recuperación futura del job conserva su incertidumbre conforme a su journal.

## 4. Referencias plurales y cobertura honesta

Derivar las referencias exclusivamente del **mismo snapshot confirmado**:

1. `appliedIncomingIds` delimita IDs incorporados. Inputs aún en mailbox/pending no se convierten en
   incorporados por estar disponibles en otra estructura.
2. `loop.incorporations` aporta `CrewInputBinding` para replies/correcciones, sus identidades,
   digests tipados y `firstIncorporationCycleId`. Es historia acumulada, no una lista de causas del
   ciclo actual.
3. `incomingMetadata.provenance` de los turnos guardados aporta el canal de inputs de conversación,
   incluido supervisor message/TASK, que no debe confundirse con el mapa anterior.

Cada referencia contiene sólo identidades operacionales y evidencia tipada disponible: input ID,
canal, source kind/source ID cuando existan, ciclo de primera incorporación cuando esté probado y
los digests correspondientes. No copia el body ni concede autoridad. No completa campos ausentes
mediante el requestId del assignment, la correlación o una consulta temporal a Room.

En particular, `ConversationInput.Provenance.contentSha256` puede identificar la **correlación** con
una pregunta, no el texto del supervisor. No se renombra a hash del input. Si se necesita un hash de
transporte guardado, `checkpointTransportSha256` identifica precisamente el contenido sanitizado que
está en ese snapshot; se distingue del digest de payload/entrega original del binding.

Compacción o formatos legacy pueden conservar un applied ID pero perder su metadata detallada.
Representar esos IDs como `unresolvedInputIds` y cobertura **PARTIAL**; no omitirlos silenciosamente.
PARTIAL no autoriza afirmar contexto exacto completo ni rellenar una atribución Central. COMPLETE
significa al menos una referencia tipada conocida por cada applied ID, no descubrimiento de todos
los canales posibles. Si un ID conserva dos canales, se guardan ambos sin fusionar sus afirmaciones.
Incluso cobertura completa significa referencias conocidas en el snapshot, no que todas causaron el comando
ni que sigan presentes en el prompt del modelo después de compactar.

No introducir ahora un ledger universal de causalidad por turno. Si un uso posterior exige demostrar
el input que disparó una inferencia concreta, necesitará un envelope de model-turn específico y una
revisión adicional; este contrato no hace esa afirmación.

## 5. Etapa coherente y límite de habilitación

1. Costura CrewManager → Runtime → Registry implementada con owner exacto y orden
   STARTED/persistencia/dispatch. Revisar y cerrar sus pruebas reales antes del siguiente opt-in.
2. Witness y extractor tipado incorporados con transporte explícito y pruebas de pertenencia,
   snapshot e inmutabilidad. Las pruebas puras no sustituyen la validación Full/Play de este wiring.
3. Sólo después, conectar la reserva/journal Coding schema 3 y sus fronteras de preparación/lanzamiento
   según el contrato Coding. Falta todavía prueba durable terminal, lease/liveness y reconciliación;
   no habilitar ASYNC submission/completion parcialmente ni llamar OK al enqueue.

Los clones ordinarios de Registry conservan el provider de su ejecución. `forDelegatedAgent` lo
elimina: un `delegate_subtask` hijo sin checkpoint propio permanece legacy, sin atribución heredada
ni claim de que el checkpoint del padre prueba sus llamadas. Esto tampoco habilita Coding async.

La ausencia de una fuente Central válida mantiene procedencia operacional explícita sin inventar
memoria OFF. Los estados legacy/desconocidos son distinguibles. Ninguna etapa cambia permisos ni
convierte la aceptación administrativa de una delegación en misión o comando terminado.
El audit técnico determinista de S6/SINGLE_SESSION §7.5A no se omite por memoria OFF: el gate de
memoria corresponde a nuevas memorias semánticas/narración, no a borrar intención/evidencia técnica.
El journal operacional permanece obligatorio. No introducir un flag global evidenceEnabled que
confunda esas superficies.

Para la etapa Coding posterior, la frontera de preparación debe cubrir también el cold start de
la factoría: `LinuxEnvironment.get` puede construir `RootfsInstaller`, que prepara/mueve directorios
antes de entrar en execute. PREPARATION_INTENT durable y la evidencia técnica started deben preceder
al backend/factory, no sólo al proceso ni al mkdir dentro de execute. No esperar Room desde locks
operacionales; probar una factory sintética que muta y exige ambos registros ya confirmados.
Esta dependencia no habilita el backend async en la etapa de procedencia.

## 6. Pruebas negativas y causales mínimas antes de opt-in

1. Dos inputs incorporados en un mismo ciclo conservan ambas referencias; ningún «latest» sustituye
   el conjunto ni se presenta una causa única.
2. Follow-up en otro ciclo conserva la raíz de assignment y distingue sus inputs posteriores,
   sin atribuir automáticamente el comando a la primera delegación.
3. Un input en mailbox/pending pero no applied queda fuera de incorporados.
4. Applied ID compactado sin metadata produce PARTIAL/unresolved, no cobertura completa ni una
   fuente inventada.
5. Call duplicado, nombre/scope/cycle/owner incorrectos y lifecycle distinto de STARTED rechazan el
   witness; probar también referencia transferida a otra invocación.
6. Fallo de confirmación/sync no entrega witness durable ni autoriza dispatch/replay por sí solo.
7. Un checkpoint posterior no cambia el envelope capturado; mutación anidada de argumentos entre
   preparación, comparación y execute no cambia lo ejecutado ni evita el rechazo. Añadir caso RAW
   que requiere coerción: checkpoint compara RAW y execute recibe EFFECTIVE original, nunca una
   coerción nueva contra un schema cambiado.
8. Corrección nativa conserva identidad UI/control y no recibe un messageId Central ficticio.
9. Digest de correlación del supervisor no se acepta como digest de su payload; proyección
   sanitizada y comparación raw en RAM no añaden secretos/hashes de credenciales persistidos.

La ampliación focal pasó en Full/Play y la revisión independiente del código y evidencia es conforme. Este
contrato, esos resultados y la validación previa de CentralWorkspace no constituyen PASS de
Coding async ni de su journal/recuperación futuros.

## 7. Aislamiento offline de la validación

Durante la revisión del primer focal (71 tests, 3 fallos) se identificó una ruta incidental de
resolución de presupuesto hacia el catálogo de modelos del proveedor. Es una consulta GET de
metadata, no un envío de prompts/bodies de conversación; la inspección no confirma que se haya
producido un envío de red. No tratar ese diagnóstico ni los resultados parciales como PASS final.

La fixture adopta proveedor fake/caché explícitos y un guard Java que rechaza DNS, sockets y
lanzamiento de subprocesos. Una excepción acotada al caller JDK permite consultar `File.canExecute`
(permisos, no lanzamiento), con test positivo y un test negativo de `ProcessBuilder`; no es un permiso
general de ejecución. Un fallo intermedio del bootstrap del guard se trató como problema de la
infraestructura de prueba, no como resultado funcional de Crew.

Estado focal: standalone **6 smoke PASS**; Gradle **Full 21/21 y Play 21/21 PASS** (15 Coding + 6 del
guard). La calibración standalone independiente del mismo focal también pasó **Full 21/21 y Play
21/21**. Ampliación standalone final: **Full 851/851 (79 clases) y Play 847/847 (78 clases)**,
1.698 ejecuciones, cero fallos/errores/skips y ambos launchers exit 0. No es una ejecución Gradle
global; la revisión independiente final confirmó los 157 XML y los 25 archivos de código/harness. Estos resultados prueban el alcance Java instrumentado, **no** una garantía universal de aislamiento de red a nivel
OS/JNI. No se requieren servicios externos para validar procedencia Crew; el focal no constituye
PASS del conjunto Crew ni de Coding async.
