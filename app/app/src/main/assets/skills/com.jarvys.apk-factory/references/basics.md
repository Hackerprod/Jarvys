# Factory guidance factory-guidance-v84 / basics

Use with the always-loaded Factory core; this reference grants no tools or approvals.
Historical v77 contract text below is preserved. Current v84: 20 capabilities/46 methods; selected string key-value backup is in basics and SQLite backup is in database.
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
- clipboard.write(text): native confirmation; no read.
- haptics.perform(kind): tap/longPress; returns performed.
- device.info(): platform/apiLevel/appId/targetSdk; no stable personal IDs.
Version storage; handle missing/invalid/upgraded data; no startup clears/false save success. Human export/share; cancel≠delivery.

## Selected string key-value backup (v84)

The catalog remains 20 capabilities with 46 methods, SDK 2 and schema/protocol 1. These APIs back up only selected strings in the generated app's Jarvys.storage key-value store. They do not back up presentation theme/orientation preferences, all app preferences, Android grants, SQLite, signing identities, arbitrary files or document contents. There is no combined SQLite/storage transaction and no new capability or Android permission.

Declare optional dataBackup:{"keys":["settings","tasks"]}. dataBackup.keys defaults empty, allows 0–32 sorted, unique names matching [a-zA-Z0-9_.:-]{1,96}, and independently requires storage+documents when nonempty. dataBackup may contain keys, legacy tables, or both, with at least one field and no extras. Legacy tables-only serialization and SQLite behavior remain unchanged; empty keys are omitted when serializing. Nonempty tables still require database+documents independently. The signed receipt must exactly match both embedded policies. An allowlist grants no approval and is not a secret detector.

- Jarvys.storage.backupExport({version:1,keys,filename}): select 1–32 sorted, unique allowed keys explicitly. Every selected key must exist; missing selected export keys reject the whole operation. filename uses the existing simple document filename grammar. Export never selects all allowed keys implicitly.
- Jarvys.storage.backupImport({version:1,keys,handle,mode}): consumes an untouched document read handle opened through documents.open({mimeType:"application/json"}) and the normal human selection/Use steps. The archive key set must exactly match the nonempty explicit selection. version is exactly the integer 1, not the SQLite database version. No migration or key renaming occurs.
- mode:"insertOnly": any existing selected key is a collision, even if its value equals the archive value. mode:"replaceSelectedKeys": writes only selected archive values, replacing selected existing keys or inserting selected absent keys. It never clears the store or deletes unselected keys. The reviewed selected-key preimage must still match before mutation; changed selected targets require fresh review. Unselected state is preserved and current whole-store quotas are rechecked.

The canonical plaintext JSON format is jarvys-preferences-data with formatVersion:1 and storageVersion:1, at most 1 MiB. It contains selected string keys/values plus package/certificate compatibility labels. Values require valid paired Unicode. Existing string limits remain: 64 KiB UTF-8 per value, 256 total store entries and 1 MiB total key/value bytes; archive escaping/metadata also count toward the independent archive limit. It is unencrypted. Labels are not authentication of the author or contents; an attacker can rewrite them. Secrets are unsupported, and arbitrary strings cannot be reliably detected as secret-free.

Installed generated app, exact current signed receipt and matching pinned Jarvys host are required. Native human review shows all outgoing or incoming values, selected keys, counts, mode and overwrite impact and requires explicit all-values approval. Export then discloses the provider and asks for a destination; it may upload, cloud-sync or retain plaintext copies. Never automate native approval, review, Use or picker actions. File selection and compatible labels do not authorize restore.

Import uses one bounded store replacement under the same transaction lock as ordinary storage, after validation and review-preimage checks. No lock spans human review or provider I/O. A backend commit returning false or throwing is an unknown outcome: memory or disk may already have changed, no rollback is promised and no automatic replay is allowed. Cancellation, process death, reply loss or timeout can follow mutation. Inspect state before a new human-reviewed attempt; a receipt proves no physical durability or independent recovery.

Export returns providerCommitConfirmed:false and closeStatus:"close_requested"; provider close does not prove durable storage, cloud completion or recoverability. Failed or cancelled exports may leave empty/partial files. The operation lifetime is five minutes and both backup families use a 310000 ms SDK timeout with an ambiguity warning. documents.cancel uses the existing authenticated cancellation path for storage-only apps; database.cancel and documents.cancel share the native backup control rate budget. SDK admission reserves one slot for each of documents.cancel, database.cancel, sensors.cancel and biometrics.cancel alongside 16 ordinary requests (20 maximum). Cancellation proves neither rollback nor provider closure; cleanup may remain busy. No new storage.cancel method exists.

Both storage backup methods are UNAVAILABLE in preview before any storage snapshot, document handle or database is allocated. Ordinary preview storage remains isolated. Lifecycle revocation, temporary-handle ownership and recovery admission still apply; no background/resume permission, durable recovery guarantee or automatic retry is added. Physical Android, provider, installation/update and real-data restore gates remain open.
