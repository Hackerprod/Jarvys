# UX42 F1 v77: per-app presentation preferences

Version 77 / `1.2.70-FACTORY-PRESENTATION`: final host aggregate, exact lint, three APK builds and independent
source/binary audit passed. Signed ARM64 native delivery was accepted and Library bytes independently verified.
No physical Android, window-manager, Chromium CSS, rotation or installation acceptance is claimed.

The zero-permission standalone `presentation` capability adds get/set/reset. Theme choices are
system/light/dark; orientation choices are system/portrait/landscape. Requests affect only the
installed generated app. Preview refuses all three methods and cannot change Jarvys presentation.
Private preferences and an immutable applied bootstrap snapshot distinguish saved requests from
what the current Activity selected. Only native night-mode bits are overridden; font scale,
locales, density, the existing safe-area/IME owner and contrast treatment are retained. Page CSS
must support its own dark/light artwork. Orientation is advisory and may be ignored by Android.

Mutations are serialized with existing native UI admission. Successful commit precedes a guarded
recreation request; page state/reply can be lost. Pause/navigation/close cancels queued recreation,
without undoing saved preferences. A failed commit can change process memory but does not request
recreation or claim persistence. Repeating an already applied pair is a no-op; a saved-but-unapplied
pair may be explicitly requested again. Adaptive icons/resources are outside this cut.

Design review identified and corrected an asynchronous recreation admission gap: native UI stays
blocked on the retiring page until pause/invalidation/close, including after recreate() returns.
Detailed methods, lifecycle semantics and Android source links: [APK_FACTORY](../../app/APK_FACTORY.md#presentation-preferences-ux42-f1-v77).

TTS/voice, sensors/biometrics, backup/restore, local UI libraries and other F1/F2/F3 work remain open.
UX34 keeps its gates, UX44 remains documentary, and UX43 remains last.

## Preliminary validation attempts

SDK30 and core731 passed. The first full runtime attempt had 74 cases with six failures in a
new test assertion that queried the public `isLightTheme` attribute on API24/26/28, before that
attribute existed publicly. All original UX35 assertions stayed intact. The reviewed test-only
correction checks native background contrast on those APIs and the theme attribute on API29+;
all 74 runtime cases then passed. Failed evidence is retained. At that checkpoint, complete release gates were pending.

A later pre-freeze review also bounded failures from Android's orientation getter/setter: rejected
window-policy requests no longer abort an otherwise valid Activity. A synthetic rejecting window
keeps preferences and the bridge usable. This delta passed fresh native tests and the entire final
aggregate; earlier passes are not presented as checks of this later delta.

## Final frozen-source host receipt

Implementation checkpoint `d32dad6`, API-aware theme fixture `f96b52f`, orientation rejection fix
`0fd7ac8`. App tree `467ffa90002dda3211fbf8afaa4ffe6193b944ac`. All 1,101 app inputs remain
identical across fresh lint, aggregate, build and after-build freezes.

Complete fresh aggregate: Full 3,500; Play 3,118; runtime 74+74; shared core 734+734.
Total 8,234, zero failures/errors/skips. SDK30/30. All 17 test JVMs have verified paired offline-
guard installation/shutdown evidence. Sixteen HTTP(S) attempts were blocked, eight per host
flavor and zero runtime/core; these are per-JVM totals, not attribution to individual tests.
Fresh lint preserves exact inherited diagnostic multisets 313/300/3/2, without additions,
removals or new suppressions. Independent source/history/preservation and three-APK audit passed.

All 18 capability bits are covered by 262,144 manifest profiles; the closed catalog has 36
methods. Full 3,500 and Play 173 preflight checks include every skill/docs test; this earlier
stage is not described as symmetric or frozen across its entire run. The final post-freeze
orientation robustness gate passed core734 and focused runtime47. The skill is 16,377 bytes,
under 16 KiB, with earlier limits and required contract phrases retained.

FullRelease unsigned: 27,519,137 bytes, SHA-256
`a23f541ae425ad4d50e42d5332d2c99eeaf8c9eb5a78d10ca30b1989df2e7da8`.
The unsigned artifact alone is not a signed installable delivery; see the signed receipt below.
Neither artifact establishes physical Android acceptance.

## Signed native delivery

`Jarvys-Factory-presentation-full-v77-arm64-test.apk`, 19,314,235 bytes, SHA-256
`550636d8f4c36f2e278932bced3c0eb5e32d374d5209af1515782a75358ca59e`.
The existing D7 test identity is retained; v2/v3 signatures, ZIP CRC, 16 KiB ZIP alignment,
package/version/ARM64 and manifest scope were independently verified. All 239 retained entries
match the audited unsigned release byte for byte. Exactly nine non-ARM64 libraries were omitted;
only three signing metadata entries were added. Freshly retrieved Library bytes match the local
signed APK exactly.

The delivery acknowledgement records native attachment acceptance at **2026-10-10 18:08:36 UTC**.
Artifact and Library bytes were independently verified; the acknowledgement was not independently
reread during that artifact audit. Accepted
attachment is not confirmed download, installation, update, UI behavior or data preservation.
This retains a test signing identity and does not claim production readiness. ZIP alignment is
not proof of ELF compatibility with a 16 KiB-page Android kernel. No real device settings or
user data were exercised by the synthetic checks. Remaining F1/F2/F3 gates stay open.
