# Roadmap de portabilidad selectiva de bots para Jarvys

Fecha de preparación: 31 de octubre de 2026. Revisión documental 2.

## 1 Decisión y resultado buscado

**Recomendación: adoptar primero los contratos de coordinación durable de OpenMausBot y después mejoras selectivas de OpenDuet.** No portar ninguna de las dos aplicaciones completas. Jarvys ya tiene bots, workspaces, checkpoints, buzones, políticas y entrega de resultados; la primera entrega debe cerrar las discontinuidades entre esas piezas, no sustituirlas por otro framework.

El primer resultado útil será un recorrido observable: **Central delega una tarea, el hijo pide un dato o el padre espera su resultado, la respuesta o resultado queda correlacionado y el trabajo continúa tras reabrir Jarvys, sin repetir efectos inciertos**. La recuperación de un worker interrumpido conserva el control nativo explícito de **Reanudar**. Leer un checkpoint, recibir una respuesta o abrir una vista no autoriza por sí solo a reejecutar trabajo.

Jarvys sigue siendo un asistente de uso general con **un único chat de Central**. Se conservan el drawer, navegación, Workspaces, Bots, Tareas programadas, perfil, ajustes, diseño y controles existentes. Los workspaces conservan datos consultables, decisiones y referencias de artefactos para nuevas misiones, incluso después de compactaciones y largos intervalos. No se crean chats de usuario por bot ni se reemplaza Engram.

**Este encargo autoriza el roadmap y su evaluación independiente, no la implementación.** El PASS del evaluador certificará sólo que el plan es ejecutable y respeta el alcance. No certificará código, paridad, APK, instalación ni modelo real. Hasta ese dictamen, esta revisión es candidata; el autor no se autoaprueba.

La instrucción histórica del 28 de octubre, registrada en [Pending.md](../Pending.md), pone Engram antes de bots. La petición del 31 de octubre permite analizar y planificar; no elimina silenciosamente ese orden. Antes de implementar, el usuario debe autorizar la unidad concreta y resolver D1. El plan distingue esa decisión de producto de las dependencias técnicas: los residuales administrativos P5/F36 y sync Git/P6 no son un prerrequisito técnico general para el primer recorrido.

## 2 Baseline y significado de la evidencia

| Elemento | Pin o evidencia | Límite |
|---|---|---|
| Jarvys inspeccionado | `455e53a1f7d6b887beff9d10da8b521edd98c91c` | Base del análisis de código y documentación. El roadmap es un archivo nuevo; no cambia producción. |
| Producción actual documentada | `bc3639918b36cf0af7831d057c29b90e6f43001e`, v97, `1.2.90-MENU-RESTORE` | Restauración acotada del menú. [README](../README.md) registra Full 146 y Play 146 casos pasados, 28 clases por sabor; focal 21 separado. No se reejecutaron en este trabajo. |
| Núcleo de memoria anterior | `71f30940e0fa9724cc36841232949caffbf3f08b` | Full 215 y Play 215, 25 clases por sabor, evidencia histórica separada. No se suma a v97 ni prueba paridad global. |
| APK v97 existente | `Jarvys-v97-full-arm64-menu-restored-debug.apk`; SHA-256 `b512bbc15658271fb77cd2d58be2d62a31a105b269891debbb80cd3c6d003451` | Entrega en tres partes aceptada el 30-10 a las 23:59:55 UTC. Instalación y aceptación física pendientes; no se pide borrar datos ni desinstalar. |
| OpenMausBot | [Commit `7de00e425848d5f1a2a845cb4784851b87d8cad2`](https://github.com/milind-soni/OpenMausBot/tree/7de00e425848d5f1a2a845cb4784851b87d8cad2) | Inspección de fuente y tests, no ejecución del runtime ni de sus dependencias. |
| OpenDuet | [Commit `677e0fcd148d80b99dd788c6d6f9c1c9f40a72db`](https://github.com/CopilotKit/OpenDuet/tree/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db) | Inspección de fuente mediante GitHub, no ejecución local ni validación empírica del producto. |
| CI de OpenDuet | [Run 40781447146](https://github.com/CopilotKit/OpenDuet/actions/runs/40781447146), correspondiente al pin | Estado Failure: tests y regresiones del harness fallaron. El análisis registra pasos de build, lint/types, browser, imagen y migrations pasados, no CI verde. No se hereda ninguna garantía a Jarvys. |

El árbol local ya contiene evidencia privada sin seguimiento en `pending/p5-orphan-relations/`, `pending/p5-repair/`, `pending/p5-tombstone-attribution/` y `pending/workspace-core-memory/`. No pertenece a este entregable y no se modifica ni publica.

**Tres categorías que no deben mezclarse:** (a) código y tests existentes inspeccionados; (b) resultados históricos declarados en los checkpoints; (c) pruebas futuras exigidas por este plan. Los tests de las secciones siguientes son **planificados, no ejecutados**. El trabajo documental no compila APKs, instala upstream, llama proveedores, cambia versiones ni modifica tests.

## 3 Matriz de fuentes y brechas reales

Leyenda: **PRESENTE** significa que existe el contrato indicado en la base inspeccionada, no que toda su aceptación física esté probada; **PARCIAL**, que existen piezas pero falta cerrar o probar el recorrido; **BRECHA**, que no se encontró el contrato preciso en la superficie inspeccionada; **NO VERIFICADO**, que falta evidencia para decidir. Una brecha acotada no afirma ausencia en todo el repositorio.

Las referencias J y U de esta tabla se resuelven en el catálogo de fuentes al final. P0 debe actualizar una fila si la inspección focal contradice su clasificación; no se implementa una copia sólo porque upstream la tenga.

| ID | Capacidad y fuente de referencia | Jarvys actual y evidencia | Decisión de portabilidad |
|---|---|---|---|
| M01 | Identidad durable de asignación, origen y árbol. U1 | **PRESENTE** identidad workspace/asignación/misión/bot/ciclo, revisiones y recibos: J1, J2. **BRECHA acotada** de aristas padre-hijo durables consultables como contrato de asignación; el esquema actual de `WorkspaceRegistry` no las contiene. | Extender sólo relaciones y causalidad faltantes; no reemplazar el registro ni confundir workspace con misión. P0/P2. |
| M02 | Pregunta, respuesta y continuación durable. U1/U2 | **PARCIAL**: `QUESTION/BLOCKER`, evidencia exacta, reply ligado al ciclo vivo, buzón, outbox durable de supervisor, incorporación y checkpoint existen (J3/J4). `coordinationRequests` es capacidad sólo en proceso; la reconciliación no la reconstruye. | Reusar el outbox existente y añadir sólo correlación/continuación faltante sobre punto seguro, sin convertir la API live-only en una vía de resurrección. P1. |
| M03 | Padre espera hijos y recibe una sola continuación. U1/U2 | **PARCIAL**: `CrewManager.waitFor` con ANY/ALL y mensajes, `WorkspaceResultDelivery` y avisos de resultado a Central (J4/J5). No se ha acreditado el árbol y sus dependencias como espera durable tras restart. | P1 cubre Central esperando al hijo; P2 cierra padre worker y sus hijos, con causas y consumo durable. |
| M04 | Estados y motivos tipados. U1 | **PARCIAL**: estados de bot, assignment y control; PARTIAL/INTERRUPTED, errores y evidencia (J1/J4). No equivale a una única taxonomía causal de pregunta, presupuesto, denegación, expiración y error de proveedor. | Crear una traducción explícita de outcomes, sin reemplazar los journals de efectos. P1/P2. |
| M05 | Recuperación e idempotencia. U2/U3 | **PRESENTE**: STARTED/UNCERTAIN no se reenvía, controles por revisión, checkpoints y recuperación explícita (J1/J2/J3/J4). | Preservar lo más conservador de Jarvys. Rechazar el `runtime.open()` de U2 que vuelve a ejecutar una hoja cuando el host no reporta efectos externos. Ausencia de evidencia no demuestra ausencia de efectos. |
| M06 | work, consult, notify. U1/U4 | **PARCIAL**: roles, `CrewMissionAccess`, herramientas de sólo lectura y distintos mensajes (J4/J6). No se acredita una semántica equivalente completa de los tres modos. | Reutilizar work. Añadir consult o notify sólo donde la fase necesite una diferencia real, con enforcement en dispatch, no sólo prompt. P2. |
| M07 | Profundidad, fan-out, presupuesto, ciclos y duplicados. U2/U3/U7 | **PARCIAL / NO VERIFICADO**: existen límites del runtime, cancelación y waits; no se ha demostrado un presupuesto transaccional por árbol durable. | Inventariar límites efectivos y reservar atómicamente lo faltante. Límites concretos son D2, no números copiados. P2. |
| M08 | Invariantes de árbol, eventos, resultado y wake. U3 | **PARCIAL**: tests nativos verifican recibos, ownership y resultados (J2/J3/J5); no suite equivalente del árbol propuesto. | Trasladar invariantes y escenarios, no todo el test harness TypeScript. P1/P2/P3. |
| M09 | Workspace durable y memoria de decisiones. U1 no sustituye Engram | **PRESENTE** para save/search/get/update scoped y recuperación por nueva misión; continuidad y autoridad cubiertas por J7. **NO VERIFICADO** en meses reales, reinicio físico y proveedor real. | Reusar Engram/Room y bindings existentes. Extender únicamente metadatos de continuidad que el recorrido necesite. No Markdown como memoria principal. |
| D01 | Investigación proactiva sólo lectura y aviso con evidencia. U5 | **PRESENTE**: allowlist explícita, rechazo de operaciones write, event IDs validados y notificación; J8. | No reconstruir este motor. Identificar únicamente puertas de dispatch que no preserven la restricción actual. P4. |
| D02 | Sugerencia separada de tarea aceptada; ocultar/suprimir repeticiones. U5 | **PARCIAL**: prefilter, eventos deduplicados, cola, avisos y suggested replies (J8). **NO VERIFICADO** equivalente durable de ocultar una sugerencia por identidad semántica y ventana. | P4 añade sólo brecha demostrada; upstream recuerda ocultas 14 días, valor de referencia no aprobado para Jarvys. |
| D03 | Fatiga de rutina distinta de reintentos de una ejecución. U6 | **NO VERIFICADO** equivalente exacto en scheduler/proactividad nativos. | P4 inventaría antes de tocar. Diez fallos consecutivos desactivan upstream; no convertirlo en política Jarvys sin D4. |
| D04 | Estado de tarea derivado de cola, preguntas y aprobaciones. U6 | **PARCIAL**: proyecciones de workspace y evidencias nativas (J1/J3/J5/J9), pero falta resolver precedencia del nuevo wait/continuation. | Proyectar sobre fuentes autoritativas; nunca otro campo mutable `status` mantenido por el modelo ni segunda base de estado. P1/P2 y refinamiento P4. |
| D05 | Leases, claim owner, handoff y send-once. U7/U8 | **PARCIAL**: tokens/ciclos, controles, journals y stop ownership (J2/J4/J6). No se acredita toda la semántica de leases del recorrido nuevo. | Portar fencing y pruebas de claim obsoleto donde sean necesarios; no Postgres, `SKIP LOCKED` ni reintentos de turnos con efecto incierto. P2/P3. |
| D06 | Motor headless y stack de servidor. U8 | **NO APLICA** como dependencia Android: Jarvys tiene `CoreAgentLoop`, runtime y servicio nativos. | Excluir CopilotKit Intelligence, copia de interfaces privadas, Bun/Hono/Postgres/Docker y UI web. Sólo contratos y escenarios seleccionados. |

## 4 Alcance incluido y exclusiones

### Incluido en unidades propuestas

- Un recorrido de pregunta/respuesta y espera/resultado accesible desde Central, primero con un hijo, después con dependencia padre-hijos acotada.
- Identidades, referencias y estados suficientes para reconocer qué se espera, qué respuesta fue admitida, qué checkpoint la incorporó y qué sigue siendo incierto.
- Reutilización de controles, políticas, almacenamiento, herramientas, proveedores, cola de recibos y entrega de archivos existentes.
- Continuidad de memoria por workspace para tareas generales, no sólo Coding; referencias a requisitos, decisiones, correcciones, pendientes y artefactos comprobables.
- Recuperación, límites y observabilidad necesarios para ese recorrido; luego supresión proactiva, estado derivado y fatiga sólo donde haya brecha demostrada.

### Fuera de alcance

- Port completo, paridad integral con los dos repositorios o checklist de todas sus features.
- Nuevo chat, selector de conversaciones, tablero web, sistema de bots paralelo, rediseño de drawer o cambios cosméticos.
- Electron, React web, Node 24, Bun, Hono, Postgres, Docker, gateway remoto, CUA, onboarding de conectores, cuentas o hosting nuevos.
- Herencia de privilegios del Chief, auto-grants, ampliación de allowlists o envío autónomo a terceros por aceptar una sugerencia.
- Sustituir Engram por archivos Markdown, copiar datos privados entre workspaces, sincronización nueva, mantenimiento integral P5/P6 o importación legacy.
- Implementar ahora, modificar versión/build/tests/producción, instalar o ejecutar los repositorios de referencia.

La selección es suficiente cuando el usuario puede terminar el recorrido útil con evidencia y sin regresiones. Si una propuesta no desbloquea ese recorrido ni corrige un riesgo concreto de sus rutas, se registra como diferida; no bloquea la entrega vertical.

## 5 Contratos nativos propuestos

Estos contratos son objetivos de implementación sujetos a autorización, no afirmaciones sobre código ya creado. Los nombres conceptuales pueden mapearse a tipos existentes; no obligan a crear clases, tablas o herramientas duplicadas.

### C1 Identidad y propiedad

La identidad durable une `workspaceId`, assignment padre e hijo, misión, bot, ciclo, request original, evento causal y revisión. `workspaceId` permanece entre misiones; `cycleId` no es memoria de proyecto ni permiso eterno. Un root referencia su propia identidad; un hijo comparte el root, pertenece al workspace autorizado y no puede apuntar a sí mismo, a un descendiente ni a otro owner. La pertenencia se resuelve desde el registro, nunca desde un argumento inventado por el modelo.

La espera tiene además un `waitId` lógico y una revisión propios, distintos del ciclo productor y del ciclo consumidor. Conserva IDs/digests de resultados ya consumidos y la clave de continuación del episodio aunque cambien los ciclos mediante el vínculo autorizado de C4. Cambiar de misión no hereda una espera.

Las operaciones usan clave estable y fingerprint del payload. Repetir exactamente una operación devuelve su recibo; misma clave con datos distintos es conflicto. Un nuevo intento no puede inventar una clave para eludir un STARTED incierto. Se preserva procedencia de usuario frente a texto de bot y evento externo.

### C2 Estado lógico separado del efecto

Se propone una vista lógica `en_cola`, `ejecutando`, `necesita_dato`, `espera_hijos`, `continuacion_pendiente`, `completado`, `fallido`, `cancelado`, `interrumpido_incierto`. Se mapea a los estados y evidencia nativos; **no sobrescribe** PREPARED/STARTED/BOUND/UNCERTAIN ni los recibos PREPARED/STARTED/ACCEPTED_LOCAL de dispatch.

El motivo debe distinguir al menos `done`, `needs_input`, `denied`, `expired`, `stalled`, `provider_error`, `budget`, `stopped`, `interrupted` y `unknown_effect`. `duplicate` es un resultado de admisión/replay, no éxito del trabajo. `needs_input` es bloqueo, no fallo de proveedor. `expired` nunca se presenta como completado. DONE del worker sigue siendo una afirmación del worker; no equivale a verificación del resultado o del archivo.

La proyección obtiene estado de fuentes reales: control vigente y fences; incertidumbre de efecto; pregunta pendiente; dependencia pendiente; continuación admitida; resultado terminal. P0 debe resolver y probar precedencia cuando coexisten, especialmente cancelación con resultado tardío. Estado histórico, estado ejecutable y estado visible se muestran por separado donde sea necesario; no borrar evidencia para simplificar una etiqueta.

### C3 Pregunta respuesta y continuación

Una pregunta durable contiene referencia exacta, hash, owner, assignment, ciclo de emisión, revisión, motivo y estado de resolución. El cuerpo se recupera por las lecturas autorizadas existentes; el aviso transporta referencias, no cuerpos privados. La respuesta lleva `questionId`, identidad del input de Central, payload digest y la revisión observada. Una respuesta ambigua a dos preguntas no se aplica a ninguna hasta que Central correlacione o pregunte al usuario.

**Ruta mínima propuesta:** mantener intacto el reply actual para capacidad viva y reutilizar `WorkspaceConversationInputStore.appendSupervisorIntent` y `deliverPendingSupervisorIntentsAfterExplicitResume`, que ya conservan inputs de supervisor y sólo entregan los pendientes al worker interrumpido después de RESUME/CORRECT nativo. No crear otro outbox. La extensión es la correlación durable exacta con pregunta/espera y su transición de ciclo, no la mera persistencia de un mensaje.

Cuando el proceso se perdió, la intención queda ligada a la pregunta original sin despacharse a su capacidad extinguida. Reanudar, autorizado y revalidado, crea token y ciclo nuevos; el vínculo de C4 identifica exactamente cuáles y permite incorporar el input una vez. No se hace pasar el ciclo nuevo por el emisor antiguo ni se reutiliza un `CrewInputBinding` con admissionCycleId incompatible. Fuente original, admisión del ciclo nuevo e incorporación son evidencias separadas. Se requiere punto seguro de checkpoint y prueba de efectos pendientes resueltos; si falta cualquiera, permanece interrumpida/incierta.

Una respuesta no es permiso de conector, acceso a otro namespace ni aprobación de un efecto. `ACCEPTED_LOCAL` significa aceptación de buzón; un recibo de incorporación prueba un checkpoint concreto, no entendimiento o resolución. La resolución funcional se registra sólo con la transición/evento que realmente la establece. Cancelación, expiración, revocación o cambio de revisión no amparado por el vínculo causal confirmado invalidan dispatch pendiente; la respuesta tardía se conserva como evidencia rechazada, sin reabrir la tarea. RESUME puede avanzar assignmentRevision sin sustituir la instrucción de supervisor: sólo se acepta el avance exacto del control enlazado. Un CORRECT posterior invalida instrucciones supervisor anteriores según su admittedAssignmentRevision, como ya hace el outbox; no se arrastran automáticamente a la continuación corregida. Una nueva pregunta, espera o instrucción tras esa corrección requiere su intención vigente.

### C4 Espera y árbol acotado

Una espera declara hijos concretos, condición ANY/ALL, resultados consumidos y evento que permite continuar. Para la primera entrega, Central espera a un hijo. La siguiente unidad permite padre worker con hijos autorizados. Un resultado tiene identidad estable, outcome, hash y referencias; el padre consume cada resultado una vez. Dos hijos que terminan simultáneamente no crean dos continuaciones del mismo episodio. Sólo satisface una dependencia el ciclo productor exacto actualmente esperado; cualquier ciclo distinto se rechaza, salvo su sustitución previa por la transición autorizada siguiente. Otro workspace o misión nunca satisface esa espera.

**Cambio de ciclo por Reanudar:** `CrewManager.resumeWithUserMessage` invalida coordinación y crea token/ciclo nuevos. El plan exige un vínculo durable de continuación, no aceptar «el último ciclo del mismo bot». Antes del dispatch se registra la intención del control nativo con `waitId`, revisión esperada, child assignment/bot/misión/workspace, ciclo anterior, checkpoint de origen y control ID. Al confirmar qué ciclo produjo ese control se registra el sucesor exacto, la revisión admitida y evidencia de checkpoint del sucesor. Bajo CAS de la revisión de espera y el fence actual de ownership/control, se sustituye únicamente el ciclo esperado de esa dependencia. Es un nuevo evento/revisión de la misma espera lógica; no cambia el ciclo, ID ni digest de resultados históricos.

Para confirmar ese vínculo deben seguir coincidiendo owner, assignment, misión, bot y el control RESUME exacto; no puede existir cancelación, expiración, corrección supersedente, control nuevo o generación revocada que invalide su autoridad. El punto de origen debe ser seguro y no contener efecto incierto que se pretenda repetir. Un control STARTED/UNCERTAIN sin prueba suficiente del sucesor no confirma el vínculo ni se redispatcha. Si el resultado nuevo llega antes de confirmarlo, queda como evidencia sin satisfacer la espera. Reconciliar sólo puede completar metadatos a partir de pruebas durables exactas de una reanudación ya autorizada; nunca genera un ciclo, permiso ni efecto.

Después de sustituir el ciclo, resultados tardíos del anterior siguen siendo legibles como historia, pero no satisfacen la dependencia actual. Tampoco sirven un follow-up independiente del mismo bot, otro RESUME, nueva misión o un sucesor inferido sólo por timestamps. Repetir la misma transición devuelve el mismo recibo; mismo control con sucesor distinto es conflicto. Si el resultado original ya fue consumido, su consumo permanece y no se vuelve a abrir para aceptar otro resultado.

**Reinicio del padre:** conservar `waitId`, revisión, conjunto de resultados consumidos y clave del episodio de continuación. Si el padre es un worker, su propio Reanudar necesita un vínculo equivalente para el ciclo consumidor; reanudar un hijo no autoriza al padre. Si es Central, el wake es la referencia nativa ya existente y el siguiente turno se admite por su ruta actual. El consumidor nuevo no vuelve a consumir resultados ni publica otro wake por verlos de nuevo. Si el dispatch de continuación quedó STARTED sin recibo suficiente, se mantiene incierto sin replay, aunque el input/resultado sea durable.

Detener al padre y cancelar su subárbol son operaciones distintas. D2 debe fijar antes de habilitar P2 qué ocurre con hijos ya admitidos, tanto en cola como ejecutando, y exigir una intención explícita si se ofrece cancelación de subárbol. No se hereda la cascada upstream. Un stop del padre cierra sus futuras admisiones y wakes; resultados retenidos de hijos siguen visibles bajo permisos actuales. Una cancelación de subárbol captura la lista y revisiones/ciclos poseídos, revalida cada destino y no alcanza ciclos posteriores o hijos ajenos.

Un hijo detenido o expirado también resuelve su dependencia con un motivo de no éxito; no queda oculto bajo un spinner eterno. El padre decide el siguiente paso permitido con esa evidencia, no inventa éxito. En ANY, los hijos restantes no se cancelan ni se vuelven invisibles automáticamente: el contrato aprobado debe conservarlos o solicitar una cancelación explícita con ownership exacto.

Antes de delegar se validan grafo sin ciclos, profundidad, hijos activos, turnos/llamadas admitidas y presupuesto del request. Reservar y comprobar se realiza bajo el mismo arbitraje durable; contar antes y crear después permite sobrepasar límites concurrentemente. Se reutilizan límites nativos existentes y sólo se añade lo no cubierto. Agotar presupuesto detiene nuevas delegaciones y produce un estado explicable; no elimina trabajo ni concede más presupuesto. D2 fija valores y alcance antes de activar la unidad.

`consult` sólo dispone de herramientas realmente de lectura en el namespace autorizado; no delega, escribe memoria ni dispara efectos por prompt. `notify` es un aviso interno unidireccional y no crea una espera de respuesta; no habilita mensajería externa. `work` conserva el conjunto admitido para su misión, no el conjunto del padre.

### C5 Persistencia sin un segundo motor de estado

Puntos preferidos de extensión: `WorkspaceRegistry/WorkspaceRecords` para identidad y relación; `LocalRunStore` para evidencia y recibos; `CrewManager`, `CrewCheckpointCoordinator` y `CrewCheckpointStore` para ejecución e incorporación; `WorkspaceCoordinator` para admisión y controles. `WorkspaceResultDelivery`, `WorkspaceCoordinationDelivery` y la entrega de incorporación mantienen sus responsabilidades. La memoria semántica continúa en Room; no se usa como cola ni se infiere ejecución de un recuerdo.

P0 elige la representación mínima en esos almacenes tras comprobar sus garantías: relación/espera versionada en el registro o registro de evento en el ledger existente, **una sola autoridad para cada dato nuevo**, no dos estados mutablemente sincronizados. Entregable obligatorio: tabla campo→autoridad→writer→lector→fence→recuperación. Se rechaza crear otra base de estado o un framework antes de demostrar que esos puntos no pueden satisfacer el contrato.

No se presupone una transacción atómica entre SQLite, JSONL y archivos de checkpoint. Cada frontera debe tener intención durable, identidad estable, confirmación/recibo y reconciliación idempotente. La caída entre cualquiera de sus pasos se prueba. La reconciliación puede completar referencias/recibos ya demostrados; **no reenvía efectos, no llama al modelo y no restaura autoridad histórica**. Corrupción, disco lleno o checkpoint ausente se hacen explícitos, no se tratan como cola vacía.

### C6 Permisos y cancelación

La autorización efectiva es la intersección de identidad actual, misión admitida, herramientas permitidas, política actual, ownership, generación y token vigente. ASK/DENY/ALLOW se comprueban en cada frontera protegida. No se porta `assignmentLevel` de OpenMausBot que eleva el nivel del hijo por linaje Chief. Guardar un permiso antiguo, root o delegación no lo vuelve vigente.

Se preservan STOP, cancelación y limpieza de recursos poseídos; un stop del ciclo anterior no afecta al nuevo ciclo autorizado. OFF→ON no revive capabilities ni escrituras preparadas antes de OFF. Revocación entre preparar y despachar bloquea el siguiente efecto. El recibo distingue «no iniciado probado», «aceptado localmente», «efecto confirmado» e «incierto»; un error de red después del envío no se rebautiza como «no ejecutado».

Contenido de bots, conectores y memoria es dato no confiable, no instrucción para alterar políticas. Las proyecciones no filtran cuerpos de otro workspace, memoria personal ni historial protegido. Un grant de lectura no autoriza delegar, responder ni entregar archivos.

### C7 Workspace y Engram a largo plazo

La ruta de tarea que se reutiliza es `WorkspaceMemoryTools` → `ChatDatabaseBridge.workspaceMemoryOperation` → `WorkspaceMemoryOperations.admit/execute` → `MemoryNamespace.workspace`, con el Crew witness vigente y su propia identidad/recibo scoped. `RuntimeMemoryIdentity` es un helper de proyecciones **sólo de Central**, cuyo selector fija `jarvys-central`; se conserva únicamente donde esa ruta ya aplica. No ampliar ese selector ni usar sus recibos como identidad de una tarea. Central descubre el workspace mediante el registro/inventario autorizado y consulta sus decisiones; el agente de tarea sólo recibe su proyecto scoped y su binding actual. Un agente read-only no obtiene save/update. No se propaga memoria personal/global ni otro workspace por compartir un root o un nombre de proyecto.

En los puntos útiles del recorrido se guardan, con procedencia e IDs, requisitos, decisiones confirmadas, correcciones, estado pendiente y referencias de artefactos. Los recuerdos no guardados no tienen garantía de sobrevivir a compactación. La entrega de un artefacto usa los lectores y verificación de hash existentes; una referencia de memoria no prueba que los bytes sigan disponibles. Si faltan o cambiaron, Central lo comunica y no afirma haber entregado un archivo nuevo.

El ensayo largo es: guardar decisiones y referencia verificada, compactar más de una vez, cerrar/reabrir stores, avanzar reloj de prueba por meses, empezar una misión nueva autorizada del mismo workspace y recuperar los datos sin transcript anterior. Aislar simultáneamente un workspace ajeno y memoria personal. El reloj simulado no demuestra meses reales ni durabilidad física; el informe lo debe declarar.

## 6 Transiciones y recuperación exigidas

Todas estas transiciones se verifican con las rutas de admisión reales y proveedor guionado. No describen código ya implementado.

| Evento | Transición o resultado permitido | Evidencia y caso negativo obligatorio |
|---|---|---|
| Delegación nueva válida | Intención→admisión→inicio→binding; presupuesto reservado una vez | Revisión/clave/owner exactos. Duplicado idéntico devuelve recibo; payload distinto falla. |
| Hijo pide un dato | Ejecutando→necesita_dato en punto seguro durable; Central recibe referencia | Pregunta persistida antes de anunciarla. Fallo de persistencia no produce una pregunta «respondible» ficticia. |
| Respuesta válida a capacidad viva | Intención exacta→buzón→incorporación en checkpoint→continuación permitida | No basta ACCEPTED_LOCAL para afirmar consumo; doble entrega no duplica incorporación. |
| Respuesta tras muerte del proceso | Outbox existente retenido; Reanudar nativo y vínculo exacto anterior→sucesor confirmado permiten nueva admisión/incorporación | No reconstruir `coordinationRequests` ni reutilizar binding incompatible. Sin punto seguro o con efecto incierto, no ejecutar. |
| Padre espera resultado | Espera con IDs/ANY/ALL→resultado del ciclo productor esperado→continuación pendiente; sólo C4 puede sustituir ese ciclo | Resultado tardío del anterior no satisface una dependencia sustituida. Preservar consumo y clave de continuación tras restart del padre; no replay de dispatch incierto. |
| Reinicio antes de dispatch probado | Revalidar intención PREPARED y políticas antes de iniciarla | Sólo el preflight demostrablemente sin efectos puede justificar «no iniciado». |
| Reinicio después de STARTED sin resultado | Interrumpido/incierto; conservar evidencia y explicar qué falta | Ni falta de proceso, ni vencimiento del lease, ni host «sin efectos conocidos» autorizan replay. |
| Caída después de commit y antes de ACK | Reconciliar el mismo recibo; no repetir mutación/entrega | Inyectar caída a cada lado de la frontera; verificar conteo de efectos, no sólo mensaje final. |
| Cancelación/STOP | Detener nueva admisión, cancelar sólo recursos del ciclo poseído; mostrar pendiente hasta terminación | Respuesta/resultados tardíos no reviven tarea ni disparan continuación; conservar historia confirmada. |
| Revocación o Memory OFF→ON | Bloquear la siguiente frontera y capabilities de generación anterior | No-op protegido no debe leer ni filtrar cuerpo antes de rechazar. |
| Denegación/expiración | Motivo denied/expired, nunca done; informar al padre sin nuevo intento implícito | Respuesta a pregunta vencida es stale; autorización nueva requiere nueva intención explícita. |
| Stall/budget | Suspender nuevas admisiones, guardar motivo y siguiente acción permitida | Actividad real, reloj y progreso son distintos; no usar spinner o tokens como prueba de avance. |
| 429, proveedor no disponible o red | Espera/reintento sólo si la operación es segura según evidencia y presupuesto; si no, provider_error o uncertain | Backoff cancelable, límite actual del proveedor, caída después de envío, permiso revocado durante espera. |
| Pérdida de claim/lease | Worker anterior pierde derecho a completar, renovar o despachar | Dueño y generación exactos. Vencer lease no demuestra que el efecto externo no ocurrió. |
| Archivo cambiado, store corrupto o disco lleno | Resultado explícito de fallo/incertidumbre; no inventar referencias ni éxito | Cero borrado automático, cero reconstrucción de un store faltante para fabricar comprobante. |

## 7 Fases de ejecución propuestas

El orden es **P0 → P1 → P2 → P3 → P4**, con entrega vertical al cerrar P1 y su gate de entrega, sin esperar todo P2/P4. P3 se aplica también como gate de seguridad a cada slice anterior: no se posponen sus fallos críticos hasta el final. Cada fase requiere una autorización de implementación que hoy no existe. No se asignan fechas o duraciones inventadas.

### P0 Congelar contratos y autorizar la primera unidad

**Objetivo:** eliminar incertidumbres de integración antes de editar producción, sin abrir un proyecto de mantenimiento general.

- Dependencias: PASS documental independiente, decisión D1 y autorización del usuario para P1.
- Inventariar los métodos exactos de J1–J9 afectados por pregunta, wait, checkpoint, control y efecto; confirmar o corregir M01–D06.
- Entregar mapa campo→autoridad→writer→lector→fence→recuperación, tabla de transiciones y vectores de prueba del primer vertical. Elegir extensión mínima y resolver D3 si cambia formato persistido.
- Definir qué perfiles de tarea tienen checkpoint apto. Los no aptos deben fallar de forma explícita, sin anunciar reanudabilidad universal.
- No requiere completar reparación global, sync, export Markdown ni todo Engram. Sí exige que cualquier fallo real de memoria/ownership en el recorrido elegido se cierre antes de usarlo.
- **Aceptación:** reviewer puede seguir un request hasta cada registro/recibo sin saltos de autoridad o atomicidad. Ninguna API existente queda duplicada sin justificación. Decisiones bloqueantes resueltas, cohorte de pruebas seleccionada por dependencias.
- **Stop/rollback:** si falta autorización o no hay punto seguro reanudable, parar la implementación y devolver ese bloqueo concreto. No editar storage ni abrir capacidades para «hacerlo pasar». En esta fase no hay cambios de producción que revertir.

### P1 Primera entrega útil de Central y un hijo

**Objetivo:** Central delega, recibe pregunta o espera un resultado, incorpora la respuesta correcta y retoma con continuidad y sin repetir efectos.

- Dependencias: P0 y sólo los cambios mínimos C1/C2/C3/C5/C6/C7 necesarios; no todo el árbol ni proactividad nueva.
- Reusar `CentralWorkspaceTool`, `WorkspaceCoordinator`, `CrewManager`, outbox de supervisor, deliveries y controles. Extender sólo correlación de respuesta, vínculo durable de cambio de ciclo, continuación y outcomes donde la matriz demuestre brecha.
- Dos recorridos de aceptación: (A) hijo pregunta→Central pide/recibe dato→respuesta correlacionada→checkpoint→resultado; (B) Central espera→hijo termina→referencia de resultado→Central consulta/sintetiza. Interrumpir ambos en fronteras persistentes, cerrar/reabrir y usar Reanudar explícito para el worker interrumpido. Probar que el resultado de ese ciclo nuevo satisface sólo la espera enlazada, que uno antiguo o no enlazado no la satisface y que reiniciar el consumidor no repite consumos ni continuaciones.
- Ejemplo generalista: preparar un itinerario y pedir una fecha faltante; ejemplo Coding puede reutilizar referencia de artefacto, pero no define el único perfil soportado.
- Mantener UI y navegación. La disponibilidad/pregunta aparece en Central y metadatos/controles existentes del workspace; no se crea una conversación de usuario del hijo.
- **Entregables:** slice funcional nativo, pruebas de aceptación, matriz actualizada, checkpoint y candidato entregable acotado.
- **Aceptación:** T01–T09 y T11–T13 aplicables pasan en el mismo SHA Full/Play; se inspeccionan registros, prompts e identidad de artefactos, no sólo textos de respuesta. Una respuesta falsa/cross-owner no satisface la espera. Conteo de efectos externos simulados no aumenta por reiniciar, reintentar o duplicar el evento.
- **Stop/rollback:** un replay de efecto incierto, fuga de memoria, reanudación sin control vigente o regresión del menú bloquea entrega. Deshabilitar sólo la nueva admisión y conservar registros; no borrar workspaces ni reescribir historial. Volver al checkpoint anterior sólo con compatibilidad de datos comprobada según D3.

### P2 Dependencias entre bots y límites comprobables

**Objetivo:** padre worker con hijos acotados, resultado tipado y continuación durable; no añadir equipos ilimitados.

- Dependencias: P1 aceptado y políticas D2 resueltas, incluida la disposición de hijos en cola/ejecutando al detener al padre frente a cancelar explícitamente un subárbol. Reusar ANY/ALL y buzones; agregar sólo relación durable, consumo y reserva faltantes.
- Integrar work/consult/notify únicamente si necesitan capacidades distintas en este recorrido. Probar cada restricción en dispatch.
- Entregar grafo sin ciclos, motivos terminales, resultado/aviso idempotente, presupuesto reservado bajo concurrencia y proyección consistente. No permitir saltos cross-workspace ni herencia Chief.
- **Aceptación:** T04–T08, T10 y T12; padre esperando varios hijos con éxito/fallo/pregunta/cancelación, resultados reordenados/duplicados, restart y claim obsoleto. Probar la política elegida de stop de padre y subárbol con hijos en cola/ejecutando, outcomes retenidos visibles y ningún wake del padre detenido; una cancelación no alcanza ciclos posteriores. Presupuesto usado y reservado coincide con admisiones registradas; sin wake duplicado ni dependencia huérfana silenciosa.
- **Stop/rollback:** una dependencia sin dueño, deadlock no explicable, sobrepaso concurrente o escape de consult bloquea la unidad. Rechazar nuevas delegaciones anidadas y mantener consulta/cancelación autorizada de las existentes; nunca convertir fallos en éxito para cerrar el árbol.

### P3 Robustez Android y entrega por slice

**Objetivo:** establecer qué continuidad es real en el entorno de entrega y qué sigue sin verificar. Sus controles aplican antes de entregar P1/P2, no después.

- Dependencias: candidato exacto del slice y sus tests de host; entorno de build y dispositivo autorizado cuando corresponda.
- Reusar servicio foreground, WorkManager y controles del producto. No daemon siempre vivo, alarma exacta nueva, wake lock indefinido ni promesa de horario exacto.
- Probar T09/T11/T12/T13 en host y, para afirmar continuidad física, muerte de proceso, force-stop, reabrir, reinicio, ahorro de batería/Doze, desconexión/reconexión, proveedor no disponible, límites de rate y notificación denegada en dispositivo.
- WorkManager/servicio pueden diferir o detener trabajo; la UI debe distinguir pendiente, suspendido, necesita acción e incierto. No ejecutar todos los pendientes en ráfaga al recuperar red. Separar caducidad de pregunta, timeout de proveedor y programación de Android.
- **Entregables:** candidato reproducible, resultado por prueba/entorno, APK verificado y entrega autorizada con hash. Medición de llamadas, wakes, tiempo activo y consumo observado; no SLA ni cuota de batería sin baseline.
- **Aceptación:** sin regresión del único chat/drawer/controles; compatibilidad y firma verificadas; comportamiento de interrupción coherente con los journals. La validación pendiente de v97 en el teléfono es un gate de confianza de la base visual antes de afirmar que un nuevo APK conserva su aceptación física; no bloquea redactar el plan ni toda prueba offline.
- **Stop/rollback:** dispositivo no disponible permite informar «host PASS, físico no verificado», no PASS físico. Regresión visual o recuperación insegura detiene nuevo rollout. No solicitar desinstalación/borrado como reparación automática. Conservar APK/checkpoint previo y datos; ninguna reversión binaria se presume compatible con un schema nuevo.

### P4 Mejoras selectivas de OpenDuet después del núcleo

**Objetivo:** reducir interrupciones repetitivas y hacer que el estado de tarea represente la realidad, sin sustituir la proactividad nativa.

- Dependencias: recorrido P1/P2 estable y evaluado; D4 para políticas nuevas. Reinspeccionar D01–D05 y omitir lo ya cubierto.
- Primera unidad: sugerencia identificada por evidencia, supresión durable de repetidas/ocultadas y tránsito explícito de sugerencia a tarea iniciada por el usuario. La lectura proactiva sigue limitada a la allowlist actual; un check-in no escribe conectores ni delega.
- Segunda unidad sólo si hace falta: fatiga de rutina con estado visible y reactivación explícita; separar fallos consecutivos de disparos de los reintentos de una ejecución. Cero auto-reactivación por nueva conexión o reinicio.
- Reusar proyecciones para mostrar pregunta/aprobación pendiente, ejecución admitida, resultado o error. No copiar el `status` mutable de otra capa. Si las dos unidades son independientes, entregar la primera sin esperar la segunda.
- **Aceptación:** T14/T15 más regresiones de proactividad/seguridad afectadas; ocultar sobrevive a reapertura y no reaparece por cambio de wording; aceptar dos veces no crea dos tareas; lectura/background no envía mensajes ni inicia children; rechazo no se transforma en permiso. Reloj controlado y causas de error prueban la política elegida, sin adoptar automáticamente 14 días o diez fallos.
- **Stop/rollback:** sugerencia sin evidencia, aviso duplicado, costo sin límite, task iniciada sin aceptación o permiso ampliado bloquean la unidad. Desactivar la mejora nueva conservando preferencias/historia; no apagar silenciosamente tareas del usuario ni borrar evidencia.

## 8 Plan de validación y observables

**Estado de toda esta tabla: PLANIFICADO.** Las clases existentes son puntos de reutilización, no prueba de que los escenarios nuevos ya pasaron. Test fixtures usan datos sintéticos y proveedor guionado; no exponen conversaciones privadas a upstream ni a proveedores.

| ID | Escenario y observación exigida | Base a extender o contrastar |
|---|---|---|
| T01 | Central→delegar→pregunta→respuesta→incorporación→resultado; cada ID/hash/owner trazable y un único efecto simulado | `WorkspaceCoordinatorIntegrationTest`, `CentralWorkspaceCoordinationToolTest`, `WorkspaceCoordinationDeliveryTest` |
| T02 | Matar/reabrir entre intención, dispatch, buzón, checkpoint y ACK; hijo reanudado obtiene ciclo nuevo y vínculo exacto que satisface sólo la espera prevista. Vínculo faltante/conflictivo y STARTED incierto no se reenvían | `CrewManagerRecoveryTest`, `CrewCheckpointDurabilityTest`, `WorkspaceInputIncorporationDeliveryTest` |
| T03 | Respuesta ambigua, a otro ciclo, tardía, duplicada, payload conflict y expiración; cero incorporación incorrecta | `CrewCoordinationEvidenceTest`, `CrewCoordinationCheckpointTest`, `CrewInputCheckpointManagerTest` |
| T04 | Padre ANY/ALL recibe hijos éxito/fallo/pregunta, reordenación/duplicados; rechazar resultado de ciclo anterior sustituido, follow-up no enlazado y nueva misión. Reiniciar/reanudar al padre conserva consumo y clave: una continuación por episodio, ninguna si su dispatch es incierto | `CrewManager.waitFor`, `CrewResultEvidenceTest`, `WorkspaceResultDeliveryTest`; nuevo vector de árbol |
| T05 | STOP/revocación concurrente, ciclo antiguo y OFF→ON; distinguir stop del padre/subárbol, probar hijos en cola/ejecutando, outcomes visibles, cero wake del padre detenido y cero cancelación de ciclos posteriores | `WorkspaceControlStopOwnershipTest`, `CrewWorkspaceCycleOwnershipTest`, `WorkspaceMemoryAuthorityTest` |
| T06 | Hijos intentan permisos del Chief, consult escribe/delega y notify espera; rechazo en dispatcher sin efecto | `CrewWorkspaceMissionAdmissionTest`, `CoreAgentLoopToolAdmissionTest`, `ActionPolicyFoundationTest` |
| T07 | Casos positivos y negativos de memoria propia, personal/global y workspace ajeno; mismo nombre no confiere acceso | `WorkspaceMemoryAuthorityTest`, `WorkspaceMemoryOperationsTest` |
| T08 | Dos compactaciones, reopen de store, tiempo simulado por meses y misión nueva; recuperar decisión y referencia correcta sin transcript viejo | `WorkspaceMemoryContinuityTest`, `CrewConversationCompactionRecoveryTest` |
| T09 | Artefacto inspeccionado y entregado con hash; bytes cambiados, ausentes, STOP y retry tras ACK perdido | `WorkspaceEffectReceiptTest`, `WorkspaceResultDeliveryTest`, contrato existente WORKSPACE_DELIVER |
| T10 | Crear hijos concurrentes al borde del presupuesto, ciclo A→B→A, depth y fan-out, claim viejo completa tarde | Nuevos vectores de contratos mínimos; contraste con U3/U7 sin adoptar sus parámetros |
| T11 | No hay proveedor/red, 429, cancelación durante backoff, pérdida de proceso/claim después de enviar | `CrewProviderUnavailableRecoveryTest`, `CrewRecoveryLifecycleTest`, controles de proveedor existentes |
| T12 | Disco lleno, corrupción, registro/checkpoint ausente y caída entre stores; no éxito inventado ni creación destructiva | `WorkspaceRegistryTest`, `WorkspaceLedgerIndependenceTest`, `CrewCheckpointRecoveryTest` |
| T13 | Abrir vistas no crea trabajo; único chat, menú, navegación y controles intactos; luego flujo físico Android | `MainActivityWorkspaceRoutingTest`, `WorkspaceRestorationReadOnlyTest`, `RestoredWorkspaceComposerTest` |
| T14 | Mismo evento/sugerencia repetido, ocultación, reinicio, aceptación doble y revocación previa al inicio | `ProactiveAgentProcessorTest`, `ProactivePolicyQueueTest`, `ProactiveActionReceiverTest` |
| T15 | Fallo de un disparo, retry seguro, éxito que rompe racha, fatiga, notificación única y reactivación explícita | `ProactiveWorkManagerTest` y tests nuevos sólo para el gap confirmado |

### Cómo medir sin inventar cuotas

- **Corrección:** contar intents distintos, dispatches, efectos simulados, incorporaciones, resultados consumidos y continuaciones. Deben coincidir con el contrato; ningún duplicado, fuga o grant indebido es tolerable.
- **Recuperación:** clasificar cada estado recuperado como reconciliado, esperando acción, terminal o incierto. Todo registro del corpus tiene causa y siguiente paso; no «desaparece» para mejorar una métrica.
- **Utilidad:** medir si la tarea concluyó, si Central puede explicar qué falta y si recupera la decisión guardada de la misión previa. Un contador de tests no sustituye ese recorrido.
- **Carga:** registrar llamadas al modelo, herramientas, waits, wakes, tiempo activo, bytes persistidos y efecto de red/batería por escenario. Comparar con baseline del mismo dispositivo/proveedor antes de fijar umbrales; no prometer porcentajes o latencias sin medición.
- **Ruido proactivo:** contar eventos, sugerencias únicas, avisos emitidos, suprimidos y tareas aceptadas. Cada aviso necesita evidencia; cada tarea, intención de inicio válida. No usar una tasa alta de avisos como éxito.

Seleccionar una cohorte por dependencias del diff real y ejecutar Full/Play sobre un único candidato. Conservar fallos, omisiones e intentos anteriores; no sumar corridas de candidatos diferentes. Si se retiene evidencia histórica por igualdad exacta de inputs, documentar esa igualdad y no llamarla nueva ejecución. Test no ejecutado, bloqueado y fallido son categorías distintas.

## 9 Licencias y arquitectura de las referencias

**OpenMausBot:** [LICENSE Apache 2.0](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/LICENSE) cubre el core; [enterprise/LICENSE](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/enterprise/LICENSE) excluye expresamente `enterprise/`, con condiciones separadas y sin redistribución general. No se copia esa carpeta ni sus presupuestos comerciales, UI o hooks de licencia. Los candidatos de este plan son `server/runtime/{types,runtime,tree,invariants,capabilities}.ts` y sus tests, fuera de `enterprise/`. Los límites del core se distinguen de las features comerciales de presupuesto. Confirmar licencia/cabeceras de cada archivo realmente reutilizado antes de copiar texto/código; conservar licencia, avisos aplicables, procedencia y declaración de modificaciones.

Su [package.json](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/package.json) pide Node >=24 y contiene Electron/React. La portabilidad es de algoritmos, invariantes y contratos a Kotlin/Java; no consiste en embutir la app desktop en Android.

**OpenDuet:** [LICENSE MIT](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/LICENSE), con aviso de copyright/licencia que debe conservarse en copias sustanciales. La licencia del repositorio no sustituye la revisión de dependencias/assets concretos. Su [servidor](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/package.json) usa Bun, Hono, Postgres y CopilotKit. `run-turn.ts` documenta la copia manual de interfaces internas de CopilotKit: es una frontera frágil que se **excluye como implementación**. Se aprovechan sus pruebas de admisión, leases y no duplicación, no sus clases privadas ni su infraestructura.

Para cada copia o adaptación futura registrar ruta origen, commit, licencia, ruta destino, qué se cambió y prueba que cubre el comportamiento. Una reimplementación desde contrato debe decirlo; no afirmar que portar nombres o prompts equivale a portar semántica.

## 10 Decisiones pendientes y gates

Los responsables son roles propuestos; no implican una aprobación o compromiso ya recibido.

| ID | Decisión pendiente | Responsable | Impacto y condición para avanzar |
|---|---|---|---|
| D1 | Mantener Engram completo antes de bots, o autorizar expresamente el primer vertical una vez aceptado el núcleo de memoria y resuelta la base visual | Usuario | Bloquea implementación, no este roadmap. El orden OpenMausBot→OpenDuet es recomendación técnica, no derogación de la instrucción histórica. |
| D2 | Límites nativos por request/árbol, profundidad, fan-out, llamadas/costo, ANY y stop del padre frente a cancelación explícita de subárbol | Implementador propone con evidencia; usuario decide límites de costo/autonomía | Bloquea P2/activación. Fijar disposición de hijos ya admitidos en cola/ejecutando y límites vigentes; política/valores nuevos sin aprobación no se activan. No copiar cascadas/cuotas upstream ni fijar presupuesto ilimitado. |
| D3 | Representación mínima y compatibilidad si se extiende un formato persistido de la instalación nueva | Implementador y revisor; usuario si requiere cambiar alcance o manejo de datos | Bloquea writes de formato nuevo. El guard fresh-only actual no autoriza destruir datos nuevos. Plan de lectura/upgrade/rollback probado antes de entrega; soporte de la app legacy sigue excluido. |
| D4 | Ventana/identidad de supresión, quiet periods y política de fatiga/reactivación | Usuario, con propuesta basada en pruebas de producto | Bloquea políticas nuevas de P4, no P1/P2. Los 14 días y diez fallos de upstream son referencias, no defaults aprobados. |
| D5 | Dispositivo, aceptación física de v97 y muestra no sensible para eventual proveedor real | Usuario y responsable de validación | Bloquea afirmaciones físicas/live, no pruebas sintéticas. Registrar resultado real antes de afirmar continuidad Android o elección autónoma del modelo. |

### Gate documental y revisión independiente

1. Congelar revisión del roadmap y baseline Jarvys. Entregar al evaluador limpio este archivo, sus hashes, pins de referencias y las restricciones del usuario; no una conclusión preescrita.
2. El evaluador no participó en redacción. Inspecciona fuentes read-only y devuelve **PASS o FAIL documental**, con severidad, ubicación, evidencia y corrección concreta. Debe comprobar alcance, fuentes, brechas, estados, permisos, memoria, Android, licencias, dependencias y aceptación útil.
3. Ante FAIL, corregir documentación sin implementar; congelar otra revisión/hash y volver a evaluar. No reducir criterios ni declarar PASS por número de rondas. Hallazgos nuevos se mantienen visibles.
4. Sólo un dictamen independiente sobre los bytes finales cierra el gate. Registrar hash/revisión evaluada, identidad del evaluador, fecha, veredicto y límites en el checkpoint de revisión. El autor no modifica un FAIL a PASS.
5. PASS documental **no** habilita P1. Se presenta la decisión D1 y alcance mínimo para autorización; tampoco se promete cerrar upstream completo.

### Gate de checkpoint respaldo y entrega futura

Cada slice autorizado conserva fuente exacta, diff, resultados, límites y checksum del artefacto. La revisión independiente comprueba producción/tests afectados, permisos y recorrido, sin mezclar el PASS del plan con el de código. La documentación pública resume evidencia sin logs privados, credenciales o cuerpos de conversación.

El respaldo autorizado a `Hackerprod/Jarvys` debe usar un manifiesto explícito de archivos permitidos, revisar diff/secretos, comprobar HEAD remoto y publicar sin sobrescribir historial. Ante respuesta incierta se verifica primero el remoto antes de reintentar; no force-push, no `git add -A` sobre evidencia privada. El publicador verifica el SHA remoto antes de afirmar «respaldado». Este documento **no realiza esa publicación** ni amplía un manifiesto previo por sí solo.

La entrega de APK requiere autorización del slice, tests y revisión del candidato, build/firma/compatibilidad e inspección de bytes. Enviar un archivo no demuestra descarga o instalación. La aceptación física queda separada de paridad Engram, del port de bots y de pruebas con proveedor real. Una entrega vertical no espera features auxiliares no causales; una falla de seguridad sí la detiene.

## 11 Catálogo verificable de fuentes

### Jarvys en el baseline fijado

Rutas relativas a este documento. Los tests citados en la matriz se encuentran bajo `app/app/src/test/java/com/jarvys/agent/`; inspeccionarlos no equivale a ejecutarlos. Los textos UX43 documentan cortes históricos; cuando difieren de la base actual, manda la fuente del pin y el checkpoint más reciente, no la etiqueta de release de un documento antiguo.

- **J1 Identidad y proyección:** [WorkspaceRecords.kt](../app/app/src/main/java/com/jarvys/agent/workspaces/WorkspaceRecords.kt), [WorkspaceRegistry.kt](../app/app/src/main/java/com/jarvys/agent/workspaces/WorkspaceRegistry.kt), [WorkspaceProjection.kt](../app/app/src/main/java/com/jarvys/agent/workspaces/WorkspaceProjection.kt). Estados técnicos, schema, claves/revisiones y owner. `WorkspaceRecords.needsReconciliation` conserva la incertidumbre STARTED.
- **J2 Admisión y controles:** [WorkspaceCoordinator.kt](../app/app/src/main/java/com/jarvys/agent/workspaces/WorkspaceCoordinator.kt), [CentralWorkspaceTool.kt](../app/app/src/main/java/com/jarvys/agent/workspaces/CentralWorkspaceTool.kt), [WorkspaceControlOwners.kt](../app/app/src/main/java/com/jarvys/agent/workspaces/WorkspaceControlOwners.kt). `delegateInternal`, `executeNativeControl` y APIs centrales; tests `workspaces/WorkspaceCoordinatorIntegrationTest`, `WorkspaceControlStopOwnershipTest`, `CentralWorkspaceToolTest`.
- **J3 Coordinación exacta:** [WorkspaceCoordinationDelivery.kt](../app/app/src/main/java/com/jarvys/agent/WorkspaceCoordinationDelivery.kt), [WorkspaceInputIncorporationDelivery.kt](../app/app/src/main/java/com/jarvys/agent/WorkspaceInputIncorporationDelivery.kt), [WorkspaceConversationInputStore.kt](../app/app/src/main/java/com/jarvys/agent/WorkspaceConversationInputStore.kt), [CrewCoordinationEvidence.java](../app/app/src/main/java/com/jarvys/agent/crew/CrewCoordinationEvidence.java), [contrato UX43](UX43_COORDINATION.md). `reconcile` es metadata/recibos, no ejecución; el outbox de supervisor existente conserva intents y entrega tras RESUME/CORRECT explícito, con invalidación por corrección posterior. Tests `WorkspaceSupervisorMessagePipelineTest`, de coordinación/incorporación y `workspaces/CentralWorkspaceCoordinationToolTest`.
- **J4 Runtime existente:** [CrewManager.java](../app/app/src/main/java/com/jarvys/agent/crew/CrewManager.java), [CrewMessageBus.java](../app/app/src/main/java/com/jarvys/agent/crew/CrewMessageBus.java), [CrewCheckpointCoordinator.java](../app/app/src/main/java/com/jarvys/agent/CrewCheckpointCoordinator.java), [CrewCheckpointStore.java](../app/app/src/main/java/com/jarvys/agent/CrewCheckpointStore.java). `waitFor`, `restoreBot`, `resumeExact`, `send`, correlación de respuesta y pending inputs. `coordinationRequests` sólo vive en proceso y restore conserva `requiresExplicitResume` para estados incompletos/inciertos.
- **J5 Resultado y entrega:** [WorkspaceResultDelivery.kt](../app/app/src/main/java/com/jarvys/agent/WorkspaceResultDelivery.kt), [UX43_RESULT_DELIVERY.md](UX43_RESULT_DELIVERY.md), [UX43_CENTRAL_WORKSPACES.md](UX43_CENTRAL_WORKSPACES.md). Referencia/aviso, lectura autorizada y artefacto checksum-bound son contratos distintos.
- **J6 Autoridad:** [CrewMissionAccess.java](../app/app/src/main/java/com/jarvys/agent/crew/CrewMissionAccess.java), [ActionPolicyDefaults.kt](../app/app/src/main/java/com/jarvys/agent/connectors/ActionPolicyDefaults.kt), [UX44_ACTION_POLICY.md](UX44_ACTION_POLICY.md). Tests `CrewWorkspaceMissionAdmissionTest`, `CoreAgentLoopToolAdmissionTest`, `WorkspaceMemoryAuthorityTest` y `connectors/ActionPolicyFoundationTest`.
- **J7 Memoria por workspace:** [WorkspaceMemoryTools.kt](../app/app/src/main/java/com/jarvys/agent/WorkspaceMemoryTools.kt), [WorkspaceMemoryOperations.kt](../app/app/src/main/java/com/jarvys/agent/chatdb/WorkspaceMemoryOperations.kt), [ChatDatabaseBridge.kt](../app/app/src/main/java/com/jarvys/agent/chatdb/ChatDatabaseBridge.kt), [MemoryNamespace.kt](../app/app/src/main/java/com/jarvys/agent/chatdb/MemoryNamespace.kt), [roadmap Engram](ENGRAM_PORT_ROADMAP.md). El Crew witness y `workspaceMemoryOperation` determinan identidad scoped. [RuntimeMemoryIdentity.kt](../app/app/src/main/java/com/jarvys/agent/chatdb/RuntimeMemoryIdentity.kt) permanece como helper de proyección sólo de Central, no adaptador de tareas. Tests `WorkspaceMemoryContinuityTest`, `WorkspaceMemoryAuthorityTest`, `WorkspaceMemoryOperationsTest`.
- **J8 Proactividad nativa:** [ProactiveAgentProcessor.kt](../app/app/src/main/java/com/jarvys/agent/proactive/ProactiveAgentProcessor.kt), [ProactiveCandidateQueue.kt](../app/app/src/main/java/com/jarvys/agent/proactive/ProactiveCandidateQueue.kt), [ProactivePrefilter.kt](../app/app/src/main/java/com/jarvys/agent/proactive/ProactivePrefilter.kt), [ProactiveScheduler.kt](../app/app/src/main/java/com/jarvys/agent/proactive/ProactiveScheduler.kt). `ProactiveReadOnlyToolFactory`, validación de event IDs y trabajo único; tests bajo `proactive/`.
- **J9 Presentación vigente:** [README.md](../README.md), [Pending.md](../Pending.md), [WorkspaceTimelineProjection.kt](../app/app/src/main/java/com/jarvys/agent/workspaces/WorkspaceTimelineProjection.kt). Conservación del diseño y controles; los bloques históricos no autorizan nuevos cambios.

### OpenMausBot en el pin fijado

- **U1 Tipos y contrato:** [server/runtime/types.ts](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/server/runtime/types.ts), [docs/runtime-contract.md](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/docs/runtime-contract.md). Assignment, origen, parent/root, work/consult/notify, needs_input/waiting/resume y outcomes.
- **U2 Ejecución:** [runtime.ts](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/server/runtime/runtime.ts), [tree.ts](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/server/runtime/tree.ts), [store.ts](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/server/runtime/store.ts). `open`, enqueue/dispatch, espera, cancelación y resultados; la rama de rerun tras restart es una divergencia rechazada.
- **U3 Invariantes y vectores:** [invariants.ts](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/server/runtime/invariants.ts), [runtime.test.ts](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/server/runtime/runtime.test.ts), [scenarios.test.ts](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/server/runtime/scenarios.test.ts). Resultado terminal único, coherencia estado/eventos, wakes, árbol y dedupe. Adaptar sólo las invariantes compatibles; las de rerun no autorizan replay en Jarvys.
- **U4 Capabilities y divergencia de privilegios:** [capabilities.ts](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/server/runtime/capabilities.ts), [level.ts](https://github.com/milind-soni/OpenMausBot/blob/7de00e425848d5f1a2a845cb4784851b87d8cad2/server/runtime/level.ts). `assignmentLevel` puede elevar por Chief; esa política no se porta.

### OpenDuet en el pin fijado

- **U5 Proactividad:** [restriction.ts](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/src/proactive/restriction.ts), [engine.ts](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/src/proactive/engine.ts), [store.ts](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/src/proactive/store.ts). Restricción de dispatch, sugerencias, aceptación, `HIDDEN_MEMORY_DAYS = 14`; la ventana no es una aprobación de producto Jarvys.
- **U6 Fatiga y proyección:** [routines/runner.ts](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/src/routines/runner.ts), [openduet/task-store.ts](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/src/openduet/task-store.ts). `FATIGUE_LIMIT = 10` es racha de disparos, no intentos de un turno; `taskStatus` deriva de preguntas/aprobaciones/cola y resolución humana.
- **U7 Cola y handoffs:** [work/queue.ts](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/src/work/queue.ts), [agents/handoff.ts](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/src/agents/handoff.ts). Claims con owner/lease, reloj consistente, reserva concurrente y límites de profundidad.
- **U8 Frontera frágil y escenario útil:** [toolkit/src/run-turn.ts](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/toolkit/src/run-turn.ts), [approval-send-once.integration.test.ts](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/tests/approval-send-once.integration.test.ts). Interfaces internas copiadas manualmente frente a pruebas de acción aprobada como máximo una vez; no confundir un test presente con un test ejecutado/pasado en Jarvys.
