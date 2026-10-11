# Ping Notebook: packaged-DEX multiview example

A representative Factory app, not a generated APK or a device-validation claim.
Copy this directory into an authorized Coding project, inspect the actual Factory
contract/template, then build `factory.json` to a new output path using the current
scope version. Preview the exact returned APK/hash. Signing and installing use
their separately authorized native flows. Customize the app ID/name/icon before
real distribution; an update must retain its ID/key and increase its version.

The generated app loads the runtime-owned `/factory-sdk.js` and Pure.css from
`/factory-ui/pure-3.0.0/pure-min.css`. Do not copy or replace the SDK, generated
config/host pin, manifest or DEX. `factory.json` follows the existing example's
project schema. Factory derives the generated `documentBroker` host binding and
Android manifest scope from the selected capabilities. Opening `index.html` as a
file is not an installed-runtime test.

## Three views and native boundaries

- **Ping:** domain/IPv4/IPv6 input, one explicit foreground `Jarvys.network.ping`
  operation, cancel, optional real utility output and a selected result. Host
  syntax and all operation limits are validated again in native code. The app
  never uses HTTP as a ping fallback: `network.probe` measures HTTP response
  headers, whereas this example requires `protocol:"icmp"`. `elapsedMs` is
  operation duration; only `latencyMs` is measured ICMP RTT.
- **History:** up to 30 summaries/errors saved through `Jarvys.storage`, with
  explicit clear confirmation and no automatic startup probe. Raw utility output
  and contact numbers are never saved. Invalid/unreadable history is not silently
  overwritten; new results remain session-only until the user clears it. Failed
  writes stay visible and are not retried. The oldest entries are trimmed when
  necessary to fit the 64 KiB UTF-8 value quota. Preview storage is temporary.
- **Settings:** bounded packet/family/deadline controls; opt-in haptics and
  immediate own-app notifications. Saving the option grants no OS permission.
  Only the explicit setup button invokes `notifications.requestPermission`, then
  creates the app's `ping-results` channel when permission permits. Status is
  reread before posting; accepted does not mean displayed/delivered. Notification
  ID 1 replaces this app's previous result. Removal is explicit. There is no
  background service, scheduled work, notification listener or other-app access.

Haptics use only `haptics.perform("tap")`; Android settings can suppress it. This
is not an arbitrary vibration-pattern API. Contact selection returns only the
chosen phone datum, unchanged. The user checks or edits its format on screen.
SMS uses the supported editor; WhatsApp uses `browser.open` with a bounded HTTPS
`wa.me` link and an explicitly reviewed international phone number. The button's
disclosure covers sending that number and the summary to the browser/website.
That query uses a single-line summary and form-encoded `+` spaces to respect the
native browser URL grammar, which rejects percent-escaped whitespace.
The generic `share.text` chooser is another route to WhatsApp when installed,
without contact preselection. No route promises app availability, sending or
delivery; external editors may sync drafts. No number normalization, contact
lookup, direct message sending or arbitrary Android intent is invented.

Selected capabilities are exactly `storage`, `network`, `notifications`,
`haptics`, `contacts`, `sms`, `browser`, `share`. Network contributes `INTERNET`;
notifications contributes `POST_NOTIFICATIONS`. Real Android permission and the
current authenticated host's per-operation policy still apply. WebView local
assets do not imply process-level network isolation. System utility availability,
flags, platform denial, DNS errors and unparsed output are displayed honestly.
Missing statistics stay missing. A cancelled/deadline-expired operation records
its returned error, never a fabricated zero-packet result or rollback guarantee.
A result winning a cancellation race remains authoritative. A new run cannot
start until that cancellation response has settled. Navigation between these
views is local UI state with browser Back/Forward support. Leaving/closing the
native app can end work; no auto-resume or automatic replay is implemented.

## Focused, offline checks

From this directory, run:

```sh
node --test tests/ping.test.cjs
node --check web/model.js
node --check web/app.js
```

Tests use only in-process fake SDK receipts/rejections; they never run `ping`,
make HTTP/DNS requests, contact recipients, post Android notifications or build
an APK. Fixture results are confined to tests, never shipped as runtime samples.
They cover unavailable/preview behavior, persistence and corruption, real-schema
statistics, missing results, failures, cancellation races, repeat actions,
notification permission separation, phone review and compose/share arguments.

## Remaining physical acceptance

Use only an explicitly authorized target and recipient in a signed/installed app:
check ICMP success/no reply/unsupported/denial/DNS/deadline/cancel on the real OEM,
foreground loss with no replay, persisted history after relaunch, Android 13+
grant/deny and blocked channel, notification tap/removal, suppressed haptics,
contact-picker cancellation and invalid selected phone formatting, SMS/browser
handler availability, chooser return without send, and unexpected response loss.
Check narrow/wide and enlarged text, keyboard and insets, light/dark, view
Back/Forward, repeated clicks, clear-confirm dismissal and long/error text.
Host-side checks are not proof of WebView layout, device network behavior,
notification delivery, message sending or phone acceptance.
