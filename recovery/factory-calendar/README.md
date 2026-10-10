# UX42 F1 v75: calendar event editor

Version 75 / `1.2.68-FACTORY-CALENDAR` is in implementation. Independent design review approved;
final host tests, exact lint, three APK builds, source/binary audit and signed delivery are pending.

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
