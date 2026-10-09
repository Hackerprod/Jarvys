# UX37: durable activity presentation

## Contract

An actual invocation has one visible row. Its stable execution identity is independent of its display name, provider call ID, and the UI row ID. New runtime events use the run UUID, model-turn occurrence, and call occurrence; raw provider IDs remain evidence for transcript association. A retry is another execution. Tool names and arguments are never deduplication keys.

The canonical events are immutable and append-only. `ToolActivity.project` is a presentation reducer: duplicate event deliveries are idempotent, the first definitive outcome is authoritative, and later starts/progress cannot downgrade it. Progress and useful result/error detail remain selectable. Unknown stages and incomplete execution are unconfirmed, never successful. Stopping does not imply that effects were rolled back.

Successful ordinary tools show Used; failed tools show Failed. A real `LoadSkillTool` invocation carries the selected skill identity and uses Loading skill / Loaded skill / Failed to load skill. Loaded means complete instructions were accepted at the tool-result boundary. It does not certify that subsequent work followed those instructions. Missing, disabled, oversized, cancelled, or context-budget-rejected skills are not labelled loaded.

## Storage and recovery

- Crew records local activity with explicit `origin: local_activity` and a typed payload. It does not enqueue a message, notify the captain of pending work, or enter the captain's message description. Real communications, including actual STATUS messages, preserve their sender and recipient.
- Snapshot schema 3 reads versions 1 and 2. Both snapshots and checkpoint metadata use `CrewMessage`'s codec. Original records remain intact; projection never pairs historical English strings or tool names. Unknown typed payloads remain local, distinct, and unconfirmed.
- Restoring a Crew worker adds an interruption observation for each unfinished retained execution. Explicitly resuming that same bot does not revive those old rows. A new execution has a new identity.
- Main-chat activity has dedicated `tool_activity` ledger records, outside executable transcript and reflection inputs. The first event binds to the exact already-persisted model batch; following events retain that association. Legacy model calls/results use their batch identity, with conservative handling of ambiguous metadata.
- If a durable model result exists but the final UI event was interrupted, recovery preserves that result. For skills, a missing final acceptance remains unconfirmed with the available instructions visible. No tools are replayed by presentation recovery.
- Preview references remain subject to the existing conversation ownership checks. If a preview is no longer available, the row retains its other evidence and reports the missing preview.

## Presentation and identity

The main chat retains its existing connector localization, web-result links, Markdown/skill-file links, preview actions, and result selection. Crew uses the same lifecycle language and expandable result pattern. Local execution rows do not display a recipient; actual inter-agent communications still do.

Jarvys's principal identity uses the existing launcher through Android's Drawable renderer, which supports adaptive launchers. It is shared by captain/chief avatars and presentation-only entries in the Bots catalog and mission list. No principal worker profile, spawn route, capability, approval, or editable bot definition is created. Existing custom and built-in bot identities remain unchanged.

All new labels have English and Spanish resources. Expand controls have 48 dp targets, state descriptions remain available, large labels wrap, and the new lifecycle rows introduce no animation. Existing avatar motion still follows reduced-motion and visibility policy.

## Validation boundary

Host checks cover projection, persistence, legacy migration, real local skill loading with synthetic model responses, late events, retries, interruption/resume, presentation-only principal identity, and EN/ES light/dark normal/2x Compose rendering. Provider calls, physical Android installation, physical TalkBack, and real WebView acceptance are separate checks and are not implied by host results.

UX35, UX36, UX38, the factory assets, launcher resources, package identity, permissions, and UX34's diagnostic-only phase remain in scope for regression checks. UX37 does not activate context maintenance.
