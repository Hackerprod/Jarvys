# S6 — contrato mínimo pendiente de flush, tramo y digest

2026-10-26. **Investigación de fuente y propuesta; no código/esquema, no PASS.** SDK pendiente. Alcance cloud local, sin publicación. Releer el árbol al implementar: otros bloques S6 siguen en revisión.

## 1. Requisito normativo y límites de producto

`docs/SINGLE_SESSION.md` §7.1 exige referencias a episodios del tramo resumido, descritos por `through_seq`; §7.5 fila C exige, antes de compactar: (1) narrar episodios pendientes del tramo, (2) crear/actualizar digest del tramo con topic_key y part_of hacia episodios, (3) conservar extracción de hechos de usuario. §7.5 prohíbe que imágenes/adjuntos/Coding desactiven memoria y requiere contexto de respuesta final y evidencia acotada para episodios. El fallo del flush no bloquea necesariamente la compactación: debe quedar incompleto, nunca presentarse como completado.

La tabla §7.7 decide que session_start/end/summary no se adoptan como herramientas: **el equivalente es compactación + digest**, no eliminar esa función. `docs/continue/engram-features.md` F02 describe upsert estable; F17/F18 documentan sesiones/resumen/finalización; F03 dedupe textual no prueba idempotencia por operación. No trasladar formato obligatorio, constantes, ventanas temporales ni interfaces Engram por analogía. Tampoco usar las exclusiones explícitas de pin/compare/review/doctor para excluir digest. F22/S7 y paridad completa son otro bloque.

## 2. Trazado actual y huecos comprobados

- `ConversationCompactor.compact`: calcula plan.summarize/keep, pero llama `memoryFlush.beforeCompact(new ArrayList<>(transcript), token)` con **todo** el transcript. Si sliding no cabe, cambia después a planAll y vuelve a resumir sin repetir/reencuadrar flush.
- `CoreAgentRuntime.compactionMemoryLifecycle`: pasa esa lista a `PersonalMemoryFlush.run`; `onCompacted` actualiza contadores después de appendCompaction y no revierte una compactación ya escrita si falla el contador.
- `PersonalMemoryFlush.run`: elegibilidad + needsMemoryFlush; llama `EpisodeNarrator.narratePending(token)` para toda la conversación; luego runPass. No crea digest de compactación ni links part_of.
- `EpisodeNarrator.narratePending`: consulta todos los episodios de conversación con narration none/pending/failed, alguna evidencia tool_call/crew_result OK y ninguna intent/started. Preserva chequeos de memoria/cancelación y commit por fingerprint de evidencia/fuentes. El filtro actual excluye episodios solo error/uncertain/not_executed/deferred; no cambiarlo silenciosamente como parte del rango ni ocultar esos episodios del digest.
- `PersonalMemoryFlush.privateUserMessages`: copia solo mensajes user genuinos, omite incoming no confiable, conserva durableMessageId. No contiene respuestas finales ni resultados; apropiado para extracción de hechos, **insuficiente como única fuente del digest de episodios**.
- `runPass`: herramientas de extracción, recibo persistenceEvidence o NO_REPLY; llama markMemoryFlushCompleted antes de que el compactor persista su resumen. No consulta ni actualiza cursor incremental.
- `appendEpisodeReferences`: obtiene episodios solo por durableMessageId de mensajes user presentes en plan.summarize. No resuelve tramo seq ni incorpora por sí solo episodios cuyo owner ya quedó dentro de una compactación previa.
- `PersonalAgentRepository.recordEpisodeToolStarted`: crea episodios de herramienta con through_seq NULL. `MissionEpisodeRepository` sí escribe through_seq=sourceSeq del input de misión. No existe hoy base homogénea para ejecutar el filtro normativo por through_seq.
- `saveMemory` admite kind=digest y topic_key, revisiones y links en transacción. Sin embargo `LINK_RELATIONS` solo admite related/compatible/conflicts_with: **part_of no está en la API normal**. No basta mandar relation=part_of desde el modelo. La consolidación diaria existente crea digest daily_consolidation; no equivale al digest de tramo de compactación.
- `PersonalMemoryExtractionWorker.extractIncrementally`: lee inputs posteriores a saved_turn_count, extrae users, avanza al máximo seq tras éxito y memoria habilitada. Narración no avanza ese cursor. Extract y snapshot tienen jobs/leases por tipo; flush síncrono no comparte ese ownership. Es posible repetir extracción entre flush y worker; MAX(cursor) no evita doble escritura ni demuestra cobertura sin huecos.
- `markMemoryFlushCompleted` guarda compaction_count+1; `markCompacted` incrementa contador y acredita completed por booleano. No identifica qué plan/rango/fingerprint produjo ese éxito. Una cancelación posterior o fallback de plan puede reutilizar una marca que no corresponde al rango final.

## 3. Fuentes e identidad exacta del tramo

Introducir un descriptor interno de cobertura (API a diseñar, no esquema aprobado) que el compactor construye a partir de **plan.summarize real**, no de transcript completo ni de índices de posición:

- conversationId; identidad de compactación/base durable vigente;
- message IDs genuinos y su seq resuelto en Room;
- batches/call IDs exactos y run IDs de evidencia cuando corresponda;
- cobertura explícita heredada de resúmenes previos, recuperada de filas de compactación, no parseada del texto del resumen;
- frontera final y fingerprint de pertenencia/orígenes, distinguiendo seq de originalMessageIndex/firstKeptMessageIndex.

No sustituir pertenencia por min/max de índices si hay huecos, mensajes retenidos o steering. Resolver siempre en la conversación correcta. No derivar identidad por texto, timestamps, último batch ni call ID aislado. Una planificación que deja el user actual visible aunque lo sintetice debe describir su inclusión real sin confundirlo con destrucción del mensaje durable.

Para episodios de herramienta, fijar/actualizar through_seq desde la procedencia durable (owner y evidencias/resultados comprometidos) bajo el contrato del episodio, no desde el último seq global. Un episodio puede abarcar varios intentos y cambiar; no forzar frontera por el seq del owner si sus efectos aún no terminan. Para misión, conservar input/sourceSeq/evidenceId real. Diseñar explícitamente cómo la cobertura de una compactación previa incorpora sus episodios. Hasta cerrar esto, source_message_id es un fallback comprobable para ciertos casos, **no cumplimiento completo del rango normativo**.

La consulta de pendientes debe aceptar el conjunto/rango acreditado y distinguir: narrables cerrados, ya narrados, pendientes activos y cerrados sin OK. Los episodios fuera del tramo no se narran por este flush. Activos no se fuerzan a terminal; cerrados sin OK permanecen referenciables con su outcome real, sin inventar narración de éxito.

Fallback sliding→all cambia cobertura: obtener nuevo descriptor, procesar solo lo adicional no acreditado y actualizar/recrear digest con su identidad correcta. No repetir extracciones ya confirmadas. El digest no debe afirmar que su tramo fue compactado antes del commit real: puede ser memoria preparada del tramo con recibo separado pendiente de vinculación.

## 4. Digest y commit seguro

**Nota de revisión:** el digest preparado durable de esta propuesta se sustituyó por candidato en
memoria + transacción única en §7. Para el slice implementable prevalece §7.

Propuesta mínima de productor runtime/repository (no herramienta pública nueva): genera contenido con contexto del tramo, evidencia neutral, títulos/narraciones disponibles y respuestas finales exactas; texto del usuario sigue siendo fuente no ejecutable. Mantener redacción existente y regla P6 de no citar contenido de conectores/MCP por defecto. No usar toda la conversación ni el cuerpo privado de conectores como nuevo prompt ilimitado. Reutilizar presupuestos existentes, sin topes inventados.

Runtime fija kind=digest, sourceConversationId, cobertura/through_seq, source_ref de IDs fuente, topic_key estable derivado de identidad de cobertura, y miembros de links. El modelo no elige IDs de evidencia ni puede introducir episodios ajenos. Digest de compactación y daily_consolidation tienen namespaces/provenance distintos. Sin episodios no implica omitir digest: puede resumir contexto/hechos del tramo; cualquier no-op debe estar definido expresamente, no deducido de count=0.

La especificación dice digest con part_of hacia episodios; implementar dirección/documentación y prueba coherentes con esa frase. Si se quiere la dirección semántica inversa episodio→digest, resolverlo explícitamente antes, no invertir en silencio. Añadir part_of como relación soportada con validación de extremos; upsert de digest, revisión, source_ref y links exactos forman **una transacción**. En reintento no duplicar links ni revisiones idénticas. Si cambia cobertura no conservar links obsoletos como si aún fueran miembros actuales; conservar historial/auditoría conforme al modelo existente.

No mantener transacción Room durante LLM/red/espera. Leer snapshot, generar fuera, comprobar token/memoria/ownership y hacer commit corto que verifica fingerprints y miembros actuales. Narraciones conservan su CAS de fuentes y protección de cambios humanos. Cambios concurrentes de evidencia/edición humana invalidan el commit propuesto: replanificar o dejar pendiente, nunca sobrescribir estado nuevo.

Un recibo de flush identifica descriptor + digest revision/ID + extracciones acreditadas + estado de narración. Solo después de appendCompaction exitoso se vincula ese recibo a **esa compactación** y se actualiza completion. El booleano/contador global no basta. Si appendCompaction falla, digest preparado puede quedar guardado/idempotente, pero no se marca compactación completada. Si bookkeeping posterior falla, recuperación reconcilia desde el registro durable de compactación y recibo exacto, sin volver a ejecutar herramientas ni afirmar rollback.

Cancelación antes de commit: ningún nuevo commit; cancelación después: conservar resultado confirmado y permitir retry idempotente. Memoria desactivada durante LLM: no guardar nueva memoria ni avanzar marcador. La autoridad de toggle necesita coordinación real en la frontera de escritura, no solo lectura al inicio; no añadir transacción larga ni cambiar preferencia para completar. Fallo de narrador/digest/extracción deja flush incompleto y conserva evidencia. La compactación puede continuar bajo el contrato existente, manteniendo referencias recuperables; no convertir un fallo local en pérdida de episodios.

## 5. Extracción compartida: no doble escritura ni salto de cursor

No avanzar saved_turn_count desde narración o digest. No avanzar el cursor de extracción al final del tramo solo porque hubo digest: ese tramo puede omitir inputs que el worker aún necesita procesar.

Se necesita ownership compartido entre flush, extract y snapshot para extracción de hechos, con identidad estable de input/operación y recibos durables. Propuesta: coordinador común que selecciona mensajes genuinos por IDs/seq, resta inputs ya acreditados y mantiene recibo por unidad/rango procesado. Solo avanzar watermark por cobertura **contigua demostrada**, nunca por máximo de un subconjunto. Un lease por jobType no basta para exclusión mutua entre tipos y flush.

Persistencia de hechos y acreditación de esos inputs deben ser idempotentes frente a muerte entre ambos pasos. Las herramientas actuales escriben cada memoria antes de completar el pass; un lease y cursor al final no garantizan exactamente una escritura. Opciones a decidir antes del código: staging de propuestas con commit atómico + cursor/recibo, o claves de operación durables por input+propuesta y ledger de pass. No reutilizar topic_key textual como prueba universal: el modelo puede variar contenido/clave en retry. No retroceder cursor ni borrar hechos ya guardados al fallar un digest.

La extracción del tramo solo recibe hechos de usuario; digest/narración pueden leer respuesta final y evidencia sin elevarlos a hechos del usuario automáticamente. Snapshot usa el mismo ownership para no volver a extraer por otro jobType. Nuevos mensajes llegados durante generación quedan fuera del snapshot y se procesan después.

## 6. Pruebas y aceptación pendiente

- Plan sliding exacto: episodio dentro/fuera, mismo owner con varios intentos, sourceBatch/run repetidos, límites seq versus messageIndex, steering y resumen previo. Ninguna consulta global inadvertida.
- Fallback a all amplía rango: digest/links corresponden al plan final; hechos previos no se extraen dos veces. Reintento igual reutiliza identidad; cobertura distinta no colisiona.
- Digest con episodios OK/error/uncertain/deferred/not_executed y activo: estados honestos, sin false success; fallo de narración conserva evidencia y marca incomplete. Tramo sin episodios sigue contrato de digest.
- Transacción digest+links: rollback total ante fallo, rechazo de episodio ajeno, links idempotentes y dirección explícita, revisión humana/fingerprint concurrente protegido. Separación daily_consolidation.
- Commit de compactación falla después del digest; cancelación antes/después; bookkeeping falla después: recuperación por identidad exacta sin replay ni pérdida.
- Toggle de memoria durante modelo y justo antes de commit; imágenes/adjuntos/Coding no bloquean por privacidad retirada.
- Worker extract/snapshot y flush concurrentes, muerte entre memory_save y final del pass, entrada nueva, hueco de seq: cero saltos del watermark y no duplicación por retry. Narración/digest nunca avanzan cursor de hechos.
- B15/fact extraction existente y tests focales de PersonalMemoryFlush, ConversationCompactor, EpisodeNarrator, PersonalAgentRepository y worker incremental. No suite total. Mutaciones en copia: pasar transcript entero, quitar CAS, mover cursor sin acreditar inputs, descartar part_of, completar contador antes de append deben romper pruebas correspondientes.

**Estado:** diseño pendiente. El rango homogéneo through_seq, soporte part_of, recibo por compactación y ownership/idempotencia de extracción requieren decisiones/APIs concretas antes de implementación. Android/Room/KSP no ejecutados. No se declara S6 ni paridad Engram completas.

## 7. Corte mínimo acordado para implementación S6 (revisión 26 oct)

Esta sección acota la propuesta anterior. No afirma implementación ni ejecución de pruebas.
**Exactly-once universal de propuestas del modelo no es requisito textual de §7.5.** El staging/ledger
por propuesta de §5 queda fuera de este slice. Se conserva la extracción existente **at-least-once**:
un retry tras escrituras parciales puede repetir hechos; no se promete eliminarlo mediante topic_key
ni cursor. Digest/narración nunca avanzan saved_turn_count. Se mantiene el worker incremental actual;
la coordinación universal extract/snapshot/flush no es condición para implementar el digest.

### APIs y procedencia mínima

- `ConversationTurn` transporta procedencia inmutable tipada para MESSAGE(messageId),
  BATCH(batchId), BATCH_RESULT(batchId,callId) y COMPACTION(compactionId). Todas las copias la conservan.
  No confundir una fila de resultado con callId aislado. La lectura del transcript conserva las claves
  de sus filas; los turnos vivos reciben los bindings del checkpoint **después del commit exitoso**.
- `CompactionCoverage` contiene conversationId, referencias de `plan.summarize`, cobertura heredada
  acreditada de compactaciones anteriores y fingerprint canónico de fuentes hoja más sus versiones.
  Los IDs de compactaciones padre son procedencia causal, no identidad del tramo: envolver los mismos
  miembros sin cambios no crea otro digest. El snapshot CAS incluye contenido/fuentes/evidencia y
  revisión humana, no sólo IDs. El repositorio
  resuelve seq por claves exactas e índices existentes, no por posición, texto ni último batch.
  `through_seq` es frontera descriptiva de esa cobertura; no sustituye la lista de miembros.
  Separar coverageKey/topic de expectedSnapshot CAS: la revisión del propio digest y su edición humana
  sólo pertenecen al CAS, nunca a la identidad del topic (evita crear otra clave por cada guardado).
- `prepareCompactionCoverage` retorna snapshot o incompleto con motivo estructural. Turnos sintéticos
  conocidos pueden declararse no-fuente; un mensaje/batch/resumen durable sin binding no puede ignorarse.
  Compactaciones históricas sin cobertura exacta no se convierten retrospectivamente en rangos ciertos.
  Si falta fuente comprobable, el flush queda incompleto; la compactación puede continuar como hoy.
- La selección de episodios usa owner y fuentes de evidencia exactos dentro de cobertura. Actualizar
  `through_seq` desde procedencia durable, no desde el último seq de conversación. Episodios activos o
  parcialmente cubiertos no se presentan como completos. No hace falta inventar comportamiento S7 para
  steering: sólo se acreditan los bindings que realmente existen.

### Selección final, digest y commit

1. Resolver el plan definitivo sliding/all y su resumen candidato antes de la extracción de hechos.
   El presupuesto debe incluir las referencias de episodio del snapshot. Si el plan se amplía, rehacer
   cobertura/resumen; no extraer hechos del plan descartado y otra vez del definitivo.
2. Narrar sólo episodios elegibles de esa cobertura; leer nuevamente snapshot si cambian narraciones.
   Crear digest con presupuesto/redacción existentes, incluidos estados no exitosos y contexto final
   disponible. Falta de contexto necesario o fallo de fase deja incompleto, no éxito por ausencia.
3. Preparar `CompactionDigestCandidate(expectedSnapshot, proposedText)` sin guardarlo todavía. Runtime
   fija kind=digest, namespace topic_key de cobertura canónica, source_ref y frontera; el modelo no elige
   IDs. Su snapshot incluye los miembros y la revisión humana vigente que se comprobarán al commit.
4. Ejecutar la extracción existente una vez para el plan final. Su resultado forma parte del candidato
   del flush, pero no convierte el pass en exactly-once ni acredita cobertura incremental del worker.
5. `PreparedCompactionFlush` transporta coverage fingerprint, candidato de digest, snapshot y resultado
   de fases. Es una preparación en memoria, no recibo de persistencia ni prueba de compactación.
6. `LocalRunStore.appendCompaction` devuelve identidad durable (`Compaction` o id), en lugar de
   descartarla. `CompactionInput` puede transportar candidato/cobertura opcionales. **Una transacción**
   inserta compaction y, tras CAS válido de fuentes/evidencia/edición humana, hace upsert de digest,
   revisión, reemplazo exacto de miembros `digest --part_of--> episode`, cobertura y completion.
   Retry idéntico no añade revisión/links idénticos. Reemplazar sólo los miembros runtime de este digest,
   no enlaces ajenos/manuales. Un candidato invalidado permite append con flush incompleto explícito,
   pero nunca persiste texto stale ni reutiliza un booleano de otro plan. Fallo transaccional revierte
   el conjunto. No existen digest preparado durable huérfano ni bookkeeping exitoso antes del append.

Las tablas/columnas precisas y migración se fijarán con el implementador; no usar facts_json escaneado
como sustituto de claves de lookup. Mantener entrada desactivada/olvidada y CAS de edición humana;
no transacciones Room durante modelo/red ni nuevos permisos o herramientas públicas.

### Pruebas mínimas de este corte

Plan exacto versus transcript completo; fallback all sin segunda extracción; batch/call reutilizado
sin mezclar origen; resumen previo con cobertura y legado sin ella; episodios fuera/activos/parciales;
digest y part_of atómicos/idempotentes; cambios de evidencia o texto humano rechazan CAS;
append fallido no marca completed; append exitoso liga sólo su recibo; toggle/cancelación preservan
resultado ya comprometido. Pruebas de exactly-once universal de §6 quedan diferidas con §5, sin
ocultar la posibilidad actual de duplicación at-least-once. No declarar S6 completo sin validación.

### Corte de datos implementado localmente (sin validación Android)

- `ConversationTurn.Source` distingue MESSAGE/BATCH/BATCH_RESULT/COMPACTION y conversación exacta.
  Holder write-once compartido entre copias; checkpoint publica después del commit. Restore sólo asigna
  fuente de resultado a una fila real compatible, nunca a placeholders de interrupción.
- `CompactionCoverage` separa pertenencia/versiones leaf (coverageHash/topic) del fingerprint CAS
  (incluye episodios, narración, fuente/respuesta final y digest existente). Ancestros son wrappers:
  expanden filas persistidas y verifican sus versiones; no cambian el topic de iguales leaves.
- Cobertura estructural y finalización del flush son independientes. Episodio parcial o task sin vínculo
  model_spine demostrable conserva leaves y marca `episodesComplete=false`; no se omite para declarar
  éxito. Fuente ausente, oculta o ancestro legado sin cobertura no se acredita.
- Texto/rol MESSAGE se capturan de la misma lectura durable que el fingerprint. `sourceTexts` permite
  detectar transcript obsoleto y validar resúmenes padre sin parsearlos como hechos. El payload de digest
  no puede sustituir ese snapshot por texto antiguo de la UI. Leaves conservan trust/provenance y
  sort_seq/sort_sub/seq para ordenar el diálogo; el orden canónico por identidad sólo calcula hashes.
- `appendCompactionPrepared` retorna la Compaction real y une resumen, coverage leaf, digest/revisión,
  evidencia `model_spine`, links `digest --part_of--> episode`, status exacto y contador en una transacción.
  CAS inválido conserva el resumen con flush incompleto; receipt ausente no escribe digest ni episodios.
  `through_seq` de episodios cerrados se actualiza sólo desde miembros fuente demostrados en commit válido.
- Dos tablas nuevas en schema WIP5: `compaction_coverage_sources` (PK conversación/compaction/kind/id/call,
  fingerprint/seq sin copiar cuerpos) y `compaction_flush` (PK conversación/compaction, cobertura/digest/
  revisión/status). FK cascade a la compactación. No se inventa schema JSON5 sin KSP.
- Guard explícito `BooleanSupplier canCommit` comprueba cancelación/toggle antes del commit de memoria;
  **no es un gate atómico compartido con SharedPreferences**. Sigue existiendo ventana entre la última
  comprobación y commit si se cambia el toggle concurrentemente. No afirmar atomicidad ni introducir
  otro gate en este corte.

`CompactionFlushPersistenceTest`: 14 pruebas preparadas de selección exacta/retención, CAS, guard,
reutilización de topic/revisión, herencia/legado, source missing/foreign/hidden, rollback, partial/reused
call, edición concurrente, restore de protocolo y holder compartido. No ejecutadas (SDK pendiente).
Revisor ejecutó DDL SQLite4→5 aislado, PK/duplicados/cascade/lookup indexado: PASS de ese check solamente;
no compilación Android, Room/KSP ni suite. Revisión estática final y controles de whitespace no equivalen
al PASS funcional de S6.

### Consulta scoped de narración — reanudación posterior al SDK

El corte anterior ya se compiló y ejecutó: schema5 exportado por KSP, tests Room/flush incluidos
entre 1.088 ejecuciones seleccionadas aprobadas. Las referencias anteriores a SDK pendiente son
históricas, no el estado actual.

La sobrecarga de cola recibe una colección opcional de IDs resuelta por cobertura. `null` conserva
la cola de mantenimiento; vacío significa ningún episodio y retorna antes de despacho de bridge,
transacción, prompt o modelo. Un scope inexistente no se convierte en cola global.

IDs se copian/deduplican, sin truncar. Cada consulta usa `id IN` parametrizado junto con TODOS los
predicados de conversación, estado, no borrado, evidencia OK y ausencia de intent/started. Chunks de
400 son únicamente transporte bajo una misma transacción de lectura; el resultado combinado se
ordena globalmente por event_at (null primero) e ID. Narrator ya no carga la cola global para filtrar
en Kotlin. Tres nuevas pruebas cubren elegibilidad (incluido OK+started), foreign/deleted/done/no-OK,
duplicados, orden entre chunks y ausencia de LLM/escrituras para episodios fuera de scope.

Este cambio no resuelve por sí solo el gate memoria/toggle: ese contrato requiere exclusión hasta
commit, invalidación de resultados antiguos y preservación separada de evidencia causal y acciones
humanas explícitas. Se diseña y revisa como siguiente slice, sin afirmar garantía universal prematura.

Validación scoped: Full 167/167 y Play 167/167 PASS, cero skips; revisión independiente conforme.
