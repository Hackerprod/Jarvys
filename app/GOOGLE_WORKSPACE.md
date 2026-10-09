# Google Workspace connectors (UX19, extended by UX31)

Generic Gmail management, effective scopes, account proof and resumable receipts are documented in [GMAIL_MANAGEMENT.md](GMAIL_MANAGEMENT.md); that contract supersedes the original Gmail-only limitations below.

## Scope and authorization

These native connectors are available in the Full distribution. They keep the existing Android Google Identity `AuthorizationClient` flow and the optional legacy Desktop OAuth client in Advanced. No client secret, service account or server OAuth flow is embedded in the APK.

The connector is usable only when it is enabled and its base read scope is actually granted. Gmail read, compose, send, reversible management and optional permanent deletion, and Drive read and per-file write capabilities are enabled separately. Interactive authorization requests the currently enabled set plus the feature selected by the user and stores only the actual granted set. A normal API call never launches consent for a new scope. Native access tokens remain in call-local memory; existing legacy refresh grants remain encrypted.

An authorization attempt owns a monotonic session and a unique Activity result key. Cancellation, a changed account/configuration, local disconnect or Activity destruction invalidates the attempt; old callbacks cannot reconnect a new session. Write approvals capture that authorization epoch, and request-lease acquisition rejects an account/configuration change even after approval. The UI exposes authorization progress/cancel and distinguishes local disconnection from remote revocation. Remote revocation is project-wide and is reported as verified only after Google acknowledges it. A failed or interrupted request can leave the Google grant active. Legacy retry credentials remain encrypted until confirmation; explicitly forgetting the local retry credential requires a warning and does not claim remote revocation.

Advanced displays the installed package and public signing SHA-1 together with a configuration checklist. The Android OAuth client must match the installed package/certificate, the requested APIs must be enabled, and consent/audience restrictions must permit the account. The app does not infer that a project is in Testing or claim to verify Google Cloud Console configuration.

## Supported operations

Existing operation names/order remain compatible. Definitions contain concise, separate schemas; only connected connectors enter the tool registry.

- Gmail: query search with continuation tokens, message/label/thread reads, draft list/read/create/replace/send, replies with `threadId`, `In-Reply-To` and `References`, and attachment download.
- Gmail outgoing MIME supports UTF-8 subjects, RFC header folding and binary attachments from existing opaque current-chat attachment references. Draft send reviews complete bounded content and sends the frozen raw message, not a later edit. `messages.send` receives a Message with `raw` at its root; draft methods retain the `message` container.
- Drive: paginated search with parent/MIME/shared-drive filters, file metadata and capabilities, inline text, binary download, allowed Docs/Sheets/Slides exports, file creation/update and folder creation. Uploads use `/upload/drive/v3/files` with multipart POST/PATCH. Shared-drive flags are explicit.
- Drive writes use `drive.file`; they do not request full Drive write access. Existing targets/parents must be accessible to this app and permit the operation. No delete, sharing/permission changes, move, automatic execution or installation operations are added.

## Transfers and approval

Downloads become immutable native attachments in the invoking main conversation, using the existing Download/Share UI. Binary data and local paths are not returned to the model. Upload inputs are `attachment:<id>` or `delivered:<id>` owned by that conversation; arbitrary filesystem paths, other chats and delegated/background attachment access are rejected. The exact file bytes, MIME, filename, destination and SHA-256 are captured before approval and are not re-read after approval.

- General binary download bound: 8 MiB.
- Gmail attachment download: 5 MiB; outgoing attachments: 4 MiB combined and the complete double-base64 JSON request must remain within 8 MiB.
- Drive multipart uploads: 5 MiB including framing. Legacy inline text retains its 1 MiB input bound and 12 KiB UTF-8 preview.
- Metadata responses, MIME nesting/parts, row counts, strings and cursors are bounded. Truncation is explicit. Google Sheets CSV/TSV export includes the first sheet only.

New write operations require approval. Existing Gmail create/send Allow policy remains subject to its recipient checks and existing approval controls; attaching a file does not grant new account permissions. External content, headers, filenames, labels and document text remain untrusted data.

## Failure and concurrency semantics

Transport validates HTTPS hosts/service paths, refuses redirects and caller-supplied authorization headers, caps transfer sizes and disconnects active I/O on cancellation. One explicit 401 can refresh a token once. A 403 returns a safe permission/quota/access reason without a refresh loop. Only GET requests have bounded transient/network retries with jitter and Retry-After. Writes are never blindly retried after an ambiguous transport or server outcome.

Before any Gmail/Drive mutation, a private persistent journal reserves a deterministic intent digest. It stores no message/file content or credentials. Verified success and definitive rejection clear the digest; timeout, cancellation after dispatch, 5xx, malformed success or process interruption retain it. The bounded journal fails closed rather than discarding unresolved actions. Identical uncertain attempts remain blocked across reconnect/account changes. Manual reconciliation in Gmail/Drive is required; there is no automatic reset/replay command.

Drive update checks capabilities and version again immediately before upload and sends a strong `If-Match` condition when an ETag is available. Without one, version preflight is not atomic. Gmail draft replacement also has a race between revision preflight and PUT; the API does not provide a documented atomic draft revision condition. The approval describes full replacement, and sending an existing draft uses the exact reviewed raw content.

## Validation boundary

Hermetic Android/JVM tests exercise actual request contracts and native storage/UI paths with fake accounts/transports. They do not prove Google Cloud configuration, real consent, mailbox access, delivery, shared-drive policy or physical Android lifecycle behavior. Real acceptance requires the final original-signed APK and explicit authorization for any account read/write or new persistent grant. No live Google accounts were used during implementation.

## References and implementation provenance

The implementation adapts API contracts and design patterns rather than copying an external connector codebase or importing a desktop/server OAuth flow. No new third-party implementation source was copied.

- [Android authorization](https://developer.android.com/identity/authorization)
- [AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
- [Gmail messages.send](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send)
- [Gmail drafts](https://developers.google.com/workspace/gmail/api/guides/drafts)
- [Gmail threads](https://developers.google.com/workspace/gmail/api/guides/threads)
- [Gmail attachments](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages.attachments/get)
- [Drive uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- [Drive file search](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list)
- [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Drive export formats](https://developers.google.com/workspace/drive/api/guides/ref-export-formats)

Research also compared the official Google API Java client and Google Workspace CLI (Apache-2.0) and Google Workspace MCP (MIT). Their useful principles are narrow capability schemas, incremental authorization, correct REST contracts, explicit error categories and bounded transport; their desktop/server implementations were not transplanted into Android.
