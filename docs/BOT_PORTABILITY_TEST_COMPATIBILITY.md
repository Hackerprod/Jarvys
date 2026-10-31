# Reconstructed test compatibility corrections

These are test-only API adaptations, not a claim of recovered original P2 tests or passing execution. Production compilation previously passed; the first supplemented test compilation failed before any tests ran. No legacy runtime was restored.

Whole-file exclusions with original supplement hashes and individual reasons are in `BOT_PORTABILITY_TEST_COMPATIBILITY_REMOVALS.json`; each removed path was verified absent from the published v98 backup. Mixed/current tests were retained for adaptation. All 31 published v98 tests are retained.

| Test/helper | Change and retained invariant |
|---|---|
| `app/app/src/test/java/com/jarvys/agent/AgentRunRestorationComposeTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. |
| `app/app/src/test/java/com/jarvys/agent/ArtifactHtmlPreviewUiTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/ArtifactSnapshotPreviewRecompositionTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/CodingSubmissionTestSource.java` | Real registry ASYNC_JOB branch, native Crew invocation/checkpoint and returned producer receipt; required project_jobs companion fails if invoked, no fabricated effect receipt. |
| `app/app/src/test/java/com/jarvys/agent/CompactionFlushPersistenceTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/ConnectorArtifactScopeTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/EpisodeEvidencePersistenceTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/HtmlThumbnailCardUiTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. |
| `app/app/src/test/java/com/jarvys/agent/HtmlThumbnailLifecycleUiTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. |
| `app/app/src/test/java/com/jarvys/agent/HtmlThumbnailRendererTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. |
| `app/app/src/test/java/com/jarvys/agent/MainActivityArtifactPreviewGestureTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. Remove obsolete file-memory disclosure setup only; current reflection disclosure and UI assertions retained. |
| `app/app/src/test/java/com/jarvys/agent/MainActivityWorkspaceRoutingTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. Remove obsolete file-memory disclosure setup only; current reflection disclosure and UI assertions retained. |
| `app/app/src/test/java/com/jarvys/agent/MissionEpisodeCaptureTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/PreviewMobileViewportIntegrationTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/PreviewResponsePolicyTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/ProactiveCentralTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/ToolTimelineRecoveryTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/chatdb/ConnectionProjectionTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/proactive/ProactiveActionReceiverTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/proactive/ProactiveAgentProcessorTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/tasks/ScheduledTaskProcessorTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/tasks/TaskManualRunWorkerTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/ui/chat/DeliveredFileUiTest.kt` | Use native generation-bound WorkspaceStore; preserve artifact contents, previews and restoration assertions. |
| `app/app/src/test/java/com/jarvys/agent/ui/chat/MainActivityDrawerNavigationTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. Remove obsolete file-memory disclosure setup only; current reflection disclosure and UI assertions retained. |
| `app/app/src/testFull/java/com/jarvys/agent/flavor/LinuxToolsTest.kt` | Use current FreshStorageBootstrap readiness/stamp API; retain original timing, isolation and behavior assertions. |
| `app/app/src/test/java/com/jarvys/agent/CentralFlowTestApplication.java` | Restore missing host-only Application declaration; callers still explicitly initialize native storage and drive actual service/loop with scripted provider. No storage/authority guard bypass. |

Validation for this slice: `git diff --check`; no build or test execution by this helper. Full/Play compilation and tests remain integrator gates. Removed reset-driven tests are not evidence that attachment startup behavior passed on current generation; current behavior needs native tests rather than restoring obsolete reset APIs.

- Fresh Full compile attempt2 failed after 4m42 with one remaining MainAgentInteractionTest setup reference to removed SingleSessionReset. Replaced only that setup call with current FreshStorageBootstrap; test bodies/assertions unchanged. No test execution or PASS from that attempt.

## Java constructor pass after Full-tests-3

The Full-tests-3 build reached Java compilation after Kotlin compilation, but failed; no execution PASS is implied. Seven assigned Java files only:

- `HtmlPreviewSnapshotTest.java`: use current five-argument StorageGeneration constructor; actual workspace/manifest/asset mutation paths follow `StorageGeneration.NAME`. Retains alien owner and platform symlink checks, tampering and immutable preview assertions.
- `HtmlThumbnailStoreTest.java`: current constructor and exact generation paths for thumbnail cache/manifest/captured CSS. Cache corruption and no-creation assertions still target actual storage, not an absent legacy directory.
- `WorkspaceArtifactDeliveryTest.java`: current constructor and actual generation paths for delivery counts, source tampering and inspection cleanup. Preserve deliberate mismatched project/session fixture in forged-ownership negative test; do not “correct” its identity.
- `DeliveredArtifactTest.java`: current generation constructor including provider fixture; actual current paths replace old generation literals. All artifact integrity, session and provider permission assertions retained.
- `ToolArgumentValidatorTest.java`: generation-bound ordinary workspace fixture preserves exact real-schema nullable argument/default behavior. Removed old memory-enabled parameter is **not** mapped to current `skillsOnly`; the fixture intentionally reads an ordinary file.
- `ProjectImageToolTest.java`: current three-argument Context/project/session constructor; image flow assertions unchanged.
- `WorkspaceAttachmentRecoveryTest.java`: removed `memoryEnabled()` query replaced with actual denied `/memory` read and write operations. Existing delegated attachment/skill/alias/import/coding-scope assertions unchanged.

Static validation: `git diff --check`. No builds run by the helper; all compiler/runtime acceptance remains pending the integrator.
