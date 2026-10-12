# UX44: configurable action policies

## Validated and delivered v85

Version 85 / `1.2.78-AUTONOMY`, final source `4802bd2fb8991d51dbdbc4abd20d9a0b04edd7ad`. The retained-D7 ARM64 APK was sent with acceptance on 12 October 2026 at 03:28:36 UTC: 20,434,579 bytes, SHA-256 `2fe460765edc4cec9392ea45fa3d121134ecf7484ab476758f58ece45c25108f`. Persisted bytes, signature, retained payload and all three host APKs were independently verified. This does not establish download, installation or physical-device acceptance.

## Implemented scope

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
- Native memory viewing/capture, mutations, restoration, export/sharing and reflection use the same policies. View permission covers the selected native screen/capture, while agent and reflection mutations bind the relevant write/delete decisions. Exact content/scope, cancellation and export ownership remain checked.
- Policy editors disclose provenance and protect only the actual choice interaction. Genuine ASK cards retain human-interaction protection; ALLOW execution does not acquire a blanket approval guard.


## Validation and remaining limits

Host coverage is an exact reconciled union: 4,187 Full and 3,744 Play cases pass with no unresolved failures, errors or skips. Initial failures are preserved; complete affected classes were replaced only after source and compiled-input comparison. This is not a second complete fresh aggregate. SDK59 ran fresh. Unchanged Factory core/runtime evidence is retained against 333 identical source/configuration files, 108 identical resolved inputs, the same environment and the exact embedded template: core 2,706 passes plus one explicit SDK24 location assumption per variant; APK runtime 93 passes per variant.

Fresh host lint and affected incremental analysis preserve the same 46 Full and 37 Play legacy errors, with no new errors. Visible warning/hint totals are 317/4 Full and 313/3 Play; this is not a lint-clean claim. Main/unit analysis and report reuse are byte-bound. The guarded initial complete Full and Play runs each recorded eight blocked network attempts; corrected class runs recorded zero. No destination attribution or blanket zero-attempt claim is made.

Android/OEM grants, denial/revocation, lifecycle behavior, installation and real provider acceptance remain unverified on a physical device. The batch does not connect accounts, grant Android/OAuth access or remove UX34 and other independent hardware gates. The next local-diagnostic feature is separately queued and is not present in v85.

## Admission limit

A durable DENY before admission prevents the effect. Revocation after an effect has been admitted cannot promise to undo a dispatched Android/provider action. Fresh batch mutations, retries and resumed dispatches need fresh admission. Unknown outcomes are reconciled rather than replayed blindly.
