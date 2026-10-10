# HTTPS browser launch, v71 / 1.2.64-FACTORY-BROWSER

Implementation checkpoint, not a completed release. Independent design review preceded edits.
Browser is a standalone capability using existing build-pinned host metadata and deduplicated
host package visibility. It does not imply documents or generated Internet permission.

Native full-URL review, browser choice, exact caller/recipient identity, one-shot durable launch
and protected human recovery are implemented with public Android APIs. No arbitrary intents,
JS components/extras/flags, generated network client or remote WebView access.

Only initial URL HTTPS is enforced; external browser DNS/redirects/resources/network policy are
outside Jarvys control and disclosed. Launch acceptance is not rendered browser, page loading or
delivery. Recovery records human closure acknowledgment, never verified external task closure.

All validation must remain synthetic. No actual navigation, installation or user-data transmission.
Preliminary focused validation passed 361 core + 72 APK-runtime cases and 16 SDK cases, with
zero failures/errors/skips and zero blocked attempts in both guarded JVMs. First-run failures
were JSONObject comparison and synthetic installed-UID fixture mistakes, retained and corrected.
Initial host production Kotlin compilation passed. Later metadata/startup/test additions still
require fresh coverage; host focused/full suites, exact lint, three-APK independent audit,
signing/native delivery and physical acceptance remain pending at this checkpoint. Host reports stay outside
the repository. Preserve v63–v70 and TTS/voice, other F1, F2/F3, UX34 and UX43-last gates.

Detailed scope and current official references: [APK_FACTORY](../../app/APK_FACTORY.md#https-browser-launch-ux42-f1-v71).

Initial host-focused suite passed 71 synthetic cases (18 Activity, 16 coordinator, 34 target
API-matrix cases and three actual Application startup cases). This includes blocked final
recipient verification with pause/focus/source cancellation and close-callback receipt races.
Additional cold-start ordering and partial-obscuration regressions are being added before freeze.
Runtime metadata explicitly scopes legacy offline:true to embedded_webview and declares that
the external browser may use its own network. All later changes need fresh final validation.

Expanded host validation passed 167 cases with zero failures/errors/skips, including 78 browser
cases: 23 Activity, 18 coordinator, 34 target API-matrix and three actual Application cases.
All seven JVMs have guard launch/install/shutdown evidence. Browser cases ran with zero blocked
attempts. One attempt was blocked during an unchanged skill-suite worker; the guard stores no
URI/stack, so its cause is not established. No zero-total-network-attempt claim is made.
The direct new browser-only/combined APK packaging test and one new runtime metadata assertion
remain pending the final full aggregate. Exact lint and all release gates remain pending.
