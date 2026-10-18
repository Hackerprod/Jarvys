# Local APK factory, SDK 2 (schema/protocol 1)

Current development source contract: 33 capabilities / 134 methods; SDK 2 and schema/protocol 1. No new APK release is implied.
Selected SQLite and string key-value backups are documented at the end of this guide. Implementation and host
validation do not establish physical device, provider durability or independent restore evidence.
Versioned technical sections retain their API/data scope; obsolete descriptions of saved operation
approval policies are removed. The v83/v84 backup contracts supersede older
no-import/export restrictions within explicit table/key selections only.

Current source additions are documented in [Native network, notifications and speech (v85)](#native-network-notifications-and-speech-v85) and [Factory app runtime follow-up](docs/factory-app-runtime.md). The directed checks and `FullDebug` host build for this batch passed; the global suite, generated-project JavaScript run on a device and release acceptance are not claimed. Historical versioned sections preserve API/data anchors. Their latest-only admission, obsolete saved-operation approval descriptions, old capability totals, blanket zero-permission/offline and pending TTS/network/notification statements do not describe the current code. No unimplemented Android family is implied.



## Current media and packaging development limits

Signed budgets preserve legacy defaults while allowing larger sources. `fileShare.sourceBytes` defaults to 8 MiB and accepts up to 4 GiB; transfer timeout and URI lease default to five minutes and accept up to 24 hours. `audio.sourceBytes` defaults to 6 MiB and accepts up to 4 GiB; duration defaults to 30 seconds and accepts up to 268435454 ms; setup timeout defaults to five minutes and accepts up to 24 hours. Document source/session capacity and remaining lease must also suffice. These are ceilings, not disk, memory, provider throughput or uninterrupted Android-lifetime guarantees.

Share/audio preparation streams into a private immutable unlinked file descriptor with 32 KiB working buffers. Small transfers retain the authenticated v1 wire format; larger transfers use v2 long lengths. The host streams canonical PCM16 WAV playback rather than allocating the entire clip. Mono/stereo 8–48 kHz remains the supported format. Very short clips use a bounded static path; longer clips use AudioTrack streaming. Cancellation/revocation cannot recall sound or bytes already exposed, and uncertain native cleanup retains ownership rather than permitting blind retry. After process death during snapshot preparation, a bounded private provisional cache file can remain until the next materialization validates and removes it before allocating another. Completed snapshots are unlinked; recovery receipts do not certify disk reclamation or erasure of every cache byte.

Before file-sharing or audio-player handoffs, authenticated source checks bind the installed caller and required capabilities. The host issues a fresh exact source-bound lease for those host-mediated effects after preparation; it does not consult a saved operation policy or open an approval screen. Final effects remain live-checked. The optional descriptor extension requires a compatible host for these new clients; existing clients retain their prior authenticated protocol behavior.

Optional [adaptive launcher icons](../docs/FACTORY_ADAPTIVE_ICONS.md) use compiled Android resources and preserve the authored foreground canvas by default. [Packaging budgets](../docs/FACTORY_PACKAGING_BUDGETS.md) permit up to 512 website files, 16 MiB per file and 32 MiB total, subject to complete-archive entry/expanded/APK bounds and actual-size advisory heap admission. The precompiled DEX architecture remains unchanged. Historical fixed media/source ceilings below describe earlier releases and are superseded by this section for current development source.

These changes are undergoing targeted checks. No rebuilt-template, final APK, sound, external share or physical-device acceptance is asserted.

## Generic hardware, widgets and finite jobs in development

The current source adds NFC NDEF, Bluetooth LE central, RemoteViews widgets and finite HTTP jobs through the shared SDK and packaged capability budgets. App-owned effects use in-process, page-bound authorization; document brokers, external handoffs and backup commits retain authenticated host checks. These are bounded generic Android building blocks. They do not activate arbitrary Java classes, native libraries, a background JavaScript engine or app-specific workflows. Source integration and focused tests are not a rebuilt-template, release or physical-device acceptance claim.

### Runtime, protocol and rebuild contract

The generated runtime keeps schema 1 and bridge protocol 1. App-owned calls use in-process packaged-capability and page checks, without a Jarvys Binder lease. Document and file brokers, chooser/picker handoffs, clipboard writes, backups and host-mediated selectors retain pinned host package, certificate and UID checks, Binder authentication and Android URI grants. If the host is absent or its identity cannot be verified, the operation returns `SOURCE_UNAVAILABLE` and tells the user Jarvys must be installed and active. Existing generated APKs embed the runtime DEX and must be rebuilt and re-signed to receive these fixes.

- `nfc.status/read/write/cancel`: foreground reader mode; exact typed NDEF records (`tnf`, `typeHex`, `idHex`, `payloadHex`). Read and write are separate operations. Optional formatting must be explicit; cancellation after writing starts may leave changed contents. Normal NFC manifest permission, optional hardware feature, no invented runtime permission prompt.
- `bluetooth.status/requestPermissions/scan/poll/stopScan/connect/services/read/write/requestMtu/subscribe/unsubscribe/disconnect/cancel`: generic GATT central with opaque page-owned handles, paginated services/attributes, serialized operations and bounded event queues. Explicit signed `bluetooth:{version:1,scanPrivacy:"full"|"neverForLocation"}` selects real Android permission semantics; neverForLocation can filter beacon results. Actual Android pairing/authentication UI may occur. No-response writes follow the callback-confirmed MTU; initial MTU23 allows20 data bytes. Poll retains original live grants through accepted delivery. Raw UUID/profile payloads are developer data, never predefined business commands.
- `widgets.availability/list/read/requestPin/update/remove/pollActions/acknowledgeAction`: generic compiled RemoteViews row/column/text/image/progress/button trees. Effects share `widgets.manage`; native launcher pinning remains an Android flow. Remove clears owned configuration, not launcher placement. Repeatable actions create native events per accepted Intent delivery; optional oncePerGeneration deduplicates. Events do not execute app code or grant external effects. Unknown updates are never blindly replayed.
- `jobs.enqueueHttp/status/cancel/readResult/forget/resume`: durable one-shot typed HTTP requests through JobScheduler, with network/charging/delay constraints. Requires jobs+network. If background prerequisites or caller/source authorization are unavailable, dispatch waits; foreground resume explicitly chooses execution now and must acknowledge an uncertain prior dispatch where applicable. Existing NetworkRequest timeout bounds apply; no background document handles or arbitrary scripts are persisted. Results and admission status are distinct from successful remote delivery.

All four use signed `resourceBudgets` sections and explicit bounded native lifetime/cleanup. NFC/tag capability, Bluetooth stack, launcher support and Android background scheduling can independently be unavailable. Current foreground capture/location/recording APIs do not become continuous background APIs merely because jobs exists. Local model inference remains contingent on a chosen engine/model/license/ABI and measured device budget; remote model HTTP protocols compose network and scoped secrets without a provider-specific runtime.

### Streaming document HTTP

`network.upload` and `network.download` require network+documents. They transfer sequential native handles in bounded blocks with long offsets, explicit maximum bytes, deadlines and cancellation. Upload binds the selected handle/length, not a promise that a mutable provider is an immutable content snapshot; automatic redirect replay is refused. Download resolves redirects before writing. Reaching the explicit byte cap without EOF confirmation reports a partial limit result. Cancellation cannot undo bytes already sent or written, and uncertain provider positions retire the handle. Document metadata reports its actual signed capacity and remaining native lease.

## Generic app services in development

These additions are generic composable APIs, with no ping-specific runtime behavior. Shared SDK wrappers are present, but the combined production compilation, final suite, exact rebuilt DEX audit and physical acceptance must be checked separately.

### Per-app encrypted secrets

Declare `secrets`; `secrets.request` additionally requires `network`. Methods: put, capture, get, list, describe, remove, reset, request. Put accepts an explicit value; capture is optional native input; get deliberately returns plaintext. These app-owned effects use in-process, page-bound operation lifetimes and the declared capability; AndroidKeyStore and app-private storage remain local to this APK. A scope binds one exact HTTPS origin, uppercase HTTP methods, a non-transport header and prefix. `request({id,request:<ordinary network options>})` uses the same local authorization path as other app-owned effects, with no separate network.request authorization. Injection is connection-local and rechecks scope on every redirect, rejects collisions and HTTPS downgrade, and never places plaintext into the authorization disclosure or a reusable redirect map. A server may reflect a credential in a response, so there is no universal never-JavaScript promise.

AndroidKeyStore protects the app's AES-GCM key; the encrypted vault is in private no-backup storage. Invalidated/lost keys or corrupt storage are explicit errors, not silent key replacement. Reset is an explicit configured operation. Signed `resourceBudgets.secrets` fields are entries (default32, maximum256), valueBytes (8192/32768), vaultBytes (524288/4194304). Vault capacity must cover valueBytes+8192. Native buffers/input are cleared when released; Java/JavaScript Strings cannot be reliably wiped. A cancelled storage/network operation may already have committed or transmitted, and is never blindly replayed.

### Incoming Android sharing

Declare `incomingShare` plus `documents` and `incomingShare:{mimeTypes:[...],multiple:boolean}`. MIME patterns are sorted unique lowercase values (1–32); exact SEND and optional SEND_MULTIPLE filters are generated separately. No general file/storage permission is added. list returns bounded metadata; take({id,attachmentIndices?}) uses an in-process, page-bound lease over the immutable app-local envelope and selection; discard forgets remaining local data. Android grants and content URI grammar are checked before opening and each read. All selected document slots are reserved before the first open; cleanup uncertainty retains capacity.

Initial sharing transfers once to the first trusted page; a new Intent binds to the current page. Original Intents/extras are not persisted. Envelopes are process-local and cannot survive Activity/process replacement; incoming MIME/labels are untrusted data. Text is delivered once and attachments become ordinary read handles. Commit precedes bridge posting, so a lost reply never requeues already-consumed content. Signed incomingShare budgets bound envelopes, attachments, text and queued bytes. Existing Android grants are not globally revoked while another live owner may use them.

### Notification actions and durable scheduling

`notifications.show` accepts optional actions [{id,title,data?}] and uses the same in-process, page-bound authorization as other app-owned notification effects. Android controls POST_NOTIFICATIONS and delivery. Actions open this app and enqueue inert typed events; native code does not execute their payload. `pollActions` repeats the oldest event until `ackAction({eventId})`. Immediate action receipts include generationId, managed by generationStatus/forgetGeneration. Plain notifications need no alarm component or durable generation row.

Opt into `notifications:{scheduling:true,exactAlarms:false}` for schedule/resumeScheduled/scheduleStatus/cancelScheduled/forgetScheduled. schedule accepts scheduleId, atEpochMs, expiresAtEpochMs, precision, allowWhileIdle and a notification. Resume reacquires the original complete notifications.schedule operation. There is no arbitrary app callback or always-running JavaScript service. Private SQLite commits immutable generations before alarm setup and reserves dispatch before notify; a crash after dispatch reservation yields delivery_unknown and no blind replay. Data ownership is app+signer; same-signer updates retain data but revalidate current capabilities, signed budgets and policy. Unknown outcomes can be explicitly forgotten with acknowledgeDeliveryUnknown, never by pretending delivery was absent.

Scheduling adds two private default-process receivers and RECEIVE_BOOT_COMPLETED. exactAlarms:true additionally declares SCHEDULE_EXACT_ALARM; requestExactAlarmAccess opens genuine Android settings. Inexact is the default; explicit exact denial never silently downgrades. Android permission/channel state, Doze, process lifecycle and OEM behavior still govern delivery. Accepted means notify returned, not displayed/read or exact timing.

Background widgets, finite HTTP jobs and scheduled notification dispatch derive identity and signed resource budgets from the installed APK's Android-verified signer, package version and packaged configuration. Foreground scheduling and job enqueue use the same packaged capabilities and budgets with a page-bound local lifetime. Android still controls job/alarm delivery and runtime permissions; missing platform prerequisites defer or reject work. Cleanup/status/action acknowledgment use app-owned identity and do not need Jarvys. Signed notification budgets govern schedules, inbox events, retained deduplication/tombstones and ledger storage; explicit terminal cleanup makes these capacity limits reusable.

## What it does

Coding can turn a reviewed HTML/CSS/JavaScript project into a separate Android APK with its own application ID, name, icon and version. In `factory.json`, omit `capabilities` to select every compiled capability; an explicit array keeps only that subset. Omission includes the generic widget provider and receiver, so the app appears as a widget source in launchers that expose the widget picker; pinning and placement remain launcher-controlled. The HTML describes the requested product; the offline notebook included with the runtime is an acceptance example, not a fixed application generator.

The native Activity and AndroidX WebKit bridge are compiled into DEX once when Jarvys is built. Each new app reuses those exact DEX bytes. Jarvys transforms the binary manifest and the resource package name with a bounded, typed parser, replaces the launcher PNG and website assets, writes an aligned ZIP, and signs the exact completed same-project artifact with its per-app AndroidKeyStore identity when requested. It never runs Gradle, AAPT, a shell, a downloaded compiler, or x86 emulation for an individual app.

This is the established hybrid-app pattern used by projects such as [Cordova](https://cordova.apache.org/docs/en/latest/guide/overview/) and [Capacitor](https://capacitorjs.com/docs). The template adds a deliberately bounded local factory and a restricted native API; it does not invent a new app architecture or promise arbitrary native functionality from JavaScript.

## Native operation authority

There is no saved per-operation ALLOW/ASK/DENY policy, Factory permissions editor or Jarvys approval screen for generated-app operations, signing or installation. The owner authorizes APK Factory actions through the requested Coding task. `sign` verifies the completed same-project artifact and signs it with that app's AndroidKeyStore identity. `install` verifies the exact signed artifact and submits it to Android PackageInstaller; Android may require its per-source install setting or a system confirmation.

Host-mediated native calls authenticate the actual UID, package, certificate, version, APK hash and retained exact capability scope through the pinned host. A nonce/request/source-bound Binder lease authenticates both endpoints and remains live-checked before broker, document, backup and host-mediated external-activity effects. This is technical caller and scope authorization, not a user approval prompt. Revocation, process death or a stale source ends that lease. App-owned effects instead check the packaged capability and current page in process, then rely on Android's own grants and permissions. Read-only status does not grant host authority; no JavaScript flag, cached grant or Intent label substitutes for either boundary.

Generated apps still require declared capabilities. Android runtime permission prompts, document/photo/contact selectors, recipient choices, biometric prompts, widget pinning and PackageInstaller consent remain system-controlled when Android requires them. Completed effects and recipient copies cannot be recalled; uncertain outcomes are not automatically replayed. Signed-artifact history retains 64 recent exact receipts plus an older installed matching-certificate artifact when applicable. Older installed artifacts retain only their own exact scope; legacy compatibility is exact-latest only. Missing or damaged evidence fails closed.

New backup protocol v2 authenticates both Binder endpoints and checks its live lease before each export write and import commit. Legacy backup protocol v1 remains authenticated, but its final host authorization is the last enforceable checkpoint; older DEX has no later callback. Already-open file descriptors, completed writes, clipboard copies, installation commits, emitted speech, transmitted network bytes and recipient copies cannot be undone. New share-file opens recheck authority; an already-open recipient descriptor may remain usable. Never claim cancellation proves rollback or external task closure.

`share.text` and `export.text` use host-authenticated, page-bound capability leases before handing off to Android's chooser or document picker. Export writes only through the URI grant Android returns. Local storage, SQLite, haptics, device information, presentation, sensors, biometrics, notifications, speech, voice recognition, recording, location, steps, NFC, Bluetooth and foreground network effects use their packaged capability and Android-owned permission/grant rules. Clipboard preview stays simulated with performed:false and never writes the system clipboard. Network, notifications and speech still reject UNAVAILABLE in preview before effects. Byte/file/row/rate/deadline budgets are technical quotas, not operation permissions. Dispatch, chooser return and cleaned-up audio need no invented success attestation; unknown outcomes still require reconciliation and are never replayed automatically.
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

The safe-area surface follows the app's effective light/dark setting (system by default), with
explicit contrasting system bar icons; it is independent of arbitrary page artwork. API 24/25 retain a black navigation bar
because dark navigation icons are unavailable there. No immersive mode, new Android permissions,
page-specific rules or arbitrary JavaScript window control are added. The declared `presentation`
capability permits only typed, local theme/orientation choices; the single safe-area/IME owner
is unchanged. Keyboard, rotation, cutout and real WebView behavior still require physical acceptance; host-injected
insets and native test captures do not constitute Chromium or phone evidence.

Window fixes ship in the compiled template. An APK previously generated by Jarvys retains its
old runtime until rebuilt and signed as an update with the same application ID and existing key,
and a larger version code. Updating Jarvys alone does not modify already generated applications.

The runtime implements `storage`, `export`, `share`, `clipboard`, `haptics`, `device`, `documents`, `photos`, `audio`, `browser`, `maps`, `phone`, `email`, `sms`, `contacts`, `calendar`, `database`, `presentation`, `sensors`, `biometrics`, `network`, `notifications` and `speech`. Documents adds temporary binary handles through a compatible installed Jarvys host; it does not add general filesystem access. `runtime.info` returns non-sensitive API/capability metadata without granting any capability. Every functional method checks the generated app's declared capability before executing it. Storage is private to that installed app. Text export uses the Android document picker, sharing uses a system chooser, and clipboard writes in the new runtime use the authenticated host; preview is simulated with performed:false.

The compiled template declares **zero Android permissions**. Selected generated capabilities contribute only their exact catalog manifest scope. Omitting `capabilities` derives all catalog entries and can add `USE_BIOMETRIC`, `INTERNET` with cleartext enabled, `POST_NOTIFICATIONS`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `RECORD_AUDIO`, `ACTIVITY_RECOGNITION`, `HIGH_SAMPLING_RATE_SENSORS`, `NFC` plus its optional hardware feature, `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and the BLE feature, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, queries for `com.jarvys.agent` and `com.jarvys.agent.recoverytest`, and intent queries for `android.intent.action.TTS_SERVICE` and `android.speech.action.RECOGNIZE_SPEECH`. These declarations do not grant Android runtime permissions; the generated app still relies on user-controlled Android grants and system flows. The generated schema-1 `factory-app.json` records the effective explicit capability list; existing explicit subsets keep their scope. API24–29 biometrics remain unsupported. `notifications.requestPermission` requests the real Android grant where required; JavaScript cannot grant it. Other unimplemented Android APIs and arbitrary permissions remain unavailable. Listing a desired method cannot add absent DEX. WebView local-only configuration is not process isolation: `INTERNET` is process-wide, and TTS engines/providers/recipients may use their own network permissions.

### Closed capability and manifest contract (UX42 F0a-1)

Pure Java `:factory-contract` owns 29 capabilities and 99 wire methods (including ungated, non-sensitive `runtime.info`). FactorySpec, FactoryConfig, bridge validation, dispatch and introspection share the catalog; handlers remain explicit switches. SDK 2 and schema/bridge protocol 1 remain unchanged. Manifest scope is derived from the exact selected capabilities, not project-provided nodes. Profiles with none of `biometrics`, `network`, `notifications` contribute no Android permission; each selected member adds only its permission above. Broker-dependent families include documents, clipboard, export, share, browser, maps, phone, email, sms, contacts, calendar and the three new native families. They contribute the exact package queries `com.jarvys.agent` and `com.jarvys.agent.recoverytest`; speech additionally contributes `android.intent.action.TTS_SERVICE`. All generated profiles retain the same launcher and add no provider/service/receiver/Activity. These are source catalog rules, not exhaustive APK-build or device-validation results. Full Runtime2/arbitrary manifest support remains unimplemented.

`ManifestPlan` is an immutable, closed expected tree with exact names, namespaces, Android attribute resource IDs, types and values. It pins API 24/35/compile 36, the launcher, theme, backup/cleartext policy and compiled icon/backup references. Project JSON cannot supply XML, nodes or arbitrary manifest attributes. F0a-2 adds an immutable compiled construction vocabulary and deterministic typed AXML encoder, including bounded repeated permissions/features/private components/filters/package and intent queries/metadata. Apart from the exact broker package/TTS queries and selective `USE_BIOMETRIC`, `INTERNET` and `POST_NOTIFICATIONS` declarations above, these extra nodes remain host construction fixtures. Other contributions and project-defined nodes remain rejected. See [the construction scope and gates](../recovery/ux42-f0a2/README.md).

`ManifestAudit` is a read-only AXML decoder with no transformer helpers. TemplateApk compares the actual decoded tree, explicit parent indices and attributes to the plan before and after generation. A typed encoder now generates the output AXML; the independent parser enforces the same closed production ceiling. Ambiguous repeated-path lookups fail rather than selecting the first node. It rejects unknown/missing/duplicate production nodes and attributes, invalid namespaces, resource IDs, raw/typed disagreements, unsupported types, ambiguous element names and truncation. Resource bindings resolve by compiled type/name, retaining an unqualified XML fallback and the nodpi launcher; the backup XML must exclude exactly the existing nine domains for both cloud backup and device transfer. Template hash authentication remains mandatory. The trusted AAPT template may contain a palette-optimized PNG; generated icons retain the normalized RGB/RGBA validation.

Every canonical, contiguous `classes*.dex` entry is inventoried by name and SHA-256, header-checked and retained byte for byte. The receipt records the inventory without changing schema v1. Signing rechecks the exact project/hash/version receipt, compares the decoded manifest to its plan, and verifies that every ZIP payload entry is unchanged after v2/v3 signing. Approval uses these verified permissions, exported components, features/queries/hosts and DEX inventory; it grants no Android runtime permission and does not install anything.

F0a-3 derives immutable `resourceBindings` and `componentDex` evidence from the actual APK. The complete resource-table envelope now checks package/pool boundaries, type specifications and chunk counts, configuration and entry alignment, nonoverlapping entry spans, and unambiguous type/name/ID identities. Required icon and backup files retain their exact type/name/configuration/content checks. Unrelated complex resource values receive structural checks, not full Android resource-loader semantic validation. See [the binding scope](../recovery/ux42-f0a3/README.md).

DEX binding checks verify SHA-1/Adler32 integrity, bounded metadata/tables/strings, unique class definitions across the complete DEX set, and public concrete definitions with the expected superclass and public no-argument constructor for both the launcher and AndroidX component factory. Descriptors present only as references are insufficient. This is neither authentication nor a full bytecode verifier: authenticated template hashes, receipts, complete byte preservation and independent host audits remain mandatory.

Signing approval now compares effective capabilities and manifest scope with an optional bounded snapshot of the last signed APK, atomically recorded alongside its application ID, certificate, version and hash. It distinguishes first signing, unchanged scope, additions/removals and unknown legacy/missing/invalid baseline. Comparison is to the last signed artifact, including publication-unconfirmed versions, never an assertion about the installed or published app. The additive schema-v1 identity evidence does not rotate keys or reset version history; records without it remain readable with explicit uncertainty. The new-key fingerprint is unavailable until the approved key-creation step.

Previously completed v1 receipts remain usable. Receipt-less or foreign artifacts are still rejected. Older receipts may use the exact published pre-UX35 manifest profile, whose sole difference is absence of `windowSoftInputMode`; approval discloses the old window behavior. New receipts require the current closed profile. Unsupported older layouts get a rebuild instruction without replacing the receipt or signing identity. Rebuilds preserve the app ID/key and use a higher version when updating an already signed release. Reconstructed historical v35 sources and packager are host compatibility evidence, not recovery of a user's device data.

## C. Packaging and signing

### Input files

Create these inside the Coding project's existing scope:

- `factory.json`: required keys `schemaVersion` (1), `appId`, `name`, `versionCode`, `versionName`, `webDir`, `icon`; optional `capabilities` selects a subset (omitted means all compiled capabilities). Optional `dataBackup` selects bounded table/key backups below.

Omitting `capabilities` also derives the full catalog manifest: biometric, process-wide internet/cleartext, notification, coarse/fine location, microphone, activity/sensor, NFC and BLE declarations, boot/network state, package queries for Jarvys and TTS/speech intent queries. It adds schema-v1 defaults for the exact policies that require a config: incoming shares accept `*/*` and multiple items; Bluetooth uses `scanPrivacy: "full"`. These declarations do not grant Android runtime permissions. Use an explicit subset to narrow derived scope; Android grants remain under user control.
- The directory named by `webDir`, containing `index.html`, separate scripts and styles, and local assets. Up to 128 files, 1 MiB each and 8 MiB total, with bounded directory depth and 256 total directory entries. Hidden paths, symlinks, special files and parent traversal are rejected.
- An icon file: PNG (48–1024 pixels per side, at most 1 MiB), or bounded geometric JSON authored with text tools.

Application IDs use 2–16 lowercase dot-separated segments, at most 127 characters. Android and Jarvys identities are reserved. App labels are bounded to 80 printable characters. Versions are explicit; sign an update only with a larger version code and the same existing key.

Vector icon schema: `schemaVersion: 1`, an opaque `background` color in `#RRGGBB`, and 1–32 `shapes`. Each shape uses a `fill` color and either a `circle` (`cx`, `cy`, `r`), `rect` (`x`, `y`, `width`, `height`) or `polygon` (`points`, 3–32 coordinate pairs). Coordinates and shapes must fit a 192×192 canvas. This is drawing data, not an SVG or scripting engine. PNG rendering can vary across Android graphics implementations; exact-byte reproducibility requires the same renderer as well as the same inputs and template.

### Real tool actions

1. In built-in Coding, load `com.jarvys.apk-factory` using `read_skill` and inspect the real `apk_factory` tool.
2. `apk_factory` with `action: inspect` returns a compact readiness/capability summary, template hash and project version; it does not return full method or resource inventories.
3. `action: build`, `spec_path`, `output_path`, `expected_scope_version` snapshots files, checks the template hash, creates a deterministic aligned unsigned APK and returns its SHA-256. The output parent directory must already exist; existing files are never replaced.
4. `action: sign`, `input_path`, `expected_sha256`, `output_path`, `expected_scope_version` accepts only an exact, completed factory artifact from this same project. It signs with the per-app AndroidKeyStore identity and independently verifies using AOSP apksig.

The principal only discovers that Coding has this workflow. Custom bots, Android-use and nested delegates cannot inherit the factory tool or its full skill. Disabling the skill or changing the active bot policy revokes availability; bundled instructions do not authorize actions by themselves.

Unsigned archives include a public provenance asset with the template SHA-256, input source hashes and rendered-icon hash. Private operation receipts bind artifacts to the durable project identity. DEX bytes are preserved exactly. ZIP names, lengths, CRCs, compression, resource chunks and manifest types are validated; stored entries are aligned before signing. Source and destination hashes are verified, cancellation is honored, and incomplete exclusive writes retain recovery evidence instead of being replayed.

### Signing identity and limitations

Existing schema-v1 identities retain their distinct non-exportable AndroidKeyStore key. They cannot be exported or converted, even if their key is missing. Missing keys, mismatches, interrupted creation and unsupported records fail closed. First signing creates a non-exportable key unless a recoverable identity already exists. To choose recoverable signing, use **Settings → Device → Factory identities** before requesting the first signature. No tool, bridge, project file or prompt accepts passphrases or private keys.

For a new recoverable identity, the native secure screen obtains an explicit file destination and a user-entered, confirmed passphrase. It generates a portable RSA identity only after approval, saves and reads back an authenticated encrypted backup, and then commits the local identity. The local PKCS#8 copy is encrypted with a distinct per-app AndroidKeyStore AES-GCM wrapping key in no-backup app storage. This wrapper is not the recovery backup. The versioned portable container uses PBKDF2-HMAC-SHA256 (600,000 iterations, random salt) and AES-256-GCM with authenticated format parameters and random nonce. It includes the exact certificate, appId, private key and known version/hash evidence. A strong, unique passphrase is essential; the 12-character minimum alone does not guarantee strength. Losing the passphrase or the only usable backup may permanently prevent updates.

Export and import use explicit system document selection, bounded data and separate approval. A chosen cloud document provider may store the encrypted file remotely; Jarvys does not upload it independently. Import authenticates the container, verifies certificate/key matching and requires an exact existing certificate match; it never replaces a legacy or conflicting identity. An interrupted new commit pins the certificate and known version before local protection is created; its matching backup can repair it. Existing records are replaced atomically only after encryption succeeds. The shared signing lock and record digest invalidate stale approvals and prevent concurrent restore/sign version rollback. Local and backup version floors merge by maximum, with conflicting known APK evidence rejected.

Every import marks the latest release history **unknown**, even if the backup decrypts successfully. Signing stays blocked until the user reviews available APKs/receipts and explicitly confirms a version floor at least as high as all locally known versions. Raising that floor discards stale APK/scope associations. A user-declared floor is disclosed on signing and is not independent proof of the latest release. A backup cannot reveal releases made after its export, nor recover their project receipts. Signing still requires an exact project build receipt and a version higher than the saved floor. The signed version/scope is reserved before publication, including publication-uncertain outcomes.

The screen prevents screenshots, saved-state and autofill of passphrases, clears its own sensitive buffers on cancellation, and rejects stale/backgrounded approvals before beginning a durable action. JVM/IME/provider copies cannot be guaranteed erased. A save already committed before backgrounding may remain; inspect status instead of assuming cancellation rolled it back. A successful file write is not proof of restoration. Real independent-device restoration, matching-key update installation and preservation of app data remain physical acceptance gates. Backup of signing keys is separate from project export and generated-app data backup. The main Jarvys signing key is never used for generated apps.

Signing creates a local artifact. It does not install, publish, upload, grant Android permissions or accept a store agreement. Those actions require their own authorization. A verified APK signature does not certify device compatibility or production hardening.

## D. Coding skill

`app/src/main/assets/skills/com.jarvys.apk-factory/SKILL.md` is below the existing 16 KiB full-content budget. It is selected only for the immutable built-in Coding profile. It contains reusable design guidance, exact spec and SDK contracts, icon construction, packaging, signing, verification and recovery rules. No large factory prompt is injected into the principal's context.

## E. Acceptance gates

Host acceptance covers two distinct IDs and an update, labels/icons/resource-package identities, exact DEX preservation, deterministic unsigned bytes, no unexpected permissions, signing verification using isolated test fixtures, archive attacks, bridge capability/argument failures, storage isolation semantics, source scope/leases, output collisions, cancellation, lease revocation and real skill-to-tool routing.

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

The Coding-only `apk_factory` actions `preview`, `preview_status` and `test` require exactly `input_path`, `expected_sha256` and `expected_scope_version` in addition to `action`. They accept a completed same-project build receipt and exact verified APK, with the currently bundled template. Old-template builds need rebuilding; neither preview nor test signs or installs anything. Results return compact state/counts plus failed checks and relevant warnings; passing checks are still fully executed and omitted from the returned observations. The short evidence limit remains in each result. The snapshot binds durable project identity, scope version, build identity, source-manifest digest, template/APK digests, appId and version. Assets are copied from the verified APK, not read from mutable project paths while rendering. Existing website/file/count limits bound the snapshot; a single active preview replaces its predecessor.

`preview` requests a private native Activity using a process-local one-shot token. Its permanent native header identifies the APK and functional/simulated mode, with Reset test data and Close. Native storage is an isolated bounded RAM backend; resetting discards it and starts the same immutable snapshot. Sharing, clipboard, haptics and document export use explicit simulation adapters: no system chooser, clipboard write, vibration, document write or permission request occurs. `documents.*` instead rejects `UNAVAILABLE`, without fake handles or a real picker. All three `presentation.*` methods also reject `UNAVAILABLE`, without changing host settings. Simulated effect results are `{simulated:true, performed:false, mode:"preview", operation:...}` rather than a claim of a completed external effect. Runtime/device info distinguish declared appId from the actual Jarvys host package. Installed apps retain mandatory package/config binding and their actual adapters.

Both preview and generated apps use `:factory-runtime-core` for validators, request dispatch, asset/CSP/network policy, rate/pending limits, reply generation and lifecycle cancellation. The generic HTML workspace preview remains unprivileged and has no Factory bridge. Preview requires WebView message-listener and isolated-profile support, failing closed when unavailable. It uses a separate provider profile with cookies disabled without modifying Jarvys's default profile or global debugging/cookie settings. Provider profiles may use disk; normal cleanup and recovery cleanup are best effort, not secure erasure. Closing, backgrounding, recreation or process death ends the interactive session. Do not infer isolation of the installed app's UID, permissions, providers or persistence from a harness running inside Jarvys. Generated network profiles and the Jarvys preview process have INTERNET permission. CSP/interception/network-load settings are not proof that every Chromium subsystem or process path emits no traffic.

`preview_status` revalidates the exact artifact and current caller scope before returning the retained process-local bounded observation summary. It distinguishes launch request, native screen creation, runtime request/failure, provider page callbacks, handler outcomes and closure/revocation. It also retains the latest `INVALID_ARGUMENT` validator message and a saturating count for each known bridge method; messages name the invalid field and expected shape. A page-finished callback is not a visual or JavaScript assertion. The native UI is screenshot-protected; no automatic screenshots, console strings, request arguments, storage values or arbitrary error messages are collected. Counts are aggregated over the fixed operation/outcome vocabulary and saturate safely. A missing receipt after process death is unavailable evidence, not a successful run.

`test` executes fixed synthetic requests against the actual shared validators and dispatcher with isolated RAM storage and simulated effects. It checks catalog method paths (including undeclared-capability rejection and explicit documents unavailability), storage isolation and origin/subframe rejection, returning each assertion actually observed. It does not evaluate project JavaScript, render a browser, execute the APK's DEX, install an app, test hardware, grant permissions, or establish absence of network traffic. Project workflow testing still requires explicit UI/browser/device evidence. `preview` returning `launch_requested` alone proves none of those outcomes.

The originating active Coding token's cancellation, scope/version change, profile/skill revocation, replacement and native Close invalidate preview operations. Normal completed Coding work does not itself clear its standalone token. A global stop of already completed work is not a new cancellation guarantee; the native session closes when backgrounded. Queued stale operations cannot be replayed into a new generation; an already-started installed-app external effect may finish and is never silently retried. Installation is a separate `apk_factory` action that submits the exact signed artifact to Android PackageInstaller.

## F0c: Android installation

`install`, `install_status` and `install_cancel` take exactly `input_path`, `expected_sha256` and `expected_scope_version`. They require a completed signed same-project receipt, with immutable app/version, APK SHA-256, certificate SHA-256 and receipt digest. The Full host verifies signed bytes with apksig and checks manifest/DEX bindings; project identity, scope, receipt and source artifact are revalidated, and the immutable private snapshot is reverified before commit. Signing never requests installation. Jarvys adds no operation approval screen for installation.

Full alone declares `REQUEST_INSTALL_PACKAGES`. If Android's per-source install setting is unavailable, Jarvys reports that state and provides a link to the Android setting; Jarvys does not grant it. After the setting changes, issue a fresh install request. Play returns an explicit unavailable result and leaves the artifact in the project. Generated profiles never receive package-install permission. No claim of Google Play eligibility or approval is made. Managed-owner and privileged-installer modes are rejected; API 31+ requests `USER_ACTION_NOT_REQUIRED` where available, but Android still decides whether confirmation is required.

The native install activity displays exact app identity, version, hashes and current installation status, and submits the validated artifact to Android PackageInstaller. Android may return a consent Intent, which opens from the live foreground flow; no receiver starts Android UI in the background. Durable state preserves unknown outcomes and never replays a commit. Only an authenticated `STATUS_SUCCESS` callback establishes session success. Android rejection/abort, other errors, pre-commit cancellation and post-commit unknown cancellation remain distinct outcomes. Android's aborted status does not reliably distinguish every user denial from other abort causes. Closing system UI, a disappeared session or an installed package/version do not establish success; cancellation cannot promise rollback.

A single atomic no-backup journal records the latest session. Session ID is saved before commit; `committing` is written before Binder. Pending-system Intents remain process-local and are not reconstructed after restart. Status/cancel bind the durable receipt even if APK bytes are no longer readable; changed project scope or inaccessible receipts require **Settings → Factory installations** recovery. Background/recreation revokes uncommitted authority. A malformed journal remains unavailable for new installation until recovery. Explicit native recovery can cancel up to 16 validated Jarvys-owned installer sessions (not installed apps), with foreground checks and a required empty final query, then atomically preserve an unknown-outcome record. Query, cancellation or write failure retains the unresolved journal state. A separate native Close rechecks no owned sessions remain. No signing identity or project artifact is reset. Crash-created uncommitted orphan sessions can never commit themselves; real-device lifecycle, OEM system UI, source permission denial, update/data preservation and recovery remain acceptance gates. Synthetic tests do not install anything, change settings or use real signing keys.

References: [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller), [session commit](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session), [required user action](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)), and [Google Play permission policy](https://support.google.com/googleplay/android-developer/answer/12085295).

Automation protection belongs to active PackageInstaller interaction and genuine Android confirmation. There is no configured Jarvys operation review; permission errors and old uncertainty remain local to installation and do not lock unrelated work.
## v67: temporary binary documents

Declare `documents` in factory.json and use the Promise SDK contract in [RUNTIME.md](apk-runtime/docs/RUNTIME.md#temporary-binary-documents-v67). The factory writes build-pinned `documentBroker` package/certificate metadata into runtime-owned factory-app.json; the project cannot choose or replace that trust anchor. A compatible Jarvys host must remain installed. Both known host packages installed together blocks access.

Jarvys's exported native `FactoryDocumentActivity` verifies the caller's actual installed package, unique UID, certificate, version and exact APK hash against the retained signed identity/scope evidence. Unknown, restored-but-unresolved or mismatched evidence fails closed. Signing a newer release does not update the previously installed app. Android presents the document picker; a validated selection returns temporary access without a second **Use this document** confirmation. Foreground authority is rechecked before delivery; already-open descriptors cannot be recalled retroactively. Generated apps add only the two exact package queries, no permissions or components; the broker belongs to Jarvys.

Handles carry no path/URI and cannot be persisted or restored. Reads/writes are sequential, canonical-base64 chunks of at most 32 KiB, 16 MiB per handle and 32 MiB cumulative per page/session, with four admission slots and five-minute expiry. Cancel/close do not replenish the cumulative budget. Backgrounding, page reset and rotation revoke authority; installed selection is effectively one at a time. Selecting the next document closes the previous one and may return BUSY across runtime instances while provider cleanup remains pending.

Cancellation cannot roll back provider work. Create may leave an empty or partial file even if no handle is returned; a user-selected cloud provider may transfer data remotely. `close_requested` and `providerCommitConfirmed:false` do not certify flush, durable commit or upload. At exact quota, close rather than reading an extra byte to prove EOF. Interrupted-picker recovery in **Settings → Factory documents** acknowledges uncertainty without proving task closure or restoring authority. A fresh authenticated request may retire historical uncertainty before acquiring new authority; it never replays the old request.

v67 host test and delivery results are recorded in recovery/factory-documents. Physical acceptance remains pending. The bounded v68 file-sharing extension is described below; the other F1/F2/F3 families remain closed. Required device checks include retained-artifact identity rejection, both-host ambiguity, picker denial/cancel, live-lease revocation/no duplicate Use confirmation, background/rotation/reset, slow/cloud providers, quota boundaries, partial output and unknown-outcome recovery. Host tests and preview never establish those results.


## v68: bounded binary file sharing

`share.file({handle,filename,mimeType})` requires both `documents` and `share`, an untouched
read handle and strict concrete metadata. The source handle is consumed into a complete 1-byte
to 8 MiB immutable snapshot with charged EOF proof and existing cumulative quotas. Native
Binder sends sequential chunks of at most 32 KiB to the pinned, uniquely identified host; exact
length/hash/EOF validation precedes publication. One process-wide transfer admission and a
five-minute lifetime bound authority. No caller URI/path or persistent grant is introduced.

The host rechecks exact signed caller identity and both capabilities, then stages one
private snapshot. The sharing coordinator checks live authenticated authority and shared document/share
admission, then opens Android's system chooser. Host-only FileProvider
`${applicationId}.factory.files` exposes only `cache/factory-file-shares/`, with an exact registered
URI, monotonic expiry, read-only operations and process-death fail-closed behavior. Generated
manifest permissions/providers are unchanged. See [the complete API and lifecycle contract](apk-runtime/docs/RUNTIME.md#binary-file-sharing-v68).

A valid live host lease opens the system chooser. Read-only URI grants never include write,
prefix or persistable authority. Chooser return delivers a dispatch-only receipt and detaches the
bounded recipient lease, without a second closure acknowledgment. New provider opens recheck
live authority; Settings recovery handles uncertain state without claiming recipient closure. No outcome is reported as delivery; `deliveryConfirmed` is always false.
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

Both flows check photos.pick/capture capability and retained signed APK/certificate/receipt
identity with shared admission. Android presents the picker or camera flow. Validated image delivery has no second **Use this photo** confirmation.
A durable native recovery latch retains uncertainty. Interrupted work cannot resume or restore image authority. Recovery
reconciles uncertainty, never proves external-task closure or restores old authority.
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

A compatible single Jarvys host and its retained exact signed receipt remain mandatory;
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
before materialization. The retained exact signed installed APK, unique compatible host and
native-only Binder peers are authenticated. No URI, path, bytes or target enters this API.

Only canonical little-endian WAV is accepted: exactly 44 header bytes, RIFF/WAVE, fmt length
16, format 1, PCM16, one or two channels, 8,000–48,000 Hz, data immediately following the
header, positive whole frames, exact RIFF/data/byte-rate/block-align lengths, no trailing or
extra chunks. Frames must not exceed sampleRate × 30. Maximum valid data is 5,760,000 bytes.
No compressed codec, external playback/synthesis engine, network addition or new permission.
Earlier document selection may involve a cloud provider; local playback does not change that.

The host validates the bytes and checks live authenticated authority before playback. Durable
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
until native cleanup is confirmed. The operation then completes without a separate audibility attestation;
unknown cleanup remains blocked. The result `{playbackAttempted,audibilityConfirmed:false}`
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
must remain installed. The host verifies the source APK hash, certificate, version and retained
exact signed scope; signing a newer release does not change the installed artifact's scope.

URLs are immutable printable ASCII, 1–2,048 characters, exact lowercase `https`, strict lowercase
DNS labels (1–63 characters, at least two labels, at most 253 total), no user information, fragment,
trailing dot or local/numeric host spellings. Authority is exactly the host, optionally `:443`.
Backslashes, whitespace, controls and malformed percent escapes are rejected, including escaped
controls/space/DEL/backslash. Paths and queries remain exact, without decoding or normalization.
These rules do not detect arbitrary secrets in a path/query or prove a public/safe destination.
Do not put passwords, tokens or other secrets into a URL.

An authenticated FLAG_SECURE disclosure view displays the exact URL/host and rejects obscured touches, including partial obscuration on API29+. Dispatch uses a verified Android default browser; if no eligible default exists, Android presents recipient choice.
These controls do not control unrelated accessibility services. The selected target is a
native browser component; JavaScript cannot choose packages, components, flags, extras or schemes.
Discovery uses a single host-only HTTPS/BROWSABLE query and public GET_RESOLVED_FILTER metadata.
Eligible components have a general HTTPS filter without authority/path/SSP/MIME/relative-filter
restrictions, are exported/enabled, and have no required component permission. Suspended, instant,
cross-profile and shared-UID candidates are rejected. Candidates and result counts are bounded.
The selected component, certificate, version, update time and UID are rechecked after source
verification on the bounded worker; main dispatch rechecks live foreground/focus/consent/cancellation.
There is no implicit intent fallback. Eligibility is not a guarantee of browser trustworthiness.

The native disclosure explains that the exact URL, including path and query, goes to the selected browser
and website. Browser cookies, signed-in accounts, history/sync/network settings may apply. DNS,
redirects, resources and browser behavior can contact other destinations, downgrade to HTTP or open
other apps. Jarvys neither fetches data nor follows redirects; it cannot constrain the external
browser after launch. No generated INTERNET permission, remote WebView loading or network client
is added. HTTPS-only refers to the initial reviewed URL, not an enforced browser traffic policy.

The host persists `launch_pending` before its one-shot dispatch. Minimal durable journal contains
state/open/nonce only, never URL or browser data. Source cancellation/death, expiry and interrupted
preparation revoke authority. IPC cancellation delivery is not a hard realtime guarantee;
local checks and host expiry remain fail-safes. Five-minute native expiry is independent of JavaScript timeout.
After dispatch, the broker closes its request without asking for an external-task closure
attestation. No automatic retries, relaunches or resurrection after restart. A worker still preparing
blocks recovery until it finishes. Unknown recovery remains distinct from successful dispatch and
never verifies OS closure. Cancellation cannot close the browser, undo site actions or retract data.

A successful receipt is `{launchRequested:boolean,pageLoadConfirmed:false}` after dispatch and broker completion.
`launchRequested` means only that `startActivity` returned without an exception. It proves neither
rendered browser, page loading, server receipt, data delivery nor successful website action.
Interrupted or uncertain recovery yields no successful receipt. Preview is unavailable and never
opens a browser. `documents.cancel` remains unrelated; page/runtime invalidation and the native
browser deadline revoke browser authority independently.

Validation uses synthetic URLs, Android package/IPC/lifecycle fixtures and mocked activity dispatch;
no real browser navigation or user-data transmission. API24+ platform behavior, browsers, installation,
updates and physical acceptance remain separate gates. Other typed editors, TTS/voice,
F2/F3 and UX34 after phase 0 remain gated; UX43 stays last.

Official Android references checked October 10, 2026:
[web intents and visibility](https://developer.android.com/training/package-visibility/use-cases),
[IntentFilter public API](https://developer.android.com/reference/android/content/IntentFilter),
[resolved filters and cross-profile forwarding](https://developer.android.com/reference/android/content/pm/ResolveInfo),
[deep-link risks](https://developer.android.com/privacy-and-security/risks/unsafe-use-of-deeplinks).
Plain ACTION_VIEW can open deep-link apps. The hidden `handleAllWebDataURI` field is deliberately
unused; public filter inspection provides the documented restricted candidate policy.


## Typed maps and dialer launch (UX42 F1, v72)

Declare `maps` for `Jarvys.maps.open({latitude,longitude})` or `Jarvys.maps.open({query})`;
declare `phone` for `Jarvys.phone.dial({number})`. These are independently gated launch-only
capabilities. Neither grants documents, browser, device location, contact access or calling.
SDK 2/schema/protocol 1 remain unchanged. Like browser, either requires the factory-owned
`documentBroker` pin and exactly the two existing host package queries. All generated APKs
remain permissionless with the same launcher. A compatible exact-pinned Jarvys host must be
installed; source identity must match its retained signed APK hash/certificate/version/scope.
No new host permission is added. The host already has unrelated legacy permissions; the new
paths do not use them. Only narrow host ACTION_VIEW/geo and ACTION_DIAL/tel visibility queries
and a separate authenticated native review Activity are added.

The argument object has exactly one of these shapes; mixed, missing and extra fields fail:
- Coordinates: `{latitude,longitude}` with actual JSON numbers, finite latitude in [-90,90]
  and longitude in [-180,180]. Numeric strings fail. Native construction uses locale-independent
  plain decimal values and canonical zero; it shows those same values for review.
- Query: `{query}` with a nonblank String, 1–256 Unicode code points and at most 1,024 UTF-8
  bytes. Surrogates must be paired. ISO controls, format/bidi controls and line/paragraph
  separators fail. Accepted text is preserved and percent-encoded once, including literal
  %, &, + and #, into `geo:0,0?q=...`. No supplied URI is accepted.
- Phone: `{number}` matching `\+?[0-9]{1,15}`. ASCII digits only; optional leading + and
  leading zeroes are preserved. No trimming, country inference or number-validity guarantee.
  USSD, *, #, pauses, extensions, separators, arbitrary schemes and URI parameters fail.

Only ACTION_VIEW with a constructed geo URI or ACTION_DIAL with a constructed tel URI can
be dispatched. There is no ACTION_CALL/autodial path, routing/navigation URI, GPS read,
permission request or fallback to arbitrary intents. JavaScript cannot supply components,
packages, flags, extras or recipients. Native discovery uses DEFAULT and a broad exact
matching action/scheme filter without MIME/authority/path/SSP/relative restrictions, not the
browser-specific BROWSABLE requirement. Exported/enabled, no component permission, same-profile,
noninstant, nonsuspended and nonshared-UID checks remain; candidates/counts are bounded. The
selected component, certificate, version, update time and UID are checked again against exact
intent resolution after source verification, before explicit dispatch. No implicit fallback.
Eligibility is not proof that the recipient is trustworthy or behaves as requested.

FLAG_SECURE native review shows the full query/coordinates/number, source app, operation and
chosen native recipient. The native launch view uses obscured-touch protection. Dispatch uses
a verified Android default or Android recipient choice.
The disclosure says the selected app receives these data and may use its own network,
accounts and history. Maps may send a location query to its provider. Dialer apps also receive
the number. External app behavior is outside Jarvys control; ACTION_DIAL requests a dialer UI,
not a sandbox that proves a third-party app cannot perform other actions. No real location,
number, call, map navigation or data transmission is used during synthetic validation.

A separate native broker, Binder domain, `factory-external-launch` journal and Settings recovery
preserve the v71 browser protocol and old recovery state. Shared admission permits one active
external interaction; startup reserves the new guard before asynchronous restoration, and its
review waits for external/browser/audio/share restoration. Older families can safely reject a
request during incomplete cold-start restoration; they cannot bypass the new owner's guard.
Journals contain only state/open/nonce, never query, coordinates, number, URI or recipient.
The consumed `launch_pending` latch is durable before dispatch; no exception, cancellation,
rotation, timeout or process restart replays it. Five-minute native expiry is independent of JS.
Source death/revocation and stale focus/generation checks revoke pending authority. Cancellation
cannot retract data or close another app. A pending worker retains ownership until finished.
A completed dispatch returns without a second external-screen closure acknowledgment.
Settings → External action review reconciles uncertain state, never proves OS closure, and
never turns an unknown outcome into a successful operation receipt.

A successful closed review returns `{launchRequested:boolean,actionConfirmed:false}`.
`launchRequested` only means startActivity returned; it does not prove UI display, navigation,
calling, delivery or task closure. The same receipt cannot assert any downstream completion.
Preview returns UNAVAILABLE and never launches an external app. `documents.cancel` is unrelated;
page/runtime invalidation and native expiry revoke these launch requests independently.
Updating Jarvys alone does not update previously generated APKs: rebuild with the same appId/key
and a higher version. Physical Android behavior, real Binder boundaries, installation/update,
recipient UI and user closure remain unverified until separately authorized device acceptance.
v73 adds only the bounded email/SMS editors below. TTS/voice and remaining F1 families stay pending;
F2/F3 remain closed, UX34 gated, UX44 documentation-only future defaults, UX43 last.

Official Android references checked October 10, 2026:
[common maps and dialer intents](https://developer.android.com/guide/components/intents-common),
[package visibility for intents](https://developer.android.com/training/package-visibility/use-cases),
[IntentFilter](https://developer.android.com/reference/android/content/IntentFilter).


## Email and SMS editors (UX42 F1, v73)

`Jarvys.email.compose({to,subject,body})` requires only `email`;
`Jarvys.sms.compose({number,body})` requires only `sms`. Neither grants documents, phone,
contacts, account access or sending. SDK 2/schema/protocol 1 remain unchanged. All fields are
required strings, with exact keys only; subject/body may be empty. No implicit defaults.

The shared typed validator accepts this conservative grammar, not all valid mailboxes/numbers:
- `to`: one ASCII address, at most 254 characters. Local part is 1–64 characters matching
  `[A-Za-z0-9_+\-]+(\.[A-Za-z0-9_+\-]+)*`. Domain has at least two dot-separated labels,
  at most 253 characters total, each 1–63 ASCII letters/digits/hyphens with alphanumeric ends.
  No display name, quoted local part, recipient list, Unicode domain, URI or header syntax.
  This validates syntax only, not mailbox existence; input is not trimmed or normalized.
- SMS `number`: exactly the v72 optional `+` followed by 1–15 ASCII digits, preserving zeroes.
- `subject`: at most 256 Unicode code points and 1,024 UTF-8 bytes, single line.
- `body`: at most 4,096 UTF-8 bytes; LF and TAB are allowed. Both text fields require paired
  surrogates and reject other ISO controls (including CR), format/bidi controls and Unicode
  line/paragraph separators. Literal punctuation is text, never URI/header instructions.
- Serialized editor arguments are limited to 32 KiB; the existing maps/dialer argument ceiling
  remains 8 KiB. The wider JSON envelope accommodates escaped text without raising field limits.

Only host-constructed ACTION_SENDTO is dispatched: `mailto:` with the encoded local part,
`@` and validated domain, or `smsto:` with the validated number. The local part is percent-encoded
once using UTF-8 and RFC 3986 unreserved bytes; a plus becomes `%2B`. No subject/body is put in
a URI query. Email has exactly fixed EXTRA_SUBJECT and EXTRA_TEXT, SMS exactly `sms_body`.
No ACTION_SEND fallback, arbitrary URI/component/extra/flag, CC/BCC, attachments, contacts read,
SMS permission, account selection, automatic Send or generated-app permission is added.
The host adds only narrow ACTION_SENDTO mailto/smsto visibility queries; generated APKs keep
zero permissions and the same two exact host package queries, factory-owned `documentBroker`
pin and launcher. Rebuild older generated apps with the same ID/key and a higher version.

This reuses the v72 closed external broker, journal, Binder protocol and recipient checks;
there is no generic intent API. Retained exact signed source APK and exact pinned host verification
remain mandatory, as do bounded eligible-recipient discovery and selected component/certificate/
UID/version/update-time revalidation immediately before dispatch. Two installed hosts fail closed.
Native review displays complete, separately labelled recipient, subject and body (including empty
values), source and selected app. FLAG_SECURE, disabled saved view state/autofill descendants/content
capture and obscured-touch protection apply to the native disclosure screen. These protections do
not erase IME/JVM copies or control the recipient app. No content is persisted in the journal.

Opening itself shares the fields with the selected app. It may ignore/change them, save or
cloud-sync drafts, or transmit content using its own accounts/network/permissions. Do not submit
secrets. ACTION_SENDTO requests an editor; Jarvys never presses Send, but cannot enforce recipient
behavior. Inspect the external editor before any further action. Native disclosure displays the fields before dispatch; there is no saved per-operation approval policy.

Five-minute expiry, cancellation/source death, stale/lost lifecycle authority, durable consumed
launch state, pending-worker ownership and no-replay recovery remain unchanged. Cancellation cannot
recall data or close the external app. Dispatch completes without another closure acknowledgment;
Settings → External action review preserves any uncertain outcome, never OS-verified closure. An unknown
outcome cannot become a successful receipt. `{launchRequested,actionConfirmed:false}` only records
startActivity acceptance, never editor display, draft saving, sending, delivery or verified closure.
Preview rejects both methods with UNAVAILABLE; `documents.cancel` remains unrelated.

`runtime.info` exposes `emailRequires:["email"]`, `smsRequires:["sms"]`,
`externalEditorsMaySyncDrafts:true`, `externalRecipientsMayUseNetwork:true` and
`externalActionConfirmed:false`. Its limits include `emailAddressCharacters:254`,
`editorSubjectCodePoints:256`, `editorSubjectBytes:1024`, `editorBodyBytes:4096`,
`editorArgumentsBytes:32768` and `externalLaunchLifetimeMs:300000`. Preview lists both capabilities
and methods as unavailable. `offline:true` still covers only `offlineScope:"embedded_webview"`.

Host validation does not establish actual editor acceptance, physical Binder/Android behavior,
installation/update, transmission or delivery. These remain separate authorized device gates.
This editor-only slice does not complete Factory: TTS/voice and remaining F1 stay pending, F2/F3
closed, UX34 gated, UX44 documentation-only future defaults and UX43 last.

References checked October 10, 2026: [Android common intents](https://developer.android.com/guide/components/intents-common)
and [RFC 6068](https://www.rfc-editor.org/rfc/rfc6068). The address subset above is intentionally
narrower than RFC 6068; subject/body use fixed Android extras, not arbitrary mailto headers.

## Single contact datum selection (UX42 F1, v74)

`Jarvys.contacts.pick({kind:"phone"})` or `{kind:"email"}` requires only `contacts`.
Exact keys and required strings; no default, URI, query, row ID, projection, component,
permission or intent extras can be supplied by JavaScript. SDK 2/schema/protocol 1 remain.
The capability contributes only the same two exact host-package visibility queries and
factory-owned `documentBroker` binding. Generated APKs remain permissionless.

The generated app authenticates its pinned host; the host verifies the retained exact signed
installed APK, version, certificate, hash and signing scope. Source/host shared-UID
ambiguity and two installed Jarvys hosts fail closed. A separate bounded control endpoint
handles cancellation and source death; no document handle or photo transfer is used.

The contacts.pick capability and a live host lease open one verified system `ACTION_PICK` component using only the
Phone or Email `CONTENT_TYPE`. Picker and canonical contacts-provider package/component,
UID, certificate set, version and update time are pinned and rechecked. A legitimate platform
contacts provider may have a shared UID. System provenance is not universal OEM compatibility.
The host adds two narrow ACTION_PICK MIME queries and one canonical provider visibility query;
its pre-existing permissions are unchanged, and this flow requests no permissions.

Only one owned `RESULT_OK` callback from the original live picker interaction can authorize
reading. Unsolicited, canceled, duplicate, expired, restarted and stale results do not create
selection authority. There is no `createPendingResult`, deep-link, Binder-URI or recovery-data
read entry point. The raw URI must be exactly
`content://com.android.contacts/data/<positive signed-64-bit integer>` without leading zeroes,
encoding, query, fragment, user/profile prefix or trailing slash. Typed/lookup/collection,
enterprise and Android 17 session URI shapes are intentionally unsupported; no URI rewriting.
Result metadata, grant flags and ClipData are strictly checked. No write/prefix/persistable grant.

Android access must be supplied by an explicit read URI grant or already-granted host
`READ_CONTACTS`. Existing permission is never selection provenance or consent. AOSP may omit
a redundant basic URI grant when this permission already exists; the same exact-row reader
handles either access condition. No permission request/change, bulk query, retry or fallback.
Before querying, the host rechecks source proof/control/deadline and picker/provider identity.
The one query uses the unmodified selected URI, exactly `[mimetype,data1]`, null selection and
sort, and a CancellationSignal. Exactly one row with expected phone/email item MIME is accepted.
No names, labels, contact IDs, photos or enrichment are requested. The accepted value is limited
to 256 Unicode code points/1,024 UTF-8 bytes; malformed surrogates, blank-only text, ISO controls,
format/bidi and line/paragraph separators fail. Cursor/provider allocation is outside this
accepted-value bound. Provider Binder cancellation is best effort, not a hard I/O timeout.

The human selects the datum in Android. The validated frozen string is delivered without a second
host approval. Delivery rechecks the latest source proof/control and consumes the live authority lease
durably before returning exactly
`{kind,value}`. The returned value is unchanged: no normalization, dialability, mailbox validity,
contact ownership or external action claim. No URI/ID/name/permissions are returned. A generated
app can retain this approved value or use other separately granted capabilities to share it.

Review uses FLAG_SECURE, disabled saved state/autofill descendants/content capture and obscured
and partially obscured touch filtering; human accessibility remains. These protections do not
erase immutable JVM strings or control another app. Journals contain state/open/nonce only.
Five-minute expiry, background/focus loss after return, cancellation and restart revoke delivery.
Shared admission remains while provider work is blocked; Native interaction protection ends when the external action is dispatched or cancelled.
Settings → Contact selection review provides native uncertain-outcome recovery; acknowledgment
is not OS closure proof. A fresh authorized request may retire historical uncertainty without
restoring old authority. Recovery never returns data or replays selection. Cancellation cannot erase copies already received.

Preview returns UNAVAILABLE. `documents.cancel` is unrelated. `runtime.info` exposes
`contactsRequires:["contacts"]`, `contactsBrokerRequired:true`, allowed kinds, protocol and
bounds, with no normalization or syntax-validation claim. All host tests use synthetic rows,
intercepted system dispatch and an offline guard; actual device/OEM grants, Binder/UI lifecycle,
installation/update and contact selection remain unverified until authorized physical testing.
Calendar insertion is described separately below; TTS/voice, remaining F1/F2/F3, UX34 gates,
UX44 documentation-only defaults and UX43-last are preserved.

References checked October 10, 2026: [Android common intents](https://developer.android.com/guide/components/intents-common),
[explicit URI permission API](https://developer.android.com/reference/android/content/Context#checkUriPermission(android.net.Uri,%20int,%20int,%20int)),
[AOSP URI grant handling](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/uri/UriGrantsManagerService.java),
[AOSP contacts provider](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/master/AndroidManifest.xml),
and [Android 17 contact picker](https://developer.android.com/about/versions/17/features/contact-picker).

## Calendar event editor (UX42 F1, v75)

`Jarvys.calendar.insert({title,location,description,startTimeMillis,endTimeMillis,timeZone,allDay})`
requires only `calendar`. All seven keys are required with no defaults. Title is nonblank,
paired Unicode, at most 256 code points/1,024 UTF-8 bytes. Location has the same bounds but may
be empty. Description may be empty and is at most 4,096 UTF-8 bytes. Text is unchanged;
ISO controls, format/bidi and line/paragraph separators fail, except description LF and TAB.
CR and malformed surrogates fail. Argument JSON is limited to 32 KiB, including escaped text.

Times must be JSON integers, not strings, decimals, exponent forms or coercible values.
They are absolute Unix milliseconds in `[0,4102444800000]` (1970-01-01 through the boundary
2100-01-01T00:00:00Z), with end strictly greater than start and duration at most 366 days.
No local-time parsing, DST guessing or current-time default. `timeZone` must exactly match
an ID in the installed runtime's timezone data (or literal `UTC`); aliases depend on that data.
Unknown IDs never silently become GMT. `allDay` is a JSON Boolean. When true, timezone must
be literal `UTC`, both times must be exact UTC midnights, and the end date is exclusive.

This is an editor request only: fixed `ACTION_INSERT`, fixed
`content://com.android.calendar/events`, literal MIME `vnd.android.cursor.dir/event`, and only
TITLE, EVENT_LOCATION, DESCRIPTION, EXTRA_EVENT_BEGIN_TIME, EXTRA_EVENT_END_TIME,
EVENT_TIMEZONE and EXTRA_EVENT_ALL_DAY. The time extras are native longs and all-day is Boolean.
EVENT_TIMEZONE is a requested prefill; recipient behavior is not guaranteed. There is no
calendar/provider query, insert, update, deletion, event ID, attendees, invitations, recurrence,
reminders, arbitrary URI/component/extra/flags, permission request or automatic saving.
No `READ_CALENDAR`/`WRITE_CALENDAR` permission is added. Existing host permissions are unchanged;
generated APKs remain permissionless and acquire only the two reviewed host-package queries.

The v72–v74 retained exact signed source APK, authenticated pinned host, bounded discovery and
recipient component/certificate/UID/version/update-time revalidation apply unchanged. The host
adds one narrow ACTION_INSERT/DEFAULT/event-MIME visibility query. Recipient filters must have
exactly that sole MIME, no scheme/authority/path/scheme-specific-part/relative-URI restrictions,
and the matching action/default category. Wildcards and item-event MIME fail. This conservative
subset can reject otherwise legitimate OEM or content-scheme handlers; no generic fallback.

The native view shows source app/version/APK hash/certificate, every field in separate
labelled views, exact epochs and readable timezone offsets (UTC dates for all-day), and the exact
selected recipient. Dispatch uses authenticated host authority and a verified Android default or recipient chooser. Description is last and never interpreted as labels or links. Opening itself
shares all fields with the app. It may ignore/change times, timezone, all-day or other values,
save or transmit data, and cloud-sync a draft using its own accounts/network/permissions. Jarvys
never presses Save. Inspect all fields again in the external editor; never submit secrets.

FLAG_SECURE, disabled saved state/autofill descendant export/content capture, obscured-touch
filtering protect the native disclosure screen; they do not erase JVM copies or control external-app behavior.
Five-minute expiry, cancellation/source death, lifecycle loss, consumed launch authority and
uncertain-state recovery prohibit replay. Dispatch needs no extra user-declared closure. Journals retain only
state/open/nonce, never event content. Cancellation cannot recall content or close another app.
Settings → External action review recovers uncertainty without dispatching or returning event data.
`{launchRequested,actionConfirmed:false}` proves only accepted startActivity dispatch, never that
a screen appeared, an event was created/saved, or the external task actually closed.

SDK 2/schema/protocol 1 remain. Preview reports UNAVAILABLE; `documents.cancel` is unrelated.
`runtime.info` exposes calendar requirements, exact bounds and advisory-editor status. Synthetic
host tests intercept dispatch and use no real calendar/event/user data. Physical Android/Binder,
OEM recipient handling, timezone/all-day interpretation, installation/update and saved-event
behavior remain unverified until separately authorized device testing. TTS/voice and remaining
F1/F2/F3 stay pending, UX34 gated, UX44 documentation-only future defaults and UX43 last.

References reviewed October 10, 2026: [Android common calendar intents](https://developer.android.com/guide/components/intents-common#Calendar),
[Calendar provider intents](https://developer.android.com/identity/providers/calendar-provider#intents),
[EVENT_TIMEZONE](https://developer.android.com/reference/android/provider/CalendarContract.EventsColumns#EVENT_TIMEZONE),
and [IntentFilter](https://developer.android.com/reference/android/content/IntentFilter).


## Private structured database (UX42 F1, v76)

`database` is the standalone seventeenth capability. SDK 2/schema/protocol 1 remain unchanged.
Its six Promise methods require only that selected capability: no Jarvys host binding, Android
permission, host visibility query, document handle or native confirmation for each private query.
Other capabilities retain their existing review and authorization requirements. There is no raw
SQL, selectable database name/path, ATTACH, extension loading, network, import/export, join,
arbitrary expression, index creation or generic query interface.

Installed apps use one fixed SQLite file in their own no-backup directory. Data persists across
connection closure and normal app restarts; this is not encrypted storage or a backup. App removal,
data clearing or device loss can lose it. Preview uses isolated in-memory SQLite and loses data on
close, reset or pause/session invalidation. Installed close retains data; it does not delete/reset
schema. Lifecycle cancellation invalidates queued work, signals active reads and closes the
connection on the serialized worker. Rebuild older APKs with the same app ID/key and a higher
version to acquire the API; actual data-preserving updates still require device acceptance.

All objects reject unknown keys. Common strict JSON limits (depth 8 and 1,024 tokens) still apply,
so individual maxima cannot necessarily be combined in one request. Table/column identifiers are
`[a-z][a-z0-9_]{0,31}`, excluding `id` and prefixes `sqlite_` and `factory_`. Each table has an
implicit immutable string primary key `id`, matching `[A-Za-z0-9_-]{1,96}`. Application columns
use `{type,nullable}` with required Boolean `nullable` and type `text`, `integer`, `real` or
`boolean`. Values are typed scalars or null only when nullable. Integers are JSON integers within
±9,007,199,254,740,991; reals are finite JSON numbers. Text requires paired Unicode, no NUL, and
at most 4,096 UTF-8 bytes. No coercion, nested cell objects, arrays or blobs.

- `Jarvys.database.info()` (or `{}`) returns `{version,schema,mode,persistent,encrypted,
  backupIncluded,limits}`. New stores start at version 0 with an empty schema. Mode is `installed`
  or `preview`; `encrypted` and `backupIncluded` are false. Inspect before migration/recovery.
- `migrate({fromVersion,toVersion,steps})` advances exactly one version, from the actual current
  version (0–999) to `fromVersion+1` (1–1000). One to sixteen steps execute atomically.
  `createTable` uses `{kind:"createTable",table,columns}`, with 1–16 column definitions.
  `addColumn` uses `{kind:"addColumn",table,column,definition}` and requires `nullable:true`;
  existing rows acquire null. Existing table/column names cannot be redefined. No drop, rename,
  type change, backfill expression or index step. Returns `{version,stepsApplied,committed:true}`
  only after successful commit. Version conflicts require inspection, not automatic replay.
- `transact({version,operations})` requires the exact current version (1–1000), with 1–64 atomic
  operations: `{kind:"insert",table,id,values}`, `{kind:"update",table,id,values}` or
  `{kind:"delete",table,id}`. Insert/update values must be nonempty. Insert requires every
  nonnullable column; omitted nullable values are null. Duplicate IDs fail rather than upsert.
  Update changes only supplied columns and requires an existing row. Delete can affect zero rows.
  Returns `{version,changes,committed:true}`, with one affected-row count per operation.
- `select({version,table,where?,afterId?,limit?})` requires exact current version. Optional `where`
  is a conjunction of typed equality predicates on declared columns, including null equality;
  empty/omitted means no filter. The implicit id also supports equality with a nonnull valid ID
  string, for example `where:{id:"one"}` for direct lookup. Optional
  `afterId` is an exclusive cursor in ascending binary string-ID order, not insertion/time/numeric
  order. Limit defaults to 50 and is 1–100. Returns `{version,rows,hasMore,nextAfterId,
  snapshotAcrossPages:false}`; rows include id and all columns, and nextAfterId is null when no
  further page exists. Separate pages are not a stable snapshot; concurrent changes can affect
  later pages. There is no offset, custom sort, OR/range predicate, projection or aggregate.
- `close()` (or `{}`) returns `{closed:true,dataRetained}`; true for installed, false for preview.
- `cancel()` (or `{}`) returns `{cancelRequested:true,rollbackConfirmed:false}`. Cancellation
  cannot undo a transaction that crossed the commit boundary or prove rollback after a lost
  reply. Inspect info/schema/affected IDs after interruption or uncertainty; never blindly replay
  writes or migrations. A missing reply is not proof that no mutation committed.

Limits: eight tables, sixteen application columns per table (plus implicit id), sixteen migration
steps, sixty-four operations per transaction and 10,000 rows total across tables. Each serialized
row is at most 16 KiB, each text cell 4 KiB, and serialized arguments/results at most 256 KiB;
select returns at most 100 rows and can hit the byte bound sooner. Reduce requested page size
when a result would exceed its bound. SQLite main-file size is capped at 16 MiB. Journal/temp
files, SQLite caches, cursor/JVM objects and preview RAM are additional usage: this is explicitly
not a total disk or memory hard limit. Migration verifies enlarged existing rows before commit.

Failures include INVALID_ARGUMENT, VERSION_CONFLICT, SCHEMA_MISMATCH, NOT_FOUND,
QUOTA_EXCEEDED, DATABASE_ERROR and lifecycle CANCELLED, plus ordinary bridge/capability errors.
SQLite errors are sanitized; SQL, filenames and private values must not be logged or exposed.
Failed precommit batches roll back atomically. Corrupt/missing metadata is not silently repaired
or reset; preserve uncertainty and data rather than erasing the store on startup. Synthetic shared
validator/SQLite/runtime/SDK tests do not establish real WebView, Binder, device storage durability,
installation/update, physical ARM64 acceptance or zero network behavior. External capability,
TTS/voice, F1/F2/F3 and UX34/UX44/UX43 gates remain unchanged.

## Presentation preferences (UX42 F1 v77)

`presentation` is the eighteenth capability. SDK 2 and schema/protocol 1 remain unchanged.
It adds three Promise methods on `Jarvys.presentation`, available only in installed generated
apps. These are private per-app preferences: no broker, Android permissions, system settings,
host preference changes or arbitrary manifest attributes. Preview rejects all three methods
with `UNAVAILABLE`; it never changes Jarvys's theme or orientation.

- `get({})` reads the requested pair, defaulting to `{theme:"system",orientation:"system"}`.
- `set({theme,orientation})` requires exactly both keys. Theme is `system`, `light` or `dark`;
  orientation is `system`, `portrait` or `landscape`. Both values are validated before saving.
- `reset({})` requests the same pair as `set({theme:"system",orientation:"system"})`.

Results expose requested `theme` and `orientation`, observed `effectiveTheme` (`light`/`dark`)
and `configurationOrientation` (`portrait`/`landscape`/`undefined`), plus
`orientationGuaranteed:false` and `recreationRequested`. A successful write additionally reports
`preferencesCommitted:true`; this is omitted for reads and no-write results and is not proof
of physical durability. An unchanged pair is a no-op only when it equals both stored choices
and the immutable pair applied at this Activity's bootstrap. Observed orientation does not
control that comparison because Android can ignore the request. A saved choice whose queued
recreation was canceled can require recreation on a later explicit request for the same pair.

Save application state before changing presentation: Activity recreation discards current JS
state. A receipt does not guarantee delivery to JavaScript, recreation or the requested visual
result. Queued callbacks are canceled on navigation, pause and close; saved choices may instead
apply on the next launch. Inspect `get` after interruption rather than blindly replaying.
SharedPreferences `commit()` failure reports an error and requests no recreation, but Android
may already have changed in-memory preferences despite failed persistence. A subsequent read
must not be interpreted as proof of a successful disk write. Pending native UI must not overlap
another presentation callback/recreation.

The native light/dark platform theme is chosen before Activity creation. The context override
changes only the configuration's night-mode bits, preserving font scale, locale, density and
other user settings. The existing UX35 safe-area container remains the sole system-bar/cutout/IME
inset owner, including after recreation. Authored CSS must adapt to light/dark colors;
`prefers-color-scheme` behavior depends on the installed WebView provider. There is no forced
recoloring of arbitrary web content. See Android's [WebView dark-theme guidance](https://developer.android.com/develop/ui/views/layout/webapps/dark-theme).

Orientation is a request, not a guarantee. The current template still targets API 35. Android's
[target-36 large-screen behavior](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability)
generally ignores orientation restrictions at smallest width >=600dp; OEM policy, multi-window
and other device conditions can also limit requests. Keep responsive, scrollable layouts and
verify actual device behavior. Host tests cannot establish WebView CSS behavior, visible
rotation, physical preference durability or ARM64 acceptance.

Adaptive icons/resources, TTS/voice, sensors, biometrics, backup, local UI and F2/F3 remain
pending. This bounded API does not complete those roadmaps or relax their acceptance gates.

## Versioned agent guidance (v78 maintenance)

The reserved Factory skill is delivered as an always-present Coding core plus seven immutable
APK references. `read_skill` keeps its `skill_id` form and accepts the optional paired
`resource`/`resource_version` for complete, bounded reference retrieval. Read the core's exact
catalog first; files are not assumed to exist in the project workspace. Unknown/stale names,
invalid resource assets or insufficient whole-content budget fail without a partial load.
The core remains present through transcript compaction and respects read-only missions.
Installed custom skills, disabled/usage state and profile migration are unchanged.
This changes guidance delivery only; the capability/method catalog above remains v77.


## Foreground sensors (UX42 F1 v79)

`sensors` is the nineteenth capability; SDK 2/schema/protocol 1 stay unchanged. Its v79 totals
were 19 capabilities/39 methods. Only installed generated apps with this declared capability
can use `Jarvys.sensors`. No broker, new Android permission, native per-read confirmation,
network, background service, GPS/location, health, microphone or arbitrary sensor enumeration.
No other grant implies `sensors`. Preview rejects all three methods with `UNAVAILABLE`, even
list and cancel; it provides no simulated readings. Older templates require a reviewed rebuild.

- `sensors.list()` (or `{}`) returns `{sensors:[{kind,available,unit,frame}],mode:"installed"}`.
  The fixed kinds are `light` (unit `lux`, frame `scalar`) and `accelerometer` (unit `m/s2`,
  frame `device-natural`). Availability is a Boolean; it is not a promise of future samples.
  No hardware names, vendors, IDs, accuracy, resolution or arbitrary sensor types are exposed.
- `sensors.capture({kind,durationMs,intervalMs,maxSamples})` requires exactly all four keys.
  `kind` is `light` or `accelerometer`. Duration is a typed JSON integer 100–5000 milliseconds,
  interval a typed JSON integer 100–1000 milliseconds, and maxSamples a typed JSON integer
  1–50. No strings, fractions, exponents, coercion, defaults or unknown keys.
  Only one capture may be active. Overlap fails rather than replacing the old operation.
- A completed capture returns `{kind,unit,frame,includesGravity,reason,samples,mode:"installed",
  simulated:false}`. `reason` is `sample_limit` or `duration`. Each sample is
  `{elapsedMs,values:[number]}` for light, or `{elapsedMs,values:[x,y,z]}` for accelerometer.
  `includesGravity` is false for light and true for accelerometer. Values are copied finite
  numbers; event-owned arrays are not retained. The complete serialized result is at most
  16 KiB, not merely its samples array.
- `sensors.cancel()` (or `{}`) rejects the active capture with `CANCELLED` and idempotently
  returns `{cancelled:boolean}`. False means no active capture was canceled. This does not
  undo previously delivered readings or erase copies retained by application code. If unregister
  fails, cancel rejects `UNAVAILABLE`, discards data and disables the controller. All later
  sensor calls, including cancel, reject `UNAVAILABLE` while cleanup failure remains latched;
  no success/false result claims cleanup. Lifecycle cancellation suppresses stale replies.
  The complete cancel wire JSON envelope is limited to 1,024 UTF-8 bytes; SDK empty-args
  requests fit this bound. This permits bounded cancellation outside ordinary admission limits.

`elapsedMs` is capture-relative monotonic receipt time in integer milliseconds, not wall time
or the Android hardware-event timestamp. It cannot establish exact physical acquisition time.
Accelerometer values include gravity and keep the device's natural coordinate frame, not
screen-rotated/world coordinates. Do not claim calibrated measurement, pose or health inference.

Output is throttled to at most 10 Hz, including across sequential captures and kind switches;
intervalMs can lower that ceiling. A requested interval is not a guaranteed sample rate.
Light is on-change: a duration completion may contain fewer than maxSamples or zero samples.
No interpolation, duplicate fill, minimum rate or guaranteed hardware availability. Android
callback scheduling, sensor absence and OEM differences can affect observations.

Success, explicit cancel, pause, navigation, reset, destruction and capability revocation
invalidate the operation and unregister its listener. There is no automatic restart on resume.
Late callbacks cannot revive a revoked capture. An unregister failure discards collected data
and disables the controller; do not claim successful cleanup or that hardware stopped. Show
an honest error and stop collection rather than retrying around the failure. A missing reply
is not permission to restart. Sensors have no broker/native external-action recovery workflow.

Handle bridge/capability, invalid argument, unavailable, busy, cancellation and native failures.
Render loading/empty/canceled/error states; require an explicit next capture after interruption.
No readings in logs, telemetry or background loops. Separately declared storage/export/share
capabilities retain their own contracts and consent requirements.

The runtime adds sensor denials to WebView Permissions-Policy. Actual WebView enforcement,
physical output rates, sensor availability, lifecycle cleanup, rotation and ARM64 installation
remain unverified without authorized device testing. Synthetic fixtures prove only their tested
code paths, never hardware-stop guarantees. Remaining sensors, TTS/voice, biometrics, backup,
local UI, F1/F2/F3, UX34 gates, UX44 documentary defaults and UX43-last stay pending.

Official Android references checked October 10, 2026:
- [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [SensorEvent data and device coordinate frame](https://developer.android.com/reference/android/hardware/SensorEvent)
- [SensorManager registration, rate hints and unregister](https://developer.android.com/reference/android/hardware/SensorManager)

## Local strong biometrics (UX42 F1 v80)

`biometrics` is the twentieth capability; 20 capabilities/42 wire methods, SDK 2 and
schema/protocol 1. Only an installed generated app with this explicit grant can use
`Jarvys.biometrics`. All three preview methods reject `UNAVAILABLE`, including availability
and cancel; no simulated success. A compatible Jarvys broker is not required.

Only generated profiles selecting `biometrics` add the normal Android permission
`android.permission.USE_BIOMETRIC`. The compiled template and every nonbiometric profile
retain zero permissions. No fingerprint legacy permission, extra component, feature, query,
service or runtime permission prompt is added by this grant. It is the first reviewed
selective permission contribution to F0a, not full Runtime2 or arbitrary manifest support.
Other broker profiles keep their two exact queries; signing scope review must disclose the
biometric permission against the last signed artifact, never assume the installed scope.

The native adapter uses the Android framework `BiometricPrompt` on API30+ with only
`BIOMETRIC_STRONG` and `setConfirmationRequired(true)`. Confirmation is a system hint,
not proof of an additional user button; verify actual OEM behavior. API24–29 is explicitly
unsupported, including API28/29. There is no device credential fallback, enrollment/settings launch, weak biometric,
CryptoObject, key binding, reusable token, biometric material, credential or export API.
The person alone operates the OS prompt; never automate, impersonate, bypass or retry its
consent. The native title is fixed: "Biometric authentication"; subtitle: "Local authentication
only". The exact app-provided reason and a native notice that this authorizes no other action
are shown in the system prompt. No arbitrary title or button labels from JavaScript.

- `biometrics.availability({})` (or no argument) accepts only empty args and returns exactly
  `{available,status,minimumApi:30,biometricClass:"strong",credentialFallback:false,mode:"installed"}`.
  `status` is `ready`, `unavailable` or `unsupported`; `available` is true only for `ready`.
  This coarse snapshot discloses no enrollment count, modality, sensor identity or detailed
  reason. Ready is not admission, a guarantee of future availability or proof of authentication.
- `biometrics.authenticate({reason})` requires exactly that key, a meaningful plain string
  of 1–160 UTF-16 code units and at most 512 UTF-8 bytes. No trimming/normalization/coercion,
  controls, bidi/format characters, line/paragraph separators or unpaired surrogates; all-space
  reasons are invalid. No secrets or sensitive personal data in the reason. Only one native UI
  operation may own the foreground page. Admission requires current focus; the OS prompt can
  take focus without revoking the still-resumed page/session. Pause and actual authority loss
  cancel. Nonterminal mismatch/help callbacks retain the prompt and never resolve success.
- Success returns exactly `{authenticated:true,biometricClass:"strong",authorizesOtherActions:false,
  mode:"installed",simulated:false}`. It records only this local OS biometric event. It is not
  remote identity, identity proof, a cryptographic assertion, a token or a grant for payment,
  signing, sending, installation, data release or any other action. Each operation still requires
  its own capability and Android prompt; required Android consent is not satisfied by a biometric receipt. Never persist a receipt as reusable
  authorization, claim who authenticated, or use it to unlock an unrelated protected action.
- `biometrics.cancel({})` (or no argument) accepts only empty args. It rejects an active
  authentication with `CANCELLED` and returns `{cancelRequested:boolean,cancellationAcknowledged:false}`.
  False means no active controller request was canceled; it does not prove that an earlier
  prompt closed. Even true is only a cancellation request, never OS closure acknowledgment.
  A cleanup exception rejects `UNAVAILABLE` rather than fabricating a successful cancel receipt.

Native authentication has a 60-second monotonic deadline; the SDK wait is 90 seconds.
Timeout, explicit cancel, pause, navigation, reset, destruction, capability/session revocation
and global bridge rate failure revoke delivery and request native cancellation. There is no
replay after process death/restart or automatic resume. Late results cannot revive a request.
A lost reply or SDK timeout is not proof of UI closure and never authorizes blind retry.

Cancellation retains a process-wide draining owner until a matching terminal callback. Shared
native UI remains blocked across Activity recreation while draining. Mismatch/help do not
acknowledge cleanup. If no terminal acknowledgment arrives within 2 seconds, or native cleanup
throws (even after a synchronous terminal callback), cleanup is unconfirmed and the gate stays
poisoned until process restart. Authentication and cancel reject `UNAVAILABLE`; availability is
coarsely unavailable. Do not clear the poison, claim dismissal or prompt the user to repeat an
operation while cleanup is unconfirmed. Process restart clears only in-memory state, not proof
of past OS behavior. The adapter clears only its matching platform signal on terminal callbacks.

There are at most 3 admitted authentication starts per rolling 60 seconds and 8 no-op cancel
controls per rolling 10 seconds. Bounded monotonic counters survive Activity recreation, not
process restart. Native UI busy/poison and ordinary bridge/capability checks still apply.
A valid cancel's complete wire envelope is at most 1 KiB (1,024 UTF-8 bytes), bypasses ordinary
pending-slot/global-rate saturation, and uses the SDK's bounded reserved control capacity.
A recognized bounded invalid cancel is rejected per request without revoking authentication.
Oversized/unparseable traffic follows normal admission; exhaustion of the global limiter closes
pending requests and cancels authentication. No path uses malformed control data as authority.

Handle `INVALID_ARGUMENT`, `UNAVAILABLE`, `BUSY`, `RATE_LIMITED`, `CANCELLED`, `TIMEOUT`,
`AUTHENTICATION_FAILED`, bridge/capability and native errors honestly. Require an explicit new
request after interruption; never log receipts/reasons as identity evidence or collect biometric
material. Synthetic adapters/API tests do not establish physical matching, modality/strength,
OEM focus/cancel behavior, lifecycle closure, installation/update or ARM64 device acceptance.
This contract claims no completed validation or delivery; report actual evidence separately.
TTS/voice, backup, local UI, remaining F1/F2/F3 stay pending; UX34 stays gated, UX44 documentary,
and UX43 last. All previous native consent, data, signing, delivery and legacy-profile boundaries
remain in force.

Platform references checked October 10, 2026; these do not establish device acceptance:
- [Framework prompt and confirmation hint](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.Builder#setConfirmationRequired(boolean))
- [Normal USE_BIOMETRIC permission](https://developer.android.com/reference/android/Manifest.permission#USE_BIOMETRIC)

## Versioned agent guidance (v80 extension)

The always-present core now retrieves nine immutable references using factory-guidance-v80.
The new biometrics module extends the eight v79 modules without removing the 18 historical v77
contract sections. Historical totals, zero-permission assertions and pending-family statements
are explicitly version-scoped; current headers distinguish the selective biometric permission.
Headers and manifest hashes are release-bound. The core remains at most 8 KiB, each reference
at most 16 KiB, total references at most 64 KiB; the original 16 KiB read budget stays unchanged.
Real read_skill retrieval must cover all nine modules, read-only Coding access and unchanged
custom-skill/disabled/usage/profile preservation. These are test requirements, not claims that a
particular test run or physical acceptance completed.

## Packaged local UI library (UX42 F1 v81)

Every new generated APK contains the exact Pure.css 3.0.0 distribution stylesheets plus
BSD-3-Clause and embedded normalize MIT notices and provenance. This is fixed-inclusion
runtime data, not a capability: 20 capabilities/42 methods, SDK2/schema1 and permissions
are unchanged. `apk_factory inspect` reports summary counts, not `localUiLibraries`.
Public `factory-provenance.json` records the exact paths, bytes, SHA-256, licenses and inclusion policy;
project-source hashes continue to describe only actual project inputs.

Link `/factory-ui/pure-3.0.0/pure-min.css` and
`/factory-ui/pure-3.0.0/grids-responsive-min.css`, then a project stylesheet. The usable
[local UI example](examples/factory-local-ui/README.md) demonstrates responsive columns,
labeled forms, buttons and a table with separate JavaScript and session-only state.
CSS loads only when linked, although all five library resources are always packaged.
The notices are `LICENSE.txt`, `normalize-LICENSE.txt` and `PROVENANCE.json` at the same
root. These runtime-owned exact paths cannot be replaced by project web files.

The finite path/length/hash catalog rejects missing, modified, duplicate, unexpected and
wrong-version runtime UI resources at template/preview validation. No arbitrary directory
is exposed. Both CSS payloads contain no external imports, URL dependencies, scripts or
source-map references; upstream comment links remain provenance, not network requests.
No package scripts, compiler or dependency download runs during APK construction.
Project limits remain 128 files, 1 MiB each, 8 MiB total, with existing traversal/symlink
rules. Runtime library overhead is independently bounded at 32 KiB per file / 64 KiB total.
Preview copies and verifies the same catalog without consuming project quotas.

CSP, same-origin/frame policy, runtime network restrictions and navigation are unchanged.
Project styling owns dark themes, contrast, targets and custom-widget accessibility.
Use native safe-area/IME insets once. A library is no promise of Android WebView/provider
compatibility or a physical rendering test. Existing device/OEM/installation gates remain.
Exact archive integrity, licenses and extraction are in the bundled provenance and
`recovery/factory-local-ui/README.md`; Pure includes normalize CSS and its full MIT notice.


## Gyroscope extension (UX42 F1 v82)

The existing foreground sensors contract now accepts `kind:"gyroscope"` alongside light and
accelerometer. `runtime.info().sensorsKinds` and `sensors.list()` advertise all three fixed kinds.
SDK 2, schema/sensors protocol 1, 20 capabilities and 42 methods are unchanged. No new Android
permission, required hardware feature, broker, background subscription or network path is added.
Older generated APK templates require a reviewed rebuild to obtain this additional kind.

Android `TYPE_GYROSCOPE` supplies angular velocity in `rad/s` as copied finite `[x,y,z]` values,
`frame:"device-natural"`, `includesGravity:false`. In the device's natural orientation, x points
right, y toward the top and z outward from the screen; positive rotation follows the right-hand
convention. Axes do not rotate with the display. This is not an orientation, pose, integrated
angle or measurement-accuracy promise. No uncalibrated sensor fallback or six-value bias data.
On API 24+ devices without the calibrated sensor, availability is false and capture rejects
`UNAVAILABLE`; preview still rejects every sensor method, without synthetic hardware claims.

All existing shared limits and lifecycle rules apply unchanged: 10 Hz across kinds/sequential
captures, 100–5000 ms duration, 100–1000 ms interval, 1–50 samples, complete result at most 16 KiB.
One capture at a time; cancel, pause, navigation/reset/destroy, focus/session/authority loss and
global rate failure discard buffered data. No auto-resume. Cleanup failure disables capture and
never claims hardware stopped. Light and accelerometer retain their original units/semantics.
Synthetic host tests and binary review do not establish device availability, accuracy, timing,
physical rotation/lifecycle behavior or installation acceptance.

## Native network, notifications and speech (v85)

Original native-network checkpoint: 23 capabilities/59 methods, SDK2/schema1. Current totals and added generic services are described above. This reference grants no authority or validation. All methods below require their declared capability and installed foreground runtime; all reject UNAVAILABLE in preview before effects, including status/cancel. Inspect the actual template. Same-ID/key, increased-version rebuild is needed for existing APKs.

### Shared authority and manifest
In a generated APK, foreground network requests/probes/ping, notification mutations/permission requests, and speech voices/speak/stop use the packaged capability and a local page-bound operation lifetime; they do not contact Jarvys for each effect. Android runtime permissions and platform availability still apply. Background job/widget/scheduled-notification work uses the installed app's own signer and packaged resource budgets. Host-bound document, backup, clipboard and external handoff operations retain their authenticated Binder lease. Status is read-only; cancellation cleans up owned work without authorizing a new effect. No blind replay after reply loss; prior transmission/audio/notifications/files can remain.

The permission-free template stays unchanged. Selected network adds INTERNET and generated usesCleartextTraffic=true; notifications adds POST_NOTIFICATIONS; biometrics adds USE_BIOMETRIC independently. speech adds the exact android.intent.action.TTS_SERVICE visibility query, with the two exact Jarvys host packages. Export/share and native families require the host pin. No broad package visibility or per-app compiler is required. The original checkpoint added no service/receiver; current opt-in notification scheduling adds the exact private receivers documented above. INTERNET is process-wide: local-only WebView/CSP settings do not prove zero process egress. Native TLS trust and hostname verification remain platform-owned. A TTS engine/provider/recipient can use its own network even when the generated app has no INTERNET.

`runtime.info` now reports `offlineScope:"embedded_webview_configuration_not_process_isolation"`; `offline` is false with network selected and otherwise describes only configured WebView behavior. `processInternetPermission` follows network selection; `speechEngineMayUseNetwork:true`, `nativeProbeIsIcmp:false` and `nativePingProtocol:"icmp"` disclose boundaries, not authority or test evidence. A local foreground operation lifetime expires after five minutes and remains page-checked while active. SDK waits are 420000 ms for network/notifications and speech status/voices/stop, 86400000 ms for speak. SDK timeout never proves rollback or remote closure.

### Native HTTP
Jarvys.network.request({url,method?,headers?,body?,bodyBase64?,responseType?,connectTimeoutMs?,readTimeoutMs?,timeoutMs?,maxRequestBytes?,maxResponseBytes?,maxHeaderBytes?,redirect?,maxRedirects?,allowHttpsDowngrade?}). Exact keys/types; absolute HTTP(S) URL ≤8192 UTF-8 bytes, no userinfo/fragment, invalid port, control/space/backslash. Methods are HTTP tokens ≤32 chars, default GET; platform-unsupported methods reject. Choose UTF-8 body or canonical padded bodyBase64, never both; GET/HEAD/TRACE bodies reject. responseType text(default)|base64. No automatic cookie/session credential management is promised.

Defaults/ranges: connectTimeoutMs/readTimeoutMs 10000, timeoutMs 30000; each 1–120000 ms. maxRequestBytes/maxResponseBytes default 65536, range 0–262144. maxHeaderBytes default 16384, range 256–32768. At most 64 request headers; ASCII field values and no case-duplicate or transport-managed headers (Host/Content-Length/Transfer-Encoding/Connection/Proxy-Connection/Expect/Upgrade/Trailer/TE). Requests still fit the 512 KiB bridge envelope; encoded results must fit 512 KiB minus 4096, so body limits are not guaranteed return sizes. Oversize rejects, never silent body truncation.

redirect follow(default)|manual|error; maxRedirects default 5, range 0–10. HTTPS→HTTP requires allowHttpsDowngrade:true (default false) and Android cleartext permission. Cross-origin redirects discard all caller headers; 303 except HEAD and 301/302 POST switch to GET without body. Bodies can reach redirect destinations for preserved-method redirects; authorize that consequence. System proxy/DNS/server behavior remains external. No TLS/hostname bypass.

Success returns {url,method,status,ok,headers,redirects,latencyMs,elapsedMs,cleartext,bodyBytes,responseType} plus body/charset or bodyBase64. headers maps lowercase names to arrays. HTTP 4xx/5xx are responses with ok:false; latencyMs measures time through response headers, elapsedMs through read, not ICMP RTT. Transport failures reject DNS_ERROR/TLS_ERROR/TIMEOUT/NETWORK_ERROR or protocol/redirect/size errors; network bytes may already have been sent.

Jarvys.network.probe({url,method?,headers?,connectTimeoutMs?,readTimeoutMs?,timeoutMs?,maxHeaderBytes?,redirect?,maxRedirects?,allowHttpsDowngrade?}) uses HTTP HEAD(default) or GET without consuming a body. Successful response adds measurement:"http_response_headers",icmp:false,reachable:true; HTTP error statuses can still be reachable. I/O failure can return reachable:false,error with elapsedMs; cancellation, deadline/policy and validation errors reject. This is not ping or ICMP.

Jarvys.network.cancel({}) (or no args) cancels owned network jobs and returns {cancelled:<count>,rollbackConfirmed:false}. Socket/DNS teardown may outlast cancellation; no claim that remote processing stopped or transmitted bytes were recalled. No automatic retry.

### Real ICMP ping
Jarvys.network.ping({host,family?,count?,timeoutMs?,packetTimeoutMs?,intervalMs?,packetBytes?,maxOutputBytes?}). host is a normalized DNS name/IPv4/IPv6 literal, including validated scoped IPv6; not a URL, shell fragment or executable. family auto(default)|ipv4|ipv6. Integer defaults/ranges: count4/1–1000; timeoutMs10000/100–120000; packetTimeoutMs1000/1000–120000; intervalMs1000/200–60000; packetBytes56/0–65507; maxOutputBytes16384/1024–65536.

Uses fixed /system/bin/ping or /system/bin/ping6 with exact argument-vector values, merged output and LC_ALL=C; never shell execution, caller flags or HTTP fallback. packetTimeoutMs becomes whole-second packetWaitMs: the utility's final-response wait, not a guaranteed per-packet timer. External timeoutMs bounds the operation. OEM/tool availability and flags/payload bounds differ; accepted schema limits do not guarantee device support. Unsupported/denied utility reports honestly. Cancel/deadline destroy the process and reject CANCELLED/TIMEOUT, without proving network recall.

Result always includes protocol:"icmp",host,family,status,elapsedMs,exitCode (nullable),packetWaitMs,summaryParsed. Captured output adds output/outputBytes/outputTruncated/replySamplesParsed/oversizedLinesSkipped. Status is ok|no_reply|partial|unparsed|unsupported|platform_denied|dns_error|failed. Recognized summaries alone supply transmitted,received,packetLossPercent,reachable. Recognized valid RTT summary supplies latencyMs:{min,avg,max,deviation?,source:"utility_summary"}. Recognized echoes without summary can report partial/reachable:true; they invent no loss/RTT. Unparsed output proves neither reachability nor latency. Output truncation is explicit; bounded-line parsing can continue to final statistics. No physical ICMP acceptance is claimed.

### Immediate notifications
Jarvys.notifications.status({channelId?}) returns status,available,permission,runtimePermissionRequired,notificationsEnabled,channelsSupported,channelStatus,ready; optional channelId/channelImportance. Permission is not_required|not_declared|granted|not_granted. Status: ready|unavailable|permission_not_declared|permission_required|app_blocked|channel_missing|channel_blocked. Snapshot only, not display/read proof.

channel({id,name,description?,importance?}): id [A-Za-z0-9][A-Za-z0-9_.-]{0,63}, name nonblank≤160 UTF-8 bytes, description≤512. importance low|default|high (default default). Native ID is factory.<id>; API26+ creates only missing channels and preserves existing/user settings. API24/25 returns status:not_required; no simulated channel. channelCreated accompanies API26+ status.

show({id,channelId,title,body,priority?}): integer id1–2147483647, same channel grammar, nonblank title≤240 UTF-8 bytes, body≤4096 (may be empty), priority low|default|high. Valid paired Unicode; title/name no controls, body/description permit LF/TAB. App-scoped tag/id replaces its prior notification. API26+ channel and current OS permission/settings must permit it. Receipt {status:"accepted",id} confirms notify call only. Tap uses an immutable Intent to this app's verified launcher, no arbitrary URL/component/extra/actions.

cancel({id}) requests removal of this app's exact ID: {status:"cancel_requested",id}; no read/closure proof. requestPermission({}) uses the real Android 13+/target 33+ POST_NOTIFICATIONS flow when absent and rereads OS status after callback; Android can suppress the dialog or deny. Earlier APIs need no runtime grant. Neither in-process authorization nor a Jarvys host lease grants OS permission or changes system settings. That original checkpoint supported immediate notifications only. The current generic-services section adds opt-in durable alarms/reboot recovery and own-app actions, without promising physical delivery or introducing an always-running background JavaScript service.

### Platform text-to-speech
Jarvys.speech.status({}) discovers installed TTS service packages without engine initialization and reports available,busy,engineInitialized,completedChunks,error/status. Discovery/availability does not promise a usable language/voice or sound. voices({engine?}) initializes the chosen/default installed engine under live policy; returns sorted voice descriptors {name,language,quality,latency,networkRequired}, totalVoices/truncated. Voice-list data is bounded to 256 KiB; truncation is explicit.

speak({text,language?,engine?,voice?,rate?,pitch?,volume?}) accepts nonblank paired-Unicode text≤262144 UTF-8 bytes (512 KiB whole request). language is a valid BCP47 tag≤64 bytes; engine is a package name≤255; voice≤512. Defaults: platform engine/language/voice, rate 1, pitch 1, volume 1. Rate/pitch are positive finite numbers representable as positive floats; volume 0–1. Android may reject settings or fall back from requested engine. Missing voice/language/data errors stay explicit; no download/installation is implied.

One speech operation at a time. Full text is split sequentially at the engine input limit without dividing surrogate pairs, preferably at whitespace; no silent truncation. Initialization timeout 30 s; each chunk deadline 10 min. Authority is checked before initialization/configuration/submission/result and polled during work. Promise completes only after final matching engine callback; receipt status:"completed",submittedChunks,completedChunks,requestedEngine,systemDefaultEngine,selectedVoice? and completionMeans:"engine_reported_completion". It does not prove anyone heard audio. All speech disclosures include networkMayBeUsed:true,engineSelection:"android_preference_may_fallback",audibilityVerified:false; even networkRequired:false is no zero-egress guarantee.

stop({}) requests cleanup of the owned operation, returning status:"stop_requested",cancelled and emittedAudioCannotBeRecalled:true. Pause/session end/revocation triggers best-effort stop/shutdown; output/text already emitted or transmitted cannot be recalled. No microphone, speech recognition, background narration, offline-only engine promise or reusable audio file. Native/device/OEM/audibility/actual network tests and final global audit/APK remain separate, unclaimed gates.


## Selected SQLite data backup (v83)

The current catalog has 20 capabilities / 46 methods, SDK 2 and schema/protocol 1. database.backupExport and database.backupImport belong to database and also require documents. The existing six database methods remain unchanged. Both backup methods are UNAVAILABLE in preview before any database or document handle is allocated; ordinary preview database operations still work in ephemeral SQLite.

Declare the optional factory.json dataBackup:{"tables":["notes","tasks"]}. tables permits at most eight database identifiers in ascending order, without duplicates; the optional independent keys selection is documented in basics. Absence or an empty tables list disables SQLite backup; it grants nothing implicitly. Nonempty selection requires both database and documents capabilities. Signing discloses this exact configuration, and the receipt must match the embedded runtime configuration. No manifest permission, signing key or signing-identity backup format changes.

- Jarvys.database.backupExport({version,tables,filename}) exports exactly the nonempty, sorted, unique selected subset of the declared tables. version is the exact current database version (1–1000); filename uses the existing simple document filename grammar. It does not export every declared table automatically.
- Jarvys.database.backupImport({version,handle,tables,mode}) consumes one untouched document read handle. Open it with documents.open({mimeType:"application/json"}); Android presents the document picker and the user selects a source file. The file's table set must exactly match the explicit selection. The same exact database version and selected-table schema must already exist. No migration occurs.
- mode:"insertOnly" inserts rows only; any existing ID collision in any selected table rolls back the entire import. mode:"replaceSelectedTables" atomically deletes and replaces rows in selected tables only. Both preserve every unselected table. Target data or schema changes after native review produce a conflict, requiring a fresh review.

The bounded format is canonical plaintext JSON, at most 1 MiB, named jarvys-sqlite-data with formatVersion:1. It contains selected table schemas and typed rows plus database version, package and certificate labels. It contains no SQL, ZIP, signing keys, preferences, arbitrary files or document contents. It is unencrypted. Package/certificate labels are compatibility checks, not authentication of a backup's author or contents; an attacker can rewrite them. Secrets are unsupported and cannot be reliably detected. Never claim that scanning or a label makes a backup safe.

An installed generated app, current exact signed receipt and compatible pinned Jarvys host are required. Declared capabilities and the authenticated host lease authorize the native call; selected tables remain limited to dataBackup.tables. Protocol v2 rechecks the live lease before each write/commit; authenticated legacy v1 ends at its final host authorization. Export discloses that the destination provider may upload, cloud-sync or retain plaintext copies; Android presents destination selection. File selection or a matching label alone does not authenticate a backup.

Export reports bytes/hash with providerCommitConfirmed:false and closeStatus:"close_requested"; it does not prove provider durability, upload completion or recoverability. Cancellation or failure may leave an empty or partial file. Import reports the native transaction result, not physical durability or a successful independent restore test. A cancellation, lost reply or timeout may occur after commit; inspect state and never automatically retry or claim rollback. database.cancel and documents.cancel share the native backup control rate budget. Each has a bounded SDK control slot beside sensors.cancel and biometrics.cancel (16 ordinary requests plus four controls); cancellation still proves no rollback. The native backup lifetime is five minutes; SDK timeout is 310000 ms, allowing the terminal callback. Lifecycle revocation and cleanup remain authoritative; no background/resume permission is added.

## Selected string key-value backup (v84)

The catalog remains 20 capabilities with 46 methods, SDK 2 and schema/protocol 1. These APIs back up only selected strings in the generated app's Jarvys.storage key-value store. They do not back up presentation theme/orientation preferences, all app preferences, Android grants, SQLite, signing identities, arbitrary files or document contents. There is no combined SQLite/storage transaction and no new capability or Android permission.

Declare optional dataBackup:{"keys":["settings","tasks"]}. dataBackup.keys defaults empty, allows 0–32 sorted, unique names matching [a-zA-Z0-9_.:-]{1,96}, and independently requires storage+documents when nonempty. dataBackup may contain keys, legacy tables, or both, with at least one field and no extras. Legacy tables-only serialization and SQLite behavior remain unchanged; empty keys are omitted when serializing. Nonempty tables still require database+documents independently. The signed receipt must exactly match both embedded policies. An allowlist grants no approval and is not a secret detector.

- Jarvys.storage.backupExport({version:1,keys,filename}): select 1–32 sorted, unique allowed keys explicitly. Every selected key must exist; missing selected export keys reject the whole operation. filename uses the existing simple document filename grammar. Export never selects all allowed keys implicitly.
- Jarvys.storage.backupImport({version:1,keys,handle,mode}): consumes an untouched document read handle opened through documents.open({mimeType:"application/json"}); Android presents the document picker and the user selects a source file. The archive key set must exactly match the nonempty explicit selection. version is exactly the integer 1, not the SQLite database version. No migration or key renaming occurs.
- mode:"insertOnly": any existing selected key is a collision, even if its value equals the archive value. mode:"replaceSelectedKeys": writes only selected archive values, replacing selected existing keys or inserting selected absent keys. It never clears the store or deletes unselected keys. The reviewed selected-key preimage must still match before mutation; changed selected targets require fresh review. Unselected state is preserved and current whole-store quotas are rechecked.

The canonical plaintext JSON format is jarvys-preferences-data with formatVersion:1 and storageVersion:1, at most 1 MiB. It contains selected string keys/values plus package/certificate compatibility labels. Values require valid paired Unicode. Existing string limits remain: 64 KiB UTF-8 per value, 256 total store entries and 1 MiB total key/value bytes; archive escaping/metadata also count toward the independent archive limit. It is unencrypted. Labels are not authentication of the author or contents; an attacker can rewrite them. Secrets are unsupported, and arbitrary strings cannot be reliably detected as secret-free.

An installed generated app, exact current signed receipt and matching pinned Jarvys host are required. Declared capabilities, selected keys and the authenticated host lease constrain the operation. Protocol v2 rechecks the lease before each write/commit; authenticated legacy v1 ends at its final host authorization. Export discloses that the provider may upload, cloud-sync or retain plaintext copies, and Android presents destination selection. File selection and compatible labels alone do not authenticate a backup.

Import uses one bounded store replacement under the same transaction lock as ordinary storage, after validation and selected-key preimage checks. No lock spans source selection or provider I/O. A backend commit returning false or throwing is an unknown outcome: memory or disk may already have changed, no rollback is promised and no automatic replay is allowed. Cancellation, process death, reply loss or timeout can follow mutation. Inspect state before a fresh request; a receipt proves no physical durability or independent recovery.

Export returns providerCommitConfirmed:false and closeStatus:"close_requested"; provider close does not prove durable storage, cloud completion or recoverability. Failed or cancelled exports may leave empty/partial files. The operation lifetime is five minutes and both backup families use a 310000 ms SDK timeout with an ambiguity warning. documents.cancel uses the existing authenticated cancellation path for storage-only apps; database.cancel and documents.cancel share the native backup control rate budget. SDK admission reserves one slot for each of documents.cancel, database.cancel, sensors.cancel and biometrics.cancel alongside 16 ordinary requests (20 maximum). Cancellation proves neither rollback nor provider closure; cleanup may remain busy. No new storage.cancel method exists.

Both storage backup methods are UNAVAILABLE in preview before any storage snapshot, document handle or database is allocated. Ordinary preview storage remains isolated. Lifecycle revocation, temporary-handle ownership and recovery admission still apply; no background/resume permission, durable recovery guarantee or automatic retry is added. Physical Android, provider, installation/update and real-data restore gates remain open.
