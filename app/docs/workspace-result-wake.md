# Workspace result wake for Central

## Before and after

Crew persists immutable terminal output in its execution conversation as `crew_result_evidence`. `WorkspaceResultDelivery.reconcile` binds that evidence to the live workspace assignment, writes the source `workspace_result_reference`, then records `workspace_result_notice` in Central. The old notice path ended by updating `AgentRunUiState` and `WorkspaceChanges`; it did not enqueue a Central model run. The owner therefore saw the technical notice while the agent stayed idle.

The notice and a `workspace_result_wake_pending` marker now commit together in `StrictJournalRepository.appendWorkspaceResultNoticeAndWake`. The marker contains only the validated reference ID. `WorkspaceResultDelivery.notice` asks `AgentForegroundService` to start a system-origin Central turn with that ID. The service's existing per-session FIFO lane queues the request behind any active Central run. When the lane takes it, the service transactionally appends `workspace_result_wake_started` after checking that the matching reference remains pending. It does not interrupt or replace the active run.

`ConversationReader.loadConversationContext` projects pending notices into typed `ConversationTurn.Kind.SYSTEM_EVENT` turns with role `system`. The event states the workspace ID, assignment ID, worker-reported terminal state and result reference ID. It contains no raw result text, hash, or worker instructions. Its typed reference ID is carried separately on the turn. `CoreAgentLoop` sends that system turn as model context without creating a user message. The successful response checkpoint carries only the event reference IDs included in that provider request. `MainChatTranscriptStore` persists those IDs with the response transcript rows in one Room transaction, which acknowledges the wake in the same commit. A duplicate queue item then fails its pending claim and becomes a no-op.

## Durable recovery and delivery semantics

The pending marker remains durable until the successful provider response checkpoint and `workspace_result_wake_acknowledged` commit together. A `started` marker does not consume or hide the pending event. If the process dies after the lane claim but before that transaction, the event remains pending. Recovery runs at two concrete entry points: `AgentForegroundService.onCreate` calls `WorkspaceResultDelivery.scheduleCatalog`, which enumerates the workspace registry and enqueues each execution session for `WorkspaceResultDelivery.reconcile`; `MainActivity.onResume` calls the same catalog scan. Reconciliation finds the same immutable result, repairs a missing pending marker if needed, and retries an unacknowledged wake. A newly arriving `ACTION_WORKSPACE_RESULT_WAKE` also starts the service directly. A same-process request set coalesces duplicate intents, while the atomic checkpoint acknowledgement prevents another successful model turn after a response was durably recorded.

The notice and pending marker are written in one Room transaction, so a process death cannot commit only one of those two rows. The worker evidence and source reference are validated before either is disclosed. Wake markers and notices remain excluded from memory input and model-transcript row indexing; the system event is projected only while its exact reference is pending.

The durable local boundary is idempotent: once a successful provider response checkpoint commits, the same result cannot enqueue another successful wake, including after process recreation. If the process dies before that checkpoint, the pending event is retried. Exactly-once execution at the external provider boundary cannot be guaranteed: a process may die after submitting a request but before saving its response, so recovery can submit that ambiguous request again. A process can also fail after acknowledgement but before later tool work or a final answer completes; the first successful provider response is the acknowledgement boundary by design. If Android force-stops the app or never starts the app/service again after process death, the durable event waits until the next service or activity entry point; the app does not promise a background relaunch. The system does not replay a worker or trust its reported state as verified.

## Chat visibility and evidence

`workspace_result_notice` remains in the durable Central timeline and remains readable through the existing workspace result APIs. It continues to be available to recovery and strict-journal inspection. `ConversationVisibility` filters the generic `workspace_result` timeline event from the owner-facing central chat projection, so UUIDs and audit wording are not shown as a chat card. Crew and workspace result views keep their existing cards.

The model receives only the reference metadata event. It must use the existing `workspaces` tool and `read_result` flow to inspect exact saved output. Tool disclosures continue to describe worker output as unverified evidence; neither the result status nor receipt existence proves that a correction was applied or consumed.

## State and owner policy

Only the persisted terminal states accepted by `WorkspaceResultReference` create wakes: `DONE`, `PARTIAL`, `FAILED` and `STOPPED`. Intermediate Crew states do not create result references and therefore cannot enqueue a wake. Failed and stopped terminal results are delivered so Central can report them.

There is no separate autonomous-wake toggle. An unset action policy defaults to `ALLOW`; an explicit `ASK` or `DENY` remains authoritative for each tool action the model attempts. The wake itself grants no tool permission and bypasses no Android permission. The wake creates no separate user notification; the foreground service uses its existing run notification. Notification permission does not change the durable pending/ack state.

## Files and boundaries

This change uses existing `conversation_items` ledger rows and does not alter Room v1 entities, tables, or migrations. Workspace and Crew UI cards are unchanged. Recovery depends on Android allowing a later service or activity start so the documented scan can run; process, battery, foreground-service and provider limits still apply.
