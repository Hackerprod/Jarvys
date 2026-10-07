# Jarvys — hoja de ruta para la instancia arquitecta (VPS)

Escrito 2026-10-06 ~20:40 (hora VPS). Léelo entero antes de actuar. Repo git: `/root/Projects/Jarvys/app`. Código: `app/app/src/{main,full,play,test,testFull}/java/com/jarvys/agent/`. Informes de etapas anteriores: `/root/*_report.md` (l0, l1, lf1, lk1, i1b, i1c, th1, ux1, ux2, e1, st0..st3, m5, x1, crew_c*, ...). Diseños: `/root/kelivo_proot_analysis.md` (solo contexto; cláusula limpia: NO leer `/root/Jarvys/.tmp/kelivo` ni copiar código de Kelivo), `/root/scheduled_tasks_analysis.md`, `/root/muse_analysis.md`, `/root/proactive_agent_analysis.md`.

## ESTRUCTURA DEL FILESYSTEM (reestructurado 2026-10-07 por orden del dueño; HECHO)
```
/root/Projects/Jarvys/
  app/       repo git de la app (código en app/app/src/...)
  .tmp/      sources guía: kelivo (clean-room: NO leer), opencode-openai-codex-auth*, letta*, artemis, openclaw (copia; original en /tmp/opencode/openclaw)
  pending/   este README + briefs por implementar (UX4, ATT1, LK2, LA1...)
  tools/     error_endpoint.py (+error_reports.jsonl; servicio vivo :8090), jarvys-gradle.sh, build_apk_bigheap.sh
  JUDGE.md, lf1_report.md
```
El resto de la VPS (/root/*_report.md, scripts .py históricos, ~/.local/share/opencode, /tmp) se dejó intacto. Los informes históricos siguen citando rutas viejas `/root/Jarvys/...` = ahora `/root/Projects/Jarvys/...`. Los briefs de este directorio también citan `/root/Jarvys/app/...`: al enviarlos, sustituir con sed por `/root/Projects/Jarvys/app` y prefijar «RUTA DEL REPO CAMBIÓ: /root/Projects/Jarvys/app (antes /root/Jarvys/app)». Scripts: `bash /root/Projects/Jarvys/tools/jarvys-gradle.sh ...` y `bash /root/Projects/Jarvys/tools/build_apk_bigheap.sh`. Los `.start/.log` viejos están en app/.build-logs/.

## ESTADO AL SUBIR A GITHUB (2026-10-07 ~02:30 VPS)
- Repo git ahora en la RAÍZ `/root/Projects/Jarvys` (remote GitHub `Hackerprod/Jarvys`): `app/`, `pending/`, `tools/`; `.tmp/` ignorado. El `.git` se movió desde `app/` (historia de 2 commits conservada; commit 9d7064f = todo el trabajo acumulado). Desde ese commit la línea base de `git status` es 0: lo que opencode cambie aparece como diff nuevo (sustituye al antiguo «155»). Reglas de git para opencode siguen igual (no checkout/restore/reset/clean/stash/commit): commitea el arquitecto.
- Entregadas: UX3 (APK sha 70ad6dd8…83ee; Play 662/Full 733) y LK2 (APK Full sha bab03b97500683c9…a407; Play 662/Full 734; informe app/.build-logs/lk2_report.md — que NO se sube: está ignorado; resumen: lib-<sha> único para talloc, errno diagnosticable, sonda 'rootfs not implicated', solo 1 test LK2 [NV] matriz restante). Pendiente de probar en el móvil: linux_status + ejecutar python tras instalar LK2 (si falla, pedir probe.error/failure_detail con errno; sospecha secundaria: PROOT_TMP_DIR/TMPDIR ahora = tmp/lib-<id>).
- PARADO por orden del dueño hasta nueva indicación: siguiente = UX4 -> ATT1 -> LA1 (ver COLA).

## Roles
- Tú = ARQUITECTO: decides lo técnico, delegas la implementación a **opencode**, mides con tus propias herramientas, entregas APK al dueño e informas qué está verificado y qué no. El dueño habla español: responde en español muy breve ("caveman"); código y commits en normal.
- **opencode** = implementador. API en `http://127.0.0.1:4096` (usuario/clave en `/root/.opencode_auth`; NUNCA imprimas la clave), agente `build`, modelo `openai/gpt-6-luna` (NO usar `-fast`: duplica coste). Sesión actual: id en `/tmp/opencode_session_current.txt` (título Jarvys-LF1-s2). La sesión antigua `ses_f253c807cffe3geXRyvUjLcf95` está RETIRADA: no le escribas.

## Cómo delegar una etapa
1. Brief = fichero de `pending/` (fuente de verdad). Convierte a JSON: `python3 -c "import json,sys;t=open(sys.argv[1]).read();open(sys.argv[2],'w').write(json.dumps({'model':{'providerID':'openai','modelID':'gpt-6-luna'},'agent':'build','parts':[{'type':'text','text':t}]}))" pending/X.md /tmp/jarvis_x.json`.
2. SOLO enviar si opencode está ocioso: último mensaje assistant de `GET /session/<id>/message?limit=2&directory=<dir>` con `time.completed` y `pgrep -c java` = 0. ENVÍO (orden del dueño 2026-10-07: usar `--auto` para evitar cuelgues por permisos): `opencode run --attach` contra el servidor con `--auto` (auto-aprueba todo lo no denegado explícitamente; verificado: lee /root/*.md sin prompt). Comando (en segundo plano; la contraseña sale de /root/.opencode_auth, NUNCA la imprimas ni listes /proc/*/environ):
   `cd /root/Projects/Jarvys/app; U=$(cut -d: -f1 /root/.opencode_auth); PW=$(cut -d: -f2- /root/.opencode_auth); (setsid nohup /root/.opencode/bin/opencode run --attach http://127.0.0.1:4096 -u "$U" -p "$PW" --auto --dir /root/Projects/Jarvys/app -s <SESSION_ID> -m openai/gpt-6-luna --agent build "$(cat /tmp/<brief_listo>.md)" < /dev/null > /tmp/<etapa>_run.log 2>&1 &)`
   El proceso `run` termina cuando acaba el turno (su log queda en /tmp). Verifica a los 20 s que el último mensaje user es el brief. Ya NO hace falta aprobar permisos a mano, PERO `--auto` también aprobaría lecturas de `.tmp/kelivo` (clean-room): tras CADA etapa audita que ningún tool input de la sesión toque `kelivo` (`GET /session/<id>/message?directory=...` y grep de 'kelivo' en los inputs de tools) y avisa al dueño si lo hay. Sigue vigilando tools `running` muy antiguos.
3. Cada chequeo: `GET /permission?directory=<directorio de la sesión>` (¡IMPORTANTE!: con sesión creada con directory=/root/Projects/Jarvys/app los permisos pendientes SOLO aparecen con ese query `?directory=`; sin él la lista sale vacía y la sesión se queda bloqueada en silencio — perdimos 25 min el 2026-10-07; el reply también lleva `?directory=`). También comprueba parts de tool con state.status=running antiguo: es señal de permiso bloqueado. Aprueba SOLO con `POST /permission/<id>/reply {"reply":"once"}` si el filepath empieza por `/tmp/` y no contiene 'kelivo'. Cualquier otra cosa: retener y avisar al dueño. Si aparece acceso a `/root/Jarvys/.tmp/kelivo`: NO aprobar, `POST /session/<id>/abort` y avisar. Un `question` de opencode: abortar. Comandos del puente >30 s caducan: sleeps ≤25 s.
4. Cadencia de chequeo: 30 min con etapa activa (ScheduleWakeup 1800 s); más corto solo al construir el APK final.
5. NO empujes a opencode por lentitud sola. Tras avisos del dueño de "no inventar topes": ningún límite numérico inventado (rondas, mensajes, tamaños); solo frenos reales.

## Cómo medir antes de entregar (protocolo estándar)
- Build/tests: `bash /root/Projects/Jarvys/tools/jarvys-gradle.sh start|wait|summary|verify-suite <nombre> <PlayN> <FullN>` (se mueve a `Jarvys/tools/`). Final = `start <etapa>-final :app:testPlayDebugUnitTest :app:testFullDebugUnitTest --continue --rerun-tasks`, `wait`, `verify-suite`; luego `/root/Projects/Jarvys/tools/build_apk_bigheap.sh > .build-logs/<etapa>-apks-final.log 2>&1` (copia en `tools/`; si /tmp se limpió, usa `tools/build_apk_bigheap.sh`). APKs: `app/build/outputs/apk/{full,play}/debug/app-{full,play}-debug.apk` (+ `Jarvys.apk` Full, `Jarvys-play.apk`).
- Flakes conocidos (repetir UNA vez forzado, sin tocar src): ProactiveWorkManagerTest.punctualRequests…, TasksScreensComposeTest (Run now, lastTaskRemovalReturnsToSettings…, globalPauseBannerAndManualRun…, taskListShowsMixedStates…), MemoryNavigationComposeTest.missingExistingFileRouteFallsBackToMemoryHome, McpTransportClientStreamingTest.*, ProotProcessOutputTest.realProcessDrains…, AssistantReplyViewComposeTest.
- Comprobaciones: `find app/src NOTICE.md app/build.gradle.kts -type f -newer <apk>` vacío; XML de resultados (`app/build/test-results/test{Play,Full}DebugUnitTest/*.xml`: 0 fallos/errores/skips y mtime posterior a la última edición de src); md5 de permisos con `/opt/android-sdk/cmdline-tools/latest/bin/apkanalyzer manifest permissions <apk> | sort | md5sum`: **Full `24cee4f1a307f80d7aaae20f7c851674`, Play `65dd864b43d1da700cb024966ff1d0ba`**; `ComponentActivity exported=false`; dex Play: 0 `jarvys/agent/linux`, 0 `linux_exec`; ambos: 0 `org/robolectric`, 0 `androidx/work/testing`; paridad es/en (main 1161/1161 antes de UX3; `src/full/res/.../linux_strings.xml` 56/56); `build.gradle.kts` mtime 2026-10-05 15:22 (solo `useLegacyPackaging=true`); `Pattern SEGMENT` de `MemoryStore.java` sin cambios; LICENSE sha 8486a10c…; 0 'kelivo' en `src/main`; `git status --short | wc -l` = 155 (trabajo SIN commitear: NUNCA git checkout/restore/reset/clean/stash/commit). Leer los diffs relevantes y los informes de opencode con sentido crítico (revisa si cambió código de producto «para pasar tests», p. ej. ensanchar SEGMENT).
- Capturas visuales: Robolectric `@GraphicsMode(NATIVE)` + `View.draw` (ModalBottomSheet/Dialog no se capturan: dibuja el contenido aparte). Mírelas con Read antes de afirmar nada.
- Entrega: `cp` el APK Full a `/root/Jarvys-<etapa>-full.apk`; descargar con `vps_bridge_download` al scratchpad local; `Get-FileHash` y comparar sha con el del servidor; `SendUserFile` (attach, proactive); `telegram_send_document` UNA vez (suele dar timeout: decir «entrega no confirmada»); actualizar memoria. Informe al dueño: qué está MEDIDO vs [NV] (no verificado en el móvil).
- Lecciones: los tests deben usar la FORMA REAL de los datos/entorno (I1b cuerpo normalizado, I1c filesDir con symlink, LF1 tar con symlinks absolutos); nunca un test destructivo fuera de `java.io.tmpdir`; Robolectric `Os.lstat` sigue symlinks; ningún arnés que extraiga el tar real del rootfs (incidente VPS /run).

## COLA (orden de ejecución)
Estado a las ~20:40 VPS:
1. **UX3 — ENTREGADA 2026-10-07 00:55 VPS** (APK sha 70ad6dd8…83ee; Play 662 / Full 733 verdes; informe /root/ux3_report.md; capturas en /root/ux3_shots_arch/final/). Defectos pasados a UX4 (4c).
2. **LK2 — ENTREGADA 2026-10-07 02:15 VPS (ver ESTADO)**. (Texto original: Linux roto en el móvil tras actualizar el APK) (`pending/LK2_linux_launcher_fix.md`). Síntoma medido por el propio agente en el móvil: `linux_status` READY pero sonda COMMAND_FAILED «Cannot replace temporary libtalloc.so.2» (`ProotLauncher.kt` `plan()` ~47-73). Hipótesis NO verificada: symlink colgante `tmp/libtalloc.so.2` del build anterior. Python con LK1 aún NO confirmado. Enviar justo tras UX3.
3. **UX4** (`pending/UX4_composer_row_menu_model_sheet_user_markdown.md`): fila inferior del compositor [+][modelo][enviar], selector de modelo solo modelo + razonamiento (estilo ChatGPT), menú "+" con fila «Adjuntar» (Cámara/Fotos/Archivos, iconos centrados) y debajo el resto, quitar Crew del menú, y 4b: markdown en la burbuja del usuario.
4. **ATT1** (`pending/ATT1_attachments.md`): adjuntos reales (cámara/fotos/archivos) — hoy Cámara/Fotos solo muestran «no disponible».
5. **LA1** (`pending/LA1_linux_exec_allow_always.md`): «permitir siempre» para `linux_exec` (decisión del dueño). Su texto dice «después de UX2»: ignóralo, va después de ATT1 (o antes si el dueño lo pide). Anteponer cabecera corta de reglas como en los demás briefs.
Una etapa a la vez; no envíes la siguiente hasta medir y entregar la anterior.

## Decisiones/preguntas abiertas con el dueño
- Probar en el móvil: `curl -sI https://pypi.org`, `pip --version`, `apt update` (efecto LK1: CA bundle HTTPS) — pendiente de respuesta.
- Los dos avisos de privacidad de Memory/Reflection (MainActivity ~1755/1776) como bottom sheet: sin decidir.
- Tono del azul del tema (acero vs más vivo) e icono de la app aún verde (`ic_jarvys.xml` #39D0B2): sin decidir.
- Fila «Skills» en el menú "+": se mantiene; el dueño puede pedir quitarla.
- VPS degradada (systemd/docker CLI rotos; `api.service` en crash-loop): el dueño NO quiere reiniciar (agentes usándola); credenciales temporales de VNC/root a rotar por el dueño. NO hagas reinicios.
- Aparcado por el dueño (no encolar ni recordar hasta que lo pida): catálogo remoto de conectores.
- Pendientes no encolados: M5 mejoras (evidencia/preferencias), proactivo P5, contenedor Linux E4+ (system prompt/tools ya hechos en L1/LK1), tareas programadas ST4-ST6 si el dueño las pide.

## Antes de PUBLICAR en Google Play (no antes)
- Restaurar la sección «Open-source licenses» en Ajustes (quitada a propósito) y añadir PRoot/Ubuntu a NOTICE.
- Revisión legal humana de la limpieza clean-room de Kelivo; revisión de la política de Play sobre ejecutables descargados (el flavor `play` no incluye Linux).
- Nada puede depender de la VPS del dueño.

## Invariantes de producto
- Herramientas `linux_*` solo en el chat principal, flavor Full, profundidad 0 (excluidas de Crew, proactivo, tareas, memoria y subagentes); Play usa el stub `com.jarvys.agent.flavor.FlavorLinuxTools`.
- Sin UI de contador de tokens. Sin usuarios de prueba. Sin webs/Chrome salvo que el dueño lo pida. Sin topes numéricos inventados.
