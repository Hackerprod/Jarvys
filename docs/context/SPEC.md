# Especificación de gestión selectiva del contexto

Versión de contrato: 1.0. Estado: diseño aceptado para un prototipo controlado; fase 0 diagnóstica implementada y documentada en [PHASE0_RESULTS.md](PHASE0_RESULTS.md), sin activar gestión selectiva. Tarea de producto: UX34. La continuación posterior a fase 0 espera el cierre de los pendientes de producto prioritarios y sus gates correspondientes.

Este documento es normativo. «DEBE», «NO DEBE» y «PUEDE» describen requisitos del prototipo, no funcionalidades presentes ni pruebas ejecutadas. [AUDIT.md](AUDIT.md) conserva la evidencia del código; [CONTRACT_REVIEW.md](CONTRACT_REVIEW.md) explica las decisiones; [PHASE0_ACCEPTANCE.md](PHASE0_ACCEPTANCE.md) define el primer entregable de desarrollo.

La primera fase de código es únicamente diagnóstico de respuestas y transporte de prueba. Esta especificación no activa mantenimiento, no autoriza migraciones sobre conversaciones reales ni acredita autonomía general, ahorro o durabilidad. Cada fase posterior conserva su gate. No se debe revertir el repositorio a una base histórica para implementar el plan ni interferir con trabajos previos de la cola.

## 1. Objetivo y alcance

### 1.1 Objetivo

Permitir que el modelo proponga excluir mensajes antiguos de futuras solicitudes o sustituir su representación activa por un resumen, sin borrar los originales visibles y con recuperación paginada. La respuesta al usuario seguirá siendo texto normal. El arnés decidirá si el lote es sintáctica, estructural y transaccionalmente admisible.

No se requiere JSON generado alrededor de cada respuesta, modelo gestor adicional, embeddings, servicio externo nuevo ni base de datos distinta. Los metadatos internos y las API de herramientas sí pueden usar sus formatos estructurados habituales.

### 1.2 Primer prototipo

El prototipo edita mensajes completos de texto antiguo del asistente en el chat principal ordinario. No edita mensajes de usuario, grupos de herramientas, recuperaciones, adjuntos, razonamiento, contextos de bots/Crew, tareas programadas u otros scopes. Esas clases pueden seguir formando parte del contexto y creciendo; no se promete una ventana total acotada.

Habrá dos evaluaciones separadas:
- **Mecánica:** candidatos anotados en un corpus sintético para comprobar parser, identidad, protección, persistencia y recuperación. La anotación prepara el ensayo; no exige confirmación del usuario por cada operación ni prueba autonomía general.
- **Semántica:** candidatos elegibles por reglas estructurales del runtime, sin anotación semántica oculta que retire los casos difíciles. Un oráculo externo puntúa selecciones, resúmenes, retención excesiva y continuidad; no decide qué IDs reciben la marca editable. También usa conversaciones sintéticas y herramientas sin efectos externos.

Los pins derivados de eventos reales se mantienen en ambos ensayos. Ninguno autoriza aplicar el mecanismo a datos reales. El uso autónomo general es una decisión posterior basada en evidencia.

### 1.3 Invariantes

- **I01 Originales:** excluir o resumir no modifica el texto canónico accesible.
- **I02 Identidad:** un alias corresponde a una fuente estable de una conversación; nunca se recicla ni se renumera.
- **I03 Recencia:** diez interacciones cerradas más todas las abiertas/inciertas permanecen protegidas.
- **I04 Autoridad:** contenido derivado o recuperado no crea usuario auténtico, permiso, aprobación ni herramienta.
- **I05 Protocolo:** llamadas/resultados conservan sus relaciones; el prototipo no edita sus piezas.
- **I06 Control:** el footer no llega a UI, texto canónico ni contexto histórico como instrucciones de mantenimiento.
- **I07 Atomicidad:** un lote se aplica completo o no se aplica; respuesta/cierre/decisión se comprometen coherentemente.
- **I08 Idempotencia:** reentrega de la misma respuesta no repite respuesta, mantenimiento ni efectos externos.
- **I09 Snapshot:** cada solicitud describe exactamente el contexto, generación y configuración inmutables que usa.
- **I10 Presupuesto:** ningún exceso detectado se resuelve eliminando silenciosamente contenido protegido.
- **I11 Borrado:** fuentes borradas/regeneradas no reaparecen mediante índices, recuperación o resúmenes.
- **I12 Honestidad:** mediciones, estimaciones, pruebas planeadas y comportamiento remoto no verificado se distinguen.

## 2. Base técnica y puntos de integración

La auditoría inicial se basó en `d1ed8b4622d300ea919188ab1d3a58f916aa9f07`; los contratos posteriores se contrastaron con `5437c6de0e96d3e1bf125cf3096a935636361bda`. Entre la base inicial y `5437c6de0e96d3e1bf125cf3096a935636361bda` se incorporó el filtro documentado de acceso a operaciones de conectores. Durante la preparación de esta especificación, HEAD era `4801676f2134df17c24aa160b162a954e89e1605`; no había diferencias en los componentes centrales examinados frente a `5437c6de0e96d3e1bf125cf3096a935636361bda`. Estas referencias son evidencia histórica, no sustituyen verificar el HEAD real al comenzar una fase.

Rutas relativas a `app/app/src/main/java/com/jarvys/agent/`:

| Componente actual | Papel | Cambio futuro del contrato |
|---|---|---|
| AgentForegroundService.runRealAgent | Carga contexto, identifica entrada, ejecuta, guarda respuesta final | Pasar identidades canónicas; commit terminal único |
| LocalRunStore | Diario JSONL, mensajes visibles, compactación, timeline | Identidades/interacciones, proyección, revisión, replay y recuperación física |
| ConversationTurn | Mensajes, summaries, llamadas/resultados y partes multimodales | Transportar UUID, interacción y tipo derivado explícito |
| CoreAgentLoop.run | Preflight, llamadas, herramientas, checkpoints, final | Snapshot por llamada y extracción anterior a cualquier consumidor de respuesta |
| MainChatTranscriptStore | Diario de intenciones, STARTED, resultados y artefactos | Identidades durables en vez de identidad de objetos; resultados coherentes al recargar |
| CoreAgentModel / ModelReply | Frontera de proveedor y respuesta normalizada | Finalidad/identidad/uso opcional; configuración congelada en fase posterior |
| OpenAICodexResponsesClient | Responses/SSE | Evidencia terminal, items del asistente y seam de transporte |
| OpenRouterClient | Chat Completions para OpenAI API/OpenRouter/custom | finish_reason, uso desglosado y seam compatible |
| ConversationCompactor / ConversationCompactionPolicy | Resumen/recorte actual | Ningún trigger puede saltarse reglas del modo selectivo |
| CoreAgentRuntime.instructions y userMessagesFromHistory | Instrucciones/herramientas y fuente de contexto auténtico | Captura coherente; autoridad canónica separada de proyección |

La persistencia central auditada es JSONL privado, no Room/SQLite de mensajes. Los manifiestos fuente y los merged manifests inspeccionados no declaraban otro proceso escritor del diario; existen múltiples hilos/instancias. La aplicación puede lanzar subprocesos para otras funciones. No se extenderá la afirmación de proceso único más allá del almacenamiento auditado.

## 3. Modelo de datos propuesto

Estos nombres describen contratos propuestos, no clases que se afirme que ya existen.

### 3.1 Fuente, interacción e intento

`CanonicalMessage`:
- `conversationId`, UUID `messageId`, rol auténtico, contenido original disponible y hash;
- `interactionId`, orden de creación durable, estado de visibilidad y procedencia;
- clase de contenido y completitud de fuente;
- referencias a adjuntos si existen; no copiar imágenes al índice textual.

`Interaction`:
- identidad ligada al mensaje genuino del usuario que inicia el trabajo;
- uno o más `attemptId` por ejecución/regeneración;
- estado OPEN o cerrado, causa terminal y vínculos a llamadas/resultados;
- fuente original y orden de cierre, no edad deducida de un alias.

`Attempt` conserva generación, estado del worker y pertenencia. FAILED/PARTIAL/STOPPED pueden contar como cerrados cuando el worker terminó y sus efectos están reconciliados. Una evidencia incierta permanece fijada aunque el intento ya no ejecute. La regeneración es un intento nuevo de la misma interacción, no un segundo turno de usuario. Una interrupción pendiente no se cuenta como interacción completada.

### 3.2 Alias y representación

Alias por conversación: `m` seguido de 1–12 dígitos ASCII, primero distinto de cero. Mapeo durable a UUID; contador monotónico sin reutilización. Agotar el rango deshabilita mantenimiento antes de reciclar IDs. Mensajes iguales por texto tienen UUID/alias distintos.

`ActiveRepresentation`:
- ORIGINAL: referencia a fuente canónica;
- SUMMARY: resumen, UUID/hash/rol/interacción originales y transactionId que lo produjo;
- EXCLUDED: referencia a fuente, sin texto enviado por esa representación.

No duplicar originales innecesariamente. El journal exhaustivo de mantenimiento permanece fuera del prompt. Leer una fuente no cambia representación ni edad. No se permiten cadenas RESUMIR sobre SUMMARY en el prototipo.

### 3.3 Pins y procedencia

Un pin es metadato interno con motivo y referencia de evento/fuente. Lo crea/libera el arnés al observar hechos tipados: decisión pendiente/resuelta, misión activa/terminal, efecto incierto/reconciliado o anotación del ensayo. El footer no puede liberar pins.

El código actual no ofrece vínculos completos de mensaje/interacción en todos los registros de decisión/misión. Al migrar, una obligación activa sin vínculo probado bloquea elegibilidad de esa conversación para el ensayo hasta reconciliarse; no se adivina por proximidad o texto. Los eventos nuevos deben guardar el vínculo cuando el runtime lo conoce.

No existe garantía de detectar toda promesa libre o restricción implícita. Esa limitación se mide en la evaluación semántica; no se resuelve afirmando que un parser prueba fidelidad.

### 3.4 Herramientas e identidades

Un grupo posee `batchId`; cada llamada `callId`; resultado identificado por `(batchId, callId)`. Conservar INTENT → STARTED → efecto → RESULT y recibos actuales. Nunca usar igualdad de objeto o texto para decidir si un grupo ya se guardó.

Una herramienta iniciada antes de cancelación puede guardar su resultado tardío como evidencia. No admitir nuevas acciones del intento cancelado ni repetir un efecto para recuperar información. NEVER_LAUNCHED/INTERRUPTED_UNCERTAIN son estados reconciliados, no órdenes de replay.

## 4. Protección y elegibilidad por operación

### 4.1 Cálculo estructural

El conjunto protegido incluye:
1. diez interacciones cerradas más recientes;
2. todas las interacciones abiertas o de terminación/pertenencia incierta;
3. todas las fuentes fijadas;
4. instrucciones, políticas, permisos y controles de aplicación, siempre fuera de la superficie editable.

El cálculo usa metadatos. Una interacción con treinta llamadas no envejece treinta veces. Crear un resumen o recuperar una fuente no rejuvenece la interacción original.

### 4.2 Predicado

```text
structurallyEligible(source, snapshot) =
  ordinaryMainChatScope
  AND source.role == assistant
  AND source.class == plainTextMessage
  AND source.canonicalAvailableAndComplete
  AND source.visibleAndNotRegenerated
  AND source.outsideProtectedInteractions
  AND not source.hasActivePin
  AND source.sameConversation

mechanicalTrialEligible = structurallyEligible AND annotationAllows(UUID, sourceHash, corpusVersion)
semanticTrialEligible   = structurallyEligible
```

En ensayo semántico, el oráculo no puede modificar previamente semanticTrialEligible para ocultar contenidos difíciles. Los resultados de ese brazo son investigación controlada, no habilitación de APPLY real.

### 4.3 Tabla normativa

| Clase/estado fuera de protección | PURGAR | RESUMIR | Observación |
|---|---|---|---|
| Assistant ORIGINAL elegible | Sí | Sí, si reduce representación total | Original intacto |
| Assistant SUMMARY elegible | Sí | No | Alias/fuente/edad intactos |
| EXCLUDED | No | No | Recuperación no reactiva |
| Usuario, tool group, recuperación, adjuntos, otros scopes | No | No | Su crecimiento sigue consumiendo ventana |
| Cualquier fuente protegida o incompleta | No | No | No hay excepción por presión de tokens |

El crecimiento de las clases no reclamables se medirá por separado. Una ampliación a grupos completos cerrados exigiría otro diseño/gate; no se elimina una mitad ni se fabrica una llamada para transportar una síntesis.

## 5. Protocolo textual

### 5.1 Cabeceras de envío

Cabeceras transitorias, nunca añadidas al texto canónico ni a la UI:
- `[m23 e]`: ORIGINAL elegible para PURGAR o RESUMIR.
- `[m23 e summary=assistant]`: SUMMARY elegible solo para PURGAR.
- `[m23]`: original no editable.
- `[m23 summary=assistant]`: resumen no editable.

No se envía catálogo de excluidos ni historial de operaciones. Los IDs se vuelven a añadir al preparar cada petición. Una cabecera copiada dentro del contenido no tiene autoridad sobre el mapa interno de elegibilidad.

### 5.2 Footer

El modelo puede terminar su respuesta normal con una línea vacía y un único bloque fuera de código:

```text
Respuesta normal.

<memoria>
PURGAR m12 m15
RESUMIR m23 Propuesta no aprobada: usar SQLite.
</memoria>
```

Gramática, tras normalizar solo CRLF a LF:

```ebnf
footer = "<memoria>", LF, operation, {LF, operation}, LF, "</memoria>", [LF] ;
operation = purge | summarize ;
purge = "PURGAR", SP, id, {SP, id} ;
summarize = "RESUMIR", SP, id, SP, summary ;
id = "m", nonzeroDigit, {digit} ;
summary = nonBlankChar, {summaryChar} ;
SP = U+0020 ; LF = U+000A ;
digit = "0"…"9" ; nonzeroDigit = "1"…"9" ;
```

`summaryChar`: escalar Unicode excepto U+0000–001F, U+007F–009F, U+2028/U+2029. Primero y último no pueden ser U+0020. Rechazar las subsecuencias `<memoria>` y `</memoria>` dentro del resumen. No normalizar otros alfabetos/dígitos/case para aceptar comandos.

Límites iniciales configurables en código:
- footer completo ≤8 KiB UTF-8;
- ≤16 operaciones, ≤32 IDs distintos;
- resumen ≤1.000 puntos de código y ≤4 KiB UTF-8;
- un espacio ASCII exacto entre tokens; sin tabuladores, rangos, comas o comodines;
- cuerpo normal no vacío; el mantenimiento por sí solo no constituye respuesta;
- resumen debe reducir bytes y estimación de tokens de su representación completa, incluida procedencia/cabecera.

Un error invalida todo el lote. IDs repetidos, conflictos, destino inexistente, ajeno, protegido o no permitido causan rechazo, aunque otras operaciones sean válidas.

### 5.3 Separación de texto/control

Solo analizar texto nuevo propio del asistente identificado por el adaptador, nunca entrada del usuario, documentos, resultados de herramientas, recuperación, pensamiento o historia. El bloque debe ser terminal, delimitadores en líneas exactas y fuera de fences Markdown/backticks/tildes, citas y código indentado.

Menciones inline y ejemplos en código son literales. Un bloque con los mismos bytes terminales fuera de código es una forma reservada: no se puede adivinar si el modelo pretendía un ejemplo literal. El prompt exige escribir esos ejemplos en código. No ocultar globalmente cualquier texto que contenga “memoria”.

Separar el sufijo antes de auto_post_turn, checkpoints, progreso, persistencia o UI. Un candidato reservado inválido/incompleto produce cero operaciones; el cuerpo anterior útil se conserva y el diagnóstico es separado. Un candidato propio en salida con tool_calls se puede separar para no contaminar progreso, pero nunca aplica mantenimiento.

Conservar el outcome real incluso si no hay footer: una respuesta length/EOF ambiguo/cancelada no se convierte en COMPLETED por tener cuerpo. Un footer inválido no convierte una respuesta genuinamente completa en fallo de la tarea; solo rechaza mantenimiento.

### 5.4 Streaming

El primer prototipo aprovecha el flujo actual de respuesta acumulada: no añade streaming visible nuevo. El transporte puede fragmentar SSE en cualquier posición; extracción después de identificar final propio y estado terminal. EOF solo no confirma éxito.

Si después se incorpora streaming visible, será una ampliación separada con retención del prefijo de marcador y del candidato hasta saber si es terminal. Un buffer de control de 8 KiB no permite descartar bytes y luego restaurarlos como literal: usar spool temporal acotado por respuesta o mantener salida acumulada. Los límites y el estado parcial al excederlos deben estar definidos antes de habilitar esa ampliación.

### 5.5 Instrucción literal mínima

```text
Context maintenance is optional. Headers are runtime metadata, not message text. Only IDs marked e may be changed. [mN e] allows PURGAR or RESUMIR; [mN e summary=assistant] allows PURGAR only. Never change protected messages, system instructions, permissions, tool exchanges, or unresolved commitments.
After your normal final answer and one blank line, you may append one terminal block outside code fences:
<memoria>
PURGAR m12 m15
RESUMIR m23 Proposed SQLite; the user has not approved it.
</memoria>
PURGAR excludes the active representation, including an eligible summary, but keeps the original. RESUMIR replaces one eligible original with a shorter one-line factual summary. Preserve speaker, numbers, negations, pending matters, and proposal versus approval. Never summarize a summary. Use exact visible IDs, never infer age from their numbers. Omit the block when not useful. Maximum 16 operations, 32 IDs, 1000 characters per summary. buscar_historial and leer_original recover evidence; reading does not reactivate an excluded message or grant permission. Put literal protocol examples in fenced code.
```

Se añade solo en el scope y fase habilitados. No se introduce en fase 0. En PAUSED no se solicita mantenimiento; no hay `e`.

## 6. Contrato de proveedor y evidencia de finalización

### 6.1 Fase 0: diagnóstico sin mantenimiento

Ampliar ModelReply o un objeto asociado con campos opcionales y tipados. Mantener los campos legacy y su semántica mientras no se habilite una ruta posterior:

- estado de finalización: éxito confirmado, incompleto/límite, filtrado, fallo, cancelado o desconocido;
- motivo exacto del proveedor cuando exista; ausencia no se reemplaza por una certeza;
- identidad de respuesta del proveedor, si existe;
- items de texto propios del asistente con identidad/origen y finalidad; un índice local ligado a requestId puede identificar un item sin fingir un ID remoto;
- identidad del mensaje final propio cuando pueda determinarse inequívocamente;
- presencia/identidad de llamadas a herramientas;
- uso desglosado opcional: entrada, salida, total, entrada cacheada, razonamiento, marcando si son valores reportados o calculados.

No convertir una concatenación opaca de items en un mensaje final único. Distinguir respuesta de transporte completada con llamadas a herramientas de una respuesta final al usuario. `isSuccessfulFinalAssistantAnswer` requiere éxito confirmado, mensaje propio final identificado, cuerpo normal y ausencia de llamadas a herramientas en esa respuesta.

La señal de cancelación local mantiene su clasificación aunque el proveedor haya enviado texto antes. Si el transporte legacy lanza una excepción, un campo diagnóstico no debe convertirla silenciosamente en un éxito vacío. La fase 0 no interpreta `<memoria>`, no cambia selección de historial y no escribe estos contratos como migración de conversaciones reales.

### 6.2 Dos familias, cuatro configuraciones

- OPENAI_CODEX: OpenAICodexResponsesClient, Responses con SSE.
- OPENAI_API: OpenRouterClient configurado para OpenAI Chat Completions.
- OPENROUTER: OpenRouterClient.
- CUSTOM: compatibilidad actual OPENAI_CHAT_COMPLETIONS mediante el mismo cliente.

Normalizar únicamente formatos realmente soportados y documentados. Codex debe distinguir eventos/estado completed, incomplete y failed, y no usar EOF o `[DONE]` aislado como única evidencia positiva. Chat Completions debe distinguir finish_reason normal, tool calls, límite, filtrado y desconocido. Verificar identidades cuando lleguen deltas y output final; no deduplicar por parecido de substrings perdiendo texto repetido legítimo.

Una prueba local de cuatro configuraciones cubre ramas/configuración de dos clientes; no acredita cuatro servicios remotos. Los endpoints custom pueden variar. La incompatibilidad se declara, no se corrige cambiando roles o historial a escondidas.

### 6.3 Uso y cachés

El contador legacy `contextTokensUsed` conserva su significado documentado de total reportado o fallback entrada+salida. Añadir desgloses no cambia silenciosamente a consumidores como el presupuesto de reflexión.

Cached input y razonamiento pueden ser subconjuntos de otras categorías; no sumarlos de nuevo al total. Campos ausentes/negativos/inconsistentes deben quedar ausentes o diagnosticados, no inventar cero ni precios. La aplicación no controla explícitamente hoy un caché de conversación por prefijo; su efecto/coste remoto permanece por medir.

### 6.4 Frontera de transporte

Introducir/reutilizar un seam estrecho para capturar los bytes exactos y suministrar respuestas sintéticas. No registrar cuerpos privados en producción. El test de retry usa autenticador falso y credenciales ficticias; no refresca sesiones personales. Se debe poder probar tanto éxito como truncamiento, tool-only y estados desconocidos.

El cambio posterior a `completePreparedConversation` debe serializar exactamente la historia preparada, sin añadir userPrompt ni user `Continue.`. **No se activa globalmente en fase 0.** El contrato legacy se prueba como regresión.

## 7. Petición preparada coherente e inmutable

### 7.1 Contenido de FrozenRequestSnapshot

Capturar conjuntamente o mediante revisión comprobada:
- conversación, interacción, attemptId, generation y requestId;
- contextRevision, fuentes canónicas/hashes, proyección y allowedOps;
- instrucciones ya resueltas, metadatos dinámicos admitidos y definiciones completas de herramientas/capacidades;
- proveedor/modelo, preferencias de inferencia y presupuesto/ventana/limites de salida con su procedencia;
- versión/fingerprint de la misma política y configuración efectivamente capturadas;
- referencias verificadas e inmutables de partes adjuntas cuando correspondan a una ruta soportada.

El fingerprint no contiene tokens/secretos, ni se usa como permiso. Las credenciales de transporte pueden refrescarse para la misma identidad sin que se confunda ese refresh con una sesión de historial nueva; cambios de identidad/concesión invalidan lo que corresponda según su epoch/política.

El assembler y los serializadores NO DEBEN releer instrucciones, herramientas, preferencias o generation vivas después de capturarlas. Si AttachmentModelContext, metadatos de reacción o catálogo usan actualmente lecturas posteriores, se les entrega una copia coherente o se invalida/reconstruye la captura. No basta hashear A y serializar B.

### 7.2 Captura sin lock durante red

Algoritmo permitido:
1. resolver metadatos que puedan requerir red fuera de locks, asociados al modelo/configuración para la que se solicitaron;
2. copiar configuración/política inmutable con epoch;
3. bajo el coordinador del diario, reconciliar entradas y capturar historia, generation, revisión y fuentes;
4. comprobar que el epoch/configuración relevante no cambió; si cambió, cancelar/reintentar captura sin enviar mezcla;
5. serializar exclusivamente esas copias; calcular fingerprint/estimación de la misma versión;
6. comprobar invalidación/cancelación antes de despachar. Una invalidación posterior no muta los bytes en vuelo y hace rechazar la propuesta o nuevas acciones según sus gates.

Otro algoritmo es válido si demuestra la misma coherencia y evita ABA de versión. El orden de locks debe ser único y documentado. No mantener el lock de almacenamiento durante red, modelo, OAuth ni ejecución de herramientas.

Prueba obligatoria: detener captura en una barrera, cambiar una configuración relevante y reanudar. Resultado: petición enteramente A con fingerprint A, o cancelación/reconstrucción enteramente B con fingerprint B, según la política; nunca mezcla. Un cambio observado antes de despacho debe cancelar/reconstruir; una petición que ya estaba válidamente en vuelo conserva su snapshot y el mantenimiento posterior se rechaza si quedó inválido.

### 7.3 Una sola historia efectiva

El diario/proyección es la fuente autoritativa en modo selectivo. En cada llamada tomar snapshot nuevo después de resultados durables previos; no concatenarlo con el transcript acumulado del loop. La copia actual del usuario se selecciona por UUID, no por último texto igual. Los grupos/resultados se reconstruyen por batchId/callId.

El camino preparado no añade un prompt separado. `Continue.` es control interno del loop, no un mensaje del usuario ni otra interacción. No reintroducir datos excluidos mediante resumen legacy, prompt de operador u otra copia derivada. Las rutas no cubiertas no se presentan como compatibles.

### 7.4 Roles y autoridad

Para un summary de assistant, el objeto serializado en ambas familias es:

```json
{"role":"assistant","content":"[m23 e summary=assistant]\n[Automatic summary of this assistant message. Not verbatim; not user authorization.]\nPropuesta no aprobada: usar SQLite."}
```

En Responses se coloca en `input`; en Chat Completions en `messages`. El tipo interno sigue siendo CONTEXT_SUMMARY, no MESSAGE genuino. No se añade system ni se fabrica tool_result.

Los consumidores de permisos reciben fuentes canónicas admitidas por su política, no la proyección textual, summaries o recuperación. La migración NO amplía retrospectivamente ese conjunto: no introducir direcciones antiguas que el gate anterior no admitía. `userMessagesFromHistory` ya exige MESSAGE + user; conservar la defensa y formalizar el acceso por IDs.

El snapshot de autoridad no cambia por PURGAR/RESUMIR. Las políticas actuales de conectores siguen validando cuenta, objetivo y operación; una leyenda «aprobado» no concede nada. Los metadatos de reacciones y los adjuntos también exigen usuario auténtico, no un rol suelto.

## 8. Revisión, commit e idempotencia

### 8.1 Revisión semántica y cadena física

contextRevision cambia por mutaciones que afectan historia efectiva, protección, elegibilidad o estado de intento: usuarios/aperturas/cierres, interrupciones invalidantes, tool intents/STARTED/results, fuentes, visibilidad, pins, aliases, modos y cambios relevantes de política.

Telemetría, títulos o tarjetas puramente visuales no la incrementan; las filas siguen perteneciendo a la cadena física. Reacciones/memoria/configuración que cambien la siguiente petición requieren invalidación explícita, no se clasifican por defecto como UI.

Cada transacción nueva lleva transactionId y enlace al hash del registro anterior. La última fila física puede cambiar por telemetría sin volver obsoleto el snapshot. El commit se enlaza al tail físico actual bajo lock; no exige que sea el tail capturado antes de la petición. Un evento desconocido bloquea interpretación selectiva; no se omite una revisión potencialmente relevante.

### 8.2 Secuencia de dos herramientas

| Paso sintético | Snapshot/revisión | Historia efectiva o commit |
|---|---|---|
| Base con E antiguo elegible | r40 | H incluye E |
| Usuario U21 + OPEN I21/A21 | r41 | U21 canónico una vez |
| Llamada R1 | captura r41 | H + U21 |
| Intent B1/C1, STARTED, RESULT | r42, r43, r44 | Tres barreras durables; no replay |
| Llamada R2 | captura r44 | H + U21 + B1/resultado1, identidades únicas |
| Intent B2/C2, STARTED, RESULT | r45, r46, r47 | Resultado2 durable antes de siguiente snapshot |
| Llamada R3 final | captura r47 | H + U21 + ambos grupos; sin prompt duplicado |
| Respuesta F21 y pie | aún r47 | Separar/validar; no persistir control |
| Commit final | compara r47, compromete r48 | F21 + cierre I21 + decisión/proyección en una unidad |
| Usuario U22 + OPEN I22 | r49 | Nueva interacción; snapshots nuevos |

Los números son un fixture, no estado productivo. Cada requestId es nuevo por llamada; no se compara R3 con la revisión de R1. El cierre propio no puede invalidar su pie porque la comparación ocurre antes de escribirlo. El conjunto elegible enviado no se amplía usando el nuevo cierre.

### 8.3 Validación del lote

Antes de cualquier cambio, validar lote completo contra:
- sesión/requestId/attemptId/generation y revisión capturadas;
- fingerprint coherente y actual para aceptar esa propuesta;
- destinos en allowedOps del snapshot enviado y en el estado actual;
- misma fuente/hash/interacción, visibilidad, completitud, pins y recencia;
- operación permitida para ORIGINAL o SUMMARY;
- límites, unicidad de IDs, ahorro neto del resumen y protocolo de herramientas resultante.

No rebase automático de propuestas obsoletas. El formato no prueba fidelidad semántica; los ensayos deben medir errores de cifras, negativas, propuestas/aprobaciones y compromisos.

### 8.4 Commit terminal único

Orden:
1. normalizar respuesta/finalidad; extraer control antes de cualquier consumidor;
2. preparar propuesta sin modificar estado;
3. bajo coordinador, recuperar/validar estado y comprobar tombstone;
4. si requestId ya tiene receipt, exigir mismo digest normalizado y devolverlo **antes de CAS de revisión**; contenido distinto con el mismo ID es conflicto;
5. si el intento fue reemplazado, guardar a lo sumo auditoría de cuerpo tardío; no publicar final activo ni cerrar el sustituto;
6. si el intento sigue actual pero contexto/política cambiaron, puede guardar/cerrar su propia respuesta con mantenimiento rechazado STALE;
7. APPLY aplica solo lote válido; DRY_RUN guarda simulación; PAUSED no aplica operaciones;
8. guardar cuerpo canónico + cierre + decisión/operaciones + nueva revisión en una transacción completa, sincronizar o reconciliar resultado ambiguo;
9. publicar UI y habilitar próxima llamada solo tras receipt durable.

Mantenimiento es una mutación local de proyección: no ejecuta acciones externas. Una misma respuesta no produce dos commits por reinicio/retry. Resultados tardíos de herramientas ya iniciadas conservan su recibo aunque el intento se haya invalidado; esa excepción para evidencia no autoriza nuevos efectos.

## 9. Diario físico y eficiencia

### 9.1 Coordinador y escritores

Todos los escritores de conversaciones deben someterse a una disciplina común por ruta canónica: chat/errores/adjuntos, tool protocol/artefactos, interrupciones, decisiones, regeneración/borrado, compactación, misiones, eventos proactivos/programados, archivos/imágenes, reflexión, reacciones y títulos. Las mutaciones externas que cambian configuración efectiva deben invalidar el snapshot de forma coordinada.

Para la topología auditada basta coordinación de proceso compartida, no locks por instancia. Si aparece otro proceso escritor, exigir lock OS o único servicio propietario antes de habilitarlo. Los logs legacy `steps.jsonl` no tienen automáticamente las garantías del diario de conversación; si fueran fuente requerida, deben entrar expresamente al contrato.

### 9.2 Formato y replay

Los registros nuevos tienen esquema versionado, transactionId, contextRevision, prevRecordHash y checksum de transacción. El parser debe validar UTF-8 y consumir todos los bytes del registro. Las operaciones se aplican desde transacciones completas de una cadena íntegra; no contando solo las líneas que sobrevivieron al parseo.

Migración: validar prefijo legado según su esquema, sellar digest/límite y crear asociaciones idempotentes de IDs. No exigir al legado campos que no existían ni fabricar receipts. Las fuentes sin identidad se vinculan a posición/hash de registro sellado, no solo a contenido. Corrupción ambigua bloquea migración.

### 9.3 Recuperación antes del siguiente append

| Hallazgo | Contrato |
|---|---|
| Registro completo válido con LF | Tras arranque/error, validar y resincronizar límite recuperado antes de efectos/publicación |
| Registro completo válido sin LF | Preservar, añadir LF y sincronizar, reconciliar mismo receipt; después permitir append |
| Sufijo final incompleto sin registros posteriores | Preservar evidencia privada acotada, truncar al último límite íntegro, sync de reparación antes de escribir |
| Checksum/base inválidos o corrupción intermedia | Bloquear; no saltar ni tratar automáticamente como tail parcial |
| Esquema desconocido/futuro | Bloquear; no truncar lo que el lector no entiende |
| Error write/flush/sync tras bytes | COMMIT_UNKNOWN; reconciliar mismo transactionId, sin reintento con nueva identidad ni efectos repetidos |

La recuperación no depende de conservar un flag en RAM. Bytes completos con LF pueden existir tras un crash sin ack de fsync; resincronizar antes de confiar. Si la escritura ambigua está completa/válida, devolver su receipt original; si está ausente o parcial, reparar primero y solo entonces decidir reintento con el mismo ID. Si sync sigue fallando, detener nuevas escrituras/efectos.

La cuarentena no es fuente del agente ni índice recuperable. Se elimina con el borrado explícito y no se duplica por cada arranque. Eliminar explícitamente un mensaje/conversación invalida también summaries, índices y fuentes auxiliares correspondientes.

Archivos nuevos/renames y artefactos referenciados requieren comprobar orden de durabilidad, incluido directorio padre cuando aplique. Un commit no puede prometer recuperar un artefacto cuya existencia durable no se aseguró. No se atribuye al contrato una garantía de almacenamiento Android hasta pasar sus pruebas.

### 9.4 Estado verificado incremental

NO se relee desde byte cero todo el historial después de cada evento normal. Bajo coordinador, mantener en memoria:
- identidad de archivo/prefijo validado y offset del límite íntegro;
- hash de cola, contextRevision y estado de proyección/aliases/receipts reconstruido;
- confianza/epoch del escritor y condición de recuperación requerida.

Arranque, pérdida de confianza, error, truncamiento/reemplazo inesperado o cambio fuera del coordinador obligan a reconstruir desde origen o checkpoint validado. En proceso estable, verificar solo nuevas transacciones/bytes y actualizar estado. No confiar únicamente en tamaño/mtime ni omitir checksums nuevos.

Un checkpoint persistido solo acelera si su prefijo/cadena y pertenencia se validan con una política demostrada; en la primera versión es aceptable reconstruir una vez al arrancar y usar estado incremental en memoria. No sacrificar integridad para prometer arranque O(1).

Prueba de eficiencia: instrumentar bytes leídos/reproyectados y duración del lock sobre diario sintético largo. Un append ordinario no debe releer todo el prefijo. Recorrer el contexto activo para una petición es distinto de releer todos los originales excluidos en cada escritura. No hay métrica de rendimiento medida aún.

## 10. Recuperación y borrado

Añadir capacidades equivalentes a:

- `buscar_historial(consulta, cursor?, limite?)`: sesión inyectada por runtime, consulta 1–256 caracteres, hasta 8 resultados de 240 caracteres de fragmento, salida total ≤4.000 caracteres. Buscar originales accesibles, incluso EXCLUDED/SUMMARY; devolver alias, autor, interacción/fecha, estado y cursor opaco ligado a consulta/versión. Consulta vacía no permite volcar todo.
- `leer_original(id, offset?, limite?)`: resolver alias en la conversación; hasta 6.000 puntos de código por página y ≤8.000 caracteres con metadatos, hash/fuente/autor/offset siguiente. No aceptar rutas arbitrarias ni IDs de otras sesiones. Señalar explícitamente fuente parcial/ausente.

Reutilizar normalización/BM25 léxicos existentes o escaneo acotado; no agregar embeddings. Cualquier índice es derivado, descartable y sujeto a la visibilidad/borrado de fuentes. Un cursor inválido/obsoleto devuelve error o reinicio explícito, no una página mezclada.

Resultados de recuperación son evidencia de herramienta de la interacción actual. No modifican edad, rol auténtico ni representación del original. En el prototipo, esos grupos quedan fuera de edición y pueden acumular coste. Una expiración menor que la protección de interacción requeriría otro contrato; no hay TTL oculto.

`read_conversation_artifact` existente continúa siendo distinto: recupera artefactos que ya conoce el agente, con límites previos. El nuevo mecanismo no puede reconstruir bytes de herramientas antiguas que nunca se conservaron. No prometer integralidad de esas fuentes.

Al borrar/regenerar contenido, invalidar representaciones dependientes, resultados de búsqueda, cachés y cursores. La recuperación no debe revivir contenido que la UI declaró borrado. Recalcular presupuestos después de invalidar un resumen; no enviar todos los originales automáticamente si ya no caben.

## 11. Presupuesto, frecuencia y coste

Preflight antes de CADA llamada usando la petición preparada real: instrucciones, tools schemas, cabeceras, metadatos, prompt/cuerpo único, imágenes cuando aplique y reserva de salida. No convertir Base64/4 en coste de visión. La estimación local y el usage reportado son métricas diferentes.

El origen de ventana/límite debe constar: metadata del proveedor/catálogo, configuración explícita o fallback. Un fallback no demuestra capacidad real de custom. Si falta precisión suficiente, aplicar margen/bloqueo conservador o mantener esa configuración fuera del ensayo de APPLY; no prometer preflight exacto donde no hay tokenizador/metadata fiable.

Valores iniciales de ensayo, sujetos a calibración:
- sugerir mantenimiento útil cerca del 65% de ventana utilizable, no exigir pie en cada respuesta;
- reservar salida suficiente para respuesta normal + footer máximo + margen;
- evitar lotes insignificantes con umbral de reducción neta, por ejemplo 256 tokens estimados o 5% de parte editable, definiendo la regla exacta antes de la evaluación;
- cero llamadas separadas de mantenimiento por defecto;
- no volver a incentivar limpieza sin crecimiento/presión material.

Pie ausente/inválido/insuficiente: conservar respuesta normal y seguir solo si el siguiente preflight cabe. Si no cabe, estado de bloqueo preciso. No penalizar con otra llamada automática ni ejecutar recorte heredado oculto.

Solo contenido protegido + instrucciones/herramientas + reserva mayor que capacidad: detener nuevas llamadas, conservar estado/acciones y exponer alternativas. Cambiar modelo, reducir N, compactar contenido protegido o transferir sesión necesita una política adicional aprobada; ninguno se infiere del mantenimiento selectivo.

Interacción larga con herramientas: limitar/paginar resultados desde sus contratos, conservar fuentes cuando estén disponibles, respetar `completeContentRequired`. No envejecer ni purgar una interacción OPEN para que quepa. La aplicación puede bloquearse aun cuando todos los mensajes antiguos editables ya se excluyeron.

Se medirá el coste total por tarea completada: entrada, salida, entrada cacheada si existe, llamadas extra, recuperaciones, reintentos, errores y latencia. Cambiar prefijos puede reducir cache hits; menos bytes no implica igual reducción monetaria. No se fija ni promete porcentaje de ahorro.

## 12. Modos, migración y reversión

### 12.1 Estados

| Estado | Selección | Propuestas | Crecimiento y compactor |
|---|---|---|---|
| LEGACY | Comportamiento anterior | Protocolo deshabilitado | Triggers existentes |
| DRY_RUN | Proyección selectiva inicial coherente | Registra hipotéticos; no aplica | Nuevos originales; no triggers incompatibles |
| APPLY | Proyección durable | Aplica solo lote válido | Nuevos originales protegidos; preflight estricto |
| PAUSED | Conserva SUMMARY/EXCLUDED establecidos | Ninguna aplicación ni `e` | Nuevos originales siguen creciendo; no reactivar legacy |

Desactivar después de APPLY significa PAUSED, no LEGACY. Congelar proyección no congela conversación. Recuperación y preflight siguen activos. DRY_RUN no se declara byte-equivalente a LEGACY: es un brazo experimental separado.

### 12.2 Transiciones

LEGACY → DRY_RUN exige fuentes/identidades/replay y presupuesto coherentes, sin purga de arranque. DRY_RUN → APPLY exige evidencia revisada y aprobación del corpus/entorno; no aplica propuestas antiguas. APPLY → PAUSED invalida propuestas en vuelo. PAUSED → APPLY toma snapshot nuevo y recalcula edad/pins.

Restaurar una representación exige fuente y presupuesto; no cambia de motor por sí solo. Volver a LEGACY exige migración explícita y prueba del lector destino. Si el historial reconstruido no cabe, mantener estado actual y decidir una transición, no reenviar masivamente.

### 12.3 Datos anteriores

Preservar UUID conocidos y crear aliases idempotentes para registros legados sin alterar contenido. Las asociaciones inciertas permanecen protegidas. Los resúmenes legacy no se convierten en originales elegibles ni se envían junto a otra copia de sus fuentes por accidente.

Conversación previa ya compactada: construir una transición sin duplicación y verificar presupuesto antes de activar. Si no cabe o faltan fuentes, no activar ese chat en este prototipo. La UI puede conservar su historia aunque la función selectiva no esté habilitada.

### 12.4 Downgrade

Un campo de versión nuevo no obliga a un binario publicado antes a interpretarlo. Downgrade con proyecciones activas es no soportado salvo prueba concreta del lector destino y transición previa. Los gates de versiones nuevas no se atribuyen a versiones antiguas. La restricción habitual de versionCode de Android no basta para garantizar seguridad en todos los restores/instalaciones.

## 13. Fases, gates y orden de trabajo

### 13.1 Orden de cola

UX34 empieza después de finalizar UX33 y todos los pendientes anteriores. Publicar documentación no equivale a comenzar implementación. Ninguna fase posterior se considera aprobada solo porque la anterior terminó.

### 13.2 Fase 0: diagnóstico y seams

Alcance exacto: contrato de finalización/identidad/uso, captura sintética de solicitudes y transporte falso, pruebas de dos familias/cuatro configuraciones, preservación de campos/selección legacy. No parser de memoria, cambios de prompt, migraciones, proyecciones activas, purgas, DRY_RUN/APPLY real, instalaciones ni releases como parte de esta fase.

Registrar base real y diff propio; mantener cambios ajenos intactos. Trabajar aislado o con propiedad de checkout coordinada. Ejecutar checks pertinentes y entregar resultados, no otro plan completo. Lista de aceptación en [PHASE0_ACCEPTANCE.md](PHASE0_ACCEPTANCE.md).

**Gate P0:** revisión de resultados con contratos positivos/negativos, legacy intacto, evidencia sintética y limitaciones. Terminar la fase aquí. Pasar a fase 1 requiere su aprobación/gate posterior.

### 13.3 Fase 1: identidad y almacenamiento

UUID/aliases/interacciones/intentos, coordinador, revisión/cadena y reparación física, migración solo de fixtures. Probar cola A/B/C, fila sin LF, sync ambiguo, corrupción intermedia, borrado, concurrent writers y verificación incremental. Medir bytes/lock; no full-rescan por append normal.

**Gate P1:** integridad/recuperación/eficiencia demostradas en pruebas sintéticas. No migraciones reales implícitas.

### 13.4 Fase 2: proyección, recuperación y snapshots

Fuentes accesibles, búsqueda/lectura acotadas, tipo derivado assistant, autoridad canónica sin ampliación, FrozenRequestSnapshot coherente y wire sin duplicados. Probar dos herramientas/final, generación/política, roles y borrado.

**Gate P2:** la ausencia/presencia se verifica en bytes de transporte, además de listas locales. Fallos y fuentes incompletas explícitos.

### 13.5 Fase 3: simulación etiquetada

Parser/validador puros y DRY_RUN controlado, sin aplicación. Separar ensayo mecánico anotado y evaluación semántica de candidatos estructurales; el oráculo no oculta casos. Medir rechazos, sobrecoste y decisiones propuestas. No afirmar continuidad bajo purga efectiva si solo se simuló.

**Gate P3:** protocolo/estado y criterios semánticos revisados; no autoriza APPLY real.

### 13.6 Fase 4: APPLY limitado

Aplicar solo a corpus/entorno explícitamente autorizados después de fuentes, recuperación, transacciones y validación de contexto. Comparar tareas completas con baseline real. Respetar PAUSED y overflow. No equivale a autonomía de producto general.

**Gate P4:** evidencia de calidad/coste/durabilidad y decisión de producto separada. Editar usuarios/grupos/multimodal/otros agentes sigue fuera de alcance hasta nuevo diseño.

## 14. Evaluación y evidencia

### 14.1 Matriz obligatoria del mecanismo posterior a P0

| Área | Casos positivos/negativos que deben existir |
|---|---|
| Original/proyección | PURGAR elimina representación en siguiente wire; original visible/recuperable; RESUMIR conserva alias/procedencia |
| Transiciones | ORIGINAL → SUMMARY → EXCLUDED → leer original, sin reactivación |
| Protección | Diez interacciones con distinta cantidad de filas; todas abiertas/inciertas; regeneración y pins múltiples |
| Protocolo | Pie ausente/válido/vacío/malformado/repetido/incompleto; IDs ajenos/inexistentes/repetidos; operación no permitida |
| Literales | Código fences/inline/citas; documentos/tool outputs/historia no se ejecutan |
| Finalidad | stop/completed positivo; length/incomplete/filtered/failed/cancel/unknown nunca aplica |
| Estado | Dos herramientas y final aceptado; propio commit no se invalida; nuevo usuario/política sí invalida; telemetría inocua no |
| Snapshot | Barrera de captura y cambio de configuración: A coherente o reconstrucción B, jamás mezcla |
| Diario | A/B parcial/C, B completo sin LF, checksum inválido, schema futuro, sync desconocido y segundo reinicio |
| Incrementalidad | Bytes/lock por append ordinario sin releer prefijo completo; reconstrucción al perder confianza |
| Herramientas | Call/result closure; resultados tardíos guardados; ausencia de replay |
| Roles/autoridad | Summary assistant sigue assistant; «aprobado»/email inventado no concede derechos |
| Recuperación | Límites/paginación/pertenencia/borrado; búsqueda encuentra originales excluidos; cursor obsoleto no mezcla |
| Presupuesto | Clases no reclamables acumuladas, protected overflow, modelo pequeño sin pie, salidas grandes y cachés |
| Modos | APPLY→PAUSED→nueva interacción; no resurrección ni bounded; downgrade declarado/no supuesto |
| Fidelidad | Cifras, negativas, pendientes, propuesta frente a aprobación, exceso de retención y daño por selección |

### 14.2 Baselines y métricas

Comparar mismas tareas/modelo/herramientas en LEGACY real, selectivo DRY_RUN y APPLY controlado cuando corresponda. El baseline legacy incluye compactación/llamadas de resumen actuales; no comparar solo contra historial infinito.

Registrar por clase tamaño y proporción de contexto, sin doble contar pins; punto/causa de bloqueo; éxito funcional; tokens reportados y estimados separados; entrada cacheada cuando exista; salida/pies/resúmenes/recuperaciones; reintentos/calls; coste con tarifas verificadas; latencia/almacenamiento; errores semánticos; trabajo humano de anotación como coste distinto de inferencia.

Reportar todas las tareas, no solo las que favorecen el diseño. Menor consumo de una ejecución incompleta no es ahorro de una tarea lograda. No llamar prueba de servidor remoto a un mock ni prueba de disco real a una simulación de fallos.

## 15. Entregables y control de cambios

Cada fase debe entregar base real, archivos/símbolos propios, diff/commit, comandos ejecutados con exit codes y resultados, fixtures/logs sintéticos, riesgos y gate solicitado. Lo no ejecutado se marca expresamente. No publicar credenciales, conversaciones privadas, capturas de cuentas ni datos de usuarios.

Documentos y Pending.md se respaldan de acuerdo con el flujo del repositorio. La documentación técnica no debe mezclarse con transcript privado del proceso de revisión. En una discrepancia, SPEC.md prevalece sobre las alternativas históricas de AUDIT/CONTRACT_REVIEW; cambios de alcance se registran en la especificación antes de implementarse.

No hay resultados de ahorro, ejecución de purga ni evidencia de migración real en esta versión de la especificación.
