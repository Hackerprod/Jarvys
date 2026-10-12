# UX43: causal local coordination

Delivered software slice v92 / `1.2.85-COORDINATION`, source `b2d3e49dac4d4efa57531d7780097b54b226f408`. ZIP send accepted on 2026-10-12 at 23:24:59 UTC; extract the APK before installation. This closes the bounded coordination slice below, not every UX43 roadmap item.

## Evidence and authority

Selected worker QUESTION, FINDING and explicit BLOCKER messages persist exact immutable evidence in the existing source JSONL ledger. Identity binds source conversation, mission, bot, execution cycle and message. The content digest includes exact text, references, kind and timestamp. Legacy checkpoints remain historical; recovery never invents a live reply capability.

A bound workspace assignment registers a deterministic reference, preserving its first observed revision. Source/reference persistence does not disclose a body or confer authority. A worker finishing or asking before assignment binding is handled by later local receipt reconciliation. The existing finite receipt scheduler is reused; no model push, automatic polling loop or new external communication is introduced.

## Central tools and visible state

The central-only `workspaces` tool exposes `inbox`, `read_message` and `reply`. Existing WORKSPACE_READ and WORKSPACE_DELEGATE policies apply, with current admission checks and STOP. Default Allow permits local metadata notices; Ask requires authorized foreground disclosure and Deny withholds it. Policy changes invalidate the native summary. Worker content stays untrusted data.

Inbox pages contain bounded metadata and an exact continuation cursor. Content pages use `coordination-content-json-ascii-v1`: concatenate `content_json_fragment` in offset order before parsing the complete text/refs JSON. The serialized-content digest is distinct from the immutable evidence digest. Existing output budgets remain enforced without silently truncating bodies or references.

The native drawer/detail shows a consolidated metadata indicator for the inspected bounded page. Historical or interrupted requests are not presented as live work. New internal evidence/reference/notice/reply rows are not injected directly into visible chat, model context, reflection or compaction. Authorized tool reads remain available to the model in its normal transcript. Useful final responses and actionable errors retain their existing presentation.

## Exact reply and recovery

Only a live QUESTION or BLOCKER from the original mission/cycle can receive a reply. FINDING is read-only. Exact workspace/reference/hash, observed assignment revision, current native controls and policy must match. Replies use an existing manager and capability; they cannot create, restart or resume workers.

The journal records PREPARED, STARTED and ACCEPTED_LOCAL. A matching retry within the same originating central request can return its receipt. A different request/provenance or payload is a conflict. STARTED without acceptance remains uncertain and is never resent under another key. A pending native control, STOP, superseding correction, terminal cycle or lost process capability prevents fresh dispatch.

ACCEPTED_LOCAL proves local mailbox acceptance only. It does not prove consumption, completion, resolution or user approval. The existing causal answer path accepts the matching answer or genuine user guidance, not unrelated replies. Reopening can reconcile idempotent local receipts, but never replays worker execution or reply effects. Storage corruption remains explicit rather than being treated as an empty inbox.

## Remaining scope

This slice does not implement workspace memory, proactive inference, server push, legacy chat migration or UX34 gated phases. Explicit file delivery remains the separate WORKSPACE_DELIVER contract. Physical process-death/power-loss behavior and real-model usage require their own evidence. CrewAI remains last in Pending order.


## Validation and delivery

The reviewed affected scope contains 39 classes per flavor. Full: 415 passing cases, comprising 412 retained cases with exact production/resource/dependency and unaffected-test equality, plus the entire repaired three-case Compose class. Play: 415 fresh passing cases. Seventeen earlier seam checks ran against integrated FullDebug production classes. The original compile failure (Android JSONObject key iteration) and two nested-semantics fixture failures remain in private evidence; repairs preserve all assertions. Test guards recorded 2/0/2 blocked attempts, with no global zero-attempt claim.

One FullRelease and its Vital task passed. Independent review explains all changed DEX, resource, manifest and ART-profile content against v91. Factory/Core/runtime/SDK evidence is retained through 333 source and 108 resolved-input equalities. Full and Play lint tasks remain FAILED on the same 46/37 historical errors; three additional PluralsCandidate warnings per flavor reflect real singular/plural wording debt in the counters. No detector was suppressed. Full/Play Debug APKs were not rebuilt, and unselected app tests remain historical.

Retained-D7 signed ARM64 APK: 21,347,987 bytes, SHA-256 `ad0bbfb6c2b232db57664a52b019c35929b0c02459be3625b2f8e9d1f39ef284`. Delivered ZIP: 19,790,107 bytes, SHA-256 `e4f61d3727b1bebfbec4c7410596a25da76f27fcc64f2dbd49a1c2d4403a5bdd`. Independent persisted readbacks verified exact contents, certificate, CRC and alignment. Sending does not establish installation, physical recovery or real-model acceptance.

A later bounded step may expose a durable acknowledgement that an exact reply or correction was incorporated into a worker checkpoint. This version does not infer that from observed assignment revision, local mailbox acceptance or redacted historical text. Such an acknowledgement would still not prove understanding, compliance, resolution or verified completion.
