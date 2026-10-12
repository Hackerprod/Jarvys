# UX44: configurable action policies

## Development checkpoint, not a release

Factory v84 remains the delivered APK. The authorized UX44 batch is in progress. The initial Full Debug checkpoint passed 42 targeted tests. The next integration compiled production and tests, then ran 155 targeted cases: 153 passed and two failed, with no blocked offline-network attempts. One fixture incorrectly modeled document truncation; custom MCP rediscovery also changed its own configuration revision through unrelated GitHub bookkeeping. Both corrections are integrated and await rerun. Later maintenance/task integrations and the complete current checkpoint have not yet been compiled or globally validated. No new APK is delivered.

## Implemented in this checkpoint

- One absent-value rule: ALLOW. Persisted valid ASK/DENY/ALLOW remain unchanged; malformed values remain ASK and are marked invalid.
- Versioned provenance distinguishes default, user, ambiguous legacy, inherited server and invalid records. Restoration is reconciled per record. Unknown provenance is never inferred from a value alone.
- Durable writes and exact, single-use action admission share an authority lock across store instances. Raw-state and observation tokens prevent stale grants after intervening changes/restoration. Persistence failure restores the old state; an unverified rollback blocks further admission.
- Native connector reads and writes use the same configurable policy. Tool exposure, mission/profile capabilities, actual Android permissions, account grants and availability stay independent. Notification availability no longer turns ALLOW into ASK.
- Approval cards decide; Registry owns native intent dispatch. Receipts distinguish a launch request from confirmed presentation, sending, saving or call completion.
- Gmail/Drive writes and execution-time nested provider requests recheck policy at each dispatch/retry. Exact account, target, revision, selection and uncertain-write journals remain.
- SAF result ownership survives cancellation: a launched cancelled picker drains its old result before another request can own a result.
- MCP read/write dispatch, OAuth refresh and retries use exact current server/tool/auth/schema/exposure leases. Existing initial server choices and disabled tools survive discovery; absence inherits the saved server decision. Endpoint or restored-state changes invalidate older requests.
- Principal Linux commands, Coding commands and separate Linux setup/removal choices use the common policy store. Setup phases and process launches recheck authority; uninstall has exclusive ownership and honest partial-removal receipts. Actual keep/delete-workspace selection is retained when the request omits it.
- Bot creation/mascot and signing-identity operations use exact native operation decisions. Provider icon retries, key/backup effects and task mutation boundaries recheck authority. Actual key/passphrase forms and Android file selection remain.
- Agent task creation, modification, cancellation and reads have explicit controls. These govern agent management tools, not recurring scheduler execution or human STOP/recovery. ALLOW cancellation preserves history. Optional manual tests retain their exact queued task binding.
- Policy editors disclose provenance and protect only the actual choice interaction. Genuine ASK cards retain human-interaction protection; ALLOW execution does not acquire a blanket approval guard.


## Remaining integration and validation

Memory viewing/capture, mutation, reflection and sharing remain in independent source review outside this checkpoint. The memory view choice is scoped to the selected native screen/capture, not ordinary agent data reads; write/delete decisions must also bind agent/reflection mutation routes. Existing tests have been migrated to seed explicit ASK for approval scenarios while retaining real grants, exact identity and no-effect assertions. Current corrections and later integrations still require execution. No exhaustive completion claim is made.

The batch will receive one combined final validation and artifact review after implementation. No provider actions, account access expansion, real messages, remote commands or key exports are used in development tests. Android/OEM denial, revocation and physical behavior remain device acceptance conditions. UX34 and other hardware-dependent gates are not closed by host tests.

## Admission limit

A durable DENY before admission prevents the effect. Revocation after an effect has been admitted cannot promise to undo a dispatched Android/provider action. Fresh batch mutations, retries and resumed dispatches need fresh admission. Unknown outcomes are reconciled rather than replayed blindly.
