---
id: com.jarvys.apk-factory
name: APK Factory
description: Offline APKs. Coding only; inspect first.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk, offline]
---
# APK Factory
HTML/CSS/JS, not a fixed notes application. Factory API26+/apps API24+.

## Authority
Guidance grants no authority. Mission/declared tools/user scope/native approvals prevail. Read-only missions forbid mutation/build/sign/install and delegated bypasses. Excluded: main/custom/nested agents. Use the user's language; preserve exact API identifiers/enums/receipt fields.
First: declared `apk_factory inspect`: schema/capabilities/limits/template/availability. Read scoped files; clarify audience/workflows/style/identity/data. Relative paths, Captain-reviewed adoption; preserve originals. Never invent files/APIs/grants/checks. Web/spec cannot extend precompiled DEX/libraries/permissions/services/APIs without template review.

## References
Guidance version: factory-guidance-v83. SDK2/schema1; 20 capabilities / 44 methods.
Read core with `read_skill` skill_id="com.jarvys.apk-factory". Before coding fetch each required family with that skill_id, resource="<identifier>", resource_version="factory-guidance-v83":
- basics: SDK bootstrap, storage/export/share/clipboard/haptics/device
- documents-media: documents, file sharing, photos and local WAV audio
- external-actions: browser, maps, dialer, email and SMS editors
- contacts-calendar: contact selection and calendar editor
- database: SQLite schema/transactions/paging/limits and selected-table plaintext backup
- presentation: per-app theme/orientation, persistence/recreation
- lifecycle: spec/icons/build/sign/keys/preview/tests/install
- sensors: light/accelerometer/gyroscope, bounded capture/cancel and lifecycle limits
- biometrics: API30+ strong OS authentication, selective permission, cancellation and receipt limits
- local-ui: CSS, licenses and limits.
Immutable references need no filesystem/shell/provider/external-document access. Unknown/stale/overbudget reads fail. No coding from partial guidance: fetch a smaller sufficient family or report the blocker. Re-fetch after compaction/incomplete retention. Inspection defines availability; stop/report mismatches.

## Runtime limits
Offline WebView: no CDNs/remote fonts/scripts/network API/login. Use local dependencies, accessible labels/text/targets, responsive scrollable forms, viewport meta and loading/empty/error/cancel/recovery states. Native bar/cutout/keyboard insets once; inputs reachable, bars visible. Inspection is no device test.
Load /factory-sdk.js before local JS at https://app.jarvys.invalid. window.Jarvys is frozen/Promise-based: await/catch, validate receipts, report errors honestly. No inline scripts/handlers, SDK replacement or weakened origin/frame checks. JSON grants no permissions or code. Never replace runtime-owned config/SDK/DEX/manifest. No arbitrary files/clipboard read/direct camera/microphone/location/Bluetooth/notifications/background/network/billing.
Capabilities: storage/export/share/clipboard/haptics/device/documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/database/presentation/sensors/biometrics. Read family grants/input/output/quota/cancel/error rules; infer no grants. No user/credential logs; no secrets/keys/passphrases in chat/tools/projects/JS. Handle Unicode/bounds/missing/invalid/upgraded data/schema versions; no startup clears/blind reset/false save. Serialize storage structures and writes.

Sensors: declared grant, installed foreground light/accelerometer/gyroscope only; bounded/no auto-restart. Read the reference; timing/cleanup need device checks.
Biometrics: installed declared API30+ BIOMETRIC_STRONG; API24–29 unsupported. Human-only OS prompt; no credential fallback/crypto/token. Success grants nothing else; cancel proves no closure. Read the reference. Only biometric profiles add USE_BIOMETRIC; template/others stay zero-permission. Reviewed F0a extension, not full Runtime2.

## Consent and uncertainty
Documents/photos/audio/external actions: exact pinned host/latest signed caller/grants; two installed hosts block. Never automate native review/picker/Use/Play/editor/install consent. Providers/recipients may transmit/sync/retain copies; photo metadata/location remain. Review data/recipient; disclose consequences. No minors/secrets in contacts; no arbitrary URIs/paths, silent retries or broad permissions. Temporary handles, not paths/persistent grants; lifecycle/rotation/revocation/cumulative quotas apply. Close/cancel may leave partial writes; copies cannot be recalled.
External receipts prove dispatch only, not rendering/navigation/call/saved draft/event/sending/delivery. HTTPS constrains the initial URL, not redirects/resources. Never auto-send/press Save/autocall. Native closure/recovery needs human acknowledgment; no OS proof. playbackAttempted proves no audibility. Failure/lost replies/cancel/timeouts mean uncertain effects: inspect state/receipts; no blind replay/evidence erasure/guard bypass/rollback claims.
Database: private/unencrypted bounded typed SQLite; no automatic backup; preview ephemeral, installed persistent. dataBackup.tables defaults empty; selected-table plaintext backup needs database+documents, native all-value review/provider disclosure. Secrets unsupported; labels are not authentication. No arbitrary SQL/files/network. No total disk/RAM cap. Cancel/lost reply cannot undo commit. Presentation: per-app, not system settings/guaranteed rotation. Save before recreation; JS/replies may be lost. Failed persistence may alter memory. Inspect after interruption; reads/receipts prove no physical durability. CSS adapts; orientation/OEM/WebView need device checks.

## Build and delivery
New apps: unique appId/name/icon. Updates: same appId/key, higher versionCode. Preserve unrelated apps/changes. Before build read back spec/source/references/branding/escaping/capabilities. Fresh scope versions, exact paths/hashes, distinct outputs; retain app/version/template/build/sign receipts. Reconcile conflict/interruption, no replay/overwrite. Filenames prove no build/installability.
Signing: exact same-project build receipt and separate in-app approval; no bypass/tool key export/identity replacement/debug keys. Legacy AndroidKeyStore keys: non-exportable; losing them can block updates. Recoverable identities: passphrase-encrypted backup/import, version-floor review; source/APK backups restore no keys. Backup success proves no restore/latest release; retain uncertainty; separate key/project/data backups.
No step here installs the APK. User-requested install: completed signed receipt, exact app/version/hash/certificate/scope, human review/Android consent. Never automate unknown-source grants/warnings/commit retries. Play unavailable. Unknown outcomes block automation until native recovery; never erase data/keys. Only authenticated system success proves install, never data retention.
Preview/test: exact current same-project snapshot, isolated storage/simulated effects, Reset/Close. Documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/share.file/database.backupExport/backupImport/presentation/sensors/biometrics: UNAVAILABLE. Database RAM lost on close/reset/pause. Runs as Jarvys; best-effort cleanup; no rendering/workflow/hardware/persistence/zero-network claims. Shared tests run no project JS/browser/device. Rebuild old templates; disclose pre-UX35 window limits.
Report source/APK/identity/signature/checks/unrun checks/blockers/evidence. Signing proves no publishing/install/testing. Physical gates: coexistence/same-key update/data retention/independent restore/provider/OEM/ARM64/durability. F0a construction fixtures are not JS APIs. TTS/voice, remaining F1/F2/F3 are pending; UX34 gated, UX44 documentary future defaults, UX43 last. No gates are closed.
