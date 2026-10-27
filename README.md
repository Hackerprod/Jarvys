## Rama de desarrollo: sesión única — 26-10-2026

La continuación vigente parte de `feat/single-session` (`42560900`), no del estado v96 histórico descrito abajo. S1–S5 constan terminados en el traspaso; S6 sigue **WIP**, con captura de evidencia y admisión por invocación en revisión. S7/S8 y la paridad funcional interna con Engram requieren validación posterior independiente.

En el checkpoint inicial con SDK autorizado, compilaron Full/Play Java/Kotlin y KSP exportó Room 5. Los 38 selectores afectados pasan: 477 ejecuciones Full y 277 Play, cero skips; regresiones relacionadas completadas: total 1.088 ejecuciones PASS (60 clases), cero skips. No son una suite global ni prueban paridad Engram. **No es una entrega operativa ni hay APK nuevo.** Véase [continuidad](Continue.md), [reporte verificable](docs/continue/single-validate/stage-S6-cloud-report.md), [contratos de admisión](docs/continue/single-validate/S6-admission-contract.md) e [inventario de herramientas](docs/continue/single-validate/S6-tool-inventory.md).

El checkpoint posterior acota la consulta del narrador y añade gate compartido de escritura automática de memoria: tickets por fase, invalidación OFF→ON y exclusión hasta commit/rollback. Validación focal: 1.010 ejecuciones PASS, 970 casos únicos contando flavor (485 métodos, 57 clases), cero skips; el manifiesto distingue regresiones anteriores al último ajuste de factory. No es una suite global ni cierre S6. En ese checkpoint seguían pendientes conexión/outbox, Coding asíncrono y otros productores. [Contrato y límites](docs/continue/single-validate/S6-memory-write-gate-contract.md) · [Resultados](docs/continue/single-validate/S6-memory-gate-test-results.json).

El bloque posterior de conexión añade destino Room real, fence de instancia, proyección exacta, poda acreditada y bootstrap independiente de Room. Full y Play pasan 140 casos por sabor (138 métodos nominales, nueve clases; boundary SDK24/34 incluido), cero skips; schema 6 generado y migración real validada. Ese checkpoint era aislado; su integración posterior se describe a continuación. [Resultados focales](docs/continue/single-validate/S6-connection-projection-test-results.json).

El cutover posterior ya integra Registry/UI/readers, callbacks capturados y observación causal exacta. Validación final: **544 Full + 411 Play PASS**, 955 ejecuciones (51/45 clases), cero skips; no suite global ni cierre S6. DEFAULT espera bootstrap sin resucitar una baja. Una dependencia de orden en el fixture de tasks se reprodujo en aislación y corrigió siguiendo el worker real, sin cambiar sus datos/asertos. Pendientes Coding asíncrono e inventario de productores. [Resultados y límites](docs/continue/single-validate/S6-connection-cutover-test-results.json).

Foundation posterior Coding/recibos locales: 169 Full + 165 Play PASS (334 ejecuciones, cero skips), metadatos/codec aislados y semántica de aceptación local sin afirmar misión terminada. Todavía sin producer/ASYNC opt-in ni prueba de commit durable. [Resultados y límites](docs/continue/single-validate/S6-coding-foundation-test-results.json).

CentralWorkspace ya integra admisión explícita de cinco productores, recibos locales tipados y fuente Central→assignment atómica. Validación final focal **677 Full + 673 Play PASS** (1.350 ejecuciones, 57/56 clases, cero skips), con revisión independiente; aún sin Coding ASYNC ni cierre S6. La prueba real del runtime con memoria OFF conserva auditoría causal técnica, no narración/semántica automática: OFF no significa borrar el ledger técnico. [Resultados y límites](docs/continue/single-validate/S6-central-workspace-test-results.json).

Procedencia Crew integrada en la ruta real: snapshot RAW/EFFECTIVE preparado, confirmación durable del STARTED exacto y witness de owner/ciclo/llamada; contexto de inputs plural con PARTIAL explícito. La raíz del assignment es historia, no causa exclusiva del comando. Ampliación **standalone offline calibrada: 851 Full + 847 Play PASS** (1.698 ejecuciones, 79/78 clases, cero skips); focal Gradle y calibración independiente de 21/21 por sabor. Revisión independiente conforme; código `113cd8de`. Sin generación Room inventada, sin Coding ASYNC ni cierre S6. [Resultados y límites](docs/continue/single-validate/S6-crew-source-report.md).

Alineación posterior del envelope Coding con Crew: contexto plural exacto, raíz Central histórica separada de generación técnica y política semántica independiente. Compilación Full/Play y **42 + 42 pruebas frescas offline PASS**, cero skips; metadatos solamente, sin journal durable conectado ni ASYNC. [Resultados y límites](docs/continue/single-validate/S6-coding-alignment-test-results.json).

El writer del journal Coding ya está extraído e integrado en `persist`, conservando schemas 1/2: candidato exacto, token IO sólo tras sync/rename/dirsync e incertidumbre explícita ante fallos. Full/Play compilan; **38 Full + 6 Play PASS**, cero skips. Fixtures legacy se corrigieron tras reproducir sus fallos en baseline limpio, sin debilitar asertos. No conecta aún journal schema3/Room/lease ni activa ASYNC. [Reporte](docs/continue/single-validate/S6-coding-journal-io-report.md).

El respaldo de alineación Coding está confirmado en `continue/s6-cloud` (`76867431`, fuente `fd91c50b`); la extracción IO posterior aún no se afirma publicada. Conserva pruebas existentes restringidas como en remoto y un patch mínimo limpio permite reconstruir la fuente validada exactamente. El árbol de respaldo requiere ejecutar sus instrucciones de restauración antes de compilar; no subir directamente los archivos restaurados sin autorización de publicación. Los checkpoints no sobrescriben historial. [Manifiesto de validación](docs/continue/single-validate/S6-validation-manifest.md).

---

<div align="center">

## Antecedente histórico: recuperación y animaciones v96

Este corte reconstruye de forma explícita las fuentes v94/v95 que no sobrevivieron al reinicio del entorno, desde el respaldo remoto v93 y el APK v95 firmado conservado. No se afirma identidad de fuente con las entregas anteriores. Recupera los acuses, el mapa de mascota, la fila Agente/Agent y la lista propia de Workspaces; añade correcciones de descarga, rutas canónicas, levitación con Rive y ausencia de flash al pulsar. Validación integrada, APK, firma y entrega de v96 pendientes. [Contrato y procedencia](docs/RECOVERY_ANIMATIONS_V96.md).

## Última entrega confirmada: Workspaces v95

El 13-10-2026 a las 06:24:55 UTC se enviaron las dos partes del ZIP de v95. APK ARM64: 25.636.585 bytes, SHA-256 `04f2b91a4741aa9a05d3f893a151f827133343216ff8996fb5011fb941522205`, firma D7. Incluye Idle local, otros estados remotos y lista propia de Workspaces. El usuario confirmó que Idle parpadea; informó que no levita y no ve las otras animaciones. Las correcciones actuales se validan como fuente nueva. v93 no tuvo entrega independiente: sus acuses/plurales se incluyeron en v94.

## Entrega anterior: coordinación durable UX43 v92

v92 (`1.2.85-COORDINATION`) conserva preguntas, hallazgos y bloqueos ligados a la misión/ciclo del trabajador. El central puede leerlos con identidad y hash exactos y responder a una solicitud que siga activa. El indicador nativo reúne los avisos útiles; la coordinación interna sigue fuera del chat visible. Aceptación local de una respuesta no significa consumo ni resolución, y reabrir no repite trabajo. [Contrato](docs/UX43_COORDINATION.md).

Validación afectada: 415 casos Full (412 conservados por igualdad exacta y 3 reemplazos frescos) y 415 Play frescos, 39 clases por sabor. Única FullRelease/Vital, revisión exacta, firma D7 y lectura persistida aprobadas. Módulos Factory/Core/runtime/SDK retenidos por igualdad. Lint conserva 46/37 errores históricos y añade tres avisos de pluralización por sabor, sin nuevos errores; no es una regresión universal ni lint limpio.

Envío ZIP aceptado el 12 de octubre de 2026 a las 23:24:59 UTC: extraer antes de instalar. ZIP: 19.790.107 bytes, SHA-256 `e4f61d3727b1bebfbec4c7410596a25da76f27fcc64f2dbd49a1c2d4403a5bdd`. APK ARM64 interior: 21.347.987 bytes, SHA-256 `ad0bbfb6c2b232db57664a52b019c35929b0c02459be3625b2f8e9d1f39ef284`. Instalación y comportamiento físico/modelo real pendientes.

### Entrega anterior: mascota central ampliada v91

v91 (`1.2.84-MASCOT-SCALE`) amplía la mascota central de 30 a 84 dp (2,8×), con cabecera de 96 dp más insets Android. Las 24 pruebas focales Full/Play pasan; FullRelease y Vital, auditoría exacta, firma D7 y lectura persistida están aprobados. Los PNG, launcher, otros bots y animaciones siguen como en v90. Lint general de v90 retenido como histórico, sin nueva ejecución ni afirmación de regresión universal.

Envío aceptado el 12 de octubre de 2026 a las 21:37 UTC mediante ZIP: extraer antes de instalar. ZIP: 19.758.814 bytes, SHA-256 `8271af8d88a99907ae6d1341550fa8b0d0e57fd556050f35b520799de1a67393`. APK ARM64 interior: 21.311.123 bytes, SHA-256 `ef4c710d79582c43dda003eff857eb5f75617de0a7e2859310cb1a8e7fec8557`. Instalación/aceptación física pendientes. [Contrato de identidad](docs/MASCOT_STATIC_IDENTITY.md).

### Entrega anterior v90

v90 (`1.2.83-MASCOT`) está validada y firmada con D7. Incorpora la imagen exacta proporcionada como icono y mascota estática de Jarvys, con quince derivados de launcher reproducibles. El robot animado anterior queda inactivo incluso con consentimiento previo; las mascotas de otros bots y Factory siguen intactas. La animación triste entregada por separado todavía no se integra. [Contrato de identidad](docs/MASCOT_STATIC_IDENTITY.md).

Envío aceptado el 12 de octubre de 2026 a las 20:41 UTC mediante ZIP: extraer antes de instalar. El APK superaba el límite de adjunto directo de 20 MiB; el contenedor conserva exactamente el APK firmado. ZIP: 19.757.301 bytes, SHA-256 `e6440d7ca51b6aa31293168d4d2be4257adaef37d80360c9e50951179b2a41da`. APK ARM64 interior: 21.311.123 bytes, SHA-256 `c1fb78dd5b7fc6651b459f34eef9fac6d185de1670778886e0b6b6c38f7d8710`. Ambos contenidos persistidos verificados.

Cobertura afectada: 68 Full (62 retenidas por igualdad y 6 sustituciones frescas) y 68 Play frescas. Tres APK y firma auditadas independientemente. Lint byte-idéntico a v89, con sus 46 errores Full y 37 Play históricos; no es lint limpio ni una nueva regresión universal. Instalación, launcher/OEM y splash físicos sin verificar.

El hotfix v89 sigue incorporado: oculta fases/conversaciones internas live/al reabrir, conserva respuestas y errores útiles, mejora diagnósticos y admisión Workspaces. La causa concreta del fallo reportado del teléfono sigue sin confirmar. [Contrato del hotfix](docs/RUNTIME_CHAT_HOTFIX.md). Continúan las capas restantes UX43: comunicación durable restante, memoria por espacio y proactividad; UX34 conserva sus gates y CrewAI permanece al final. [Contrato UX43](docs/UX43_RESULT_DELIVERY.md).

UX45 v86 permanece incorporado: diagnósticos locales acotados de excepciones Java/Kotlin y fallos de proveedor, consultables mediante `app_diagnostics`, sin reporter HTTP automático. Los metadatos seleccionados al consultar al modelo entran en su conversación normal. No garantiza capturar ANR, fallos nativos, OOM ni toda excepción recuperada. [Contrato de diagnóstico local](docs/UX45_LOCAL_DIAGNOSTICS.md).

La autonomía configurable UX44 v85 permanece incorporada: conectores nativos y Google, MCP, Linux/Coding, bots, identidades, tareas y memoria conservan decisiones guardadas, revocación y permisos Android/OAuth reales. [Alcance y límites UX44](docs/UX44_ACTION_POLICY.md).

  <img src="app/artwork/jarvys-app-icon-source.png" width="150" alt="Jarvys mascot: white character with blue-violet eyes and a glowing star" />
  <h1>Jarvys</h1>
  <p><strong>An Android workspace for AI agents, device automation and app creation.</strong></p>
  <p>Chat with a captain. Delegate to a Crew. Build with Coding. Keep control of the actions.</p>
  <p>
    <img alt="Android 7.0 and newer" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" />
    <img alt="Kotlin and Java" src="https://img.shields.io/badge/Kotlin_%2B_Java-Android_native-7F52FF" />
    <img alt="UI: Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack_Compose-4285F4" />
    <a href="app/LICENSE"><img alt="License text: AGPL v3" src="https://img.shields.io/badge/License-AGPL_v3-blue" /></a>
  </p>
  <p><a href="#capabilities">Capabilities</a> · <a href="#quick-start">Quick start</a> · <a href="#architecture">Architecture</a> · <a href="#apk-factory">APK Factory</a> · <a href="#contributing-with-agents">Agent guide</a> · <a href="Pending.md">Roadmap</a></p>
</div>

---

> Factory v84 host validation is complete on source `f7794461ed0201dc312b1b6d128b067759e5bec2`, with fresh Full Debug, Play Debug and Full Release APKs (84 / `1.2.77-FACTORY-AUTONOMY`). Independent exact artifact review passed for all three APKs and the template. The retained-D7 signed ARM64 Full Release APK was sent successfully on 11 October 2026 at 22:45 UTC (20,266,643 bytes; SHA-256 `b4b2f38d70932ce333bdba0627b519006f3aa1b4bbd61643fc18f8a77f794a3d`). Download, installation and physical-device acceptance are not established. The packaged-DEX runtime exposes 33 generic capabilities, 134 SDK methods and 65 per-app/per-operation policy actions, defaulting to ALLOW_ALWAYS with configurable ASK/DENY. It includes generic networking/ICMP, media, notifications, speech, sensors, encrypted secrets, sharing, document streaming, NFC NDEF, BLE central, widgets and finite HTTP jobs. No external Android compiler is required per generated app. Android system grants, identity, isolation, lifecycle correctness and explicit resource budgets remain; hardware and system-service availability vary. Local inference still needs an engine/model/license/ABI decision. See [Pending.md](Pending.md) and [Factory guidance](app/APK_FACTORY.md) for scope and technical limits.


The optional [multiview ICMP ping example](app/examples/factory-ping-multiview/) demonstrates native ping, local history, notifications and contact-assisted sharing. It is a capability-composition fixture, never a default project or hardcoded application workflow. Its offline controller tests pass; device/WebView acceptance remains pending.

## What is Jarvys?

Jarvys brings a tool-using AI workspace to Android: persistent conversations, configurable model providers, specialist agents, project files, connectors, scheduled work and optional phone interaction. Its native interface is built with Kotlin, Java and Jetpack Compose.

The agent runtime runs in the Android app. **This does not mean the language model runs locally:** inference, web research, image generation and connected services can send data to their configured providers. Features depend on the selected model, account, distribution flavor, Android permissions and available runtime tools.

### Current project status

This is an actively developed project with incremental, evidence-backed checkpoints.

The v84 batch has reconciled host coverage: 4,000 Full and 3,602 Play tests passed; Core Debug/Release each have 2,706 passes and one explicit SDK24 assumption skip; APK runtime has 93 passes per variant. This combines fresh affected runs with reviewed unchanged-input results and preserves earlier failed runs. Fresh Core/template lint has zero errors and 11/22 acknowledged warnings. Host lint retains existing debt (46 Full / 37 Play errors); this is not a lint-clean or physical-device acceptance claim. Software validation, signing and accepted attachment delivery are complete for this batch; physical/OEM acceptance and unrelated UX/CrewAI work remain open.

v82 gyroscope passed staged host validation: 2,604 fresh JUnit cases, SDK42, exact retained
lint diagnostics and independent three-APK/DEX/template audits. It adds bounded rad/s capture
without new permissions. Signed ARM64 test APK delivery was accepted; persisted Library bytes
were independently verified. Physical-device acceptance remains open.
[Scope and evidence](recovery/factory-gyroscope/README.md).

| Checkpoint | Status |
| --- | --- |
| **v72 · Factory maps and dialer** | Completed earlier delivery; host tests and independent APK audit passed. [Evidence](recovery/factory-actions/README.md) |
| **v73 · Factory email and SMS editors** | Completed earlier delivery: 7,390 host tests, SDK20 and independent APK audit passed; signed test APK attachment accepted. Opening an editor is not sending a message. [Scope and status](recovery/factory-editors/README.md) |
| Physical Android acceptance | Still open for the relevant recent features. A host test, signed APK or accepted file delivery does not prove installation or phone behavior. |
| **v74 · Contact selection** | Completed earlier delivery: 7,832 tests, SDK22 and independent three-APK audit passed; signed test APK attachment accepted. One human-selected phone/email with native exact-value approval. Calendar remains separate. [Scope](recovery/factory-contacts/README.md) |
| **v75 · Factory calendar editor** | Completed earlier delivery: 7,978 fresh tests, SDK24, exact lint and independent three-APK audit passed; signed ARM64 test APK attachment accepted. Strict ACTION_INSERT with native review; no calendar database access or automatic saving. [Scope and evidence](recovery/factory-calendar/README.md) |
| **v76 · Private SQLite** | Completed earlier delivery: 8,122 fresh tests, SDK27, exact lint and independent three-APK audit passed. Typed CRUD, atomic bounded migrations, private installed storage and ephemeral preview; signed ARM64 test APK attachment accepted and Library bytes independently verified. [Scope and evidence](recovery/factory-database/README.md) |
| **v77 · Local presentation** | Completed earlier delivery: 8,234 fresh tests, SDK30, exact lint and independent three-APK audit passed. Per-app theme and advisory orientation, no host broker/permissions. Signed ARM64 test APK attachment accepted; Library bytes independently verified. [Scope and evidence](recovery/factory-presentation/README.md) |
| **v79 · Foreground sensors** | Staged host validation passed: prior full app suites retained; corrected-source Factory integration, complete runtime/core, SDK35 and exact lint passed. Three-APK/control-flow audit passed; signed ARM64 test APK attachment accepted and Library bytes independently verified. No physical sensor capture or installation claim. [Scope and evidence](recovery/factory-sensors/README.md) |
| **v78 · Modular Factory guidance** | Completed earlier delivery: 8,264 tests, SDK30, exact lint and independent three-APK/control-flow audit passed. Bounded always-present Coding core and seven versioned references; no new Factory capability. Signed ARM64 test APK attachment accepted and Library bytes independently verified; installation and physical behavior unverified. [Scope](recovery/factory-guidance/README.md) |
| **v80 · Local strong biometrics** | Complete fresh host validation: 8,776 tests, SDK41, exact controlled-baseline lint and generated-profile/signature audit passed. All three final APK audits passed. ARM64 D7 v2/v3 delivery accepted 2026-10-11 03:34:08 UTC; signed payload and independently retrieved Library bytes verified. Physical installation/authentication remain unverified. API30+ strong human system prompt; local receipt authorizes no other action. Only selected biometric profiles add normal USE_BIOMETRIC. [Scope and evidence](recovery/factory-biometrics/README.md) |
| **Factory test infrastructure** | Exhaustive subset assertions retained with reviewed compositional wire validation. The two bridge cases measured 38.437/42.347 s; 48 focused tests and exact five-diagnostic runtime lint passed. Production and delivered v80 APKs unchanged; no new full aggregate or device claim. [Proof and measured scope](recovery/factory-matrix/README.md) |
| **v81 · Packaged local UI** | Host-validated fixed Pure.css 3.0.0 catalog, full license notices, responsive CSS and usable offline example. 2,398 fresh affected tests + SDK41, exact lint and independent three-APK audit passed; unrelated host suites remain historical. ARM64 D7 v2/v3 delivery accepted 2026-10-11 05:19:24 UTC; signed payload and retrieved Library bytes independently verified. No successful browser/WebView rendering or physical installation claim. [Scope and evidence](recovery/factory-local-ui/README.md) |
| Broader Factory roadmap | The scoped v84 F1/F2 batch and bounded F3 NFC NDEF, BLE central, widgets and finite HTTP jobs are implemented, host-validated, independently audited and delivered. This includes platform TTS/recognition, recording, sensor subscriptions, secrets, scheduled/action notifications, inbound sharing, document/media streaming, adaptive icons and expanded packaging budgets. Physical/OEM acceptance and the local-inference engine/model/license/ABI decision remain open; universal Android API coverage is not claimed. |

This status is a **2026-10-11 snapshot**, not a live build badge. [Pending.md](Pending.md) is the current work queue; feature-specific evidence records distinguish passing, failed, interrupted and unrun checks.

## Capabilities

### 💬 Conversations and model providers

- Persistent chats, Markdown, selectable content, attachments, reactions, model selection and visible tool activity.
- English and Spanish interface resources, light/dark presentation and native navigation.
- OpenAI Codex sign-in, OpenAI API keys, OpenRouter, and custom **OpenAI Chat Completions-compatible** endpoints. Compatibility is a specific protocol mode, not a promise that every model or server supports every tool.
- Context budgeting, conversation compaction, run checkpoints and explicit partial/interrupted outcomes.
- STOP controls and a separate **Stop and send this message** flow for eligible active chats. Cancellation cannot undo an external effect that already happened.
- Assistant speech controls; Factory also exposes normal platform TTS through its configurable operation policy.

See the [main-agent contract](app/MAIN_AGENT.md), [tool activity design](app/TOOL_ACTIVITY.md) and [continuous mission lifetime](app/CONTINUOUS_MISSIONS.md).

### 🤖 Crew, bots and skills

- A captain delegates scoped work to specialists; mission cards show task titles, activity, messages, results and current status.
- Configurable bot profiles, selected tools and skills, explicit follow-up, checkpoint recovery and cancellation.
- Built-in Coding and Android-navigation guidance, plus skill creation/import and on-demand skill loading.
- Explicit read-only Coding missions restrict runtime capabilities and project writes; a prompt alone does not grant or revoke tools.
- Custom bot icons and bounded local mascot generation. Jarvys animation is **experimental, opt-in per app session**; host rendering is not physical Android performance evidence.

See [Crew tasks](app/CREW_TASKS.md), [Coding](app/CODING_AGENT.md), [bundled skills](app/app/src/main/assets/skills/) and [bot mascots](app/BOT_MASCOTS.md).

### 🛠️ Coding and project work

- Conversation-bound project files, text search, edits and patches, project adoption, mutation receipts and conflict checks.
- File hashes, scope revisions and writer ownership help reconcile partial changes instead of blindly repeating them.
- Full builds can expose prepared Linux/PRoot project execution, bounded jobs, logs and cancellation. **Each Coding command currently requires its own exact approval.** Persistent “Allow always” approval is roadmap work, not a current capability.
- Play builds keep applicable file-oriented Coding workflows but have no Linux execution backend.
- Conditional project image generation/editing through the configured Codex backend, with scoped image imports and output receipts.
- APK Factory tools for supported web-based Android projects, described below.

PRoot is a shared writable environment, **not a security sandbox**. Coding does not acquire Git, a browser, ADB or arbitrary host access just because a task asks for them. [Engineering contract →](app/CODING_AGENT.md)

### 📁 Files, images and web previews

- Conversation-owned file/image attachments and generated artifact delivery with native Download, Share and Open flows.
- Local HTML/CSS/JavaScript previews with captured assets, separate origins and bounded resource access.
- Static page thumbnails generated from the actual captured page, with placeholders when rendering is unavailable.
- Mobile viewport support, while preserving authored viewport declarations and explicit wide layouts.
- Provider-backed image generation/editing when the corresponding account and capability are available.

WebView-dependent rendering, keyboard behavior and network-policy enforcement require device checks. Preview headers and host tests are **not proof of universal network isolation**. [File delivery](app/FILE_DELIVERY.md) · [WebView acceptance](app/WEB_PREVIEW_ACCEPTANCE.md)

### 🔌 Research and connected services

- Web search through the built-in Bing route or optional Exa, plus bounded webpage retrieval. Public search and service availability can change.
- MCP server configuration, tool discovery/selection, authentication flows and approval-aware invocation.
- A service catalog with GitHub, Notion, Linear, Atlassian, Asana, Sentry, Vercel and Canva entries. An entry is a setup route; it does not establish account access or guarantee every remote tool works.
- GitHub repository, issue, pull-request and Discussion workflows; optional Actions tools; bounded atomic multi-file commits and exact-commit check queries through the native bridge.
- **Full flavor:** Gmail search/read, attachments, draft/send and management operations; Drive search/read, downloads/exports and scoped file/folder writes. Gmail destructive scopes and Drive writes have separate controls.

Connected content is untrusted input. Discovered tools, enabled capabilities and OAuth grants are separate from permission to perform an action. [GitHub](app/GITHUB_CONNECTOR.md) · [Google Workspace](app/GOOGLE_WORKSPACE.md) · [Gmail management](app/GMAIL_MANAGEMENT.md)

### 📱 Phone interaction and scheduled work

- Optional AccessibilityService-based device observation and interaction: screen hierarchy, supported screenshots, taps, swipes, text input and navigation.
- Native connectors for contacts, calendar, location, notifications and intent-based communication/actions, subject to their individual permissions and policies.
- Full-only SMS and call-log functionality, distinct from opening a composer or dialer.
- One-time, daily/weekly/monthly and interval-based scheduled tasks with persisted run state, time-zone handling, pause controls and result notifications; proactive processing of supported events with bounded read-only background tools.
- Long-running missions have no application-wide elapsed-time cutoff, while individual network/process operations, context budgets and no-progress controls remain bounded.

Android can suspend or terminate the app. Scheduling is not an exact-time delivery guarantee, and removing a mission timer does not guarantee uninterrupted operation for days. Accessibility must be enabled deliberately; some screenshots/actions depend on Android version and device behavior.

### 🧠 Memory and continuity

- Memory notes, reflection, search and model context are **conversation-scoped by default**.
- Older unclassified notes are preserved for native review rather than silently injected into unrelated chats.
- Sharing a personal note across current and future conversations requires native review of its exact content/version; changed content requires renewed review.
- Shared snapshots are read-only to ordinary agent tools and can be revoked for future projection. Revocation does not erase text already supplied in an earlier request.
- Protected memory review surfaces prevent Jarvys automation from providing its own consent.

This is semantic context isolation, separate from project/file boundaries. A central cross-space assistant is still roadmap work. [Memory contract →](app/MEMORY_SCOPE.md)

## APK Factory

v77 adds typed per-app theme and orientation requests. Fresh host validation and independent three-APK audit passed; the signed ARM64 test APK attachment was accepted and Library bytes independently verified. Android may ignore orientation requests; no device/WebView behavior is established. See [the presentation contract](app/APK_FACTORY.md#presentation-preferences-ux42-f1-v77).

**Turn a supported local web project into an Android APK through Coding.** Factory packages a reviewed native runtime and bounded HTML/CSS/JavaScript assets; it is not a general-purpose arbitrary Android source compiler.

Building with Factory requires **Android 8/API 26 or newer** in either Jarvys flavor. Generated apps support **Android 7/API 24 or newer**. Factory packaging itself does not require PRoot.

```text
Project files + factory.json
         │
         ▼
Inspect → Build unsigned APK → Apply signing policy → Sign
         │                                           │
         └── Private preview / simulated tests       └── Apply install policy / Android flow
```

### Earlier implemented slices

These rows summarize earlier capability slices through v80. Their original boundaries are historical; the v84 summary above and current Factory contract describe the expanded APIs and configurable policy.

| Area | What exists | Important boundary |
| --- | --- | --- |
| Packaging | Closed project schema, local assets/icons, authenticated template, manifest/resource/DEX checks and provenance receipts | Unsupported manifest nodes and arbitrary native code are rejected |
| Signing | Configurable signing policy, version/app identity continuity, verified signed payloads, recoverable new identities and encrypted backup/import | Backup/import is an explicit user interaction; older non-exportable keys stay non-exportable |
| Preview and tests | Shared runtime core, isolated test storage, exact preview snapshots, simulated effects and bounded receipts | Preview success does not establish live native effects |
| Installation | Configurable install policy tied to the exact signed artifact and durable recovery | Full flavor; Android user action/permission and actual install result remain separate |
| Documents and sharing | SAF document operations, opaque handles and bounded native file sharing | Require a compatible authenticated Jarvys host; ALLOW advances automatically, ASK reviews once, DENY blocks |
| Camera/photos | Bounded capture/picker workflows with configurable host policy and Android selection/grants | Camera compatibility varies; selected original image metadata can be retained |
| Audio | Local bounded PCM16 WAV playback | Not synthesis, microphone recognition or cloud voice |
| Browser, maps and dialer | Typed HTTPS, map and dialer launches with reviewed destinations | Dispatch does not prove page display, navigation, calling or external-task closure |
| Contacts | One selected datum; no second native approval under ALLOW | Host gates and independent APK audit passed; signed test APK delivered; no agenda enumeration or permission requests |
| Calendar editor | Typed event prefill with configurable host policy | Opens an external editor; never saves automatically or grants calendar database access |
| Private SQLite | Standalone typed CRUD, bounded atomic batches and adjacent create-table/add-nullable-column migrations | No broker or query-by-query consent; private no-backup data, ephemeral preview, no raw SQL/path/export API; physical persistence remains unverified |
| Local presentation | Per-app system/light/dark theme and system/portrait/landscape orientation requests | Private preferences, controlled Activity recreation, preview unavailable; orientation and WebView CSS require physical acceptance |
| Foreground sensors | Bounded installed light/accelerometer capture and cancel | No background or new permission; physical timing, cleanup and installation remain unverified |
| Local strong biometrics (v80) | API30+ framework prompt, coarse availability and explicit cancellation | Human-only prompt; receipt authorizes no other action, cancel proves no closure, preview unavailable; physical acceptance remains open |
| Email/SMS editors | v73 adds typed editor launches with one recipient and reviewed fields | Host gates passed and signed test APK delivered; external apps may sync drafts; no automatic sending or delivery claim |

**Not every generated app is standalone.** Local runtime features differ from brokered native capabilities. The latter require a compatible Jarvys host, exact caller/signing identity checks and the configured per-operation policy: ALLOW advances automatically, ASK reviews once and DENY blocks. Generated manifest permissions follow the selected capabilities; biometrics contributes normal USE_BIOMETRIC. Neither closed manifest scope nor local authentication makes the host or selected third-party app permissionless or offline.

The scoped v84 batch is delivered, including normal platform TTS/voice and the bounded F3 contracts. Device-dependent acceptance and the local-inference engine/model choice remain open; delivery does not imply universal Android API support or completion of unrelated roadmap items.

[Full contract and project schema](app/APK_FACTORY.md) · [Factory skill](app/app/src/main/assets/skills/com.jarvys.apk-factory/SKILL.md) · [TTS design gate](recovery/factory-tts/README.md) · [Roadmap](Pending.md)

## Full and Play flavors

| Capability | Full | Play |
| --- | :---: | :---: |
| Core chat, Crew, memory and supported file workflows | ✓ | ✓ |
| Applicable Coding file tools and Factory packaging | ✓ | ✓ |
| Prepared Linux/PRoot execution | ✓ | — |
| Native Gmail/Drive integration | ✓ | — |
| Full-only SMS/call-log connectors | ✓ | — |
| Factory package-install flow | ✓ | — |

Both inherit common permissions and capabilities; **Play is a build flavor name, not a claim of Google Play publication or approval**. Core accessibility, overlays and sensitive native connectors still need careful user configuration. Review the [main manifest](app/app/src/main/AndroidManifest.xml), [Full manifest](app/app/src/full/AndroidManifest.xml) and actual merged manifest of the APK you distribute.

## Quick start

### 1. Prepare the toolchain

- A supported JDK for Android Gradle Plugin 8.13.2; recorded host validation uses **Temurin JDK 21**.
- Android SDK **Platform 36** and **Build-Tools 35.0.0**, with applicable SDK terms accepted through the official workflow.
- The checked-in **Gradle 8.13 wrapper**. Android Gradle Plugin and Kotlin versions are pinned in [app/build.gradle.kts](app/build.gradle.kts).
- Network access to official Gradle/Maven/Google dependency repositories on a fresh build. Node.js is needed for the Factory JavaScript tests; Python 3 is used by the optional packaging helper.

```sh
git clone https://github.com/Hackerprod/Jarvys.git
cd Jarvys/app

# Set these to your own installed toolchain locations.
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew --version
./gradlew --no-daemon -PunsignedBuild=true \
  :app:assembleFullDebug :app:assemblePlayDebug
```

Unsigned outputs are under:

```text
app/app/build/outputs/apk/full/debug/app-full-debug-unsigned.apk
app/app/build/outputs/apk/play/debug/app-play-debug-unsigned.apk
```

Those paths are relative to the repository root. From `app/`, the optional `./build_apk.sh --unsigned` helper also copies both outputs to `Jarvys-unsigned.apk` and `Jarvys-play-unsigned.apk` and writes an unsigned receipt.

### 2. Sign and install deliberately

Development Debug outputs are intentionally **unsigned** and cannot be installed as-is. Signing is a separate distribution step; keys are not part of the repository. Use only the identity approved for your distribution and verify the resulting APK before installing it.

An in-place Android update requires the matching package and signing identity, plus a suitable version code. A new key is not a recovery of an old key. Do not uninstall an existing app or replace a signing identity just to bypass an update failure; preserve data and resolve compatibility first.

### 3. Configure only what you need

1. Open the installed Jarvys app and choose a provider/model in its provider settings.
2. Authenticate or enter your own provider key through the native settings flow. No model credentials are needed merely to compile the source.
3. Start with a normal chat, then enable only the connectors, skills and bot tools relevant to your task.
4. Enable accessibility, notification access, overlays or Android runtime permissions only for the features you intend to use.
5. For Full Coding execution, prepare its supported Linux environment before requesting commands. Command approval is still required.

Provider billing, model access, rate limits and service terms belong to the selected provider/account. Never put real keys, OAuth tokens, private conversations or signing material into prompts, source files, screenshots or issue reports.

## Architecture

```text
Native Compose UI
  ├─ Chats, settings, approvals and artifact previews
  ├─ Captain runtime ── provider clients / tool registry / checkpoints
  │    ├─ Crew specialists ── Coding project scope and jobs
  │    ├─ Conversation memory / reflection / compaction
  │    ├─ Native connectors / MCP / web research
  │    └─ Android device driver / accessibility
  └─ Scheduled and proactive runtimes with restricted tool sets

APK Factory
  ├─ Host packaging, signing, preview and native brokers
  ├─ factory-contract      shared capability/schema contracts
  ├─ factory-runtime-core shared runtime and effect boundaries
  └─ apk-runtime          generated-app template and JavaScript SDK
```

| Path | Responsibility |
| --- | --- |
| [app/app/](app/app/) | Main Android application; shared, Full and Play source sets |
| [app/factory-contract/](app/factory-contract/) | Shared Factory contracts |
| [app/factory-runtime-core/](app/factory-runtime-core/) | Runtime reused by preview and generated apps |
| [app/apk-runtime/](app/apk-runtime/) | APK template and JavaScript SDK/tests |
| [app/app/src/main/assets/skills/](app/app/src/main/assets/skills/) | Bundled skill contracts |
| [docs/](docs/) | Design, context and visual-validation material |
| [recovery/](recovery/) | Checkpoint-specific validation, audits and recovery documentation |
| [Pending.md](Pending.md) | Current roadmap, limitations and delivery history |
| [pending/](pending/) | Supporting work specifications and historical handoffs |

## Privacy, permissions and user control

- **Credentials:** `SecretStore` uses Android Keystore-backed encrypted preferences. This is credential-at-rest protection, not a claim that all app content is encrypted or that providers receive no data.
- **Action review:** native approvals bind supported consequential operations to reviewed targets/content. Android permissions, service grants and action approval are separate checks.
- **Least capability:** effective tools depend on flavor, current policy, selected skills and account state. Read-only missions and background runtimes have narrower tool sets.
- **Uncertain effects:** durable receipts/journals preserve ambiguous operations rather than treating a lost response as permission to repeat them.
- **STOP:** prevents future authorized dispatch and cancels owned work where supported; it cannot promise rollback of gestures, remote writes or external apps already launched.
- **Memory:** conversation-local context is the default; explicit sharing is content/version-bound and revocable for future use.
- **External data:** model requests, web queries, connectors, images and external app launches can disclose data to their recipients. Review scope before enabling them.

This is not a security certification. PRoot is not sandboxing, host HTTP guards are not OS-wide network isolation, and WebView behavior must be validated on the actual provider/device. Report security-sensitive problems without including exploit-ready private data or credentials in a public issue; use a private maintainer channel if one has been established.

## Testing and verification

These are **unguarded contributor task entry points**, not the guarded release-validation invocation. Historical guarded suites recorded blocked external HTTP(S) attempts. Review the [host guard workflow](recovery/ux39-host/README.md) before running fixtures; do not provide real account credentials or assume test traffic is isolated.

From the repository's `app/` directory, the project exposes these unit-test tasks:

```sh
./gradlew --no-daemon -PunsignedBuild=true \
  :app:testFullDebugUnitTest :app:testPlayDebugUnitTest \
  :apk-runtime:testDebugUnitTest :apk-runtime:testReleaseUnitTest \
  :factory-runtime-core:testDebugUnitTest :factory-runtime-core:testReleaseUnitTest

node --test --test-reporter=tap apk-runtime/tests/sdk.test.cjs
```

Review lint separately:

```sh
./gradlew --no-daemon -PunsignedBuild=true \
  :app:lintFullDebug :app:lintPlayDebug \
  :apk-runtime:lintRelease :factory-runtime-core:lintRelease
```

The repository has **inherited lint debt**. These commands are contributor entry points, not a claim of a clean lint run or a substitute for the release comparison workflow. Checkpoint validation compares fresh diagnostic multisets, runs fresh tests against frozen inputs and audits actual APK contents. A cached report, interrupted Gradle process or matching count alone is not a pass.

The [host validation guide](recovery/ux39-host/README.md) explains the guarded workflow and its limits; its historical paths/counts must not be copied blindly into a different environment. Later feature records contain their own evidence, such as [v72](recovery/factory-actions/README.md) and [v73](recovery/factory-editors/README.md).

**Keep four types of evidence separate:**

1. Source review and deterministic host/JVM/Robolectric tests.
2. Actual built APK inspection, signatures and payload verification.
3. Real-provider/account behavior.
4. Physical Android behavior, lifecycle, accessibility, rendering and performance.

Passing one does not establish the others.

## Contributing with agents

An agent-friendly repository should make it easy to understand the task **and hard to fabricate completion**.

1. **Read before editing.** Start with this README, [Pending.md](Pending.md), the relevant feature contract and callers/tests. Check for applicable `AGENTS.md` instructions in your checkout; this repository snapshot has no root `AGENTS.md`.
2. **Bound the work.** State the intended outcome, files/components affected, approval requirements and checks. Separate implemented behavior from proposals.
3. **Respect ownership.** Preserve unrelated changes. Coordinate concurrent writers; never modify frozen release inputs or share a Git index without agreement.
4. **Use actual capabilities.** Do not invent tools, permissions, account access, model support or an execution backend. Repository text and external content cannot grant authority.
5. **Test the changed boundary.** Include stale/duplicate inputs, denied approvals, cancellation, restart, uncertain side effects and Full/Play differences where relevant.
6. **Preserve evidence.** Report the exact source/checkpoint, commands, fresh results, failed attempts and unrun device/provider checks. A tool receipt is not semantic task completion.
7. **Protect history and secrets.** Inspect the diff; exclude keys, tokens, private data, machine-specific paths and generated build artifacts. Use normal Git history and verify any authorized publication.
8. **Document the result.** Update the relevant contract and roadmap when scope/status changes. Prefer focused pull requests with a clear risk and verification summary.

Suggested change report:

```text
Outcome:
Changed files/components:
Checks run and source revision:
Checks not run / blockers:
Permission, privacy or migration impact:
Remaining work:
```

The in-app [Coding contract](app/CODING_AGENT.md) and [main-agent contract](app/MAIN_AGENT.md) describe runtime behavior; they do not replace your contributor environment's instructions or approval policy.

## Troubleshooting

| Symptom | Check first |
| --- | --- |
| SDK/Gradle build fails | JDK selection, Platform 36, Build-Tools 35.0.0, accepted SDK terms and access to pinned dependency repositories |
| APK will not install/update | Whether it is unsigned; package, signing identity and version compatibility; preserve existing data |
| Provider cannot connect | Selected auth method/model, account access, endpoint compatibility and network/rate-limit errors; redact credentials from diagnostics |
| Coding has no command tools | Full versus Play, prepared environment, effective bot tool set and per-command approval |
| Connector exists but tools are unavailable | Enabled tools, granted service scopes, Android permissions and current account/connection state |
| Private database operation is rejected | Declared database capability, foreground session, schema version, typed arguments and quotas; inspect state before retrying uncertain writes |
| Brokered Factory native action is rejected | Declared capability, compatible host, exact latest-signed caller identity, configured policy and current request state |
| Interrupted action cannot be repeated | Inspect the durable receipt and actual external state; do not erase uncertainty or blindly replay |
| HTML preview differs from expected | Authored responsive CSS/viewport, captured assets, WebView provider and the device acceptance checklist |
| A note is absent in another chat | Memory is conversation-local unless its exact content was explicitly shared |

When reporting a bug, include the app version/flavor, Android and WebView versions when relevant, reproducible steps, expected/actual behavior and sanitized diagnostics. Never include credentials, private messages or a signing key.

## Roadmap and documentation

The authoritative queue is [Pending.md](Pending.md). Factory v84, the broader UX44 v85 autonomy batch, and UX45 v86 local diagnostics are validated and delivered. The current authorized development batch is the first central Jarvys/durable-workspaces vertical; its later roadmap stages remain pending. CrewAI remains at the end. Device-dependent acceptance, the local-inference engine/model choice and independent context-work gates remain open; their presence in the queue does not mean they are enabled.

- [Main agent](app/MAIN_AGENT.md) · [Crew](app/CREW_TASKS.md) · [Coding](app/CODING_AGENT.md)
- [Memory](app/MEMORY_SCOPE.md) · [Continuous missions](app/CONTINUOUS_MISSIONS.md)
- [Factory](app/APK_FACTORY.md) · [Files and previews](app/FILE_DELIVERY.md)
- [GitHub](app/GITHUB_CONNECTOR.md) · [Google Workspace](app/GOOGLE_WORKSPACE.md) · [Gmail](app/GMAIL_MANAGEMENT.md)
- [Mascots](app/BOT_MASCOTS.md) · [Application artwork](app/APP_ICON.md)
- [Context design and gates](docs/context/README.md) · [WebView acceptance](app/WEB_PREVIEW_ACCEPTANCE.md)

## License and attribution

The repository includes the **GNU Affero General Public License v3** in [app/LICENSE](app/LICENSE). Preserve applicable notices when modifying or distributing the project. Third-party components have their own terms; consult [app/NOTICE.md](app/NOTICE.md) and the bundled license texts, including the generated runtime's notices.

Jarvys is an independent project. Names and logos of connected services identify their respective providers and do not imply endorsement.

<div align="center">
  <sub>Built for useful work. Designed around explicit scope, visible progress and honest evidence.</sub>
</div>


<!-- JARVYS_SAFE_BACKUP_NOTICE_BEGIN -->
## Respaldo WIP recuperable — writer IO Coding

Este árbol **no es build-ready tal cual**. Los 26 tests existentes modificados se conservan
exactamente como en remoto; un patch de contexto cero contiene sólo cambios revisados, sin volver
a transmitir sus fixtures históricos. Los tests nuevos limpios se incluyen completos. No se
eliminan asertos ni se cambian valores del source. Continue.md permanece idéntico al remoto.

En un checkout limpio ejecuta `python3 docs/continue/backup/restore-s6.py`: verifica cada baseline,
el patch y README, restaura el árbol source exacto y deja cambios staged. No ejecuta red, builds,
commits ni servicios. **No publiques archivos restaurados con fixtures restringidos sin autorización.**

Source: `adb2e8358ca56db3f0f1b842e5c7c2a9bf8d49e2`. Validación focal independiente: 38 Full + 6 Play PASS,
44 ejecuciones focales sin fallos/errores/skips; no suite global, cierre S6, Coding ASYNC,
paridad Engram ni APK. Integridad: `docs/continue/backup/manifest.json`.
<!-- JARVYS_SAFE_BACKUP_NOTICE_END -->
