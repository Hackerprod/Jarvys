# Reusable Jarvys APK runtime v1

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

The default `src/main/assets/www` is a runnable offline-notes acceptance fixture. Replace those assets for other apps; the native runtime contains no notes-specific behavior. The fixture provides explicit save, reopen, delete, and export flows. It limits notes to 20, each 60 title characters and 600 body characters, to fit native storage quotas. It does not silently fall back to temporary browser memory if native storage fails.

## Shared v1 contract (UX42 F0a-1)

`:factory-contract` is a dependency-free Java library shared with the factory. Its immutable CapabilityCatalog contains exactly six implemented capabilities and ten methods. FactoryConfig, BridgeProtocol and explicit FactoryActivity dispatch use it, and SDK parity tests compare the actual JS calls with catalog/validator/handler cases. No reflection, new bridge API, schema v2 or SDK v2 is introduced.

The factory uses a closed immutable ManifestPlan and a separate read-only AXML auditor to validate the actual compiled tree, typed attributes, resource bindings and backup rules before/after packaging and signing. All 64 selections still request zero Android permissions; only the existing launcher is exported. Every classes*.dex name/hash and all signed payload entries are checked. F0a-2 adds typed construction primitives and a deterministic AXML encoder with explicit parent identity. Additional nodes are host fixtures only: every current catalog contribution stays empty and the production plan rejects nonempty contributions. New selectable nodes/resources/native capabilities and complete F0a remain gated. See [the packaging contract](../../APK_FACTORY.md#closed-capability-and-manifest-contract-ux42-f0a-1).

F0a-3 additionally verifies the complete resource-table structure and symbol identities, required compiled icon/backup files, and actual public concrete launcher/component-factory DEX class definitions with their superclass and constructor. Immutable inspect evidence identifies the verified bindings. DEX metadata integrity checks do not replace template authentication or Android bytecode/launch acceptance. Signing discloses additions/removals against an anchored last-signed scope snapshot, or explicitly says the baseline is unavailable for older records. No runtime schema/SDK/capability change occurs.

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
| `Jarvys.clipboard.write(text)` | `null` after native confirmation | `clipboard` |
| `Jarvys.haptics.perform("tap" or "longPress")` | Boolean, respects system haptic settings | `haptics` |
| `Jarvys.device.info()` | Android platform/API level, actual app ID, target SDK | `device` |

Errors reject with `error.code` and readable `error.message`. Handle `CANCELLED`, `CAPABILITY_DENIED`, `INVALID_ARGUMENT`, `QUOTA_EXCEEDED`, `PERMISSION_DENIED`, `BUSY`, `RATE_LIMITED`, `UNAVAILABLE`, `TIMEOUT`, and persistence errors. SDK timeouts do not revoke a system action the user already chose. It times out normal calls after 30 seconds and interactive calls after 10 minutes. Closing/replacing the page invalidates reply handles. Introspection does not expose device/user data.

Storage keys match `[a-zA-Z0-9_.:-]{1,96}`. Values must be strings; explicitly JSON stringify structured app data. Value limit 64 KiB UTF-8, total keys+values 1 MiB, at most 256 entries. Read/quota/write transactions share a process-wide lock across Activity instances to prevent lost updates during recreation. Storage is private app `SharedPreferences`, scoped by the real package name, and backed up by neither Android Auto Backup nor this runtime.

Exports are UTF-8 text, up to 256 KiB (and always subject to the encoded message limit), using `ACTION_CREATE_DOCUMENT`. Supported MIME types: `text/plain`, `text/markdown`, `application/json`, `text/csv`. Native code never takes an arbitrary filesystem path or URI from JavaScript. Only the system picker result is accepted, and the transient content URI write grant is checked at time of use. No broad storage access or persistent grant is requested. Export success is reported after writing and closing the document stream. Cancellation is a rejected Promise.

Sharing uses a fixed `ACTION_SEND` text/plain system chooser. Clipboard is write-only and requires a visible native confirmation; on Android 13+ it is marked sensitive to suppress the system preview. No arbitrary intent, package, class, component, shell command, file URI, network URL, reflection invocation, or permission grant can be supplied.

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
- No location, camera, microphone, notifications, contacts, alarms, Bluetooth, network, filesystem reads, arbitrary intents, or runtime permission grants in v1. Adding these requires a separately reviewed reusable-runtime release, manifest changes, typed policy, denial-path tests, and explicit capability declaration.

## Verification

Parent build runs `:apk-runtime:testReleaseUnitTest` and `:apk-runtime:assembleRelease`. Optional browser-only fixture smoke (requires Playwright and a usable Chromium): `CHROMIUM_PATH=/usr/bin/chromium node apk-runtime/tests/smoke-notes.cjs`. The current cloud environment blocks Chromium Unix sockets, so that smoke has not completed here.

Standalone SDK tests: `node --test apk-runtime/tests/sdk.test.cjs` from `app/`.

JVM coverage: origin/subframe rejection, JSON/schema/method/argument validation, capability denial, config/package binding, hostile asset URLs, CSP constraints, storage entry/byte quotas, persistence across backend reconstruction, and backend isolation, and overlapping Activity-store concurrency. Storage tests use a durable filesystem fake, not an Android emulator; they do not prove Android platform sandbox behavior. Node coverage includes request correlation, native rejection, malformed replies, unavailable bridge, payload/pending limits, page teardown, and timeouts.

UX35 adds 47 Robolectric cases across API 24, 26, 28, 29, 34 and 35 for light/dark contrast, one safe-area owner, keyboard union/dismissal, rotation, cutouts, native metadata/child dispatch, gesture preservation and protected errors. Four optional JARVYS_UX35_CAPTURE_DIR images draw the actual native root with labeled native content; they do not render Chromium, System UI or a real IME.

Device acceptance still required: install two differently named/signed generated packages, save/relaunch notes, inspect package/data isolation, export with save/cancel, deny clipboard confirmation, share chooser/cancel, old WebView fallback, and adversarial same-origin iframe attempts. Do not call this end-to-end validated without those device checks.

Also validate real WebView fields/scroll with repeated keyboard open/close, portrait/landscape cutouts, light/dark system theme, and gesture/three-button navigation on supported devices. Injected-inset host tests cannot certify those platform behaviors.
