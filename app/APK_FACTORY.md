# Local APK factory, runtime 1

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

Runtime 1 implements `storage`, `export`, `share`, `clipboard`, `haptics` and `device`. `runtime.info` returns non-sensitive API/capability metadata without granting any capability. Every functional method checks the generated app's declared capability before executing it. Storage is private to that installed app. Export uses the Android document picker, sharing uses a system chooser, and clipboard writes require a native confirmation dialog.

This offline template declares **zero Android permissions**. Its JavaScript cannot request camera, microphone, contacts, location, Internet, notifications, package installation or broad filesystem access. Adding any such API requires a reviewed native template update, appropriate per-app manifest selection, current Android permission checks and a new acceptance pass. Listing a desired method in JavaScript cannot add native code that is absent from DEX.

### Closed capability and manifest contract (UX42 F0a-1)

This is the first bounded foundation of F0a, not Runtime2 or the complete selective-manifest phase. Pure Java `:factory-contract` owns the six existing capabilities and ten wire methods (including ungated, non-sensitive `runtime.info`). FactorySpec, FactoryConfig, bridge validation, dispatch and runtime introspection use that catalog. The native validators and handlers remain explicit switches. SDK/schema/protocol stay v1; no new method, permission, component or network access is enabled. All 64 capability subsets have the same zero-permission launcher manifest.

`ManifestPlan` is an immutable, closed expected tree with exact names, namespaces, Android attribute resource IDs, types and values. It pins API 24/35/compile 36, the launcher, theme, backup/cleartext policy and compiled icon/backup references. Project JSON cannot supply XML, nodes or arbitrary manifest attributes. F0a-2 adds an immutable compiled construction vocabulary and deterministic typed AXML encoder, including bounded repeated permissions/features/private components/filters/package and intent queries/metadata. These extra nodes remain host construction fixtures: all six catalog contributions are empty, and production ManifestPlan rejects nonempty contributions. No project-defined nodes or new functionality are enabled. See [the construction scope and gates](../recovery/ux42-f0a2/README.md).

`ManifestAudit` is a read-only AXML decoder with no transformer helpers. TemplateApk compares the actual decoded tree, explicit parent indices and attributes to the plan before and after generation. A typed encoder now generates the output AXML; the old base-only parser remains an independent production ceiling. Ambiguous repeated-path lookups fail rather than selecting the first node. It rejects unknown/missing/duplicate production nodes and attributes, invalid namespaces, resource IDs, raw/typed disagreements, unsupported types, ambiguous element names and truncation. Resource bindings resolve by compiled type/name, retaining an unqualified XML fallback and the nodpi launcher; the backup XML must exclude exactly the existing nine domains for both cloud backup and device transfer. Template hash authentication remains mandatory. The trusted AAPT template may contain a palette-optimized PNG; generated icons retain the normalized RGB/RGBA validation.

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

Each application gets a distinct, non-exportable AndroidKeyStore signing key only after a clear approval that discloses its purpose and permanence. Repeated builds do not get new keys. Public certificate fingerprints and version continuity are retained in app-private, non-backup records. Missing keys, mismatched identities and interrupted key creation fail closed. There is no universal default/debug key, automatic rotation or private-key export.

**Clearing or uninstalling Jarvys, losing the device, or losing its Keystore can permanently prevent updates to generated apps.** Runtime 1 has no portable key backup or migration workflow. Do not use this key policy for a production distribution that requires recoverable signing without first designing and approving that separate workflow. The main Jarvys application's release key is unrelated and is never used for generated apps.

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
