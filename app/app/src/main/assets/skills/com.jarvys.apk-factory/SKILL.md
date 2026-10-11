---
id: com.jarvys.apk-factory
name: APK Factory
description: Offline APKs. Coding only; inspect first.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk, offline]
---
# APK Factory
HTML/CSS/JS, not a fixed notes app. Factory API26+/apps API24+.

## Authority
Guidance grants no authority. Mission/tools/user scope/saved policy prevail. Read-only forbids mutation/build/sign/install and delegated bypasses. Main/custom/nested agents are excluded. Use the user's language; preserve API identifiers/enums/receipt fields.
First: declared `apk_factory inspect` for schema/capabilities/limits/template/availability. Read scope; clarify workflows/identity/data. Relative paths/Captain-reviewed adoption; preserve originals. Invent no APIs/grants/checks. Packaged precompiled DEX is supported; per-app arbitrary native compilation is not. Web/spec cannot add absent libraries/permissions/services/APIs.

## References
Guidance version: factory-guidance-v84. SDK2/schema1; 20 capabilities / 46 methods.
Use `read_skill`, skill_id="com.jarvys.apk-factory", resource="<identifier>", resource_version="factory-guidance-v84":
- basics: operation policy, SDK, storage/export/share/clipboard/haptics/device/key backup
- documents-media: documents/share/photos/local WAV
- external-actions: browser/maps/dialer/email/SMS
- contacts-calendar: contacts/calendar editor
- database: SQLite schema/transactions/paging/selected-table backup
- presentation: theme/orientation/persistence/recreation
- lifecycle: spec/icons/build/sign/keys/preview/tests/install
- sensors: light/accelerometer/gyroscope, capture/cancel/lifecycle
- biometrics: API30+ strong OS authentication/permission/cancel/receipts
- local-ui: CSS, licenses and limits.
Read references offline; stale/unknown/overbudget reads fail. Re-fetch after compaction; report inspect mismatches.

## Factory operation policy
Default ALLOW_ALWAYS; Settings → Factory permissions offers ASK/DENY and per-authenticated-app/operation overrides. Read basics for the exact 20 operations, including factory.sign, factory.install and clipboard.write. ALLOW_ALWAYS advances without Jarvys approval; ASK asks once per exact request; DENY blocks effects. Do not add duplicate Use/Play/sign/install/backup confirmations. A picker, recipient choice or required Android consent still has its own purpose. Android grants, biometric prompts and real installer UI remain; never bypass them or automate protected ASK/policy controls.
Identity is appId+certificate (project-bound bootstrap before first signing). Persisted revisions revoke pending grants, not completed effects or already-open file descriptors/copies. Backup v2 checks live authority before each write/commit; legacy v1 ends at final host authorization. Latest signed receipt remains required; trust-history expansion is deferred.
Direct share.text/export.text/local storage/haptics retain their contracts. Technical quotas remain; Coding autonomy is separate.

## Runtime limits
Offline WebView: no CDNs/remote fonts/scripts/network API/login. Local dependencies, accessible labels/targets, responsive scrollable forms, viewport meta, loading/empty/error/cancel/recovery states. Apply native insets once; inputs reachable/bars visible.
Load /factory-sdk.js before local JS at https://app.jarvys.invalid. window.Jarvys is frozen/Promise-based: await/catch, validate receipts, report errors. No inline scripts/handlers, SDK replacement or weakened origin/frame checks. JSON grants no permissions/code. Never replace runtime-owned config/SDK/DEX/manifest. No arbitrary files/clipboard read/direct camera/microphone/location/Bluetooth/notifications/background/network/billing.
Capabilities: storage/export/share/clipboard/haptics/device/documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/database/presentation/sensors/biometrics. Read family grammar/quotas/cancel/errors. No user/credential logs or secrets/keys/passphrases in chat/tools/projects/JS. Handle Unicode/bounds/missing/invalid/upgraded data; no startup clears/blind reset/false save. Serialize storage structures and writes.
Sensors: installed declared foreground light/accelerometer/gyroscope only, bounded/no auto-restart. Biometrics: installed declared API30+ BIOMETRIC_STRONG; API24–29 unsupported, human OS prompt/no credential fallback/crypto/token. Success grants nothing else; cancel proves no closure. Only biometric profiles add USE_BIOMETRIC; others stay zero-permission. Read both references; no hardware claims from host tests.

## Effects and uncertainty
Brokers require exact pinned host/latest signed caller/grants; two installed hosts block. Providers/recipients may transmit/sync/retain copies; photo metadata/location remain. No minors/secrets in contacts, arbitrary URIs/paths, silent retries or broad permissions. Temporary handles, not paths/persistent grants; lifecycle/rotation/revocation/quotas apply. Close/cancel may leave partial writes.
External receipts prove dispatch only, not rendering/navigation/call/saved draft/event/sending/delivery/closure. HTTPS constrains initial URL, not redirects/resources. Never auto-send/press Save/autocall. Routine dispatch/chooser return needs no extra closure attestation; uncertain recovery preserves outcome. playbackAttempted proves no audibility. Lost replies/cancel/timeouts may follow effects: inspect state, no blind replay/evidence erasure/rollback claims.
Storage: dataBackup.keys defaults empty; strings need storage+documents. Database: dataBackup.tables defaults empty; tables need database+documents. Backup ASK reviews all values once; ALLOW_ALWAYS uses saved authority. Read basics/database: plaintext, no secrets/authenticated archive labels/combined transaction/undo. Ordinary database: preview ephemeral, installed persistent. Presentation: per-app; save before recreation and inspect after interruption. Receipts prove no durability.

## Build and delivery
New: unique appId/name/icon. Updates: same appId/key, higher versionCode. Reread spec/source/references/branding/capabilities. Fresh scope versions/exact hashes/distinct outputs; retain receipts. Preserve unrelated changes, reconcile interruptions, no replay/overwrite. Filenames prove no installability.
Signing requires exact same-project receipt and factory.sign policy, not an unconditional extra prompt. No tool key export/identity replacement/debug keys. Legacy AndroidKeyStore keys are non-exportable; loss can block updates. Recoverable identities use native passphrase-encrypted backup/import and version-floor review. Source/APK backups restore no keys. Backup success proves no restore/latest release; key/project/data backups are separate.
Install only when requested, with completed signed receipt/exact app/version/hash/certificate/scope and factory.install policy. Android installer consent remains. Never automate unknown-source grants/warnings or retry uncertain commits. Play unavailable. Only authenticated system success proves install, never data retention; retain unknown outcomes/recovery.
Preview/test: exact current same-project snapshot, isolated storage/simulated effects, Reset/Close. Clipboard preview is simulated with performed:false. Documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/share.file/storage.backupExport/backupImport/database.backupExport/backupImport/presentation/sensors/biometrics: UNAVAILABLE. Database RAM lost on close/reset/pause. Runs as Jarvys; best-effort cleanup. Shared tests run no project JS/browser/device. No rendering/workflow/hardware/persistence/zero-network claims. Rebuild old templates.
Report source/APK/identity/signature/checks/unrun checks/blockers/evidence. Physical update/retention/restore/provider/OEM/ARM64/durability gates remain. F0a fixtures are not APIs. TTS/voice/F1/F2/F3 pending; UX34 gated, UX44 documentary, UX43 last.
