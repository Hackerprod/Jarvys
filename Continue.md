# Continue.md — contexto para retomar el trabajo en otro PC

Escrito el 2026-10-25 por Claude Code (Sonnet 5.5) a peticion del dueno, que cambia de PC. Idioma de trabajo con el dueno: espanol (estilo telegrafico, sin relleno); codigo, commits y PRs en ingles normal. Lee este archivo ENTERO antes de hacer nada. El dueno es el arquitecto de Jarvys (app Android personal-agent, Kotlin/Compose + Java, repo `Hackerprod/Jarvys`); yo oriento, delego en Codex/agentes, valido y entrego.

## 0. Como retomar en el otro PC (checklist)

1. Trae el bundle `jarvys-continue-bundle.zip` (ver §9) y el repo con la rama `feat/single-session` (commit de este fichero). El push desde el PC viejo esta DESHABILITADO a proposito (`pushurl DISABLED_push_from_here_until_owner_enables`): el dueno decide como mover la rama (push manual con `gh`, copia del worktree o `git bundle`). NO hagas push sin que lo diga.
2. Restaura la memoria de Claude (carpeta `memory/` del bundle) en `~/.claude/projects/<proyecto-actual>/memory/` (ajusta la ruta del proyecto del nuevo PC). El indice es `MEMORY.md`.
3. Ajusta las rutas Windows de este documento al nuevo PC (hoy: repo principal `C:\Projects\Jarvys`, worktrees `C:\Projects\Jarvys-wt\*`, validacion `C:\Projects\Jarvys-wt\single-validate`, mascota `C:\Projects\mascot-lab`, build `C:\Projects\Jarvys-tools\build.ps1`, salida APK `C:\Projects\Jarvys-out`). Los briefs y reportes copiados estan en `docs/continue/single-validate/` (rutas dentro de ellos apuntan al PC viejo: sustituyelas).
4. Comprueba herramientas: JDK 21 (`JAVA_HOME`), Android SDK (`ANDROID_HOME`), `codex` CLI (modelos `gpt-6-luna`, `gpt-6.1-sol`, `gpt-6-astra`), `gh`, Edge/Chrome para la mascota, Python con PIL/numpy.
5. ESTADO ACTUAL = PAUSA ORDENADA POR EL DUENO: "pausa la implementacion hasta nuevo aviso". No relances implementacion, verificacion ni jueces hasta que el dueno lo diga. Primero informa del estado y espera.
6. Cuando el dueno de el aviso, empieza por §3.2 (S6 parcial).

## 1. Reglas del dueno (obligatorias; vienen de su memoria)

- Los dos humanos de este proyecto: el dueno y yo. Responder terse en espanol; no recapitular; honestidad: decir siempre que esta VERIFICADO y que NO.
- NO inventar topes/rondas/limites numericos (frenos reales, no numericos). NO validadores de frases ni regex por idioma ni mensajes defensivos al modelo (como OpenClaw/Muse). Sin parches ni hardcodeos: causa raiz.
- Tests LIMITADOS por tarea: cada tarea con los suyos; solo correr tests ajenos si el diff toca esa parte; nunca la suite completa (~1800 tests, ~1400 fallos de entorno Windows preexistentes). Un fallo solo se llama "preexistente" si se compara contra una copia exportada de HEAD fuera del repo (en S2 un implementador declaro preexistente una regresion propia).
- Vigilar cambios de producto "para pasar tests" (agentes que cambian prod para que pasen sus tests).
- Autorizacion permanente (2026-10-18): tras validacion PASS -> merge, tests afectados, build, firma, verificacion y entrega del APK a Telegram, y push de ramas sin preguntar. Excepciones: el dueno dice "espera/pausa" (HOY ESTA EN PAUSA); fallos de test NUEVOS paran la entrega.
- NO usar la VPS para nada. Telegram: `devmcp telegram_send_document` del PC (corta a 20 s con APK de ~40 MB: intentar directo; si falla adjuntar con `SendUserFile` y avisar). Push a GitHub desde el PC con `gh` (autenticado como Hackerprod; no pedir token).
- Si el sistema corta una tarea por poca RAM ("critically low on memory"): el dueno dijo "no limites, deja que el sistema pagine" y autorizo relanzarla sin preguntar (salvo en pausa). La memoria libre es siempre baja: no pedir cerrar apps. Causas reales detectadas: daemons de Gradle/Kotlin (~4 GB) y procesos zombi (ver §8).
- PRE-PUBLICACION: la seccion "Open-source licenses" de la app fue quitada a proposito; NO publicar sin reponerla.
- Validadores/jueces independientes: Sol (`gpt-6.1-sol`) para validar; Luna (`gpt-6-luna`) para implementar; Astra (`gpt-6-astra`, esfuerzo alto) para trabajo duro (la mascota). Si codex sin cuota: agentes Claude (Sonnet implementa y juzga; Opus solo si imprescindible, el dueno pidio ahorrar Opus).
- La herramienta Bash del harness falla con comillas/heredocs en este entorno: usar PowerShell o ficheros .ps1/.py.

## 2. Que se esta construyendo

Reestructuracion de Jarvys a UNA SOLA SESION del agente (estilo Muse de Meta) con sistema de recuerdos propio inspirado en Engram:

- Una unica sesion visible: `CENTRAL_SESSION = "jarvys-central"`. Sin chats legacy, sin "new chat", workspaces INVISIBLES, menu izquierdo = perfil (abre Memoria) + Ajustes (Bots, Tareas programadas y Memoria dentro de Ajustes).
- Bots/Crew multiples SE CONSERVAN (decision del dueno): una mision de bot es una mision de un workspace invisible; el resultado aparece en el hilo como tarjeta `mission_ref`.
- Memoria en capas: contexto (perfil + resumen rodante + ultimos turnos) / memorias en DB (hechos + EPISODIOS con evidencia determinista + narracion del LLM) / transcripcion completa buscable (`history_search/read`). Caso de referencia (Muse): "te pedi limpiar mi spam de gmail?" -> "si, 29 sep, 53 mensajes a la papelera tras conceder acceso a Gmail".
- Documento de diseno (la especificacion, validado PASS por Sol en 6 rondas): `docs/SINGLE_SESSION.md` en esta rama. Etapas S1..S8 en su §9; contratos de pruebas B1..B15 en §7.9; preguntas ya resueltas en §10.B.
- Inspiracion Engram (repo `Gentleman-Programming/engram`, MIT): inventario F01..F44 en `docs/continue/engram-features.md` (codigo propio, nunca copiar literal, no usar nombre/logos). El dueno quiere ADEMAS un juez PASS/FAIL contra esas funcionalidades al final del port.

### Decisiones del dueno P1..P11 (2026-10-24)

P1 memoria personal TAMBIEN se borra (empezar de cero total, sin copia ni importador). P2 Bots y Tareas dentro de Ajustes (drawer = perfil + Ajustes). P3 sin preview en vivo de proyectos no entregados. P4 privacidad eliminada del todo (ni por mensaje). P5 un episodio por PETICION de usuario (owner_user_message_id). P6 la narracion NO cita contenido de conectores por defecto (instruccion en agent_docs). P7 NUNCA se expulsan artefactos referenciados por episodios (si no queda nada expulsable: aviso en Ajustes>Almacenamiento y degradar sin perder datos). P8 `delegate_subtask` fuera de Central. P9 borrar archivos huerfanos tras el primer run correcto. P10 diez interacciones cerradas protegidas de la compactacion (cifra elegida por el dueno). P11 sin boton de reiniciar hilo. Chats viejos: empezar de cero. Modo privado: quitado.

## 3. Estado exacto del repo y de las etapas

Repo: `C:\Projects\Jarvys` (master viejo; NO usar). Rama viva: `feat/single-session`, worktree `C:\Projects\Jarvys-wt\single`, basada en `deliver/v11` (worktree `C:\Projects\Jarvys-wt\v11`, rama de la ultima entrega 2026-10-19). Commits (todos locales, sin push):

| Etapa | Commit | Estado |
|---|---|---|
| Documento de diseno | 47ac4557 | PASS Sol (ronda 6) |
| S1 nucleo de sesion unica, esquema v3 aditivo, reset por fases, StorageGeneration g3 | 3ac44605 | COMMITEADA |
| S2 UI minima y retirada de privacidad | 09bd1352 | COMMITEADA |
| S3 proactivo y tareas -> Central | 2ed9ea6d | COMMITEADA |
| S4 workspaces invisibles + Crew/bots | 5ac859b7 | COMMITEADA |
| S5 memoria: topic_key, history_*, retirada de scope, retencion exacta | e65020e5 | COMMITEADA (PASS Sol ronda 5) |
| S6 episodios | (commit WIP de esta rama) | PARCIAL, ver §3.2 |
| S7 contexto `<recent_episodes>` + diez interacciones protegidas | - | NO EMPEZADA |
| S8 limpieza MemFS + deuda | - | NO EMPEZADA |

### 3.1 Ciclo por etapa (como se ha trabajado)

1. Escribo `stage-SN-brief.txt` (spec + reglas + comandos exactos) en `single-validate/` y lanzo un implementador: `powershell -NoProfile -ExecutionPolicy Bypass -File run_stage.ps1 -Stage SN -Model gpt-6-luna` (script en `docs/continue/single-validate/run_stage.ps1`: lee el brief por stdin a `codex exec -m <modelo> -c model_reasoning_effort=high --skip-git-repo-check -s danger-full-access`, salida en `stage-SN-last.txt`, log en `stage-SN-run.log`; con `-Mode judge` usa `-s read-only`).
2. El implementador deja `stage-SN-report.md` (el informe se actualiza durante el trabajo: si lo cortan por RAM, el relanzador lo lee y continua).
3. YO ejecuto compile (Full y Play, main+tests) y los tests de la etapa con `--rerun` (plantillas `verify_S5e.ps1`, `verify_S4b.ps1`...: `gradlew :app:testFullDebugUnitTest --rerun --tests "*Clase" -x :app:prepareApkFactoryTemplate`; sin `--rerun` Gradle reutiliza resultados en cache y NO prueba nada). Leo los XML en `app\app\build\test-results\testFullDebugUnitTest`.
4. Juez independiente solo-lectura: Sol (`gpt-6.1-sol`, `run_stage.ps1 -Stage SN-judge -Mode judge` con `stage-SN-judge-brief.txt`; primera linea `VEREDICTO: PASS|FAIL`). FAIL -> el informe del juez va a una brief de correccion (`stage-SNfixK-brief.txt`) para el implementador -> repetir. PASS -> commit con atribucion (ver §7).
5. Los jueces han encontrado cosas REALES en cada etapa (regresiones de tests, advertencias de seguridad borradas, UI sin refrescar, rol `assistant` al final de la peticion, cursores inestables...): no aflojar.

### 3.2 S6 PARCIAL (lo que hay en el worktree y falta)

S6 = episodios: captura durante la ejecucion, reconciliacion, narracion y flush (spec: `docs/SINGLE_SESSION.md` §7.5, §7.8, §9 "S6", contratos B3, B4, B5, B15, R5/R8). Brief: `docs/continue/single-validate/stage-S6-brief.txt`. Informe parcial del implementador: `docs/continue/single-validate/stage-S6-report.md`. Luna (`gpt-6-luna`) fue CORTADA por poca RAM a mitad (sin `stage-S6-last.txt`).

Hecho (sin verificar por mi): `CoreTool.EffectClass` + hook de pre/post dispatch en `CoreToolRegistry`; persistencia de episodios/evidencia y reconciliacion `intent|started -> uncertain`; migracion Room 3->4 (`connector_autonomy_audit` que sustituye a `appendAudit`, y `connector_state_events` con el primer evento de conexion); captura en tareas y acciones aprobadas (UUID de intento durable en `ScheduledTaskProcessor`, `TaskPendingActionExecution`, `TaskPendingActionStore`); `EpisodeNarrator` (estados none/pending/failed/done), job `narrate` con lectura incremental, flush previo a compactar, instruccion P6 en `agent_docs`, referencias de episodio en el resumen; primera compilacion Full/Play main+tests OK (65 tareas) ANTES de las ultimas ampliaciones.
Falta: segunda compilacion (tras Room y narracion), B3/B4/B5/B15, prueba de contrato del registro (toda herramienta declara su clase de efecto), mutaciones de control, tests de clases tocadas, comparacion de fallos contra HEAD, informe final. El commit WIP NO es una version validada: tratarlo como borrador y verificar compilacion antes de nada.

Al retomar: (a) leer el informe de S6; (b) comprobar `git diff --stat` del commit WIP respecto a e65020e5; (c) relanzar Luna con la brief de S6 (+ nota "continua desde el informe, no reinicies") o terminar manualmente; (d) verify + Sol; (e) commit definitivo de S6.

### 3.3 S7 y S8 (pendientes)

- S7: `<recent_episodes>` en `PersonalAgentRepository.promptContext` (presupuesto = lo que queda de `CorePromptBudget.memoryChars`), orden estable del prefijo, recencia protegida de diez interacciones cerradas en `ConversationCompactionPolicy.planSliding/planAll`, interaccion definida por `owner_user_message_id` e intentos (NO por contiguidad), pertenencia durable del steering (hoy no existe: S7 la ANADE), bloqueo preciso si lo protegido no cabe. Tambien F22 (nudge) de Engram. Contratos B6/B7.
- S8: borrar MemoryStore y satelites (MemoryReflectionCoordinator, MemoryReflectionAuthority, helper MemoryUiLogic.sessionMemoryDisabledKey, MemSessionMode ya borrado), `MemoryReflectionPreferences` -> preferencia unica, y toda la DEUDA anotada: `workspaceSenderLabel` sin asignador, strings `workspace_*` y `workspace_coordination_live/historical` sin uso, `previewIsCurrent` y `chat_open_current_preview`, consultas `countPending*ByWorkspace`, `enqueueNativeControl/submitNativeControl` sin llamador UI, parametros `crewMode/onCrewModeChange` de `ChatComposer`, `WorkspaceStore.hasIndexHtml/resolvePreviewPath` y rama workspace de `WorkspacePreviewContent`, strings `single_session_retired/started` faltan en `values-es`, rama proactiva de `onNewIntent` sin guarda de grafo, cobertura de `assistant_deleted`/reapertura del cursor en B2 (S5), kinds `proactive/tasks` residuales en `listConversations`/`DEFAULT_EXCLUDED_KINDS`, `readConversationTimeline` sin llamadores de produccion, docs `app/docs/chat-db/*.md` obsoletos, `SingleSessionReset.kt` prefijo `chat_without_memory_` (excepcion aceptada del grep).

## 4. Despues de S8 (plan acordado con el dueno)

1. PORT del sistema de recuerdos tipo Engram con JUEZ PASS/FAIL contra F01..F44 (`docs/continue/engram-features.md`). Mucho ya esta cubierto por S5-S7 (F01, F02, F04, F08, F11, F12, F15, F16, F25, F34, F35...). Tabla honesta pendiente de consolidar: F22 -> S7; F34 hecha como regla de prompt (CORE_RULES_V3); F07/F35 parciales; F37 export/import SIN etapa y SIN decision (proponer al dueno incluirlo como trabajo posterior o descartarlo); F11 prioridad de clave exacta con `/` no cubierta. El juez debe verificar comportamiento real, no solo lectura de codigo. No usar el nombre "Engram" en la app.
2. Pendiente de producto menor: enlace "Ver en Memoria" desde el hilo (§6.4 del doc; no estaba en S5).
3. APK: build + firma + verificacion + entrega a Telegram segun la autorizacion permanente. Script `C:\Projects\Jarvys-tools\build.ps1` (parametros `-Flavor full|play|both`, `-Clean`, `-Install`, `-Repo` (poner `...\Jarvys-wt\single\app`), `-Out`, `-Tag`); firma con `app/debug.keystore` (SHA-1 de la firma de Jarvys en `reference_jarvys_signing_sha1` de la memoria); NO crear otra keystore. Tiempos: build incremental 30 s - 3 min, frio ~10 min. Un solo Gradle a la vez. Sin APK hasta que TODAS las etapas esten validadas. Esta rama requiere BASE DE DATOS NUEVA (v3) y reset de datos: el dueno asumio empezar de cero; avisar al entregar que la primera apertura retira los datos antiguos (memoria incluida) tras el primer run correcto. Texto de UI de primer arranque y de almacenamiento bajo aun sin redactar.
4. MASCOTA (tarea PAUSADA por el dueno hasta terminar la sesion unica/memoria): redibujar el fantasma de Jarvys con Canvas para animarlo por partes segun el estado del agente (idle, thinking, tool, waiting, done, error, sleep), basado en el "Mochi" de Coucou. Laboratorio: `C:\Projects\mascot-lab` (copiado en `docs/continue/mascot-lab/`: `jdraw.js` con IoU 96.2% contra `reference.png`, `cmp.py`, `poses.py`, `render.html`, `BRIEF.txt` de Astra, `VALIDATOR_BRIEF.txt`). Estado: la version publicada (artifact https://claude.ai/artifact/RjL1tLnv5KLcwJo5WGuzQa, "Jarvys Canvas Sketch") mejoro mucho pero el dueno ve "muchisimas incoherencias" (orejas inferiores como ovalos sueltos, manos como bolas grises, cabeza algo cuadrada, rizo plano, boca abierta/error rara). `jdraw.js` del laboratorio tiene cambios PARCIALES sin medir de una corrida de Astra cortada (copia previa en `docs/continue/mascot-lab/jdraw.before-astra.js` si existe en `out/`). Plan: Astra (`gpt-6-astra`, esfuerzo alto, `BRIEF.txt`) retoca `jdraw.js` y un VALIDADOR (el dueno pidio un Sonnet; hoy usaria Sol) verifica antes de declarar terminada (`VEREDICTO: PASS|FAIL`, `VALIDATOR_BRIEF.txt`); luego corregir el recorte por arriba en la pagina (escala/headroom), re-ensamblar con `assemble.py` (en el scratchpad del PC viejo; si no esta, reconstruir la pagina desde `tpl2.html` con el `jdraw.js` final) y republicar el artifact. Despues, si el dueno aprueba, portar a Compose Canvas (sin shadowBlur/ctx.filter/imagenes; solo gradientes/paths/clip) usando el dibujo solo en estados animados.
5. Cola antigua del dueno (sin tocar aun): chat de workspace oculta respuestas finales del worker (revisar si sigue en la nueva arquitectura), semantica de STOP, hangs de chat largo (mediciones con adb), items de rendimiento (streaming por frame, locks de admision, snapshot de memoria de sesion, build release/profileable), comparacion real de calidad de respuesta, limpieza de worktrees/temp en `C:\Projects\Jarvys-wt\` (hay ~35 worktrees viejos), reponer la seccion de licencias antes de publicar, +1.7 MB de APK en v10 sin explicar.

## 5. Baseline de fallos conocidos (preexistentes en HEAD, acreditados)

No los declares ni los arregles sin comparar de nuevo: `ProactiveAgentProcessorTest.importantDecisionPostsCleanNotificationAndRestorableCentralMessage` (rename atomico de Windows), 3 de `HtmlPreviewSnapshotTest` (2 por `createSymbolicLink` sin privilegio + `missingToolPresentation...`), `MemoryScopeRuntimeTest` (6), `MemorySearchToolTest` (5; dos son aserciones, no symlinks), `MainAgentInteractionTest` (21), `CrewContextArtifactsRecoveryTest` (1), `DeliveredArtifactTest` (1), `WorkspaceResultLedgerTest.deletedCentralIsNeverRecreated` (1), 2 de `GeneratedImageStoreTest` (symlinks), `AppLanguagePolicyTest.defaultAndSpanishResourceTablesHaveMatchingTranslatableKeys` (faltan traducciones `inline_block_*`). La mayoria es ruido del entorno Windows; en otro PC pueden cambiar.
Exportaciones de HEAD para comparar (en el PC viejo, NO incluidas): `single-validate\stage-S4-head-export`, `...S5-head-export`. Crea una nueva del HEAD que corresponda con `git archive` a una carpeta temporal fuera del repo.

## 6. Hallazgos tecnicos que importan al continuar

- Barrera de reset de S1: `SingleSessionReset` arranca asincrono en `Application` y los consumidores (Activity, servicio, workers, receptores, providers) ESPERAN la barrera antes de leer/escribir datos nuevos. Los tests de Robolectric que toquen Room necesitan el `@Before` de espera (`runBlocking { SingleSessionReset.awaitReady(context) }` o `awaitReadyBlocking` en el worker de BD).
- La base v3 se edita EN SITIO (rama no publicada); cualquier cambio tras el primer lanzamiento exige `Migration(3,4)`. S6 ya anade una migracion 3->4 (connector_autonomy_audit, connector_state_events): verificar coherencia con "v3 en sitio" (el doc lo preve para tras el primer lanzamiento; decidir si se pliega a v3).
- Contexto no confiable al modelo (avisos proactivos recuperados) va como input con rol `user` envuelto en `<jarvys_incoming ... trust="untrusted">` (no `assistant` final: da 400 en OpenRouter con ciertos modelos).
- Todo escritor de fondo que escriba en Central debe refrescar la UI visible (`LocalRunStore.refreshVisibleCentral()`).
- `history_search` usa keyset `seq DESC` con cursor ligado a consulta + `conversations.history_revision` (durable, incrementada por los productores reales de mutaciones; los append no invalidan).
- Cierre protegido de artefactos: por TODA fila de `memory_evidence` (tambien de entradas retiradas); solo se protege la compactacion cubridora (menor `first_kept` mayor que el indice del lote, desempate por seq).
- Fallos del entorno: un OOM del daemon de Kotlin ocurre si coinciden varios Gradle; los tests de HtmlPreview/Preview* necesitan la barrera.

## 7. Convenciones de commit y git

Commits locales en `feat/single-session`, mensajes en ingles, terminando con:
```
Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WT37UhkqzWMVeV3WpHqvFy
```
(la sesion del otro PC tendra su propia URL: usa la que indique el harness alli). Nunca force-push ni tocar master. Los PRs terminan con la linea "Generated with Claude Code" del harness.

## 8. Entorno del PC viejo y tooling (referencia)

- Windows 11, 15 GB RAM, 4c/8t. `JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'`, `ANDROID_HOME=$env:LOCALAPPDATA\Android\Sdk`. `~/.gradle/gradle.properties`: daemon, parallel, build cache, Xmx3g; configuration cache DESACTIVADO (`prepareApkFactoryTemplate` falla con el).
- Fuga de procesos conocida: servidores MCP `codegraph` (uno por sesion de Claude Code antigua) y los hijos de un `codex.exe` de larga vida (`cua_node`, `node_repl`, `cmd`, `conhost`) acumulan cientos de procesos y ~2.7 GB; se limpiaron con `single-validate\cleanup_procs.ps1` (mata por arbol los codegraph anteriores a hoy y los hijos de PID concreto con mas de 2 h). Si la RAM vuelve a ir justa, mirar eso primero. Los cortes "low on memory" del harness solo se desactivan arrancando Claude Code con `CLAUDE_CODE_DISABLE_BG_SHELL_PRESSURE_REAP=1`.
- MCP del PC viejo (puede no existir en el nuevo): `devmcp` (ECONNREFUSED hoy), `ghidra`, `codegraph`, Chrome automation, Gmail/Calendar/Drive/Canva conectores; `cubanjobs` (VPS: NO usar).
- Codex: cuota agotada el 2026-10-24 22:46 hasta 2026-10-30 10:41 y repuesta antes por el dueno (2026-10-25: "ya tiene cuota"). Puede volver a agotarse: probar con `echo ok | codex exec -m gpt-6-luna --skip-git-repo-check -s read-only -` y mirar "usage limit".
- Claude Code: los avisos programados (`ScheduleWakeup`) arrastran prompts con estado viejo; el bucle esta DETENIDO por la pausa. Si se reanuda el ciclo autonomo, actualizar siempre el prompt con el estado nuevo, cadencia 1800 s mientras haya etapa activa.

## 9. Bundle para el otro PC (fuera de git)

`jarvys-continue-bundle.zip` (creado en el Escritorio del PC viejo) contiene:
- `memory/`: la memoria persistente de Claude (35+ ficheros `.md`, indice `MEMORY.md`): decisiones, reglas del dueno, planes (Crew, memoria M5, proactivo Muse, contenedor Linux, tareas programadas, agente personal, modulos de presentacion...), firma del APK, setup local.
- `transcripts/`: la transcripcion COMPLETA de esta conversacion (`0971c147-ee28-406b-9ea2-db31aeb9c389.jsonl`, ~236 MB sin comprimir) por si hace falta buscar un detalle exacto.
- `single-validate/`: TODOS los briefs, informes, veredictos de jueces, scripts y logs de las etapas (los logs pesan ~70 MB; los `*-head-export` no se incluyen).
- `mascot-lab/`: el laboratorio de la mascota completo.
- `scratchpad/`: `engram-features.md` y los scripts de la pagina de la mascota que existan.
Dentro del repo (commit): `Continue.md` y `docs/continue/` con los briefs/informes/veredictos de texto (1.5 MB), `engram-features.md` y el laboratorio de la mascota.

## 10. Primer mensaje sugerido para retomar

"Lee Continue.md. Estado: pausa. Dime en 5 lineas donde estamos y espera mi aviso." Cuando el dueno diga "continua": reanudar por §3.2.
