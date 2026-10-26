# S6 — manifiesto de validación del checkpoint WIP

Fecha: 2026-10-26. Base remota: `42560900aa81c5a3c46b483052219d7b2ef2d936`.
**No es PASS S6 ni paridad Engram.** Compilación Full/Play y validación seleccionada completadas en la reanudación inferior. Código congelado inicial: `22c87d3d74db6537a5d38ca5797ad1b19a24c822`. Este manifiesto se añade en el checkpoint documental posterior, sin cambiar producción.

## Slice posterior: consulta scoped del narrador

Tras el checkpoint de validación completa seleccionada, el narrador pasa IDs exactos al SQL, con chunks sin truncación y una transacción de lectura. La cola global ya no se carga para filtrar en memoria. Empty/missing no llaman al modelo ni preparan prompt. Revisión independiente conforme; tres pruebas nuevas cubren filtros, orden entre chunks y exclusión de episodios fuera de scope.

Recompilación y rerun core: **Full 167/167 y Play 167/167 PASS, cero skips**. Esta tanda revalida core; no equivale a reejecutar los otros lotes del checkpoint anterior de 1.088 ejecuciones. Gate compartido de memoria sigue en diseño revisado, no implementado ni acreditado por este slice.

## Reanudación con SDK autorizado — 2026-10-26

La tabla y el inventario inferiores conservan el corte congelado anterior; este apartado lo actualiza sin atribuirle resultados retroactivos.

- Autorizada instalación de herramientas de desarrollo en nube y licencia Android SDK. Instalados SDK platform 36, build-tools 36.0.0 y 35.0.0 (requerido por AGP), platform-tools y command-line tools 19.0 oficiales; JDK Temurin 21.0.13+9 y Gradle 8.13. Sin modificar el equipo del usuario ni desactivar validación TLS.
- **Compilación de producción FullDebug y PlayDebug Java/Kotlin: PASS**, incluyendo KSP; se excluyó `prepareApkFactoryTemplate` para no construir su APK auxiliar. No equivale a ensamblado APK ni a PASS S6.
- La base remota original no compila Kotlin: faltaba la proyección nullable de Cursor y tenía declaraciones duplicadas de episodios. En el candidato se añadió un helper nullable privado al archivo; los duplicados ya estaban corregidos por el WIP. No se afirma una base verde.
- KSP generó el esquema Room 5 real. Comparación SQLite del esquema 4 más migración 5 contra sus 61 entidades: cero diferencias de columnas, claves foráneas o índices. Revisión SQLite independiente repitió la comparación, incluyendo índices y `foreign_key_check`, sin discrepancias.
- Primer lote de tests Full detenido **antes de ejecutar pruebas** por dos errores nuevos de compilación de fixtures: llamada inexistente para editar fuente y tipo recursivo de un gate. Corregidos usando la mutación SQL de fixture existente y anotación explícita de tipo, sin retirar asertos; revisados independientemente. La compilación posterior de tests Kotlin/Java pasó.
- Primer lote real Full `core_memory`: **164 tests ejecutados, 157 PASS, 7 FAIL**. Tres fallos de fixtures por API SQLite incompatible con el driver Room, dos expectativas antiguas de esquema/persistencia global y dos de narración bajo diagnóstico. Tras corregir exclusivamente fixtures/expectativas y revisión independiente, el rerun pasó **164/164, cero skips**; incluye Room, flush y narración. Antes hubo un intento bloqueado por directorio de cache Robolectric de sólo lectura, corregido en configuración del runner sin cambiar producto.
- Full `connectors_audit`: **47/47 PASS, cero skips**, diez clases incluyendo persistencia Room, threading/UI history, MCP y recibos nativos/módulos. Full tasks inicial: 64/66; tras instrumentar el helper legacy con admisión/recibo, **66/66 PASS, cero skips**, sin alterar datos ni asertos del fixture restringido. No se autoriza su publicación por ello.
- Play `core_memory`: **164/164 PASS, cero skips**. Full Google/Linux: **200/200 PASS, cero skips** tras corregir lifecycle del fixture y comprobar rechazo de dismissal protegido seguido de cancelación explícita. El intento intermedio bloqueado se interrumpió con exit 130 y no cuenta como resultado de pruebas; no se añadió timeout ni se relajó producción.
- Siguen pendientes la autorización del fixture de respaldo y la decisión de producto sobre `linux_status`. No se publicó contenido denegado ni una rama candidata.

## Cierre de validación seleccionada — 17:33 UTC

**1.088/1.088 ejecuciones PASS: Full 690 y Play 398; cero fallos, errores u omisiones.** Son 60 clases y 690 métodos sin duplicar sabores, no 1.088 pruebas únicas. Los 38 selectores afectados suman 477 Full + 277 Play; las regresiones adicionales suman 121 Main por sabor y 92 específicas Full.

[Resultados por lote](S6-selected-test-results.json) y [selectores exactos](S6-selected-test-classes.json) contienen sólo nombres técnicos y conteos, sin logs ni payloads de pruebas. Las ejecuciones anteriores fallidas y canceladas permanecen diferenciadas en el relato; no se borran para atribuir éxito al primer intento.

Reproducción desde `app/`, con SDK/JDK configurados: `gradle :app:testFullDebugUnitTest --rerun -x :app:prepareApkFactoryTemplate --tests <clase>`; sustituir Full por Play para clases Main. Cada lote usa todos sus selectores `--tests`. Se usaron Gradle 8.13, Kotlin daemon 3 GiB, Gradle 1 GiB y un worker. Sólo configuración de cache writable, proxy y truststore existente en el runner; no alteración de asertos ni desactivación TLS. La compilación principal ejecutó las tareas Kotlin/Java Debug de ambos sabores, sin ensamblar APK.

Revisión independiente del delta: único cambio productivo adicional es helper privado nullable de Cursor (tres líneas); esquema5 KSP verificado; siete archivos de tests corregidos sin debilitar asertos. Los cambios de pruebas incluyen APIs correctas del driver Room, contrato de corrección por nuevo ID, esquema actual, recibos de fake y lifecycle/decisiones protegidas. Ninguno modifica `linux_status` ni habilita permisos nuevos.

## Ejecutado antes de disponer del SDK (corte histórico)

| Comprobación | Resultado y límite |
|---|---|
| Preflight Gradle 8.13 / JDK 21 | BLOQUEADO: `SDK location not found`, antes de compilar fuentes. No se aceptó licencia SDK. |
| `EffectInvocationTest` standalone Java/JUnit 4.13.2 | 15/15 PASS, repetido independientemente; siete mutaciones detectadas en copias temporales. No valida wrappers/gates Android. |
| `EpisodeNarrationPayloadTest` standalone Kotlin 2.2.20/JDK 21 | 8/8 PASS; dos mutaciones iniciales de presupuesto/conteos detectadas. No valida Room ni modelo real. |
| `AutonomyAuditMigrationTest` standalone Kotlin 2.2.20, JSON20240303 | 10/10 PASS local e independiente; tres mutantes finales detectados. Cuatro mutantes del corte inicial de ocho tests también detectados. No sustituye JSONTokener/SharedPreferences Android. |
| SQL migración 4→5 sobre esquema 4 exportado, SQLite | PASS del DDL probado, estados y conservación de texto/título; dos tablas flush, PK/FK/cascade y planes indexados comprobados independientemente. No sustituye Room/KSP ni esquema 5 exportado. |
| XML strings Main inglés/español | Parse XML válido y nombres únicos. No valida recursos Android/Compose. |
| `git diff --check` | PASS repetido al congelar el árbol. |
| Remoto, lectura `git ls-remote` | `feat/single-session` sigue 42560900; master sigue 8a9870e; `continue/s6-cloud` no existe. No hay respaldo publicado por rama. |

Las mutaciones se ejecutaron únicamente sobre copias temporales, no sobre el árbol de producción.
Repetición final sobre las fuentes congeladas: 15/15 + 8/8 + 10/10 PASS; DDL SQLite y whitespace PASS.

Los tres runners standalone usan las clases de producción indicadas y JUnit; no son una suite Gradle.
No se ejecutó APK, emulador, dispositivo, Linux guest ni servicios del usuario.

## Archivos de pruebas preparados o modificados — estado actual

Lista de archivos de tests tocados desde la base, **no inventario exhaustivo de todas las regresiones posibles**.
Los 38 archivos siguientes se ejecutaron satisfactoriamente mediante Gradle/Robolectric cuando aplica. Main se ejecutó en Full y Play; testFull sólo en Full. No es validación sobre dispositivo. Las firmas de fakes también cuentan como archivo modificado, no como contrato nuevo probado.

| Archivo relativo a `app/app/src` | Ejecución |
|---|---|
| `test/java/com/jarvys/agent/CompactionFlushPersistenceTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/CompactionMemoryFlushTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/ConnectorScheduledReadReceiptTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/CoreMcpEpisodeIntegrationTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/EffectInvocationTest.java` | 15 standalone + PASS Full/Play |
| `test/java/com/jarvys/agent/EpisodeEvidencePersistenceTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/EpisodeNarrationPayloadTest.kt` | 8 standalone + PASS Full/Play |
| `test/java/com/jarvys/agent/EpisodeNarrationTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/EpisodeRecorderTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/EpisodeRunIdentityTest.java` | PASS Full/Play |
| `test/java/com/jarvys/agent/MissionEpisodeCaptureTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/PersonalAgentBehaviorTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/PersonalMemoryIncrementalExtractionTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/ScheduledTaskBoundToolTest.java` | PASS Full/Play |
| `test/java/com/jarvys/agent/SingleSessionMemoryRetentionTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/WorkspaceEffectReceiptTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/apkfactory/FactoryRetiredAuditPreservationTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/chatdb/EpisodeIdentityMigrationTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/chatdb/JarvysChatDatabaseM0Test.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/connectors/AutonomyAuditMigrationTest.kt` | 10 standalone + PASS Full/Play |
| `test/java/com/jarvys/agent/connectors/AutonomyAuditPersistenceTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/connectors/AutonomyAuditThreadingTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/connectors/ConnectorAuditHistoryUiTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/connectors/ConnectorEffectAdmissionTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/mcp/McpWriteApprovalCoordinatorTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/modules/ModuleEffectAdmissionTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/tasks/ScheduledTaskProcessorTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/tasks/TaskMemoryAdmissionTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/tasks/TaskPendingActionExecutionTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/tasks/TaskTickEngineTest.kt` | PASS Full/Play |
| `test/java/com/jarvys/agent/tasks/TaskWorkManagerTest.kt` | PASS Full/Play |
| `testFull/java/com/jarvys/agent/GmailEpisodeAdmissionTest.kt` | PASS Full |
| `testFull/java/com/jarvys/agent/GoogleMutationAdmissionTest.kt` | PASS Full |
| `testFull/java/com/jarvys/agent/connectors/DriveConnectorContractTest.kt` | PASS Full |
| `testFull/java/com/jarvys/agent/connectors/GmailApiContractTest.kt` | PASS Full |
| `testFull/java/com/jarvys/agent/connectors/GmailDraftDeletionTest.kt` | PASS Full |
| `testFull/java/com/jarvys/agent/connectors/GmailManagementContractTest.kt` | PASS Full |
| `testFull/java/com/jarvys/agent/flavor/LinuxToolsTest.kt` | PASS Full |

## No ejecutado / pendiente

- Suite global, lint completo, ensamblado APK y recorridos de dispositivo no ejecutados. Compilación Full/Play, KSP5 y tests seleccionados sí ejecutados (ver resumen).
- Recorridos integrados B3/B4/B5/B15 y pruebas de crash/recovery reales en Android. Las pruebas preparadas y SQLite aislado no los sustituyen.
- Auditoría final y suites/cierre de etapa; S7/S8; evaluación independiente de paridad Engram del candidato. La matriz de baseline es **NO EQUIVALENTE** y el candidato sigue **NO EVALUADO**.
- CI del candidato: sin commit/ref remoto no hay run atribuible; no workflows de repositorio detectados en la base inspeccionada.

## Límites y contradicciones que siguen abiertos

- El checkpoint es WIP: productores legacy/módulos sin recibo siguen UNCERTAIN, otros inventarios/clasificaciones y manifest de registro requieren cierre. No es una entrega operativa validada.
- `linux_status` ejecuta perfiles/binarios guest mutables; no está certificado como lectura inocua. Host-only cambiaría lo verificado y necesita decisión, no permiso exec inventado.
- Coding asíncrono y eventos de conexión/outbox tienen contratos revisados, **sin implementación nueva**. Conexión UI síncrona/grants obsoletos permanecen gaps conocidos fuera del parche de audit history.
- La consulta del narrador quedó acotada por IDs en `9dafb70e`; conserva paginación y orden sin cargar la cola global.
- Flush mantiene extracción at-least-once, no exactly-once universal; el gate compartido posterior cierra la carrera de escritura automática hasta commit/rollback en el proceso actual. Cobertura histórica no acreditada queda incompleta. Referencias que exceden el presupuesto pueden bloquear compactación explícitamente sin perder fuentes; no se oculta como éxito.
- Las exclusiones antiguas de Engram por falta de demanda no satisfacen el requerimiento actual de paridad completa; S6–S8 no prueban esa paridad por sí solos.

## Autorizaciones y publicación

Licencia Android SDK e instalación cloud autorizadas explícitamente antes de instalar. No se aceptaron otras licencias SDK.
Pendiente autorización para sustituir un fixture histórico rechazado por datos claramente ficticios conservando sus pruebas. No se reintenta contenido denegado.
El cambio local de `Continue.md` fue excluido del respaldo; el archivo conserva exactamente la versión remota previa. No trasladar su contenido histórico por otro canal.
Los blobs ya cargados/verificados no equivalen a commit/ref remoto. No se creó rama, PR, despliegue ni APK.

El bloqueo SDK está resuelto. La siguiente expansión requiere un bloque coherente dentro de S6 con revisión independiente; los gaps anteriores no quedan cerrados por estas pruebas.


## Checkpoint posterior — gate de escritura automática

Full/Play compilan y KSP5 permanece sin cambios. Selección ejecutada: new gate 32, core 168,
tasks 66, legacy 72, regresiones adicionales 46 y principales 121 por sabor: **1.010 ejecuciones
PASS**, cero fallos/errores/skips. Son **970 casos únicos contando flavor**, 485 métodos únicos y
57 clases; 40 ejecuciones duplicadas corresponden a clases compartidas entre grupos. Las regresiones
46/121 por sabor preceden al último ajuste de la factory scheduled; gate/core/tasks/legacy se
repitieron después. No se suman las 1.088 ejecuciones del checkpoint anterior como evidencia nueva.
[Inventario exacto y hashes de XML, sin contenidos](S6-memory-gate-test-results.json).

Revisión independiente del código y pruebas: captura antes de inputs/modelo, propagación sin
recaptura, setter offmain, atomicidad de commit/rollback, bypass exclusivo USER/cleanup y límites
por proceso. La revisión descubrió captura tardía en consolidación, corregida con dos pruebas ABA
del worker. El oráculo de cola de lock se reforzó para evitar falso verde de scheduling.
[Contrato y mutantes](S6-memory-write-gate-contract.md).

Fallos conservados como diagnóstico, no omitidos: runners Android ausentes, roots de generación y
readiness de fixtures; factory scheduled incluía preview indebidamente (fix de producto limitado).
El antiguo test de prompt legacy se conserva como harness explícito con todos sus asertos; un test
nuevo prueba el runtime Room real, recarga de datos, exclusión legacy y runtime nuevo con OFF.
Se corrigió la raíz física del fixture de búsqueda y se exige existencia real del symlink antes de
probar su exclusión. No se restauró memoria por chat en el runtime ni se cambió lectura mid-run.

Este checkpoint no cierra conexión/outbox, Coding asíncrono, inventario completo, `linux_status`,
S7/S8 ni equivalencia Engram. Sin suite global, lint, APK o pruebas de dispositivo. Publicación sigue
pendiente de autorización específica para el fixture rechazado; no se subió contenido denegado.


## Foundation de conexión aislada

Main/tests Full y Play compilan; `ConnectionStateCoordinatorTest` pasa 29/29 por sabor (58 ejecuciones,
29 métodos, cero skips). [Resumen seguro](S6-connection-foundation-test-results.json). Código puro sin
wiring de producto, revisado independientemente. No reejecuta ni reemplaza el resultado del gate de
memoria. Adapter/SharedPreferences, bootstrap legacy, recovery cross-process, UI, Room y lifecycle
real siguen pendientes antes de habilitar la fachada; no PASS conexión ni S6.


## Adapter/bootstrap aislados, después de la foundation

Cinco clases pasan Full67/67 y Play67/67: 134 ejecuciones, 67 métodos, cero skips. Luego los dos métodos
de frontera Android se repitieron en SDK24/34 (4 casos Full + 4 Play), conservando separado ese resultado
y sus repeticiones SDK34. [Resumen](S6-connection-adapter-test-results.json) ·
[Boundary](S6-connection-android-compat-test-results.json). No sumar los 58 anteriores de foundation
como pruebas nuevas de este árbol. Compilación main/tests Full/Play PASS, schema5 sin cambio.

Preflight anterior: Full compiló, faltó el selector Bootstrap y falló la clasificación de cleanup
rechazado; se añadió el selector y corrigió producto sin modificar la aserción. Rerun completo de
los cinco selectores PASS. Revisión independiente de código/contratos; hashes reconciliados independientemente. Simulación de fallos separada de frontera SharedPreferences Robolectric y de dispositivo real.

Pendiente antes de wiring: destino dinámico/Room generation/fence, proyección exacta, poda con keep-set
acreditado y backlog, Registry/UI/raw readers y lifecycle. No se entrega una fachada parcialmente
operativa; los callers actuales no se sustituyeron. Publicación/fixture y decisión linux_status siguen
pendientes. Sin suite global, lint, APK ni equivalencia Engram declarada.


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


## Cutover operativo de conexión — validación focal posterior

Este corte sustituye el límite histórico de integración pendiente: Application comparte una fachada
única; Registry, defaults, UI y lectores Notification/Gmail usan elección confirmada más deny local.
Requests capturados antes de permisos/OAuth conservan identidad/generación y no se recapturan al
volver. OFF temprano sigue siendo provisional, nunca durabilidad inventada; operaciones aceptadas
sobreviven al observador. Repair/proyección independientes no reconectan runtimes. Google serializa
mutaciones locales y rechaza callbacks obsoletos sin ampliar scopes. DEFAULT espera un intento de
bootstrap fuera de main/locks antes del CAS; una baja concurrente conserva false/deny.

La admisión lleva Observation tipada exacta al intent de la llamada. Se eliminó el lookup de último
connected=true: local_choice no es autorización ni éxito externo. Tool/operación reemplazados no
adoptan silenciosamente el nuevo catálogo. Preferencias de política y permisos conservan su autoridad.

Árbol final: **544 Full / 51 clases y 411 Play / 45 clases PASS**, 955 ejecuciones contando flavor,
cero fallos/errores/skips, con `--rerun`. No sumar los checkpoints anteriores como casos únicos.
[Resumen y hashes](S6-connection-cutover-test-results.json) · [Contrato](S6-connection-cutover-contract.md).
Main y tests Java/Kotlin de ambos sabores compilan; schema6 no cambió. Revisión independiente de
fuente y XML. Pruebas Room/Robolectric y storage controlado, no dispositivo físico/crash-fsync.
La prueba de recreación simula restauración de estado y flag de configuración, no Activity.recreate.

Intentos fallidos conservados: dos errores de compilación corregidos sin cambiar JVM target/asertos;
un selector de subtipo de memoria y sincronización Compose; fixtures worker/readiness y expectativa
antigua de adoptar operación obsoleta. La regresión tasks parecía verde en conjunto pero falló 7/22
al aislarla: catálogo BotCreation consultaba Registry global con módulos fríos desde fixture main.
Se corrigió exclusivamente la afinidad del fixture para igualar TaskTickWorker IO; datos/asertos
intactos. Aislación fría 22/22 y unión final pasan. DEFAULT tenía una carrera distinta, cubierta por
controles de ausencia y OFF durante bootstrap. No se atribuye el fallo tasks a esa carrera.

**S6 sigue WIP.** Pendientes Coding asíncrono (submission frente a terminal durable), inventario de
productores/módulos y aceptación completa; después S7/S8 y paridad Engram con juez independiente.
Sin suite global, lint, APK ni despliegue. No se publicó: saneamiento del fixture rechazado y decisión
linux_status siguen pendientes. Continue.md no se modificó ni se transmitió contenido restringido.


## Foundation Coding y semántica local — checkpoint aislado

Metadatos inmutables discriminan checkpoint Crew, assignment y procedencia central; codec estricto
rechaza duplicados, coerciones y contradicciones. No es prueba de commit durable ni autoridad,
no reemplaza journals runtime y no activa ASYNC. La transición validada es de productor consecutivo,
no un proyector con lag. Recibos owner-bound distinguen creación local, coordinación aceptada,
entrega local y referencia existente; recorder/narrador no convierten aceptación en misión terminada.
Los productores CentralWorkspace/Coding aún no emiten esos recibos nuevos.

Full **169/169** (18 clases) y Play **165/165** (17 clases), 334 ejecuciones, cero fallos/errores/skips.
Pure JVM final: Coding17 y receipts27 PASS; seis mutantes detectados sobre snapshots previos a
los últimos guards, no cobertura de mutación completa del árbol final. Primer Full conservado:
169 casos/1 fallo por shadowing del helper Kotlin en un test nuevo; corregido sin cambiar asertos.
[Resumen/hashes](S6-coding-foundation-test-results.json). Revisión independiente de fuente y XML.

Sigue pendiente el slice CentralWorkspace de admisión explícita/recibos productores y fuente
operacional atómica con assignment; después origen Crew exacto y journal/preparación/proyección/
recovery Coding completos. La fuente original de assignment no debe confundirse con entradas
posteriores de un ciclo: ese límite sigue en revisión antes del opt-in. No S6 PASS, suite global,
lint, APK ni paridad Engram; respaldo seguro y limitaciones se documentan por separado.

## CentralWorkspace — admisión de productores y raíz operacional

Execute4 separa acceso puro, admisión persistente fuera de locks y confirmación tipada local de
create/delegate/deliver/reply/message. Una aceptación local no acredita ejecución de misión ni
consumo del modelo; referencias duplicadas no producen un segundo OK. La entrega diferencia
inserción nueva de duplicado mediante el resultado de la misma transacción Room, no un lookup previo.

El runtime Central instala un proveedor independiente de memoria que vincula owner, batch, call,
run, proceso y generación física de DB al checkpoint STARTED comprometido. El witness pertenece a
una sola invocación; copias ordinarias del registry lo conservan y la delegación no lo hereda.
WorkspaceRegistry v1→v2 añade fuente nullable sin reescribir filas legacy; assignment + fuente
se insertan en la misma transacción. El fence de lifecycle protege sólo esa atribución, con orden
anchor→workspace→SQLite, sin bridge/listeners/spawn dentro. La segunda fase relee el assignment
exacto y conserva CAS/no replay. La raíz es histórica, no el trigger exclusivo de comandos futuros.

Los intentos fallidos quedan en el manifiesto. Dos ejecuciones del source anterior e9c937f0
reprodujeron exactamente nueve y veintisiete fallos respectivamente: fixtures de contexto/lifecycle,
API Central no borrable, ruta DB, esquema de acciones y constructor reflejado obsoletos. Se repararon
los fixtures sin retirar asertos ni relajar guards de producción; se añadió comprobación explícita
de que la API actual no borra Central. La ausencia/tombstone histórica se simula sólo en fixtures
transaccionales, con restauración exacta del trigger. El nuevo test de cierre observaba un hilo
redispatcher, no el closer real; se corrigió ese oráculo conservando fence, deadline y asertos.

La integración runtime de memoria OFF usa una mutación local de fixture, no red. El requisito
SINGLE_SESSION §7.5 A y el contrato del gate permiten audit causal técnico con OFF; B/R2 separan
la narración automática. Un aserto nuevo de cero episodios resultó no sustentado por ese contrato:
se reemplaza por pruebas más precisas del episodio/evidencia exactos, título/texto vacíos,
narration=none y ausencia de nuevas memorias semánticas/digest. No se cambia producto para esconder
el hallazgo ni se promete ausencia total de auditoría con OFF. El primer intento
READ mostró que CoreConnectorTool.execute4 delega esa ruta a execute2 y no transporta EffectInvocation
al gateway: no se modificó producto para hacer pasar el test ni se afirma cubrir ese transporte READ.
S6 continúa WIP: falta origen Crew/inputs exactos, journal/preparación/terminal/proyección/recovery de
Coding ASYNC y el inventario restante; después S7/S8 y evaluación independiente de paridad Engram.
Sin suite global, lint, APK, dispositivo físico ni despliegue en este checkpoint.

Validación final del slice: **677 Full / 57 clases y 673 Play / 56 clases PASS**, 1.350 ejecuciones
contando flavor, cero fallos/errores/skips, con `--rerun`. Main/tests Java/Kotlin de ambos sabores
compilan. Contexto puro 9/9 PASS; fuente y resultados se revisan independientemente.
[Manifiesto con hashes e intentos](S6-central-workspace-test-results.json) ·
[Contrato](S6-central-workspace-causal-contract.md). No sumar runs previos como casos únicos.

Respaldo seguro anterior confirmado en `continue/s6-cloud` (`8605176`, source restaurable `e9c937f0`).
Los checkpoints posteriores usan el mismo método: archivo restringido idéntico al remoto + patch
mínimo limpio, integridad/restauración exacta y rama sin force. El source restaurado no debe subirse
con el fixture completo sin autorización. Continue.md permanece idéntico al remoto.
