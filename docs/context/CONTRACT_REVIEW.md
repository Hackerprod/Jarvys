# Revisión técnica de contratos de contexto de Jarvys

Fecha de referencia: 9 de octubre de 2026 UTC.

**Referencia auxiliar de los contratos aceptados R1–R6 y las precisiones C1–C3. [SPEC.md](SPEC.md) es la especificación normativa final y prevalece ante cualquier ambigüedad.** [AUDIT.md](AUDIT.md) conserva el mapa A–F de evidencia y alternativas iniciales. Este documento expone el razonamiento y los contratos revisados sin constituir un segundo diseño normativo.

**Estado: diseño aceptado; implementación no iniciada; APPLY real bloqueado.** UX34 va al final de la cola. La fase 0 solo puede comenzar cuando estén cerrados UX33 y todos los pendientes anteriores. El respaldo documental no inicia ninguna fase. La aceptación del diseño no acredita pruebas ejecutadas, durabilidad real, eficacia semántica ni permiso para activar el mecanismo.

R1–R6 cierran el alcance del experimento, las operaciones sobre resúmenes, la autoridad canónica, los snapshots por llamada, la recuperación física y los modos. C1 separa validación mecánica y semántica; C2 fija un snapshot coherente de todos los insumos de petición; C3 conserva verificación incremental bajo el coordinador único. Estas precisiones se incorporan en las secciones correspondientes y se resumen en la sección 11.

## 1. Base de código y diferencias comprobadas

La REV1 se basó en `d1ed8b4622d300ea919188ab1d3a58f916aa9f07`. Esta revisión focalizada toma `5437c6de0e96d3e1bf125cf3096a935636361bda`, base seleccionada para el diseño. La comparación posterior con `4801676` no detectó cambios relevantes adicionales en el núcleo examinado. Fuente: https://github.com/Hackerprod/Jarvys/tree/5437c6de0e96d3e1bf125cf3096a935636361bda .

Se compararon los componentes relevantes. LocalRunStore, CoreAgentLoop, ConversationTurn, AgentForegroundService y el manifiesto principal no cambiaron entre esas bases. En CoreAgentRuntime se añadieron dos líneas al construir herramientas de conectores: filtrar operaciones cuyo acceso de servicio no está concedido (`:1845–1846`). El conector Gmail y su gestión de permisos sí avanzaron; se leyó su entrada de contexto/autonomía actual. No se considera auditada aquí toda la funcionalidad Gmail nueva.

Rutas citadas: `app/app/src/main/java/com/jarvys/agent/`, excepto Gmail bajo `app/app/src/full/java/com/jarvys/agent/` y manifiestos/rutas explícitas.

Hallazgo que matiza R3: `CoreAgentRuntime.userMessagesFromHistory:1174–1185` **ya exige simultáneamente rol user y Kind.MESSAGE**. Por tanto, no se afirma que el resumen COMPACTION_SUMMARY actual se convierta hoy en autorización solo por tener rol user. El diseño nuevo debe conservar esa defensa y separar formalmente la fuente de autoridad de la proyección; no corregir un fallo de elevación de permisos que esta inspección no demostró.

No se ejecutaron pruebas durante esta revisión. Las trazas y resultados esperados siguientes son propuestas sintéticas; no representan conversaciones ni estado de una instalación particular.

## 2. Matriz de resolución R1–R6

| Punto | Decisión de esta REV2 | Sustituye o precisa | Componentes afectados en una futura implementación | Prueba decisiva propuesta |
|---|---|---|---|---|
| R1 | Experimento acotado de selección de texto antiguo del asistente, con conjunto de elegibilidad anotado y condiciones deterministas; no afirmar eficacia general ni contexto acotado | B1/B5/B6, D1 y E4 | ConversationContextProjection propuesta; metadatos en LocalRunStore; evaluación por clases | Comparar éxito, coste y punto de bloqueo con compactación actual, incluyendo herramientas/recuperaciones acumuladas |
| R2 | PURGAR permite ORIGINAL y SUMMARY elegibles; RESUMIR solo ORIGINAL; mismo alias y edad original | B3/C5 | Validador por operación, cabeceras y prompt mínimo | ORIGINAL → SUMMARY → EXCLUDED → leer original íntegro sin reactivación permanente |
| R3 | Resumen de assistant se serializa como assistant en todos los adaptadores cubiertos; autoridad se deriva de registros canónicos admitidos por política, nunca de resumen | B3/A7 | ConversationTurn, serializadores, CoreAgentRuntime, fuente de beginAgentRun | Resumen con «aprobado»/email nuevo no altera autoridad ni política |
| R4 | Diario como única fuente de la historia de envío en modo selectivo; snapshot nuevo por llamada; commit propio valida antes de incrementar revisión | A4/C6/C7 | Loop, store, transcript store, service, adaptadores de petición preparada | Dos herramientas y pie final aceptado sin duplicados; mutación ajena rechaza por obsolescencia |
| R5 | Recuperar o bloquear cola antes de append; no saltar corrupción intermedia; reconciliar escrituras ambiguas por identidad | A3/C6 | Coordinador central de LocalRunStore y todos sus escritores relevantes | A válido → B parcial → recuperación → C válido → segundo reinicio: A/C válidos, B sin efecto parcial |
| R6 | LEGACY, DRY_RUN, APPLY y PAUSED inequívocos; pausa conserva proyección y acepta mensajes nuevos como originales; downgrade no soportado por magia de esquema | D1/D3 | Estado de modo, loader, todos los triggers de compaction y rollback | APPLY → PAUSED → nuevo mensaje: no reaparecen excluidos ni entra bounded/sliding/all |

## 3. R1 y R2: retención, elegibilidad y límites del experimento

### 3.1 Qué se intenta medir

La variante inicial es un **experimento de selección de texto antiguo del asistente**. Puede ahorrar explicaciones descartables. No acota el historial total: usuarios, herramientas, recuperaciones y adjuntos permanecen fuera de la superficie de edición. Bajo cargas con muchas herramientas puede bloquearse antes que el compactor actual, o más tarde si las respuestas antiguas del asistente dominaban el coste. No se sabe sin medir.

El riesgo de crecimiento se evalúa con cargas reproducibles; no se adopta una cifra ilustrativa externa como estimación ni objetivo de ahorro de Jarvys.

No se da por ganadora una variante que envía menos tokens porque abandona la tarea antes. Separar ahorro condicionado a éxito de consumo de ejecuciones bloqueadas/fallidas.

### 3.2 Tabla de retención del primer experimento

Todas las protecciones usan diez interacciones cerradas más abiertas/inciertas, no filas. «Después» significa fuera de esa protección temporal, además de cualquier fijación vigente.

| Clase | Protección reciente | PURGAR después | RESUMIR después | Salida de vista activa en este experimento | Conservación/recuperación |
|---|---|---|---|---|---|
| Mensaje auténtico de usuario | Sí | No | No | No, salvo borrado explícito con su semántica actual | Original canónico; no confundir recuperación con petición nueva |
| ORIGINAL de texto assistant | Sí | Sí, si cumple predicado completo | Sí, si cumple predicado completo y ahorro | PURGAR lo excluye; RESUMIR reemplaza solo representación | Original permanece, alias estable |
| SUMMARY de fuente assistant | Hereda edad e interacción originales | Sí, si cumple predicado completo | No | PURGAR lo excluye; no retención perpetua obligatoria | Fuente original + operación + hash permanecen |
| Grupo de llamadas/resultados | Sí, y evidencia incierta fijada | No | No | No en esta fase | Grupo íntegro en proyección; archivo canónico con los límites heredados declarados |
| Resultado de buscar_historial/leer_original | Es parte de la interacción recuperadora | No | No | No mientras los grupos de herramientas estén fuera del alcance | El original leído mantiene su edad; el resultado nuevo tiene la del run actual |
| Adjuntos y representaciones multimodales | Sí | No | No | No en esta fase | Reglas actuales de fuente/pertenencia; no afirmar ahorro multimodal |
| Fuente fijada por obligación, decisión o incertidumbre | Independiente de edad | No | No | Solo tras liberar fijación y volver a evaluar clase/edad | Retener vínculo a registro fuente y motivo estructurado |
| Instrucciones, herramientas y permisos de aplicación | Siempre fuera de superficie | No | No | No mediante este mecanismo | Configuración/runtime separado, sin alias editable |

La recuperación paginada limita cada lectura, **no su acumulación**. Se contabiliza expresamente ese coste creciente. PURGAR un resumen evita una retención artificial adicional, pero no resuelve el crecimiento de las otras clases.

### 3.3 Predicado de elegibilidad realmente implementable

Para el experimento usar un corpus sintético/anotado con un manifiesto externo al prompt:

```text
structuralCandidate(record, snapshot) =
  snapshot.scope == ordinary_main_chat
  AND record.sourceRole == assistant
  AND record.contentClass == plain_text_message
  AND record.canonicalSourceAvailableAndComplete
  AND record.visibleAndNotRegenerated
  AND record.aliasPresentInSentSnapshot
  AND record.interactionClosedAndOutsideProtectedSet
  AND not record.hasActivePin

mechanicalFixtureEligible(record, snapshot) =
  structuralCandidate(record, snapshot)
  AND annotationAllows(record.uuid, record.sourceHash, corpusVersion)

semanticFixtureEligible(record, snapshot) =
  structuralCandidate(record, snapshot)

allowedOpsInMechanicalFixture(record) =
  ORIGINAL + mechanicalFixtureEligible → {PURGAR, RESUMIR}
  SUMMARY  + mechanicalFixtureEligible → {PURGAR}
  cualquier otra combinación → {}
```

**C1: separar dos evaluaciones.** El ensayo mecánico usa `mechanicalFixtureEligible` para verificar selección, serialización, commits, protección y recuperación frente a un conjunto permitido conocido. La evaluación semántica independiente usa `semanticFixtureEligible`, determinado únicamente por reglas estructurales del runtime. El oráculo externo puntúa propuestas de PURGAR/RESUMIR, compromisos, negaciones, cifras, decisiones, procedencia, retención excesiva y éxito posterior de la tarea; no decide previamente qué IDs reciben la marca editable ni retira casos difíciles mediante anotaciones ocultas. Ser candidato estructural no demuestra que una eliminación o resumen sea semánticamente correcto. Ambos brazos conservan los pins derivados de eventos y usan conversaciones sintéticas y herramientas sin efectos externos. Las mismas reglas por representación admiten PURGAR/RESUMIR sobre ORIGINAL y solo PURGAR sobre SUMMARY. No usar las anotaciones del ensayo mecánico para presentar como probada una selección semántica autónoma. Ambos resultados se reportan por separado; ninguno autoriza APPLY real por sí solo.

`annotationAllows` es una anotación de evaluación, no un detector semántico nuevo. El responsable del ensayo crea la anotación y fija/libera compromisos conocidos en los fixtures. No se implementa una casilla de confirmación por cada operación propuesta por el modelo: dentro del conjunto del ensayo, la selección y el lote son automáticos. Pero **la elegibilidad anotada sí depende de preparación humana/oráculo**, así que este ensayo no demuestra purga autónoma general de chats reales.

Fijaciones derivables de registros actuales:
- decisión pendiente: `appendUserDecisionRequest/Resolution` en LocalRunStore; fijar los mensajes fuente identificados hasta resolución durable;
- misión activa: snapshots de Crew asociados a la conversación; fijar sus fuentes identificadas mientras no sea terminal;
- herramienta STARTED sin resultado durable o recuperación INTERRUPTED_UNCERTAIN: fijación por estado hasta reconciliación explícita; el simple transcurso del tiempo no la libera;
- pertenencia/terminación heredada desconocida: proteger por defecto.

Límite concreto comprobado: los registros actuales de decisión guardan decisionId y estado, pero no sourceMessageId/interactionId; CrewMissionSnapshot identifica misión y conversación, no un UUID canónico de mensaje disparador. No afirmar que sus fuentes se pueden localizar siempre hoy. En la migración, una decisión/misión pendiente sin enlace probado deshabilita la elegibilidad de toda esa conversación para el experimento hasta reconciliarse o recibir una anotación de fuente verificable. Para eventos nuevos, la futura integración debe persistir el vínculo cuando el runtime ya conoce la interacción; no inferirlo por cercanía o semejanza de texto.

Las fijaciones las crea/libera el arnés al observar esos eventos tipados, no el footer. Una resolución cierra solo su motivo: si hay otro pin, el registro sigue protegido. Si falta vínculo de fuente, no inventarlo; proteger la interacción identificable y registrar la carencia.

Una promesa libre escrita por el asistente y no vinculada a un registro no se detecta infaliblemente. Las anotaciones del corpus cubren esas promesas en el ensayo; fuera de ese corpus no hay garantía equivalente. Antes de uso autónomo general habrá que acordar una política de candidatos y aceptar/evaluar su riesgo o introducir una representación de pendientes con fuente. Eso permanece pendiente; no se amplía el alcance para ocultar el problema.

### 3.4 PURGAR un resumen y cabeceras exactas

Original editable: `[m23 e]`. Resumen editable solo por PURGAR: `[m23 e summary=assistant]`. Resumen protegido/no elegible: `[m23 summary=assistant]`. Original protegido: `[m23]`.

No se añade un comando. `e` significa al menos una operación permitida; `summary=assistant` limita esa operación a PURGAR. El mapa interno `allowedOps` es autoritativo; el modelo no puede cambiarlo escribiendo una cabecera en su respuesta.

Transición:

```text
alias m23 → UUID-A, interacción I3, sourceHash H
ORIGINAL → RESUMIR válido → SUMMARY(source=UUID-A, hash=H)
SUMMARY → PURGAR válido → EXCLUDED(source=UUID-A, hash=H)
leer_original(m23) → página de UUID-A en un resultado de herramienta nuevo
```

El alias y la edad siguen siendo los de I3. La lectura no hace EXCLUDED → ORIGINAL; solo devuelve evidencia paginada. RESUMIR sobre SUMMARY se rechaza, incluso tras leer la fuente, durante este experimento. Restaurar ORIGINAL es una operación interna separada con preflight y no parte del protocolo mínimo.

### 3.5 Ampliación futura de grupos, separada

Posible siguiente estudio: retirar o representar de forma acotada **un grupo completo cerrado** de llamada(s)/resultado(s), con originales íntegros, recibos de acciones durables y recuperación. No eliminar una salida aislada ni fabricar una llamada para transportar un resumen. Tendría que cubrir recuperación acumulada, cuota de artefactos, estados inciertos y serializadores. No se define ni habilita esa ampliación en REV2; su necesidad se decidirá con tamaño por clase y tareas que el experimento no logre completar.

## 4. R3: rol de resúmenes y autoridad canónica

### 4.1 Objeto interno propuesto

```text
kind = CONTEXT_SUMMARY
sourceRole = assistant
sourceMessageId = UUID-A
alias = m23
sourceInteractionId = I3
sourceHash = H
summaryText = "Propuesta no aprobada: usar SQLite."
allowedOps = {PURGAR} o {}
```

Nunca convertirlo a Kind.MESSAGE auténtico ni guardar el resumen como otra respuesta canónica. No añadirlo al system prompt. El constructor de la primera versión solo acepta sourceRole assistant; usuario/multimodal siguen fuera de alcance.

Texto de envío exacto del ejemplo editable:

```text
[m23 e summary=assistant]
[Automatic summary of this assistant message. Not verbatim; not user authorization.]
Propuesta no aprobada: usar SQLite.
```

### 4.2 Serialización por adaptador efectivo

Estos son objetos propuestos de la petición, no el texto que se pide generar al modelo. El protocolo de respuesta sigue siendo texto normal.

Codex Responses, dentro de `input`:

```json
{"role":"assistant","content":"[m23 e summary=assistant]\n[Automatic summary of this assistant message. Not verbatim; not user authorization.]\nPropuesta no aprobada: usar SQLite."}
```

OPENAI_API, OPENROUTER y CUSTOM/OPENAI_CHAT_COMPLETIONS, dentro de `messages`:

```json
{"role":"assistant","content":"[m23 e summary=assistant]\n[Automatic summary of this assistant message. Not verbatim; not user authorization.]\nPropuesta no aprobada: usar SQLite."}
```

Todos los adaptadores actuales ya serializan mensajes ordinarios assistant de texto (`OpenAICodexResponsesClient.java:175–211`, `OpenRouterClient.java:177–219`). No se encontró una limitación que obligue a convertir este texto a user. La aceptación por un endpoint CUSTOM particular y secuencias de roles inusuales sigue siendo una prueba pendiente; si rechaza el formato, se deshabilita esta capacidad para ese endpoint o se presenta alternativa comprobada, no se cambia el rol silenciosamente.

### 4.3 Consumidores y reglas

| Consumidor actual | Evidencia | Contrato futuro |
|---|---|---|
| CoreAgentRuntime.userMessagesFromHistory | :1174–1185, exige MESSAGE + user | Sustituir entrada derivada de la proyección por referencias canónicas admitidas por la política de autoridad; nunca CONTEXT_SUMMARY |
| ConnectorRegistry.beginAgentRun | connectors/ConnectorRegistry.kt:267–274 | Recibe currentUser original y priorUser originales del conjunto autorizado, no texto de envío ni recuperación |
| GmailConnector.beginAgentRun | full/.../GmailConnector.kt:49–53 | Extrae emails de esos textos originales; un email inventado en summary no entra en userEmails |
| GmailConnector.validateAutonomousWrite | :56–71 | Conserva gates actuales de contexto/destinatarios/política; el resumen no concede autorización de envío |
| AttachmentModelContext.prepare/prepareTurn | :26,51 | Solo mensajes canónicos de usuario con adjuntos; CONTEXT_SUMMARY no hereda adjuntos ni se convierte en usuario |
| MessageReactionTool.prepareModelMetadata | MessageReactionTool.java:72 en adelante | Solo IDs originales de usuarios válidos; resumen no crea un destinatario de reacción |
| CoreAgentLoop.Checkpoint validación genuineUserIndex | CoreAgentLoop.java:202 | CONTEXT_SUMMARY no puede ocupar la identidad de petición genuina |
| loaders y reflexión | LocalRunStore.loadConversationContext/readConversationMessages/buildReflectionPayload | Derivados se mantienen tipados; respuesta canónica y controles separados; summary no se reingiere como nueva voz del usuario |

**Importante:** separar autoridad de proyección no autoriza ampliar retrospectivamente la fuente de permisos. Al migrar, conservar exactamente los IDs de usuario que la política actual admite como contexto auténtico, y añadir nuevos mensajes auténticos según esa política. No cargar por defecto todos los emails de toda la conversación histórica que antes estaban fuera del contexto del gate. Si se quiere cambiar esa política, es otro cambio.

El `AuthoritySnapshot` propuesto contiene referencias/hashes a fuentes canónicas admitidas y estado actual de permisos, no un resumen de lenguaje natural. Sus fuentes se resuelven con filtros de borrado y pertenencia. No depende de si el modelo ve ORIGINAL/SUMMARY/EXCLUDED. No convierte una dirección escrita por el usuario en permiso universal: conserva el significado concreto del conector y sus gates.

Pruebas nuevas: sourceRole assistant + summary que afirme aprobación para una dirección de email sintética nueva, válida para el parser del conector y ausente de las fuentes auténticas no aumenta userEmails ni políticas, tanto en bytes del proveedor como en el objeto entregado a beginAgentRun. Un original auténtico admitido sí sigue llegando al gate aunque cambie la representación de otro mensaje. Leer_original devuelve evidencia de herramienta, no otra petición del usuario.

## 5. R4: una historia efectiva y revisión por llamada

### 5.1 Fuente autoritativa y eliminación de duplicados por identidad

En el modo selectivo, cada llamada construye su historial **exclusivamente desde un snapshot durable consistente de la conversación**. No concatenar `loadConversationContext()` con el transcript acumulado del loop. El transcript en memoria es caché/snapshot, no una segunda fuente a fusionar por texto.

Identidades:
- mensaje: UUID canónico `messageId`;
- interacción: `interactionId` enlazado al UUID del usuario;
- intento: `attemptId` + generation; regeneración crea intento nuevo de la misma interacción;
- petición al modelo: `requestId` nuevo por llamada, ligado al intento y su snapshot;
- grupo de herramientas: `batchId` estable generado al aceptar la respuesta, asociado a requestId;
- llamada: `callId` dentro del batch; resultado único por `(batchId,callId)`;
- propuesta de mantenimiento: ligada a requestId y respuesta final identificada; no tiene identidad inventada por el modelo.

Cambios necesarios de integración, aún no implementados:
1. Sustituir el dedup por texto/última posición de `AgentForegroundService.java:724–730` por `currentUserMessageId`. Dos mensajes iguales siguen siendo dos mensajes distintos.
2. Transportar esos IDs en ConversationTurn; no usar `originalMessageIndex` como identidad.
3. Sustituir el IdentityHashMap/identidad de objeto de `MainChatTranscriptStore.attach:59–91` por identidades durables. Recargar objetos nuevos por llamada no debe registrarlos otra vez como llamadas nuevas.
4. Registrar user + apertura de interacción antes del primer snapshot. Conservar adjuntos en ese único registro. El assembler no añade otra copia desde `currentInput`.
5. Persistir resultados y su representación acotada antes de capturar el siguiente snapshot. Los originales/artefactos y la representación de envío tienen referencias/hashes explícitos; recargar no mezcla un resultado completo con una segunda copia recortada. Conservar la semántica de `completeContentRequired` y el recibo real de efectos aunque su representación de envío sea un error de presupuesto.

**Adaptadores:** introducir una entrada explícita de “conversación ya preparada” que serializa exactamente sus turns y no añade `userPrompt` al final. Hoy ambos clientes añaden un mensaje user si el historial no termina en usuario, incluso con un prompt vacío. Pasar simplemente `prompt=""` no basta después de tool_result. La nueva entrada evita esa adición; las llamadas legacy conservan su contrato anterior.

`Continue.` pasa a ser señal interna del loop, sin mensaje canónico ni elemento user en la petición preparada. El modelo recibe resultados de herramientas y continúa el protocolo normal. No cuenta como interacción, no es una petición del dueño ni una fuente de autorización. Si un endpoint particular exigiera otra forma, deberá comprobarse y diseñarse explícitamente; no convertir esa señal en un usuario genuino.

### 5.2 Semántica de revisión

Usar una revisión semántica `contextRevision`, comprometida en el diario. Para integridad física, una cadena de hashes de registros y límites de bytes; no hace falta otro contador de “turnos” que duplique la revisión. Cada registro nuevo contiene transactionId único y prevRecordHash. Los registros que afectan contexto incrementan contextRevision exactamente una vez por transacción lógica; los restantes mantienen el mismo valor y siguen formando parte de la cadena íntegra.

La revisión cubre **selección efectiva y elegibilidad**, no solo texto visible.

| Evento | ¿Incrementa contextRevision? | Motivo |
|---|---|---|
| Usuario nuevo + apertura, despacho de interrupción, invalidación/regeneración | Sí | Cambia historia/interacciones/protección |
| Petición de interrupción que invalida el intento actual, aunque aún no esté despachada | Sí | Invalida el snapshot/generation para nuevas acciones y mantenimiento |
| Alias nuevo, pin/unpin, cambio de modo/política de retención | Sí | Cambia referencias o allowedOps |
| Batch de tool calls aceptado, STARTED, resultado durable/reconciliación | Sí | Cambia protocolo/evidencia o estado incierto y elegibilidad |
| Respuesta final + cierre + lote de mantenimiento | Una vez, al final del commit | Mutación propia atómica; no tres revisiones intermedias |
| Borrado de fuente/conversación | Sí y/o tombstone terminal de sesión | Impide reutilizar fuentes/proyección |
| Registro de requestId y métricas, título, estado visual de tarjeta, acuse de reflexión | No, si no altera la petición ni protección | Telemetría/presentación; la cadena física sí cambia |
| Reacción, memoria/skills/configuración o capacidad efectiva | Sí cuando cambia datos/instrucciones de la petición | No clasificarlas automáticamente como “solo UI” |
| Evento nuevo desconocido al lector selectivo | Bloqueo de interpretación/append | No adivinar que no afecta contexto |

**C2: snapshot coherente de la petición completa.** Capturar en una versión coordinada e inmutable: `contextRevision`, interacción, intento, `generation`, mensajes y proyección, hashes y `allowedOps`, fuentes de autoridad, configuración e instrucciones efectivas, memoria/skills que se inyecten, herramientas y sus esquemas/capacidades, proveedor/modelo, ventana y límites/reserva/margen de presupuesto. El `requestPolicyFingerprint` se deriva de esos mismos valores congelados. Serializar y medir exclusivamente esa captura; no volver a leer configuración o herramientas mutables entre captura, presupuesto y envío. El hash del cuerpo preparado permite asociar la evidencia de transporte a la captura utilizada.

Si alguna fuente vive fuera del diario, su versión debe participar en la misma disciplina de captura e invalidación. Puede capturarse bajo coordinación común o mediante una captura con validación de versiones y reintento antes de enviar; no basta leer varios estados por separado y calcular al final un fingerprint. Antes de aceptar efectos nuevos o mantenimiento, comparar revisión, generación y versión/fingerprint según ese contrato. Una modificación relevante posterior invalida la propuesta aunque los bytes del snapshot anterior sean internamente válidos.

Para configuración almacenada fuera del diario, capturar un `requestPolicyFingerprint` de instrucciones/capacidades/modelo relevantes, además de generación y revisión. La integración debe coordinar sus cambios con invalidación observable antes de aceptar la propuesta; verificar un hash no elimina una carrera si los escritores de esa configuración no participan en esa disciplina. Esa condición es un gate de implementación, no una garantía del código actual. No se espera que el modelo escriba este hash.

Cada llamada captura su **propia** revisión después de los resultados durables anteriores. Los checkpoints del mismo run no invalidan retroactivamente una propuesta futura: esa propuesta todavía no existía. Una propuesta de R3 se compara con la revisión de R3, no con R1.

### 5.3 Traza sintética completa con dos herramientas

Supuestos: E es una respuesta antigua ORIGINAL de assistant, alias m7, fuente íntegra y elegible según el manifiesto del ensayo. Está fuera de las diez interacciones protegidas. H es el historial activo previo, incluido E. La conversación está en APPLY; revisión inicial 40. No hay cambios concurrentes.

| Paso | Interacción / intento / request | Revisión capturada → actual después | Fuente/historia efectiva o escritura | Elegibilidad |
|---|---|---|---|---|
| 0. Base | I20 cerrada, intento previo | — → 40 | Diario contiene H y metadatos; no petición en vuelo | E permite PURGAR/RESUMIR |
| 1. Usuario nuevo U21 | I21 / A21 | — → 41 | Una transacción guarda U21, alias y OPEN I21; no duplicación por argumento prompt | E sigue elegible; I21 protegida |
| 2. Primera llamada | I21 / A21 / R1 | 41 → 41 | Snapshot durable 41: H + U21. requestId registrado como metadato sin cambiar contexto. `currentInput` no se añade otra vez | SentEligible(R1)={E} |
| 3. Respuesta pide T1 | I21 / A21 / R1, batch B1/call C1 | validar 41 → 42 | Persistir intención B1/C1 y texto propio asociado. Sin efecto externo aún | I21 y B1 protegidos |
| 4. Inicio T1 | I21 / A21 / B1/C1 | 42 → 43 | STARTED durable; validar generación antes de iniciar efecto | Evidencia de C1 fijada hasta resultado |
| 5. Resultado T1 | I21 / A21 / B1/C1 | 43 → 44 | Resultado/recibo durable y representación de envío; el loop no vuelve a añadirlo por su cuenta | E igual; B1 completo pero aún no editable |
| 6. Segunda llamada | I21 / A21 / R2 | 44 → 44 | Nuevo snapshot: H + U21 + B1/C1/resultado1, cada identidad una vez | SentEligible(R2)={E} |
| 7. Respuesta pide T2 | I21 / A21 / R2, batch B2/call C2 | validar 44 → 45 | Nueva intención B2/C2 durable | I21/B2 protegidos |
| 8. Inicio T2 | I21 / A21 / B2/C2 | 45 → 46 | STARTED durable antes del efecto | C2 fijado |
| 9. Resultado T2 | I21 / A21 / B2/C2 | 46 → 47 | Resultado2 durable. Ningún transcript se concatena con el recargado | E igual |
| 10. Llamada final | I21 / A21 / R3 | 47 → 47 | Snapshot: H + U21 + grupo1 + grupo2. No user `Continue.` ni copia U21 | SentEligible(R3)={E} |
| 11. Recibir final y pie | I21 / A21 / R3 | 47 → 47 | Parseo de respuesta completada; cuerpo F21 y propuesta RESUMIR m7; cálculo puro, todavía sin guardar | Validar E contra snapshot R3 y estado actual |
| 12. Commit final único | I21 / A21 / R3 | validar 47 → 48 | Una fila: F21 canónico, CLOSED I21, operación E→SUMMARY, índices/revisión y receipt de R3. fsync antes de UI | No usar el cierre propio para ampliar SentEligible(R3) |
| 13. Nueva petición U22 | I22 / A22 | — → 49 | U22 + OPEN I22. I21 ahora pertenece a las diez cerradas recientes; I22 abierta | Recalcular, SUMMARY E solo permite PURGAR |
| 14. Primera llamada nueva | I22 / A22 / R4 | 49 → 49 | Proyección de H con E resumido + U21 + grupos1/2 + F21 + U22. Sin pie R3 | Snapshot nuevo, allowedOps por clase |

Las revisiones concretas son ilustrativas de esta clasificación, no números observados. El número de eventos puede variar; la propiedad es que cada snapshot incluye todos sus resultados durables previos y que el commit final compara su base antes de aplicar sus propias mutaciones.

### 5.4 Concurrencia, cierres y efectos ya ejecutados

Si durante R3 llega un usuario nuevo/cambio de retención/regeneración, contextRevision pasa a 48 por esa mutación. R3 sigue ligada a 47 y su mantenimiento se rechaza STALE. Su cuerpo se conserva o clasifica según el estado real de A21; no se etiqueta como respuesta de I22. Cambiar solamente la conversación visible en UI no cambia la revisión de la conversación de R3; guardar en su sesión original si no fue borrada.

No guardar primero F21, incrementar revisión y comparar después el pie contra 47: eso produciría falso STALE. Validar una vez dentro del lock y comprometer conjuntamente cuerpo, cierre y lote. La propuesta solo apunta a fuentes antiguas del snapshot; no afirma haber resumido la propia respuesta F21. No hace falta una cola asíncrona nueva para aplicar ese lote puro.

Si una nueva entrada cancela el run mientras una herramienta ya actúa, **su resultado debe seguir siendo registrable**. No rechazar evidencia de un efecto real solo porque su snapshot quedó viejo. Fijar nuevos comienzos de herramientas mediante generation, y admitir el resultado vinculado a una intención existente como observación tardía, con nueva revisión. Nunca ejecutarlo otra vez para “recuperar” el resultado perdido.

Preservar los puntos de durabilidad existentes: INTENT → STARTED → efecto → RESULT. Las observaciones NEVER_LAUNCHED/INTERRUPTED_UNCERTAIN se generan solo al reconciliar un intento cuyo worker terminó o no puede reanudarse; recargar un snapshot de un worker activo no debe inventar que sus herramientas pendientes fueron interrumpidas.

La reflexión automática existente se encola después de la respuesta hoy. No reutilizar ese trabajo como motor oculto del nuevo mantenimiento ni afirmar que su programación actual es transaccional. Su efecto posterior sobre instrucciones/memoria invalida el fingerprint correspondiente para una llamada concurrente.

## 6. R5: recuperación física y siguiente append

### 6.1 Hechos y topología

`LocalRunStore.appendSessionRowLocked:1659–1670` añade JSON+LF, flush y fsync, pero no repara la cola antes de escribir. `readConversationRows:1054–1067` omite JSON inválido, incluso intermedio. La secuencia A válido, B parcial y C añadido sin reparar puede concatenar B+C en una línea inválida; no hace falta un contador para deducir ese riesgo. No se reprodujo mediante fallo real en esta auditoría.

Se leyeron los tres manifiestos fuente y los merged manifests Full/Play existentes: no declaran `android:process`, `isolatedProcess` ni `multiprocess`. Eso respalda que los escritores de este diario son múltiples objetos/hilos del proceso predeterminado. El sabor Full sí lanza subprocesos Linux; no se encontró un escritor directo del diario de conversaciones en esa ruta. No se afirma que toda la APK carezca de subprocesos. Los manifiestos generados no se recompilaron aquí.

Para esta topología basta un coordinador compartido por ruta canónica o el lock global actual centralizado. Un `synchronized` por instancia no basta. No se requieren locks de archivo adicionales por defecto; si se introduce un escritor desde otro proceso, se exige coordinación OS o único servicio dueño del almacenamiento antes de habilitarlo.

### 6.2 Framing y replay propuestos

Cada fila nueva versionada contiene transactionId, prevRecordHash, contextRevision y operación lógica. El checksum incluye su contenido completo y el enlace al registro anterior. La revisión solo incrementa cuando corresponde según la clasificación de la sección 5.2. No derivar revisión contando las filas que el parser pudo salvar.

Al migrar, validar primero todo el prefijo legado según su esquema conocido y sellar su digest/límite de bytes en un registro de migración. No exigir al legado un checksum/transactionId que nunca guardó ni inventar un receipt antiguo: sus UUID/offsets se vinculan mediante la migración idempotente. Si su última fila JSON completa carece de LF, preservarla y cerrar el delimitador antes de sellar; si su estructura es ambigua, bloquear migración. Los UUID y aliases se vinculan a esos registros sin cambiar sus textos. El replay nuevo verifica el prefijo sellado y todos los registros posteriores en orden. Alias, tombstones, modos y commits se aplican solo desde la secuencia íntegra; una línea desconocida no se salta como si fuera telemetría. Nunca registrar el pie del modelo como comandos ejecutables históricos.

El parser físico valida UTF-8 y consume todos los bytes del registro JSON; no basta que JSONObject acepte un prefijo. La búsqueda del último límite válido se hace desde una cadena verificada o checkpoint validado contra esa cadena, no desde el último `}` encontrado.

### 6.3 Política de recuperación antes de cualquier append

**C3: verificación incremental bajo un único coordinador.** El coordinador conserva por diario un estado verificado compuesto por identidad del archivo y esquema, último offset completo verificado, hash de cola, `contextRevision`, estado proyectado y recibos/identidades reconstruibles. El arranque, la primera apertura sin ese estado, una migración, un error de escritura/sync, una discrepancia de identidad/tamaño o una modificación no reconocida requieren reconstrucción/recuperación desde un prefijo o checkpoint verificable; si no existe una base confiable, validar la cadena completa.

En el camino normal, todos los escritores participan en el coordinador. Cada append valida su nueva fila, enlace al hash verificado, esquema, identidad y transición semántica contra ese estado; escribe y sincroniza antes de avanzar el cursor y publicar el nuevo estado. El snapshot reutiliza el estado proyectado verificado. No se reescanea todo el historial en cada append o snapshot. Ningún caché descartable sustituye al diario ni convierte una cola no verificada en una transacción confirmada.

Ante una escritura ambigua se invalida la confianza necesaria para continuar y se reconcilia el mismo `transactionId`; no se avanza optimistamente el cursor. Una discrepancia no reconocida no permite continuar solo porque el tamaño parezca compatible. C3 conserva íntegramente las reglas siguientes de cola parcial, corrupción intermedia y bloqueo; cambia el coste del camino normal, no la garantía exigida.

Bajo el mismo coordinador de escritura, cuando se requiera recuperación:

| Estado encontrado | Acción propuesta antes de escribir C |
|---|---|
| Archivo válido terminado en LF | Verificar/reconstruir cadena y revisión desde una base confiable; tras arranque o error de append, sincronizar el límite recuperado antes de efectos/publicación, incluso si LF ya existe; después continuar con verificación incremental |
| Último JSON completo sin LF, esquema/checksum/base/transactionId válidos | Preservarlo: añadir LF y hacer sync; reconciliarlo como el mismo commit, nunca duplicarlo; después permitir C |
| Último sufijo cortado o UTF-8/JSON incompleto, sin registros posteriores | Preservar bytes en cuarentena privada no indexada con digest/offset; truncar al último límite verificado y hacer sync de reparación; solo después permitir C |
| JSON parseable pero checksum/base incorrectos, incluso al final | No confundir con escritura parcial demostrada; bloquear escritura normal y requerir recuperación/investigación explícita |
| Esquema futuro/no soportado | Bloquear; no truncar datos que este lector no entiende |
| Corrupción en una fila con datos/registros posteriores | Bloquear; no saltar ni conservar solamente el sufijo “bueno” automáticamente |
| write/flush/sync devolvió error después de producir bytes | Estado COMMIT_UNKNOWN; congelar nuevos efectos/publicación final, reabrir y reconciliar el mismo transactionId antes de reintentar |

La recuperación no depende de recordar COMMIT_UNKNOWN en RAM: tras reiniciar se considera no reconocido el ack de los bytes recuperados hasta validarlos y resincronizar el límite, aunque la última fila ya termine en LF. Para COMMIT_UNKNOWN: si aparece una transacción completa válida, volver a sincronizar y devolver su receipt original. Si solo aparece su cola parcial, aplicar reparación y determinar que el commit no está comprometido antes de permitir reintento **con el mismo ID**. Si no hay bytes de esa transacción, un reintento puede guardar la misma respuesta/operación; no se vuelve a llamar a herramientas externas. Si sync sigue fallando, permanecer bloqueado, sin afirmar durabilidad.

El mantenimiento afecta proyección local, no vuelve a ejecutar acciones. Si el resultado perdido pertenecía a una herramienta que pudo producir efectos, conservar/reconstruir su estado incierto desde su intención STARTED previa. No inferir ausencia de efecto externo por ausencia de la fila resultado.

La cuarentena no es una nueva fuente de recuperación del agente; se elimina con el borrado explícito de la conversación y no debe restaurar contenido deliberadamente eliminado. Su propósito es preservar evidencia del daño mientras se decide una recuperación. Limitarla por archivo/incident ID y no duplicarla en cada arranque.

Para crear archivos de diario/artefacto, o reemplazarlos mediante rename, también verificar sincronización del directorio padre donde sea compatible con el almacenamiento de Android. No extender las garantías de fsync del diario a archivos que no tengan esa secuencia. Un artefacto referenciado por un commit debe estar disponible/validado primero; un artefacto huérfano es recuperable como basura, un commit que referencia fuente no durable no es aceptable.

### 6.4 Todos los escritores bajo la misma disciplina

La entrada central actual es `appendSessionRowLocked`; deben participar también mutaciones de metadatos que influyen en visibilidad/retención. Familias:

- mensajes usuario/asistente, adjuntos, respuestas de error y cierres;
- intención/STARTED/resultado y propiedad de artefactos del modelo;
- interrupción pendiente y despacho;
- regeneración, borrado individual y tombstone de conversación;
- solicitudes/resoluciones de decisiones y nuevos pins;
- compactación y migración de modo/proyección;
- snapshots de misión Crew asociados;
- mensajes proactivos/programados y claims de respuesta;
- eventos de imágenes/archivos entregados;
- tarjetas/progreso/reflexión/reacciones/títulos/acks, aunque algunas filas no incrementen contextRevision.

Llamadores actuales a revisar al implementar: MainActivity, AttachmentDraftViewModel, AgentForegroundService, CoreAgentRuntime/MainChatTranscriptStore, MemoryReflectionCoordinator, ProactiveInteractionDispatcher/ProactiveAgentProcessor, ScheduledTaskProcessor/TaskNotifier. Los registros externos de permisos/memoria deben anunciar invalidación de fingerprint cuando cambien la petición; no se transfieren a JSONL todos sus datos.

`steps.jsonl` de las ejecuciones legacy usa FileWriter/flush sin el mismo fsync (`LocalRunStore:1838–1842,2177–2181`). No se le atribuye la garantía del diario de conversaciones. Si se convierte en fuente necesaria de la proyección, debe entrar explícitamente en el contrato; la primera propuesta usa el diario de conversación y artefactos propios.

### 6.5 A → B parcial → C y reanudación

```text
1. A completo + LF + sync; estado lógico r.
2. Comienza B; corte antes de fila completa; no hubo receipt durable de B.
3. Reinicio: validar A y localizar inicio del sufijo B.
4. Cuarentena B, truncate a fin de A, sync de reparación.
5. Replay termina en r; no media operación de B.
6. Append C con su transactionId y base r; sync; receipt r+1 si C es relevante.
7. Segundo reinicio: A y C íntegros; B no cambia proyección.
```

Variante: B está completo y válido pero falta LF. Recuperación conserva B, añade LF+sync, reconstruye su receipt y revisión; C se enlaza a B. Tras otro reinicio quedan A/B/C. No eliminar una fila válida por faltarle un salto de línea.

El envío al modelo solo se reanuda después de reparación sincronizada, replay íntegro, revisión/aliases/proyección coherentes y reconciliación del intento. Mostrar parcialmente la UI no habilita por sí solo modelos ni append. Si una fila válida se escribió pero no recibió ack antes del crash, el transactionId evita duplicar respuesta o lote.

## 7. R6: modos, pausa y versiones antiguas

### 7.1 Estados operativos

No usar OFF con dos significados. Los nombres siguientes pueden ser internos; no obligan a añadir cuatro botones a la UI.

| Estado | Historia efectiva | Nuevos mensajes | Pies y elegibilidad | Recuperación | Compactor heredado |
|---|---|---|---|---|---|
| LEGACY, nunca migrada o transición explícita concluida | Loader/compactor anterior | Semántica actual | Protocolo selectivo deshabilitado | Capacidades existentes | Conserva sus triggers actuales |
| DRY_RUN, esquema/proyección selectivos creados | Proyección inicial validada; propuestas no la modifican | ORIGINAL con IDs/interacción; presupuesto selectivo | Calcula allowedOps y registra propuesta/resultado hipotético; nunca aplica | buscar_historial/leer_original disponibles | No ejecuta all/sliding/bounded/overflow/manual incompatibles |
| APPLY | Proyección durable con ORIGINAL/SUMMARY/EXCLUDED | ORIGINAL; protección vigente | Puede aplicar lote válido contra snapshot propio | Disponible | Solo rutas compatibles con la proyección aprobada; en primer experimento no compactor viejo |
| PAUSED, proyección selectiva congelada para operaciones nuevas | Estados existentes SUMMARY/EXCLUDED se respetan; no restauración masiva | Se añaden ORIGINAL y consumen presupuesto | No `e`; no propuestas aplicables. IDs/procedencia siguen disponibles para recuperación | Sigue disponible | Nunca se reactiva por estar fuera de APPLY |

“Congelada” significa no aceptar cambios automáticos a las representaciones ya establecidas. La conversación y sus interacciones siguen creciendo. El preflight, los filtros de borrado, la protección y el registro de herramientas siguen funcionando en PAUSED. Un mensaje nuevo no se resume ni excluye automáticamente. La búsqueda/lectura no reactiva representaciones.

DRY_RUN es ahora explícitamente una variante selectiva sin aplicación. **No afirmar equivalencia de todas sus peticiones con LEGACY**: cambia el contrato de selección/protección y puede bloquearse antes. La propiedad a probar es que una propuesta dry-run no cambia la proyección de su propia variante. La comparación con LEGACY se hace como brazo independiente sobre las mismas tareas. Esto reemplaza el criterio demasiado ambiguo de la fase 3 de REV1.

### 7.2 Transiciones

| Transición | Precondición y efecto |
|---|---|
| LEGACY → DRY_RUN | Migración idempotente, fuentes/aliases/proyección de partida coherentes y preflight. Si reconstruir lo necesario no cabe, no activar por purga previa encubierta |
| DRY_RUN → APPLY | Contratos y evaluación aprobados; fuentes/recuperación listas; nuevo snapshot/revisión; ninguna propuesta vieja se ejecuta por cambiar la bandera |
| APPLY → PAUSED | Commit de modo; invalida propuestas en vuelo; conserva SUMMARY/EXCLUDED; futuras peticiones sin e y con preflight |
| PAUSED → APPLY | Nuevo commit y snapshot, recalcular edad/pins; no replay de propuestas antiguas |
| DRY_RUN → PAUSED | Conserva esquema/proyección, deja de solicitar propuestas; no activa compactor legacy |
| APPLY/PAUSED → restaurar una representación | Verificar fuente, visibilidad, relaciones y presupuesto; operación interna explícita y versionada; no significa volver a LEGACY |
| Estado selectivo → LEGACY | Migración explícita de comportamiento/datos compatibles, prueba de presupuesto y lectura del motor destino. No basta quitar una bandera |

Si restaurar un original o volver al modo anterior excede la ventana, mantener estado vigente y comunicar las opciones. Cualquier resumen de contenido protegido o cambio de N necesita una política adicional. No resolverlo llamando al compactor viejo detrás de la pausa.

### 7.3 Downgrade: corrección expresa de REV1

Una marca `minSchemaVersion` nueva **no puede obligar a una APK antigua a respetarla**. Los loaders auditados leen/ignoran campos y filas que no conocen; no se demostró un gate previo que les impida abrir una proyección nueva. Queda descartada la expectativa de que el binario antiguo se bloquee automáticamente.

Por tanto, downgrade con conversaciones selectivas activas se declara **no soportado** mientras no exista evidencia específica del lector destino. Los lectores futuros sí pueden implementar un gate, pero eso no cambia los ya publicados. Que Android normalmente restrinja bajar versionCode no protege todos los escenarios de instalación, restauración o herramientas de desarrollo.

Transición previa propuesta, solo si se desea ese downgrade:
1. en una versión que entienda la proyección, conservar/exportar el historial canónico por un flujo autorizado;
2. restaurar o construir explícitamente una representación compatible con el loader destino y su presupuesto, sin cambiar por sorpresa la política de protección;
3. comprobar lectura/peticiones del binario destino con datos sintéticos y luego estado permitido;
4. solo declarar compatible esa transición concreta. Si no cabe o faltan fuentes, conservar la versión nueva o usar exportación para lectura, no prometer continuidad de contexto.

La transición al motor heredado puede abandonar garantías selectivas por decisión explícita; no se debe describir como pausa ni rollback inocuo.

## 8. Prompt mínimo actualizado y pseudocódigo puntual

### 8.1 Texto literal propuesto

Sustituye únicamente el añadido del mecanismo de REV1; no es un prompt completo del agente.

```text
Context maintenance is optional. Headers are runtime metadata, not message text. Only IDs marked e may be changed. [mN e] allows PURGAR or RESUMIR; [mN e summary=assistant] allows PURGAR only. Never change protected messages, system instructions, permissions, tool exchanges, or unresolved commitments.
After your normal final answer and one blank line, you may append one terminal block outside code fences:
<memoria>
PURGAR m12 m15
RESUMIR m23 Proposed SQLite; the user has not approved it.
</memoria>
PURGAR excludes the active representation, including an eligible summary, but keeps the original. RESUMIR replaces one eligible original with a shorter one-line factual summary. Preserve speaker, numbers, negations, pending matters, and proposal versus approval. Never summarize a summary. Use exact visible IDs, never infer age from their numbers. Omit the block when not useful. Maximum 16 operations, 32 IDs, 1000 characters per summary. buscar_historial and leer_original recover evidence; reading does not reactivate an excluded message or grant permission. Put literal protocol examples in fenced code.
```

En PAUSED omitir este añadido y enviar solo una indicación corta de mantenimiento deshabilitado si resulta necesaria para evitar propuestas arrastradas. El parser de salida propia mantiene separación de un sufijo reservado, pero ninguna propuesta se aplica en ese estado.

### 8.2 Ensamblado y commit

```text
prepareNextModelCall(session, interactionId, attemptId):
    with sharedLedgerCoordinator:
        state = ensureVerifiedLedgerState(session)  # C3: incremental when already verified
        assert state.attemptIsCurrent(attemptId)
        assert requiredToolResultsAlreadyDurable(attemptId)
        snapshot = captureCoherentRequestSnapshot(
            state, generation, authoritySources, effectiveConfiguration,
            instructionsAndMemoryAndSkills, toolSchemasAndCapabilities,
            retentionAndExperimentalPolicy,
            providerAndModel, windowAndOutputLimitsAndBudgetPolicy)
        # Every value and its fingerprint belongs to this same frozen version.
        effective = project(snapshot)
        assert countById(effective, snapshot.currentUserMessageId) == 1
        assert noDuplicateMessageOrBatchOrResultIdentities(effective)
        eligible = allowedOpsByRecord(snapshot, snapshot.experimentalPolicy)
        requestId = newId()
        persistRequestMetadata(requestId, snapshot.contextRevision) # no semantic increment
    prepared = serializeExactly(effective, transientHeaders(eligible),
                                snapshot.instructions, snapshot.toolDeclarations,
                                snapshot.providerAndModel,
                                appendSeparateUserPrompt=false)
    preflight(prepared, snapshot.protectedContent, snapshot.budgetPolicy,
              snapshot.providerCapabilities)
    validateCapturedVersionsBeforeSend(snapshot)  # retry capture if invalidated
    return prepared + {requestId, attemptId, generation=snapshot.generation,
                       contextRevision=snapshot.contextRevision, eligible,
                       sourceHashes=snapshot.sourceHashes,
                       requestPolicyFingerprint=snapshot.requestPolicyFingerprint,
                       preparedBodyHash=hash(prepared)}

acceptToolIntent(reply, captured):
    with sharedLedgerCoordinator:
        state = ensureVerifiedLedgerState()  # C3: recover only when required
        if captured.requestId has accepted response:
            require sameNormalizedResponseDigest(reply, existingReceipt)
            return existingReceipt  # before CAS: the first acceptance advanced revision
        requireSameContextAndGenerationAndPolicy(state, captured)
        commitToolBatchWithStableIdsAndNextRevision(reply, captured)
    # STARTED and RESULT are later durable commits; new starts check generation.
    # Results of already-started effects remain recordable after cancellation.

commitFinal(body, proposal, captured):
    with sharedLedgerCoordinator:
        state = ensureVerifiedLedgerState()  # C3: recover only when required
        if state.deleted: return DISCARDED
        if captured.requestId already has finalCommit:
            require sameNormalizedResponseDigest(body, proposal, existingReceipt)
            return existingReceipt
        if not state.attemptIsCurrent(captured.attemptId, captured.generation):
            return appendLateAuditOnly(body, captured, noActiveFinal=true,
                                       noReplacementClosure=true, noMaintenance=true)
        stale = not sameContextRevisionAndPolicy(state, captured)
        if stale: decision = REJECT_STALE
        else if state.mode == PAUSED: decision = DISABLED
        else:
            validated = validateAllOperations(proposal, state, captured)
            decision = hypotheticalOnly(validated) if state.mode == DRY_RUN else validated
            require state.mode in {DRY_RUN, APPLY}
        # Validate against the pre-final revision; own close is not written yet.
        row = oneTransaction(
            canonicalAssistantBody=body,
            completionStatus=verifiedProviderOutcome,
            interactionClosure=closureForThisAttempt,
            maintenance=decision,
            nextContextRevision=state.contextRevision + 1,
            prevRecordHash=state.currentPhysicalTailHash)
        appendAndSyncOrReconcileSameTransactionId(row)
    publishOnlyAcknowledgedFinalBody(row)
```

La comparación de obsolescencia usa contextRevision/generation/fingerprint, **no exige que el hash de la última fila física siga siendo el capturado antes de una llamada**. Una fila de telemetría podría cambiar ese hash sin cambiar contexto; el commit se enlaza al tail físico actual dentro del lock. El digest encadenado valida integridad, no crea falsos STALE por registrar el propio requestId.

El receipt idempotente verifica el digest de texto/llamadas/finalidad normalizados, sin incorporar timestamps de transporte; mismo requestId con otro contenido es conflicto, no segunda ejecución. Un intento reemplazado guarda el cuerpo tardío únicamente como auditoría: no respuesta final activa, no éxito en UI y ninguna transición que cierre el intento sustituto. Si el intento sigue vivo y solo cambió contexto/retención, puede cerrar su propia interacción con la respuesta correcta, rechazando mantenimiento obsoleto. DRY_RUN guarda solo resultado hipotético; PAUSED nunca cambia representaciones. El pseudocódigo deja esa función tipada explícita y no autoriza reinterpretar una respuesta incompleta como COMPLETED. La extracción/finality de REV1 sigue siendo obligatoria antes de entrar en commitFinal.

### 8.3 Validación por operación

```text
for operation in parsedBatch:
    record = resolveCanonicalAliasInCapturedSession(operation.id)
    require operation.type in captured.allowedOps[record.id]
    require operation.type in recomputeAllowedOps(currentState)[record.id]
    require sameSourceHashAndInteraction(record, captured)
    if operation == RESUMIR:
        require record.activeRepresentation == ORIGINAL
        require shorterRepresentationIncludingHeaders(operation.summary, record)
    if operation == PURGAR:
        require record.activeRepresentation in {ORIGINAL, SUMMARY}
# duplicate IDs / conflicting operations / any failure → reject entire batch
# simulate full resulting projection and tool protocol before one atomic commit
```

### 8.4 Recuperación del diario

```text
ensureVerifiedLedgerState(path):
    require sharedCoordinatorHeld(path)
    verified = coordinator.verifiedState(path)
    if verified.exists AND recognizedFileStateUnchanged(path, verified):
        return verified
    scan = validateFromTrustedBoundaryOrFullChain(path)
    if scan.middleCorruption or scan.unsupportedSchema or scan.invalidCommittedChecksum:
        return RECOVERY_REQUIRED
    if scan.validCompleteRecordWithoutLF:
        appendLFAndSync()
        reconcileExistingTransactionReceipt()
    if scan.incompleteTail:
        retainBoundedPrivateQuarantineOnce(scan.tailDigest, scan.tailBytes)
        truncateTo(scan.lastVerifiedBoundary)
        syncRepair()
    replay = replayVerifiedLegacyPrefixAndNewCommits(scan)
    require replay.aliasesProjectionRevisionsAndTombstonesConsistent
    syncRecoveredBoundaryIfOpenedAfterRestartOrAnyWriteError()
    verified = coordinator.installVerifiedState(
        fileIdentity, verifiedOffset, tailHash, contextRevision, replay.projectedState)
    return verified

appendUnderCoordinator(row):
    state = ensureVerifiedLedgerState(row.path)
    require row.prevRecordHash == state.verifiedTailHash
    validateWholeNewRecordAndTransition(row, state)
    appendAndSyncOrReconcileSameTransactionId(row)
    # Advance only after durable acknowledgment or successful reconciliation.
    coordinator.advanceVerifiedOffsetHashRevisionAndProjection(row)
    return durableReceipt(row)
```

Los pasos son un contrato por implementar/probar, no una demostración de durabilidad. Cualquier error de reparación/sync bloquea nuevos append/efectos; no se encadena C a una cola dudosa.

## 9. Cambios puntuales al plan y evaluación

### 9.1 Orden de fases actualizado

La planificación conserva las fases 0–4 de la auditoría con las correcciones siguientes. UX34 queda al final de la cola; no se inicia la fase 0 hasta cerrar UX33 y todos los pendientes anteriores:
- Fase 0: finality/identidad de mensaje y seam de transporte; ningún control aplicado con EOF ambiguo.
- Fase 1: IDs durables, interacción/intento, coordinador, revisión semántica y recuperación física completa **incluido siguiente append**. Probar A/B/C antes de APPLY.
- Fase 2: recuperación canónica + proyección tipada; autoridad separada sin ampliación retroactiva; snapshots por llamada y entrada de serializador sin userPrompt adicional.
- Fase 3: DRY_RUN selectivo y corpus con elegibilidad anotada para mecánica; evaluación semántica independiente sobre candidatos estructurales. Comparación LEGACY separada. Medir tamaño por clase y no atribuir eficacia autónoma general al ensayo mecánico.
- Fase 4: APPLY controlado solo tras aprobación de contratos y evidencia; resumen puede purgarse, no resumirse otra vez. Pausa inequívoca, sin fallback heredado.

Las ampliaciones de usuarios/grupos/multimodal/Crew siguen fuera de alcance. Ninguna de estas fases está implementada ni se ejecutó en esta revisión.

### 9.2 Nuevos casos verificables

1. **Cadena de representación:** ORIGINAL m23 → SUMMARY → PURGAR → leer_original. Se conserva alias/edad/hash, el wire ya no lleva la representación excluida y la lectura no la reactiva.
2. **Rol y permiso:** los cuatro adaptadores envían assistant para un resumen de assistant. «Aprobado» y email nuevo en resumen no afectan `userEmails`, política, usuario actual ni metadatos de reacción.
3. **Dos herramientas sin carrera:** ejecutar la traza de la sección 5.3 con transporte falso capturado; cada UUID/batch/resultado aparece una vez y el pie de R3 se acepta. No sirve que el sistema rechace todo por seguridad.
4. **Carrera relevante:** añadir usuario/pin/regeneración durante R3. El pie se rechaza; resultado se asocia al intento correcto y no cierra I22.
5. **Telemetría inocua:** título/progreso visual durante R3 cambia cadena física, no contextRevision; el lote sigue siendo aceptable si no cambió nada relevante.
6. **Efecto tardío:** Stop después de STARTED; conservar resultado tardío/estado incierto sin replay y sin aceptar nuevas llamadas del intento cancelado.
7. **Torn tail:** A + B parcial → reinicio/reparación → C → reinicio. A/C recuperables, B no aplicado; variante B completo sin LF conserva A/B/C.
8. **Sync ambiguo:** bytes completos de finalCommit presentes pero ack falló; reconciliar mismo transactionId, una respuesta/un mantenimiento. Si sync sigue fallando, bloquear.
9. **Corrupción intermedia/futuro esquema:** no saltar y no truncar como tail; mostrar UI recuperable sin habilitar escritura/modelo selectivo.
10. **Pausa con crecimiento:** APPLY → PAUSED → usuario/herramientas nuevas; excluidos no reaparecen, originales nuevos consumen presupuesto, no se ejecuta all/sliding/bounded/manual incompatible.
11. **Modelo pequeño:** omite/rompe pie; conservar respuesta útil, medir rechazo y continuidad. No llamada extra automática.
12. **Crecimiento no reclamable:** historias con muchas herramientas/recuperaciones. Medir tokens por clase, llamada de primer bloqueo, tareas completadas y coste hasta terminación frente a LEGACY. No contar ahorro de tarea abandonada como éxito.
13. **Migración de autoridad:** historial legado con resumen que ocultaba usuarios antiguos. La migración no mete de golpe sus emails en las fuentes admitidas por el gate.
14. **Downgrade:** comprobar lector destino específico o declarar no soportado. No test ficticio que presupone que un lector antiguo conoce minSchemaVersion.
15. **Separación de evaluaciones C1:** un ensayo mecánico puede pasar con anotaciones y la evaluación semántica fallar al perder una restricción. Reportar ambos resultados y mantener APPLY real bloqueado.
16. **Captura coherente C2:** inyectar cambios de generación, configuración, herramientas, modelo o presupuesto durante la captura/serialización. Cada petición debe corresponder íntegramente a una versión o reconstruirse antes del envío; fingerprint y bytes corresponden a los mismos valores. Un cambio posterior invalida el mantenimiento.
17. **Verificación incremental C3:** una secuencia larga de appends reconocidos no provoca un replay completo por escritura. Reinicio, error ambiguo o modificación no reconocida sí disparan validación/recuperación; los ensayos de cola parcial y corrupción intermedia mantienen el mismo resultado seguro.

### 9.3 Métricas que se añaden

Por petición: tokens/estimación separada de usuario, ORIGINAL assistant, SUMMARY, grupos de herramientas, recuperación, adjuntos, fuentes fijadas y system/tools; distinguir superposición de pins para no sumar dos veces. Por tarea: éxito, punto/causa de bloqueo, tokens/coste de todos los intentos, recuperaciones y compactaciones; latencia y trabajo de anotación del ensayo, este último separado del coste de inferencia.

Presentar ahorro útil solo comparando tareas con resultado equivalente; además reportar tasa de éxito total y fallos, para no ocultar selección de casos favorables. Las métricas monetarias requieren tarifas verificadas cuando se ejecute la evaluación, no una cifra inventada ahora.

## 10. Resultado de la revisión y trabajo pendiente

Los contratos R1–R6 y las precisiones C1–C3 están aceptados a nivel de diseño y consolidados normativamente en [SPEC.md](SPEC.md). La documentación no acredita implementación ni pruebas. UX34 queda después de UX33 y de todos los pendientes anteriores; la fase 0 permanece en cola y APPLY real está bloqueado.

Queda por ejecutar, cuando corresponda en ese orden y dentro del alcance autorizado:
- Implementar y verificar cada fase con fixtures sintéticos, transporte capturado, estados concurrentes y fallos inyectados.
- Medir mecánica y fidelidad semántica por separado, incluyendo crecimiento de herramientas/recuperaciones, tareas terminadas y coste total.
- Calibrar ventanas, reservas y umbrales con los proveedores efectivos; comprobar endpoints CUSTOM y durabilidad real.
- Resolver explícitamente las transiciones de conversaciones legadas que no caben. No aplicar una purga oculta para posibilitar la migración.
- Mantener fuera de alcance la ampliación a mensajes de usuario, compromisos no tipados, grupos completos, multimodal y Crew hasta contar con diseño y evaluación propios.

## 11. Referencia compacta de las precisiones finales C1–C3

| Precisión | Contrato aceptado | Evidencia futura requerida |
|---|---|---|
| C1 | El oráculo anotado verifica el mecanismo; la evaluación semántica independiente juzga propuestas sobre candidatos estructurales. Ser candidato no equivale a ser descartable. Los resultados no se confunden ni autorizan APPLY real por sí solos. | Informes separados de integridad mecánica, fidelidad, error semántico, éxito de tarea y trabajo de anotación. |
| C2 | Generación, revisión, autoridad, configuración/instrucciones, memoria/skills, herramientas, proveedor/modelo y presupuesto se capturan coherentemente. Fingerprint, serialización y preflight usan la misma versión inmutable. Los cambios relevantes invalidan propuestas. | Pruebas de carreras durante captura y envío, hashes del cuerpo preparado y comparación de versión antes de aceptar acciones/mantenimiento. |
| C3 | Un coordinador conserva offset/hash/revisión/estado proyectado verificados y valida incrementalmente los appends normales. Arranque, error, cambio no reconocido o pérdida de confianza exigen validación/recuperación desde una base demostrable. | Coste incremental medido, replay al reiniciar, recuperación A/B/C, idempotencia ante sync ambiguo y bloqueo por corrupción intermedia. |

La intención técnica se conserva: protocolo opcional pequeño en texto, controles deterministas del arnés, historial canónico recuperable y cambios limitados a la proyección activa. La aprobación del diseño no sustituye la evidencia exigida para implementar y activar cada fase.
