# UX42 F1 v77: per-app presentation preferences

Version 77 / `1.2.70-FACTORY-PRESENTATION`: implementation and synthetic fixtures are under
independent review. Final host aggregate, exact lint, APK audit, signing and delivery are pending.
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
all 74 runtime cases then passed. Failed evidence is retained. Complete release gates are pending.

A later pre-freeze review also bounded failures from Android's orientation getter/setter: rejected
window-policy requests no longer abort an otherwise valid Activity. A synthetic rejecting window
keeps preferences and the bridge usable. This delta receives fresh focused native tests and the
entire final aggregate; earlier passes are not presented as checks of this later delta.
