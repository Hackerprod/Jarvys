# v99 repair Phase B — R2 host candidate, independent review pending

## Exact identity and evidence

Frozen implementation/test source: `b40a7f4980bf2d98eb5df680ebe15a580337a086`, tree `8f67f386d1121982c0f75a6f544e57004e1ad82c`. Phase A baseline: `011ed51e96b5f6a0ecdaed7a6b6ca701d220f6f0`. Later documentation-only commits do not change the tested source identity.

Author-executed Full and Play each passed **148 classes / 1,358 tests**, zero failures/errors/skips, with identical test names and unchanged source/artifact bindings. Both native cohorts passed 10 classes / 132 tests; the Full STOP regression passed 6 tests. These overlap the union and are not additional unique coverage. Source binding: `602c9d7d2ea92979271fbf9d49b8dd64fb6b35aad971b5ce405bc30a1f7e6e9a` (2,045 files under app/ and tools/testing/). [Exact sanitized evidence](V99_CENTRAL_R2_HOST_RESULTS.json), SHA256 `e523a5d08f14ea2576c81bbc305b836733f99bd9cb6056c54d365c8a24b5a8de`.

**Independent R2 review is pending. No Phase B approval or new APK is claimed.** The original independent R1 FAIL and every diagnostic failure remain in [chronological progress](V99_CENTRAL_AVAILABILITY_PROGRESS.md); the [R1 candidate](V99_CENTRAL_IMPLEMENTATION_CANDIDATE.md) remains historical, not silently relabeled.

## Review entry points and repaired contracts

- `AgentForegroundService`, `ProviderAttemptLease`, `SealedInferenceTransport`, `OrdinaryRunOwner`: real native regeneration, ordinary AGENT and late STEER owners can release the single Central lane during eligible sealed inference. Exact source, generation, token and operation ownership remain mandatory. No fictitious task tree or new delegation authority.
- `NativeRegenerationReceipt` and native conversation writer: manual regeneration retains existing eligible completed, stopped and legacy response semantics. Missing/changed positive generation fails closed; legacy bootstrap is limited to the real legacy writer. Hidden historical evidence remains retained and protected, not promoted to successful completion.
- Typed confirmed ordinary interruption: one-use exact receipt and transactional source/owner validation preserve USER/AGENT ACK state, pending inputs and memory finalization semantics. A failed typed write cannot fall through to a false terminal acknowledgment.
- `CentralYieldCheckpointCodec` and native suspension source: optional hash-bound operation lineage conserves original USER operation text and same-root authority. INTERNAL and absent legacy lineage remain restricted; no empty replay prompt is mistaken for the original operation.
- `WorkspaceContinuationDelivery.requireSuspendedResultRead`: the bound INTERNAL successor retains only the original exact `workspaces/read_result` capability, validating selected canonical result, ACK, assignment revision, root, source, disclosure and current owner before/after. It cannot delegate, inherit Chief authority or reuse a retired token.
- STOP and admission cleanup: confirmed STOP cohort persistence is not repeated during interrupted unwind. Denied forged regeneration and already-handled stored USER intents release only their acquired transient claims, without rewriting terminal evidence or poisoning a later genuine manual action.
- Current-generation additive storage compatibility and conservation remain covered by the paired pre-write gates and final union. No destructive reset, legacy import, schema bypass or binary downgrade promise.

Native review cases include `OrdinaryRegenerationAvailabilityTest`, `OrdinaryAgentAvailabilityAcceptanceTest`, `LateSteerAvailabilityAcceptanceTest`, `OriginalUserSuspensionAuthorityTest`, `InternalContinuationSuspensionAuthorityTest`, `CentralStopFreshUserTest` and `LegacyRegenerationGenerationTest`, alongside the complete compatibility/native union in the manifest.

## Scope and limits

The repaired availability contract applies to sealed inference and safe boundaries. Committed tool effects and structural compaction are not interrupted or replayed to manufacture responsiveness. Supersession does not prove remote provider computation/billing stopped. No universal latency guarantee, exhaustive concurrency proof, physical-device/live-provider validation or confirmed explanation of the original phone screenshots is claimed. The earlier isolated P2 readiness-timing failure remains unclassified.

One Central chat, general-purpose agents, existing menu, user data and Engram remain preserved. The separate Phase A Full Proot5 supplement was not rerun or counted in Phase B. A matching host pair is not packaging approval: any later APK requires its own version/source binding, original DEBUG identity, manifest/native payload checks and independent packaging review before delivery.
