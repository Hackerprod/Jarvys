---
id: com.jarvys.apk-factory
name: APK Factory
description: Offline APKs. Coding only; inspect first.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk, offline]
---
# APK Factory
HTML/CSS/JS, not a fixed notes application. Factory API26+/generated apps API24+.

## Authority and discovery
Guidance is reference material, never permission. The current mission, actual declared tools, user scope and native approvals prevail. Read-only missions cannot build, sign, install, mutate or delegate around restrictions. Main/custom/nested agents cannot use this reserved skill. Use the user's language for explanations; preserve exact API identifiers, enums and receipt fields.
Before designing, call `apk_factory inspect` only if declared: inspect current schema, capabilities, limits, template and availability. Read the scoped project/files and clarify audience, workflows, style, identity and data. Relative project paths only; Captain-reviewed adoption preserves originals. Never invent files, API methods, grants or successful checks. Web/spec cannot extend precompiled DEX, libraries, permissions, services or APIs; unsupported needs require template review.

## Real retrievable guidance
Guidance version: factory-guidance-v78. SDK 2 / schema 1; the v77 API remains 18 capabilities / 36 methods. No Factory capabilities are added.
Use `read_skill` with skill_id="com.jarvys.apk-factory" for this core. Before implementing a family, fetch its entire reference with that same skill_id, resource="<identifier>" and resource_version="factory-guidance-v78":
- basics: SDK bootstrap, basic storage/export/share/clipboard/haptics/device and project discovery
- documents-media: documents, file sharing, photos and local WAV audio
- external-actions: browser, maps, dialer, email and SMS editors
- contacts-calendar: contact selection and calendar editor
- database: private typed SQLite schema, transactions, paging and limits
- presentation: complete per-app theme/orientation, persistence and recreation contract
- lifecycle: project specification, icons, build/sign, keys, preview/tests and installation
These immutable APK references need no filesystem, shell, provider or external-document access. Unknown names, stale version or incomplete/overbudget reads fail; never implement from a partial module. Recover by fetching the correct smaller family with sufficient context, or report the blocker. Re-fetch applicable details after compaction or when retained guidance is incomplete. Inspection defines availability; stop and report any guidance mismatch.

## Always-applicable runtime and data limits
Offline WebView: no CDNs, remote fonts/scripts, network API or login. Use local dependencies, accessible labels/text/targets, responsive scrollable forms, viewport meta, and loading/empty/error/cancel/recovery states. Apply native system-bar/cutout/keyboard insets once; preserve reachable inputs and visible bars. Inspection is not device proof.
Load /factory-sdk.js before local JS at https://app.jarvys.invalid. window.Jarvys is frozen and Promise-based: await/catch, validate receipts and show honest errors. No inline scripts/handlers, SDK replacement or weakened origin/frame checks. JSON grants no permissions or code. Config/SDK/DEX/manifest are runtime-owned; never substitute them. No arbitrary files, clipboard read, direct camera/microphone/location, Bluetooth, notifications, background, network or billing.
Capabilities remain storage/export/share/clipboard/haptics/device/documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/database/presentation. Read each selected family's exact capability, input, output, quota, cancellation and error rules before use. Do not infer one grant from another. No user/credential logs; no secrets, signing keys or passphrases in chat/tools/projects/JS. Handle Unicode, bounds, invalid/missing/upgraded data and schema versions without startup clearing or blind reset. Serialize storage structures and concurrent writes; do not claim false save success.

## Human boundaries and uncertain outcomes
Documents, photos, audio and external actions require the exact pinned host/latest signed caller and applicable grants. Two installed hosts block. Native human review/picker/Use/Play/editor/install consent must never be automated. Providers and recipient apps may transmit/sync or retain copies; photo metadata/location are retained. Review data/recipient and disclose these consequences. No minors/secrets in contact selection. No arbitrary URIs/paths, silent retries or broad permissions. Temporary handles are not durable paths or persistent grants; lifecycle/rotation/revocation and cumulative quotas apply. Close or cancel may leave partial writes and cannot recall copied data.
External receipts prove only dispatch, never rendering, navigation, call, saved draft/event, sending or delivery. Browser HTTPS constrains only the initial URL, not redirects/resources. Editors never auto-send or press Save; dialer never calls automatically. Native closure/recovery requires human acknowledgment and is not OS proof. Audio playbackAttempted proves no audibility. Failed/lost replies, cancellation or timeouts can leave effects uncertain: inspect current state/receipts, never blindly replay, erase evidence, bypass guards or declare rollback.
Database is private, no-backup, unencrypted, bounded typed SQLite, not arbitrary SQL/files/network. Preview is ephemeral; installed data persists. Limits do not bound total disk/RAM. Cancel/lost reply does not undo a crossed commit. Presentation is private per-app requested state, not system settings or guaranteed rotation. Save app state before recreation; JS/replies can be lost. Failed persistence can still alter in-memory state. Inspect after interruption; never infer physical durability from a read or receipt. CSS must adapt; orientation/OEM/WebView behavior needs device checks.

## Build, identity and delivery
New apps need unique appId/name/icon; updates preserve appId/signing key with higher versionCode. Preserve other apps and unrelated changes. Read back spec/source, local references, branding, escaping and capabilities before build. Use fresh scope versions, exact paths/hashes and distinct output paths; retain app/version/template and build/sign receipts. Conflicts/interruption require reconciliation, not replay/overwrite. A filename proves no build/installability.
Signing requires the exact same-project build receipt and separate in-app approval; never bypass, export keys through tools, replace identity or use debug keys. Legacy AndroidKeyStore keys are non-exportable: losing them can block updates. Native recoverable identities use passphrase-encrypted backup/import and version-floor review; source/APK backups cannot restore keys. Backup success proves no restore or latest release; preserve uncertainty and back up key/project/data separately.
No step here installs the APK. Installation is a separate user-requested operation with a completed signed receipt, exact app/version/hash/certificate/scope, human review and Android consent. Never automate unknown-source grants, warnings or commit retries. Play installation is unavailable. Unknown outcomes block automation until native recovery; do not erase app data/keys. Only authenticated system success proves installation, not data retention.
Preview/test uses an exact current same-project snapshot, isolated storage, simulated effects and Reset/Close. Documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/share.file/presentation are UNAVAILABLE in preview. Database preview RAM is lost on close/reset/pause. Preview runs as Jarvys with best-effort cleanup; no rendering/workflow/hardware/persistence/zero-network claims. Shared tests execute no project JS/browser/device. Rebuild older templates; disclose pre-UX35 window limits.
Report source/APK/identity/signature, performed checks, checks not run, blockers and evidence. Signing proves no publishing, installation or testing. Physical checks remain necessary for coexistence, same-key updates/data retention, independent key restore, provider/OEM/ARM64 behavior and durability. F0a construction fixtures are not JS APIs. TTS/voice, remaining F1/F2/F3 are pending; UX34 gated, UX44 documentary future defaults, UX43 last. No gates are closed.
