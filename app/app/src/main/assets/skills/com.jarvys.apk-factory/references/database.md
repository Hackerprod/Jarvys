# Factory guidance factory-guidance-v86 / database

## Private typed SQLite
database:no per-query approval/broker/permission. Private unencrypted no-backup SQLite persists; preview RAM lost on close/reset/pause. No raw SQL/path/ATTACH/extensions.
- database.info({}) → current schema/version (initial0).
- database.migrate({fromVersion,toVersion,steps}): atomic adjacent versions0..1000,1..16 steps createTable:{kind,table,columns} or addColumn:{kind,table,column,definition}. Definition {type,nullable}, type text|integer|real|boolean; addColumn must be nullable. No drop/rename/index/type-change.
- database.transact({version,operations}): atomic insert/update:{kind,table,id,values} or delete:{kind,table,id}. Nonempty values, required insert columns, no duplicate insert; missing update fails. Exact schema version.
- database.select({version,table,where?,afterId?,limit?}): AND equality on columns/null or id/non-null, exclusive binary-ascending id cursor. Default min(50,signed page bound); rows,hasMore,nextAfterId. No multi-page snapshot; shrink pages on byte overflow.
- database.close({}) keeps installed data. database.cancel({}) returns rollbackConfirmed:false; cancellation/reply loss can follow commit. Inspect state, no automatic replay/reset.

id [A-Za-z0-9_-]{1,96}; table/column [a-z][a-z0-9_]{0,31}, excluding id and sqlite_/factory_ prefixes. Typed finite scalars/nullable null; integers±9007199254740991,paired text/no NUL. Do not log SQL-like values/private rows.
Signed resourceBudgets.database defaults/maxima: mainFileBytes16MiB/1GiB (multiple4096),rows10000/1000000,tables8/16,columnsPerTable16/32,cellBytes4096/261120,rowBytes16384/261120,selectPageRows100/23738,batchOperations64/2046. cellBytes≤rowBytes. Fixed schema64KiB, request/result256KiB, shared bridge token bound8192 can bind earlier than row/batch counts. Main-file cap excludes journal/temp/cache/heap. Lowered quotas retain reads/deletes/non-growing changes. No total disk/RAM guarantee.

## Selected-table backup v1
dataBackup.tables defaults empty; empty tables list disables backup. Explicit sorted unique≤8 table names and database+documents are required; independent keys selection is in basics. Receipt/config exact match.
- database.backupExport({version,tables,filename}): explicitly select nonempty allowed table subset; version exact1..1000 current schema. No implicit export-all.
- database.backupImport({version,handle,tables,mode}): untouched read handle from documents.open({mimeType:"application/json"}); exact archive/selection/table schema/current version. No migration.
- insertOnly: any selected ID collision rolls back whole transaction. replaceSelectedTables: atomically replace selected rows only, preserve unselected tables. Changed reviewed targets/schema fail conflict.

Canonical plaintext jarvys-sqlite-data formatVersion:1≤1MiB; frozen backup bounds≤8tables/16columns/10000rows/4KiBcell/16KiBrow. Expanded live schemas/data that cannot fit reject; no silent truncation/backup format upgrade. Selected schemas/rows+version/package/certificate labels,not SQL/ZIP/preferences/keys/files. Labels compatibility,not authentication. Secrets unsupported,not reliably detectable.

Exact artifact/pinned host: ALLOW_ALWAYS continues,ASK all values/selection/consequences once,DENY blocks. Android export selection/cloud-sync/retention disclosure remain. Protocol v2 checks live authority before each write/import commit; legacy v1 stops at final host authorization.

Export bytes/hash plus providerCommitConfirmed:false/closeStatus:"close_requested" proves no durable file or successful restore; failure can leave empty/partial file. Import transaction receipt proves no physical durability. Cancel/lost reply/timeout may follow commit; inspect state and never automatically retry. Five-minute operation/310000ms SDK timeout; database.cancel/documents.cancel share native backup control admission. UNAVAILABLE in preview before database/handle allocation. Device/provider/restore/update acceptance remains unverified.
