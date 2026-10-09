---
id: com.jarvys.apk-factory
name: APK Factory
description: Design and build distinct offline Android applications from a freeform brief using the local WebView APK factory. Coding runtime only; inspect its actual capabilities before implementation.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk, offline]
---

# APK Factory

The native factory requires Android 8/API 26 or later for bounded project file access; generated apps support Android 7/API 24 or later. Inspect the actual availability before starting.

Create the application the user described. This is a reusable development workflow, not a fixed notes application or a collection of hardcoded screens. A notes app is only an acceptance-test fixture. Invent suitable HTML, CSS, JavaScript, interaction flows, application identity, branding and capability selection for each brief. Preserve existing application identity and data formats when the user asks for an update.

## Confirm the actual contract

1. Call apk_factory with action `inspect`. Read the returned supported schema, native capabilities, limits and template identity before authoring files. Tool declarations and results are authoritative if they differ from this guidance. If apk_factory is absent, report the missing capability; do not pretend a shell command or remote build ran.
2. Read the conversation project's current scope version and relevant files with ls/read. Use this project's relative paths with coding tools. Do not assume parent attachments or legacy chat files were copied. Ask the captain to obtain an explicitly reviewed adoption when source files are elsewhere.
3. Extract the intended audience, workflows, visual style, app name, data to keep, offline behavior and requested device features from the brief. Ask only about choices that materially affect the app. For a new app, choose an unused, valid applicationId and an appropriate name and icon; for an update, read the recorded applicationId, signing identity and increasing versionCode. Never silently replace an app with an unrelated one.
4. Compare the feature requirements with inspect's actual ceiling. The precompiled DEX supplies a fixed native runtime; per-app HTML/CSS/JS and assets can vary, but Java/Kotlin, new native libraries, permissions, services and new bridge APIs cannot be added by editing web files or the spec. Adding a native feature requires implementing and shipping a new runtime template. Describe an unsupported feature honestly before presenting a plan that depends on it.

## Design within the native ceiling

The current runtime is offline. Do not depend on remote fonts, scripts, CDNs, API calls or network login. Keep all resources local, provide accessible text and touch targets, and design loading, empty, error and recovery states for the actual app. Use standard WebView HTML/CSS/JavaScript for its unique UI and business logic.

Use a mobile viewport (`<meta name="viewport" content="width=device-width, initial-scale=1">`), responsive layouts and scrollable forms. The native runtime owns the safe area around system bars, cutouts and the keyboard; use the available WebView viewport without hardcoded Android bar heights or a second safe-area subtraction. Keep focused inputs reachable as the viewport changes. Do not remove focus on resize or promise immersive fullscreen: the system bars remain visible with native light/dark contrast.

Select only capabilities required by the brief and confirmed by inspect: storage, export, share, clipboard, haptics and device. They do not mean arbitrary filesystem access, clipboard reading, unrestricted hardware control or installed-app automation. Do not invent camera, microphone, location, Bluetooth, notifications, background execution, network or billing support. A capability in a JSON file cannot grant an Android permission or add native code.

Use the factory's supplied JavaScript SDK instead of exposing a generic native interface. Await native results and handle rejection, cancellation and unavailable bridge states. Browser-only previews cannot prove that native storage, export or sharing works. Keep developer diagnostics free of user content and credentials.

Load the runtime asset with `<script src="/factory-sdk.js"></script>`, followed by your local external JavaScript such as `<script src="app.js" defer></script>`. It is served at the trusted offline origin https://app.jarvys.invalid. Use event listeners in external files; inline scripts and inline onclick handlers are blocked by the runtime CSP. Do not replace the SDK or relax its origin/frame checks.

The frozen window.Jarvys API returns Promises:
- Jarvys.runtime.info() reads runtime metadata without a capability grant.
- Jarvys.storage.get(key) returns a string or null; set(key, value) stores a string; remove(key) deletes one value; list() returns a list of keys. Serialize your own structured data.
- Jarvys.export.text({filename, text, mimeType}) opens the document export flow; mimeType is optional. A resolved {saved:true} confirms saving, while cancellation rejects. Supported text formats are text/plain, text/markdown, application/json and text/csv.
- Jarvys.share.text({text, title}) opens a chooser; title is optional. {chooserOpened:true} does not prove that another app received or sent the text.
- Jarvys.clipboard.write(text) requires native confirmation; no clipboard read API is supplied.
- Jarvys.haptics.perform(kind) accepts tap or longPress and returns whether feedback was performed.
- Jarvys.device.info() returns platform, apiLevel, appId and targetSdk; it exposes no stable personal device identifiers.

Each API except runtime.info requires its matching declared capability. Catch structured error codes and show appropriate user-facing states without treating a denied operation as a reason to weaken security.

For persistent structured data, explicitly serialize a versioned data schema and handle missing or invalid saved values. Preserve data on ordinary app upgrades and migrate it deliberately when schema changes. Never clear storage on startup or treat a failed save as success. Device information must remain limited to the fields the runtime actually returns. Export and share are user-initiated UI operations; cancellation is a valid outcome, not a successful delivery.

## Author the project

Create distinct source files for the user's application, normally a web directory with index.html, app.css and app.js, an app-specific icon source supported by inspect, and factory.json. Do not copy the notes acceptance fixture as the generic product. Provide the functions the brief needs and avoid adding unrelated capabilities.

The factory spec uses these exact fields: schemaVersion, appId, name, versionCode, versionName, capabilities, webDir and icon. schemaVersion is 1; versionCode is a positive integer; capabilities is a list of supported capability IDs; webDir and icon are project-relative paths. appId is the Android applicationId. index.html is the web entrypoint. Choose concrete values for this app rather than leaving examples or placeholder branding. Verify the precise type, accepted icon formats, size and path limits using inspect before writing.

For artwork authored with text tools, icon may reference a JSON descriptor: schemaVersion 1, background as an opaque #RRGGBB color, and shapes as a list. Design a unique composition on a 192 by 192 canvas with up to 32 shapes. Supported shapes are circle {type,cx,cy,r,fill}, rect {type,x,y,width,height,fill}, and polygon {type,points,fill}; polygon points are [x,y] pairs, at most 32 points, with coordinates in 0..192. Every fill is opaque #RRGGBB. Geometry must fit the canvas. This is per-app artwork, not a fixed notes glyph. Rendering is reproducible with the same input/template/Android renderer; graphics-version changes may alter raster output. An existing PNG can instead be used after explicit adoption into the project. Never invent a generated-image result or a PNG file you did not create or inspect.

The packaged assets/factory-app.json is generated by the factory with schemaVersion, appId, name, entryPoint and capabilities. Do not overwrite runtime-owned configuration, SDK, DEX or manifest artifacts in the web sources. Keep private credentials, Jarvys account data, signing material and unrelated project files out of the app. Review exactly the files that will be packaged.

## Build and sign with evidence

1. Read back the spec and source files. Check references, offline dependencies, unique branding, basic JavaScript flow, escaping and the minimal capability list. Record which checks are source inspection versus executed tests.
2. Call apk_factory with action `build`, spec_path, output_path and expected_scope_version. output_path is a new project-relative unsigned APK path, for example dist/the-app-unsigned.apk. Use the freshly observed scope version. The factory transforms and packages its precompiled template locally; it does not compile arbitrary Java/Kotlin for each request. Wait for a successful result and retain its recorded path, SHA-256, app identity, version and template identity.
3. Re-read state after an error, interruption or scope-version conflict. Never blindly repeat a write or overwrite an existing output. Preserve partial/uncertain evidence and choose a fresh reviewed output path if necessary. Do not call an unsigned artifact installable or claim the build succeeded solely because a filename exists.
4. For an installable result, call apk_factory with action `sign`, input_path from the recorded build, its exact expected_sha256, a distinct output_path and the current expected_scope_version. Signing accepts only builds recorded in this conversation project and requires the actual in-app approval. Do not bypass approval, export keys, substitute a debug key, or silently create a new key for an existing applicationId.
5. Disclose key continuity: the generated app uses its per-app signing identity held by Jarvys in AndroidKeyStore. Losing that key, including through clearing Jarvys data or uninstalling Jarvys, may permanently prevent compatible updates. Backing up source files or the APK alone does not restore the signing key. Do not promise transferable or recoverable signing keys without verified support.
6. Read the sign result and report its exact artifact identity and verification result. No step here installs the APK automatically. User installation, package coexistence, update compatibility and data retention require actual Android evidence and must remain pending until observed.

## Acceptance and handoff

Validate the application against the requested workflows and its actual capabilities. For a persistence/export app, test create/edit/delete, empty data, Unicode, long text, reload/relaunch persistence, export contents and export cancellation. For a different app, derive an equally concrete checklist from its requirements rather than forcing notes behavior.

When device execution is available and authorized, test two distinct applicationIds coexisting and a higher-version update with the same signing identity preserving stored data. An emulator/host test is not evidence of a physical ARM64 device run. A successful local package transform or signature check is not evidence that the app launched, that native APIs worked, or that installation succeeded.

Return a concise summary of what the app does, source and APK references, applicationId/version, whether the APK is unsigned or signed, actual checks with results, and remaining limitations. Include unsupported requirements or missing authorization clearly. Keep logs and full implementation details out of the captain's brief summary; leave inspectable evidence in the project. Never claim builds, signatures, launches, exports, installations or successful upgrades that the corresponding tool or device result did not confirm.

## Closed v1 manifest verification (UX42 F0a-1)

The six listed capabilities remain the entire implemented ceiling. A shared immutable catalog drives spec/config/bridge/introspection; schema and SDK are still v1. All 64 selections keep zero Android permissions and only the existing launcher. Do not claim Runtime2, additional native methods, new manifest-node construction or completion of F0a.

Build/sign independently decode the actual AXML and compare every closed node/typed attribute/resource binding with the plan. Backup exclusions and every classes*.dex name/hash are checked; signing preserves all ZIP payload entries. Inspect and approval report the verified manifest and DEX inventory. Do not infer these values from desired JavaScript APIs.

Existing v1 projects/receipts are preserved. A receipted pre-UX35 artifact may retain its older window behavior, explicitly disclosed during signing. Unsupported layouts require rebuilding the original project without replacing its app ID or retained key. An installed/signed update still requires a higher version. No new key policy, network permission or installation action is included. Host AXML/aapt2/signature checks do not prove Android hardware, sandbox, Keystore, WebView or installation acceptance.
