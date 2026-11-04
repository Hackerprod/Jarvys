# P4A native proactive reply handoff — design candidate

Baseline: `e0801e05caca2f4857ace19e1d7033cd8fe7a356` (delivered v100 source tree). Design candidate dated 2026-11-04; no implementation PASS or new APK. Source tracing is not an executed reproduction. All source paths below are relative to app/app/src/main/java/com/jarvys/agent/.

## Scope and outcome

Close the source-traced acceptance persistence/handoff gap for (a) selected proactive options and (b) proactive notification inline replies. Keep real USER origin, current thread/reply-to metadata, one Central queue/chat, existing tool approvals and Engram scope. No suppression/fatigue/TTL policy, no legacy backfill, no read/app-open admission. Scheduled TaskActionReceiver buttons are a separate entry path and are not silently represented as covered; task-result chips rendered through ProactiveInteractionDispatcher are covered by that entry path.

Current traces: ProactiveInteractionDispatcher.kt:22–33 consumes an option then independently inserts USER; :36–51 independently inserts inline USER; :54–61 sends legacy ACTION_REAL_AGENT. LocalRunStore.java:985–997 uses a fresh random message identity, not Central submission evidence. The service startup scan covers interrupts (AgentForegroundService.java:1607–1633) and AGENT inputs (:1394–1420), not these USERs. The option claim itself is serialized/durable and already rejects double consumption (ConversationRepository.kt:878–905).

## Native action identity and authority

Introduce a narrowly typed native reply action adapter, alongside rather than weakening the ordinary NativeSendAction. Its active process-local owner must be the current UI callback or private receiver delivery, and its exact object/lifecycle must remain current through the transaction and post-commit registration. Selection data or an Intent alone is not a live permit. The MainActivity chip callback must capture the actual displayed event/option identity into this owner before launching its IO coroutine; passing messageId/index to a public factory later is insufficient. Revalidate the captured event/source selection before admission. The private receiver creates its owner only after validating the current explicit Intent URI/extras and retains that owner through goAsync completion, then closes it. A lifecycle/generation change expires the action. Tests must exercise the real UI and receiver owners, not only construct argument DTOs. It must never be callable by the silent review loop as a grant, inherit a background witness, or reconstruct itself from persisted rows.

Option logical acceptance identity: storage generation + Central session + source notice ID + action kind. Exclude option index from this identity, since all options on one notice are already single-consumer. Conserve selected ordinal and canonical text in the immutable acceptance payload. An identical repeated selection returns the prior receipt/disposition without a second USER or fresh permit. A different ordinal for the consumed notice returns a conflict/consumed disposition, never another action. Distinguish an old consumed marker with no typed receipt: retain it as legacy evidence; do not synthesize accepted USER or authority.

Inline has no option consumption marker. Its exact logical action identity is a canonical length-prefixed digest of storage generation + Central session + full thread key + notified source message ID + INLINE. The existing immutable canonical message is the durable occurrence anchor, not a caller nonce; actual live native receiver delivery supplies permission. There is no new issuance table or assertion that an OS notification was durably displayed.

Repeated delivery of that identity with exact normalized text/source returns the existing receipt without a second USER or fresh permit; different text/source conflicts. Reconcile an existing receipt before admission-only visibility/latest checks. For a first acceptance, require the source is the current eligible visible thread head, and bind its exact canonical metadata. The accepted USER becomes the next thread head in the same transaction. Immediately refresh the notification from canonical latest thread state after commit, rather than waiting for a provider answer; a subsequent intentional identical reply therefore addresses a different real source message and remains possible. Posting failure does not undo or repeat the accepted USER. Reposting an unchanged source preserves its identity. A stale source cannot create a new acceptance, even when its old PendingIntent arrives after refresh. Never deduplicate by text or clock.

Encode generation, source message and action identity in PendingIntent data URI as well as extras. Verify exact correspondence in the receiver; thread-only URI plus FLAG_UPDATE_CURRENT would otherwise retarget an old handle by replacing its extras. Use an explicit component and the existing non-exported receiver. Only the inline RemoteInput PendingIntent uses FLAG_MUTABLE on API 31+; chip/open-chat intents retain FLAG_IMMUTABLE. Earlier supported Android versions use their existing RemoteInput-compatible flag semantics. Do not allow caller-filled source/action fields to replace the canonical URI binding. Keep the existing FreshStorageBootstrap format-generation guard, but additionally bind the current physical Room generation from storage_meta/ConnectionProjectionRepository in the action identity, URI, immutable acceptance part and transaction validation. A same-format recreated store must reject an old notification even if its bootstrap stamp still matches. Validate both before any write. Host tests inspect flags and native delivery but do not prove physical Android interaction.

ProactiveNotifier.kt:91–109 currently has canonical source/index for options but no inline per-action ID; :103–109 is the bounded notification plumbing change. Keep flags/privacy/channel behavior otherwise unchanged. This identity addition must be covered by notification tests rather than pretending Android supplies a per-delivery idempotency key.

## One composite writer; no broad guard deletion

Preserve ConversationModelTurnWriter.kt:76,85 ordinary-send guards. Add a separate typed reply writer, sharing only an internal persistence primitive after its exact source validation. Do not strip proactiveThreadKey or pretend the reply is an ordinary non-threaded row.

The service adapter uses the existing `interruptAdmissionLock(Central)` before lifecycle/Room admission, as ordinary `admitCentralSubmission` does. Required lock order is Central admission lock → destination lifecycle fence → native Room writer. No Room callback may acquire the Central admission lock in reverse. This serializes receipt registration with native STOP capture; no separate queue is introduced. Under that existing writer and lifecycle fence, in this order:
1. Revalidate the live native action and storage generation; resolve canonical source row, thread, option (when applicable), and immutable action identity. Read exact existing acceptance by stable ID before mutation.
2. For a new option, confirm source assistant/decision/ordinal/thread and unconsumed marker. For inline, confirm the notified source's thread token and exact action/text binding; no option consumption.
3. Append genuine USER with preserved thread and reply-to semantics, selected text or normalized inline text, empty attachments/selected skills as today, CentralSubmissionRequest part, and one strictly decoded acceptance part.
4. Append the option's consumed marker/update only for option selection, and insert the existing central_turn_submissions PREPARED row. All three changes share the same writer transaction. Failures abort the whole transaction, including spine/counters/parts.
5. Revalidate the live action before commit/return. Return the existing CentralSubmissionAppendReceipt plus immutable acceptance disposition.
6. After commit, update in-memory UI and register a live Central permit only for the first current action's new commit. Start via AgentForegroundService.startCentralSubmission, never the legacy stored-message entry point. Duplicate receipt reads never register a fresh permit. If registration or service start fails, retain PREPARED and expose existing explicit recovery.

Use current stable IDs/message_parts and Room v3 submission schema if they satisfy this contract; no new database or parallel mutable status. A new typed part still requires D3 format/read/rollback compatibility review. Existing ordinary-send request and root digest formats must not be silently rewritten.

## Immutable acceptance linkage and all source revalidation

Suggested strict acceptance fields: version, action kind/ID, source message ID, canonical thread key, USER reply-to ID (nullable for inline to preserve existing latest-prior-notice semantics), selected ordinal for options, and selected/normalized text digest. Include canonical decision identity where applicable. Store inline source-message binding even when its reply-to column remains null. The USER text parts already enter the root digest. Use exact typed schema, no duplicate/unknown fields, and verify source role/decision/thread/selection according to action kind.

CentralUserRootCheckpoint.kt:19–44 currently hashes parts/attachments but not proactive columns. Add one shared acceptance validator called from this root capture/revalidation function BEFORE hashing/attesting, so every later digest recomputation validates acceptance part against canonical USER thread/reply-to/text and source notice or notified-message metadata. Positive threaded submissions require that part. Ordinary submissions retain their current null-thread rule. Legacy non-positive thread history remains readable without inventing a typed receipt. A missing/changed typed part, thread, reply-to, source, ordinal or text fails closed; never recover by stripping metadata or falling back to ordinary USER.

This shared entry is reached by:
- CentralTurnSubmissionRepository.kt:48,77,102–119,140–159,188–197: completed confirmation, regeneration, prepared insert, claim/recovery/finish/cancel source checks, suspension checks.
- ChatDatabaseBridge.kt:1685–1696,2024–2030,2035–2044,2107–2117: initial/canonical root reads and validation, post-commit receipts, lookup, explicit recovery receipt.
- ConversationReader.kt:260–263: evidence-root context validation.
- InteractionProtection.kt:134: protected root integrity.
- ConversationRepository.kt:798: retained regeneration source capture.

CentralSubmissionRequest.read (:24–27) must retain its exact skill-request decoding. It may invoke the same acceptance validator for positive sources, but it is NOT the only validation hook: several source checks call the digest helper directly. Ensure no recursive validator→digest loop.

## Readers/fences and preserved behavior

- Submission context: ChatDatabaseBridge.kt:2161–2177 → ConversationReader.kt:143–157 validates the exact claim and excludes other queued/uncertain USERs. This should remain unchanged except shared source validation; accepted replies become NEW_TURN and must not simultaneously hot-steer a predecessor.
- Generic steering: MainChatTurnContextProvider.java:91–104 currently sees plain proactive USER rows. Existing submission exclusion must cover new threaded positive rows too. Test this exact distinction, not a second mutable state.
- Referent: provider constructor :27–35 seeds the starting USER ID; :137–150 emits the untrusted-notice warning and reads its compacted referent. ConversationRepository.kt:1159–1190 uses reply-to for options and latest earlier notice of the same thread for null inline reply-to. Keep metadata and those semantics. Validate typed linkage before contextual source use (including takeUntrustedContext/readCompactedProactiveReferent), not only before service claim, to close post-claim metadata mutation races. A mismatched immutable source must fail rather than silently use another thread. Visibility/latest-row eligibility is checked only before first acceptance: a later native hide or newer thread message does not revoke the already accepted USER or prevent its STOP. Source identity/role/thread/decision/selected payload remain validated against the immutable acceptance receipt.
- Thread notifications: ConversationRepository.kt:1140–1149 and ProactiveInteractionDispatcher.kt:64–80 read thread rows; AgentForegroundService.java:2704 refreshes the same thread after reply. No thread metadata stripping, no cross-thread output.
- UI: ConversationReader.kt:569–581 materializes threaded USER with kind=user; AgentRunUiState.kt:158–166 retains message ID/thread. CentralAvailabilityUiProjection.kt:62–75 attaches PREPARED/UNCERTAIN by userMessageId without a thread exclusion. Thus existing UI recovery can work without a new screen.
- Exact retry: Compose ConversationTimeline.kt:475–476,560–566; Canvas ChatCanvasContract.kt:81–87,144–146; MainActivity.kt:1157–1168 → AttachmentDraftViewModel.kt:344–365. The latter checks actual displayed event identity using AgentRunUiState.kt:669–675 and exact revision. No app-open retry, no raw ID-only recovery factory.
- Reaction/attachment exclusions remain: AgentRunUiState.kt:109,118; ConversationRepository.kt:547,839; ConversationArtifactsRepository.kt:256. Threaded input is genuine USER without gaining attachments/reactions previously excluded.
- STOP keeps current captured cohort CAS (ConversationModelTurnWriter.kt:108–122; CentralTurnSubmissionRepository.kt:209–225). Source validation participates in cancel; never turn a failed validation into a fresh acceptance. Existing process-local permit invalidation and exact physical claim remain unchanged.

## Crash order and acceptance outcomes

Before composite commit: nothing consumed/accepted; a fresh real user interaction may retry. After commit but before registration/start: one USER + PREPARED, no auto execution on reopen; existing exact UI retry is required. After permit registration but before service claim: process-local permit cannot survive restart; still explicit PREPARED recovery. After STARTED: no reset/replay, even if provider result is absent; existing uncertain projection remains. After terminal commit before ACK: same receipt, no new USER/effect. Same-action duplicate after STOP/terminal remains consumed, never a new permit. A genuinely new inline affordance is a different explicit user action, not replay.

## Deterministic red tests first, then acceptance cohort

RED-1 (actual production prepare path): synthetic option notice; SQLite trigger rejects INSERT into messages when NEW.role='user'; prepareSuggestedReply fails; close/reopen Room; current baseline shows consumed notice + no USER + repeat selection null. No service/provider needed.

RED-2 (inline): call current prepareInlineReply with canonical source/token and stop before launch; reopen and inspect USER + zero central_turn_submissions; current exact UI projection offers no PREPARED retry. Do not call this a provider/external effect test.

Post-fix acceptance:
1. Trigger failure at USER, consumed marker and submission insert independently: complete rollback; counters/spine conserved.
2. Concurrent same-index and differing-index selection: one accepted USER/receipt, same-index idempotent; conflicting selection no mutation/no permit.
3. Repeated identical inline action ID: one USER; same ID/different text conflicts; fresh inline action ID permits intentional same text again.
4. Crash/exception after commit before register, after register before service claim, and after STARTED: exact states above; reopen/view does not run; real displayed PREPARED retry runs once; STARTED cannot retry.
5. Duplicate service Intent and concurrent UI/notification tap: one physical claim/provider start.
6. Corrupt only thread column, reply-to column, source-message thread, option ordinal/text, typed part or generation after prepare; initial receipt, recovery, claim, context/referent and terminal checks all reject without fallback or cross-thread content.
7. Actual Compose and Canvas retry for threaded USER; stale displayed revision/fence fails; fresh event succeeds; no extra chat/menu.
8. STOP exact cohort before dispatch blocks it; double-tap after STOP cannot revive it; unrelated later action unaffected.
9. Running Central: accepted positive reply stays out of predecessor steering, yields/queues through existing safe boundary; referent/warning preserved after compaction for both option and inline.
10. Notification refresh/post failure never redoes accepted write, and second intentional reply gets fresh identity.

Extend existing ProactiveAgentProcessorTest, ProactiveActionReceiverTest, ProactiveCentralTest, ProactiveSuggestedRepliesComposeTest, ChatCanvasContractTest, CentralSubmissionPersistenceTest, CentralAvailabilityServiceTest and CentralAvailabilityBoundaryAcceptanceTest. Run relevant host Full/Play only after the design/reproduction gate. Do not claim device acceptance or upstream behavior from these tests.

## Phase boundary and D4 policy disposition

The current implementation request authorizes continuing the roadmap; earlier roadmap statements about waiting for implementation approval are historical. P4A is the acceptance/handoff subset of P4's first vertical unit. It is not completion of durable suppression or fatigue. Independent design review precedes new format writes; independent implementation review follows fresh matching Full/Play evidence.

No new suppression, time window, quiet-hours, fatigue threshold or automatic reactivation is enabled by P4A. Existing explicit hide makes a notice ineligible for a new acceptance. Hiding after acceptance does not cancel the already accepted USER; cancellation remains the existing STOP operation. Both option and inline validation must reject a source hidden before admission. No restore control or new menu is introduced.

The next bounded part of the first P4 unit will define exact cited source-occurrence/version identity, independent of generated wording, and preserve native explicit hiding durably without a timer or topic mute. Its separate design must resolve source-version CAS, publication/hide concurrency and legacy audit replay before implementation. It must not be reported as implemented by this handoff repair.

Upstream provenance: OpenDuet `677e0fcd148d80b99dd788c6d6f9c1c9f40a72db`, `openbot/server/src/proactive/engine.ts:105–107,319–327` uses a fourteen-day dismissed-title lookup and normalized title equality, not deterministic semantic evidence equivalence. `proactive/store.ts:771–792` atomically resolves open suggestions once, but that alone does not prove cross-store task dispatch atomicity. Its `routines/runner.ts:44–48,164–185` ten-firing failure policy belongs to routines; Jarvys already moves ERROR/PARTIAL tasks to NeedsAttention and requires explicit resume. Neither numeric policy is copied. Existing native enabled flag, OS notification choices, user preemption and scheduler timing stay unchanged.

## D3 compatibility and activation gate

New typed reply writes remain disabled until both variants pass predeclared format/conservation tests: genuine current v100 ordinary USER and threaded history remain readable; old consumed notices are not backfilled; old PREPARED/STARTED rows retain their exact authority; absent/null/unknown/duplicate/mismatched new proof fails closed; UTF-16 text and thread identity are conserved; rollback and reopen preserve all existing rows, indexes and foreign-key behavior. No schema migration is planned. If a required additive schema change is discovered, revise and review this design before enabling writes. Older binaries are not promised to understand new positive typed reply submissions; do not recommend downgrade after new writes.

Inline identity uses the existing immutable source row as specified above. No new mutable issuance status or schema table is needed: acceptance consumption is derived from the exact existing submission/USER receipt. New-format receiver identities missing their source/generation/URI proof fail before admission. Old notification intents remain historical rather than being upgraded into new authority; refreshing a notification through the current app creates the current explicit affordance. Existing ordinary and threaded rows stay readable without backfill.

## Review status

Design candidate only. Baseline failures, new tests, compatibility outcomes and exact source bindings will be recorded separately after execution. The prior v100 host and packaging PASS remains historical and does not validate this candidate. Physical phone, live provider and universal latency behavior remain unverified.
