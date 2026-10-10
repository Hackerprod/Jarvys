# UX42 F1 v74: one human-selected contact datum

Version 74 / `1.2.67-FACTORY-CONTACTS` is being implemented and tested. No completed host,
release, signature or delivery gate is claimed yet. Calendar remains a separate pending slice.

A `contacts` capability exposes `contacts.pick({kind:"phone"|"email"})` with native human
picker launch and a second complete-value approval. Only one canonical selected item row is
queried; no address-book query, enrichment or caller-supplied URI is allowed. Android access
may use a temporary grant or existing host READ_CONTACTS, but permission alone never grants
selection authority. This explicit design revision avoids rejecting normal AOSP behavior that
omits redundant URI grants when general permission already exists. No new permission is added
or requested. Generated APKs remain permissionless and return only `{kind,value}`.

Separate authenticated host/source control, durable consumption/recovery, lifecycle revocation,
provider/picker identity checks, bounded accepted values and privacy-protected native review
are described in [APK_FACTORY](../../app/APK_FACTORY.md#single-contact-datum-selection-ux42-f1-v74).
All tests are synthetic; no user contacts, real picker, calls, calendar writes or external
transmissions are performed. Provider cancellation is best effort; a blocked worker retains
admission and automation protection. Accepted-value bounds do not bound provider allocations.

Independent design review approved the exact-row access model and identified lifecycle and
cursor-cancellation cases incorporated before final gates. Final source, tests, lint, three-APK
and independent release receipts will be recorded only after verified completion. Physical
compatibility/installation remain unverified. TTS/voice/remaining F1/F2/F3, UX34 gates,
UX44 documentation-only defaults and UX43-last are preserved.
