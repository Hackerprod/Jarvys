# UX43: causal local coordination

Development checkpoint, not a delivered APK. The current delivered version remains v91. First integrated Full Debug Kotlin/Java compilation passed on 2026-10-12 at 21:57 UTC. Independent contract/source review and bounded direct tests are available; integrated behavior, UI and artifact acceptance remain pending.

## Evidence and authority

Selected worker QUESTION, FINDING and explicit BLOCKER messages persist exact immutable evidence in the existing source JSONL ledger. Identity binds source conversation, mission, bot, execution cycle and message. The content digest includes exact text, references, kind and timestamp. Legacy checkpoints remain historical; recovery never invents a live reply capability.

A bound workspace assignment registers a deterministic reference, preserving its first observed revision. Source/reference persistence does not disclose a body or confer authority. A worker finishing or asking before assignment binding is handled by later local receipt reconciliation. The existing finite receipt scheduler is reused; no model push, automatic polling loop or new external communication is introduced.

## Central tools and visible state

The central-only `workspaces` tool exposes `inbox`, `read_message` and `reply`. Existing WORKSPACE_READ and WORKSPACE_DELEGATE policies apply, with current admission checks and STOP. Default Allow permits local metadata notices; Ask requires authorized foreground disclosure and Deny withholds it. Policy changes invalidate the native summary. Worker content stays untrusted data.

Inbox pages contain bounded metadata and an exact continuation cursor. Content pages use `coordination-content-json-ascii-v1`: concatenate `content_json_fragment` in offset order before parsing the complete text/refs JSON. The serialized-content digest is distinct from the immutable evidence digest. Existing output budgets remain enforced without silently truncating bodies or references.

The native drawer/detail shows a consolidated metadata indicator for the inspected bounded page. Historical or interrupted requests are not presented as live work. Internal conversation and ledger records remain excluded from the ordinary visible chat, model transcript, reflection and compaction; useful final responses and actionable errors retain their existing presentation.

## Exact reply and recovery

Only a live QUESTION or BLOCKER from the original mission/cycle can receive a reply. FINDING is read-only. Exact workspace/reference/hash, observed assignment revision, current native controls and policy must match. Replies use an existing manager and capability; they cannot create, restart or resume workers.

The journal records PREPARED, STARTED and ACCEPTED_LOCAL. A matching retry within the same originating central request can return its receipt. A different request/provenance or payload is a conflict. STARTED without acceptance remains uncertain and is never resent under another key. A pending native control, STOP, superseding correction, terminal cycle or lost process capability prevents fresh dispatch.

ACCEPTED_LOCAL proves local mailbox acceptance only. It does not prove consumption, completion, resolution or user approval. The existing causal answer path accepts the matching answer or genuine user guidance, not unrelated replies. Reopening can reconcile idempotent local receipts, but never replays worker execution or reply effects. Storage corruption remains explicit rather than being treated as an empty inbox.

## Remaining scope

This slice does not implement workspace memory, proactive inference, server push, legacy chat migration or UX34 gated phases. Explicit file delivery remains the separate WORKSPACE_DELIVER contract. Physical process-death/power-loss behavior and real-model usage require their own evidence. CrewAI remains last in Pending order.
