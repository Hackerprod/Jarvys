# UX42 F1 v76: private typed SQLite

Version 76 / `1.2.69-FACTORY-DATABASE` is in development. This is not a completed delivery.

A standalone zero-permission capability supplies bounded typed schema migrations, CRUD and atomic
batches against one generated app's private native SQLite database. No Jarvys host broker or native
consent dialog per private query. Preview uses an isolated memory database and loses it on close,
reset or pause. Installed data remains in no-backup app storage; no encryption or export is claimed.
Full API and limits are in [APK_FACTORY](../../app/APK_FACTORY.md).

Design and source review identified and corrected preview foreground handling, migration row-size
rollback, preservation of corrupt metadata and persisted-value type checks. The first focused run
ran 66 cases with 35 failures: SQLite's Android-owned memory metadata table was mistaken for an
existing user schema. The corrected native metadata exception passed all 66 focused cases; SDK27
also passed. The initial compile invocation used the wrong working directory and ran no tests;
its failure is retained. Full aggregate, exact lint and independent three-APK audit remain pending.

No real user data, device files, network, installation or actual schema migration was performed.
Synthetic host tests cannot establish Android/OEM/process-death/update persistence. Remaining F1,
strict TTS/voice, sensors/biometrics, F2/F3, UX34 gates, UX44 future-default documentation and UX43-last
are preserved.

## Retained first aggregate

The first six-task aggregate stopped after Full: 3,498 cases, one failure in the inherited
MainAgentComposerTest.spanishLargeLight fixture. Background Markdown parsing resumed Compose
modifications during applyChanges. The narrowly scoped fixture correction uses
StandardTestDispatcher, matching existing v74 fixtures; all test methods, assertions, interactions
and captures remain unchanged, and production UI is untouched. Both-flavor focused tests and a
new full lint/aggregate/build sequence are required. Prior lint was exact313/300/3/2; its archived
freeze is not represented as the final corrected snapshot. Host skill/preview/manifest preflight
passed148+148. The failed aggregate and all earlier evidence are retained.

The first dispatcher-only fixture correction ran33Full with10 failures because Markdown parsing
is outside Compose idle accounting. A bounded condition wait for the same original progress-text
semantics was added before the retained visibility assertion. Final related fixtures passed33Full
and33Play; all original tests/assertions/captures remain. Final database-focused70cases passed,
including actual native page cap/WAL, oversized-file preservation and exact ID lookup. Fresh
lint/full aggregate/builds are being repeated on the new freeze; no lint reuse waiver is claimed.
