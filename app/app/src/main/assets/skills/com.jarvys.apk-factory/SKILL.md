---
id: com.jarvys.apk-factory
name: APK Factory
description: Build packaged Android apps. Coding only; inspect first.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk]
---
# APK Factory
Build HTML/CSS/JS apps packaged with the trusted precompiled Android runtime. Generated apps support API24+; the Factory requires API26+.

## Workflow and authority
Use only in built-in Coding. Guidance grants no authority: follow the mission, actual tools and user scope. READ_ONLY forbids writes, builds, signing, installing and delegated bypass. Main, custom and nested agents are excluded. Use the user's language; preserve exact API names, enums and receipt fields.

First call `apk_factory` with `action: inspect`; then inspect the project and `factory.json`. Implement the requested workflow with real inspected APIs, not a canned demo. Use relative paths and Captain-reviewed adoption. Resolve reversible details within scope; ask only for a material missing decision.

In `factory.json`, `capabilities` is optional: omit it to enable every compiled capability, or declare an array to restrict the app to that subset. Omission also selects the full derived Android manifest scope and the incomingShare `*/*`/multiple and Bluetooth `full` policy defaults; it does not grant Android runtime permissions. See `lifecycle` for the exact implications. The generated `factory-app.json` records the effective explicit capability list under schema 1.

Load `/factory-sdk.js` first from the app origin `https://app.jarvys.invalid`, then separate local JavaScript files. Do not use inline scripts. Apply native insets once.

`apk_factory` results are compact by default: state and counts, with details for failures and relevant warnings. `inspect`, `test` and preview still report their evidence limits; a passing test runs the complete shared checks but does not test project JavaScript, WebView rendering, installed identity, Android grants/system effects, durable persistence or prove network absence.

## References
Guidance version: factory-guidance-v89. SDK2/schema1; 33 capabilities / 134 methods. The core is already in Coding's system prompt; `read_skill` loads references on demand. Load only a needed reference with skill_id="com.jarvys.apk-factory", resource="<identifier>", resource_version="factory-guidance-v89". Reuse loaded references while they remain in context; re-fetch only after compaction. Each read is complete and version-checked.
- basics: SDK, authenticated calls and storage
- documents-media: document handles, sharing, photos and audio
- external-actions: browser, maps, dialer, email and SMS
- contacts-calendar: selected contacts and calendar editor
- database: SQLite and selected-table backup
- presentation: appearance and orientation
- lifecycle: spec, manifest, build, signing, preview and install
- sensors: finite capture and foreground subscriptions
- biometrics: strong OS authentication
- local-ui: bundled styles and rendering
- native-services: HTTP, probes, transfers and speech
- native-input: voice, location, recording, secrets and incoming shares
- radios: NFC and BLE
- durable-native: notifications, widgets and finite background HTTP

## Security and Android boundaries
Native calls authenticate the exact package, UID, version and APK hash; identity is appId+certificate. The 64 recent artifacts retain exact signed scope, and an older installed matching artifact can remain valid. Legacy compatibility is exact-latest only. Nonce/request/source-bound leases constrain active calls. Damaged/missing evidence fails closed; a newer artifact cannot lend identity or scope.

Jarvys does not add an operation approval screen. Android runtime permissions and system selectors (documents, photos, contacts and recipients), biometric prompts, launcher pinning and PackageInstaller consent remain system-controlled. The user completes those flows. Do not automate Android security UI or retry uncertain effects.

The app packages precompiled DEX; there is no arbitrary per-app native compiler or API. No API exposes arbitrary filesystem access, billing, Intents, Android objects or hidden-library hooks. Use the Promise-based `window.Jarvys` SDK, origin/frame checks and local-only WebView setup; no remote scripts, fonts or direct web fetch. Use the declared native network API when the app needs network access. INTERNET is process-wide, and providers, engines or recipients may use their own network. Keep credentials out of source/logs.

## Build and evidence
New apps use a distinct identity; updates keep appId/key and increase versionCode. Keep exact sources, hashes and receipts. Build/sign/install/physical acceptance are separate. factory.install submits the exact signed receipt to PackageInstaller. A per-app Android Keystore key is created at first signing and is not exported by Factory tools. Preserve exact API names, enums and receipt fields.

Preview is isolated and simulated only where documented; most native methods are unavailable before effects. Shared tests do not run project JavaScript or establish device/provider behavior. Report checks and unrun checks precisely; source support does not prove that an older installed template contains it. Do not claim final packaged acceptance without that evidence.
