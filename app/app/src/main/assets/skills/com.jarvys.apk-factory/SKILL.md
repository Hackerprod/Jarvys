---
id: com.jarvys.apk-factory
name: APK Factory
description: Design and build distinct offline Android applications from a freeform brief using the local WebView APK factory. Coding runtime only; inspect its actual capabilities before implementation.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk, offline]
---

# APK Factory

Factory requires Android API 26+; generated apps API 24+. Inspect availability first.

Build the user’s application, not a fixed notes application, with distinct HTML/CSS/JS, workflows, identity, branding and minimal capabilities. Preserve identity and data formats for updates.

## Confirm the actual contract

1. Call apk_factory action `inspect`; read schema, capabilities, limits and template identity before authoring. Actual tool results govern. If unavailable, report it; never invent a build.
2. Read current project scope version and files with ls/read; use project-relative paths. External sources/attachments require explicitly reviewed adoption; ask the captain.
3. Establish audience, workflows, style, name, saved data and device features. Ask only material questions. New apps need unused valid applicationIds and distinct names/icons; updates retain recorded appId/key and increase versionCode. Never replace an unrelated app.
4. Compare requirements with inspect. Web files/spec cannot add Java/Kotlin, libraries, permissions, services or bridge APIs to precompiled DEX. Unsupported native features need a reviewed new template; disclose this before planning around them.

## Design within the native ceiling

Keep all WebView resources offline: no CDNs, remote fonts/scripts, API calls or login. Provide accessible text/touch targets and loading, empty, error and recovery states.

Use `<meta name="viewport" content="width=device-width, initial-scale=1">`, responsive layouts and scrollable forms. Native code owns system-bar/cutout/keyboard safe areas: never hardcode bar padding or subtract twice. Keep focused inputs reachable on resize. Bars stay visible with native light/dark contrast; no immersive fullscreen.

Select only capabilities required by the brief and confirmed by inspect: storage, export, share, clipboard, haptics, device, documents and photos. They do not mean arbitrary filesystem access, clipboard reading, unrestricted hardware control or installed-app automation. Photos only supports the bounded picker/system-camera contract below. Do not invent direct camera, microphone, location, Bluetooth, notifications, background execution, network or billing support. A capability in a JSON file cannot grant an Android permission or add native code.

Use only the supplied SDK; await results and handle rejection/cancellation/unavailability. Browser previews cannot prove native effects. Never log user content or credentials.

Load the runtime asset with `<script src="/factory-sdk.js"></script>`, followed by your local external JavaScript such as `<script src="app.js" defer></script>`. It is served at the trusted offline origin https://app.jarvys.invalid. Use event listeners in external files; inline scripts and inline onclick handlers are blocked by the runtime CSP. Do not replace the SDK or relax its origin/frame checks.

The frozen window.Jarvys API returns Promises:
- Jarvys.runtime.info() reads runtime metadata without a capability grant.
- Jarvys.storage.get(key) returns a string or null; set(key, value) stores a string; remove(key) deletes one value; list() returns a list of keys. Serialize your own structured data.
- Jarvys.export.text({filename, text, mimeType}) opens the document export flow; mimeType is optional. A resolved {saved:true} confirms saving, while cancellation rejects. Supported text formats are text/plain, text/markdown, application/json and text/csv.
- Jarvys.share.text({text, title}) opens a chooser; title is optional. {chooserOpened:true} does not prove that another app received or sent the text.
- Jarvys.clipboard.write(text) requires native confirmation; no clipboard read API is supplied.
- Jarvys.haptics.perform(kind) accepts tap or longPress and returns whether feedback was performed.
- Jarvys.device.info() returns platform, apiLevel, appId and targetSdk; it exposes no stable personal device identifiers.

Except runtime.info, each API requires its declared capability. Show structured failures; never weaken security after denial.

Serialize versioned storage data; handle missing/invalid values and deliberate upgrade migrations. Never clear storage on startup or claim a failed save succeeded. Export/share require user initiation; cancellation is not delivery.

## Temporary binary documents (v67)
SDK 2 adds Promise methods under Jarvys.documents; declare documents. open({mimeType}) resolves {handle,mode:"read",expiresAfterMs:300000,maximumBytes:16777216,providerCommitConfirmed:false}; create({filename,mimeType}) returns the same with mode:"write". MIME is required, <=127 characters, one type without parameters; open allows */* or image/*, create requires a concrete type. filename matches [a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}, without .. or final dot.

read({handle,offset,length}) returns {data,offset,nextOffset,eof}; write({handle,offset,data}) returns {offset,nextOffset,bytesWritten,providerCommitConfirmed:false}. data is canonical padded standard base64, not a data URL; decoded chunks and length are 1..32768 bytes. Start integer offset at 0, then use nextOffset sequentially, never parallel or seek. Read EOF data may be empty; a short read alone is not EOF. close({handle}) returns {status:"close_requested",providerCommitConfirmed:false}; cancel() or cancel({}) returns {cancelled:true,rollbackConfirmed:false,pickerMayRemainOpen}.

Read with open({mimeType:"*/*"}) then read({handle,offset:0,length:32768}); advance to nextOffset until eof/quota, then close. Shrink to remaining quota; never probe beyond it. Write canonical base64 using write({handle,offset:0,data:"AAEC"}); close in finally. Catch errors in UI.

Limits: 16 MiB/handle, 32 MiB cumulative/page-session, four admission slots including cleanup, five-minute expiry. Cancel (native cancelAll) and close preserve quota. No paths/URIs or persistent grants; never save handles. Background/page reset/rotation revokes them. Installed selection is effectively one at a time: opening next closes previous; BUSY can persist across runtime instances during provider cleanup. Handle errors: INVALID_HANDLE, WRONG_MODE, HANDLE_BUSY, INVALID_OFFSET, INVALID_CHUNK, DOCUMENT_QUOTA, HANDLE_LIMIT, SESSION_REVOKED, DOCUMENT_IO. Also handle INVALID_ARGUMENT/INVALID_REQUEST, CAPABILITY_DENIED, UNAVAILABLE, BUSY, CANCELLED, PERMISSION_DENIED, RATE_LIMITED, TIMEOUT, NATIVE_ERROR. Never blindly retry writes.

A compatible build-pinned Jarvys must be installed. Its exported native broker verifies caller installed certificate/hash/version against exact latest signed identity/scope evidence; both host packages installed blocks access. Signing newer makes the prior installed release unavailable until updated to that exact APK. Human review, Android picker and Use this document are mandatory; never automate them. Automation stays blocked. Recovery in Settings → Factory documents requires human acknowledgment of closing the old picker/task, explicitly user-reported, not OS proof. Outcome remains unknown; authority is never restored.

Cancellation cannot roll back a write; create may leave empty/partial files even without a returned handle. Cloud providers may transfer data remotely. close_requested does not prove durable commit/upload. Other F1/F2/F3 families stay closed; physical acceptance remains pending.

Jarvys.share.file({handle,filename,mimeType}) requires documents+share and an untouched read handle. It consumes a complete 1-byte–8 MiB snapshot, charging existing quotas; reopen after failure. No raw bytes/URI/path/target. Concrete MIME and simple filename rules above apply. The pinned authenticated host stages the copy in 32 KiB native chunks; human-only review opens the chooser. Five-minute expiry; native close/recovery keeps automation blocked until acknowledgment of closing the chooser/recipient task. {chooserOpened,deliveryConfirmed:false} never proves delivery. Cancel/expiry cannot recall copies/open descriptors; recipients may upload. Preview is unavailable.

## Photos (v69)
Jarvys.photos.pick({}) / capture({}) require photos+documents and the same exact-latest authenticated host. Native human launch/Use only; never automate. Return opaque read handles for documents.read/close/share.file; 8 MiB JPEG/PNG, <=4096 per side/12 MP, five minutes. Snapshot acquisition also charges cumulative quota. Original metadata, including location if present, stays intact. Camera uses one write-only bounded pipe; seeking/reopen-dependent cameras fail, never thumbnail fallback. No gallery save/autosend; external camera may retain copies. Preview unavailable. Close interrupted external UI yourself and use Settings → Factory photos recovery. API24–29: provider FDs must be regular.

## Author the project

Create factory.json, index.html/app.css/app.js and a distinct icon; implement the brief with minimal capabilities.

factory.json exact fields: schemaVersion (1), appId (Android applicationId), name, versionCode (positive integer), versionName, capabilities (supported IDs), webDir and icon (project-relative paths). index.html is the entrypoint. Use concrete identity/branding; inspect types, icon formats, size/path limits first.

Icon JSON: schemaVersion 1, opaque #RRGGBB background, 1–32 shapes on 192×192: circle {type,cx,cy,r,fill}, rect {type,x,y,width,height,fill}, polygon {type,points,fill} with 3–32 [x,y] pairs. Geometry must fit 0..192; fills opaque #RRGGBB. Raster bytes vary by renderer. Only use inspected PNGs; never invent files.

Factory-owned assets/factory-app.json contains schemaVersion, appId, name, entryPoint, capabilities and, for documents, a build-pinned documentBroker. Do not overwrite runtime-owned configuration, SDK, DEX or manifest artifacts in the web sources. Keep private credentials, Jarvys account data, signing material and unrelated project files out of the app. Review exactly the files that will be packaged.

## Build and sign with evidence

1. Read back the spec and source files. Check references, offline dependencies, unique branding, basic JavaScript flow, escaping and the minimal capability list. Record which checks are source inspection versus executed tests.
2. Call apk_factory action `build` with spec_path, output_path and freshly read expected_scope_version. Use a new project-relative output, e.g. dist/the-app-unsigned.apk. The factory packages its template locally, without compiling per-app Java/Kotlin. Retain successful path/hash/app/version/template evidence.
3. After errors, interruptions or scope conflicts, re-read state; never blindly repeat writes/overwrite outputs. Preserve uncertain evidence and choose a fresh output if needed. A filename alone proves neither build success nor installability.
4. Call action `sign` with recorded input_path, exact expected_sha256, distinct output_path and current expected_scope_version. Only same-project receipted builds qualify. Actual in-app approval is mandatory; never bypass it, export keys, substitute debug keys or silently replace an existing identity.
5. Disclose the selected key policy. Existing non-exportable AndroidKeyStore identities cannot be backed up or converted; losing them may permanently prevent updates. For a NEW recoverable identity, the user must first open Settings → Factory identities, explicitly create it and save a passphrase-encrypted backup. The native screen also handles export/import. Never request, receive or write a passphrase/private key in chat, tools, project files or JavaScript. Source/APK backups do not restore signing keys.
6. Read the sign result and report its exact artifact identity and verification result. No step here installs the APK automatically. User installation, package coexistence, update compatibility and data retention require actual Android evidence and must remain pending until observed.

## Acceptance and handoff

Test requested workflows, persistence/export mutations, Unicode, boundaries, reload and cancellation. Authorized device checks must cover coexisting appIds and same-key higher-version updates preserving data. Host tests do not prove physical ARM64 behavior.

Return source/APK references, appId/version, signature status, checks and blockers. Evidence is mandatory.

## Closed manifest verification

Eight capabilities use SDK 2/schema 1. Generated apps have zero permissions and only the launcher; documents adds the exact two Jarvys host-package queries. F0a fixture nodes are not JS features. Build/sign verify closed AXML/resources/DEX and unchanged signed payload; inspect resourceBindings/componentDex. Approval compares the latest signed scope, disclosing unknown baselines. Preserve template authentication. Signed does not mean published, installed or Android-tested. Pre-UX35 receipts retain old window limits; rebuild unsupported layouts with retained appId/key and higher version.

## Recoverable identity workflow (UX42 F0b)

Only native Factory identities handles secrets. Never bypass it or change legacy/conflicting certificates. User-selected encrypted export is not proof of restore. Import authenticates encryption/key/certificate/appId, merges maximum version floors and marks release history unknown. Signing stays blocked until native review resolves the floor; user declarations cannot prove the latest release. Old backups omit later releases/receipts. Rebuild with the same appId/key and higher version. Disclose passphrase/sole-backup loss; distinguish key backup, project export and app data. Independent-device restoration/data-preserving update still need physical tests.


## Factory preview/tests (F0c)
Use action preview, preview_status or test with exactly input_path, expected_sha256 and expected_scope_version from a current same-project build. Preview opens a private functional harness: isolated native test storage, simulated export/share/clipboard/haptics, Reset and Close. documents/photos/share.file return UNAVAILABLE; no fake handles/picker. Supported isolated WebView profiles are required; profiles may use disk and cleanup is best effort. It runs under Jarvys, never the installed app identity. preview_status returns bounded observed events; launch/page callbacks do not prove rendering or app workflows. test executes fixed synthetic shared-handler/validator assertions, not project JavaScript or browser/device tests. Inspect each result; no installation, hardware, persistence or zero-network claims. Scope changes, active-run cancellation and native closure revoke the preview. Rebuild old-template artifacts; preserve real-device acceptance gates.

## Explicit installation (F0c)
Only after the user requests installation, use install with exactly input_path, expected_sha256 and expected_scope_version for a completed signed receipt. Full opens human-only native review bound to app/version/hash/certificate/receipt, then Android consent. Signing approval never authorizes installation. Never automate either screen, grant unknown-source permission, accept security warnings or retry a commit. Play returns unavailable and preserves the signed artifact without a bypass. install_status and install_cancel use the same exact fields; status/cancel do not require the APK bytes to remain readable. Settings → Factory installations provides native recovery if project access changes. Backgrounding revokes uncommitted approval; restart cannot replay it. Only authenticated system success confirms the session; missing sessions, opened UI and cancellation after commit do not prove success or rollback. Unknown outcomes keep device automation blocked until native recovery. Device behavior and update/data retention still need actual acceptance.

Missing install permission opens protected native review; manual grants never resume it. Native-close then request again. Damaged-record recovery is human-only, cancels owned installer sessions and preserves unknown outcomes; never erase app data or signing identities.
