# v99 repair checkpoint — Phase A scoped host PASS; Phase B implementation pending

2026-11-02. Baseline `15ea334ce62b9559f76f72107031f9298538bde4`, the verified source backup for delivered v99. The delivered APK, prior host/packaging evidence and historical FAIL records remain unchanged. Device feedback reports stalled delegation and Central responsiveness; source findings are not proof of the phone's exact cause.

## Historical initial candidate — bounded lifecycle and STOP correctness

Production is unchanged at this checkpoint. Four new deterministic regression cases are prepared, **not compiled or executed yet**:

- Native deferred worker failure immediately before Registry binding. Original admission error must survive cleanup failure, with secondary errors retained; uncertainty disposition must not be skipped.
- Native deferred worker failure after binding but before executor submission. Captured lifecycle must terminate without a scheduled zombie, invented result, mission-wide termination, or uncertain reservation replay.
- Fresh USER after STOP while the old provider remains physically held must survive and execute once after serial owner release; pre-STOP steering stays cancelled.
- Repeated STOP captures its then-current input cohort without retroactively cancelling a later USER.

Regressions use actual native/service paths with barriers and offline injected providers. No live provider, device mutation, reset or raw personal-data export. The original environment's loss means fresh toolchain/export evidence is required; earlier test counts do not validate this repair.

## Phase B: Central availability and autonomous delegation

Source-backed design work is separate from Phase A fixes. Preserve one Central chat, single mutation owner, durable delegation/return evidence and existing general-purpose worker capabilities. Reuse upstream asynchronous delegation/requester wait and priority-human semantics, not arbitrary mission timers or new quotas. Do not import new UI, privileged Chief inheritance, parallel memory, destructive upgrades or automatic replay of uncertain effects. Concrete ownership/yield design and independent review precede activation.

Each phase requires exact-source deterministic tests and an independent clean-context evaluator; failed results remain retained. No new APK or repair PASS is claimed.

## First diagnostic result and native fix candidate

Fresh frozen Full export passed with source binding unchanged. The first guarded run executed 43 tests: native41 had exactly the two new intended failures (prebind original-error masking and postbind scheduled lifecycle leak), while its prior39 passed. The two new service tests failed before provider entry because their fixture did not initialize current connection readiness; those failures are retained and are **not STOP reproduction evidence**.

Native production repair is now a candidate: positive process-local initial-start ownership for fresh spawned workers, consumed under the cycle lock; exact never-submitted STOP completes its own lifecycle even when persistence cleanup fails. Submitted/restored/replacement cycles are not inferred as unsubmitted. Coordinator preserves the original exception and independently attempts STOP/uncertainty cleanup, attaching secondary failures instead of hiding them. No synthetic worker result, whole-mission completion, or uncertain replay. Two adjacent regressions cover persistence failure and submission winning the STOP race.

The service fixture now uses the same native connection readiness as current acceptance and captures the actual runtime exception. **Production service remains unchanged**, awaiting a meaningful red reproduction. Next frozen Full run is a mixed diagnostic: native green candidate plus corrected service red. No repair PASS is claimed.

## Conclusive STOP red and complete Phase A fix candidate

Second frozen Full diagnostic: native43/43 passed (39 existing plus four repair cases); corrected service2 failed at the intended assertions, proving a later USER was already resolved by the old STOP. Total45 tests, two expected service failures, zero errors/skips; source/artifact bindings unchanged. This establishes host reproduction, not the phone's exact cause.

The service fix now captures exact STOP-owner input cohorts and never rescans later inputs from old cleanup. Exact token cancellation is latched before the potentially blocking database snapshot. A native writer batch resolves only captured IDs atomically using the existing transaction, with no schema change. Later USER input stays durable and waits for physical old-owner release. Failed STOP persistence conservatively gates recovery; existing serial-lane requests remain queued until exact known-cohort metadata resolution succeeds. No raw USER-to-steering conversion or new queue.

New tests cover atomic rollback/reopen, idempotency, later-input conservation, direct stored input parked behind persistence uncertainty, and immediate token cancellation while the database is blocked. Known limitation: failed STOP capture before durable write has no persisted cut marker; in-process quarantine is not a claim of cancellation conservation after process death during storage failure. No provider termination or device guarantee.

The combined fix is awaiting fresh focused Full/Play and complete matching acceptance, then independent review. **Not repair PASS or a new APK.**

## Independent R1 FAIL retained; bounded cleanup repair

The first combined affected Full run executed155 tests with one existing semantic failure: explicit queued STOP used start-failure wording instead of the native stopped message. New service/storage7 and native43 all passed, but this was not acceptance. Independent clean-context review also found a process-local claim leak: skipping duplicate terminal persistence skipped the queued follow-up's claim release. Its separate guarded native regression reproduced one intended failure, with source/artifacts unchanged. R1 is **FAIL**, retained.

The revised candidate separates unconditional claim-only STOP cleanup from terminal persistence, restores the existing localized STOP wording, and removes resolved cohort bookkeeping only after its exact owner is inactive. A permanent queued-follow-up STOP→explicit regeneration regression verifies claim release, one terminal bubble and fresh execution. No old assertion was weakened. Matching affected Full/Play and final121-class acceptance remain pending; independent R2 must assess corrected bytes.

## Phase A final independent R2 PASS

Exact production/test source `011ed51e96b5f6a0ecdaed7a6b6ca701d220f6f0`; source binding SHA256 `62dc8b06be368494196bbc6ba314b55699ec512215876b62cc2f6f778dc9c535` over 1,996 files. Fresh Full/Play affected cohorts each156 passed; final exact matching121-class cohorts each1,146 passed, zero failures/errors/skips, unchanged source/artifacts. Independent evaluator reconciled every XML/class set and reran its original compiled regression unchanged RED→GREEN. Independent R2 report SHA256 `79823061c7bbd00995f491fe22745dc98b54d9a8be9d94cd0b65bc19ce41b064`: **PASS for bounded Phase A source/host repair**. Original R1 FAIL remains retained.

Separate Full-only Proot planner/process-tree supplement5 passed after reviewer sealing; not part of the1,146 count or independent verdict, and no native binary execution. [Public exact host manifest](V99_REPAIR_HOST_RESULTS.json). This selected acceptance is not a claim that every repository test was run.

Provider physical unwind remains necessary in Phase A. Failed STOP capture before a durable write still has no persisted cut marker; in-process quarantine is not cross-process cancellation conservation during storage failure. Historical unclassified15-second fixture failure remains retained. No phone-cause, latency/reliability, physical install, live-provider or new APK claim.

## Phase B independent design gate and next work

Design R2 at `21635258ad02e8652b3881c06a79655b799996ce`, artifact SHA256 `560a4e8fa1022980ad10855c2005f038b2c4002ba6070a0dc81f0c96f188198c`, independently **PASS for design only**; review SHA256 `b7298937ad110f972d8cb1d8cc2856ec2dbef552abcb64eaf16ef7a0eaa2ca35`. Its R1 FAIL is retained. The exact [design artifact](V99_CENTRAL_AVAILABILITY_DESIGN.md) retains its candidate-era wording; this checkpoint records the subsequent verdict without changing reviewed bytes.

Implement atomic native NEW_TURN submissions, provider-only supersession, typed safe yield and exact event-driven Central resumption on original roots. Room and Registry additive compatibility gates stay OFF before matching pre-write D3; implementation and final matching acceptance require a new independent verdict. No invented limits, new chat/memory, Chief inheritance, destructive reset or uncertain replay.
