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

Physical acceptance remains required on Android 24 and Android 29+: descriptor path aliases and `/proc/self/fd` availability, MediaStore visibility, permission UI, read-only sharing, process recreation, and external viewers. Jarvys development APKs remain unsigned; final delivery uses the existing approved signing identity after each validated stage.

Platform references:
- [Android shared storage and MediaStore](https://developer.android.com/training/data-storage/shared/media)
- [Android system descriptor APIs](https://developer.android.com/reference/android/system/Os)

## HTML previews (UX22)

Valid delivered UTF-8 HTML pages expose a tappable full-card thumbnail with overlaid icon-only Download and Share. Tapping the thumbnail opens the saved page inside Jarvys; it never reruns the agent or reads a newer project version. APKs and binary files with an HTML-looking filename are not HTML previews. Existing valid HTML attachments can show their immutable single-file original, with a notice that linked resources were not saved.

`deliver_file` captures an immutable dependency bundle when both source and delivered filename identify HTML. `preview_workspace` without arguments retains the existing live ordinary-workspace `index.html` behavior. With an explicit `path`, including `/project/site/index.html`, it uses the same immutable delivery and preview contract and persists the native card. The tool result reports the actual `preview_available`, token, file count, bytes and capture warnings; a file can still be attached successfully when a preview is unavailable.

Capture follows bounded static references, never enumerates the repository, and never downloads external resources. Limits are 128 files, 1 MiB per file, 8 MiB total, 2,048 references and 16 levels of dependency/path depth. Supported assets include HTML, CSS, JavaScript modules, SVG, common raster images and fonts. Dynamic fetches, generated paths and runtime-discovered assets may be missing. The UI marks incomplete captures; it does not promise a complete application. Factory web sources can be previewed as ordinary untrusted HTML, without the generated APK runtime's native capability contract.

The stored manifest binds source provenance, entry path and each captured path/size/SHA-256. A dependency-only change produces a new artifact, leaving earlier cards unchanged. Reads accept only manifest members from the owning chat, reject symlinks/private zones/path escapes and verify saved bytes. Deleting, renaming or changing source project files does not alter a completed snapshot. Downloads continue to export the original delivered HTML file to OS Downloads; the preview bundle is private to Jarvys and is not silently uploaded, shared or exported.

Each preview uses an independent HTTPS `.invalid` origin. The WebView disallows file/content access, mixed content, external URL resource loads, external navigation, form submission, frames and workers. It installs no Android JavaScript bridge and grants no camera, microphone, location or filesystem capabilities. JavaScript and DOM storage remain available for existing interactive pages. These controls are not a proof that all JavaScript networking is disabled: stock WebView's WebRTC data-channel/ICE path is an inherited residual network-egress risk. No airtight offline isolation is claimed. Future hardening must preserve the interactive contract or explicitly disclose a changed capability.

The drawer's drag recognizer is disabled only while a preview is shown. Native WebView receives its original touch stream, including taps, scroll/fling, multi-pointer zoom and text selection. Its initial URL is loaded once; routine Compose updates do not reload internal navigation. A bounded Activity state cache keeps four recently opened previews (48 KiB native history per page, plus URL/scroll fallback), captures lifecycle state and releases destroyed WebViews. Restoring a native history is not a promise to preserve arbitrary JavaScript heap/form state after process death.

Host tests verify the real Activity/AndroidView dispatch path, native policies, scoped bytes, restored controls and state contracts. Robolectric does not run Chromium; smooth scrolling, real DOM behavior, pinch rendering, accessibility, soft keyboard and WebRTC policy require physical-device validation. No frame-rate or complete network-isolation claim follows from host tests.


## Static page thumbnails (UX28 / UX33)

The compact file card renders actual immutable HTML/CSS/image bytes locally into a bounded 3:2 image. It never substitutes generated artwork, a screenshot service, or a page-specific template. The card has no metadata header or static-caption footer. Its page pixels remain static with scripts disabled; removing the caption does not change the rendering policy. JavaScript-only pages, blank rendering, resource failures and unavailable Chromium use an honest placeholder that still opens a valid page. The interactive preview retains its existing JavaScript contract; opening it does not capture entered form data or later browsing into a thumbnail.

Only visible cards in a resumed Activity request thumbnails. One renderer runs at a time, with at most eight queued requests, a five-second visual-state deadline, a viewport no wider than 1536 pixels, and output no larger than 768×512 pixels. The renderer is attached offscreen, excluded from touch, accessibility and autofill, and destroyed on completion, cancellation or timeout. Decode, hash, validation, compression and disk IO stay off the UI thread; WebView creation, drawing and destruction stay on it. The deadline is a cancellation budget, not a guarantee against every Chromium/native failure.

The automatic renderer disables JavaScript, DOM storage, file/content access, geolocation and network fallback. It has a separate per-conversation/artifact origin, fail-closed immutable-member resource responses, no bridge, denied navigation/permissions, restrictive CSP, DNS-prefetch disabled and no downloads. It does not load external fonts, scripts or screenshot services. These source-level protections and host tests do not establish observed zero network traffic or actual pixels on a physical WebView; device acceptance remains required.

The derived PNG cache is separate from the immutable manifest and includes the session, artifact, validated content fingerprint, output dimensions and rendering policy. It admits at most 2 MiB encoded and 4 MiB decoded per image, 16 MiB/128 items on disk, and an 8 MiB retained bitmap lease budget. It revalidates transcript ownership and immutable resources, uses atomic private-file staging and bounded PNG validation, and rejects stale generations after deletion. A deleted chat cannot be recreated by a late thumbnail callback. Cache loss simply causes a new static render. No memory cache stores Activity or WebView references.

Download, Share, Cancel and saved-file Open continue to use the native file-transfer state machine. Icons retain localized labels with filenames and 48 dp hit areas. Other file types retain truthful file cards; APKs and binary HTML-looking files are never rendered as pages. An icon row does not publish a public page link.

UX33 uses one rounded outer border and a 3:2 edge-to-edge thumbnail. Download and Share sit over a bottom gradient; their 22 dp glyphs remain inside 40 dp visible circles with separate 48 dp touch targets and 64 dp center spacing. Busy controls preserve cancellation, disabled taps cannot open the preview beneath them, and saved Open remains available. Loading and unavailable text stays above the controls. Metadata resolution reserves the same card geometry before a valid preview appears. Filenames remain in accessible labels; other file types retain their file cards.
