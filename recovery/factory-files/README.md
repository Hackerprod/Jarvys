# v68 / 1.2.61-FACTORY-FILES

Status: implementation, final host gates and independent three-APK audit passed; signing/delivery pending.

Bounded `share.file` consumes an untouched read handle, maximum 8 MiB, into an immutable
snapshot. A native 32 KiB Binder protocol transfers it to the exactly authenticated
Jarvys host. Both documents/share capabilities are mandatory. Human-only native review,
durable document/share admission and a narrow expiring read-only FileProvider protect
the chooser. No raw JS paths/URIs, persistent grants, generated providers or permissions.

Chooser opened is distinct from delivery. Native human closure/recovery acknowledges
closing external tasks but cannot verify OS closure or recall copies/open descriptors.

Production compile passed. Focused host tests: 116 passed; SDK: 12 passed.
Initial core test failures were retained and fixed (strict Parcel fixture representation
and explicit WebView-feature test shadow); the subsequent core/runtime pass was 201/68.
The first aggregate failed in 30 of 2,978 Full tests due to startup fixture contamination;
the other five tasks did not run. Initial lint added three API-level diagnostics per host
variant. Test isolation and real startup tests plus API 24-compatible atomic open flags
were independently reviewed; the expanded repair suite passed 199 tests with no failures,
errors or skips. No guard was weakened and no lint suppression was added. The final
six-module aggregate passed against 1,015 frozen app inputs: Full 2,984, Play 2,602,
runtime 68+68, core 204+204, with zero failures/errors/skips; fresh SDK TAP passed 12.
All 17 test JVMs have verified offline-guard install/shutdown records; attempted network
access was blocked (8 Full, 8 Play). Exact lint multisets remain 313/300/3/2. All three
unsigned APK builds and independent artifact audit passed. D7 signing and native delivery
remain pending. Final implementation source is backed up in commit `6ede7f9`.
A cold-start admission race found by independent review was fixed with one bounded
startup waiter; both sharing and existing SAF have explicit interrupted-startup tests.
No real device, user document or third-party share has been used. Other F1/F2/F3 and
UX34 gated phases remain closed; UX43 remains last.

The actual APK audit preserved all prior resources, class definitions, permissions and
unrelated components/assets. Manifest/resource additions were limited to the approved sharing activity/provider/chooser
query, ten sharing strings and narrow paths XML. All three APKs embed the same
zero-permission template; DEX checks retain atomic close-on-exec/no-follow flags without
API 27 public-field references. These checks do not establish physical FD semantics.

Delivery input is exclusively the immutable Full Release unsigned APK, SHA-256
`a516501df1a6b4cbae8e79aed20766c2420de2ada88431e55a4053c8bc5d2bba`,
for the existing ARM64 D7 test-signing workflow. Debug artifacts are audit inputs only.
