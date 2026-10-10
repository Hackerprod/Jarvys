# HTTPS browser launch, v71 / 1.2.64-FACTORY-BROWSER

Host validation completed on the frozen source commit `fec86ffdd864fd6ddeb6f922674960953206688b`,
app tree `2b7e61ddb58c03be0d525925565b646b154c6411`. Native signed delivery was accepted on 2026-10-10 at 10:47:25 UTC.

## Scope and limits

`browser.open({url})` is a standalone browser capability, with no implied documents access.
The exact initial HTTPS URL is bounded to 2,048 ASCII characters and validated without rewriting;
URL userinfo, fragments, local/IP host forms, unsafe escapes and arbitrary intent fields are rejected.
This syntax validation does not classify every secret or establish that a destination is safe.
Native human review displays the complete URL, destination host, exact selected browser component
and data/network disclosure. Both the caller's latest signed installed APK identity and the selected
recipient are rechecked. Shared browser UIDs, app-link-only handlers and unsupported profiles are rejected.

The one-shot launch is journaled before dispatch. A protected durable recovery guard remains until
human acknowledgment of manually closing the browser screen/task; this is never OS-verified closure.
Source cancellation, timeouts, pause/focus loss and startup races cannot silently reapprove a launch.
There is no generated INTERNET permission, generated network client, remote WebView access or
arbitrary JavaScript intent/component/extra/flag interface. Preview reports unavailable.

Only the initial URL is constrained to HTTPS. External browsers may use cookies, signed-in accounts,
history/sync, DNS, resources and redirects, including other destinations, HTTP or apps. These external
browser behaviors are disclosed and are outside Jarvys control. Jarvys does not fetch or follow them.
`launchRequested` means the native launch call returned; it does not prove browser rendering, page
loading, delivery or closure. `pageLoadConfirmed` is always false. Runtime metadata scopes legacy
`offline:true` to the embedded WebView and explicitly declares external browser network use.

## Validation

All 1,052 app inputs stayed frozen through the six-task fresh aggregate, lint and three APK builds.
The aggregate passed 6,858 cases with zero failures/errors/skips: Full 3,186, Play 2,804,
APK runtime 72+72 and core 362+362; SDK 16/16. Required browser-only/combined packaging, runtime
metadata, shared-UID and lifecycle/overlay regressions ran. All 17 test JVMs had verified guard
launch/install/shutdown evidence. Guards blocked 16 external HTTP(S) attempts, eight per host flavor,
matching v70; only per-JVM totals are recorded, so individual attempts are not attributed to tests.
Runtime/core had zero blocked attempts. The initial 71 browser-only cases and final 37 target cases had zero blocked attempts;
the expanded 167-case host run recorded one block in an unchanged skill-suite worker.
All tests used synthetic data; there was no real browser navigation or user-data transmission.

Lint exactly preserves the four inherited diagnostic multisets, 313/300/3/2, without added/removed
diagnostics or new suppressions. Three unsigned APKs were built from the same frozen sources.
Full release: 27,408,921 bytes, SHA-256
`0becc16b3dc1f203c67c0a8c5247ca01f9108b889616c0ceab2c722a1d3cbcd3`.
Independent final source, history, guard/evidence, lint and actual three-APK audit passed.
Release D8 lambda renumbering and public-API outline movement were checked with narrow bytecode
equivalence, not blanket exemptions. Prior permissions, native libraries and security families remain intact.

Exploratory evidence is retained separately: the first runtime run exposed JSONObject equality and
synthetic installed-UID fixture mistakes, corrected without production weakening; one focused
invocation used the wrong Gradle directory and failed before tasks/workers, then passed from the
correct directory; initial lint found one UseKtx warning, corrected without suppression. The final
fresh aggregate and exact lint were rerun after the final source change. Earlier focused results were
361 core + 72 APK runtime, 71 host, expanded 167 host and final 37 target cases across API 24/32/35.
Host reports, logs, generated test data, APKs and secrets are not published to the repository.

## Signed delivery

`Jarvys-Factory-browser-full-v71-arm64-test.apk`: 19,207,739 bytes, SHA-256
`5a4e13272d76c08d97891767277589ca21f3b5f60522a81cfbde69956e7e381e`.
Existing D7 test signer verified v2/v3, ZIP CRC and 16 KiB alignment; manifest unchanged,
239 retained entries byte-identical and only nine non-ARM64 libraries omitted. Native send accepted
on 2026-10-10 at 10:47:25 UTC; this does not confirm download, installation or physical acceptance.

Signing/native delivery and physical acceptance are distinct. No device, installation, live browser,
real cross-process Binder, page/network delivery or browser-closure acceptance is claimed here.
Preserve v63–v70; other typed external actions, strict TTS/voice and remaining F1 work stay pending,
F2/F3 remain closed, UX34 retains its gates and UX43 remains last.

Detailed contract and current official references: [APK_FACTORY](../../app/APK_FACTORY.md#https-browser-launch-ux42-f1-v71).
