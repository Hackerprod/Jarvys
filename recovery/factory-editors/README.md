# UX42 F1 v73: typed email and SMS editors

Preparation version 73 / `1.2.66-FACTORY-EDITORS`. Implementation and host validation in progress.
This is a bounded capability slice, not completion of Factory or physical Android acceptance.

## Scope

Separate `email` and `sms` capabilities use the existing closed v72 external-launch broker.
Email accepts one conservative ASCII address and required subject/body strings; SMS accepts
one optional-plus ASCII digit number and a required body. Native-only constructors produce
ACTION_SENDTO with mailto/smsto and fixed subject/text or sms_body extras. No arbitrary URI,
component, extras, attachment, CC/BCC, contacts read, permission or automatic sending path.
All limits and exact grammar are documented in [APK_FACTORY](../../app/APK_FACTORY.md).

The recipient gets the complete fields on opening. Its network, account and cloud draft-sync
behavior are outside Jarvys control, and it may ignore or change the supplied values. Native
human review shows separate labelled values and selected app; dispatch alone does not prove
rendering, draft saving, sending, delivery or external-task closure. The receipt retains only
`launchRequested` and `actionConfirmed:false`. No false assertion that the recipient never sent.

Exact latest signed caller identity, pinned host and recipient identity revalidation, single-use
durable launch_pending authority, no replay, expiry/revocation and user-declared native recovery
remain from v72. Journal contains only state/open/nonce, never message data. Native review is
FLAG_SECURE, save-disabled, excludes content capture and refuses autofill descendant export,
including explicit include-unimportant requests. Accessibility remains available for human review.

All tests use synthetic values and intercepted calls, with no actual composer or user transmission.
UX44 remains documentation-only; TTS/voice and remaining F1/F2/F3 stay open, UX34 stays gated,
and UX43 remains last. Final test/build/audit and signed delivery receipts will follow only once verified.

## Retained exploratory results

The initial SDK run exposed an old catalog-count assertion and a new cross-realm object comparison;
fixed test expectations without weakening field/receipt checks. Corrected SDK passed 20 cases.
The initial shared-core run failed one newly written preview fixture because it supplied an adapter
that intentionally throws rather than the existing simulatedEffects adapter; production policy was
unchanged. The fixture was corrected and a fresh run started. Initial evidence is retained locally,
not published as host reports. Preliminary results do not substitute for final frozen-source gates.

Corrected shared core passed 485 cases without failures/errors/skips. The accompanying runtime
run exposed one remaining old 23-method assertion after expansion to 25 methods; corrected to 25
while retaining all original matrix rows and adding both editor rows. Final aggregate will be fresh.
