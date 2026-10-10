<div align="center">
  <img src="app/artwork/jarvys-app-icon-source.png" width="150" alt="Jarvys robot: silver body, dark visor and cyan eyes" />
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

## What is Jarvys?

Jarvys brings a tool-using AI workspace to Android: persistent conversations, configurable model providers, specialist agents, project files, connectors, scheduled work and optional phone interaction. Its native interface is built with Kotlin, Java and Jetpack Compose.

The agent runtime runs in the Android app. **This does not mean the language model runs locally:** inference, web research, image generation and connected services can send data to their configured providers. Features depend on the selected model, account, distribution flavor, Android permissions and available runtime tools.

### Current project status

This is an actively developed project with incremental, evidence-backed checkpoints.

| Checkpoint | Status |
| --- | --- |
| **v72 · Factory maps and dialer** | Completed earlier delivery; host tests and independent APK audit passed. [Evidence](recovery/factory-actions/README.md) |
| **v73 · Factory email and SMS editors** | Latest completed delivery: 7,390 host tests, SDK20 and independent APK audit passed; signed test APK attachment accepted. Opening an editor is not sending a message. [Scope and status](recovery/factory-editors/README.md) |
| Physical Android acceptance | Still open for the relevant recent features. A host test, signed APK or accepted file delivery does not prove installation or phone behavior. |
| Broader Factory roadmap | Remaining F1 capabilities and F2/F3 are unfinished. Strict Factory TTS remains blocked on its recipient/consent design. |

This status is a **2026-10-10 snapshot**, not a live build badge. [Pending.md](Pending.md) is the current work queue; feature-specific evidence records distinguish passing, failed, interrupted and unrun checks.

## Capabilities

### 💬 Conversations and model providers

- Persistent chats, Markdown, selectable content, attachments, reactions, model selection and visible tool activity.
- English and Spanish interface resources, light/dark presentation and native navigation.
- OpenAI Codex sign-in, OpenAI API keys, OpenRouter, and custom **OpenAI Chat Completions-compatible** endpoints. Compatibility is a specific protocol mode, not a promise that every model or server supports every tool.
- Context budgeting, conversation compaction, run checkpoints and explicit partial/interrupted outcomes.
- STOP controls and a separate **Stop and send this message** flow for eligible active chats. Cancellation cannot undo an external effect that already happened.
- Assistant speech controls; these are separate from the still-pending strict Factory TTS capability.

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

**Turn a supported local web project into an Android APK through Coding.** Factory packages a reviewed native runtime and bounded HTML/CSS/JavaScript assets; it is not a general-purpose arbitrary Android source compiler.

Building with Factory requires **Android 8/API 26 or newer** in either Jarvys flavor. Generated apps support **Android 7/API 24 or newer**. Factory packaging itself does not require PRoot.

```text
Project files + factory.json
         │
         ▼
Inspect → Build unsigned APK → Review signing scope → Sign
         │                                           │
         └── Private preview / simulated tests       └── Separate install review
```

### Implemented slices

| Area | What exists | Important boundary |
| --- | --- | --- |
| Packaging | Closed project schema, local assets/icons, authenticated template, manifest/resource/DEX checks and provenance receipts | Unsupported manifest nodes and arbitrary native code are rejected |
| Signing | Explicit review, version/app identity continuity, verified signed payloads, recoverable new identities and encrypted backup/import | Backup/import is an explicit user interaction; older non-exportable keys stay non-exportable |
| Preview and tests | Shared runtime core, isolated test storage, exact preview snapshots, simulated effects and bounded receipts | Preview success does not establish live native effects |
| Installation | Separate native review tied to the exact signed artifact and durable recovery | Full flavor; Android user action/permission and actual install result remain separate |
| Documents and sharing | SAF document operations, opaque handles and bounded native file sharing | Require a compatible authenticated Jarvys host and human interaction |
| Camera/photos | Bounded capture/picker workflows with native consent | Camera compatibility varies; selected original image metadata can be retained |
| Audio | Local bounded PCM16 WAV playback | Not synthesis, microphone recognition or cloud voice |
| Browser, maps and dialer | Typed HTTPS, map and dialer launches with reviewed destinations | Dispatch does not prove page display, navigation, calling or external-task closure |
| Email/SMS editors | v73 adds typed editor launches with one recipient and reviewed fields | Host gates passed and signed test APK delivered; external apps may sync drafts; no automatic sending or delivery claim |

**Not every generated app is standalone.** Local runtime features differ from brokered native capabilities. The latter require a compatible Jarvys host, exact caller/signing identity checks and native review. A generated APK's closed, zero-permission manifest does not make the host or selected third-party app permissionless or offline.

Strict Factory TTS/voice, remaining F1 work and F2/F3 remain open. Do not interpret the available runtime or a successful build as completion of the full Factory roadmap.

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
| Factory native action is rejected | Declared capability, compatible host, exact latest-signed caller identity and native review state |
| Interrupted action cannot be repeated | Inspect the durable receipt and actual external state; do not erase uncertainty or blindly replay |
| HTML preview differs from expected | Authored responsive CSS/viewport, captured assets, WebView provider and the device acceptance checklist |
| A note is absent in another chat | Memory is conversation-local unless its exact content was explicitly shared |

When reporting a bug, include the app version/flavor, Android and WebView versions when relevant, reproducible steps, expected/actual behavior and sanitized diagnostics. Never include credentials, private messages or a signing key.

## Roadmap and documentation

The authoritative queue is [Pending.md](Pending.md). Current open themes include the remaining Factory capability families, strict TTS/voice design, physical-device acceptance, context-work gates, future persistent command approval and the later central Jarvys/spaces architecture. Their presence in the queue does not mean they are enabled. **UX44**'s proposed permission defaults and migration remain documentation-only; current approval behavior is unchanged.

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
