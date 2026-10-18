# Factory guidance factory-guidance-v90 / database

## Private typed SQLite
Private unencrypted no-backup SQLite; preview RAM is lost on close/reset/pause. No SQL/path/ATTACH/extensions.

Exact keys only. `database.info/close/cancel` take `{}`; info returns schema/version/mode/persistence/limits (new DB version 0), close retains installed data, cancel returns `rollbackConfirmed:false`. Cancellation/lost reply may follow commit; inspect, never replay/reset.

`database.migrate({fromVersion,toVersion,steps})`: adjacent integer versions 0..1000; 1..16 atomic steps; no drop/rename/index/type change. `createTable` is exactly `{kind,table,columns}`; **columns is an object map**, name→`{type,nullable}` (not array). `addColumn` is `{kind,table,column,definition}` with the same definition shape; new columns must be nullable. Types: `text|integer|real|boolean`.

`database.transact({version,operations})`: exact version, atomic; ops are insert/update `{kind,table,id,values}` or delete `{kind,table,id}`. `values` is nonempty; insert needs all non-nullable columns; duplicate insert/missing update fail. `database.select({version,table,where?,afterId?,limit?})`: exact version; `where` maps columns/`id` to ANDed equality (`null` is `IS NULL`, `id` non-null); `afterId` is exclusive binary-ascending. `limit` is 1..23738; default min(50, configured page bound); `info.limits.selectRows` may cap it. Returns `rows,hasMore,nextAfterId,snapshotAcrossPages:false`; shrink pages on byte overflow.

Validator-tested examples `[method,args]`:
<!-- database-contract-examples -->
```json
[["database.info",{}],["database.migrate",{"fromVersion":0,"toVersion":1,"steps":[{"kind":"createTable","table":"notes","columns":{"title":{"type":"text","nullable":false}}}]}],["database.transact",{"version":1,"operations":[{"kind":"insert","table":"notes","id":"n1","values":{"title":"Read docs"}},{"kind":"update","table":"notes","id":"n1","values":{"title":"Done"}},{"kind":"delete","table":"notes","id":"n1"}]}],["database.select",{"version":1,"table":"notes","where":{"title":"Read docs"},"afterId":"n0","limit":20}],["database.close",{}],["database.cancel",{}]]
```

Names match `[a-z][a-z0-9_]{0,31}` except `id` and `sqlite_`/`factory_` prefixes; IDs match `[A-Za-z0-9_-]{1,96}`. Values: valid Unicode text without NUL, safe integers (±9007199254740991), finite real, boolean, or nullable null matching column types. Never log them.

`resourceBudgets.database` defaults/maxima: mainFileBytes=16MiB/1GiB (4096 multiple); rows=10000/1000000; tables=8/16; columnsPerTable=16/32; cellBytes=4096/261120; rowBytes=16384/261120; selectPageRows=100/23738; batchOperations=64/2046. cell≤row; schema=64KiB; request/result=256KiB; bridge token≤8,192 bytes may apply first. No journal/temp/cache/heap budget. Lowered quotas keep reads/deletes/non-growing writes; no total disk/RAM guarantee; no physical durability guarantee. Device/provider/restore/update acceptance unverified.

## Selected-table backup v1
`dataBackup.tables` defaults empty; sorted unique ≤8; requires `database`+`documents`. See `basics` for shared backup rules. `database.backupExport({version,tables,filename})` selects a nonempty allowed subset at exact schema version 1..1000, never all. `database.backupImport({version,handle,tables,mode})` needs an untouched JSON read handle from `documents.open`; archive, selection, schema and version must match; no migration. `insertOnly` aborts on selected ID collision; `replaceSelectedTables` atomically replaces selected rows; target/schema changes conflict.

Plaintext `jarvys-sqlite-data` v1 ≤1MiB; frozen limits 8 tables/16 columns/10000 rows/4KiB cell/16KiB row. Oversized data rejects (no truncation/upgrade). Archive has selected schema/rows, version, package/cert labels; excludes SQL/ZIP/preferences/keys/files. Labels are compatibility, not authentication; secrets unsupported.

Shared backup: see `basics`. Preview is `UNAVAILABLE` before DB/handle allocation.
