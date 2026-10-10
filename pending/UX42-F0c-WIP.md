# UX42 F0c — implementation checkpoint history

The original work was preserved from v62, then reconciled onto v64 without reverting its protocol, progress or conversation-memory corrections. Version 65 / 1.2.58-FACTORY-PREVIEW contains a shared runtime core, a private Factory preview with isolated native test storage and simulated side effects, exact-build-bound Coding preview/test/status actions, and bounded observation receipts. Ordinary HTML preview remains separate.

The initial checkpoint was deliberately incomplete. Its exploratory pass of 247 app, 65 + 65 installed-runtime and 18 + 18 shared-core tests is superseded by final-source evidence; it is not release acceptance. Missing direct WebKit and optional profile-installer dependency issues were corrected before final tests.

The frozen implementation passed 248 focused app tests, fresh aggregates of 2,812 Full / 2,430 Play / 65 + 65 installed runtime / 18 + 18 shared core, and ten SDK checks. Independent fixture audits passed. Lint then identified missing locally recognizable feature guards and a duplicate inherited dependency diagnostic in the extracted library. A narrow three-file correction adds explicit fail-closed feature guards and moves the same pinned dependency to the core API; test assertions are unchanged. Affected tests, comparative lint and rebuilt APK review must pass before delivery.

See [the delivery verification summary](../recovery/ux42-f0c/README.md) for current acceptance and limits. WebView profiles may use disk; only the native test store is RAM-only. No installed-app or physical-device acceptance is claimed. Installation remains a separate explicit feature. Existing signing identities and UX34 phase gates are unchanged; UX43 stays last in the queue.
