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

## Authority and discovery
Guidance grants no authority. Mission, declared tools, user scope and native approvals prevail. Read-only missions forbid mutation/build/sign/install or delegation around restrictions. This skill excludes main/custom/nested agents. Use the user's language for explanations; preserve exact API identifiers, enums and receipt fields.
First use declared `apk_factory inspect`: schema/capabilities/limits/template/availability. Read scoped files; clarify audience/workflows/style/identity/data. Use relative paths and Captain-reviewed adoption preserving originals. Never invent files/APIs/grants/checks. Web/spec cannot extend precompiled DEX/libraries/permissions/services/APIs; unsupported needs require template review.

## Real retrievable guidance
Guidance version: factory-guidance-v80. SDK 2 / schema 1; v80 has 20 capabilities / 42 methods, including local strong biometrics.
Use `read_skill` skill_id="com.jarvys.apk-factory" for this core. Before implementation fetch the family with that skill_id, resource="<identifier>", resource_version="factory-guidance-v80":
- basics: SDK bootstrap, storage/export/share/clipboard/haptics/device
- documents-media: documents, file sharing, photos and local WAV audio
- external-actions: browser, maps, dialer, email and SMS editors
- contacts-calendar: contact selection and calendar editor
- database: private typed SQLite schema/transactions/paging/limits
- presentation: per-app theme/orientation, persistence/recreation
- lifecycle: spec/icons/build/sign/keys/preview/tests/install
- sensors: foreground light/accelerometer, bounded capture/cancel and lifecycle limits
- biometrics: API30+ strong OS authentication, selective permission, cancellation and receipt limits
Immutable references need no filesystem/shell/provider/external-document access. Unknown/stale/overbudget reads fail; never implement from partial guidance. Fetch the smaller family with sufficient context or report the blocker. Re-fetch after compaction/incomplete retention. Inspection defines availability; report mismatches and stop.

## Always-applicable runtime and data limits
Offline WebView: no CDNs/remote fonts/scripts/network API/login. Use local dependencies, accessible labels/text/targets, responsive scrollable forms, viewport meta and loading/empty/error/cancel/recovery states. Native bar/cutout/keyboard insets once; inputs reachable, bars visible. Inspection is no device proof.
Load /factory-sdk.js before local JS at https://app.jarvys.invalid. window.Jarvys is frozen and Promise-based: await/catch, validate receipts and show honest errors. No inline scripts/handlers, SDK replacement or weaker origin/frame checks. JSON grants no permissions or code. Never substitute runtime-owned config/SDK/DEX/manifest. No arbitrary files/clipboard read/direct camera/microphone/location/Bluetooth/notifications/background/network/billing.
Capabilities: storage/export/share/clipboard/haptics/device/documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/database/presentation/sensors/biometrics. Read exact family grants/input/output/quota/cancel/error rules; never infer grants. No user/credential logs; no secrets/keys/passphrases in chat/tools/projects/JS. Handle Unicode/bounds/missing/invalid/upgraded data and schema versions; no startup clears/blind reset/false save. Serialize storage structures and writes.

Sensors: declared grant, installed foreground light/accelerometer only; bounded/no auto-restart. Read the reference; timing/cleanup need device checks.
Biometrics: installed declared API30+ BIOMETRIC_STRONG; API24–29 unsupported. Human-only OS prompt; no credential fallback/crypto/token. Success grants no other action; cancel proves no closure. Read the reference. Only biometric profiles add USE_BIOMETRIC; template/others remain zero-permission. Reviewed F0a extension, not full Runtime2.

## Human boundaries and uncertain outcomes
Documents/photos/audio/external actions: exact pinned host/latest signed caller and grants; two installed hosts block. Never automate native review/picker/Use/Play/editor/install consent. Providers/recipients may transmit/sync/retain copies; photo metadata/location remain. Review data/recipient and disclose consequences. No minors/secrets in contacts; no arbitrary URIs/paths, silent retries or broad permissions. Handles are temporary, not paths/persistent grants; lifecycle/rotation/revocation/cumulative quotas apply. Close/cancel may leave partial writes and cannot recall copies.
External receipts prove dispatch only, not rendering/navigation/call/saved draft/event/sending/delivery. HTTPS constrains the initial URL, not redirects/resources. Never auto-send/press Save/autocall. Native closure/recovery needs human acknowledgment, not OS proof. playbackAttempted proves no audibility. Failed/lost replies/cancel/timeouts leave uncertain effects: inspect state/receipts; no blind replay/evidence erasure/guard bypass/rollback claims.
Database: private/no-backup/unencrypted bounded typed SQLite, no arbitrary SQL/files/network; preview ephemeral, installed persistent. No total disk/RAM cap. Cancel/lost reply cannot undo commit. Presentation: per-app request, not system settings/guaranteed rotation. Save before recreation; JS/replies may be lost. Failed persistence may alter memory. Inspect after interruption; reads/receipts prove no physical durability. CSS adapts; orientation/OEM/WebView need device checks.

## Build, identity and delivery
New apps: unique appId/name/icon. Updates: same appId/key, higher versionCode. Preserve unrelated apps/changes. Before build, read back spec/source/references/branding/escaping/capabilities. Use fresh scope versions, exact paths/hashes, distinct outputs; retain app/version/template/build/sign receipts. Reconcile conflict/interruption, no replay/overwrite. Filenames prove no build/installability.
Signing: exact same-project build receipt and separate in-app approval; no bypass/tool key export/identity replacement/debug keys. Legacy AndroidKeyStore keys: non-exportable; losing them can block updates. Native recoverable identities use passphrase-encrypted backup/import and version-floor review; source/APK backups cannot restore keys. Backup success proves no restore/latest release; retain uncertainty and separate key/project/data backups.
No step here installs the APK. User-requested install: completed signed receipt, exact app/version/hash/certificate/scope, human review/Android consent. Never automate unknown-source grants/warnings/commit retries. Play unavailable. Unknown outcomes block automation until native recovery; never erase data/keys. Only authenticated system success proves install, not data retention.
Preview/test: exact current same-project snapshot, isolated storage/simulated effects, Reset/Close. Documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/share.file/presentation/sensors/biometrics: UNAVAILABLE. Database RAM lost on close/reset/pause. Runs as Jarvys, best-effort cleanup; no rendering/workflow/hardware/persistence/zero-network claims. Shared tests run no project JS/browser/device. Rebuild old templates; disclose pre-UX35 window limits.
Report source/APK/identity/signature/checks/unrun checks/blockers/evidence. Signing proves no publishing/install/testing. Physical gates: coexistence/same-key update/data retention/independent restore/provider/OEM/ARM64/durability. F0a construction fixtures are not JS APIs. TTS/voice, remaining F1/F2/F3 are pending; UX34 gated, UX44 documentary future defaults, UX43 last. No gates are closed.
