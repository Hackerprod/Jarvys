# Reusable Jarvys APK runtime: SDK 2, schema/protocol 1

This Android application is a **build-time template**, not a shared-identity production APK. Build its release variant once; the on-device factory retains its compiled DEX/resources and replaces package metadata, `factory_icon.png`, web assets, and per-app configuration. No generated Kotlin/Java is compiled on the phone. There is no bundled production signing key. Every distributed app needs its own stable signing identity managed outside this module.

## Module and packaging contract

- Module `:apk-runtime`, namespace `com.jarvys.factory.runtime`, template application ID `com.jarvys.factory.template`.
- Android min SDK 24, target 35, compile 36; Java 8 source.
- Launcher class **absolute** `com.jarvys.factory.runtime.FactoryActivity`.
- Manifest label `FACTORY_APP_LABEL`, version name `FACTORY_VERSION`, version code 1.
- Exactly one launcher PNG `res/drawable-nodpi/factory_icon.png`; no application `R` or `BuildConfig` references in runtime code.
- Dependencies: `androidx.webkit:webkit:1.14.0` and `androidx.core:core:1.15.0` (Google Maven); test-only `junit:junit:4.13.2`, `org.json:json:20240303`, `org.robolectric:robolectric:4.16`. Optional profileinstaller is excluded on every dependency path. Core's unused dynamic-receiver permission declarations are removed during manifest merging; the final template must retain zero permissions and no providers/receivers.
- No Android permissions, providers, services, receivers, network access, debug bridge, or production signing keys.
- Actual identity always comes from `Context.getPackageName()`. Configuration `appId` must equal it or startup fails closed.

Required APK assets:

```
assets/factory-app.json
assets/factory-sdk.js         # reusable SDK, supplied by runtime
assets/www/index.html         # generated app
assets/www/app.js             # generated app (external JS)
assets/www/style.css          # optional
```

`factory-app.json` (strict JSON, at most 8 KiB; no unrecognized fields):

```json
{
  "schemaVersion": 1,
  "appId": "com.example.notes",
  "name": "My Notes",
  "entryPoint": "www/index.html",
  "capabilities": ["storage", "export"]
}
```

For a documents-enabled installed app, factory-owned configuration also requires `documentBroker: {packageName, certificateSha256}`. The package is exactly `com.jarvys.agent` or `com.jarvys.agent.recoverytest`, and the certificate is a lowercase 64-hex SHA-256. The factory pins its own host at build time; project JSON/JS cannot supply a broker. Configuration rejects this field without `documents`; installed documents startup rejects a missing pin. Preview may parse without a pin but cannot perform documents operations.

The default `src/main/assets/www` is a runnable offline-notes acceptance fixture. Replace those assets for other apps; the native runtime contains no notes-specific behavior. The fixture provides explicit save, reopen, delete, and export flows. It limits notes to 20, each 60 title characters and 600 body characters, to fit native storage quotas. It does not silently fall back to temporary browser memory if native storage fails.

## Shared contract (UX42 F0a and v67)

`:factory-contract` is a dependency-free Java library shared with the factory. Its immutable CapabilityCatalog contains seven capabilities and sixteen methods. FactoryConfig, BridgeProtocol and explicit FactoryDispatcher dispatch use it, and SDK parity tests compare the actual JS calls with catalog/validator/handler cases. SDK 2 adds six `documents` methods; schema and bridge protocol remain 1, without reflection.

The factory uses a closed immutable ManifestPlan and a separate read-only AXML auditor to validate the actual compiled tree, typed attributes, resource bindings and backup rules before/after packaging and signing. All 128 selections request zero Android permissions; only the existing launcher is exported. Every classes*.dex name/hash and all signed payload entries are checked. F0a-2 adds typed construction primitives and a deterministic AXML encoder with explicit parent identity. Only `documents` contributes the exact package queries `com.jarvys.agent` and `com.jarvys.agent.recoverytest`; all other extra construction nodes remain host fixtures. No generated-app components or permissions are added; arbitrary nodes/resources and complete F0a remain gated. See [the packaging contract](../../APK_FACTORY.md#closed-capability-and-manifest-contract-ux42-f0a-1).

F0a-3 additionally verifies the complete resource-table structure and symbol identities, required compiled icon/backup files, and actual public concrete launcher/component-factory DEX class definitions with their superclass and constructor. Immutable inspect evidence identifies the verified bindings. DEX metadata integrity checks do not replace template authentication or Android bytecode/launch acceptance. Signing discloses additions/removals against an anchored last-signed scope snapshot, or explicitly says the baseline is unavailable for older records. Those F0a-3 checks do not themselves change runtime capabilities; the additive v67 SDK is described below.

The historical pre-UX35 manifest remains accepted only through the receipt-bound v1 compatibility path. It lacks the newer windowSoftInputMode but does not acquire new capabilities; new builds use the current profile. Updating this shared compiled code changes the template DEX, so already generated apps still need their own same-ID/same-key, higher-version rebuild.

## Native window and safe area

The runtime retains NoActionBar and visible system bars. FactoryWindowPolicy creates the native decor before applying explicit light/dark chrome and icon contrast, then reserves the union of system bars, cutouts and IME in one shared root for the WebView and errors. Handled types are zeroed before child dispatch, including native legacy stable/cutout metadata; updates continue after keyboard dismissal and unhandled gesture types remain intact. API 24/25 use a black navigation bar because dark navigation icons are unavailable. See [the factory contract](../../APK_FACTORY.md#window-and-safe-area-contract-ux35).

Apps use a responsive mobile viewport and scrollable forms without hardcoded Android bar padding. The runtime does not infer colors from app HTML or grant JavaScript control over the native window. Previously generated APKs require a same-ID/same-key, increased-version rebuild to receive changes to this compiled template.

## JS SDK

HTML uses external scripts, including `<script src="/factory-sdk.js"></script>` before app scripts. Global `window.Jarvys` exposes only Promise-returning methods:

| Call | Result | Required capability |
|---|---|---|
| `Jarvys.runtime.info()` | SDK version, supported/declared capability names, limits | None (non-sensitive introspection) |
| `Jarvys.storage.get(key)` | String or `null` | `storage` |
| `Jarvys.storage.set(key, stringValue)` | `null` after durable commit | `storage` |
| `Jarvys.storage.remove(key)` | `null` | `storage` |
| `Jarvys.storage.list()` | Sorted string keys | `storage` |
| `Jarvys.export.text({filename,text,mimeType?})` | `{saved:true}` after writing | `export` |
| `Jarvys.share.text({text,title?})` | `{chooserOpened:true}`, never delivery confirmation | `share` |
| `Jarvys.share.file({handle,filename,mimeType})` | `{chooserOpened,deliveryConfirmed:false}` after native human closure | `share` + `documents` |
| `Jarvys.clipboard.write(text)` | `null` after native confirmation | `clipboard` |
| `Jarvys.haptics.perform("tap" or "longPress")` | Boolean, respects system haptic settings | `haptics` |
| `Jarvys.device.info()` | Android platform/API level, actual app ID, target SDK | `device` |

Errors reject with `error.code` and readable `error.message`. Handle `CANCELLED`, `CAPABILITY_DENIED`, `INVALID_ARGUMENT`, `QUOTA_EXCEEDED`, `PERMISSION_DENIED`, `BUSY`, `RATE_LIMITED`, `UNAVAILABLE`, `TIMEOUT`, and persistence errors. SDK timeouts do not revoke a system action the user already chose. It times out normal calls after 30 seconds and interactive calls after 10 minutes. Closing/replacing the page invalidates reply handles. Introspection does not expose device/user data.

Storage keys match `[a-zA-Z0-9_.:-]{1,96}`. Values must be strings; explicitly JSON stringify structured app data. Value limit 64 KiB UTF-8, total keys+values 1 MiB, at most 256 entries. Read/quota/write transactions share a process-wide lock across Activity instances to prevent lost updates during recreation. Storage is private app `SharedPreferences`, scoped by the real package name, and backed up by neither Android Auto Backup nor this runtime.

Exports are UTF-8 text, up to 256 KiB (and always subject to the encoded message limit), using `ACTION_CREATE_DOCUMENT`. Supported MIME types: `text/plain`, `text/markdown`, `application/json`, `text/csv`. Native code never takes an arbitrary filesystem path or URI from JavaScript. Only the system picker result is accepted, and the transient content URI write grant is checked at time of use. No broad storage access or persistent grant is requested. Export success is reported after writing and closing the document stream. Cancellation is a rejected Promise.

Sharing uses a fixed `ACTION_SEND` text/plain system chooser. Clipboard is write-only and requires a visible native confirmation; on Android 13+ it is marked sensitive to suppress the system preview. No arbitrary intent, package, class, component, shell command, file URI, network URL, reflection invocation, or permission grant can be supplied.

## Temporary binary documents (v67)

Every `Jarvys.documents` method below returns a Promise and requires the `documents` capability. Options are exact objects: unknown keys, paths, URIs, invalid types and malformed handles reject. `open` and `create` require a compatible installed build-pinned Jarvys host, human native review, Android picker selection and **Use this document**. The generated app has no document Activity/provider or storage permission. The host's exported broker checks actual caller UID/package, certificate, version, APK SHA-256 and latest signed scope/identity evidence. Signing a newer version disables document access for the previous installed version until the exact new signed APK is installed. Both known host packages installed together blocks access; missing or uncertain evidence fails closed.

| Promise call | Resolved object |
|---|---|
| `Jarvys.documents.open({mimeType})` | `{handle,mode:"read",expiresAfterMs:300000,maximumBytes:16777216,providerCommitConfirmed:false}` |
| `Jarvys.documents.create({filename,mimeType})` | Same fields with `mode:"write"` |
| `Jarvys.documents.read({handle,offset,length})` | `{data,offset,nextOffset,eof}`; `data` is canonical base64, empty on observed EOF |
| `Jarvys.documents.write({handle,offset,data})` | `{offset,nextOffset,bytesWritten,providerCommitConfirmed:false}` |
| `Jarvys.documents.close({handle})` | `{status:"close_requested",providerCommitConfirmed:false}` |
| `Jarvys.documents.cancel()` or `cancel({})` | `{cancelled:true,rollbackConfirmed:false,pickerMayRemainOpen}` |

Both MIME arguments are required, at most 127 characters, without parameters or lists. `open` also accepts `*/*` or a type wildcard such as `image/*`; `create` requires a concrete type such as `application/octet-stream`. `filename` is a simple 1–120 ASCII-character suggestion matching `[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}`, without `..` or a final dot. It is not a path. No filename or provider URI is returned. Treat the random 64-lowercase-hex handle as opaque authority, never as a durable ID.

Offsets are integers, start at zero and must equal the last `nextOffset`; no seeking, overlap, parallel I/O or mode changes. `length` is 1–32768 bytes. `data` is nonempty canonical padded standard base64 for at most 32768 decoded bytes: no whitespace, URL-safe alphabet, data-URL prefix or nonzero unused bits. Handle quota is 16 MiB; page/session quota is 32 MiB cumulative across handles, including conservatively charged failed/cancelled I/O. Requests must fit the remaining quota. A short positive read is not EOF: only `eof:true` confirms EOF. At exact quota do not probe one more byte; close with EOF unconfirmed. Four admission slots include outstanding provider opening/cleanup, not a guarantee of four simultaneously usable picker results. Handles expire after five minutes and are never persisted; cancellation/close does not restore quota.

Minimal external-script usage (catch rejections in the UI):

```js
const {handle} = await Jarvys.documents.create({filename:"sample.bin",mimeType:"application/octet-stream"});
try {
  const part = await Jarvys.documents.write({handle,offset:0,data:"AAEC"});
  // The next write must use part.nextOffset. This is not a durable-save receipt.
} finally {
  await Jarvys.documents.close({handle});
}
```

For reads, call `open({mimeType:"*/*"})`, then `read({handle,offset:0,length:32768})`; consume `data`, advance to `nextOffset` and stop at `eof` or the remaining quota, then close. Shrink each request to remaining handle/session budget. Keep handles in current-page memory only. Installed selection is effectively one at a time: opening another document revokes/closes the previous one and may reject `BUSY` across runtime instances until provider cleanup completes. Backgrounding, reset/navigation, rotation and destruction revoke existing authority; no restart can restore it. `cancel()` calls native cancelAll, invalidates current work and retains cumulative quota. A picker may remain open; cancellation does not prove its dismissal.

Handle errors include `INVALID_HANDLE` (missing/expired/revoked), `WRONG_MODE`, `HANDLE_BUSY`, `INVALID_OFFSET`, `INVALID_CHUNK`, `DOCUMENT_QUOTA`, `HANDLE_LIMIT`, `SESSION_REVOKED` and `DOCUMENT_IO`. Bridge validation may instead reject malformed options as `INVALID_ARGUMENT` or `INVALID_REQUEST`. Also handle `CAPABILITY_DENIED`, `UNAVAILABLE`, `BUSY`, `CANCELLED`, `PERMISSION_DENIED`, `RATE_LIMITED`, `TIMEOUT` and `NATIVE_ERROR`; never retry writes blindly. Preview always rejects documents with `UNAVAILABLE`, without a fake handle or real picker.

`close_requested` schedules best-effort provider close; it does not prove flush, durable commit, cloud upload or rollback. A started write may partially finish after cancellation. Create can leave empty/partial files even when no handle is granted. A selected cloud provider can use its own network despite the generated app's zero-permission offline WebView. JavaScript never receives/chooses a path or URI; there are no persistent grants. Jarvys automation is blocked throughout the human interaction. **Settings → Factory documents** handles interrupted-picker recovery: the human closes the old picker/task and acknowledges closure. This is explicitly user-reported, not OS-verified; the old outcome stays unknown and no authority is recovered.

v67 SAF host validation and APK delivery are recorded separately; physical picker/provider and grant lifecycle remain unverified. Binary sharing is the following bounded v68 slice; the other F1/F2/F3 families remain closed.

## Binary file sharing (v68)

`Jarvys.share.file({handle, filename, mimeType})` requires **both `documents` and `share`**.
Use a newly opened, untouched read handle. No writes, previously read handles, raw bytes, URI,
path, destination package, flags or persistent authority are accepted. Filename has the same
simple bounded grammar as document creation; MIME must be one concrete type, without wildcards
or parameters. These are declared metadata, not proof of the document's content format.

The runtime consumes and closes the handle while preparing a complete 1-byte to **8 MiB** native
snapshot. It verifies EOF without exceeding v67's 16 MiB handle / 32 MiB cumulative session quotas.
Empty, oversized, partial, expired, busy or quota-exhausted sources fail closed; a failed attempt
may have consumed the handle and some quota. Reopen through the human document flow before retrying.
No source read occurs after the sharing broker opens. One process-wide snapshot/transfer slot
bounds memory; a blocked native source retains admission until cleanup rather than admitting
unbounded replacements. Pending work expires after five minutes.

Native Binder transfers at most 32 KiB per transaction to the pinned Jarvys host. Calls authenticate
its unique UID/certificate and bind nonce, exact size, hash and sequential offset. No file data,
Binder object, path or URI is exposed in the JavaScript result. The host independently authenticates
the caller's exact latest signed APK and both capabilities, rejects two installed hosts, then stages
and verifies the complete immutable snapshot before enabling human review. Generated APKs still add
no provider or Android permission. A compatible current Jarvys host is required.

The protected native screen shows the requesting app, name, declared MIME and size. Only a human
can open Android's chooser. Its FileProvider grants read access only to one registered private URI;
there are no write, prefix or persistable grants. The host copy expires in five minutes. Registry
loss on process restart rejects old reads. Closing/cancelling cannot recall an already opened file
descriptor, recipient copy or completed network transfer; recipients may upload data remotely.

The successful response is `{chooserOpened: true|false, deliveryConfirmed:false}` after explicit
native closure. A chooser callback, selected target, cancelled UI or timeout never proves delivery.
The human must close the old chooser/recipient task before acknowledging closure. Jarvys automation
stays blocked across the chooser, callback, interruption and unknown outcomes until that native
acknowledgment; **Settings → Factory file sharing** provides recovery. Acknowledgment is user-reported,
not OS proof. No lost authority is restored. `documents.cancel()` closes the source-side transfer
but cannot promise rollback of the host copy or dismiss the external UI.

Preview rejects `share.file` with `UNAVAILABLE`; existing `share.text` simulation is unchanged.
`runtime.info` discloses the 8 MiB limit, five-minute lifetime and both required capabilities.
Its preview `unavailableMethods` explicitly includes `share.file`; `simulatedCapabilities` keeps
`share` because text sharing is simulated, not because every sharing method is simulated.
Errors additionally include `EMPTY_FILE`, `SHARE_TOO_LARGE`, `SHARE_BUSY` and `SHARE_UNAVAILABLE`.
Host synthetic tests cannot establish actual Binder IPC, chooser grant behavior, recipient access,
revocation, OEM lifecycle or physical-device acceptance. v68 release gates are recorded separately.

## Security boundary

- `WebViewCompat.addWebMessageListener` registers only exact origin `https://app.jarvys.invalid`, with no wildcard.
- Every invocation independently checks origin and `isMainFrame`; subframe messages are dropped.
- WebView versions without `WEB_MESSAGE_LISTENER` fail closed with an update message. No `addJavascriptInterface` fallback.
- Bridge accepts strict bounded JSON `{v:1,id,method,args}`. Unknown method/fields, duplicate fields, malformed JSON, type mismatches, nesting over 8 levels, excessive tokens, or message over 512 KiB are rejected.
- Capability declaration is checked for every native request (except non-sensitive runtime introspection). Unknown or duplicate declared capabilities reject startup.
- Web content is served directly from packaged assets at the trusted HTTPS origin. Every other request returns a blocked response. Config and private app data are never served.
- File/content access, universal file access, mixed content, cookies, DOM storage, network loads, JavaScript popups, downloads, file uploads, browser permissions, geolocation, and SSL-error bypass are disabled.
- CSP blocks remote scripts, inline JavaScript, eval, connections, frames, workers, plugins, forms, and base-URL changes. Only packaged assets can load. Styles may be inline; image `data:` sources are permitted.
- Native pending queue at most 16; incoming bridge limited to 80 calls per 10 seconds; all file persistence runs on a single bounded background executor.
- No location, direct camera access, microphone, notifications, contacts, alarms, Bluetooth, network, filesystem reads, arbitrary intents, or runtime permission grants. Documents permits only native-selected temporary streams, never arbitrary filesystem reads. Photos adds only the bounded v69 system-intent contract below. Adding these requires a separately reviewed reusable-runtime release, manifest changes, typed policy, denial-path tests, and explicit capability declaration.

## Verification

Parent build runs `:apk-runtime:testReleaseUnitTest` and `:apk-runtime:assembleRelease`. Optional browser-only fixture smoke (requires Playwright and a usable Chromium): `CHROMIUM_PATH=/usr/bin/chromium node apk-runtime/tests/smoke-notes.cjs`. The current cloud environment blocks Chromium Unix sockets, so that smoke has not completed here.

Standalone SDK tests: `node --test apk-runtime/tests/sdk.test.cjs` from `app/`.

JVM coverage: origin/subframe rejection, JSON/schema/method/argument validation, capability denial, config/package binding, hostile asset URLs, CSP constraints, storage entry/byte quotas, persistence across backend reconstruction, and backend isolation, and overlapping Activity-store concurrency. Storage tests use a durable filesystem fake, not an Android emulator; they do not prove Android platform sandbox behavior. Node coverage includes request correlation, native rejection, malformed replies, unavailable bridge, payload/pending limits, page teardown, and timeouts.

UX35 adds 47 Robolectric cases across API 24, 26, 28, 29, 34 and 35 for light/dark contrast, one safe-area owner, keyboard union/dismissal, rotation, cutouts, native metadata/child dispatch, gesture preservation and protected errors. Four optional JARVYS_UX35_CAPTURE_DIR images draw the actual native root with labeled native content; they do not render Chromium, System UI or a real IME.

Device acceptance still required: install two differently named/signed generated packages, save/relaunch notes, inspect package/data isolation, export with save/cancel, deny clipboard confirmation, share chooser/cancel, old WebView fallback, and adversarial same-origin iframe attempts. Do not call this end-to-end validated without those device checks.

Also validate real WebView fields/scroll with repeated keyboard open/close, portrait/landscape cutouts, light/dark system theme, and gesture/three-button navigation on supported devices. Injected-inset host tests cannot certify those platform behaviors.

## Shared controller and preview adapters (F0c)

The generated launcher delegates to `FactoryRuntime` in the resource-ID-independent `:factory-runtime-core` library. Installed startup still parses configuration against `Activity.getPackageName()`. The core owns WebView security setup, asset serving, strict bridge validation, dispatch, queues and page-generation reply cancellation. `FactoryDispatcher` shares native method behavior with the bounded synthetic test harness; installed effects are explicit Android adapters, while preview effects return declared simulation metadata without performing them. The v67 documents methods explicitly reject `UNAVAILABLE` in preview, with no fake handle or real picker; schema/protocol remain 1.

A separate private Jarvys Activity uses verified immutable build assets and an isolated native RAM backend. Its explicit preview metadata reports the real host package separately from declared appId. A provider-specific isolated profile is mandatory; there is no default-profile fallback. The controller does not change process-global cookies or debugging in preview mode. Profile cleanup is best effort and profiles may be disk-backed. Native UI/host tests do not establish actual provider cleanup, installed UID isolation, hardware, device rendering or absence of traffic.

`start()` and `reset()` return whether native initialization and the load request were accepted, not whether rendering or JavaScript succeeded. Old page/reply generations are invalidated, with pending interactive ownership retained when required to reject stale system results. Installed provider I/O does not hold the lifecycle monitor used by UI teardown; cancellation cannot roll back an already-started external write. See [Factory preview/test contracts](../../APK_FACTORY.md#f0c-functional-preview-and-bounded-shared-core-tests) for exact tool scope and evidence limits.

## Photos (v69)

`Jarvys.photos.pick({})` and `Jarvys.photos.capture({})` require both photos and documents.
Their strict empty argument object accepts no filenames, URI, paths, actions, target packages,
MIME choices or flags. Preview returns UNAVAILABLE. The pinned compatible Jarvys host must
verify this exact installed app against its latest signed receipt; native human launch/use is
mandatory. The runtime receives a native-only Binder snapshot and returns only:

`{handle, mode:"read", size, mimeType, width, height, expiresAfterMs:300000, metadataRetained:true}`

The handle works with documents.read/close/cancel and an untouched handle with share.file.
Input snapshot acquisition charges the existing cumulative 32 MiB page quota without refunds
for failure/cancellation; later reads/sharing charge their normal sequential quotas. One photo
copy is admitted process-wide, including blocked or cancelling copies. Length, SHA-256 and
explicit EOF are verified before handle delivery. Backgrounding, reset and process death
revoke authority; late callbacks cannot attach to a new request/page.

Only JPEG/PNG up to 8 MiB, 4096 pixels per side and 12 megapixels are supported. Original
metadata may include location. Capturing uses a one-open write-only bounded pipe, not a
seekable file: incompatible camera handlers fail and no thumbnail fallback occurs. A system
photo picker or SAF fallback must exist. Jarvys does not autosend or save to gallery, but the
external camera/cloud provider can keep or transfer its own copies. No broad camera/gallery
permission is added. Physical acceptance and interrupted external UI recovery remain required.

Android 24–29 accepts only regular file descriptors from photo providers; shared streaming
providers require Android 11/API30+. The private camera pipe remains supported from API24
with its sole host-owned reader. Provider I/O may remain blocked despite cancellation or
nonblocking setup (a provider may share descriptor flags). Worker and cross-broker admission
stay held until actual cleanup; no forced provider-read termination is promised.


### Browser-only launch, v71

`runtime.info` retains `offline:true` and adds `offlineScope:"embedded_webview"` plus
`browserMayUseExternalNetwork:true`; external browser/provider networks are outside that offline scope.

`browser.open({url})` requires only `browser`, with the factory-owned pinned `documentBroker`
metadata (also used by documents). It grants no document methods or network permission. Only an
installed generated app can open authenticated human-only host review; preview is unavailable.
URL is exact printable ASCII HTTPS, at most 2048 characters, strict lowercase DNS hostname and
optional canonical :443, no credentials/userinfo/fragment, backslash/controls or malformed escapes.
Escaped controls/space/DEL/backslash are rejected; no normalization or secret detection is claimed.

The native user reviews the whole URL, data-transmission disclosure and exact selected browser.
No caller-controlled component/extra/flag or implicit fallback. The host owns durable one-shot
launch/recovery and protected manual closure. Result `{launchRequested,pageLoadConfirmed:false}`
is only startActivity acceptance, never rendered page, delivery or browser-closure evidence.
External browser cookies, DNS, redirects, resources and other destinations are outside Jarvys
control. Runtime's five-minute timer revokes pending launch authority even after SDK timeout;
reload/closure and control death revoke too. Expected pause during the host flow does not fabricate
cancellation; interrupted outcomes stay uncertain, never auto-replayed. `documents.cancel` is not
browser cancellation. Full contract and physical-acceptance limits: [APK_FACTORY](../../APK_FACTORY.md#https-browser-launch-ux42-f1-v71).


### Maps and dialer-only launch, v72

`maps.open({latitude,longitude})` or `maps.open({query})` requires only maps;
`phone.dial({number})` requires only phone. Both require the exact build-pinned host, not documents.
Coordinates are finite actual JSON numbers in [-90,90]/[-180,180]. Queries are nonblank,
valid Unicode, 1–256 code points/1,024 UTF-8 bytes, without control/format/line/paragraph characters;
accepted text is encoded once. Phone is optional + and 1–15 ASCII digits, preserved exactly.
No supplied URI, extra keys, GPS read, CALL_PHONE, permission request or automatic call.

Native human review shows complete input and chosen recipient, including external network/accounts/
history disclosure. Constructed geo ACTION_VIEW and tel ACTION_DIAL only; no implicit fallback.
Separate broker/recovery namespace preserves v71, sharing the durable admission/automation guard.
Five-minute expiry, source cancellation/death, lifecycle loss and interrupted preparation cannot
reapprove a launch. The user manually closes the external task and acknowledges closure; this is
not OS verification. Receipt `{launchRequested,actionConfirmed:false}` proves only launch request,
never display/navigation/calling/delivery. Preview unavailable; documents.cancel is unrelated.
Metadata distinguishes external recipient network use from the embedded WebView offline scope.
Generated apps keep zero permissions and the existing exact two-host queries. Rebuild old generated
APKs to gain this runtime. Complete grammar, recovery and physical limits:
[APK_FACTORY](../../APK_FACTORY.md#typed-maps-and-dialer-launch-ux42-f1-v72).
