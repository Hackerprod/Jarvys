> Historical R1 candidate: independent review **FAIL**. Its host results remain valid only for its stated source. See [R2 repair candidate](BOT_PORTABILITY_P2_CANDIDATE_R2.md); independent R2 review pending.

# P2 implementation candidate — matching host acceptance, independent review pending

2026-10-31. Production/test source: `dd2a629671c7e9584a6d4df18ee16f4b4fd2102e`. **Full 1,090/0 and Play 1,090/0**, 117 classes per flavor, no errors or skipped cases. The included ten-class pre-write cohort is **105/0 per flavor**, not additional tests. A separate Full worker-focused39/0 is also not added to acceptance. This is an implementation candidate, **not independent P2 PASS or delivery approval**. No APK/signing/version change, physical-device, installation, live-provider or full upstream parity claim.

Both builds and tests use identical 1,981 app/testing files. Canonical source-binding SHA256 (`json.dumps(binding, sort_keys=True, separators=(',', ':'))`, UTF8): `eaed237a147ce794d22a50b7e656112f6a3d9cb2fcb2953f074ed7082c59f722`. Before/after build bindings and current file bytes match. Class list SHA256: `f40797a38d9ae7cc64c0df21bf05ffaa0bbb15be80f29ae6c01055d25cd4f1d8`. [Sanitized per-class results](BOT_PORTABILITY_P2_CANDIDATE_RESULTS.json), [exact cohort](BOT_PORTABILITY_P2_ACCEPTANCE_CLASSES.json), [pre-write chronology](BOT_PORTABILITY_P2_PREWRITE_GATE.md).

## Provenance and limits

This is fresh reconstruction from verified v98 backup7d96ba6, supplemented at5075054 with explicitly inventoried missing resources. Neither unavailable2025d787 nor its former965 tests validate these bytes. [Incident/resource provenance](BOT_PORTABILITY_RECONSTRUCTION.md), [restored fixture inventory](BOT_PORTABILITY_P2_HOST_FIXTURE_INVENTORY.md), [main-chat inventory](BOT_PORTABILITY_P2_MAIN_CHAT_FIXTURE_INVENTORY.md). Diagnostic failures and build failures remain recorded; final success does not relabel them.

The independently accepted [reconstruction design](BOT_PORTABILITY_P2_RECONSTRUCTION_DESIGN.md) remains the contract. User direction to inspect actual upstream replaces the withdrawn invented quotas. OMB pin7de00e4 supplies depth4/requests64/executions96/wakes48 per durable progress window, initial Central execution1, qualified open-root progress resets, retained needs_input49 followed by budget cancellation, terminal reports uncapped. These are not provider-call totals, monetary caps or permission for unlimited spend. OpenDuet defaults are not blended. P4 policies remain unactivated.

Current-generation Registry v1→v2 is additive/transactional, preserves existing rows/types and has no legacy import, deletion or app-open enrollment. Local admission is enabled only for this acceptance candidate after both pre-write gates. **Old binary downgrade after new writes is unsupported**; keeping v98 does not make downgrade safe. Preserve all user data, never clear/reinstall to repair a failed guard.

## Acceptance/reviewer map

All paths below are under `app/app/src/main/java/com/jarvys/agent/`; tests use the corresponding test source tree.

| Contract | Implementation entry points | Primary tests |
|---|---|---|
| A: original USER/root, steering, exact process authority, completion | `CentralTreeAdmission`, `workspaces/WorkspaceTreeAdmissionGateway`, `WorkspaceTreeCentralStore`, `WorkspaceTreeP1Preclaim` | `CentralTreeAdmissionTest`, `CentralTreeServiceAcceptanceTest`, `WorkspaceTreeP1PreclaimTest` |
| B/C/D: atomic requests/depth/executions/progress/wake overflow | `workspaces/WorkspaceTreeStore`, `WorkspaceTreeRecords`, `WorkspaceTreePolicy`, `WorkspaceTreeWaitStore` | `WorkspaceTreeStoreTest`, `WorkspaceTreeEvidenceStoreTest`, actual `WorkspaceTreeNativeRuntimeTest` |
| E/F: joins, needs_input, exact wait checkpoint/claim, reopen/no replay | `WorkspaceTreeWorkerCoordinator`, `WorkspaceTreeWakeCoordinator`, `WorkspaceTreeDelivery`, `CrewCheckpointCoordinator`, `WorkerTreeWaitRecord`, `workspaces/WorkspaceTreeEvidenceStore` | `WorkspaceTreeNativeRuntimeTest`, `CrewTreeWaitCheckpointTest`, `CrewTreeInputCodecTest`, P1 continuation/live-permit/source suites |
| G: requester STOP vs exact subtree cancel; pre-owner denial and successor fencing | `workspaces/WorkspaceTreeStops`, `WorkspaceTreeControlStore`, `TreeUserTargetControl`, native Crew cancellation/control seams | `CentralWorkspaceCancelToolTest`, `CentralUserTargetControlToolTest`, `WorkspaceControlStopOwnershipTest`, `NativeContinuationStopTest`, native worker/store cases |
| H: no Chief privilege, saved generalists, consult/native coordination, normal chat/menu/memory | `WorkspaceTreeWorkerTools`, `crew/CrewTreeSupport`, typed `CrewMessage`/`CrewInputBinding`, native session admission | `CrewTreeMessageRoutingTest`, `WorkspaceTreeNativeRuntimeTest`, `MainAgentInteractionTest`, `WorkspaceMemoryContinuityTest`, `PreservedCentralDrawerComposeTest`, `MainActivityWorkspaceRoutingTest` |
| I: exact current-generation DDL/readers/transaction and checkpoint conservation | `workspaces/WorkspaceRegistry`, `WorkspaceTreeSchema`, native checkpoint/ref codecs | `WorkspaceTreeSchemaCompatibilityTest`, `WorkspaceRegistryTest`, all ten preregistered pre-write classes |

## Explicit integration deltas for independent assessment

- Natural later USER reply/cancel uses exact committed invocation plus retained owned target/question/revision. It grants no delegation, root replacement or budget reset. No mandatory slash syntax or new menu. A denial-only cancel may reach captured owned descendants of a closed root without reopening it; reply/Resume remain denied there.
- Typed CHILD_WAIT successor lineage conserves exact checkpoint/Registry claim. Strict TREE_TASK/report/answer routing preserves actual PEER/SUBORDINATE identities, not Chief/USER masquerade. Consult permits existing read-access coordination ask/report, despite durable coordination's WRITE effect classification; external mutation/delegation restrictions remain intact.
- Native USER successor queues its exact supervisor answer before certified checkpoint/executor. Already-bound uncertain control has only a denial-only PAUSE verifier; generic restore/execution still reject uncertainty.
- Registry nested operations borrow only the same-thread exact-path transaction connection, maintaining strict schema checks/rollback conservation. Cleanup failure never hides the original SQLite transaction exception, nor proves rollback without reopen evidence.
- Durable parked WAITING source freezes before releasing its native cycle lock. Confirmation still gates wake; failure/STOP and replaced cycles do not inherit permission. Deterministic sibling-publication, confirmation-failure and STOP interleavings cover this gap.
- Physical dequeue refuses an exact already-handled non-regeneration stored USER before UI/token allocation. The unchanged real-service red case observed3 provider calls rather than2; final tests prove2 plus fresh/unhandled USER, persisted receipt, AGENT, explicit regeneration and STOP boundaries.
- Historical outbound TASK restore now positively validates original immutable admission/invocation/execution/bind evidence and actual parent-child relationship, full payload/provenance, initial revision/cycle and strict JSON shape/scalars. Pending recipient checks are unchanged. Real child successor/reopen plus cross-parent/sibling/cycle/source/malformed-scalar mutations pass; no capability or replay is returned.

Detailed red→green history: [integration ledger](BOT_PORTABILITY_P2_INTEGRATION_LEDGER.md). Offline injected clients exercise actual service/native worker paths; this is not proof of autonomous live-model task selection or Android process/power-loss behavior. JVM sockets/DNS/listen/accept/process launch are denied by the host runner; it is not a native OS network sandbox. Only known generated optional classpath directories are waived, not missing dependency archives. No timeout or assertion weakening was used to turn this cohort green.
