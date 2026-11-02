# Central availability and autonomous return — design candidate, not activated

## Scope

Phase A repairs three proven lifecycle/STOP paths without changing delegation policy. This separate phase repairs availability: accepted delegation runs independently and reports autonomously; normal Send creates a new request while existing work remains alive; explicit steering changes the current request. Preserve one Central conversation, serial effect ownership, generalist scope, Engram namespace, menu and data. No quota changes or automatic uncertain-effect replay.

Primary provenance: [OpenMausBot runtime](https://github.com/milind-soni/OpenMausBot/blob/9230dc9b71a22375a9d3b296172b5664c2ae3201/server/runtime/runtime.ts) provides asynchronous durable assignment/dispatch, requester waiting and event-driven wake. Its [person-first implementation](https://github.com/milind-soni/OpenMausBot/blob/9230dc9b71a22375a9d3b296172b5664c2ae3201/server/host/person-first.ts) fences/requeues human priority during tools, but does not solve silent inference and its60s threshold is not imported. [OpenDuet task queue](https://github.com/CopilotKit/OpenDuet/blob/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db/openbot/server/src/work/queue.ts) has independent worker ownership/leases; same-thread contention and stop cleanup limitations are not copied. These are adaptation references, not a claim that upstream solves every native crash boundary. No upstream code executed or enterprise code imported.

## 1. Return semantics

New Central delegate requests with omitted continue_result default to AWAIT; explicit false remains detached notification-only. Preserve omission separately until the existing assignment lookup under the native authority transaction. Existing PREPARED request retries use their recorded reply policy, not the new default, and still require exact root/source/payload. Existing started/uncertain requests never replay. Do not reinterpret historical rows. The initial delegation receipt is truthful admission, not completed work.

A native explicit wait action parks after exact completed tool results and current awaited dependencies; no polling. It may not hide unfinished calls, approvals, compaction or uncertain effects. Do not unconditionally terminate on each delegate: complete every admitted call in the batch, preserving sibling and dependent setup. Existing result delivery remains the authority for exact child results.

## 2. Positive normal-submission representation

Choose one new additive Roomv3 table central_turn_submissions, not mutable message_parts and not repurposed STEER interrupt_requests. Parts participate in the immutable USER root digest; an ordinary USER row alone is not queue authority. No backfill.

Minimal fields: conversation_id/submission_id composite PK; unique FK conversation_id/user_message_id to messages; immutable original USER sequence, exact source SHA256, storage generation, original admission process; current admission process and revision; state PREPARED/STARTED/COMPLETED/FAILED/CANCELLED; claim process/run/token generation; terminal evidence; optional exact recovery-control receipt. No duplicate content/attachments/memory or serialized executable permission. Pending index orders by canonical USER sequence.

Native Send atomically appends genuine USER and PREPARED submission under the existing Room writer/lifecycle lease. The live admission capability is process-local and exact-ID; row metadata cannot mint it. Duplicate UI/service delivery retains the same submission ID. A typed append cannot leave either half on failure.

Claim requires current lifecycle/generation, exact immutable source, oldest eligible current-process PREPARED and live native capability. Under Central admission/STOP lock allocate actual run/token, then CAS exactrevision PREPARED→STARTED with claim identity. Recheck token and source before model dispatch. STARTED never resets. Existing old rows are history only, not recovered as queue.

After process death app-open is read-only/nondispatching. PREPARED may be explicitly retried via a fresh native user action selecting its exact row/revision (not a model read or background signal): preserve original source/root identity, record recovery receipt and new process/revision, mint a live capability only for that action. STARTED without confirmed terminal evidence remains uncertain and cannot retry. Native UI recovery is an existing-row action, not a new chat or mandatory slash language. A receipt alone never authorizes later execution.

STOP captures exact active token and then-current submission IDs under the same admission lock, with immediate token latch before blocking storage; atomically disposes only that cohort. Later Send survives. Preserve Phase A exact-cohort failure semantics; no late broad scan.

## 3. Reader conservation

Generic history, steering, latest-user fallbacks and regeneration must exclude PREPARED and uncertain STARTED NEW_TURN inputs; current claimed execution sees only its own exact USER via claim-specific validation. UI still shows actual queued USER. Completed rows enter ordinary history only after exact terminal linkage. InteractionProtection includes submission state/revision/source, and compaction protects unresolved sources. Internal AGENT/P1 inputs remain separately typed and cannot mint new USER roots. Explicit STEER stays on original root/budget; do not infer mode from text.

## 4. Provider-only supersession

Use a provider-attempt lease independent of CancellationToken authority: exact session/run/generation/attempt/checkpoint identity, with atomic stages PREPARING, INFERENCE, COMMITTING, SUPERSEDED, FINISHED. Never substitute another token into canonical prepared-context/memory checks.

Commit new submission before requesting yield. Supersede only INFERENCE; cancel its transport, not run/children. Stream deltas, reply parsing, retries and proposed tool calls must all check attempt identity. A reply's COMMITTING transition races atomically with supersession; if commitment won, complete admitted calls before yielding. Compaction, approvals and executing/uncertain tools do not count as inference. No timer is invented.

If transport cannot unblock synchronously, isolate only provider inference behind a fenced future; stale provider code may not write application effects, consume inputs, dispatch tools or overwrite UI. At most one Central effect loop exists. Remote provider computation/billing termination cannot be promised.

## 5. Typed yield and durable resumption

On the same old execution lane, close its input reader under admission lock, excluding the new submission. Persist old-root transcript with no unresolved calls and mint a typed yield receipt from confirmed checkpoint + actual old source/run + selected new submission. Extend MainChatTranscriptStore receipt validation; do NOT relax completed-assistant validation or invent an assistant completion.

Record CENTRAL_YIELD in the existing Registry event ledger and CAS exact old node/cycle/revision to WAITING with a distinguished suspension intent. This must participate in root pending/closure predicates, so no child-settlement or withdrawal closes a suspended root. No Room/Registry transaction crosses callbacks/network operations. Only after confirmed parking may old service owner release without STOP/FAILED/completed-memory effects and new submission claim start.

Crash before parking: no new dispatch. After parking/before claim: old root parked, new submission retained. After claim: no blind replay. If persistence fails, fail closed with truthful uncertainty; do not claim it parked.

Suspension needs its own bounded continuation because a yield may occur after the last child result was already consumed. Reusing P1 claim would replay authority. CENTRAL_YIELD carries exact confirmed source/checkpoint; one CAS claim in existing node/event store advances its original root to a new cycle and charges the existing execution counter without resetting limits. Current-process live continuation permit is minted at confirmed park; after restart fresh explicit recovery is required. This source is not Chief permission or a new root.

Choose strict suspension-first arbitration with **no coalescing**. Add Registry table bot_tree_central_resume, shared by SUSPENSION and CHILD_RESULT causes: resume_id PK; root/node; cause_kind/cause_id unique within root; immutable typed source certificate/hash; revision/state PREPARED, RESERVED, BOUND, INCORPORATED, CANCELLED or UNCERTAIN; reservation process/run/token/successorcycle; expectedparentrevision/cycle/rootstopepoch; binding/incorporationreceipts. Row plus parent control_intent is the sole mutable claim authority; events are immutable audit evidence.

Parking inserts a unique PREPARED suspension and CENTRAL_YIELD event and CASes parent RUNNING→WAITING in one Registry transaction, only if no unresolved control_intent. Existing P1 incorporation must finish its exact acknowledgment first. Idempotent exact repeated receipt is not a second continuation.

All tree-enrolled Central resumptions reserve through one Registry transaction requiring exact WAITING parent fence, open root, unchanged stop epoch and no control_intent. If any unresolved suspension exists, only its exact live capability may reserve; a P1 caller returns not-claimed with permit/checkpoint/input untouched. Otherwise exact child result may reserve. Reservation records actual successor owner and sets parent control_intent before leaving transaction. A committed reservation cannot be preempted. RESERVED/BOUND/UNCERTAIN ownership blocks competition.

The P1 winner reserves Registry **before** consuming its existing live permit and PREPARED→STARTED checkpoint CAS. It then prepares the exact Room generation and confirms receipts outside Registry. A second Registry transaction checks exact reservation/fences, binds successor and charges the existing execution counter once. The suspension winner similarly prepares a successor from confirmed safe checkpoint (not completed-effect replay) and binds before dispatch. Any ambiguous bridge failure retains UNCERTAIN ownership, never resets the row or reconstructs permission after restart.

After confirmed successor checkpoint incorporation, mark the cause INCORPORATED and clear only its matching intent in one Registry transaction; P1 additionally preserves existing CENTRAL_INCORPORATED child proof. An unconsumed child result remains pending during suspension resume and gets its distinct later P1 turn. Every successful closure predicate—WorkspaceTreeCentralStore.pending, finish, reconcileWithdrawals—must count PREPARED/RESERVED/BOUND/UNCERTAIN resume rows as active. Central closeRoot explicitly disposes unclaimed rows and fences claimed owners for STOP/failure without enabling replay. No ACK, release or success is inferred merely from losing arbitration.

Affected entry points: CentralTreeAdmission typedyield/attach; MainChatTranscriptStore confirmedyield/successor receipts; WorkspaceTreeCentralStore attach/incorporated/pending/finish/reconcileWithdrawals/fail; WorkspaceTreeStore wrappers/closeRoot/schema; WorkspaceTreeP1Preclaim and WorkspaceContinuationDelivery.claim reservation-before-P1-consumption. Existing result scheduling only nudges eligibility; it cannot mint either capability.

## 6. Compatibility and activation gates

Roomv3 submission table and the Registry resume-table version extension are current-generation additive extensions, not legacy import/destructive reset. Pin exact supported v1/v2 schemas/identities and generated v3; under existing lifecycle-owned native BEGIN IMMEDIATE create empty table/index, validate prospective full schema before identity/version publication, conserve every old row/FTS/index/trigger. Unknown/malformed objects fail before writes. Preserve read-only recognition, registered reader exclusion, Doctor/export inventories and no downgrade claim.

Each database upgrade is independently atomic; no false cross-database transaction is claimed. A partial upgrade preserves the successfully upgraded empty schema and retries only the remaining supported upgrade after exact validation. NEW_TURN/yield dispatch requires BOTH schema capabilities ready, so no new protocol writes occur in a mixed-capability state. Separate migration and submission admission flags remain OFF until matching Full/Play D3 tests pass: genuine populated v98/v99 reopen, old-data conservation, rollback/interruption/SQLITE_FULL, concurrent/cached readers, no partial USER/submission writes, exact CAS contention, source mutation/compaction, STOP cohort, process-loss nonreplay and ordinary legacy history unchanged. Then enable local acceptance, never infer PASS from a checkpoint.

Required final vertical: delegate omitted→automatic result return; explicitfalse retains detached mode; oldretry policy conserved; multipledelegates/mixedbatches safe; normalSend during held provider gets a freshroot while children continue; steering preserves oldroot; stale response/stream/tool proposals denied; yieldafterlastchild resumes exactly once; each crash boundary conserved; oldSTOP cannot cancelnewUSER; at most oneeffectowner. Run final exactsource Full/Play affected and complete acceptance, then fresh independent review. No physical-device/live-provider guarantee from host tests.

## Independent design gate

This is a concrete design candidate, not an implementation or PASS. Review must validate queue-source protection, both migration boundaries, provider-only transport fencing, suspension-first arbitration and all closure readers before enabling new protocol writes. Canonical prepared-context/memory and invocation validation stay on the native owner thread; only sealed provider transport may be isolated in another future. Moving the entire prepared-runtime function to another thread would break thread-bound authority and is forbidden.

Required additional negatives: losing P1 leaves checkpoint hash/input/permit unchanged; crash after reservation cannot claim again; last-child-already-incorporated yield resumes via suspension, not P1reset; child withdrawal cannot close suspended root; STOP before/after reservation cannot bind a later owner; two DBs partially upgraded cannot admit new protocol. Explicit native retry after process loss applies only to exact PREPARED submissions with fresh user action, never STARTED or a background read. UI must distinguish queued/background/uncertain states from active execution without claiming completion.
