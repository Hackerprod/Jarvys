# Portabilidad P1 — candidato implementado, pendiente de revisión independiente

Estado: **P1 R3 PASS independiente acotado a host en `6bfd252a7e516cc25144194913a93b13b4dc8c67`; R1/R2 FAIL conservados; P3 de entrega en preparación, sin avance P2**. D3 Full/Play pasó antes de habilitar la admisión en este candidato local; no está desplegado. Este documento no sustituye los resultados ejecutados ni la revisión independiente.

## Alcance y autoridad

Se mantiene Central único, workspaces/agents generalistas, memoria Engram nativa con su autoridad existente y menú v97. No hay nueva conversación de usuario del hijo, store de memoria/outbox/journal paralelo, migración, borrado, restauración de UI legacy ni código upstream incorporado. La recuperación generalista usa el perfil nativo existente; el valor interno `LEGACY_CHAT` del perfil no habilita chats legacy.

La autoridad de coordinación es `centralContinuationV1` en el checkpoint exacto del hijo, dueño lógico Central. El store compara revisión y SHA del payload confirmado, preserva el bloque bajo el mismo lock en saves ordinarios y confirma sucesor + vínculo BOUND en un reemplazo atómico. No son permisos del modelo ni garantías multiproceso.

Una delegación opta mediante `workspaces(action=delegate, continue_result=true)`. La creación de la espera ocurre después de BOUND y antes de iniciar el worker. Una clave de delegación no puede cambiar de modalidad para crear otro trabajo por reintento. Las delegaciones históricas/sin bloque mantienen su recorrido anterior.

## Recorrido implementado

1. El hijo generalista emite `ask_chief`. Sólo en el episodio enlazado se usa una frontera exclusiva del batch: no se inicia ninguna llamada acompañante, aunque preceda a la pregunta o se declare paralela. Múltiples fronteras se rechazan sin dispatch. El checkpoint conserva `NEVER_LAUNCHED` y la pregunta termina el turno; no inicia otra inferencia.
2. La pregunta tiene evidencia exacta de tool-call y coordinación. Queda un checkpoint PARTIAL/needs_input que requiere el control Reanudar existente. No se reconstruye una capacidad live-only de reply.
3. Una respuesta retenida usa el outbox existente con kind ANSWER, separado de MESSAGE. Se conserva antes del buzón y requiere un RESUME explícito, revisión admitida y vínculo exactos. STARTED antes de entrega; incorporación sólo por checkpoint + recibo Room. Un STARTED incierto no se reenvía.
4. Reanudar crea ciclo nuevo y confirma su checkpoint/vínculo BOUND antes de programar executor o entregar la respuesta. El certificado de origen conserva hashes del perfil, transcript, lifecycle y ownership aunque el archivo anterior se reemplace.
5. Sólo un resultado DONE o FAILED del ciclo esperado selecciona la dependencia. PARTIAL/pregunta no la satisface; STOPPED no despierta Central. Selección y consumo se fijan en el mismo CAS. El input Central contiene referencia/hash y provenance SUBORDINATE, no cuerpo ni permisos del Chief.
6. El servicio serial existente reclama ese input exacto mediante PREPARED→STARTED antes del proveedor. El run real usa el ID persistido, no un UUID paralelo. Assistant + generation_run se preparan juntos en la transacción nativa; la finalización de ambos también es atómica y no permite sustituir un terminal por un fallo posterior.
7. Un consumidor de continuación sólo puede ejecutar la lectura de su resultado exacto y sintetizarlo. No recibe un witness USER ficticio ni autoridad para escribir/delegar. Los demás caminos de history/steering excluyen estos inputs; un resultado incierto no se cuela a otro run.
8. ACKNOWLEDGED deriva únicamente del input exacto incorporado en Room. La reconciliación de receipts puede reparar un ACK de bloque perdido sin invocar al modelo. Nunca se deduce incorporación de una notificación, estado de bot o journal cualquiera.

## Punto seguro acotado

Se exige identidad exacta de workspace/assignment/mission/bot, perfil versionado, permisos actuales y efectos inspeccionables. El primer perfil admite lecturas locales `board_read`, `memory_search`, `memory_get`, y `ask_chief` con su recibo exacto conservado. Rechaza lifecycle STARTED/INTERRUPTED_UNCERTAIN, jobs no inspeccionables, llamadas cuyo origen desapareció por compacción, recibos de pregunta reutilizados y otros efectos incluso si figuran RESULT.

En particular, no se afirma recuperación global de efectos externos, `msg_send` sin recibo por llamada, ni de escrituras de memoria `memory_save/update`. La memoria propia sigue usando sus sistemas actuales; estos límites no se resuelven inventando un perfil Coding o replay. Un checkpoint no apto conserva evidencia y exige otra intención explícita, sin restauración destructiva.

## Aclaración P0→P1: continuación explícita de Central

P0 no inventaba un Reanudar de Central. La revisión de implementación mostró un caso distinto: el hijo ya terminó, pero el proceso cae antes del claim Central. El control Reanudar del hijo no corresponde y abrir la app no debe ejecutar el proveedor. Se incorpora por autorización de coordinación una acción acotada en la herramienta existente, sin cambio de UI:

- El usuario escribe en el Central existente **`/continue_result <result_id>`**, con el ID exacto visible en el estado/resultado.
- El turno actual puede llamar `workspaces(action=continue_result, workspace_id, result_id, expected_sha256, expected_assignment_revision)`.
- Se exige witness real del call actual, propietario USER, proceso/storage vigentes y que ese mensaje sea el último USER con el comando exacto. Background, texto delegado, lectura o witness ausente/caducado no bastan.
- Sólo se admite el input ya seleccionado PREPARED, mismo resultado/hash/owner/revisión/ciclo y sin control contradictorio. No crea una espera ni reinicia STARTED, ACKNOWLEDGED o CANCELLED.
- `centralAdmissions[]` agrega fuentes USER exactas, append-only y sólo mientras PREPARED. El claim vuelve a comprobar la última fuente, proceso/storage y último mensaje: un comando encolado que quedó obsoleto no habilita dispatch. El historial no se reescribe.
- Reutiliza la misma fila estable y la misma cola serial. Repetir la acción no duplica consumo ni generación. Un episodio STARTED incierto no se recupera por esta acción.

Los scans genéricos de app-open/recovery/STOP no seleccionan inputs del protocolo. La reconciliación de catálogo sólo repara metadata/inputs/ACK locales. Un evento vivo de resultado puede entregar el input exacto; la acción explícita anterior permite recuperarlo tras restart sin convertir la apertura de vistas en permiso.

## STOP, errores y conservación

Antes de claim, STOP/CORRECT cancelan la espera sin borrar resultados. Después de claim, un handle transitorio cancela exclusivamente el token cuyo proceso/generación coinciden con el bloque; no es autoridad persistente ni permiso de replay. La cancelación latches antes de callbacks asíncronos y un token viejo no detiene un ciclo posterior. Los cierres del servicio liberan el handle por identidad, sin resetear el estado durable.

Fallos de disco, versiones desconocidas, CAS conflictivo o identidad incompatible fallan cerrado. STARTED sin journal/ACK sigue incierto. Inputs bloqueados no se marcan incorporados para limpiar la cola y no provocan filas repetidas de error ni bloquean inputs ordinarios. Los terminales confirmados no se sobrescriben por un catch posterior.

## Rollback y límites de evidencia

Rollback seleccionado: deshabilitar nuevas admisiones manteniendo lectores, guards y datos. El downgrade binario a v97 con episodios nuevos **no está soportado**; no hay migración ni borrado para hacerlo pasar.

La validación ejecutada es JVM/Robolectric con proveedores guionados y fuentes/artefactos enlazados a hashes. No demuestra Android físico, force-stop/Doze/reinicio real, proveedor vivo, APK, publicación ni deploy. P2–P4 no se inician sin PASS independiente de esta fase y los gates aplicables.

## Entradas de revisión y pruebas

- Store/compatibilidad: `CentralContinuationRecord`, `CrewCheckpointStore`, `CrewContinuationStoreTest`, `CrewCheckpointDurabilityTest`, `CrewCheckpointRecoveryTest`.
- Worker: `CrewCheckpointCoordinator`, `CrewManager`, `CrewTools`, `CoreAgentLoopExclusiveYieldTest`, `NativeWorkspaceCheckpointRecoveryTest`.
- ANSWER: `WorkspaceContinuationAnswers`, `WorkspaceConversationInputStore`, `WorkspaceInputIncorporationDelivery`, `WorkspaceContinuationAnswersTest`.
- Central: `WorkspaceContinuationDelivery`, `WorkspaceResultDelivery`, `ConversationReader`, `MainChatTurnContextProvider`, `AgentForegroundService`, `CoreAgentRuntime`.
- Persistencia/STOP: `ChatDatabaseBridge`, `ContinuationGenerationReceiptTest`, `CancellationToken`, `StopController`, `ExactRunCancellationTest`.
- Recuperación explícita: `CentralWorkspaceTool`, `WorkspaceMutationContext`, `WorkspaceCoordinator`, `CentralWorkspaceContinueResultToolTest`, `ContinuationServiceRunTest`.

La cohorte final del candidato16 completó 87 clases y 781 casos por sabor Full/Play, todos aprobados; esto no sustituye el PASS independiente. Las compilaciones/ejecuciones fallidas conservan sus marcadores privados; no cuentan como PASS. El evaluador independiente recibe commit, bindings y evidencia por clase sin publicar logs privados.

| Roadmap | Entrada de pruebas y observación | Límite |
|---|---|---|
| T01–T03 | `NativeWorkspaceCheckpointRecoveryTest`, `WorkspaceContinuationAnswersTest`, `CoreInputIncorporationTest`, `ContinuationServiceRunTest`: generalista pregunta→ANSWER retenida→Resume explícito con ciclo nuevo→incorporación probada→resultado seleccionado→lectura/síntesis Central; duplicados y fuentes incorrectas rechazados. | Proveedores guionados; reconstrucción local, no muerte física de dispositivo. |
| T04–T05, parte P1 | `WorkspaceContinuationDeliveryTest`, `ExactRunCancellationTest`, `WorkspaceControlStopOwnershipTest`: una selección/continuación; resultado viejo/no enlazado no satisface espera; STOP conserva evidencia y cancela sólo dueño exacto, nunca ciclo posterior. | Árbol worker ANY/ALL durable y nueva política de subárbol siguen P2. |
| T06 | `CoreAgentLoopExclusiveYieldTest`, `CrewWorkspaceMissionAdmissionTest`, `CentralInvocationSourceTest`, `CentralWorkspaceContinueResultToolTest`, `CrewC0Test`: frontera exclusiva sin efectos vecinos, roles/permisos nativos, cero herencia Chief y fuente de usuario vigente para recuperación explícita. | CrewC0 conserva contratos existentes; no acredita por sí solo P2. |
| T07 | `WorkspaceMemoryAuthorityTest`, `WorkspaceMemoryOperationsTest`, `CentralMemorySessionCaptureTest`: namespace propio, lecturas positivas, denegación personal/global/ajena y revocación; input AGENT no se convierte en USER. | Una memoria Engram nativa; ninguna memoria paralela. |
| T08 | `WorkspaceMemoryContinuityTest`: dos compactaciones reales, envejecimiento determinista de datos por 240 días, reopen, nueva misión sin transcript anterior; decisión corregida y referencia recuperadas, `needs_review` canónico y bytes/hash de artefacto conservados. | Envejecimiento de fixture persistido, no avance probado del reloj Android ni meses físicos. Caso extendido incluido en la cohorte final aprobada. |
| T09 | `WorkspaceArtifactDeliveryTest`, `WorkspaceEffectReceiptTest`, `WorkspaceResultDeliveryTest`: inspección/hash, entrega immutable a Central, retry idempotente, cambio de bytes/STOP/owner incorrecto rechazados. | No garantía universal de exactly-once para efectos externos inciertos. |
| T11 | `CoreAgentLoopProviderRetryTest`, `CrewProviderUnavailableRecoveryTest`, `CrewRecoveryLifecycleTest`: 429/backoff/cancelación y PARTIAL/FAILED conservadores; STARTED incierto no se reproduce. | Sin proveedor/red vivos. |
| T12 | `CrewContinuationStoreTest`, `CrewCheckpointDurabilityTest`, `ContinuationGenerationReceiptTest`, `CoordinationRefEncodingCompatibilityTest`: CAS, corrupción/disco, atomicidad native assistant/run/receipt, TEXT histórico y BLOB UTF-16 exacto sin reescritura; NUL/surrogates preservados, corruptos rechazados. | Fallos locales inyectados; sin migración, borrado o reparación destructiva. |
| T13 | `WorkspaceRestorationReadOnlyTest`, `MainActivityWorkspaceRoutingTest`, `DrawerNavigationComposeTest`, `RestoredWorkspaceComposerTest`, `CoreIntermediateAssistantSourceTest`: abrir no despacha, único Central, menú restaurado, controles existentes y binding exacto del assistant antes de steering. | No UI overhaul ni validación Android físico/force-stop/Doze/reboot. |


## Gate D3 ejecutado antes de habilitar nuevas admisiones

En el candidato congelado `p1-d3-4` se compilaron Full y Play y se ejecutaron 32 casos por sabor: `CrewContinuationStoreTest` (13), `CrewCheckpointDurabilityTest` (12), `CrewCheckpointRecoveryTest` (7). Cero fallos/errores/omisiones; hashes de fuentes y artefactos idénticos antes/después. Terminal Full SHA-256 `306bbbc1bdc3224c9c2950ab8e3298e15e2b57f986856f4f9fe85fb59cba196b`; Play `28eaf7b93bd85c1b1d2b719e372b0fdfeadd346c49c53ac1cd976d33a1051e29`. Evidencia privada fuera del repositorio.

La cohorte diagnóstica adicional de ese mismo candidato ejecutó 57 casos por sabor y detectó 21 fallos por sabor. No se oculta ni se denomina PASS: incluye fixtures a corregir y el error real de rebind del turno anterior al excluir un input. Las correcciones y gates adicionales fueron recompilados y revalidados en la cohorte final registrada abajo; D3 por sí solo no es PASS P1.

La integración diagnóstica posterior detectó además dos errores reales, corregidos y cubiertos por regresiones en la cohorte final: el assistant preparado debe conservar como owner de interacción el ID exacto del input AGENT (el nombre histórico de columna no lo convierte en USER), y el resolvedor canónico del buzón debe conservar el binding tipado de ANSWER para que la incorporación tenga prueba de checkpoint. No se deshabilita la protección de contexto ni se acepta la mera entrega al buzón como incorporación.

El input del protocolo sigue excluido del Reader genérico. En el runtime reclamado, antes de preparar y antes de enviar cada request, un validador acotado exige token vivo exacto, dueño transient exacto, generación de storage, claim STARTED/ACKNOWLEDGED del proceso, assignment/revisión/control vigentes y fila Room canónica con contenido/provenance iguales. Exige exactamente un turno con ese ID/source/payload. Sólo para la llamada de validación genérica separa ese turno ya validado; valida todos los demás sin excepciones. El transcript enviado, el sourceTranscript y el snapshot de protección completo no se filtran: la compacción conserva la frontera del input. No es un bypass para sources desconocidos ni permite la misma fila en un runtime no reclamado.

## Delta de integridad encontrado en la cohorte ampliada

La prueba heredada de fragmentos exactos expuso que el cuerpo de coordinación ya conservaba UTF-16 exacto, pero `refs` atravesaba SQL TEXT y podía perder NUL/surrogates aislados, rompiendo el hash conservado. Se autoriza una reparación acotada con el codec nativo `ExactUtf16` y lectura discriminada por tipo SQLite TEXT/BLOB, sin cambiar schema ni convertir/borrar filas existentes. Nuevas escrituras de refs permanecieron deshabilitadas hasta ejecutar compatibilidad/conservación en ambos sabores; sólo después del gate registrado abajo se habilitaron en el candidato local. El downgrade a un binario cuyo lector sólo entiende TEXT no es compatible con refs nuevas; retirar nuevas admisiones conservando lectores y datos no hace seguro ese downgrade.

La cohorte también confirmó un hueco de binding al intercalar un assistant y un nuevo input durante el mismo run: el servicio persistía el assistant intermedio pero el transcript no conservaba el ID devuelto. La corrección transmite una fuente tipada sólo después del commit y la enlaza al objeto exacto del turno (también para un terminal de herramienta seguido de steering). No busca por texto ni exceptúa sources del validador. Si falla persistencia no se admite otro proveedor ni se confirma el input. El adaptador de progreso del worker no transmite recibos al Central.


## Gate previo de referencias exactas

`p1-candidate15` compiló Full y Play con `NEW_EXACT_REF_WRITES_ENABLED=false`. Cada sabor ejecutó 39 casos: los 32 D3 originales, seis de `CoordinationRefEncodingCompatibilityTest` y uno de admisión del servicio con guard de main-thread activo. Cero fallos/errores/omisiones y fuentes/artefactos/SDK/launcher invariantes. Terminal Full SHA-256 `94964149c83700e5c2c481a9fe4285b865d7ef59546d9c521464803e22963219`; Play `17afe5dea65216fddc2b87fc2042c72a929aa41d5f75cfc05ec912f5c42eb356`. Los BLOB de esta prueba se prepararon sólo en fixtures sintéticos; la escritura de producción se habilitó después, para un candidato que aún requiere la cohorte completa y revisión independiente.

T08 se amplió sin reloj de producción nuevo: después de dos compactaciones reales se envejecen exactamente 240 días los timestamps/deadlines de memoria propia en un fixture, conservando demás columnas, revisiones, filas ajenas y bytes del artefacto. Reopen y misiones nuevas deben recuperar decisión corregida/referencia exacta mediante herramientas nativas, con estado canónico `needs_review` y sin transcript previo. Esto no afirma meses reales transcurridos ni continuidad física del dispositivo.


## Evidencia histórica del candidato R1 (no PASS de fase)

- Export de clases Gradle 8.13 / JDK 21.0.13+9: Full y Play completados. Ejecución offline JUnit/Robolectric: **87 clases, 781 casos por sabor; cero fallos, errores u omisiones**. No se ejecutó assemble/APK; se conservó la exclusión verificada del template Factory para el export de pruebas, sin certificar Factory/release.
- Los 2.261 archivos fuente del snapshot previo a compilar coinciden con ambos bindings; classpath, SDK instrumentados y launcher permanecen iguales antes/después. Snapshot compilado SHA-256 `70dc7fcf3cdd7a37286bdfbd328f6ea818dde76dbd25bca7f8e36221e3237e6a`.
- Full terminal SHA-256 `3fab585143c49cc04046cd2ac325798ecf7ebd2ec9a3dbc8e8d313c5f6f8d28d`; binding `232d5e5334931541457a9785b20d92b14a182408a1acf6f0454b13660e2bf904`.
- Play terminal SHA-256 `d9ab2815432cc5a5afba568938e2a3e4243b2613ae813e2154d4f1b9071862c1`; binding `9eb4beeb852c9bc7157b71d428ead19eb8252682123a7daf5675eeddcb8f4e09`.
- La cohorte diagnóstica previa `candidate11` tuvo, además de sus fallos, un timeout Play de `MainAgentInteractionTest` sin XML: no se contó como éxito. Se conservó y se añadió captura de hilos ante recurrencia, manteniendo el límite de 300 s. En la cohorte final esa clase completó sus 53 casos en ambos sabores (Full 15,20 s; Play 15,80 s). No se atribuye una causa al timeout anterior que no se haya demostrado.
- El SDK instrumentado 26 faltante se obtuvo de Maven Central oficial y se verificó contra SHA-512 publicado; SHA-256 local `712196356bf61fbcb186f41135c33e6d3416be31543d7be120a33bc898cc1208`. Los fixtures de filesystem usan el shadow nativo existente para representar ENOENT real, sin deshabilitar guards de instalación limpia.

Los XML, hashes de artefactos, selección exacta y diagnósticos anteriores se conservan privadamente para evaluación. El candidato no se ha publicado, mergeado ni desplegado y P2 no se inicia sin PASS independiente.


## Revisión independiente R1: FAIL conservado

El commit `34b0f1d4599e3c795bca702d392e617ecb813317` fue rechazado pese a la cohorte verde. F1: un permiso de dispatch por workspace podía despertar un episodio PREPARED histórico a partir de otro evento después del restart, sin comando explícito para ese resultado. F2: el hook de Stop-all nativo omitía `ACKNOWLEDGED` por comparar `ACK`, dejando vivo el consumidor Central de un hijo DONE. Ambos bloquean P1. Se exige permiso live exacto por resultado/proceso, comprobado de nuevo al claim, y cancelación del dueño exacto después del ACK; las regresiones deben usar episodios A/B y la ruta real de Stop-all. Los resultados del candidato16 se conservan como evidencia histórica, no como PASS de fase. El candidato R2 y su revisión siguen pendientes.

### Corrección R2 en validación

F1 elimina el booleano de dispatch compartido. Sólo el callback nativo que acaba de persistir un resultado puede registrar una capacidad opaca transitoria, enlazada al resultado, episodio, manager/bot/token, ciclo, proceso y generación de storage exactos. La cola transporta esas mismas instancias; un scan, catálogo, lectura o nueva delegación no las fabrica. Se validan otra vez antes de enqueue y claim; se consumen antes del CAS STARTED. Si el CAS queda incierto no se vuelve a emitir permiso. Un PREPARED histórico sin capacidad necesita el comando USER exacto; una admisión explícita obsoleta no hace fallback a permiso live. No se añade formato durable ni otro store.

F2 corrige únicamente `ACK` → `ACKNOWLEDGED` en el hook nativo. La regresión pausa el proveedor después del ACK, recorre Stop-all de misión con callback de capitán nulo, exige cancelación inmediata del token exacto y conserva el token de reemplazo y la evidencia.

Los fixtures anteriores que reclamaban resultados sintéticos sin autoridad ahora deben pasar por un USER real, witness de tool-call confirmado y el backend `continue_result`; no insertan `centralAdmissions` a mano. Las nuevas regresiones cubren A histórico + B vivo en el mismo workspace, catálogo/lectura/reopen, intents demorados, permisos falsos/obsoletos, cambio de proceso/storage/bot/token/ciclo, duplicados y recuperación explícita una sola vez. Estas pruebas host pasaron en la aceptación R2 final indicada abajo; no sustituyen el PASS independiente.

### Consistencia nativa adicional detectada al ampliar aceptación R2

La primera aceptación completa R2 Full ejecutó 789 casos: 788 pasaron y el test existente de tres bots falló al no encontrar aún el resultado de Critic tras `crew_wait(all)`. El trace confirmó una ventana real: `DONE` se publicaba antes de encolar RESULT, por lo que la señal de otro bot podía completar el wait y drenar antes del mensaje. No se cambió la aserción ni se convirtió el fallo en éxito por reintento.

La corrección autorizada añade sólo un fence transitorio de publicación pendiente al predicado de finalización nativo. Se reserva antes del estado terminal, se libera y señala en `finally` alrededor de la publicación existente fuera del lock de ciclo; STOP/cancelación conservan respuesta inmediata. No es árbol durable P2, nueva autonomía ni permiso persistente. Un barrier del listener de publicación prueba determinísticamente DONE, FAILED y cancelación; está situado después del enqueue y prueba publicación todavía en curso, no simula que ese listener sea la ventana anterior al enqueue. El test de tres bots mantiene además la comprobación original de resultados completos.


## Evidencia histórica R2 (FAIL independiente, no PASS de fase)

- Full y Play: **90 clases / 792 casos por sabor**, cero fallos, errores u omisiones. Ambos exports completados; no assemble/APK ni validación física/live-provider.
- Los 2.265 archivos fuente del snapshot compilado coinciden con ambos bindings y con el árbol actual. Se revalidaron todos los XML y los 4.476 artefactos Full / 4.130 Play (incluidos SDK instrumentados y launcher). Snapshot SHA-256 `a88d0e9bfe725d678e049b1f0b14a254d7cce1916110754bc015c896babaa931`.
- Selección de 90 clases SHA-256 `0a3b4793f488c9589d31b41ad6d468ab3563f44b86087db97d4c09f50eb17355`.
- Full terminal `ad2a8c376368ec0ba0be66ca272ed2c25ae14f5f93a59df347924599b0487427`; binding `8a11c7ccbd52fed9c69b36d826d4161c8cbcc22947121144207d9919eb22beb4`.
- Play terminal `afe4f2c7a1f1ee0b81697755fef0ab2a8e4e9728951823e14440b7d66c183030`; binding `828fbc195552a41eef8f8ff8499ef7f72eaf357f0cfba4cc4b5e31b616057404`.
- La cohorte focal previa del mismo código pasó 18 clases / 152 casos. Los seis fallos de fixtures de la primera corrección (perfil no registrado y facade de DB cerrada) y el fallo posterior de publicación nativa se conservan como diagnósticos; ninguno fue contado como PASS.
- Entradas nuevas: `NativeContinuationLivePermitTest` (cinco casos), `NativeContinuationStopTest`, `CrewTerminalPublicationTest` (tres casos), y regresiones A/B reopen/USER en `ContinuationServiceRunTest` y sin autoridad en `WorkspaceContinuationDeliveryTest`.

La evaluación independiente R2 debe revisar estos bytes y sus límites. R1 sigue FAIL, P0 sigue siendo el único PASS independiente confirmado, y no se avanza P2 hasta el nuevo gate.


## Revisión independiente R2: FAIL conservado; correcciones R3

El commit `3ad25c521e1c0d63582eaa3c47f180ab77124ff8` pasó las pruebas host indicadas, pero fue rechazado por dos interleavings no cubiertos. F3: Stop-all podía observar STARTED durable antes de instalarse el dueño transitorio y perder la cancelación. F4: un resultado A cancelado tras PAUSE conservaba su revisión histórica; la comparación de revisión ejecutable abortaba el scan y bloqueaba un resultado B válido del mismo workspace. F1/F2 y la corrección de publicación nativa fueron aceptados como resueltos, no como PASS de fase.

R3 conserva un latch de denegación por episodio antes de buscar el dueño; la instalación del dueño lo vuelve a comprobar antes de preparar el journal/proveedor. Si STOP había leído PREPARED y pierde su CAS contra STARTED, el latch también cancela al dueño exacto que ganó. No se toma el lock de assignment desde el lock de ciclo y no se cancela un token de reemplazo ajeno.

Los resultados CANCELLED/ACKNOWLEDGED se validan contra su fuente retenida y se tratan como históricos, antes de exigir revisión/controles para ejecutar. Una excepción tipada de inelegibilidad esperada aísla únicamente episodios con controles pendientes o autoridad/revisión obsoletas; los scans y la cola siguen con sus hermanos. Corrupción de fuente/schema/owner/hash y cancelación no se capturan como inelegibilidad ni se silencian.

Regresiones nuevas: barrera `owners.compute` vacía entre CAS STARTED real y registro de dueño, seguida de Stop-all real y cero proveedor/journal; cancelación desde snapshot PREPARED cuyo CAS pierde; A completo→PAUSE nativo→reconciliación con A único→B nativo del mismo workspace; control pendiente y permiso obsoleto de A aislados de B; evidencia corrupta y cancelación siguen produciendo error. No se fabrica un claim persistido ni se añade un hook de producción para las barreras. Las regresiones R3 pasaron en la aceptación host indicada abajo; el PASS independiente sigue pendiente.


## Evidencia final R3 para nueva revisión independiente

- Full y Play: **90 clases / 798 casos por sabor**, cero fallos, errores u omisiones. Cohorte focal previa: 18 clases / 158 casos PASS.
- Los 2.265 archivos fuente, todos los XML y 4.481 artefactos Full / 4.135 Play se revalidaron contra los bindings y el árbol final. Snapshot SHA-256 `e034441335a384f28c6eea1a206acea56369395e2c2f3bab9cf40cc6c7f89ecd`; selección `0a3b4793f488c9589d31b41ad6d468ab3563f44b86087db97d4c09f50eb17355`.
- Full terminal `aab8bf3c0cca75b3b37a643ae12054f059aa1befd730b729e055381f71bd82ab`; binding `563e586e4652635c48d0507812e635cb04b0483b1244b65553fda12bec702923`.
- Play terminal `e07a4a91c7a7598ca8cf965afd3fc7e6beb72d19c71bce691825d8ce33a63303`; binding `d1cc0a6152d2a80b2b5a614cdb993ac6312ab6cec61be3f338fcedaf9fe809e4`.
- La primera compilación R3 sólo falló en un import de extensión `chatSql` faltante en el nuevo fixture. Se corrigió ese import; no se contó como ejecución de pruebas ni se cambió la protección de producción para obtener PASS.
- La revisión adyacente separó validación estricta de elegibilidad del adaptador booleano: el dispatch conserva denegaciones tipadas hasta el aislamiento por episodio, sin convertir un STOP concurrente en un error global ni ocultar corrupción de input/ref/owner/hash.

Los reportes independientes R1/R2 permanecen FAIL; estas cifras son aceptación host, no PASS de fase. No hay formatos durables nuevos, migración, eliminación de datos, cambio de menú ni avance P2. Se mantiene la limitación sin validación Android física/proveedor vivo/APK/deploy y sin downgrade binario soportado.


## Cierre del gate P1

Revisión independiente R3: **PASS acotado a implementación y host** sobre `6bfd252a7e516cc25144194913a93b13b4dc8c67`. El revisor verificó ambas cohortes completas de 798 casos y repitió de forma independiente 62 casos Full / 62 Play sin fallos, errores ni omisiones. R1/R2 siguen FAIL históricos. [Checkpoint público y límites](BOT_PORTABILITY_P1_CHECKPOINT.md).

P3 prepara un APK v98 bajo la identidad DEBUG original y regresión del source final si cambia la versión. Esa preparación no implica entrega, instalación ni continuidad física Android. P2 permanece bloqueada por D2: el usuario debe decidir cuotas finitas de costo/autonomía y política de parada antes de activar un árbol nuevo. No se presume presupuesto ilimitado.


### Gate P3: drift de metadata antes del proveedor (PASS independiente host y APK)

La regresión v98 original conservó un fallo real (797/798 Full; Play no ejecutado): dos resultados nativos concurrentes dejaron Central sin llamada al proveedor. El diagnóstico acotado reprodujo `context_protection_changed`, no sólo un timeout. La validación del transcript canónico sí pasó; el fingerprint global también incluye cards nativas de misión excluidas del payload. No se elimina esa protección ni se aumenta el timeout.

El delta permite descartar una solicitud preparada únicamente mediante una señal tipada privada del runtime, ligada a esa instancia y token, después de validar fuentes y autoridad de memoria originales y antes del proveedor. Como máximo se reconstruye una vez por intento preparado, pasando otra vez por los gates normales; un segundo drift falla cerrado. Una compacción ya intentada deshabilita esa reconstrucción: puede haber llamado al proveedor de resumen o persistido artefactos. Si la solicitud reconstruida necesita ahora compacción, falla por presupuesto antes de iniciarla; el refresh no autoriza un nuevo efecto de resumen antes de revalidar el ticket original. Los errores de fuente, owner, token, cancelación, presupuesto o proveedor no activan este camino; una señal reenviada por el proveedor tampoco lo activa.

La nueva solicitud es inmutable y conserva el ticket original de memoria y el mismo conjunto de owners; no adopta una nueva época de permiso. La preparación puede renovar la lease de la misma sesión existente mediante su identidad de productor original; no crea otra sesión ni escribe contenido de memoria. Se recalculan targets de reacción en memoria y se omite el segundo incremento de actividad (`activityAttempt=false`). No se reinicia el ciclo, no se repite herramienta y no se agrega retry de proveedor. El delta y el APK recibieron PASS independiente; la entrega e instalación no se presumen.


**Candidato host v98, posteriormente aceptado por revisión independiente:** Full y Play pasan cada uno 92 clases / 833 casos únicos (cohorte P1 de 90/798 más suplemento de 2/35), cero fallos, errores u omisiones, con fuentes y artefactos inmutables. El foco Full de 5/64 es evidencia separada, no se suma. Las 12 regresiones nuevas deterministas ejercitan el runtime y cards/compactor nativos: refresh único, segundo drift, fuente alterada, revocación de owner/epoch, cancelación, no transferencia de request/token, señal reenviada y cero resumen/artefacto ante presupuesto nuevo. Además, 16 JVM frescas repiten los 14 casos de messaging sin fallos, conservando el timeout original. No se suman esas repeticiones al total único.

Se conserva como negativo el primer candidato de fixtures: 52/63; los wrappers Kotlin heredaban el método default Java `usesPreparedRequests=false`. Se corrigió la delegación explícita para ejercer el adapter real, sin reducir aserciones ni producir una señal sintética de permiso. La primera aceptación v98 797/798 y su reproducción diagnóstica siguen registradas como fallos históricos.


PASS independiente posterior sobre source `a50dd3ab9b4029af3a88a5260e59183349bacc4e` y APK firmado `d70949e04935964d61ab6b7e9d352308c1ef7e1ed602fa6d8bb4b36e9676dd54`: el evaluador verificó las cohortes completas y reejecutó 64 casos Full / 64 Play, además de inspeccionar identidad, certificado original contra v97 y bytes del APK. [Checkpoint público del gate](BOT_PORTABILITY_V98_DELIVERY.md). La documentación posterior no cambia el source compilado ni el artefacto. P2 sigue esperando D2; no se acredita entrega/instalación física ni proveedor vivo.
