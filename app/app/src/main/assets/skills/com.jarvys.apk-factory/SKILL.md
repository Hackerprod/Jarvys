---
id: com.jarvys.apk-factory
name: APK Factory
description: Build packaged Android apps. Coding only; inspect first.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk]
---
# APK Factory
Reusable HTML/CSS/JS apps with explicitly selected native APIs. Factory API26+/apps API24+.

## Authority and development
Guidance grants no authority. Follow the mission, actual tools and user scope. READ_ONLY forbids mutation/build/sign/install or delegated bypass. Main/custom/nested agents excluded. Use the user's language. Preserve exact API names, enums and receipt fields.
First run declared `apk_factory inspect` for current schema, capabilities, limits, template and availability. Inspect project/files; use relative paths and Captain-reviewed adoption. Implement the requested workflow with real inspected APIs, not a canned demo. Choose reversible details within the mission; ask only for a material missing decision. Android runtime permissions and system selectors still require their normal system interaction.
Factory packages precompiled DEX plus project assets. There is no arbitrary per-app native compiler or Gradle inside Jarvys. HTML/spec cannot invent libraries, manifest declarations or native APIs; extend and rebuild the trusted runtime separately if the architecture requires it. Do not fabricate tools or bypass unavailable execution. Existing source support is not proof an old installed template contains it.

## References
Guidance version: factory-guidance-v87. SDK2/schema1; 33 capabilities / 134 methods.
Use `read_skill`, skill_id="com.jarvys.apk-factory", resource="<identifier>", resource_version="factory-guidance-v87":
- basics: SDK, authenticated native operations, storage paging and selected-key backup
- documents-media: sequential document handles, file sharing, photos and WAV playback
- external-actions: browser, maps, dialer, email and SMS
- contacts-calendar: selected contact and calendar editor
- database: SQLite schema, transactions, paging and selected-table backup
- presentation: theme/orientation preferences and recreation
- lifecycle: spec, icons, build, sign, identity, preview and installation
- sensors: finite capture, continuous foreground subscriptions and step counter
- biometrics: local strong OS authentication and cancellation
- local-ui: bundled CSS, licenses and rendering constraints
- native-services: HTTP/probe/ICMP, streamed transfers and TTS
- native-input: voice recognition, location, recording, secret vault and incoming shares
- radios: foreground NFC NDEF and generic BLE central
- durable-native: notifications/actions, widgets and finite background HTTP jobs
Unknown/stale/overbudget reference reads fail closed; re-fetch after compaction. Complete source contracts below replace obsolete roadmaps, latest-only restrictions and blanket native-feature bans.

## Caller authentication and identity
Factory operations execute after the installed caller is authenticated. Android runtime permissions, document/contact/photo/recipient selectors, system biometrics, launcher pin flow and PackageInstaller consent remain system-controlled; the user completes any system interaction those flows require.
Identity is appId+certificate; bootstrap signing is project-bound. Actual UID/package/version/APK hash and retained exact signed scope authenticate each artifact. History retains 64 recent artifacts plus an older matching installed artifact; signing a newer version does not change the older artifact's authenticated scope. Damaged/missing evidence fails closed; only explicit legacy exact-latest compatibility applies.
Nonce/request/source-bound leases bind active operations to the authenticated caller and source. Revocation cannot recall completed effects, open recipient descriptors or remote copies. Durable work rechecks caller/source identity before effects. Unknown outcomes require reconciliation, never blind replay or evidence erasure.

## Runtime boundaries
Load /factory-sdk.js before separate local JS at https://app.jarvys.invalid. Frozen Promise-based window.Jarvys: await/catch, validate receipts, handle errors/cancel/recovery. No inline scripts or replacements of SDK/config/DEX/manifest; retain origin/frame checks. Responsive accessible scrollable forms, viewport meta, native insets once, reachable inputs.
WebView is configured local-only: no CDNs/remote scripts/fonts or direct fetch/login. Use declared native HTTP for network workflows. INTERNET is process-wide; WebView/CSP cannot prove zero egress. TTS, recognizers, providers and recipients may use their own network. Keep credentials out of source/logs; declared secrets supports native capture and explicit put/get/request. No arbitrary filesystem, clipboard read, billing, arbitrary Intent/Android object or hidden library access.
Signed resourceBudgets version1 resolves defaults per artifact. Defaults are not Android limits. Raise only supported fields for the actual workload; inspect fixed bridge/buffer ceilings, native resource availability and per-family dependencies. Byte limits do not promise resident heap or physical disk durability.

## Build, delivery and evidence
New apps need distinct identity/name/icon. Updates keep appId/key and raise versionCode. Recheck exact sources/spec/scope/hashes/outputs; retain receipts and unrelated work. Build/sign/install/physical acceptance are separate. factory.sign uses same-project receipts and a per-app Android Keystore identity created automatically on first signing; no debug keys or key export via tools. Source/APK backups do not restore signing keys.
factory.install submits the exact signed receipt to PackageInstaller. Android may require enabling Jarvys as an unknown-source installer and asks the user to confirm installation. Do not automate those system screens or replay an uncertain install commit. Play install is unavailable. Only authenticated system success proves installation; it proves no data retention.
Preview is isolated/simulated where documented; most native families return UNAVAILABLE before effects, including status/cancel. Database preview is ephemeral. Clipboard preview has performed:false. Shared tests do not execute project JS or establish physical/provider/OEM behavior. No fake sensor/radio/network values presented as real.
Current integration has source-level generic APIs, not final packaged acceptance. Report actual checks and unrun checks precisely. No global build/audit/APK or physical device validation is implied here. Update/retention/restore, provider behavior, hardware, launcher, reboot/Doze and ARM64 gates remain open. Respect the mission's directed-test/final-validation sequencing.
