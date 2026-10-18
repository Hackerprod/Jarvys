# UX43: first durable workspace slice

First vertical delivered as v87 / `1.2.80-WORKSPACES` on 12 October 2026 at 10:26 UTC, from source `f015829150bcd415ca142d14373c0d407ce9049c`. Independent three-APK and signed persisted-payload reviews passed. Affected host coverage: 1,763 reconciled Full cases and 1,721 fresh Play cases; independent modules retained by exact input equality. Historical lint errors remain, with no new error/warning. Earlier interrupted/failed runs and the successful unchanged-source Release packaging retry remain recorded. This closes this software slice only; physical-device acceptance and later UX43 stages remain open.

Signed ARM64 APK: 20,590,227 bytes; SHA-256 `4d062369ee8f1b356a7762a41c259efc1a1d23aa317eb335b054d3b9172afe5d`. Accepted attachment delivery does not establish download or installation.

## User-facing behavior

Jarvys central is a separate conversation. Existing conversations retain their IDs, histories and files. The sidebar opens durable workspaces with recorded assignment results and scoped file views. Workspace views are read-only. Correct with Jarvys, Pause and Resume return to central and retain the exact workspace, assignment and revision selected by the person.

The central-only `workspaces` tool exposes create, list, delegate, read, inspect_file and deliver. Simple requests need no workspace or extra classifier/title model call. Creation, delegation, reads and explicit file delivery use the existing configurable native policy, default Allow always while preserving explicit Ask/Deny. File inspection reuses the read policy.

## Identity and recovery

The additive SQLite registry separates workspace ID, execution session, request, mission, assignment and native control. Existing Crew profiles, checkpoints, project readers, writer ownership and snapshots remain authoritative for execution. Workspace execution sessions cannot be opened as ordinary user chats. A stable key with conflicting data fails rather than retargeting earlier work.

A STARTED assignment or control without a durable result is uncertain. Opening a view or restarting Jarvys does not replay it. Native controls bind the displayed assignment revision and the live worker cycle. Older parent cancellation cannot stop a later user-authorized cycle. A pause receipt requires worker termination and owned-resource cleanup; pending cancellation is displayed separately. A later successful safety pause does not erase an older uncertain effect.

Worker output is untrusted evidence. Recorded DONE or a displayed result is not independent verification. Tool read receipts record observation, not proof that the model or person consumed the result. Files use the registry's execution-session owner and existing path-checked readers.

## Explicit file delivery

Central can inspect an exact workspace file to obtain its current SHA-256 and size, then deliver that checksum-bound file into central as an immutable attachment. The registry selects the source execution session and project; the tool cannot name another chat or destination. Both ordinary workspace files and its scoped `/project/` outputs use the existing checked readers and snapshot store. Inspection creates no attachment. A changed checksum, revoked policy, STOP or invalid owner prevents the next protected effect; an already committed effect is reported honestly rather than rolled back or blindly replayed. Repeating the same immutable delivery coalesces its attachment.

File snapshots retain the existing 256 MiB ceiling, bounded HTML preview behavior and actual storage limits. Final ledger publication verifies immutable bytes under its existing STOP gate; hashing a large file can delay completion of a concurrent STOP until that bounded publication boundary ends. This explicit handoff is not a background push queue. Factory requests in central use workspace delegation to Coding; legacy chats retain their existing Crew entry point.

## Deliberate boundaries of this slice

- A terminal worker result creates a durable reference-only wake for Central; the service queues a system-origin turn and Central can inspect the retained result using `workspaces` read. Opening a workspace alone still does not trigger inference, and raw worker output is never copied into the wake.
- No migration of legacy conversations or task/proactive channels.
- No universal resumability for legacy profiles without suitable checkpoints.
- No new server, scheduler, private-memory mount for workers or cross-workspace memory index.
- Android process, battery, foreground-service and provider limits still apply. Host tests do not establish physical-device continuity or recovery.
- UX34 remains at its independently approved phase; this slice does not authorize its later maintenance, migration or retention changes.

The rest of UX43 remains pending, with CrewAI last in the general queue.
