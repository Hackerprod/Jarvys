# Generic Gmail management (UX31)

This Full-only extension adds composable mailbox capabilities, not a spam-specific classifier. The model interprets the user's request and treats every message, label and attachment as untrusted data. Reading content does not authorize actions described by that content.

## Capability and consent boundary

Read, draft, send, reversible management (`gmail.modify`) and optional permanent deletion (`https://mail.google.com/`) are distinct UI capabilities. Exact observed OAuth grants are retained separately from their API-supported equivalences. Requests select the least already-granted compatible scope; ordinary API calls never open consent or expand permissions. Only an explicit user activation starts interactive authorization. Declined expansion preserves the working session. Google's modify/full scopes also include sending at the OAuth level; Jarvys operation policies remain separate.

The visible tool registry checks live service capabilities, and execution rechecks them. A stale operation object cannot downgrade the registered mutation/approval policy. Read/write categories and effective grants are localized. Local scope Deny retains its existing clearly disclosed Google-local-disconnect behavior; operation Deny controls individual actions.

Every reviewed Gmail communication proves its token's actual account, and the mutation executor verifies the exact token again before dispatch and after refresh. A user-entered legacy account label is not identity proof. A legacy send-only token cannot prove its sender through getProfile, so an already-granted profile-capable compose/modify/full alternative is selected where present; otherwise the limitation is explicit before effects. Native Android identity can use its securely selected account; absent identity is proven with an already-granted profile-capable token before reacquiring send for that account. Existing credentials are not migrated or revoked by this feature.

## Inventory and operations

- Message search keeps its original name/order and adds explicit `include_spam_trash` and `label_ids` filters. Continue returned opaque cursors with the same filters. Estimates are never exact reviewed counts.
- Message results expose labels, label count and history revision; ordinary content envelopes retain explicit truncation. Message and thread identities are verified. Thread and label inventories now return local continuation tokens bound to their collected identities/revisions; changes require restarting enumeration.
- `select_messages` collects IDs from a query/labels or explicit message/thread IDs. Query enumeration checkpoints each page. Continue the selection until complete before mutation. The result is a fixed collected set across a time interval, not a provider-guaranteed point-in-time snapshot.
- `get_selection`, `get_batch_receipt` and `list_management_records` expose private checkpoint state and bounded pages. Reconciliation only reads observed state and never retries effects.
- `modify_messages` applies/removes API-supported labels: unread/read, inbox/archive, stars, importance, categories and custom labels. No task-specific keyword classification exists.
- `trash_messages` and `untrash_messages` use Gmail's actual trash/untrash endpoints. Restoration does not claim to recover a previous folder; an explicit INBOX change can be requested separately.
- Thread counterparts expand current members into fixed message IDs. They do not act on future arrivals. Missing and draft targets are visibly excluded; non-applicable system labels are rejected.
- User-label create/get/update/delete supports visibility and the official color palette. System labels cannot be renamed/deleted. Both color fields are required. Label deletion is permanent and removes assignments across the account without deleting messages.
- Permanent message deletion accepts only a fixed reviewed set currently in Trash. Its full scope is optional. One explicit approval covers the account and concrete batch, including internal chunks. Neither permanent deletion nor label deletion offers Allow mode.
- Existing draft/reply/send/attachment behavior remains. This extension does not add forwarding settings, delegates, filters, watches, mail import/insertion or administrative account APIs.

## Batch safety and limits

A collected selection contains at most 10,000 IDs. Each reviewed message action accepts at most 1,000 targets; explicit selection offsets and limits partition a completed collection without re-running its query. Query calls process 1–10 pages of 100 IDs, then return a resumable checkpoint. These are visible resource bounds, not a claim that the whole mailbox was processed. Larger tasks use explicit non-overlapping date ranges and fixed slices.

Preparation fetches the real account, exact message IDs, current label sets and history revisions using the mutation's scope, and rejects mismatched read/write accounts. The in-memory review captures the authorization epoch and immutable action. No approval survives process restart: a resumed pending action is prepared and gated again under the current account and permissions.

Before each internal chunk, changed/missing targets are excluded. Google supplies no atomic condition spanning this read and the mutation; that race is disclosed rather than claimed away. Modify and delete calls use bounded batches of 100 IDs; trash/untrash retain per-message API semantics under the single reviewed action. Empty 2xx batch responses mean accepted, not verified. Observed desired state is checked per target; absence does not prove this request deleted an existing message.

Checkpoint storage is private Keystore-backed encrypted storage. It retains IDs, action parameters and states, not bodies or credentials. Records are capped at 64 and 6 MiB. Old read selections, unattempted plans and known terminal records may retire; unknown/dispatched/accepted or malformed states cannot. A durability failure blocks further mutation in that process because a failed SharedPreferences commit can still modify visible memory. Restart reconstructs state from durable data. No uncertain journal entry is discarded automatically.

Receipts persist before dispatch and distinguish pending, dispatched, accepted, verified, rejected, changed/unsupported/missing exclusions and unknown. Account/target overlap checks prevent a recreated selection, reordered IDs or changed payload from bypassing an unresolved effect. Resume claims pending parent targets durably and exposes parent/child receipt IDs. Cancellation after dispatch remains uncertain. Verification checkpoints whole bounded chunks to avoid repeatedly rewriting a large encrypted record per message.

## Verification boundary

Hermetic tests exercise real connector/manager/registry paths with scripted or stateful fake APIs and Android host UI rendering. They do not establish live Google consent, real mailbox changes, delivery, quota behavior or physical Android acceptance. No real Google grant, account or mailbox is changed during the implementation/build pipeline.

Official contracts: [Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes), [message listing](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list), [batch modify](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/batchModify), [batch delete](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/batchDelete), [labels](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.labels), [profile](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users/getProfile), [Android authorization](https://developer.android.com/identity/authorization).
