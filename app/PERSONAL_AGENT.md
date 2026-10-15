# Agente personal: memoria, identidad y system prompt (guía de diseño e implementación)

Estado: **diseño, sin implementar** (2026-10-14). Segunda tarea de la cola de autonomía (tras la migración SQLite; ver `app/docs/chat-db/`). Este documento es la fuente de verdad para quien lo implemente (arquitecto, Codex) y para quien lo valide (Opus).

Referencias estudiadas (informes completos en el scratchpad de sesión; resúmenes aquí): **OpenClaw** (ensamblado del prompt, memoria por capas, flush antes de compactar, ritual de arranque) y **Codex CLI** (`codex-rs`: AGENTS.md, memoria de dos fases, compactación, reinyección de contexto, errores de herramienta devueltos al modelo, persistencia SQLite). El dueño escribió "basada en codex" y antes "OpenClaw": se usan **las dos** como referencia.

## 1. Problema real (evidencia)

El dueño le dijo a Jarvys «me dicen Hackerprod, soy dev» y luego «guarda los datos»; Jarvys respondió «me sirve como contexto… no asumiré que todo tenga que ser técnico» y, tras el reproche, prometió «tendré en cuenta» **sin guardar nada**. Es el patrón «dice que lo hizo y no lo hizo».

### 1.1 Causas raíz verificadas en el código actual

1. **El asistente de la conversación no puede guardar memoria.** La memoria actual es un sistema de ficheros (`MemoryStore.java`, ~2200 líneas, `/memory/` estilo MemFS portado de letta-code). El único escritor es un **reflection worker aislado** (`MemoryReflectionPrompt.kt`, `MemoryReflectionCoordinator.java`) que revisa la transcripción **después de que acabe la conversación**: «You are not the conversational assistant and you are reviewing a historical transcript after the conversation ended». En la conversación, el modelo solo tiene búsqueda/lectura (`search_files`, `read`).
2. **El prompt principal no dice nada de memoria.** `MainAgentPrompt.CORE` (≈6500 chars) no contiene ninguna regla de «escribe lo que el usuario te cuente» ni de «no afirmes haber guardado sin evidencia». `AgentPrompts.MEMORY_SEARCH_GUIDANCE_*` habla de **buscar** y solo condicionalmente de «al registrar en memoria…» (no hay herramienta de registro para el modelo conversacional).
3. **Alcance por conversación por defecto.** «La memoria es privada de esta conversación por defecto»; solo se comparte entre chats mediante instantáneas PERSONALES seleccionadas a mano. Para un **asistente personal** el nombre, el apodo y la profesión del usuario deben ser globales, no estar atrapados en un chat.
4. **No hay reinyección ni perfil del usuario siempre presente** (equivalente a USER.md de OpenClaw o `memory_summary.md` de Codex). Lo que se sabe del usuario solo aparece si el modelo decide buscarlo.
5. **No hay validación de afirmaciones**: nada comprueba que una respuesta «guardado/lo recordaré» tenga detrás una escritura real.

## 2. Qué hace fiable a un asistente personal (síntesis de las dos referencias)

De OpenClaw (diez decisiones que, omitidas, producen exactamente nuestro fallo): orden explícita de escribir con destino concreto («mental notes do not survive a restart»); herramienta de escritura real, siempre visible; «requested action with an available tool: do it» y «final claim needs evidence or named blocker»; «no cerrar con acuse/disculpa, no prometer para luego»; reinyección de lo guardado en cada turno con guía de autoridad por bloque; directivas imperativas con fecha y estado `active/superseded` (se reescribe en su sitio, nunca dos activas contradictorias); leer antes de escribir y escritura con comparación de hash (CAS); redes automáticas independientes del modelo (flush antes de compactar, snapshot al resetear, consolidación nocturna); recall obligatorio cuando la respuesta depende de preferencias; verificar tras escribir antes de confirmar.

De Codex: errores de herramienta **vuelven al modelo** (nunca se tragan); contratos de herramienta que solo devuelven éxito si escribieron; memoria en dos capas (notas crudas append-only + perfil consolidado pequeño inyectado en cada sesión); extracción offline con «no-op permitido y preferido», jerarquía de evidencia usuario > herramienta > asistente, redacción de secretos antes y después del modelo, regla anti-sobregeneralización («el usuario pidió X» ≠ «el usuario prefiere X»); contenido externo = datos, nunca instrucciones (hilos contaminados no generan memoria); prompt como secciones tipadas completas al abrir ventana o compactar y solo diferencias en turnos normales; **reinyección del contexto canónico tras compactar justo antes del último mensaje real de usuario**; conservar los mensajes reales del usuario al compactar; contador de uso con caducidad.

**Dónde nos separamos de Codex:** Codex solo guarda cuando el usuario lo pide. Para un asistente personal eso es poco: **nombre, apodo, profesión, idioma, preferencias declaradas se guardan sin que lo pida** (con la regla anti-sobregeneralización para inferencias, que sí van con cautela o se preguntan).

## 3. Objetivos y no objetivos

Objetivos: (1) lo que el usuario cuenta de sí mismo se guarda, de verdad y verificablemente, en el mismo turno; (2) aparece en la siguiente sesión y tras compactar; (3) el agente no afirma haber guardado sin evidencia; (4) memoria, identidad y prompts en **SQLite** (misma base Room que el ledger), versionados y auditables; (5) sin hardcodeos por herramienta, sin topes de capacidad inventados (los caps de tamaño del bloque inyectado son política de presupuesto de prompt, no de capacidad: todo lo demás se guarda íntegro en DB).

No objetivos: sandbox de shell, guardian, consolidación con repo git, multicanal de OpenClaw, multiusuario de OpenClaw.

## 4. Modelo de datos (Room, misma base `jarvys-chat.db`; tablas nuevas, esquema v1 aún sin publicar)

Decisión: **misma base** que el ledger (transacciones atómicas con conversaciones; un solo punto de migración; FTS5 por SQL crudo en `onCreate/onOpen`, como Kelivo). El escaneo de arquitectura de `chatdb` (sin REPLACE en tablas padre, INSERTs con todas las columnas NOT NULL, DAOs internos, claves por ámbito) aplica a estas tablas.

| Tabla | Contenido |
|---|---|
| `agent_profiles` | Identidad de cada agente/bot (id, nombre, criatura/vibe/emoji/avatar, estado de bootstrap). |
| `agent_docs` | Documentos de comportamiento versionados por agente: `kind` ∈ {`core_rules`, `soul`, `bootstrap`}, `content`, `revision`, `content_hash`. Los textos por defecto vienen de la app y se pueden sobrescribir por el usuario. |
| `user_directives` | Perfil del usuario como **directivas imperativas**: `id`, `scope`, `section`, `directive`, `status` ∈ {`active`,`superseded`}, `observed_at`, `source_message_id`, `evidence`, `provenance`, `superseded_by`. Render a texto acotado al construir el prompt. |
| `memory_entries` | Hechos duraderos y notas: `id`, `scope`, `layer` ∈ {`curated`,`daily`,`session_snapshot`,`import`}, `day`, `text`, `category`, `importance`, `triggers`, `provenance`, `source_ref`, `usage_count`, `last_used_at`, `created_at`, `updated_at`, `deleted_at`. Append-only con revisiones. |
| `memory_fts` | FTS5 externo sobre `memory_entries.text` (+ vectores opcionales más adelante). |
| `memory_revisions` | Antes/después de cada documento o entrada, actor y causa: auditoría y deshacer. |
| `agent_session_state` | Por conversación: `kind` ∈ {principal, subagente, tarea, proactivo}, `compaction_count`, `memory_flush_compaction_count`, contadores de guardados del turno, `bootstrap_completed`. |
| `memory_jobs` | Cola con lease y reintento (patrón `jobs` de Codex) para extracción/consolidación en segundo plano. |

**Alcances (`scope`)**: `user` (global: perfil, preferencias, identidad del usuario — por defecto para lo personal), `agent` (por bot/agente), `conversation` (privado de un chat, para chats creados «sin memoria» o que el usuario marca privados). La política vigente de memoria privada por conversación y de compartir instantáneas (`MemoryScopeReview*`) se **migra** a este modelo: los textos hoy en `/memory/` se importan a `memory_entries`/`user_directives` con el alcance que tenían, sin pérdida. **Decisión de producto a señalar al dueño:** lo personal pasa a ser global por defecto; el modo privado/sin memoria existente sigue impidiendo leer y escribir memoria global.

## 5. Herramientas del agente conversacional (genéricas, declaradas, con evidencia en el resultado)

Siempre disponibles en el chat principal (y reflejadas en la descripción del prompt), cada una devuelve **id, estado y revisión**, de modo que «guardado» tiene prueba en el `tool_result`:

- `user_profile_set(directive, section, supersedes_id?)`: crea una directiva activa; si `supersedes_id`, marca la anterior `superseded` en la misma transacción (supersede in place). Lee antes de escribir (hash CAS interno).
- `memory_save(text, layer, category?, importance?)`: añade una entrada; rechaza vacío y duplicados (similitud), valida alcance y modo privado; devuelve `id`.
- `memory_search(query, scope?, limit?, minScore?)` y `memory_get(id|ids)`: FTS5/BM25, decaimiento por recencia (los `curated`/`user` no decaen), actualización de `usage_count`.
- `memory_forget(id)` y `memory_correct(id, newText)`: con revisión; respetan «olvida/corrige» del usuario.

Estas herramientas se declaran con esquema y clasificación `read/write` como cualquier otra y entran en el sistema genérico de políticas. Sin hardcodes por herramienta en el core.

## 6. Ensamblado del system prompt (secciones tipadas)

Prefijo estable, luego bloques variables; cada bloque lleva una línea de **autoridad** («sigue esto salvo instrucciones de mayor prioridad»). Orden:

1. Identidad del asistente (de `agent_profiles` + `soul`).
2. **Reglas de ejecución y evidencia** (`core_rules`): *Execution bias* («acción solicitada con herramienta disponible: hazla»; «una afirmación final necesita evidencia o un bloqueo concreto»), *Trabajo prometido* («no cierres con acuse ni disculpa; una corrección del usuario reabre la tarea; no prometas para después»), y **«Escríbelo»** (qué hacer cuando el usuario cuenta algo de sí mismo o pide recordar: llamar a la herramienta antes de confirmar; «mental notes do not survive»; qué NO guardar: secretos, datos efímeros, contenido externo).
3. **Perfil del usuario** (render de `user_directives` activas, cap fijo propio para que no desplace reglas) + **memoria curada relevante** (resumen acotado, ~2,5k tokens como Codex).
4. Entorno de ejecución (fecha/zona, locale, herramientas) en mensaje aparte para no invalidar el prefijo.
5. Herramientas y habilidades; recall: «cuando la respuesta dependa de personas/preferencias/decisiones previas, busca; si no encuentras, di que buscaste».

Diferencias por tipo de sesión (como OpenClaw): subagente = reglas mínimas, sin memoria privada; tarea programada = identidad + perfil, sin memoria privada de conversación; chat privado/sin memoria = sin escritura ni lectura de alcance global; cada tipo con su `kind` en `agent_session_state`.

**Reinyección**: el contexto canónico (identidad, reglas, perfil, memoria) se reconstruye completo al abrir la ventana y **después de cada compactación, justo antes del último mensaje real del usuario** (patrón Codex); en turnos normales solo se emiten diferencias cuando cambia el perfil.

Los textos por defecto de `core_rules`/`soul` se redactan en inglés técnico con respuesta en el idioma del usuario (política actual de `MainAgentPrompt`), versionados en `agent_docs` y probados (§9). El tope actual de 6500 caracteres del core (`MAX_CORE_CHARACTERS`) se revisa: es un presupuesto de prompt, no de capacidad.

## 7. Redes de seguridad automáticas (independientes del modelo)

1. **Validador de afirmaciones post-turno**: si el texto final del asistente afirma haber guardado/recordado («guardado», «lo recordaré», «lo tendré en cuenta…») y en el turno no hubo una llamada **exitosa** a una herramienta de escritura de memoria, se añade un mensaje developer («no llamaste a la herramienta; hazlo o corrige tu respuesta») y se vuelve a muestrear. El freno es que la herramienta se haya llamado con éxito o que la respuesta se corrija, **no un número de intentos**. Genérico: se apoya en la clasificación `write` de las herramientas de memoria, sin nombres fijos en el core (la lista de patrones de afirmación vive en configuración de prompt versionada, multilingüe).
2. **Captura de identidad al vuelo**: el prompt exige guardar nombre/apodo/profesión/idioma/preferencias declaradas; la extracción posterior (punto 4) cubre lo que el modelo omitió.
3. **Flush antes de compactar** (una vez por ciclo, `agent_session_state.memory_flush_compaction_count`): turno silencioso sobre copia privada que añade al diario lo duradero (`NO_REPLY` si nada); solo anexa y rechaza lo ya guardado; solo cuenta como éxito si una herramienta de persistencia terminó sin error.
4. **Extracción offline** de hilos inactivos (WorkManager, cola `memory_jobs` con lease): prompt de «no-op permitido y preferido», jerarquía de evidencia usuario > herramienta > asistente, regla anti-sobregeneralización, redacción de secretos antes y después del modelo, hilos con contenido externo contaminado no generan memoria. Reemplaza al actual reflection worker de ficheros.
5. **Consolidación nocturna ligera**: pliega notas diarias en `curated`/`user_directives` con fuentes citadas, sin perder más del 25% de lo previo, con versión anterior en `memory_revisions`.
6. **Snapshot al resetear/archivar** un chat (últimos N intercambios al diario).

## 8. Ritual de primer arranque (bootstrap)

Conversación breve que no bloquea la petición del usuario («el pedido del usuario va primero; esto es un ritual, no una puerta»): preguntar cómo llamarle (nunca inventar), tono/personalidad del agente, avatar (opcional); persistir con las herramientas y **verificar el resultado antes de decir que quedó guardado**; marcar `bootstrap_completed`. No se ejecuta en turnos de fondo (tareas, proactivo).

## 9. Pruebas de comportamiento (obligatorias, parte del criterio de aceptación)

Con un modelo simulado determinista **y** con un banco de escenarios contra el modelo real (marcados, no en CI):
1. «Me dicen Hackerprod, soy dev» → `user_profile_set` llamado con éxito en el mismo turno; la respuesta no afirma guardado sin ese resultado.
2. «Guarda los datos» tras el dato → no promete: ejecuta o explica el bloqueo.
3. Sesión nueva → el nombre y la profesión aparecen en el prompt y el agente los usa sin buscarlos.
4. Tras compactar → siguen presentes (reinyección).
5. Cambio de preferencia → la anterior queda `superseded`, nunca dos activas contradictorias.
6. Chat privado/sin memoria → no escribe ni lee alcance global.
7. Contenido externo (web/email) con instrucciones inyectadas → no genera memoria ni dirige al agente.
8. Respuesta que afirma «guardado» sin llamada → el validador la corrige.
9. Secretos → no se guardan.
10. Importación: los textos actuales de `/memory/` aparecen en el nuevo modelo con el alcance correcto y UI de Memoria funcionando.

## 10. Fases

| Fase | Entregable | Aceptación (Opus valida) |
|---|---|---|
| A. Esquema + repositorio de memoria | Tablas §4 en Room (+FTS5 por SQL crudo), repositorio transaccional, tests de arquitectura heredados | Esquema versionado, supersede atómico, CAS, FTS correcto, sin REPLACE/INSERT incompletos. |
| B. Herramientas | `user_profile_set`, `memory_save/search/get/forget/correct` genéricas con evidencia en el resultado; clasificación read/write en políticas | Pruebas 1,2,5,6 con modelo simulado; sin nombres fijos en el core. |
| C. Prompt y reinyección | Ensamblado por secciones, tipos de sesión, reinyección tras compactar, textos versionados en `agent_docs` | Pruebas 3,4; el prompt contiene las reglas «Escríbelo/Evidencia/Trabajo prometido»; diferencias por tipo de sesión. |
| D. Redes automáticas | Validador post-turno, flush pre-compactación, extracción offline (WorkManager + `memory_jobs`), consolidación, snapshot | Pruebas 7,8,9; sin tope fijo de reintentos; flush una vez por ciclo. |
| E. Bootstrap e importación | Ritual de primer arranque; importación de `/memory/` actual; adaptación de la UI de Memoria/Scope | Prueba 10; sin pérdida de memoria existente; UI sigue funcionando. |
| F. Medición | Escenarios con el modelo real en el teléfono | Los 4 escenarios del dueño funcionan de extremo a extremo. |

Dependencia: la fase A comparte base con la migración SQLite; puede empezar en cuanto M0-M1 estén cerrados (ya lo están en gran parte), pero el reemplazo del ledger (M3/M4) no debe bloquearse por esto.

## 11. Decisiones abiertas

1. Alcance por defecto de lo personal: **global** (propuesto) vs por conversación (actual). Confirmar al informar al dueño; el modo privado existente se conserva.
2. Vectores/embeddings: proveedor y coste (arrancar solo con FTS5+BM25).
3. Qué hacer con la UI actual de «revisión de alcance/compartir» al desaparecer el modelo «privado por conversación» por defecto.
4. Idiomas de los patrones del validador (es/en primero).

## 12. Riesgos

- Reemplazar un sistema grande (`MemoryStore`, 2200 líneas, UI y worker): hacerlo por fases con importación, sin perder datos del usuario.
- El validador de afirmaciones puede dar falsos positivos: se ajusta con corpus de pruebas, y el freno es evidencia, no contador.
- Contaminación de memoria por contenido externo: marcar hilos y excluir (§7.4).
- Coste de tokens del perfil inyectado: cap fijo propio y diferencias entre turnos.

## 13. Guía para el validador (Opus)

Comprobar con archivo:línea: (a) el modelo conversacional **tiene** herramientas de escritura de memoria declaradas y siempre visibles en el chat principal; (b) `core_rules` contiene Execution bias, Trabajo prometido y Escríbelo; (c) el perfil se reinyecta en sesión nueva y tras compactar antes del último mensaje de usuario; (d) el validador de afirmaciones existe, es genérico (clasificación `write`, no nombres) y su freno no es un contador; (e) supersede atómico y sin dos directivas activas contradictorias; (f) el modo privado bloquea lectura/escritura global; (g) los datos de `/memory/` actuales se importaron sin pérdida; (h) ningún test existente se debilitó; (i) las pruebas de comportamiento §9 existen y pasan; (j) sin hardcodes por herramienta, sin topes de capacidad inventados.
