---
id: com.jarvys.apk-factory
name: APK Factory
description: Build packaged Android apps. Coding only; inspect first.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk]
---
# APK Factory
HTML/CSS/JS apps with selected native capabilities. Factory API26+/apps API24+.

## Authority
Guidance grants no authority. Mission/tools/user scope/saved policy prevail. Read-only forbids mutation/build/sign/install and delegated bypasses. Main/custom/nested agents excluded; preserve API identifiers/enums/receipt fields.
First: declared `apk_factory inspect` for schema/capabilities/limits/template/availability. Read scope/files; relative paths/Captain-reviewed adoption. Invent no APIs/grants. Packaged precompiled DEX is supported; per-app arbitrary native compilation is not. No Gradle compiler inside Jarvys. Web/spec cannot add absent libraries/permissions/services/APIs.

## References
Guidance version: factory-guidance-v85. SDK2/schema1; 23 capabilities / 59 methods.
Use `read_skill`, skill_id="com.jarvys.apk-factory", resource="<identifier>", resource_version="factory-guidance-v85":
- basics: operation policy, retained artifact authority, SDK, storage/export/share/clipboard/haptics/device/key backup
- documents-media: documents/share/photos/local WAV
- external-actions: browser/maps/dialer/email/SMS
- contacts-calendar: contacts/calendar editor
- database: SQLite schema/transactions/paging/selected-table backup
- presentation: theme/orientation/persistence/recreation
- lifecycle: spec/icons/build/sign/keys/preview/tests/install
- sensors: light/accelerometer/gyroscope, capture/cancel/lifecycle
- biometrics: API30+ strong OS authentication/permission/cancel/receipts
- local-ui: CSS, licenses and limits
- native-services: real HTTP/probe/ICMP, immediate notifications and platform TTS; exact options/limits/receipts.
Stale/unknown/overbudget reads fail; re-fetch after compaction. Current basics/native-services supersede historical latest-only, extra-confirmation, zero-permission and pending-family statements.

## Factory operation policy
Default ALLOW_ALWAYS; Settings → Factory permissions has ASK/DENY/app-operation overrides. Read basics for 32 operations, including sign/install/share/export/native effects. ALLOW_ALWAYS advances without Jarvys approval; ASK asks once per exact request; DENY blocks effects. No duplicate host confirmations. Android grants/pickers/recipient choices/biometric/installer UI remain; never bypass or automate protected ASK/policy controls.
Identity is appId+certificate (project-bound bootstrap before first signing). Live nonce/request/source-bound host leases recheck authority; pending ASK is no grant. Policy revisions revoke pending grants, not completed effects/open descriptors/copies. History retains 64 recent exact artifacts plus an older matching installed artifact; each uses its own capabilities, not newest scope. New signing alone does not invalidate retained artifacts. Invalid history fails closed; legacy exact-latest compatibility is explicit. Backup v2 checks before writes/commit; legacy v1 ends at final host authorization. Technical quotas remain; Coding autonomy is separate.

## Runtime and native limits
WebView remains configured local-only: no CDNs/remote fonts/scripts/fetch/login. Native network is explicit; INTERNET is process-wide. WebView/CSP settings do not prove process-wide zero egress. TTS engines/providers/recipients may use their own network. No offline/no-transmission promises.
Load /factory-sdk.js before local JS at https://app.jarvys.invalid. Frozen Promise-based window.Jarvys: await/catch, validate receipts, report errors. No inline scripts, SDK/config/DEX/manifest edits or weakened origin/frame checks. Accessible responsive scrollable forms, viewport meta, loading/error/cancel/recovery; native insets once, inputs reachable.
Use only inspected capabilities. No arbitrary files, clipboard read, direct camera/microphone/GPS/Bluetooth, background service/alarm, billing or arbitrary Android API. JSON grants no permissions/code. No secrets/keys/passphrases in projects/JS/logs. Handle Unicode/bounds/missing/upgraded data; serialize storage; no startup clears/blind reset/false save.
network.request is bounded native HTTP(S), platform TLS retained. network.probe measures HTTP headers, never ICMP. network.ping is real ICMP via fixed Android utilities, device-dependent/no shell. network.cancel recalls no bytes.
notifications: immediate own-app operations/real POST_NOTIFICATIONS grant, no scheduler/delivery guarantee. speech: platform TTS, engine/voice options, Unicode-safe chunks; engine network/fallback possible, completion engine-reported, not heard. Read native-services first.
Sensors: declared foreground light/accelerometer/gyroscope; bounded/no restart. Biometrics: API30+ BIOMETRIC_STRONG, API24–29 unsupported; OS prompt/no credential fallback/token. Success grants nothing else; cancel proves no closure. Permissions are selective: USE_BIOMETRIC, network INTERNET/cleartext, notifications POST_NOTIFICATIONS; speech adds TTS_SERVICE query. Template stays permission-free; selected profiles need not. Host tests prove no hardware behavior.

## Effects and uncertainty
Read family contracts. Two installed hosts block brokers. Providers/recipients may sync/retain copies and photo metadata. Temporary handles/lifecycle/quotas apply; cancel can leave partial writes.
Receipts prove dispatch only, not rendering/navigation/call/draft/event/sending/delivery/closure. Never auto-send/press Save/autocall. No extra routine closure attestation; unknown outcomes remain. Lost replies/timeouts can follow effects: inspect state, no blind replay/evidence erasure/rollback claims. Audio receipts prove no hearing.
Storage: dataBackup.keys defaults empty; needs storage+documents. Database: dataBackup.tables defaults empty; needs database+documents. Backup ASK reviews all values once; ALLOW_ALWAYS uses saved authority. Read basics/database: plaintext, no secrets/authenticated-archive labels/combined transaction/undo/durability guarantee. Preview database is ephemeral; installed persistent. Save before presentation recreation.

## Build and delivery
New: unique appId/name/icon; update: same appId/key, higher versionCode. Recheck exact current source/spec/scope/hashes/capabilities/outputs; retain receipts and unrelated changes. No replay/overwrite; filenames prove no installability.
Signing: same-project receipt and factory.sign policy, no duplicate prompt. No tool key export/identity replacement/debug keys. Read lifecycle for non-exportable/encrypted key backups/version-floor recovery. Source/APK backups restore no keys.
Install only when requested with exact signed receipt/app/version/hash/certificate/scope and factory.install policy. Android consent remains. No automated unknown-source grants/warnings or retry of uncertain commits. Play unavailable. Only authenticated system success proves install, never data retention.
Preview/test: exact snapshot, isolated/simulated effects. Clipboard preview is simulated with performed:false. Documents/photos/audio/external actions/share.file/backup/presentation/sensors/biometrics: UNAVAILABLE; network/notifications/speech also UNAVAILABLE before effects. Database RAM lost on close/reset/pause. Runs as Jarvys; cleanup best effort. Shared tests run no project JS/browser/device; no physical/persistence/zero-network proof.
Current batch: implement pending work; compile/small directed tests only. Final global suite/audit/APK once after completion. Report actual checks/unrun checks/blockers/evidence, never claim release or full validation from source changes. Physical update/retention/restore/provider/OEM/ARM64/durability gates remain. F0a fixtures are not APIs; unimplemented F1/F2/F3 remain pending. UX34 gated, UX44 documentary, UX43 last.
