# Mixed search/memory supplement compatibility inventory

Test-only reconstruction against the current published production APIs. No legacy runtime or schema restored. No tests executed by this helper; compilation/execution remain required integrator gates.

## MemorySearchIndexTest.kt

Retained unchanged tokenizer, BM25 ordering, snippets/secret classification, corrupt/versioned cache rebuild, randomized incremental/full rebuild equivalence, and large-corpus tests. Background rebuild test changes only the removed coordinator boolean constructor argument.

Retired specific methods because their subject is removed, not because their assertions failed:
- `memoryExportDoesNotIncludeThePrivateDerivedIndex`: removed MemoryStore ZIP export/seeds; there is no current equivalent file-memory export.
- `freshScopedSearchTracksWriteEditDeleteUndoRestoreClearAndReflectionUndoWithoutCachingMemory`: removed MemoryStore file revision/reflection lifecycle; not represented as current Engram lifecycle coverage.
- `memoryChangesStayInOwningChatAndNeverEnterAnotherPersistedIndex`: removed per-chat MemoryStore source authority.

Current replacements exercise actual coordinator rejection of the removed memory namespace even with retained derived memory payload, snapshot filtering, current workspace source refresh, and separate workspace/index ownership. These are not claims that Engram mutation or export behavior was tested.

## MemorySearchToolTest.kt

Preserved actual registry search of workspace and skill files, stable snippet evidence, limit/zone validation, symlink exclusion, secret-snippet redaction, catalog/crew exclusion and scheduled approved-skill filtering. Source contract now excludes file-memory, so the old fourth `/memory` hit and revision label are replaced with explicit denied-memory-zone/read assertions; three actual allowed hits remain asserted, not a relaxed count. Reflection-file tool construction is intentionally removed. Scheduled catalog now asserts **only search_files** and no workspace/file-memory access; the obsolete memory-on/off filesystem catalog is not restored. Normal workspace cannot masquerade as skills-only scheduled scope.

## NativeMemoryActionRuntimeTest.kt

Removed MemoryStore/MemoryNativeActions no longer exist. Original file-memory CRUD is replaced by denial across write/edit/read/delete catalog with ordinary workspace write/read retained. Mutable argument probe remains live through the actual registry (single getter read and exact persisted captured content), now for ordinary files rather than forbidden memory paths. Cancellation keeps an actual cancelled token and proves no file created. Current MemoryUiAutomationGuard tests changed-view rejection without effects. Actual MemoryNamespace selection proves personal/global scope selection cannot grant an unknown project owner.

Retired legacy-only compound directory revision count and reflection-group undo method: these depend on removed filesystem revision/group APIs, not current Engram semantics. No fake namespace witness, no blanket memory permission, and no unsupported claim of Engram write/revision coverage was substituted.
