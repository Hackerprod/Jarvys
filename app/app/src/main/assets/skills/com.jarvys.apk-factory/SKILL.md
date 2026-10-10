---
id: com.jarvys.apk-factory
name: APK Factory
description: Build distinct offline Android apps with the local WebView APK factory. Coding only; inspect capabilities first.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk, offline]
---

# APK Factory

Factory requires Android API 26+; generated apps API 24+. Inspect availability first.

Build distinct HTML/CSS/JS, not a fixed notes application. Preserve identity/data formats for updates.

## Confirm the actual contract

1. Call apk_factory `inspect`; read schema, capabilities, limits and template identity. Actual results govern; report unavailable honestly.
2. Read project scope version/files; use relative paths. External sources/attachments need reviewed adoption; ask the captain.
3. Establish audience, workflows, style, identity, data and device features. Clarify material gaps. New apps need unique applicationIds/names/icons; updates retain appId/key and raise versionCode. Never replace unrelated apps.
4. Compare needs with inspect. Web/spec cannot add native code/libraries/permissions/services/APIs to precompiled DEX. Disclose unsupported features; they need a reviewed new template.

## Design within the native ceiling

Keep all WebView resources offline: no CDNs, remote fonts/scripts, API calls or login. Provide accessible text/touch targets and loading, empty, error and recovery states.

Use responsive layouts, scrollable forms and `<meta name="viewport" content="width=device-width, initial-scale=1">`. Native owns bar/cutout/keyboard insets; never subtract twice. Keep focused inputs reachable; bars stay visible.

Select minimal capabilities: storage, export, share, clipboard, haptics, device, documents, photos, audio, browser. Only documented contracts exist. No arbitrary files, clipboard reading, direct camera/microphone, location, Bluetooth, notifications, background execution, network or billing. JSON cannot add permissions/native code.

Use the SDK; await results and handle rejection. No user-content/credential logs; previews cannot prove native effects.

Load `<script src="/factory-sdk.js"></script>` before local external JS at https://app.jarvys.invalid. CSP blocks inline scripts/handlers. Never replace the SDK or weaken origin/frame checks.

The frozen window.Jarvys API returns Promises:
- Jarvys.runtime.info() reads runtime metadata without a capability grant.
- Jarvys.storage.get(key) returns a string or null; set(key, value) stores a string; remove(key) deletes one value; list() returns a list of keys. Serialize your own structured data.
- Jarvys.export.text({filename, text, mimeType}) opens the document export flow; mimeType is optional. A resolved {saved:true} confirms saving, while cancellation rejects. Supported text formats are text/plain, text/markdown, application/json and text/csv.
- Jarvys.share.text({text, title}) opens a chooser; title is optional. {chooserOpened:true} does not prove that another app received or sent the text.
- Jarvys.clipboard.write(text) requires native confirmation; no clipboard read API is supplied.
- Jarvys.haptics.perform(kind) accepts tap or longPress and returns whether feedback was performed.
- Jarvys.device.info() returns platform, apiLevel, appId and targetSdk; it exposes no stable personal device identifiers.

Except runtime.info, each API requires its declared capability. Show structured failures; never weaken security after denial.

Version storage; handle invalid/missing values and upgrades. Never clear it on startup or claim failed saves succeeded. Export/share need user initiation; cancellation is not delivery.

## Temporary binary documents (v67)
SDK 2 adds Promise methods under Jarvys.documents; declare documents. open({mimeType}) resolves {handle,mode:"read",expiresAfterMs:300000,maximumBytes:16777216,providerCommitConfirmed:false}; create({filename,mimeType}) returns the same with mode:"write". MIME is required, <=127 characters, one type without parameters; open allows */* or image/*, create requires a concrete type. filename matches [a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}, without .. or final dot.

read({handle,offset,length}) returns {data,offset,nextOffset,eof}; write({handle,offset,data}) returns {offset,nextOffset,bytesWritten,providerCommitConfirmed:false}. data is canonical padded standard base64, not a data URL; decoded chunks and length are 1..32768 bytes. Start integer offset at 0, then use nextOffset sequentially, never parallel or seek. Read EOF data may be empty; a short read alone is not EOF. close({handle}) returns {status:"close_requested",providerCommitConfirmed:false}; cancel() or cancel({}) returns {cancelled:true,rollbackConfirmed:false,pickerMayRemainOpen}.

Read open({mimeType:"*/*"}), then read({handle,offset:0,length:32768}); use nextOffset to eof/quota, then close. Shrink to remaining quota. Write write({handle,offset:0,data:"AAEC"}); close in finally and display failures.

Limits: 16 MiB/handle, 32 MiB cumulative/page-session, four admission slots including cleanup, five-minute expiry. Cancel (native cancelAll) and close preserve quota. No paths/URIs or persistent grants; never save handles. Background/page reset/rotation revokes them. Installed selection is effectively one at a time: opening next closes previous; BUSY can persist across runtime instances during provider cleanup. Handle errors: INVALID_HANDLE, WRONG_MODE, HANDLE_BUSY, INVALID_OFFSET, INVALID_CHUNK, DOCUMENT_QUOTA, HANDLE_LIMIT, SESSION_REVOKED, DOCUMENT_IO. Also handle INVALID_ARGUMENT/INVALID_REQUEST, CAPABILITY_DENIED, UNAVAILABLE, BUSY, CANCELLED, PERMISSION_DENIED, RATE_LIMITED, TIMEOUT, NATIVE_ERROR. Never blindly retry writes.

A compatible build-pinned Jarvys must be installed. Its exported native broker verifies caller installed certificate/hash/version against exact latest signed identity/scope evidence; both host packages installed blocks access. Signing newer makes the prior installed release unavailable until updated to that exact APK. Human review, Android picker and Use this document are mandatory; never automate them. Automation stays blocked. Recovery in Settings → Factory documents requires human acknowledgment of closing the old picker/task, explicitly user-reported, not OS proof. Outcome remains unknown; authority is never restored.

Cancellation cannot roll back a write; create may leave empty/partial files even without a returned handle. Cloud providers may transfer data remotely. close_requested does not prove durable commit/upload. Other F1/F2/F3 families stay closed; physical acceptance remains pending.

Jarvys.share.file({handle,filename,mimeType}) requires documents+share and an untouched read handle. It consumes a complete 1-byte–8 MiB snapshot, charging existing quotas; reopen after failure. No raw bytes/URI/path/target. Concrete MIME and simple filename rules above apply. The pinned authenticated host stages the copy in 32 KiB native chunks; human-only review opens the chooser. Five-minute expiry; native close/recovery keeps automation blocked until acknowledgment of closing the chooser/recipient task. {chooserOpened,deliveryConfirmed:false} never proves delivery. Cancel/expiry cannot recall copies/open descriptors; recipients may upload. Preview is unavailable.

## Photos (v69)
Jarvys.photos.pick({}) / capture({}) require photos+documents and the same exact-latest authenticated host. Native human launch/Use only; never automate. Return opaque read handles for documents.read/close/share.file; 8 MiB JPEG/PNG, <=4096 per side/12 MP, five minutes. Snapshot acquisition also charges cumulative quota. Original metadata, including location if present, stays intact. Camera uses one write-only bounded pipe; seeking/reopen-dependent cameras fail, never thumbnail fallback. No gallery save/autosend; external camera may retain copies. Preview unavailable. Close interrupted external UI yourself and use Settings → Factory photos recovery. API24–29: provider FDs must be regular.

Jarvys.audio.play({handle}) requires audio+documents; consumes an untouched read handle (6 MiB, charged EOF/quota). Canonical 44-byte-header PCM16 WAV only: mono/stereo, 8–48 kHz, whole frames, <=30 s. Authenticated host reviews bytes; human Play once, current Android output/volume. No URI/path, codec, TTS, network engine, looping, resume or background audio. Native Close/recovery retains automation protection; focus/lifecycle/route loss stops. documents.cancel requests revocation, not confirmed stop. {playbackAttempted,audibilityConfirmed:false} never proves hearing. Document selection may use cloud providers. Preview unavailable.


## HTTPS browser (v71)
Jarvys.browser.open({url}) needs only browser and the pinned authenticated host; no documents grant. Exact ASCII URL <=2048: lowercase https, lowercase DNS host (>=2 labels, <=63/label, <=253 total), optional :443. No userinfo/fragment, local/numeric host, trailing dot, controls, whitespace, backslash or malformed escapes; escaped controls/space/DEL/backslash also fail. No normalization or secret detection: never include passwords/tokens. Human native review displays full URL/host and selects a browser; never automate. Exact URL/path/query goes to browser/site; cookies/accounts/history/sync may apply. DNS/redirects/resources may reach other destinations, HTTP or apps outside Jarvys control. No Jarvys fetch or generated Internet permission. One-shot durable guard, five-minute expiry, no retries; close browser manually then native review, or Settings → Factory browser recovery. Cancellation cannot retract/close/undo. {launchRequested,pageLoadConfirmed:false} means only dispatch acceptance, never rendered page/delivery. Preview unavailable; documents.cancel is unrelated. Other typed actions/TTS/voice remain unsupported.

## Author the project

Create factory.json, HTML/CSS/JS and a distinct icon; use minimal capabilities.

factory.json exact fields: schemaVersion (1), appId (Android applicationId), name, versionCode (positive integer), versionName, capabilities (supported IDs), webDir and icon (project-relative paths). index.html is the entrypoint. Use concrete identity/branding; inspect types, icon formats, size/path limits first.

Icon JSON: schemaVersion 1, opaque #RRGGBB background, 1–32 shapes on 192×192: circle {type,cx,cy,r,fill}, rect {type,x,y,width,height,fill}, polygon {type,points,fill} with 3–32 [x,y] pairs. Geometry must fit 0..192; fills opaque #RRGGBB. Raster bytes vary by renderer. Only use inspected PNGs; never invent files.

Factory-owned assets/factory-app.json holds schemaVersion/appId/name/entryPoint/capabilities and documentBroker for documents or browser. Never overwrite runtime config/SDK/DEX/manifest. Exclude credentials, account/signing data and unrelated files; review exact packaged inputs.

## Build and sign with evidence

1. Read spec/sources back. Check references, offline dependencies, branding, JS flow, escaping and minimal capabilities. Distinguish inspection from executed tests.
2. Call `build` with spec_path, output_path and fresh expected_scope_version. Use a new relative output, e.g. dist/app-unsigned.apk. Local template packaging compiles no per-app Java/Kotlin. Retain path/hash/app/version/template evidence.
3. After errors/interruption/conflicts, reread state; never blindly repeat writes or overwrite outputs. Preserve uncertain evidence, use fresh output if needed. Filenames prove neither build nor installability.
4. Call `sign` with input_path, exact expected_sha256, distinct output_path and current expected_scope_version. Only same-project receipts qualify. Require in-app approval; never bypass, export keys, use debug keys or replace identity.
5. Disclose key policy: existing non-exportable AndroidKeyStore keys cannot be backed up/converted; losing them may prevent updates. For NEW recoverable keys use native Settings → Factory identities and passphrase-encrypted backup/export/import. Never receive/write passphrases or private keys in chat/tools/projects/JS. Source/APK backups do not restore keys.
6. Report exact signed artifact/verification. No step here installs the APK. Installation, coexistence, updates and data retention need real Android evidence.

## Acceptance and handoff

Test workflows, persistence/export, Unicode, boundaries, reload/cancel. Authorized device tests must check coexistence and same-key higher-version data-preserving updates. Host tests do not prove ARM64 behavior.

Return source/APK, identity, signature, checks and blockers with evidence.

## Closed manifest verification

Ten capabilities use SDK 2/schema 1. Generated apps have zero permissions and only the launcher; documents/browser share two exact host queries. F0a fixture nodes are not JS features. Build/sign verify closed AXML/resources/DEX and unchanged signed payload; inspect resourceBindings/componentDex. Approval compares the latest signed scope, disclosing unknown baselines. Preserve template authentication. Signed does not mean published, installed or Android-tested. Pre-UX35 receipts retain old window limits; rebuild unsupported layouts with retained appId/key and higher version.

## Recoverable identity workflow (UX42 F0b)

Only native Factory identities handles secrets. Never bypass it or change legacy/conflicting certificates. User-selected encrypted export is not proof of restore. Import authenticates encryption/key/certificate/appId, merges maximum version floors and marks release history unknown. Signing stays blocked until native review resolves the floor; user declarations cannot prove the latest release. Old backups omit later releases/receipts. Rebuild with the same appId/key and higher version. Disclose passphrase/sole-backup loss; distinguish key backup, project export and app data. Independent-device restoration/data-preserving update still need physical tests.


## Factory preview/tests (F0c)
Use action preview, preview_status or test with exactly input_path, expected_sha256 and expected_scope_version from a current same-project build. Preview opens a private functional harness: isolated native test storage, simulated export/share/clipboard/haptics, Reset and Close. documents/photos/audio/browser/share.file return UNAVAILABLE; no fake handles/picker. Supported isolated WebView profiles are required; profiles may use disk and cleanup is best effort. It runs under Jarvys, never the installed app identity. preview_status returns bounded observed events; launch/page callbacks do not prove rendering or app workflows. test executes fixed synthetic shared-handler/validator assertions, not project JavaScript or browser/device tests. Inspect each result; no installation, hardware, persistence or zero-network claims. Scope changes, active-run cancellation and native closure revoke the preview. Rebuild old-template artifacts; preserve real-device acceptance gates.

## Explicit installation (F0c)
Only after the user requests installation, use install with exactly input_path, expected_sha256 and expected_scope_version for a completed signed receipt. Full opens human-only native review bound to app/version/hash/certificate/receipt, then Android consent. Signing approval never authorizes installation. Never automate either screen, grant unknown-source permission, accept security warnings or retry a commit. Play returns unavailable and preserves the signed artifact without a bypass. install_status and install_cancel use the same exact fields; status/cancel do not require the APK bytes to remain readable. Settings → Factory installations provides native recovery if project access changes. Backgrounding revokes uncommitted approval; restart cannot replay it. Only authenticated system success confirms the session; missing sessions, opened UI and cancellation after commit do not prove success or rollback. Unknown outcomes keep device automation blocked until native recovery. Device behavior and update/data retention still need actual acceptance.

Missing install permission opens protected native review; manual grants never resume it. Native-close then request again. Damaged-record recovery is human-only, cancels owned installer sessions and preserves unknown outcomes; never erase app data or signing identities.
