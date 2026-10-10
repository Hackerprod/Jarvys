# Typed maps and dialer launch — UX42 F1, v72

Implementation checkpoint; final aggregate, lint, three-APK audit and signed delivery are pending.
No physical Android acceptance is claimed.

## Scope

Separate `maps` and `phone` capabilities expose `maps.open({latitude,longitude})` or
`maps.open({query})`, and `phone.dial({number})`. Closed constructors create geo ACTION_VIEW
and tel ACTION_DIAL only. No arbitrary URI, flags, extras, component or recipient from JavaScript;
no GPS/contact read, CALL_PHONE path, automatic call or new generated/host permission.
Generated apps retain the two exact host package queries and their zero-permission manifest.
Jarvys host already has unrelated legacy permissions; only narrow host visibility and a separate
native review Activity are added here.

Native review shows complete immutable input and chosen recipient with data/network/accounts/
history/permissions disclosure. Exact latest-signed installed source identity and selected native
recipient pins are rechecked; no implicit fallback. Recipient eligibility is not a trust guarantee.
A third-party app's behavior is outside Jarvys control. `launchRequested` indicates only dispatch
acceptance; `actionConfirmed:false` cannot prove display, navigation, calling or delivery.

Separate Binder/journal/recovery domains preserve the v71 browser flow. Shared admission and
human-only guard remain durable through cancellation, unknown outcomes and pending preparation.
The new coordinator reserves its startup guard before asynchronous restore. Initial Binder setup
and recipient discovery also retain preparation ownership until terminal. No payload or recipient
is journaled, and no interrupted launch is replayed. Human acknowledgment after manually closing
the external task is a declaration, not independently verified OS closure.

## Validation in progress

Official Android guidance and independent design review approved this bounded combined slice.
Independent early production review checked typed grammars, source/recipient identity, capability
separation, recovery, manifest scope and compacted Coding skill. Skill remains below 16 KiB.
Tests use synthetic values, package/Binder/lifecycle fixtures and intercepted native dispatch only;
no real call, map navigation, device location or user-data transmission.

The first shared-core run executed 453 cases with one fixture failure: a test mutated an original
JSONObject after the validator had copied it. The test now mutates the actual request's arguments;
production validation was not weakened. That run recorded zero guarded external HTTP(S) attempts.
The failing evidence is retained on the validation host, not published here.

Checkpoint `865b119` is backed up with normal history. Focused host validation passed 224 cases,
including 96 new native external-action cases; the single guarded worker blocked one external
HTTP(S) attempt, whose cause is not attributed. Corrected core/runtime validation passed 462+72
cases with zero failures/errors/skips and zero blocked external attempts across two guarded workers.
SDK passed 18 cases. Initial lint completed with one additional CustomSplashScreen warning in
each host flavor (314/301 versus 313/300), caused by the new Activity name. The narrow
FactoryExternalActionActivity rename updates exact manifest/runtime/recovery/test references;
no suppression, permission or dispatch behavior changed. The failed-delta evidence is preserved;
all final gates will run again after the rename. Five actual-Application startup tests are included in the 1,066 frozen app inputs and await the full aggregate.
None of these focused results substitutes for final aggregate, lint, APK audits or physical acceptance.

Full contract and current official sources: [APK_FACTORY](../../app/APK_FACTORY.md#typed-maps-and-dialer-launch-ux42-f1-v72).
Preserve v63–v71. Rich editors, TTS/voice and remaining F1 work stay pending; F2/F3 remain closed,
UX34 retains its gates and UX43 remains last.
