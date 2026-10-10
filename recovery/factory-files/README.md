# v68 / 1.2.61-FACTORY-FILES

Status: implementation complete; independent design/source review approved, final release gates in progress.

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
six-module aggregate, exact lint comparison and three unsigned APK builds/audits are
rerunning against 1,015 frozen app inputs. D7 signing and native delivery remain pending.
A cold-start admission race found by independent review was fixed with one bounded
startup waiter; both sharing and existing SAF have explicit interrupted-startup tests.
No real device, user document or third-party share has been used. Other F1/F2/F3 and
UX34 gated phases remain closed; UX43 remains last.
