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
