# Factory guidance factory-guidance-v90 / basics

## SDK/data
SDK2/schema1; 33 capabilities/134 methods including runtime.info. Omitted `factory.json` capabilities select all; an explicit subset remains closed (`CAPABILITY_DENIED`). Generated schema1 `factory-app.json` records the effective list. Methods require capabilities/dependencies, closed typed JSON and awaited Promise/error handling.

`apk_factory` defaults to compact state/counts plus failures and relevant warnings; inspect, test and preview also retain evidence limits. Test still runs the full shared contract but omits passing rows. Its `evidence_limit`: “Shared handlers only: isolated RAM/SQLite, simulated effects; no project JS/WebView, installed behavior, Android grants, persistence or zero-egress proof.”

- runtime.info(): actual capability/configuration/limits metadata, never a grant.
- storage.get(key): string or null. storage.set(key,value): string key/value. storage.remove(key): deletes one key. No startup clear.
- storage.list(): complete sorted key array; TOO_LARGE instead of truncation when it cannot fit one response.
- storage.listPage({afterKey?,limit?}): exclusive sorted key cursor; default limit256, integer1..130816. Returns {keys,hasMore,nextAfterKey,snapshotAcrossPages:false}. Byte bounds shorten pages; use nextAfterKey. Concurrent writes change pages.
- export.text({filename,text,mimeType?}): MIME text/plain, text/markdown, application/json or text/csv. {saved:true} confirms the native save flow, not provider durability/cloud completion; cancellation rejects.
- share.text({text,title?}): {chooserOpened:true} proves no delivery.
- clipboard.write(text): writes the supplied text; no clipboard read. Preview is simulated with performed:false.
- haptics.perform(kind?): tap(default)|longPress, receipt performed.
- device.info(): platform/apiLevel/appId/targetSdk, no stable personal IDs.

Storage keys match [a-zA-Z0-9_.:-]{1,96}; values need valid paired Unicode. Serialize dependent writes. Signed resourceBudgets:{version:1,storage:{totalBytes,entries,valueBytes}} permits partial fields. Defaults:1MiB/256/64KiB; maxima:16MiB/65536/523264 raw-value bytes. Escaped request/result content must still fit 511KiB, full bridge512KiB; whole-map adapter ceiling,not Android disk limit. Lowered quotas preserve reads/deletes/non-growing replacements.

## Native operation boundaries
App-owned effects check packaged capability/page in process. Document/file/photo/audio brokers, share.text/share.file, export.text, clipboard, backups and host handoffs authenticate appId/certificate/source/scope through pinned-host Binder. Android controls permissions, URI grants, the text export picker and share.text chooser; Jarvys adds no approval screen. After host loss, retry when Jarvys is installed and active; never replay an unknown outcome.

Invalid or missing caller/source evidence fails closed; a newer artifact cannot lend identity or scope. Revocation stops future effects, not completed work or open descriptors; receipts do not prove evidence was erased. Cleanup/status report or close owned work; no blind replay.

Backup protocol v2 rechecks before writes/commit; legacy v1 ends at final host authorization.

SDK:16 ordinary/20 total pending with recognized cleanup controls,one pending per control method. Shared ceiling,no guaranteed slots per control; timeout proves no rollback.

## Selected string key-value backup
Declare dataBackup:{"keys":["settings","tasks"]}; dataBackup.keys defaults empty, 0–32 sorted unique storage keys. Nonempty keys require storage+documents,tables database+documents. Both may coexist; no extras,at least one field; receipt/config exact match. Selected strings only; excludes presentation/SQLite/grants/documents/keys. No combined SQLite/storage transaction; allowlist not a secret detector.

- storage.backupExport({version:1,keys,filename}): explicitly select1–32 sorted unique allowed keys. All must exist; missing selected export keys reject the operation.
- storage.backupImport({version:1,keys,handle,mode}): consumes untouched read handle from documents.open({mimeType:"application/json"}). Archive keys must exactly equal selection. No migration/renaming.
- mode insertOnly rejects any existing selected key, even if equal. replaceSelectedKeys inserts/replaces selected keys only, preserving unselected state. A changed selected preimage fails with a conflict; current whole-store quotas recheck.

Canonical plaintext jarvys-preferences-data, formatVersion:1/storageVersion:1, ≤1MiB, ≤32 selected keys, ≤64KiB per value. Expanded live budgets do not expand backup v1; reject incompatibility,no truncation/upgrade. Package/certificate labels are compatibility,not authenticity. Secrets unsupported; strings cannot be reliably screened.

The authenticated artifact and pinned host are checked before backup effects. Android destination selection and provider upload/cloud-sync/retention disclosure remain. Import uses locked replacement, not during I/O. Commit false/exception is unknown: memory/disk may have changed; no rollback or automatic replay.

Export providerCommitConfirmed:false, closeStatus:"close_requested" proves no durability. Empty/partial file possible. Five-minute lifetime/310000ms SDK timeout; documents.cancel shares backup cleanup budget with database.cancel, proves no rollback/closure. No storage.cancel. Both UNAVAILABLE in preview before any storage snapshot/handle allocation.
