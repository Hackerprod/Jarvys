# Mixed native test reconstruction inventory

Test-only reconstruction against the current production APIs. These edits are not execution evidence or a PASS. No production compatibility facade or file-based memory implementation is restored. Published v98 tests are retained.

| File | Removed/changed API | Current invariant and reconstruction |
|---|---|---|
| `MemoryWriteIntegrationTest.kt` | `SingleSessionReset`; mandatory `MemorySaveOptions`; save schema `text/kind` | Use native fresh bootstrap, explicit default save options, and `title/content/type`. Preserve real Room write gates, stale toolset rejection, user save while automatic writes are off, and causal evidence/compaction conservation assertions. |
| `PersonalAgentBehaviorTest.kt` | File `MemoryStore`, implicit save options, `personalAgentCorrect`, generation constant | Legacy data exclusion uses inert file canaries rather than resurrecting a legacy writer. Native Room remains prompt authority. Profile correction uses explicit `personalAgentSetDirective(...supersedesId)` with saved/superseded receipts; retains active replacement/profile prompt checks. Worker generation uses fresh bootstrap. |
| `PersonalMemoryConsolidationGateTest.kt` | Mandatory `MemorySaveOptions` | Explicit default options only; latches, unchanged source revisions, guarded late output and successful retry assertions remain. |
| `SingleSessionMemoryRetentionTest.kt` | Mandatory options, removed generic correction API | Explicit defaults at old five-argument call sites. Replacement tests use native save with `supersedesId`; they still require outgoing supersedes links, retained historical evidence and receipt revision matching the replacement row. They do not conflate native in-place update with supersession. |
| `CodexImageGenerationToolScopeTest.kt` | File-reflection tool factory and reset bootstrap | Use fresh bootstrap and current native memory extraction toolset. Require its memory-save capability and absence of image generation; existing credential/provider, Crew/delegation exclusion and prompt/privacy assertions retained. No claim that removed ls/read/write reflection tooling exists. |
| `UserDecisionScopeTest.kt` | File-reflection factory and reset bootstrap | Current extraction scope must contain memory_save, not generic write, interactive decision, or task-management tools. Existing main/Crew/proactive/delegated authority assertions retained. |
| `WorkspaceStoreTest.kt` | Context constructor boolean; removed memory mount/delete API | Current Context constructor preserves canonical alias test. Replaced file-memory mount lifecycle cases with explicit denial of `/memory` read/list/write (including trimmed alias and traversal), absence of delete capability, no directory creation, and conservation of actual workspace and installed skill content. Engram namespace lifecycle is tested by native memory tests, not a fake file mount. |
| `ui/Th1VisualCaptureTest.kt` | Deleted MemorySettingsScreen/MemoryStore | Gallery renders actual `PersonalMemoryScreen`; readiness waits on its current `memory-screen-scroll` semantics node. Retains both-theme scene capture and hash checks; does not fabricate old reflection UI. |

Related owned slices have separate ledgers: `BOT_PORTABILITY_MIXED_SMALL_TESTS.md` (Room/task/connector/effect cases) and `BOT_PORTABILITY_MIXED_MEMORY_SEARCH_TESTS.md` (index/search/native memory actions).

## Complete assigned 22-file inventory

The eight rows above plus the following fourteen files comprise this assigned slice (not the separate baseline/bootstrap compatibility batch):

- Search/native actions ledger: `MemorySearchIndexTest.kt`, `MemorySearchToolTest.kt`, `NativeMemoryActionRuntimeTest.kt`.
- Small/native effects ledger: `CoreMcpToolNameTest.kt`, `EpisodeNarrationTest.kt`, `MainHeaderLiveStateRecoveryTest.kt`, `chatdb/ChatReadProgressTest.kt`, `chatdb/JarvysChatDatabaseM0Test.kt`, `chatdb/PersonalMemorySearchFallbackTest.kt`, `connectors/AutonomyAuditPersistenceTest.kt`, `connectors/ConnectionPreferencesOwnerTest.kt`, `tasks/TaskManagementToolsTest.kt`, `tasks/TaskStoreTest.kt`, `WorkspaceEffectReceiptTest.kt`.

Every path is relative to `app/app/src/test/java/com/jarvys/agent/`. Execution results must be attached separately against the final source hash. Replacing a removed API's test subject is an explicitly scoped contract reconstruction, not evidence that its legacy behavior still exists.

## Java compiler follow-up (Full tests-3)

- `coding/CodingJobEvidenceTest.java`: current pure metadata constructors use an explicit canonical `technicalStoreGeneration`, `SemanticCapture`, `Source.rootCentral`, and recorded `CrewInputContext`. Tests retain revision/CAS identity conservation, idempotency, terminal/uncertainty/absence constraints, strict duplicate/unknown/malformed fields, exact numeric/boolean parsing, and caller input budgets. Input references plus unresolved IDs round-trip with PARTIAL coverage; foreign execution references reject. Historical Central root is preserved separately from child call/input metadata and is not authority. Removed CaptureMode conflation is replaced by testing ENABLED/DISABLED independently of optional historical root; capture changes and technical generation changes cannot cross a revision. Old envelope version and legacy capture/input keys explicitly reject. No manager, IO, receipt or runtime source is minted by these pure value tests.
- `AgentModelTurnLimitTest.java`: only `reflectionRetainsTwelveTurnCapFailureWrappingAndNoAutomaticRetry` is retired: its subject was the removed MemoryReflectionWorker/file-memory reflection policy, not a current Core loop contract. The ordinary run completing 130 tool rounds with exactly 131 provider calls and 130 retained results remains unchanged. Safe structural turn-limit diagnostic identity/redaction test remains unchanged. No replacement policy/12-turn production limit is invented.

These two files are a separate follow-up to the assigned 22-file inventory; no build or test execution is claimed here.
