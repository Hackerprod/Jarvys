---
id: com.jarvys.apk-factory
name: APK Factory
description: Build distinct offline Android apps. Coding only; inspect capabilities first.
version: 1
allowed-tools: [ls, read, write, edit, coding_grep, coding_glob, coding_patch, coding_adopt, read_skill, apk_factory, board_read, board_post, msg_send, ask_chief, report_done]
tags: [android, coding, apk, offline]
---

# APK Factory

Factory API26+; generated apps API24+. Inspect availability.

Build distinct HTML/CSS/JS, not a fixed notes application. Updates preserve identity/data formats.

## Contract

1. apk_factory `inspect`: check schema/capabilities/limits/template. Actual results govern; disclose unavailable.
2. Read scope version/files; relative paths only. Ask the captain for reviewed adoption of external sources/attachments.
3. Establish audience/workflows/style/identity/data/features; clarify gaps. New apps need unique applicationIds/names/icons; updates retain appId/key and raise versionCode. Never replace unrelated apps.
4. Compare needs with inspect. Web/spec cannot add code/libraries/permissions/services/APIs to precompiled DEX. Unsupported features need a reviewed template.

## Native ceiling

Offline WebView only: no CDNs, remote fonts/scripts, API calls or login. Include accessible text/targets and loading/empty/error/recovery states.

Use responsive layouts/scrollable forms and `<meta name="viewport" content="width=device-width, initial-scale=1">`. Native owns bar/cutout/keyboard insets; never subtract twice. Keep inputs reachable, bars visible.

Select minimal capabilities: storage, export, share, clipboard, haptics, device, documents, photos, audio, browser, maps, phone. Documented contracts only: no arbitrary files, clipboard read, direct camera/microphone, location, Bluetooth, notifications, background execution, network or billing. JSON adds no permissions/native code.

Await SDK results; handle rejection. No user/credential logs; preview proves no native effect.

Load `<script src="/factory-sdk.js"></script>` before local external JS at https://app.jarvys.invalid. CSP blocks inline scripts/handlers. Never replace the SDK or weaken origin/frame checks.

Frozen window.Jarvys returns Promises:
- Jarvys.runtime.info(): metadata, no capability grant.
- Jarvys.storage.get(key): string/null; set(key,value): store string; remove(key): delete; list(): keys. Serialize structured data.
- Jarvys.export.text({filename,text,mimeType}): document export. Optional MIME: text/plain, text/markdown, application/json, text/csv. {saved:true} confirms saving; cancellation rejects.
- Jarvys.share.text({text,title}): chooser, optional title. {chooserOpened:true} does not prove receipt/sending.
- Jarvys.clipboard.write(text) requires native confirmation; no clipboard read API is supplied.
- Jarvys.haptics.perform(kind) accepts tap or longPress and returns whether feedback was performed.
- Jarvys.device.info(): platform/apiLevel/appId/targetSdk; no stable personal identifiers.

Except runtime.info, APIs require declared capabilities. Show failures; never weaken security.

Version storage; handle missing/invalid values/upgrades. Never clear on startup or claim failed saves succeeded. User-initiated export/share; cancellation is not delivery.

## Binary documents (v67)
SDK 2 adds Promise methods under Jarvys.documents; declare documents. open({mimeType}) resolves {handle,mode:"read",expiresAfterMs:300000,maximumBytes:16777216,providerCommitConfirmed:false}; create({filename,mimeType}) returns the same with mode:"write". MIME is required, <=127 characters, one type without parameters; open allows */* or image/*, create requires a concrete type. filename matches [a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}, without .. or final dot.

read({handle,offset,length}) returns {data,offset,nextOffset,eof}; write({handle,offset,data}) returns {offset,nextOffset,bytesWritten,providerCommitConfirmed:false}. data is canonical padded standard base64, not a data URL; decoded chunks and length are 1..32768 bytes. Start integer offset at 0, then use nextOffset sequentially, never parallel or seek. Read EOF data may be empty; a short read alone is not EOF. close({handle}) returns {status:"close_requested",providerCommitConfirmed:false}; cancel() or cancel({}) returns {cancelled:true,rollbackConfirmed:false,pickerMayRemainOpen}.

Read: open({mimeType:"*/*"}), read({handle,offset:0,length:32768}), nextOffset to eof/quota, then close. Shrink chunks to remaining quota. Write: write({handle,offset:0,data:"AAEC"}); close in finally; show failures.

Limits: 16 MiB/handle, 32 MiB cumulative/page-session, four admission slots including cleanup, five-minute expiry. Cancel (native cancelAll) and close preserve quota. No paths/URIs/persistent grants; never save handles. Background/page reset/rotation revokes them. Installed selection is effectively one at a time: opening next closes previous; BUSY can persist across runtime instances during provider cleanup. Handle errors: INVALID_HANDLE, WRONG_MODE, HANDLE_BUSY, INVALID_OFFSET, INVALID_CHUNK, DOCUMENT_QUOTA, HANDLE_LIMIT, SESSION_REVOKED, DOCUMENT_IO. Also handle INVALID_ARGUMENT/INVALID_REQUEST, CAPABILITY_DENIED, UNAVAILABLE, BUSY, CANCELLED, PERMISSION_DENIED, RATE_LIMITED, TIMEOUT, NATIVE_ERROR. Never blindly retry writes.

A compatible build-pinned Jarvys must be installed. Its exported native broker verifies caller installed certificate/hash/version against exact latest signed identity/scope evidence; both host packages installed blocks access. Signing newer makes the prior installed release unavailable until updated to that exact APK. Human review, Android picker and Use this document are mandatory; never automate them. Automation stays blocked. Recovery in Settings → Factory documents requires human acknowledgment of closing the old picker/task, explicitly user-reported, not OS proof. Outcome remains unknown; authority is never restored.

Cancellation cannot roll back a write; create may leave empty/partial files even without a returned handle. Cloud providers may transfer data remotely. close_requested does not prove durable commit/upload. Other F1/F2/F3 families stay closed; physical acceptance remains pending.

Jarvys.share.file({handle,filename,mimeType}) requires documents+share and an untouched read handle. It consumes a complete 1-byte–8 MiB snapshot, charging existing quotas; reopen after failure. No raw bytes/URI/path/target. Concrete MIME and simple filename rules above apply. The pinned authenticated host stages the copy in 32 KiB native chunks; human-only review opens the chooser. Five-minute expiry; native close/recovery keeps automation blocked until acknowledgment of closing the chooser/recipient task. {chooserOpened,deliveryConfirmed:false} never proves delivery. Cancel/expiry cannot recall copies/open descriptors; recipients may upload. Preview is unavailable.

## Photos (v69)
Jarvys.photos.pick({}) / capture({}) require photos+documents and the same exact-latest authenticated host. Native human launch/Use only; never automate. Return opaque read handles for documents.read/close/share.file; 8 MiB JPEG/PNG, <=4096 per side/12 MP, five minutes. Snapshot acquisition also charges cumulative quota. Original metadata, including location if present, stays intact. Camera uses one write-only bounded pipe; seeking/reopen-dependent cameras fail, never thumbnail fallback. No gallery save/autosend; external camera may retain copies. Preview unavailable. Close interrupted external UI yourself and use Settings → Factory photos recovery. API24–29: provider FDs must be regular.

Jarvys.audio.play({handle}) requires audio+documents; consumes an untouched read handle (6 MiB, charged EOF/quota). Canonical 44-byte-header PCM16 WAV only: mono/stereo, 8–48 kHz, whole frames, <=30 s. Authenticated host reviews bytes; human Play once, current Android output/volume. No URI/path, codec, TTS, network engine, looping, resume or background audio. Native Close/recovery retains automation protection; focus/lifecycle/route loss stops. documents.cancel requests revocation, not confirmed stop. {playbackAttempted,audibilityConfirmed:false} never proves hearing. Document selection may use cloud providers. Preview unavailable.

## HTTPS browser (v71)
Jarvys.browser.open({url}) needs only browser and the pinned authenticated host; no documents grant. Exact ASCII URL <=2048: lowercase https, lowercase DNS host (>=2 labels, <=63/label, <=253 total), optional :443. No userinfo/fragment, local/numeric host, trailing dot, controls, whitespace, backslash or malformed escapes; escaped controls/space/DEL/backslash also fail. No normalization or secret detection: never include passwords/tokens. Human-only review: full URL/host, browser selection; never automate. Exact URL/path/query reaches browser/site; cookies/accounts/history/sync may apply. DNS/redirects/resources may reach other destinations, HTTP or apps outside Jarvys control. No Jarvys fetch or generated Internet permission. Durable one-shot guard, five minutes, no retries. Manually close browser then review, or Settings → Factory browser recovery. Cancellation cannot retract/close/undo. {launchRequested,pageLoadConfirmed:false} means only dispatch acceptance, never rendered page/delivery. Preview unavailable; documents.cancel is unrelated. Editors/TTS/voice remain unsupported.

## Maps/dialer (v72)
Jarvys.maps.open({latitude,longitude}) requires maps: finite numbers [-90,90]/[-180,180], canonical plain decimals. Or open({query}): nonblank paired Unicode, 1–256 codepoints/1024 UTF8 bytes, no control/format/line/paragraph characters; encoded once. Jarvys.phone.dial({number}) requires phone: optional + and 1–15 ASCII digits unchanged; no USSD/extensions. Exact keys only. Both need pinned host, no documents grant. Human-only full data/recipient review; never automate. Only geo ACTION_VIEW or tel ACTION_DIAL; no GPS read/autocall/arbitrary URI/intent. Recipients receive data and may use network/accounts/history/permissions outside Jarvys control. Separate durable guard, five minutes, no replay; manually close external task then native review or Settings → Maps and dialer review. Closure is user-reported, not OS-verified. {launchRequested,actionConfirmed:false} proves only dispatch, not display/navigation/call/delivery. Preview unavailable; documents.cancel unrelated.

## Author the project

factory.json exact fields: schemaVersion(1), appId(Android applicationId), name, versionCode(positive integer), versionName, capabilities(supported IDs), webDir/icon(project-relative paths). Entrypoint: index.html. Concrete identity/branding; inspect types/icon formats/size/path limits.

Icon JSON: schemaVersion 1, opaque #RRGGBB background, 1–32 shapes/192×192: circle {type,cx,cy,r,fill}, rect {type,x,y,width,height,fill}, polygon {type,points,fill} with 3–32 [x,y] pairs. Geometry must fit 0..192; fills opaque #RRGGBB. Renderer affects raster bytes. Inspect PNGs; never invent files.

Factory assets/factory-app.json: schemaVersion/appId/name/entryPoint/capabilities; documentBroker for documents/browser/maps/phone. Never overwrite config/SDK/DEX/manifest. Exclude credentials/account/signing/unrelated data; review packaged inputs.

## Build/sign evidence

1. Read spec/sources back; check references/offline dependencies/branding/JS/escaping/capabilities. Inspection is not executed testing.
2. `build`: spec_path, output_path, fresh expected_scope_version; new relative output (dist/app-unsigned.apk). No per-app Java/Kotlin compilation. Retain path/hash/app/version/template evidence.
3. After error/interruption/conflict, reread state; never replay writes/overwrite outputs. Retain uncertain evidence; use fresh outputs. Filenames prove neither builds nor installability.
4. `sign`: input_path, exact expected_sha256, distinct output_path, current expected_scope_version. Same-project receipts and in-app approval only; never bypass/export keys/use debug keys/replace identity.
5. Disclose key policy: existing non-exportable AndroidKeyStore keys cannot be backed up/converted; losing them may prevent updates. For NEW recoverable keys use native Settings → Factory identities and passphrase-encrypted backup/export/import. Never receive/write passphrases or private keys in chat/tools/projects/JS. Source/APK backups do not restore keys.
6. Report exact artifact/verification. No step here installs the APK. Installation/coexistence/updates/data retention need Android evidence.

## Acceptance and handoff

Test workflows/persistence/export/Unicode/boundaries/reload/cancel. Authorized device tests check coexistence and same-key higher-version data-preserving updates. Host tests do not prove ARM64 behavior.

Return source/APK/identity/signature/checks/blockers with evidence.

## Closed manifest verification

Twelve capabilities use SDK 2/schema 1. Generated apps: zero permissions, only launcher; documents/browser/maps/phone share two exact host queries. F0a fixture nodes are not JS features. Build/sign verify closed AXML/resources/DEX and unchanged signed payload; inspect resourceBindings/componentDex. Approval compares the latest signed scope, disclosing unknown baselines. Preserve template authentication. Signed does not mean published, installed or Android-tested. Pre-UX35 receipts retain old window limits; rebuild unsupported layouts with retained appId/key and higher version.

## Recoverable identity workflow (UX42 F0b)

Only native Factory identities handles secrets; never bypass/change legacy or conflicting certificates. Encrypted export is not proof of restore. Import authenticates encryption/key/certificate/appId, merges maximum version floors and marks release history unknown. Signing stays blocked until native review resolves the floor; user declarations cannot prove the latest release. Old backups omit later releases/receipts. Rebuild with the same appId/key and higher version. Disclose passphrase/sole-backup loss; distinguish key backup, project export and app data. Independent-device restoration/data-preserving update still need physical tests.

## Factory preview/tests (F0c)
preview/preview_status/test require exactly input_path, expected_sha256, expected_scope_version from a current same-project build. Preview: private harness, isolated native test storage, simulated export/share/clipboard/haptics, Reset/Close. documents/photos/audio/browser/maps/phone/share.file return UNAVAILABLE; no fake handles/picker. Requires isolated WebView profiles; possible disk use, best-effort cleanup. Runs as Jarvys, not the installed app. preview_status returns bounded observed events; launch/page callbacks do not prove rendering or app workflows. test executes fixed synthetic shared-handler/validator assertions, not project JavaScript or browser/device tests. Inspect each result; no installation, hardware, persistence or zero-network claims. Scope changes, active-run cancellation and native closure revoke the preview. Rebuild old-template artifacts; preserve real-device acceptance gates.

## Explicit installation (F0c)
Only on user installation request: install with exactly input_path, expected_sha256, expected_scope_version and completed signed receipt. Full opens human-only native review bound to app/version/hash/certificate/receipt, then Android consent. Signing approval never authorizes installation. Never automate either screen, grant unknown-source permission, accept security warnings or retry a commit. Play returns unavailable and preserves the signed artifact without a bypass. install_status/install_cancel: same fields, no readable APK bytes required. Settings → Factory installations provides native recovery if project access changes. Backgrounding revokes uncommitted approval; restart cannot replay it. Only authenticated system success confirms the session; missing sessions, opened UI and cancellation after commit do not prove success or rollback. Unknown outcomes keep device automation blocked until native recovery. Device behavior and update/data retention still need actual acceptance.

Missing install permission opens protected native review; manual grants never resume it. Native-close then request again. Damaged-record recovery is human-only, cancels owned installer sessions and preserves unknown outcomes; never erase app data or signing identities.
