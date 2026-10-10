# Factory guidance factory-guidance-v78 / database

Use with the always-loaded Factory core; this reference grants no tools or approvals.

## Database (v76)
database requires database only; no host/permissions/per-query confirmation. info/close/cancel: {} optional. Fixed private no-backup unencrypted SQLite: installed persists/close retains; preview RAM loses data on close/reset/pause. No SQL/path/ATTACH/extensions/network/import/export.
info(): schema/version0. migrate({fromVersion,toVersion,steps}): atomic adjacent versions0..1000; {kind:"createTable",table,columns} or {kind:"addColumn",table,column,definition}, nullable only. Definition {type,nullable}: text/integer/real/boolean + Boolean. No drop/rename/index/type change.
transact({version,operations}): atomic {kind:"insert"|"update",table,id,values} or {kind:"delete",table,id}. Nonempty values; insert required columns/no duplicate; missing update fails.
select({version,table,where?,afterId?,limit?}): AND equality: columns/null or id/non-null; exclusive binary ascending id cursor, default50/max100; rows/hasMore/nextAfterId; no multipage snapshot. transact/select need exact schema version.
id:[A-Za-z0-9_-]{1,96}; table/column:[a-z][a-z0-9_]{0,31}, excluding id and sqlite_/factory_ prefixes. Typed scalars/nullable null; paired text/no NUL.
Bounds:8 tables/16 columns+id/16 steps/64ops/10000 total rows;4KiB cell/16KiB serialized row/256KiB args/results; JSON depth8/tokens1024. 16MiB main DB excludes journal/temp/cache/cursor/JVM/RAM; no total-disk/RAM cap. Shrink pages on overflow.
cancel(): rollbackConfirmed:false. Cancel/lost reply cannot undo/disprove crossed commit. Inspect state; no auto replay/reset. Handle errors/no SQL/value logs. No device durability/update proof.
