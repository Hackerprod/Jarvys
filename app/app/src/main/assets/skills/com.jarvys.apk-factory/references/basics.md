# Factory guidance factory-guidance-v84 / basics

Use with the always-loaded Factory core; this reference grants no tools or approvals.
Historical v77 API/data contracts below are retained with reviewed operation-policy updates; forced host confirmations are superseded. Current v84: 20 capabilities/46 methods; selected string key-value backup is in basics and SQLite backup is in database.
Sensors and biometrics extend the historical contract in their references, with all six methods UNAVAILABLE in preview.
Only selected biometric profiles add USE_BIOMETRIC; template/nonbiometric profiles stay zero-permission.
Historical totals/permission and pending-family statements below describe their original versions.

# APK Factory
HTML/CSS/JS, not a fixed notes application.
Factory API26+/apps API24+.
## Contract
`apk_factory inspect`: schema/capabilities/limits/template/availability. Read scope/files; relative paths/Captain-reviewed adoption. Clarify audience/workflows/style/identity/data. New: unique ID/name/icon; update: same appId/key, higher versionCode. Preserve other apps. Web/spec cannot extend precompiled DEX/libraries/permissions/services/APIs; unsupported: template review.
## Capabilities
Offline WebView: no CDNs/remote fonts/scripts/API/login. Accessible text/targets; loading/empty/error/recovery.
Responsive/scrollable forms, viewport meta. Native bar/cutout/keyboard insets once; reachable inputs/visible bars.
Capabilities: storage/export/share/clipboard/haptics/device/documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/database/presentation. No arbitrary files/clipboard read/direct camera/microphone/location/Bluetooth/notifications/background/network/billing. JSON grants no permissions/code.
Await/catch; no user/credential logs/preview native-effect claims.
Load `<script src="/factory-sdk.js"></script>` before local JS at https://app.jarvys.invalid; no inline scripts/handlers or SDK replacement/weakened origin/frame checks.
window.Jarvys frozen Promises:
- runtime.info(): metadata, no grant.
- storage.get(key): string/null; set(key,value): strings; remove(key): delete; list(): keys. Serialize structures.
- export.text({filename,text,mimeType}): optional MIME: text/plain, text/markdown, application/json, text/csv. {saved:true} confirms save; cancel rejects.
- share.text({text,title}): optional title; {chooserOpened:true} proves no delivery.
- clipboard.write(text): authenticated host policy (ALLOW_ALWAYS/ASK/DENY); no read. Preview is simulated with performed:false.
- haptics.perform(kind): tap/longPress; returns performed.
- device.info(): platform/apiLevel/appId/targetSdk; no stable personal IDs.
Version storage; handle missing/invalid/upgraded data; no startup clears/false save success. Human export/share; cancel≠delivery.

## Factory operation permissions

Settings → Factory permissions starts with ALLOW_ALWAYS. The user can choose ASK or DENY as the default and override each operation for each authenticated app. “Use default” removes that override. The protected permission editor cannot be operated by an agent to expand its own authority. Factory permissions are separate from Coding autonomy.

The configurable operations are exactly:
- documents.open, documents.create
- photos.pick, photos.capture, audio.play
- browser.open, contacts.pick, share.file
- maps.open, phone.dial, email.compose, sms.compose, calendar.insert
- database.backupExport, database.backupImport
- storage.backupExport, storage.backupImport
- factory.sign, factory.install, clipboard.write

ALLOW_ALWAYS advances without a Jarvys approval prompt. ASK asks once for the exact request, then continues without duplicate Use, Play, all-values, sign or install confirmations. DENY prevents that operation's effects. Android document/photo/contact selection, recipient choice when no verified default exists, genuine Android grants, biometric authentication and installer consent remain when required. This is saved application authority, not a bypass for Android security. Capability declarations and backup table/key selections still constrain scope.

Identity is appId plus authenticated certificate, never an Intent label. First signing uses a project-bound new-identity subject; explicit choices transfer to the resulting certificate. Atomic persisted policy revisions invalidate affected pending grants after a default/override change. “Revoke this app’s pending operations” persists revocation without changing future policy. Unreadable or failed policy persistence fails closed. A grant binds one exact request and cannot survive relevant policy revision, lifecycle or identity loss.

New backup protocol v2 authenticates both Binder endpoints and checks live policy before each export write and import commit. Legacy backup protocol v1 remains authenticated, but its final host authorization is the last enforceable checkpoint; older DEX has no later callback. Already-open file descriptors cannot be revoked retroactively, nor can completed writes, clipboard copies, installation commits or recipient copies be undone. New share-file opens recheck authority; an already-open recipient descriptor may remain usable. Never claim cancellation proves rollback or external task closure.

The latest signed APK receipt still authenticates an installed broker caller. Signing a newer APK can make the previously installed APK unavailable until updated; per-artifact trust history is deferred. Already-generated APKs retain their packaged DEX: rebuild/sign/install an update to obtain new clipboard and backup runtime behavior. No arbitrary compiler is added; a new app packages the precompiled, authenticated DEX unchanged.

This is a 20-operation policy, not a switch for all 46 SDK methods. share.text, export.text, local storage, ordinary SQLite, haptics, device information, presentation, sensors and biometrics retain their direct runtime contracts. Clipboard uses the authenticated host in the new runtime; preview stays simulated with performed:false and never writes the system clipboard. Byte/file/row/rate/deadline budgets remain technical quotas, not permission settings. Dispatch, chooser return and cleaned-up audio need no invented success attestation; unknown outcomes still require reconciliation and are never replayed automatically.

## Selected string key-value backup (v84)

The catalog remains 20 capabilities with 46 methods, SDK 2 and schema/protocol 1. These APIs back up only selected strings in the generated app's Jarvys.storage key-value store. They do not back up presentation theme/orientation preferences, all app preferences, Android grants, SQLite, signing identities, arbitrary files or document contents. There is no combined SQLite/storage transaction and no new capability or Android permission.

Declare optional dataBackup:{"keys":["settings","tasks"]}. dataBackup.keys defaults empty, allows 0–32 sorted, unique names matching [a-zA-Z0-9_.:-]{1,96}, and independently requires storage+documents when nonempty. dataBackup may contain keys, legacy tables, or both, with at least one field and no extras. Legacy tables-only serialization and SQLite behavior remain unchanged; empty keys are omitted when serializing. Nonempty tables still require database+documents independently. The signed receipt must exactly match both embedded policies. An allowlist grants no approval and is not a secret detector.

- Jarvys.storage.backupExport({version:1,keys,filename}): select 1–32 sorted, unique allowed keys explicitly. Every selected key must exist; missing selected export keys reject the whole operation. filename uses the existing simple document filename grammar. Export never selects all allowed keys implicitly.
- Jarvys.storage.backupImport({version:1,keys,handle,mode}): consumes an untouched document read handle opened through documents.open({mimeType:"application/json"}) and Android selection under the saved documents.open policy, with no extra Use step. The archive key set must exactly match the nonempty explicit selection. version is exactly the integer 1, not the SQLite database version. No migration or key renaming occurs.
- mode:"insertOnly": any existing selected key is a collision, even if its value equals the archive value. mode:"replaceSelectedKeys": writes only selected archive values, replacing selected existing keys or inserting selected absent keys. It never clears the store or deletes unselected keys. The reviewed selected-key preimage must still match before mutation; changed selected targets require fresh review. Unselected state is preserved and current whole-store quotas are rechecked.

The canonical plaintext JSON format is jarvys-preferences-data with formatVersion:1 and storageVersion:1, at most 1 MiB. It contains selected string keys/values plus package/certificate compatibility labels. Values require valid paired Unicode. Existing string limits remain: 64 KiB UTF-8 per value, 256 total store entries and 1 MiB total key/value bytes; archive escaping/metadata also count toward the independent archive limit. It is unencrypted. Labels are not authentication of the author or contents; an attacker can rewrite them. Secrets are unsupported, and arbitrary strings cannot be reliably detected as secret-free.

Installed generated app, exact current signed receipt and matching pinned Jarvys host are required. The backup operation policy supplies ALLOW_ALWAYS authority or one ASK all-values approval showing selected keys, counts, mode and overwrite impact; DENY blocks. Export discloses that the provider may upload, cloud-sync or retain plaintext copies and retains Android destination selection, with no second host approval. File selection and compatible labels alone do not authorize restore. New protocol v2 checks live authorization before each write/commit; authenticated legacy v1 ends at the final host authorization.

Import uses one bounded store replacement under the same transaction lock as ordinary storage, after validation and review-preimage checks. No lock spans human review or provider I/O. A backend commit returning false or throwing is an unknown outcome: memory or disk may already have changed, no rollback is promised and no automatic replay is allowed. Cancellation, process death, reply loss or timeout can follow mutation. Inspect state before a fresh policy-authorized attempt; a receipt proves no physical durability or independent recovery.

Export returns providerCommitConfirmed:false and closeStatus:"close_requested"; provider close does not prove durable storage, cloud completion or recoverability. Failed or cancelled exports may leave empty/partial files. The operation lifetime is five minutes and both backup families use a 310000 ms SDK timeout with an ambiguity warning. documents.cancel uses the existing authenticated cancellation path for storage-only apps; database.cancel and documents.cancel share the native backup control rate budget. SDK admission reserves one slot for each of documents.cancel, database.cancel, sensors.cancel and biometrics.cancel alongside 16 ordinary requests (20 maximum). Cancellation proves neither rollback nor provider closure; cleanup may remain busy. No new storage.cancel method exists.

Both storage backup methods are UNAVAILABLE in preview before any storage snapshot, document handle or database is allocated. Ordinary preview storage remains isolated. Lifecycle revocation, temporary-handle ownership and recovery admission still apply; no background/resume permission, durable recovery guarantee or automatic retry is added. Physical Android, provider, installation/update and real-data restore gates remain open.
