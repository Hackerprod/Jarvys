# UX43: durable local result references

Delivered software slice v88 / `1.2.81-WORKSPACE-RESULTS`, accepted on 2026-10-12 at 16:45:36 UTC. The delivered v87 behavior and its evidence remain documented separately in [the first vertical](UX43_CENTRAL_WORKSPACES.md).

The bounded questions/findings/blockers and causal reply slice was delivered in v92; see [UX43 coordination](UX43_COORDINATION.md).

## Exact evidence and identity

Each new Crew execution cycle receives a persisted random identity, independent of process-local cancellation counters. Terminal DONE, PARTIAL, FAILED or STOPPED evidence captures that cycle's own text at the deciding branch. The immutable envelope binds source session, mission, bot, cycle, status, timestamp and exact text digest. Snapshot schema 4 retains the cycle and evidence; older snapshots remain readable without inventing missing identity or a delivery receipt.

For canonical workspace executions, the exact terminal envelope is persisted in the existing source conversation ledger. A bound assignment can register a deterministic reference to that evidence. A worker finishing before assignment binding waits for binding-time reconciliation. The observed assignment revision is registration metadata, never proof that the worker consumed a correction. Older-cycle results remain historical and cannot complete or restart a newer assignment.

## Local central receipt and read policy

The existing WORKSPACE_READ policy governs disclosure. Default Allow always permits a local metadata notice; explicit Ask leaves delivery for an authorized foreground read, and Deny withholds it. No background approval dialog or model turn is launched. Source/reference persistence alone grants no new authority. Final admission, exact owner checks and STOP apply before a new notice is appended.

The central-only `workspaces` tool adds `results` and `read_result`. Listing returns bounded metadata pages. Exact reads bind workspace, result ID, digest and UTF-16 offset; text pages contain at most 8,000 code units and preserve surrogate pairs and lone-surrogate data. Worker text remains untrusted tool output. A notice records local persistence, not user/model consumption or independent verification of the reported outcome. The native central card contains metadata, not worker Markdown or commands.

Result-only ledger rows are excluded from normal conversation, compaction and reflection inputs, including malformed rows carrying result discriminators. This does not remove the original result from its scoped evidence store. Explicit file inspection/delivery remains the separate WORKSPACE_DELIVER contract introduced in v87.

## Recovery and boundaries

The existing JSONL ledgers enforce strict UTF-8/JSON, exact immutable identities, duplicate consistency and complete records. A corrupt or torn ledger fails visibly; it is not silently truncated or treated as an absent receipt. Reconciliation retries only idempotent local receipt registration. It does not rerun tools, bots, external effects or model requests.

App resume schedules a finite paginated catalog scan. Process-local reconciliation coalesces new evidence, preserves cancellation across continuation handoff, and allows a genuinely new event after STOP to acquire a new owner. STOP cancels currently owned receipt work; it is not a permanent ban on later authorized results. If the worker terminal callback still has its interrupt flag, its STOPPED evidence is stored but notice scheduling is deferred until a later non-interrupted event, including foreground catalog recovery. Missing/deleted source or central storage prevents disclosure at the supported final boundary. Storage failure remains visible; crash/power-loss behavior on physical devices is not established by host tests.

No server push, proactive inference, memory sharing, legacy chat migration or UX34 gated phase is included. Remaining UX43 memory/proactivity work and CrewAI keep their Pending order.

## Validation and delivery

Frozen APK source: `30d27e1748ce055f78496f05f92e0abbe3c5725c`. Independently reconciled affected coverage: 1,209 Full cases in 144 classes and 1,196 Play cases in 140 classes, with each failed class replaced completely and exact input/bytecode bindings. Other app tests remain historical. Core/runtime/template/SDK evidence is retained only by verified equality. This is not a fresh universal suite.

Both host lint tasks retain their failures: the same 46 Full and 37 Play errors and all warning/hint identities as v87. Two interrupted analysis attempts remain in the evidence; no OOM or deadlock was established. The final build/lint environment used a 3 GiB daemon heap without changing production configuration or detectors. All three builds and Release Vital completed. Preserved test workers recorded 15 blocked network attempts; unknown destinations prevent a global no-network-attempt claim.

Independent exact APK review covered manifests, resources, assets, native payloads, complete DEX deltas and ART profiles. The signed ARM64 payload was independently downloaded and verified: retained D7 v2/v3 identity, 260 exact retained payloads, CRC and 16 KiB alignment. Delivery accepted: 20,614,803 bytes, SHA-256 `070eda8122ff4a9cf8ba0649789162c24722be360fbdfce002a060bd06aa31f3`. Physical installation and OEM behavior remain unverified.

The subsequently reported repeated internal chat phases and generic task/diagnostics failure are a separate hotfix priority. This delivery is not evidence that those reported symptoms are corrected. New UX43 layers are paused for that investigation.
