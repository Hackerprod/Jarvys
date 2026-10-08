# Principal-agent contract (UX26)

Jarvys's ordinary main chat uses the original English `MainAgentPrompt` contract. Its stable core is bounded to 6,500 Java characters; this is not a token estimate or a model-quality score. The current user language takes priority over the Android response-locale fallback. Generic workers, configured specialists, proactive and scheduled runtimes retain their own distinct contracts. Coding v3 and its Full/Play, read-only and approval boundaries are unchanged.

## Prompt assembly and capabilities

The run's final tool registry, including its actual recovery, reaction and Crew additions, gates operational sections. Tool schemas own parameters. Skill and bot catalogs carry metadata, not their complete bodies: selected skills load through the existing `read_skill`, and a bot's instructions load only in its child mission. No imaginary loader, imported gateway, new shell, permission grant or external account change is introduced. The core prioritizes user intent, proportional planning, current evidence, bounded context, material updates, causal error recovery and honest completion. Built-in modules retain product-specific safety rules for files, previews, bot creation, images, scheduling and approvals. The existing combined system/schema/transcript context estimator and compaction remain authoritative.

HTML preview guidance reflects UX22: ordinary and `/project/` files can use the supported immutable local-asset preview contract. An attachment receipt, page preview and completed model turn establish different facts. Neither a preview nor a successful tool transport proves visual QA or user-goal completion.

## Outcomes and visible progress

Generic scoped delegation returns a JSON receipt with `run_outcome`, `run_id`, `model_turns`, `task_verified=false`, bounded `text` and `text_truncated`. Incomplete outcomes remain tool failures with their evidence preserved. A legacy text-only adapter yields UNKNOWN rather than inventing completion. COMPLETED means the child model turn ended; the principal owns verification and delivery. This is not a universal semantic completion gate.

Normal assistant text accompanying tool calls is an interim message. The loop checkpoints first, then notifies the ordinary chat listener before tool effects. The UI derives a PROGRESS assistant row from the same marked `model_tool_calls` journal entry, keyed by its first call ID. No second ordinary assistant message is added to model history, reflection indices or the final-answer lifecycle. Blank text, private provider reasoning, generic worker and Crew commentary are not presented through this route. Persisted text uses the existing bounded/redacted journal and may be incomplete when retention limits apply.

Live progress and terminal presentation are scoped to the originating chat and run generation. Navigation cannot redirect an old callback into another conversation. Hydration preserves a live generation when returning to its conversation. Prior tool results and uncertain effects remain durable even when their live UI callback is cancelled.

## Explicit interruption and replacement

For a nonempty plain-text draft in the same active ordinary chat, **Stop and send this message** remains separate from Stop. Attachment sends, managed conversations, compaction and reflection keep their existing restrictions.

1. Persist an inert typed pending request before clearing the draft or cancelling anything.
2. Cancel the current generation and its registered approval/Crew handles. Do not stop unrelated conversations' Crew missions.
3. Reserve the next generation and serialize its dispatch on the same worker, after the previous task has unwound.
4. Promote the exact pending request ID into an ordinary user row. Rebuild the next run's transcript, reactions and connector approval context normally.
5. Reevaluate new work under the latest instruction. A stopped operation may already have effects; inspect its durable receipt/current state before further writes.

This is cancellation followed by a fresh turn, not hot injection into an outstanding model/tool batch. Pending requests are not model history or new approvals until dispatch. Repeated IDs cannot dispatch twice. A superseded or interrupted pending message remains visible/selectable for manual resend; process restart never automatically launches it. Pending input is bounded to 32,000 characters. A blocked native call can delay serialized teardown; the UI must not claim immediate rollback or successful cancellation of its external effects.

## Provenance and verification limits

Research reference: official [OpenClaw](https://github.com/openclaw/openclaw/tree/b925148b83b7ebddb32e1dd68769d6588d16f800), pinned MIT source. Patterns considered include capability-based system assembly, metadata-first skills, protocol-preserving history, and isolated workers. No upstream prompt or gateway implementation is copied; Jarvys uses its own tool contracts and approval runtime.

Deterministic tests exercise actual Java/Kotlin loop, persistence, cancellation, service-dispatch and UI-state paths with scripted model/tool adapters. They establish those assertions only. The 24 researched behavioral scenarios still require actual-model evaluation; no production-quality score, provider run, physical Android acceptance, TalkBack, phone performance or network-isolation claim is inferred. Broader typed connector error categories and a universal semantic completion ledger remain future work. Existing lint debt and the inherited preview WebRTC limitation stay disclosed in root Pending.md.
