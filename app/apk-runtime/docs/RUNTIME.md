# Reusable Jarvys APK runtime: SDK 2, schema/protocol 1

This Android application is a **build-time template**, not a shared-identity production APK. Build its release variant once; the on-device factory retains its compiled DEX/resources and replaces package metadata, `factory_icon.png`, web assets, and per-app configuration. No generated Kotlin/Java is compiled on the phone. There is no bundled production signing key. Every distributed app needs its own stable signing identity managed outside this module.

Current catalog: 20 capabilities / 46 wire methods, SDK 2 and schema/bridge protocol 1. Native backup authorization uses its own v2 protocol; it does not change the JavaScript wire schema or backup archive format. Versioned sections retain their technical API scope, with current operation-policy behavior described below.

## Module and packaging contract

- Module `:apk-runtime`, namespace `com.jarvys.factory.runtime`, template application ID `com.jarvys.factory.template`.
- Android min SDK 24, target 35, compile 36; Java 8 source.
- Launcher class **absolute** `com.jarvys.factory.runtime.FactoryActivity`.
- Manifest label `FACTORY_APP_LABEL`, version name `FACTORY_VERSION`, version code 1.
- Exactly one launcher PNG `res/drawable-nodpi/factory_icon.png`; no application `R` or `BuildConfig` references in runtime code.
- Dependencies: `androidx.webkit:webkit:1.14.0` and `androidx.core:core:1.15.0` (Google Maven); test-only `junit:junit:4.13.2`, `org.json:json:20240303`, `org.robolectric:robolectric:4.16`. Optional profileinstaller is excluded on every dependency path. Core's unused dynamic-receiver permission declarations are removed during manifest merging; the final template must retain zero permissions and no providers/receivers.
- The template has no Android permissions, providers, services, receivers, network access, debug bridge or production signing keys. Generated biometric profiles add only normal USE_BIOMETRIC; no generated INTERNET or package-install permission is added.
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

For an installed app declaring documents, clipboard, browser, maps, phone, email, sms, contacts or calendar, factory-owned configuration also requires `documentBroker: {packageName, certificateSha256}`. The package is exactly `com.jarvys.agent` or `com.jarvys.agent.recoverytest`, and the certificate is a lowercase 64-hex SHA-256. The factory pins its own host at build time; project JSON/JS cannot supply a broker. Configuration rejects this field without a broker-dependent capability; installed broker startup rejects a missing pin. Preview may parse without a pin but cannot perform real broker effects; clipboard remains explicitly simulated.

The default `src/main/assets/www` is a runnable offline-notes acceptance fixture. Replace those assets for other apps; the native runtime contains no notes-specific behavior. The fixture provides explicit save, reopen, delete, and export flows. It limits notes to 20, each 60 title characters and 600 body characters, to fit native storage quotas. It does not silently fall back to temporary browser memory if native storage fails.

## Shared contract (UX42 F0a, current v84 catalog)

`:factory-contract` is a dependency-free Java library shared with the factory. Its immutable CapabilityCatalog contains twenty capabilities and forty-six methods. FactoryConfig, BridgeProtocol and explicit FactoryDispatcher dispatch use it, and SDK parity tests compare the actual JS calls with catalog/validator/handler cases. SDK 2 adds six `documents` methods; schema and bridge protocol remain 1, without reflection.

The factory uses a closed immutable ManifestPlan and a separate read-only AXML auditor to validate the actual compiled tree, typed attributes, resource bindings and backup rules before/after packaging and signing. Of 1,048,576 capability subsets, the 524,288 without biometrics request zero Android permissions; the other 524,288 add only normal USE_BIOMETRIC. These counts describe the catalog, not exhaustive build/device validation. Only the existing launcher is exported. Every classes*.dex name/hash and all signed payload entries are checked. F0a-2 adds typed construction primitives and a deterministic AXML encoder with explicit parent identity. Any of `documents`, `clipboard`, `browser`, `maps`, `phone`, `email`, `sms`, `contacts` or `calendar` contributes the exact package queries `com.jarvys.agent` and `com.jarvys.agent.recoverytest`; all other extra construction nodes remain host fixtures. No generated-app components are added; beyond the selective biometric permission, arbitrary nodes/resources and complete F0a remain gated. See [the packaging contract](../../APK_FACTORY.md#closed-capability-and-manifest-contract-ux42-f0a-1).

F0a-3 additionally verifies the complete resource-table structure and symbol identities, required compiled icon/backup files, and actual public concrete launcher/component-factory DEX class definitions with their superclass and constructor. Immutable inspect evidence identifies the verified bindings. DEX metadata integrity checks do not replace template authentication or Android bytecode/launch acceptance. Signing discloses additions/removals against an anchored last-signed scope snapshot, or explicitly says the baseline is unavailable for older records. Those F0a-3 checks do not themselves change runtime capabilities; the additive v67 SDK is described below.

The historical pre-UX35 manifest remains accepted only through the receipt-bound v1 compatibility path. It lacks the newer windowSoftInputMode but does not acquire new capabilities; new builds use the current profile. Updating this shared compiled code changes the template DEX, so already generated apps still need their own same-ID/same-key, higher-version rebuild.

## Factory operation permissions

Settings → Factory permissions starts with ALLOW_ALWAYS. The user can choose ASK or DENY as the default and override each operation for each authenticated app. “Use default” removes that override. The protected permission editor cannot be operated by an agent to expand its own authority. Factory permissions are separate from Coding autonomy.

The configurable operations are exactly:
- documents.open, documents.create
- photos.pick, photos.capture, audio.play
- browser.open, contacts.pick, share.file
- maps.open, phone.dial, email.compose, sms.compose, calendar.insert
- database.backupExport, database.backupImport
- storage.backupExport, storage.backupImport
- factory.sign, factory.install, clipboard.write

ALLOW_ALWAYS advances without a Jarvys approval prompt. ASK asks once for the exact request, then continues without duplicate Use, Play, all-values, sign or install confirmations. DENY prevents that operation's effects. Android document/photo/contact selection, recipient choice when no verified default exists, genuine Android grants, biometric authentication and installer consent remain when required. This is saved application authority, not a bypass for Android security. Capability declarations and backup table/key selections still constrain scope.

Identity is appId plus authenticated certificate, never an Intent label. First signing uses a project-bound new-identity subject; explicit choices transfer to the resulting certificate. Atomic persisted policy revisions invalidate affected pending grants after a default/override change. “Revoke this app’s pending operations” persists revocation without changing future policy. Unreadable or failed policy persistence fails closed. A grant binds one exact request and cannot survive relevant policy revision, lifecycle or identity loss.

New backup protocol v2 authenticates both Binder endpoints and checks live policy before each export write and import commit. Legacy backup protocol v1 remains authenticated, but its final host authorization is the last enforceable checkpoint; older DEX has no later callback. Already-open file descriptors cannot be revoked retroactively, nor can completed writes, clipboard copies, installation commits or recipient copies be undone. New share-file opens recheck authority; an already-open recipient descriptor may remain usable. Never claim cancellation proves rollback or external task closure.

The latest signed APK receipt still authenticates an installed broker caller. Signing a newer APK can make the previously installed APK unavailable until updated; per-artifact trust history is deferred. Already-generated APKs retain their packaged DEX: rebuild/sign/install an update to obtain new clipboard and backup runtime behavior. No arbitrary compiler is added; a new app packages the precompiled, authenticated DEX unchanged.

This is a 20-operation policy, not a switch for all 46 SDK methods. share.text, export.text, local storage, ordinary SQLite, haptics, device information, presentation, sensors and biometrics retain their direct runtime contracts. Clipboard uses the authenticated host in the new runtime; preview stays simulated with performed:false and never writes the system clipboard. Byte/file/row/rate/deadline budgets remain technical quotas, not permission settings. Dispatch, chooser return and cleaned-up audio need no invented success attestation; unknown outcomes still require reconciliation and are never replayed automatically.

## Native window and safe area

The runtime retains NoActionBar and visible system bars. FactoryWindowPolicy creates the native decor before applying explicit light/dark chrome and icon contrast, then reserves the union of system bars, cutouts and IME in one shared root for the WebView and errors. Handled types are zeroed before child dispatch, including native legacy stable/cutout metadata; updates continue after keyboard dismissal and unhandled gesture types remain intact. API 24/25 use a black navigation bar because dark navigation icons are unavailable. See [the factory contract](../../APK_FACTORY.md#window-and-safe-area-contract-ux35).

Apps use a responsive mobile viewport and scrollable forms without hardcoded Android bar padding. The runtime does not infer colors from app HTML or grant JavaScript control over the native window. Previously generated APKs require a same-ID/same-key, increased-version rebuild to receive changes to this compiled template.

## JS SDK

HTML uses external scripts, including `<script src="/factory-sdk.js"></script>` before app scripts. Global `window.Jarvys` exposes only Promise-returning methods:

| Call | Result | Required capability |
|---|---|---|
| `Jarvys.runtime.info()` | SDK version, supported/declared capability names, limits | None (non-sensitive introspection) |
| `Jarvys.storage.get(key)` | String or `null` | `storage` |
| `Jarvys.storage.set(key, stringValue)` | `null` after backend commit; no physical-durability proof | `storage` |
| `Jarvys.storage.remove(key)` | `null` | `storage` |
| `Jarvys.storage.list()` | Sorted string keys | `storage` |
| `Jarvys.export.text({filename,text,mimeType?})` | `{saved:true}` after writing | `export` |
| `Jarvys.share.text({text,title?})` | `{chooserOpened:true}`, never delivery confirmation | `share` |
| `Jarvys.share.file({handle,filename,mimeType})` | `{chooserOpened,deliveryConfirmed:false}` after chooser return; no closure/delivery proof | `share` + `documents` |
| `Jarvys.clipboard.write(text)` | `null` after policy-authorized host write; ASK prompts once | `clipboard` |
| `Jarvys.haptics.perform("tap" or "longPress")` | Boolean, respects system haptic settings | `haptics` |
| `Jarvys.device.info()` | Android platform/API level, actual app ID, target SDK | `device` |

Errors reject with `error.code` and readable `error.message`. Handle `CANCELLED`, `CAPABILITY_DENIED`, `INVALID_ARGUMENT`, `QUOTA_EXCEEDED`, `PERMISSION_DENIED`, `BUSY`, `RATE_LIMITED`, `UNAVAILABLE`, `TIMEOUT`, and persistence errors. SDK timeouts do not revoke a system action the user already chose. It times out normal calls after 30 seconds and interactive calls after 10 minutes, except biometrics.authenticate (90 seconds) and both backup families (310000 ms). Closing/replacing the page invalidates reply handles. Introspection does not expose device/user data.

Storage keys match `[a-zA-Z0-9_.:-]{1,96}`. Values must be strings; explicitly JSON stringify structured app data. Value limit 64 KiB UTF-8, total keys+values 1 MiB, at most 256 entries. Read/quota/write transactions share a process-wide lock across Activity instances to prevent lost updates during recreation. Storage is private app `SharedPreferences`, scoped by the real package name, and excluded from Android Auto Backup. Explicit selected-key backup is available only through the declared dataBackup.keys policy below; ordinary storage operations do not create backups.

Exports are UTF-8 text, up to 256 KiB (and always subject to the encoded message limit), using `ACTION_CREATE_DOCUMENT`. Supported MIME types: `text/plain`, `text/markdown`, `application/json`, `text/csv`. Native code never takes an arbitrary filesystem path or URI from JavaScript. Only the system picker result is accepted, and the transient content URI write grant is checked at time of use. No broad storage access or persistent grant is requested. Export success is reported after writing and closing the document stream. Cancellation is a rejected Promise.

Text sharing uses a fixed `ACTION_SEND` text/plain system chooser and remains a direct runtime path. Clipboard is write-only and, in newly generated runtimes, goes through the authenticated pinned host. ALLOW_ALWAYS copies automatically, ASK shows one native approval, and DENY prevents the write. The host rechecks the exact caller, current request, lifecycle and saved policy immediately before setPrimaryClip; the source/host Binder handshake rejects forged or stale authority. On Android 13+ the clip is marked sensitive to suppress the system preview. Expiry or cancellation cannot undo an earlier copy. Preview returns {simulated:true,performed:false,mode:"preview",operation:"clipboard.write"} and never touches the system clipboard. No arbitrary intent, package, class, component, shell command, file URI, network URL, reflection invocation, or permission grant can be supplied.

## Temporary binary documents (v67)

Every `Jarvys.documents` method below returns a Promise and requires the `documents` capability. Options are exact objects: unknown keys, paths, URIs, invalid types and malformed handles reject. `open` and `create` require a compatible installed build-pinned Jarvys host and saved documents.open/create authority. ALLOW_ALWAYS opens Android selection automatically; ASK reviews once before it; DENY blocks. Validated selection delivers access without another **Use this document** confirmation. The generated app has no document Activity/provider or storage permission. The host's exported broker checks actual caller UID/package, certificate, version, APK SHA-256 and latest signed scope/identity evidence. Signing a newer version disables document access for the previous installed version until the exact new signed APK is installed. Both known host packages installed together blocks access; missing or uncertain evidence fails closed.

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

`close_requested` schedules best-effort provider close; it does not prove flush, durable commit, cloud upload or rollback. A started write may partially finish after cancellation. Create can leave empty/partial files even when no handle is granted. A selected cloud provider can use its own network despite the generated app's zero-permission offline WebView. JavaScript never receives/chooses a path or URI; there are no persistent grants. Policy changes revoke pending host requests and are checked before grant delivery; already-open file descriptors cannot be revoked retroactively. **Settings → Factory documents** reconciles interrupted-picker uncertainty without proving OS closure or restoring authority. A fresh ALLOW_ALWAYS request may retire historical uncertainty before acquiring new authority; it never replays the old operation. Unknown/blocked provider cleanup still retains admission.

v67 SAF host validation and APK delivery are recorded separately; physical picker/provider and grant lifecycle remain unverified. Binary sharing is the following bounded v68 slice; later implemented families are described in the current factory guide; unimplemented families remain unavailable.

## Binary file sharing (v68)

`Jarvys.share.file({handle, filename, mimeType})` requires **both `documents` and `share`**.
Use a newly opened, untouched read handle. No writes, previously read handles, raw bytes, URI,
path, destination package, flags or persistent authority are accepted. Filename has the same
simple bounded grammar as document creation; MIME must be one concrete type, without wildcards
or parameters. These are declared metadata, not proof of the document's content format.

The runtime consumes and closes the handle while preparing a complete 1-byte to **8 MiB** native
snapshot. It verifies EOF without exceeding v67's 16 MiB handle / 32 MiB cumulative session quotas.
Empty, oversized, partial, expired, busy or quota-exhausted sources fail closed; a failed attempt
may have consumed the handle and some quota. A deliberate new attempt needs a fresh document selection and saved operation authority; never replay an uncertain prior share.
No source read occurs after the sharing broker opens. One process-wide snapshot/transfer slot
bounds memory; a blocked native source retains admission until cleanup rather than admitting
unbounded replacements. Pending work expires after five minutes.

Native Binder transfers at most 32 KiB per transaction to the pinned Jarvys host. Calls authenticate
its unique UID/certificate and bind nonce, exact size, hash and sequential offset. No file data,
Binder object, path or URI is exposed in the JavaScript result. The host independently authenticates
the caller's exact latest signed APK and both capabilities, rejects two installed hosts, then stages
and verifies the complete immutable snapshot before applying share.file authorization. Generated APKs still add
no provider or Android permission. A compatible current Jarvys host is required.

The native screen shows the requesting app, name, declared MIME and size. ALLOW_ALWAYS opens
Android's chooser automatically; ASK reviews once; DENY blocks it. Its FileProvider grants read access only to one registered private URI;
there are no write, prefix or persistable grants. The host copy expires in five minutes. New opens recheck live saved authority. Registry
loss on process restart rejects old reads. Closing/cancelling cannot recall an already opened file
descriptor, recipient copy or completed network transfer; recipients may upload data remotely.

The response is `{chooserOpened: true|false, deliveryConfirmed:false}` after chooser return and
recipient-lease detachment, without another host closure acknowledgment. A chooser callback,
selected target, cancelled UI or timeout never proves delivery or recipient closure. The detached
lease remains bounded by expiry and policy. **Settings → Factory file sharing** reconciles uncertain
state without claiming OS closure. No lost authority is restored. `documents.cancel()` closes the source-side transfer
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
- Ordinary native/SDK pending admission is bounded at 16, with bounded reserved controls for documents.cancel, database.cancel, sensors.cancel and biometrics.cancel (SDK maximum 20). Incoming ordinary bridge traffic is limited to 80 calls per 10 seconds; specialized control admission and bounded workers retain their own family rules.
- No GPS location, direct camera/microphone capture, notifications, bulk contacts/calendar access, alarms, Bluetooth, embedded network, arbitrary filesystem reads/intents or JavaScript runtime-permission grants. Documents permits only selected temporary streams; photos uses bounded system intents; contacts returns one selected datum; calendar opens a typed editor. New APIs require a reviewed runtime/template extension, appropriate manifest changes, typed policy, denial-path tests and explicit capability declaration.

## Verification

Parent build runs `:apk-runtime:testReleaseUnitTest` and `:apk-runtime:assembleRelease`. Optional browser-only fixture smoke (requires Playwright and a usable Chromium): `CHROMIUM_PATH=/usr/bin/chromium node apk-runtime/tests/smoke-notes.cjs`. The current cloud environment blocks Chromium Unix sockets, so that smoke has not completed here.

Standalone SDK tests: `node --test apk-runtime/tests/sdk.test.cjs` from `app/`.

JVM coverage: origin/subframe rejection, JSON/schema/method/argument validation, capability denial, config/package binding, hostile asset URLs, CSP constraints, storage entry/byte quotas, persistence across backend reconstruction, and backend isolation, and overlapping Activity-store concurrency. Storage tests use a durable filesystem fake, not an Android emulator; they do not prove Android platform sandbox behavior. Node coverage includes request correlation, native rejection, malformed replies, unavailable bridge, payload/pending limits, page teardown, and timeouts.

UX35 adds 47 Robolectric cases across API 24, 26, 28, 29, 34 and 35 for light/dark contrast, one safe-area owner, keyboard union/dismissal, rotation, cutouts, native metadata/child dispatch, gesture preservation and protected errors. Four optional JARVYS_UX35_CAPTURE_DIR images draw the actual native root with labeled native content; they do not render Chromium, System UI or a real IME.

Device acceptance still required: install two differently named/signed generated packages, save/relaunch notes, inspect package/data isolation, export with save/cancel, clipboard ALLOW_ALWAYS/ASK/DENY and pending-request revocation, share chooser/cancel, old WebView fallback, and adversarial same-origin iframe attempts. Do not call this end-to-end validated without those device checks.

Also validate real WebView fields/scroll with repeated keyboard open/close, portrait/landscape cutouts, light/dark system theme, and gesture/three-button navigation on supported devices. Injected-inset host tests cannot certify those platform behaviors.

## Shared controller and preview adapters (F0c)

The generated launcher delegates to `FactoryRuntime` in the resource-ID-independent `:factory-runtime-core` library. Installed startup still parses configuration against `Activity.getPackageName()`. The core owns WebView security setup, asset serving, strict bridge validation, dispatch, queues and page-generation reply cancellation. `FactoryDispatcher` shares native method behavior with the bounded synthetic test harness; installed effects are explicit Android adapters, while preview effects return declared simulation metadata without performing them. The v67 documents methods explicitly reject `UNAVAILABLE` in preview, with no fake handle or real picker; schema/protocol remain 1.

A separate private Jarvys Activity uses verified immutable build assets and an isolated native RAM backend. Its explicit preview metadata reports the real host package separately from declared appId. A provider-specific isolated profile is mandatory; there is no default-profile fallback. The controller does not change process-global cookies or debugging in preview mode. Profile cleanup is best effort and profiles may be disk-backed. Native UI/host tests do not establish actual provider cleanup, installed UID isolation, hardware, device rendering or absence of traffic.

`start()` and `reset()` return whether native initialization and the load request were accepted, not whether rendering or JavaScript succeeded. Old page/reply generations are invalidated, with pending interactive ownership retained when required to reject stale system results. Installed provider I/O does not hold the lifecycle monitor used by UI teardown; cancellation cannot roll back an already-started external write. See [Factory preview/test contracts](../../APK_FACTORY.md#f0c-functional-preview-and-bounded-shared-core-tests) for exact tool scope and evidence limits.

## Photos (v69)

`Jarvys.photos.pick({})` and `Jarvys.photos.capture({})` require both photos and documents.
Their strict empty argument object accepts no filenames, URI, paths, actions, target packages,
MIME choices or flags. Preview returns UNAVAILABLE. The pinned compatible Jarvys host must
verify this exact installed app against its latest signed receipt. Saved photos.pick/capture policy
controls launch: ALLOW_ALWAYS advances, ASK reviews once, DENY blocks. Android selection/capture
remains; validated delivery needs no second Use confirmation. The runtime receives a native-only Binder snapshot and returns only:

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
installed generated app can use the authenticated host; saved browser.open policy controls dispatch. Preview is unavailable.
URL is exact printable ASCII HTTPS, at most 2048 characters, strict lowercase DNS hostname and
optional canonical :443, no credentials/userinfo/fragment, backslash/controls or malformed escapes.
Escaped controls/space/DEL/backslash are rejected; no normalization or secret detection is claimed.

ASK reviews the full URL, transmission disclosure and recipient once. ALLOW_ALWAYS proceeds with
a verified Android default browser or waits for recipient choice; DENY blocks. No caller-controlled
component/extra/flag or implicit fallback. The host owns durable one-shot launch/recovery and
completes after dispatch without a second manual closure acknowledgment. Result `{launchRequested,pageLoadConfirmed:false}`
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

ASK reviews complete input and recipient once, including external network/accounts/history.
ALLOW_ALWAYS dispatches to a verified default or waits for recipient choice; DENY blocks. Constructed geo ACTION_VIEW and tel ACTION_DIAL only; no implicit fallback.
Separate broker/recovery namespaces preserve v71, with shared admission and ASK-specific protection.
Five-minute expiry, source cancellation/death, lifecycle loss and interrupted preparation cannot
reapprove a launch. Dispatch completes without another closure acknowledgment; recovery preserves
uncertainty and never proves OS closure. Receipt `{launchRequested,actionConfirmed:false}` proves only launch request,
never display/navigation/calling/delivery. Preview unavailable; documents.cancel is unrelated.
Metadata distinguishes external recipient network use from the embedded WebView offline scope.
Generated apps keep zero permissions and the existing exact two-host queries. Rebuild old generated
APKs to gain this runtime. Complete grammar, recovery and physical limits:
[APK_FACTORY](../../APK_FACTORY.md#typed-maps-and-dialer-launch-ux42-f1-v72).


### Email/SMS editors, v73

`Jarvys.email.compose({to,subject,body})` requires only email;
`Jarvys.sms.compose({number,body})` requires only sms. Exact required string fields; subject/body
may be empty. One conservative ASCII email address (<=254 characters): local part <=64,
`[A-Za-z0-9_+\-]+(\.[A-Za-z0-9_+\-]+)*`; domain >=2 labels, <=253 total, each 1–63
letters/digits/hyphens with alphanumeric ends. SMS number is optional + and 1–15 ASCII digits.
No normalization or existence guarantee. Subject <=256 code points/1024 UTF-8 bytes; body <=4096
UTF-8 bytes. Valid paired Unicode only; reject ISO controls, format/bidi and line/paragraph
separators, except LF/TAB in body. CR is rejected. Editor argument JSON <=32768 bytes; maps/dialer
stay <=8192. No CC/BCC, attachments, recipient lists, supplied URI/extras or contacts access.

The v72 closed broker constructs ACTION_SENDTO mailto/smsto only. Email local part is encoded
once (including + as %2B), with fixed EXTRA_SUBJECT/EXTRA_TEXT; SMS uses fixed sms_body. No URI
query carries editor text and no implicit/ACTION_SEND fallback exists. Same exact latest signed
APK, pinned host and selected recipient verification, no new permissions, zero-permission generated
nonbiometric apps and two exact host queries. The native view shows full labelled fields and the
selected app. ASK protects one approval; ALLOW_ALWAYS dispatches to a verified default or waits for
recipient choice; DENY blocks. FLAG_SECURE and disabled saved state/autofill/content capture remain. Opening shares fields: recipient apps may ignore/change them, sync drafts or
transmit data using their own accounts/network. Jarvys does not press Send or control those apps.

Five-minute expiry, lifecycle/source revocation and durable consumed launch/no replay remain.
There is no second external-task closure acknowledgment after dispatch. Settings → External action
review reconciles unknown outcomes without proving OS closure or restoring authority. Receipt
`{launchRequested,actionConfirmed:false}` confirms only dispatch acceptance, never display,
draft saving, sending or delivery. Cancellation cannot retract data or dismiss recipients.
Preview returns UNAVAILABLE; documents.cancel remains unrelated.

runtime.info adds emailRequires/smsRequires, externalEditorsMaySyncDrafts:true and unavailable
preview methods/capabilities. Limits: emailAddressCharacters=254, editorSubjectCodePoints=256,
editorSubjectBytes=1024, editorBodyBytes=4096, editorArgumentsBytes=32768. Offline still means only
the embedded WebView. Physical editor acceptance, transmission and installation are unproven.
TTS/voice and remaining F1/F2/F3 pending, UX34 gated, UX44 documentation-only future defaults,
UX43 last. Full contract and official sources: [APK_FACTORY](../../APK_FACTORY.md#email-and-sms-editors-ux42-f1-v73).

## Remaining current families and native authorization

- audio.play consumes a bounded untouched document handle for canonical PCM16 WAV (at most 30 seconds). ALLOW_ALWAYS plays using current output/volume, ASK reviews once, and DENY blocks. Confirmed player cleanup ends ALLOW_ALWAYS work without a separate audibility attestation; uncertain cleanup keeps admission. playbackAttempted never proves audibility. [Full audio contract](../../APK_FACTORY.md#v70-one-shot-local-pcm-playback).
- contacts.pick({kind:"phone"|"email"}) needs contacts and the exact pinned/latest-signed host relationship. ASK reviews once before the Android picker; ALLOW_ALWAYS opens it automatically. One validated selected datum returns without a second frozen-value approval; no broad queries or automatic call/send. [Contact contract](../../APK_FACTORY.md#single-contact-datum-selection-ux42-f1-v74).
- calendar.insert opens a typed calendar editor under saved operation policy. ASK reviews fields/recipient once; ALLOW_ALWAYS uses a verified default or waits for recipient selection. It does not press Save, create events directly or prove saving. [Calendar contract](../../APK_FACTORY.md#calendar-event-editor-ux42-f1-v75).
- Ordinary typed SQLite, local presentation and foreground sensors remain direct runtime methods, with declared capabilities and fixed technical quotas. Sensors support light, accelerometer and gyroscope; no background subscription. Presentation and sensors are UNAVAILABLE in preview. SQLite preview is ephemeral; installed stores persist, with no physical-durability promise.
- Biometrics is a direct installed API30+ BIOMETRIC_STRONG OS prompt, unavailable on API24–29 and in preview. Only the person operates the prompt. Success is local authentication with authorizesOtherActions:false; it is never permission to sign, install, send or release data. Factory ALLOW_ALWAYS cannot bypass that OS prompt. [Biometrics contract](../../APK_FACTORY.md#local-strong-biometrics-ux42-f1-v80).

## Selected data backups and live revocation (v83/v84)

The four APIs are database.backupExport({version,tables,filename}), database.backupImport({version,handle,tables,mode}), storage.backupExport({version:1,keys,filename}) and storage.backupImport({version:1,keys,handle,mode}). All require documents plus their database/storage capability, the current exact signed receipt and the matching pinned host. Optional dataBackup.tables (up to eight sorted unique identifiers) and dataBackup.keys (up to 32 sorted unique string keys) default empty and independently disable the relevant family. Calls explicitly select a nonempty allowed subset; an allowlist alone is not operation authority.

ALLOW_ALWAYS proceeds with saved authorization; ASK reviews all values, selected tables/keys, mode and overwrite impact once; DENY blocks. Export still needs an Android destination selection, without a second host approval. Import consumes an untouched document read handle; documents.open has its own operation policy and Android selection, without an extra Use action. File selection and package/certificate labels do not authenticate an archive or independently authorize restore. Archives are bounded plaintext JSON (at most 1 MiB) and may contain sensitive app data. Secrets are unsupported; archive labels and arbitrary-value scanning cannot certify secret-free content. Providers may upload, cloud-sync or retain copies.

The new native backup request is protocolVersion:2. Its host response binds the nonce and plan hash and supplies a mutually authenticated Binder authorization lease, kept live until completion, revocation or deadline. Runtime checks that authority before each export write and each import commit. Policy changes, identity/lifecycle loss and cancellation stop future checked effects, without undoing a write or commit that already happened. The host still accepts authenticated legacy protocolVersion:1; its final host authorization is the last enforceable boundary because old DEX has no later checkpoint. This native v2 transport does not change SDK 2, schema/bridge protocol 1, archive formatVersion:1 or runtime.info's backup data-protocol version 1.

SQLite requires the exact existing version/schema; insertOnly rolls back the transaction on any selected ID collision, while replaceSelectedTables atomically replaces only selected tables. Storage insertOnly rejects any existing selected key, even equal values; replaceSelectedKeys writes selected keys only. Target changes since the prepared preimage fail instead of overwriting newer state. No combined SQLite/storage transaction, migration or signing-key backup is provided.

Storage commit false/exception is an unknown outcome, with no rollback promise. Lost reply, cancellation, timeout or process death can follow mutation in either family; inspect state before any fresh request and never replay automatically. Export returns providerCommitConfirmed:false and closeStatus:"close_requested", with no durability/cloud-completion/independent-restore proof; partial or empty files may remain. Native backup authority lasts five minutes, SDK timeout is 310000 ms. documents.cancel and database.cancel share the native backup control budget; no storage.cancel exists. Both backup families reject UNAVAILABLE in preview before allocating their source snapshot or document/database handle.

The exact full contracts are [SQLite backup](../../APK_FACTORY.md#selected-sqlite-data-backup-v83) and [selected string backup](../../APK_FACTORY.md#selected-string-key-value-backup-v84). Host tests do not close physical Android, provider, Binder, installation/update or independent-restore gates.

## Packaged local UI library (UX42 F1 v81)

Every new generated APK contains the exact Pure.css 3.0.0 distribution stylesheets plus
BSD-3-Clause and embedded normalize MIT notices and provenance. This is fixed-inclusion
runtime data, not a capability: its original v81 catalog was 20 capabilities/42 methods;
the current v84 catalog has 46 methods. SDK2/schema1 and permissions are unchanged by the library. `apk_factory inspect` exposes `localUiLibraries` with exact paths, bytes,
SHA-256, licenses and inclusion policy. Public build provenance repeats this inventory;
project-source hashes continue to describe only actual project inputs.

Link `/factory-ui/pure-3.0.0/pure-min.css` and
`/factory-ui/pure-3.0.0/grids-responsive-min.css`, then a project stylesheet. The usable
[local UI example](../../examples/factory-local-ui/README.md) demonstrates responsive columns,
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
