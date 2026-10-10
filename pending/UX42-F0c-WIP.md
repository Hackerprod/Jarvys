# UX42 F0c — incomplete implementation checkpoint

This branch preserves work in progress started on v62 and reconciled onto v64 without reverting its fixes. It is not a release and has not completed all final compilation, test, lint and APK review gates. Version 65 / 1.2.58-FACTORY-PREVIEW is reserved for this stage.

Partial implementation includes a shared runtime core, a private Factory preview with isolated native test storage and simulated side effects, exact-build-bound Coding preview/test/status actions, and bounded observation receipts. Ordinary HTML preview remains separate.

Remaining work includes compilation, lifecycle/revocation race coverage, isolated WebView profile cleanup (including interrupted sessions), truthful evidence and profile-storage disclosures, focused and aggregate tests, SDK parity, comparative lint, documentation/skill updates and independent artifact review. WebView profiles may use disk; only the native test store is RAM-only. No installed-app, physical-device, signing or delivery acceptance is claimed.

Installation remains a separate explicit feature. Existing signing identities and UX34 phase gates are unchanged; UX43 stays last in the queue.

An exploratory guarded pass completed 247 focused app tests, 65 + 65 installed-runtime tests and 18 + 18 shared-core tests, with ten SDK checks. Final frozen-source reruns remain in progress; these counts are not final release acceptance.
