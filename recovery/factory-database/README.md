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
