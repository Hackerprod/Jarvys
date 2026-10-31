# Narrow later-USER target control — unvalidated implementation draft

These are newly reconstructed draft sources, not recovered bytes. They are outside app source. Compilation and tests have not run for this draft slice.

## Authority and native flow

Existing Central USER service capability and current execute4 witness prove the **current** user execution. They do not transfer the current turn's delegation root to the older task. `TreeUserTargetControl` is private-constructor, live context/thread/action/exact-argument/target scoped; it has no delegate/reserve/reset API. Current USER must remain canonical latest; AGENT, fallback, unbound steering, absent witness, expired token/context and replaced arguments fail closed. Complete committed argument JSON is checked through the existing package-private `LocalRunStore.readEpisodeToolSource`, including STARTED/no result. UTF16 canonical encoding conserves payload identity.

Before Registry: capture the current canonical USER and retained original target-root USER digest and exact lifecycle. Under the existing source lifecycle lease and Registry transaction: recheck immutable root owner/generation/origin, exact current node/cycle/revisions/native assignment, root closure/denial, action and payload. Reply additionally verifies native question reference/hash/evidence, immutable NEEDS_INPUT/QUESTION_ARM/result/REPORTED chain and absence of ANSWER_STARTED. The original target root is never rebound/reopened. USER_CONTROL_AUTH and the operation are one atomic Registry transaction; no Room/provider/manager IO inside it. Normal cancellation denial is pure under this lock; physical native STOP remains afterward.

The tiny agent-package `CentralTargetControlSource` reader is needed because readEpisodeToolSource is intentionally package-private. It returns source data, not a grant. Freshness relies on the existing live native serial USER/thread/token ownership, not on a persisted authorization receipt. The lifecycle lock prevents storage replacement; it is not represented as a universal Room-content lock.

## Reply and cancel

`WorkspaceTreeAnswerStore.prepare` is unchanged: worker answers still need active actual-parent authority. New `prepareUser` accepts only the opaque live target grant; it can retain a user answer even when the worker parent stopped, provided the target and original root remain eligible. It cannot execute it. Existing explicit native Resume/CORRECT claim performs once-only ANSWER_STARTED and ordinary execution reservation. Already-retained identical answer is idempotent; changed text/cycle/hash is denied.

Cancellation targets the exact owned older subtree, preserving the original stable root+key immutable capture. No later cycles/siblings can be added on retry. Results remain retained. Root STOP and UNCERTAIN deny new grants. Cancellation of a running target is allowed denial, never execution replay/refund. Queued cancellation's already-approved first settlement may reset its **normal progress window**; the grant itself neither resets counters nor creates roots. Creation/accounting of the independent current USER turn remains existing service behavior.

Ordinary live MESSAGE/question delivery is unchanged. No slash commands or new UI. Natural language ambiguity cannot be inferred from valid exact native arguments: tool guidance requires Central to ask a clarification when unsure, while native code rejects missing or mismatched exact target fields.

## Source and test scope

The draft adds `CentralTargetControlSource` in the agent package and `TreeUserTargetControl` in the workspaces package. Integration affects the existing mutation context, Central workspaces tool, formal-answer adapter, cancellation adapter and answer store. No new chat, menu, permission role, schema or memory store is introduced.

Regression inputs include `CentralUserTargetControlToolTest`, test-only target-control assertions, and a service scenario covering actual USER→delegate→ask_chief→later USER reply→native Resume. Existing subtree STOP aggregation and source-validation corrections must remain intact.

## Validation intent (unrun)

Tool tests cover real execute4 and committed source, older USER root cancellation, immutable same-key sibling denial, fresh/latest USER versus stale original source, action/payload/context grant nontransferability, substituted committed args, cross-owner canonical workspace mutation, closed original root, revoked capability, read/background/missing witness and stale invocation/revision. The real service fragment covers natural later USER formal reply, unchanged original root on retention, no worker execution before native Resume, then one ANSWER_STARTED with actual answer visible to successor.

These drafts are not evidence of passing. Full/Play compilation, strict fixture validation, negative question mutation/STARTED coverage, concurrency tests and independent review remain required before acceptance. Existing older cancel test asserting that **all** different roots are prohibited must be updated: latest USER can now target its own retained older root; a stale original USER still cannot control a newer root.

### Lead-inspection correction

Native reference field is `observedAssignmentRevision`. Source reader now checks returned conversation/batch/call tuple. Reply denies explicit controlIntent and exact native PENDING or any STARTED/UNCERTAIN control. QUESTION_ARM must have the exact ask_chief argument equal to canonical QUESTION evidence text and canonical successful finish result referring to that exact evidence; nonnull result alone is insufficient. Service fragment adds native PAUSE PENDING/STARTED windows, denied without an authorization or answer event. Cancel fragment preserves integrated `performCapturedCancellationStops` aggregation (all captured exact STOP attempts), not the older fail-fast loop.
