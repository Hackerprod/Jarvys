# Native file delivery and Downloads

## User flow

The main chat's `deliver_file` tool attaches an existing relative workspace file or a shared Coding file at `/project/path`. It supports binary output, including factory APKs, without executing or installing them. Merely printing a path or a Markdown link is not delivery.

A successful tool result includes a stable artifact ID, sanitized filename, MIME type, byte size, and SHA-256. The chat shows a native file card or image preview. Copying finishes and conversation ownership is persisted before success is reported. The saved copy is independent of later workspace edits. Repeating unchanged content with the same filename reuses the artifact and card.

Download saves into the root system Downloads folder. Android 10+ uses MediaStore.Downloads without a per-file destination picker. Android 7–9 requires the user to grant the platform's external-storage permission; the manifest limits this permission to API 28. Denial remains a failure to download, with Share offered as an alternative. No all-files permission is requested.

Images, incoming attachments, and delivered files share the same asynchronous download controls. Cancelling or failing a download recovers the controls. Unsupported image previews retain access to the original file. Completion identifies the saved filename and offers Open. Open and Share are explicit user actions using read-only content URI grants; completion itself never opens an installer or executes a file.

## Boundaries and persistence

- Delivery is available only in the main chat, not delegated bots, proactive runs, or scheduled-task runs. It does not expand any agent's allowed tools or connector permissions.
- Source access is limited to ordinary files within the current conversation's legacy workspace or shared Coding root. App-private settings, credentials, memory and skill mounts, other chats, traversal, symlinks, and special files are unavailable through this operation.
- Sources are streamed with a 256 MiB bound and checked before/after copying. The opened descriptor is checked against its pinned inode and expected path. Failure to verify the descriptor fails closed. A second bounded checksum pass detects in-place source changes.
- Delivered copies and manifests live outside agent-editable mounts. Metadata and SHA-256 are checked before use. Interrupted private staging copies are removed on the next delivery; incomplete finalized records are never blindly overwritten.
- Typed delivered-file rows preserve UI ownership independently of chat compaction. P0's tool transcript retains the delivery result as model evidence. Reopening history never reruns delivery.
- The private chat-file provider accepts only exact transcript-owned IDs. It has no arbitrary-path route, write mode, exported directory, or cross-conversation fallback.

## Export transactions

DownloadStore accepts a scoped immutable-source key and stream opener, not an arbitrary URI. It rejects main-thread export, bounds stream length, and records a transaction before public storage changes. A pending MediaStore item becomes visible only after copying and closing complete. Legacy exports use hidden temporary filenames within Downloads, followed by a collision-safe final name.

Per-artifact durable receipts serialize duplicate taps and reconcile interrupted operations on retry. Filenames are sanitized and include a collision-resistant suffix. Cleanup removes only confirmed pending items. User-visible downloaded files are never removed because their receipt lags, or because the user edited, renamed, or moved them. An ambiguous or changed receipt fails safely rather than overwriting user data. Permission denials, full storage, provider failures, cancellation, and unavailable originals remain explicit failures.

## Verification limits

Host tests exercise real production stores, tools, timeline restoration and Activity controls with simulated provider/permission boundaries. Descriptor tests use an explicit Unix-identity kernel adapter because Robolectric's default fstat shadow does not model opened descriptors accurately. Those tests do not certify physical Android behavior.

Physical acceptance remains required on Android 24 and Android 29+: descriptor path aliases and `/proc/self/fd` availability, MediaStore visibility, permission UI, read-only sharing, process recreation, and external viewers. Jarvys development APKs remain unsigned; final delivery uses the existing approved signing identity after the full product queue.

Platform references:
- [Android shared storage and MediaStore](https://developer.android.com/training/data-storage/shared/media)
- [Android system descriptor APIs](https://developer.android.com/reference/android/system/Os)
