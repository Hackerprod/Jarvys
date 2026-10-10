# UX42 F1 v75: calendar event editor

Version 75 / `1.2.68-FACTORY-CALENDAR`: final host tests, exact lint, three APK builds and independent source/binary audit passed. Signed native delivery was accepted. This is not physical Android acceptance or complete Factory.

The strict `calendar.insert` capability reuses the authenticated latest-source-APK external editor
broker and durable single-use human review. Fixed ACTION_INSERT/event URI/MIME, seven exact native
extras; no database access, permission changes, attendees/invitations/recurrence or automatic saving.
Native review shows every field and source/recipient identity. Requested timezone/all-day/times may
be ignored or changed by another app; it may save, transmit or cloud-sync the draft. A launch receipt
never proves creation or saving. Full bounds and lifecycle contract are in [APK_FACTORY](../../app/APK_FACTORY.md#calendar-event-editor-ux42-f1-v75).

All tests use synthetic data and intercepted dispatch, not real calendar writes/navigation. No
physical Android/OEM/installation/update acceptance is claimed. Other F1/TTS/voice/F2/F3 remain open;
UX34 is gated, UX44 remains documentation-only future defaults, and UX43 remains last.

An inherited coverage gap was found: the v74 template matrix iterated 32,768 masks but read only
14 capability bits. v75 expands and verifies all 16 bits/65,536 profiles. This does not retroactively
claim that v74 tested every profile. Existing prior assertions and gates are retained.

## Retained exploratory validation

The first full aggregate ran 3,495 Full cases with one failure in the unchanged Factory-skill
scope fixture: compression had removed four required legacy phrases. The original assertions
were preserved and the exact wording restored; the corrected asset is 16,352 bytes, below16KiB.
The failed aggregate and its prior lint/source freeze remain retained separately. Both flavors’
skill/contract fixtures passed 59+59 cases with unchanged assertions; their guards blocked one
network attempt per flavor. Fresh final lint/full aggregate/builds were then repeated and passed.
A separate wrong-working-directory invocation launched zero test JVMs and is retained as failed.
Calendar’s preliminary 613 core, 72 runtime and 276 focused Full cases and SDK24 also passed.
No real calendar editor or provider operation was performed.

## Final frozen-source host receipt

Implementation checkpoint `8cf77b0`, matrix correction `f096815`, skill wording `37f2679`,
current-catalog documentation `4757040`. App tree `4621480e74e0f562bdf1ba56f9216093f6928f10`.
All 1,093 app inputs match across final lint, aggregate, build and post-build freezes.

Fresh six-task aggregate: Full 3,495; Play 3,113; runtime 72+72; core 613+613, total 7,978,
zero failures/errors/skips. SDK24/24. All 17 test JVMs have complete offline-guard installation
and shutdown evidence. The guard blocked 16 HTTP(S) attempts, eight per host flavor and zero
in runtime/core; per-JVM totals do not attribute attempts to individual tests. Fresh lint
preserves exact prior diagnostic multisets 313/300/3/2 with no additions/removals/suppressions.
No lint-reuse waiver is needed on the final snapshot.

Independent review passed source, v63–v74 retained tests, normal commit history, guard accounting,
actual three APK ZIP/DEX/manifests/resources/permissions/assets/native libraries and embedded
zero-permission runtime templates. Calendar DEX contains only the fixed URI/MIME/seven extras
and no calendar-provider or permission calls. The sole new host visibility query and nine
localized calendar strings are expected; existing permissions remain unchanged.

Actual unsigned APKs, version 75 / `1.2.68-FACTORY-CALENDAR`:
- FullDebug: 35,197,888 bytes; SHA-256 `575bed754b5b51b5f292ee2cdbe50a5687cddde2da3b90b84354b8a731caad99`.
- PlayDebug: 33,586,154 bytes; SHA-256 `dc4a05ebc3c53970682940fd1837551a467824bf1cbf3df10a30f22140fd3b45`.
- FullRelease: 27,486,381 bytes; SHA-256 `49e9b0c63bc8a71c87054df367e7d084c35935a960c2b9da4387740dae7c86c8`.

Physical Android/Binder/lifecycle, OEM editor/timezone/all-day semantics, installation/update,
calendar saving and actual external-task closure remain unverified. No automatic saving or
calendar database access was added. Signed delivery is recorded below; it does not close those physical gates.

## Signed native delivery

Native attachment of `Jarvys-Factory-calendar-full-v75-arm64-test.apk` was accepted on
10 October 2026 at 15:30:02 UTC. Size: 19,289,659 bytes; SHA-256
`317f27a5f25529af62254683d4055c939d1827dd2b2e08a05f2184ed7bacd3b4`.
Existing approved D7 test identity, SHA-1
`D7:C0:1F:59:79:78:32:3E:32:CA:AF:22:E9:F6:00:9A:B7:2F:32:B8`, verifies APK v2/v3.
CRC and 16 KiB ZIP alignment pass. The manifest and all 239 retained unsigned-source entries
are byte-identical; only nine non-ARM64 native libraries are omitted, with signing metadata added.
Package remains `com.jarvys.agent`, version 75 / `1.2.68-FACTORY-CALENDAR`, ARM64, minimum API24.

This is a test-key artifact; it cannot update an installation signed by another certificate.
Accepted attachment is not confirmation of download, installation/update, preserved data, a rendered
calendar editor or a created/saved event. ZIP alignment does not certify ELF16KiB page compatibility.
TTS/voice, sensors/biometrics and other F1 families, F2/F3 and physical gates remain pending;
UX34 stays gated, UX44 stays documentation-only future defaults and UX43 remains last.
