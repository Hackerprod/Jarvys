# Local APK factory, SDK 2 (schema/protocol 1)

## What it does

Coding can turn a reviewed HTML/CSS/JavaScript project into a separate Android APK with its own application ID, name, icon, version and selected native capabilities. The HTML describes the requested product; the offline notebook included with the runtime is an acceptance example, not a fixed application generator.

The native Activity and AndroidX WebKit bridge are compiled into DEX once when Jarvys is built. Each new app reuses those exact DEX bytes. Jarvys transforms the binary manifest and the resource package name with a bounded, typed parser, replaces the launcher PNG and website assets, writes an aligned ZIP, and can sign it after explicit approval. It never runs Gradle, AAPT, a shell, a downloaded compiler, or x86 emulation for an individual app.

This is the established hybrid-app pattern used by projects such as [Cordova](https://cordova.apache.org/docs/en/latest/guide/overview/) and [Capacitor](https://capacitorjs.com/docs). The template adds a deliberately bounded local factory and a restricted native API; it does not invent a new app architecture or promise arbitrary native functionality from JavaScript.

## A. Architecture and ARM64 viability

The per-app path is Java/Kotlin running inside Jarvys on Android. It contains no native executable or ABI-specific library, so Ubuntu PRoot is not a prerequisite. The generated apps likewise contain no native `.so` libraries. Both Jarvys flavors can use this native factory on Android 8/API 26 or later, which supplies bounded no-follow directory enumeration. Generated applications support Android 7/API 24 or later.

A separate, optional Ubuntu 24.04 toolchain is available from official Ubuntu packages: `aapt` 1:14~beta1-2build3 includes both aapt and aapt2; `zipalign` 1:10.0.0+r36-1ubuntu2 has ARM64 builds; `apksigner` 31.0.2-1ubuntu1 is Java. See [aapt](https://packages.ubuntu.com/noble/aapt), [ARM64 file list](https://packages.ubuntu.com/noble/arm64/aapt/filelist), [zipalign](https://packages.ubuntu.com/noble/zipalign), and [apksigner](https://packages.ubuntu.com/noble/apksigner). Those packages are not installed automatically. Android/Termux bionic executables must not be presented as Ubuntu/glibc binaries.

The host can prove the portable algorithm, inspect its Android bytecode and decode output independently. A physical Android ARM64 run, WebView behavior, hardware Keystore provider and installation/update lifecycle remain separate acceptance checks. An x86 host result is never recorded as a phone test.

## B. Runtime and bridge

- Minimum Android API 24; target API 35; compile API 36.
- Absolute Activity: `com.jarvys.factory.runtime.FactoryActivity`.
- Actual application identity is obtained from `Context.getPackageName()`. No generated `BuildConfig.APPLICATION_ID`, user app resource ID or shared provider authority is embedded in native behavior.
- One launcher icon resource is retained at its compiled ID. The resource table package name changes; the runtime does not hardcode that numeric ID. No content provider is installed.
- Trusted local origin: `https://app.jarvys.invalid`. WebViewCompat's message listener is registered for that exact origin, and every request checks both origin and main-frame status.
- Remote navigation, file/content access, network loading, frames, workers, popup windows, arbitrary intents, shell commands, reflection and native JavaScript interfaces are disabled. Android 12+ cloud/device-transfer rules explicitly exclude app data; export remains a user-controlled operation.
- The API accepts bounded, typed JSON requests through a Promise-based SDK. Unsupported methods, malformed arguments, unselected capabilities and excessive requests fail explicitly.
- HTML must load JavaScript from local files. The CSP does not allow inline JavaScript or eval.

### Window and safe-area contract (UX35)

The runtime keeps the system status/navigation bars visible and uses a native NoActionBar theme.
It owns one safe-area container shared by the WebView and native error screen. This container
reserves the union of system bars, display cutouts and the keyboard once, using the maximum
inset on each edge. Handled inset types are forwarded as zeroes to the WebView, including when
the keyboard closes; they are not passed through unchanged or replaced with a fully consumed
notification. Unhandled gesture metadata remains available. This avoids a second CSS/native
safe-area subtraction and stale keyboard padding. Apps should use the available WebView viewport
normally and keep form fields scrollable, without adding fixed Android status-bar heights.

The safe-area surface follows the device's light/dark setting with explicit contrasting system
bar icons; it is independent of arbitrary page artwork. API 24/25 retain a black navigation bar
because dark navigation icons are unavailable there. No immersive mode, new Android permissions,
page-specific rules or untrusted JavaScript control of the native window are added. Keyboard,
rotation, cutout and real WebView behavior still require physical acceptance; host-injected
insets and native test captures do not constitute Chromium or phone evidence.

Window fixes ship in the compiled template. An APK previously generated by Jarvys retains its
old runtime until rebuilt and signed as an update with the same application ID and existing key,
and a larger version code. Updating Jarvys alone does not modify already generated applications.

The runtime implements `storage`, `export`, `share`, `clipboard`, `haptics`, `device`, `documents`, `photos`, `audio` and `browser`. Documents adds temporary binary handles through a compatible installed Jarvys host; it does not add general filesystem access. `runtime.info` returns non-sensitive API/capability metadata without granting any capability. Every functional method checks the generated app's declared capability before executing it. Storage is private to that installed app. Export uses the Android document picker, sharing uses a system chooser, and clipboard writes require a native confirmation dialog.

This offline template declares **zero Android permissions**. Its JavaScript cannot request camera, microphone, contacts, location, Internet, notifications, package installation or broad filesystem access. Adding any such API requires a reviewed native template update, appropriate per-app manifest selection, current Android permission checks and a new acceptance pass. Listing a desired method in JavaScript cannot add native code that is absent from DEX.

### Closed capability and manifest contract (UX42 F0a-1)

Pure Java `:factory-contract` owns ten capabilities and twenty-one wire methods (including ungated, non-sensitive `runtime.info`). FactorySpec, FactoryConfig, bridge validation, dispatch and introspection share this catalog; native validation and handlers remain explicit switches. v67 adds SDK 2 document methods while schema and bridge protocol remain 1. All 1,024 capability subsets retain zero Android permissions and the same launcher component. `documents` or `browser` contributes the same deduplicated exact package visibility queries for `com.jarvys.agent` and `com.jarvys.agent.recoverytest`; it adds no generated-app permission, provider, service, receiver or Activity. This remains a bounded extension, not completion of Runtime2 or the broader selective-manifest roadmap.

`ManifestPlan` is an immutable, closed expected tree with exact names, namespaces, Android attribute resource IDs, types and values. It pins API 24/35/compile 36, the launcher, theme, backup/cleartext policy and compiled icon/backup references. Project JSON cannot supply XML, nodes or arbitrary manifest attributes. F0a-2 adds an immutable compiled construction vocabulary and deterministic typed AXML encoder, including bounded repeated permissions/features/private components/filters/package and intent queries/metadata. Apart from the two exact broker package queries, these extra nodes remain host construction fixtures. Other contributions and project-defined nodes remain rejected. See [the construction scope and gates](../recovery/ux42-f0a2/README.md).

`ManifestAudit` is a read-only AXML decoder with no transformer helpers. TemplateApk compares the actual decoded tree, explicit parent indices and attributes to the plan before and after generation. A typed encoder now generates the output AXML; the independent parser enforces the same closed production ceiling. Ambiguous repeated-path lookups fail rather than selecting the first node. It rejects unknown/missing/duplicate production nodes and attributes, invalid namespaces, resource IDs, raw/typed disagreements, unsupported types, ambiguous element names and truncation. Resource bindings resolve by compiled type/name, retaining an unqualified XML fallback and the nodpi launcher; the backup XML must exclude exactly the existing nine domains for both cloud backup and device transfer. Template hash authentication remains mandatory. The trusted AAPT template may contain a palette-optimized PNG; generated icons retain the normalized RGB/RGBA validation.

Every canonical, contiguous `classes*.dex` entry is inventoried by name and SHA-256, header-checked and retained byte for byte. The receipt records the inventory without changing schema v1. Signing rechecks the exact project/hash/version receipt, compares the decoded manifest to its plan, and verifies that every ZIP payload entry is unchanged after v2/v3 signing. Approval uses these verified permissions, exported components, features/queries/hosts and DEX inventory; it grants no Android runtime permission and does not install anything.

F0a-3 derives immutable `resourceBindings` and `componentDex` evidence from the actual APK. The complete resource-table envelope now checks package/pool boundaries, type specifications and chunk counts, configuration and entry alignment, nonoverlapping entry spans, and unambiguous type/name/ID identities. Required icon and backup files retain their exact type/name/configuration/content checks. Unrelated complex resource values receive structural checks, not full Android resource-loader semantic validation. See [the binding scope](../recovery/ux42-f0a3/README.md).

DEX binding checks verify SHA-1/Adler32 integrity, bounded metadata/tables/strings, unique class definitions across the complete DEX set, and public concrete definitions with the expected superclass and public no-argument constructor for both the launcher and AndroidX component factory. Descriptors present only as references are insufficient. This is neither authentication nor a full bytecode verifier: authenticated template hashes, receipts, complete byte preservation and independent host audits remain mandatory.

Signing approval now compares effective capabilities and manifest scope with an optional bounded snapshot of the last signed APK, atomically recorded alongside its application ID, certificate, version and hash. It distinguishes first signing, unchanged scope, additions/removals and unknown legacy/missing/invalid baseline. Comparison is to the last signed artifact, including publication-unconfirmed versions, never an assertion about the installed or published app. The additive schema-v1 identity evidence does not rotate keys or reset version history; records without it remain readable with explicit uncertainty. The new-key fingerprint is unavailable until the approved key-creation step.

Previously completed v1 receipts remain usable. Receipt-less or foreign artifacts are still rejected. Older receipts may use the exact published pre-UX35 manifest profile, whose sole difference is absence of `windowSoftInputMode`; approval discloses the old window behavior. New receipts require the current closed profile. Unsupported older layouts get a rebuild instruction without replacing the receipt or signing identity. Rebuilds preserve the app ID/key and use a higher version when updating an already signed release. Reconstructed historical v35 sources and packager are host compatibility evidence, not recovery of a user's device data.

## C. Packaging and signing

### Input files

Create these inside the Coding project's existing scope:

- `factory.json`: exact keys `schemaVersion` (1), `appId`, `name`, `versionCode`, `versionName`, `capabilities`, `webDir`, `icon`.
- The directory named by `webDir`, containing `index.html`, separate scripts and styles, and local assets. Up to 128 files, 1 MiB each and 8 MiB total, with bounded directory depth and 256 total directory entries. Hidden paths, symlinks, special files and parent traversal are rejected.
- An icon file: PNG (48–1024 pixels per side, at most 1 MiB), or bounded geometric JSON authored with text tools.

Application IDs use 2–16 lowercase dot-separated segments, at most 127 characters. Android and Jarvys identities are reserved. App labels are bounded to 80 printable characters. Versions are explicit; sign an update only with a larger version code and the same existing key.

Vector icon schema: `schemaVersion: 1`, an opaque `background` color in `#RRGGBB`, and 1–32 `shapes`. Each shape uses a `fill` color and either a `circle` (`cx`, `cy`, `r`), `rect` (`x`, `y`, `width`, `height`) or `polygon` (`points`, 3–32 coordinate pairs). Coordinates and shapes must fit a 192×192 canvas. This is drawing data, not an SVG or scripting engine. PNG rendering can vary across Android graphics implementations; exact-byte reproducibility requires the same renderer as well as the same inputs and template.

### Real tool actions

1. In built-in Coding, load `com.jarvys.apk-factory` using `read_skill` and inspect the real `apk_factory` tool.
2. `apk_factory` with `action: inspect` reports the template hash, actual capability ceiling and project version.
3. `action: build`, `spec_path`, `output_path`, `expected_scope_version` snapshots files, checks the template hash, creates a deterministic aligned unsigned APK and returns its SHA-256. The output parent directory must already exist; existing files are never replaced.
4. `action: sign`, `input_path`, `expected_sha256`, `output_path`, `expected_scope_version` accepts only an exact, completed factory artifact from this same project. It requests explicit in-app approval, signs and independently verifies using AOSP apksig.

The principal only discovers that Coding has this workflow. Custom bots, Android-use and nested delegates cannot inherit the factory tool or its full skill. Disabling the skill or changing the active bot policy revokes availability; bundled instructions do not authorize actions by themselves.

Unsigned archives include a public provenance asset with the template SHA-256, input source hashes and rendered-icon hash. Private operation receipts bind artifacts to the durable project identity. DEX bytes are preserved exactly. ZIP names, lengths, CRCs, compression, resource chunks and manifest types are validated; stored entries are aligned before signing. Source and destination hashes are verified, cancellation is honored, and incomplete exclusive writes retain recovery evidence instead of being replayed.

### Signing identity and limitations

Existing schema-v1 identities retain their distinct non-exportable AndroidKeyStore key. They cannot be exported or converted, even if their key is missing. Missing keys, mismatches, interrupted creation and unsupported records fail closed. Signing a new app still offers explicit creation of a non-exportable key; cancel that approval and use **Settings → Device → Factory identities** first to choose recoverable signing instead. No tool, bridge, project file or prompt accepts passphrases or private keys.

For a new recoverable identity, the native secure screen obtains an explicit file destination and a user-entered, confirmed passphrase. It generates a portable RSA identity only after approval, saves and reads back an authenticated encrypted backup, and then commits the local identity. The local PKCS#8 copy is encrypted with a distinct per-app AndroidKeyStore AES-GCM wrapping key in no-backup app storage. This wrapper is not the recovery backup. The versioned portable container uses PBKDF2-HMAC-SHA256 (600,000 iterations, random salt) and AES-256-GCM with authenticated format parameters and random nonce. It includes the exact certificate, appId, private key and known version/hash evidence. A strong, unique passphrase is essential; the 12-character minimum alone does not guarantee strength. Losing the passphrase or the only usable backup may permanently prevent updates.

Export and import use explicit system document selection, bounded data and separate approval. A chosen cloud document provider may store the encrypted file remotely; Jarvys does not upload it independently. Import authenticates the container, verifies certificate/key matching and requires an exact existing certificate match; it never replaces a legacy or conflicting identity. An interrupted new commit pins the certificate and known version before local protection is created; its matching backup can repair it. Existing records are replaced atomically only after encryption succeeds. The shared signing lock and record digest invalidate stale approvals and prevent concurrent restore/sign version rollback. Local and backup version floors merge by maximum, with conflicting known APK evidence rejected.

Every import marks the latest release history **unknown**, even if the backup decrypts successfully. Signing stays blocked until the user reviews available APKs/receipts and explicitly confirms a version floor at least as high as all locally known versions. Raising that floor discards stale APK/scope associations. A user-declared floor is disclosed on signing and is not independent proof of the latest release. A backup cannot reveal releases made after its export, nor recover their project receipts. Signing still requires an exact project build receipt and a version higher than the saved floor. The signed version/scope is reserved before publication, including publication-uncertain outcomes.

The screen prevents screenshots, saved-state and autofill of passphrases, clears its own sensitive buffers on cancellation, and rejects stale/backgrounded approvals before beginning a durable action. JVM/IME/provider copies cannot be guaranteed erased. A save already committed before backgrounding may remain; inspect status instead of assuming cancellation rolled it back. A successful file write is not proof of restoration. Real independent-device restoration, matching-key update installation and preservation of app data remain physical acceptance gates. Backup of signing keys is separate from project export and generated-app data backup. The main Jarvys signing key is never used for generated apps.

Signing creates a local artifact. It does not install, publish, upload, grant Android permissions or accept a store agreement. Those actions require their own authorization. A verified APK signature does not certify device compatibility or production hardening.

## D. Coding skill

`app/src/main/assets/skills/com.jarvys.apk-factory/SKILL.md` is below the existing 16 KiB full-content budget. It is selected only for the immutable built-in Coding profile. It contains reusable design guidance, exact spec and SDK contracts, icon construction, packaging, signing, verification and recovery rules. No large factory prompt is injected into the principal's context.

## E. Acceptance gates

Host acceptance covers two distinct IDs and an update, labels/icons/resource-package identities, exact DEX preservation, deterministic unsigned bytes, no unexpected permissions, signing verification using isolated test fixtures, archive attacks, bridge policy/argument failures, storage isolation semantics, source scope/leases, output collisions, cancellation, approval denial/revocation and real skill-to-tool routing.

Physical acceptance still requires a supported Android ARM64 device with a current WebView: generate and sign two apps; install both with explicit approval; write different notes; kill/relaunch each; export via SAF; sign an increased-version update with the first app's retained key; install that update and confirm data survives. Also test denial/cancellation, background/rotation, oversized inputs, uninstall/key-loss messaging and obsolete WebView. Do not infer any of those results from package inspection or JVM tests.

Each completed pending item or correction is backed up and handed off for its separately authorized signing and delivery; completing the entire queue is not a delivery prerequisite. Development builds remain unsigned until that handoff. Detailed measured results and remaining limitations are recorded with the repository's validation evidence and root `Pending.md`.

## References and licenses

- [AOSP binary resource structures](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h)
- [Android WebView native-bridge risks](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)
- [AndroidX WebKit](https://developer.android.com/jetpack/androidx/releases/webkit)
- [Android backup and transfer rules](https://developer.android.com/identity/data/autobackup)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [AOSP apksig](https://android.googlesource.com/platform/tools/apksig/)

The binary transformer is original bounded code informed by the published resource format; it is not a copied general-purpose APK editor. AndroidX and apksig license notices are retained with the sources and generated runtime assets.

## F0c: functional preview and bounded shared-core tests

The Coding-only `apk_factory` actions `preview`, `preview_status` and `test` require exactly `input_path`, `expected_sha256` and `expected_scope_version` in addition to `action`. They accept a completed same-project build receipt and exact verified APK, with the currently bundled template. Old-template builds need rebuilding; neither preview nor test signs or installs anything. The snapshot binds durable project identity, scope version, build identity, source-manifest digest, template/APK digests, appId and version. Assets are copied from the verified APK, not read from mutable project paths while rendering. Existing website/file/count limits bound the snapshot; a single active preview replaces its predecessor.

`preview` requests a private native Activity using a process-local one-shot token. Its permanent native header identifies the APK and functional/simulated mode, with Reset test data and Close. Native storage is an isolated bounded RAM backend; resetting discards it and starts the same immutable snapshot. Sharing, clipboard, haptics and document export use explicit simulation adapters: no system chooser, clipboard write, vibration, document write or permission request occurs. `documents.*` instead rejects `UNAVAILABLE`, without fake handles or a real picker. Simulated effect results are `{simulated:true, performed:false, mode:"preview", operation:...}` rather than a claim of a completed external effect. Runtime/device info distinguish declared appId from the actual Jarvys host package. Installed apps retain mandatory package/config binding and their actual adapters.

Both preview and generated apps use `:factory-runtime-core` for validators, request dispatch, asset/CSP/network policy, rate/pending limits, reply generation and lifecycle cancellation. The generic HTML workspace preview remains unprivileged and has no Factory bridge. Preview requires WebView message-listener and isolated-profile support, failing closed when unavailable. It uses a separate provider profile with cookies disabled without modifying Jarvys's default profile or global debugging/cookie settings. Provider profiles may use disk; normal cleanup and recovery cleanup are best effort, not secure erasure. Closing, backgrounding, recreation or process death ends the interactive session. Do not infer isolation of the installed app's UID, permissions, providers or persistence from a harness running inside Jarvys. Generated apps have no INTERNET permission; the preview inherits Jarvys's INTERNET permission, so CSP/interception/network-load settings are not proof that every Chromium subsystem emits no traffic.

`preview_status` revalidates the exact artifact and current caller scope before returning the retained process-local bounded observation summary. It distinguishes launch request, native screen creation, runtime request/failure, provider page callbacks, handler outcomes and closure/revocation. A page-finished callback is not a visual or JavaScript assertion. The native UI is screenshot-protected; no automatic screenshots, console strings, request arguments, storage values or arbitrary error messages are collected. Counts are aggregated over the fixed operation/outcome vocabulary and saturate safely. A missing receipt after process death is unavailable evidence, not a successful run.

`test` executes fixed synthetic requests against the actual shared validators and dispatcher with isolated RAM storage and simulated effects. It checks catalog method paths (including undeclared-capability rejection and explicit documents unavailability), storage isolation and origin/subframe rejection, returning each assertion actually observed. It does not evaluate project JavaScript, render a browser, execute the APK's DEX, install an app, test hardware, grant permissions, or establish absence of network traffic. Project workflow testing still requires explicit UI/browser/device evidence. `preview` returning `launch_requested` alone proves none of those outcomes.

The originating active Coding token's cancellation, scope/version change, profile/skill revocation, replacement and native Close invalidate preview operations. Normal completed Coding work does not itself clear its standalone token. A global stop of already completed work is not a new cancellation guarantee; the native session closes when backgrounded. Queued stale operations cannot be replayed into a new generation; an already-started installed-app external effect may finish and is never silently retried. Installation is separately authorized through the workflow below.

## F0c: separately authorized installation

`install`, `install_status` and `install_cancel` take exactly `input_path`, `expected_sha256` and `expected_scope_version`. They require a completed signed same-project receipt, with immutable app/version, APK SHA-256, certificate SHA-256 and receipt digest. Native Full installation verifies signed bytes with apksig and checks manifest/DEX bindings; project identity, scope, receipt and source artifact are revalidated after review and the immutable private snapshot is reverified before commit. Signing never supplies install approval.

Full alone declares `REQUEST_INSTALL_PACKAGES`. Android's per-source permission must have been enabled by the user before a new install approval; Jarvys neither grants it nor opens settings automatically. Before querying permission, Full persists the automation guard. Denied, failed-query or unsupported configurations open distinct protected native review states without creating a session. These survive manual backgrounding/restart; granting permission never resumes them. The user must close native review and request a fresh install. Play returns an explicit unavailable result and leaves the artifact available in the project. Generated apps retain zero permissions. No claim of Google Play eligibility or approval is made. Managed-owner and privileged-installer modes are rejected; API 31+ explicitly requires system user action, and older supported versions use the ordinary unprivileged installer path.

`install` opens a private screenshot-protected native review screen. It discloses app identity, version, exact hashes and replacement/data risks. Only a human button stages and commits a PackageInstaller session; a second native human action opens Android's returned consent Intent. No receiver starts UI in the background. Jarvys's own native device automation guard blocks observations and actions across review and external consent. A durable interaction latch restores that boundary at process startup and survives unknown outcomes. It is released only through foreground protected native recovery after the session has ended or been abandoned. A screen entered during an already-active automated action cannot approve anything.

A single atomic no-backup journal records the latest session. Session ID is saved before commit; `committing` is written before Binder. A crash or uncertain call is never replayed. Pending-system Intents remain process-local and are not reconstructed after restart. Callback delivery uses an explicit private mutable PendingIntent with a fixed unique URI nonce and checked session/state. Only `STATUS_SUCCESS` establishes session success. Rejection/abort, policy blocking, other errors, pre-commit cancellation and post-commit unknown cancellation are distinct states. Android's aborted status does not reliably distinguish all user denials from other abort causes. Closing the system UI, a disappeared session or an installed package/version do not establish success. Cancellation cannot promise rollback.

Status/cancel bind the durable receipt even if APK bytes are no longer readable; changed project scope or inaccessible receipts require **Settings → Factory installations** recovery. Background/recreation revokes uncommitted authority. A malformed journal remains protected instead of being discarded. Explicit native recovery can cancel up to 16 validated Jarvys-owned installer sessions (not installed apps), with foreground checks and a required empty final query, then atomically preserve an unknown-outcome record. Query, cancellation or write failure retains protection. A separate native Close rechecks no owned sessions remain. No signing identity or project artifact is reset. Crash-created uncommitted orphan sessions can never commit themselves; real-device lifecycle, OEM system UI, source permission denial, update/data preservation and recovery remain acceptance gates. Synthetic tests do not install anything, change settings or use real signing keys.

References: [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller), [session commit](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session), [required user action](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)), and [Google Play permission policy](https://support.google.com/googleplay/android-developer/answer/12085295).

The automation boundary covers the Factory interaction, including its manual permission handoff. It is not a global prohibition on Android-use navigating every OS security setting outside that interaction.

## v67: temporary binary documents

Declare `documents` in factory.json and use the Promise SDK contract in [RUNTIME.md](apk-runtime/docs/RUNTIME.md#temporary-binary-documents-v67). The factory writes build-pinned `documentBroker` package/certificate metadata into runtime-owned factory-app.json; the project cannot choose or replace that trust anchor. A compatible Jarvys host must remain installed. Both known host packages installed together blocks access.

Jarvys's exported native `FactoryDocumentActivity` verifies the caller's actual installed package, unique UID, certificate, version and exact APK hash against the latest signed identity/scope evidence. Unknown, restored-but-unresolved or mismatched evidence fails closed. Signing a newer release makes the previously installed release unavailable for documents until that exact newer APK is installed; signing alone does not update it. Human-only native review opens Android's picker, then a separate **Use this document** action grants temporary access. Jarvys automation stays blocked across this interaction. Generated apps add only the two exact package queries, no permissions or components; the broker belongs to Jarvys.

Handles carry no path/URI and cannot be persisted or restored. Reads/writes are sequential, canonical-base64 chunks of at most 32 KiB, 16 MiB per handle and 32 MiB cumulative per page/session, with four admission slots and five-minute expiry. Cancel/close do not replenish the cumulative budget. Backgrounding, page reset and rotation revoke authority; installed selection is effectively one at a time. Selecting the next document closes the previous one and may return BUSY across runtime instances while provider cleanup remains pending.

Cancellation cannot roll back provider work. Create may leave an empty or partial file even if no handle is returned; a user-selected cloud provider may transfer data remotely. `close_requested` and `providerCommitConfirmed:false` do not certify flush, durable commit or upload. At exact quota, close rather than reading an extra byte to prove EOF. Interrupted-picker recovery requires the human to close the old picker/task and acknowledge it in **Settings → Factory documents**. That is user-reported closure, not OS proof; outcome stays unknown and no grant is restored.

v67 host test and delivery results are recorded in recovery/factory-documents. Physical acceptance remains pending. The bounded v68 file-sharing extension is described below; the other F1/F2/F3 families remain closed. Required device checks include exact-latest identity rejection, both-host ambiguity, picker denial/cancel, Use this document, background/rotation/reset, slow/cloud providers, quota boundaries, partial output and unknown-outcome recovery. Host tests and preview never establish those results.


## v68: bounded binary file sharing

`share.file({handle,filename,mimeType})` requires both `documents` and `share`, an untouched
read handle and strict concrete metadata. The source handle is consumed into a complete 1-byte
to 8 MiB immutable snapshot with charged EOF proof and existing cumulative quotas. Native
Binder sends sequential chunks of at most 32 KiB to the pinned, uniquely identified host; exact
length/hash/EOF validation precedes publication. One process-wide transfer admission and a
five-minute lifetime bound authority. No caller URI/path or persistent grant is introduced.

The host rechecks exact latest signed caller identity and both capabilities, then stages one
private snapshot. A separate human-only sharing coordinator uses shared document/share admission
and its own durable automation latch, restored before automation starts. Host-only FileProvider
`${applicationId}.factory.files` exposes only `cache/factory-file-shares/`, with an exact registered
URI, monotonic expiry, read-only operations and process-death fail-closed behavior. Generated
manifest permissions/providers are unchanged. See [the complete API and lifecycle contract](apk-runtime/docs/RUNTIME.md#binary-file-sharing-v68).

Only a native human action opens the system chooser. Read-only URI grants never include write,
prefix or persistable authority. Chooser callbacks do not release automation protection: the
human must close the chooser/recipient task and acknowledge uncertainty in native closure or
Settings recovery. No outcome is reported as delivery; `deliveryConfirmed` is always false.
Deletion/revocation cannot recall already opened descriptors, recipient copies or uploads.
Preview remains unavailable for file sharing. Existing text sharing and SAF behavior are preserved.

Implementation and synthetic tests do not prove device Binder IPC, chooser grants, recipient
access or revocation. The v68 three-APK/source/test/lint/signing gates are recorded separately
in recovery/factory-files; no actual user file or third-party sharing is part of host validation.

Design references: [Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider),
[narrow provider paths](https://developer.android.com/privacy-and-security/risks/file-providers),
[Binder transaction limits](https://developer.android.com/reference/android/os/TransactionTooLargeException),
and [URI revocation](https://developer.android.com/reference/android/content/Context#revokeUriPermission(android.net.Uri,%20int)).

## v69: bounded photo selection and full streaming capture

Declare both `photos` and `documents`. `photos.pick({})` opens a uniquely resolved system
photo picker on API 33+, with a system SAF image picker fallback; `photos.capture({})` opens
a uniquely resolved system camera with an exact randomized one-use write-only output URI.
The pipe-only provider has no file path or storage roots. It rejects other UIDs, URI variants,
repeat opens and modes other than `w`; capture identity is pinned and checked before open.
Cameras requiring seekable descriptors or reopening output are unsupported. There is no
silent thumbnail fallback. Generated APK permissions/components remain unchanged.

Both flows use a separate protected native review and **Use this photo** action, the same
latest signed APK/certificate/receipt checks, shared interaction admission and a durable
native recovery latch. Interrupted work cannot resume or restore image authority. Recovery
acknowledges the person's closure of external tasks, never independently verified OS closure.
No user photo, real camera or device is accessed during synthetic host validation.

Full encoded input is frozen and bounded to 8 MiB before validation. JPEG/PNG only, at most
4096 pixels per side and 12 megapixels; decoded allocation is bounded and validated. Camera
output requires both RESULT_OK and clean reliable-pipe EOF. The private camera pipe has one host-owned reader; timed polling enforces its deadline without
relying on API30-only descriptor controls on older Android. On API24–29, selected provider
descriptors must be regular files; shared streaming descriptors are explicitly unavailable.
API30+ uses public nonblocking controls, but a provider may share and change descriptor status
flags. Provider-open/read Binder, storage/proxy or kernel operations may be uninterruptible;
the single worker and cross-broker admission remain occupied until actual termination instead
of spawning retries. No forced provider-read timeout is promised. This is an availability limit.

Native Binder transports immutable chunks with length/hash/EOF checks into opaque temporary
document read handles. Snapshot acquisition is charged to the cumulative session quota;
subsequent document reads/sharing charge their normal quotas. Original image bytes and
metadata, potentially including location, are retained. Jarvys does not write to the gallery
or automatically send the photo; an external camera or cloud provider may retain/transfer
its own copy. Revocation cannot recall copies. Preview returns unavailable.

A compatible single Jarvys host and its exact latest signed receipt remain mandatory;
newly signing another release does not update the installed generated app. Host tests do
not establish OEM camera/picker/FD behavior, Binder interoperability, actual grant revocation,
installation/update or data preservation. Other F1/F2/F3 families remain closed.

References: [photo picker](https://developer.android.com/training/data-storage/shared/photo-picker),
[camera intent](https://developer.android.com/reference/android/provider/MediaStore#ACTION_IMAGE_CAPTURE),
[ContentProvider pipe modes](https://developer.android.com/reference/android/content/ContentProvider#openFile(android.net.Uri,%20java.lang.String)),
[reliable pipes](https://developer.android.com/reference/android/os/ParcelFileDescriptor#createReliablePipe()).


## v70: one-shot local PCM playback

Declare `audio` and `documents`; call `Jarvys.audio.play({handle})` with an untouched read
handle. This consumes a complete snapshot with charged quota and EOF proof, capped at 6 MiB
before materialization. The exact-latest signed installed APK, unique compatible host and
native-only Binder peers are authenticated. No URI, path, bytes or target enters this API.

Only canonical little-endian WAV is accepted: exactly 44 header bytes, RIFF/WAVE, fmt length
16, format 1, PCM16, one or two channels, 8,000–48,000 Hz, data immediately following the
header, positive whole frames, exact RIFF/data/byte-rate/block-align lengths, no trailing or
extra chunks. Frames must not exceed sampleRate × 30. Maximum valid data is 5,760,000 bytes.
No compressed codec, external playback/synthesis engine, network addition or new permission.
Earlier document selection may involve a cloud provider; local playback does not change that.

The host shows protected native review and an explicit **Play once** action. Durable
play_pending precedes native playback. Allocation and exact single STATIC PCM write run on
a bounded worker without playing; a final installed identity check precedes main-thread
foreground/generation/guard checks and immediate-granted audio focus. API26+ disables delayed
focus and opts into duck callbacks; API24–25 use the legacy focus API. Failure never retries.
The existing output and volume are used unchanged; nearby people/connected devices may hear
it. No route selection, looping, pause/resume, speed change or background service is added.

Focus loss, route/noisy interruption, background/window-focus loss, cancellation, Close and
the finite clip deadline stop/release playback. Native calls and Binder delivery have no hard
real-time guarantee. Separate authenticated control IPC remains alive after transfer EOF:
source cancellation latches irrevocably and requests host revocation on a bounded control
lane; oneway delivery never proves completed stop. Process death also revokes. Late callbacks
cannot authorize playback or another request. Restart restores only protected unknown outcome,
never bytes/player authority. Pending preparation and uncertain native cleanup keep admission
and protection until explicit native cleanup. The result `{playbackAttempted,audibilityConfirmed:false}`
never establishes that sound was heard; interrupted requests may instead reject as unavailable.

Preview returns UNAVAILABLE. Physical playback, routing, focus, Binder/death, lifecycle,
installation/update and data preservation remain separate unverified gates. Strict engine-pinned
TTS and recognition remain pending; no weaker routing or offline guarantee substitutes for them.
Other F1/F2/F3 families, UX34 phase gates and UX43-last remain unchanged.

Design references checked before implementation: [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[AudioTrack.Builder](https://developer.android.com/reference/android/media/AudioTrack.Builder),
[audio focus](https://developer.android.com/media/optimize/audio-focus),
[IBinder](https://developer.android.com/reference/android/os/IBinder),
[audio capture policy](https://developer.android.com/reference/android/media/AudioAttributes.Builder#setAllowedCapturePolicy(int)).


## HTTPS browser launch (UX42 F1, v71)

`runtime.info` preserves legacy `offline:true` with explicit `offlineScope:"embedded_webview"`
and `browserMayUseExternalNetwork:true`. Offline covers the packaged WebView, not external browsers
or document/cloud providers and their network behavior. Metadata grants no capability.

Declare only `browser` and call `Jarvys.browser.open({url})`. Documents are not required or
implicitly granted. SDK 2 and schema/protocol 1 remain unchanged. Factory-generated metadata
uses the existing `documentBroker` key for its build-owned Jarvys pin; either browser or documents
requires that pin. Both capabilities share exactly two deduplicated host package queries, with
zero generated permissions and no new generated components. A compatible single Jarvys host
must remain installed. The host verifies the source APK hash, certificate, version and exact
latest signed scope; signing a newer release requires updating the installed generated app.

URLs are immutable printable ASCII, 1–2,048 characters, exact lowercase `https`, strict lowercase
DNS labels (1–63 characters, at least two labels, at most 253 total), no user information, fragment,
trailing dot or local/numeric host spellings. Authority is exactly the host, optionally `:443`.
Backslashes, whitespace, controls and malformed percent escapes are rejected, including escaped
controls/space/DEL/backslash. Paths and queries remain exact, without decoding or normalization.
These rules do not detect arbitrary secrets in a path/query or prove a public/safe destination.
Do not put passwords, tokens or other secrets into a URL.

An authenticated native FLAG_SECURE review displays the full exact URL and canonical host without
links or ellipsis, only after human-only automation protection is acquired. Native controls reject
obscured touches, including partial obscuration on API29+. This does not control unrelated
accessibility services. The user chooses a
native browser component; JavaScript cannot choose packages, components, flags, extras or schemes.
Discovery uses a single host-only HTTPS/BROWSABLE query and public GET_RESOLVED_FILTER metadata.
Eligible components have a general HTTPS filter without authority/path/SSP/MIME/relative-filter
restrictions, are exported/enabled, and have no required component permission. Suspended, instant,
cross-profile and shared-UID candidates are rejected. Candidates and result counts are bounded.
The selected component, certificate, version, update time and UID are rechecked after source
verification on the bounded worker; main dispatch rechecks live foreground/focus/consent/cancellation.
There is no implicit intent fallback. Eligibility is not a guarantee of browser trustworthiness.

Native consent discloses that the exact URL, including path and query, goes to the selected browser
and website. Browser cookies, signed-in accounts, history/sync/network settings may apply. DNS,
redirects, resources and browser behavior can contact other destinations, downgrade to HTTP or open
other apps. Jarvys neither fetches data nor follows redirects; it cannot constrain the external
browser after launch. No generated INTERNET permission, remote WebView loading or network client
is added. HTTPS-only refers to the initial reviewed URL, not an enforced browser traffic policy.

The host persists `launch_pending` before its one-shot dispatch. Minimal durable journal contains
state/open/nonce only, never URL or browser data. Source cancellation/death, expiry and interrupted
preparation revoke authority. IPC cancellation delivery is not a hard realtime guarantee;
local checks and host expiry remain fail-safes. Five-minute native expiry is independent of JavaScript timeout.
The durable automation guard and global admission remain held until explicit human closure or
recovery; no automatic retries, relaunches or resurrection after restart. A worker still preparing
blocks recovery until it finishes. The user must manually close the external browser screen/task
and acknowledge that fact. This is user-reported closure, not OS verification. Back cannot make
that declaration. Cancellation cannot close the browser, undo site actions or retract transmitted data.

A successful receipt is `{launchRequested:boolean,pageLoadConfirmed:false}` after native closure.
`launchRequested` means only that `startActivity` returned without an exception. It proves neither
rendered browser, page loading, server receipt, data delivery nor successful website action.
Interrupted or uncertain recovery yields no successful receipt. Preview is unavailable and never
opens a browser. `documents.cancel` remains unrelated; page/runtime invalidation and the native
browser deadline revoke browser authority independently.

Validation uses synthetic URLs, Android package/IPC/lifecycle fixtures and mocked activity dispatch;
no real browser navigation or user-data transmission. API24+ platform behavior, browsers, installation,
updates and physical acceptance remain separate gates. Other typed maps/dialer/editors, TTS/voice,
F2/F3 and UX34 after phase 0 remain gated; UX43 stays last.

Official Android references checked October 10, 2026:
[web intents and visibility](https://developer.android.com/training/package-visibility/use-cases),
[IntentFilter public API](https://developer.android.com/reference/android/content/IntentFilter),
[resolved filters and cross-profile forwarding](https://developer.android.com/reference/android/content/pm/ResolveInfo),
[deep-link risks](https://developer.android.com/privacy-and-security/risks/unsafe-use-of-deeplinks).
Plain ACTION_VIEW can open deep-link apps. The hidden `handleAllWebDataURI` field is deliberately
unused; public filter inspection provides the documented restricted candidate policy.
