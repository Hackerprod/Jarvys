# v99 repair checkpoint — regression candidate, not PASS

2026-11-02. Baseline `15ea334ce62b9559f76f72107031f9298538bde4`, the verified source backup for delivered v99. The delivered APK, prior host/packaging evidence and historical FAIL records remain unchanged. Device feedback reports stalled delegation and Central responsiveness; source findings are not proof of the phone's exact cause.

## Phase A: bounded lifecycle and STOP correctness

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
