# UX44: configurable action policies

## Development checkpoint, not a release

Factory v84 remains the delivered APK. The authorized UX44 batch is in progress. Its initial Full Debug production/test compilation and 42 targeted tests passed (25 foundation, four saved-policy, ten dispatch, three SAF coordinator cases), with no failures/skips or blocked offline-network attempts. These checks do not certify the remaining integrations or physical Android behavior.

## Implemented in this checkpoint

- One absent-value rule: ALLOW. Persisted valid ASK/DENY/ALLOW remain unchanged; malformed values remain ASK and are marked invalid.
- Versioned provenance distinguishes default, user, ambiguous legacy, inherited server and invalid records. Restoration is reconciled per record. Unknown provenance is never inferred from a value alone.
- Durable writes and exact, single-use action admission share an authority lock across store instances. Raw-state and observation tokens prevent stale grants after intervening changes/restoration. Persistence failure restores the old state; an unverified rollback blocks further admission.
- Native connector reads and writes use the same configurable policy. Tool exposure, mission/profile capabilities, actual Android permissions, account grants and availability stay independent. Notification availability no longer turns ALLOW into ASK.
- Approval cards decide; Registry owns native intent dispatch. Receipts distinguish a launch request from confirmed presentation, sending, saving or call completion.
- Gmail/Drive writes and nested provider requests recheck policy at each dispatch/retry. Exact account, target, revision, selection and uncertain-write journals remain.
- SAF result ownership survives cancellation: a launched cancelled picker drains its old result before another request can own a result.

## Remaining integration and validation

MCP discovery/transport, principal Linux/Coding admission, bots, memory and identity operations are being integrated as separate source patches. Reviewed follow-ups include read identity pinned before ASK, Google policy-save error presentation, SAF cancelled-result filtering, actual ASK-only automation protection, and expanded catalog metadata. Their tests have not run in this checkpoint. Existing tests for old mandatory-ASK defaults also need explicit migration to the new contract rather than deletion.

The batch will receive one combined final validation and artifact review after implementation. No provider actions, account access expansion, real messages, remote commands or key exports are used in development tests. Android/OEM denial, revocation and physical behavior remain device acceptance conditions. UX34 and other hardware-dependent gates are not closed by host tests.

## Admission limit

A durable DENY before admission prevents the effect. Revocation after an effect has been admitted cannot promise to undo a dispatched Android/provider action. Fresh batch mutations, retries and resumed dispatches need fresh admission. Unknown outcomes are reconciled rather than replayed blindly.
