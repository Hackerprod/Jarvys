# v99 repair Phase B — host candidate, independent review pending

## Identity and status

Frozen implementation/test source: `6bcdc1c495951eeaf7c0efa1802d4fb1a23260d7`, tree `7e13d3b24c4b452c4e7d285758a504373483a29a`. Phase A baseline: `011ed51e96b5f6a0ecdaed7a6b6ca701d220f6f0`. Documentation-only checkpoints do not change that source identity.

Full and Play each passed **137 classes / 1,291 tests**, zero failures, errors or skips. Each also passed the same 21-class/296-test focused compatibility/native cohort. Test names match across flavors, and all source/artifact before/after bindings remained identical. Source binding SHA256: `053c002a1657c3a0b3f81aa2147b20d451a0dff65cc1df8243423cef0c934885` (2,030 files). [Sanitized detailed evidence](V99_CENTRAL_FINAL_HOST_RESULTS.json). These are author-executed offline host results, **not independent implementation PASS, packaging approval or device evidence**. The separate Phase A Full Proot5 supplement was not rerun or counted here.

## Implemented contract and reviewer entry points

- **Fast native delegation:** `CentralWorkspaceTool`/`WorkspaceCoordinator` distinguish omitted automatic AWAIT, explicit detached false and retained retry policy. No new quotas or Chief authority.
- **Fresh USER vs STEER:** `AttachmentDraftViewModel`, `CentralSubmissionAdmission`, `CentralTurnSubmissionRepository`, `ConversationRepository` and guarded readers atomically retain USER+PREPARED, claim exact live actions and exclude pending/uncertain NEW_TURN from steering/history. STEER retains its actual owner chain and original root.
- **Single serial Central owner:** `AgentForegroundService`, `ProviderAttemptLease`, `SealedInferenceTransport` and `NativeCentralYieldBoundary` supersede only eligible sealed inference transport; owner-thread context/authority/effect validation remains native. No concurrent Central effect owners.
- **Durable suspension and autonomy:** `CentralYieldCheckpointCodec`, `CrewCheckpointStore`, `CentralYieldSourceEvidence`, `CentralSuspensionSourceScope` and `WorkspaceCentralResumeStore` conserve exact checkpoint/source lineage, reserve before claim/consume, give suspension priority, and resume from physical lane release without needing another child callback. Uncertain STARTED reservations cannot replay.
- **STOP/recovery:** native service captures exact predecessor/successor cycles; denial precedes blocking cleanup. Registry closure preserves started-child report policy, cancels never-started children and cannot reach another root. Recovery requires a fresh explicit native action for exact PREPARED evidence; app-open never grants capability.
- **Compatible current-generation storage:** `RoomTreeProvenanceSchema`, `JarvysChatDatabaseV3`, `WorkspaceCentralResumeSchema` and composed readiness checks support exact additive Room/Registry upgrades with conservation, rollback/interruption, unknown-object rejection and mixed-schema admission denial. No destructive reset, legacy import or binary downgrade promise.
- **Ordinary regeneration:** `NativeRegenerationReceipt`, bridge postcommit issue and service dequeue/consume preserve exact native completed source/marker/lifecycle. One-use process capability prevents stale/repeated intents; no new submission, root, budget, permission or replay. Existing ordinary history remains visible after presentation hiding. UI inspection uses the existing guarded worker, not a relaxed main-thread database rule.

## Mandatory independent-review question and limits

**Ordinary regeneration remains without tree admission or a yield boundary.** A fresh USER can queue, but processing waits for that regeneration provider turn or STOP. Review must assess this explicit limitation against the user goal that Central remain available. A PASS must not conceal it or invent tree authority to bypass it. Any necessary follow-on should use real transport/owner fencing and typed ordinary interruption, with its own regression evidence.

Provider supersession does not prove a remote server stopped computing or billing. Active effects/compaction wait for safe boundaries; no instantaneous-response or general latency guarantee. No phone/provider-live testing, installation proof or confirmed explanation of the user's original screenshots. Existing v99 packaging remains unchanged; no new APK is approved here. One Central chat, existing menu, general-purpose agents, data and Engram are preserved.

All earlier failed compilations/cohorts, the strengthened prebind STOP RED, and fixture corrections remain recorded in [progress](V99_CENTRAL_AVAILABILITY_PROGRESS.md) and the evidence manifest. The older P2 isolated readiness-timing failure remains unresolved as historical evidence; this candidate does not assert that it has been explained. Independent review and any resulting corrections must complete before packaging/delivery.
