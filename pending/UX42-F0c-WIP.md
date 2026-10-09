# UX42 F0c — incomplete implementation checkpoint

This branch preserves work in progress based on v62. It is not a release and has not passed compilation, tests, lint or final APK review. Version 64 is provisional.

Partial implementation includes a shared runtime core, a private Factory preview with isolated native test storage and simulated side effects, exact-build-bound Coding preview/test/status actions, and bounded observation receipts. Ordinary HTML preview remains separate.

Remaining work includes compilation, lifecycle/revocation race coverage, isolated WebView profile cleanup (including interrupted sessions), truthful evidence and profile-storage disclosures, focused and aggregate tests, SDK parity, comparative lint, documentation/skill updates and independent artifact review. WebView profiles may use disk; only the native test store is RAM-only. No installed-app, physical-device, signing or delivery acceptance is claimed.

Installation remains a separate explicit feature. Existing signing identities and UX34 phase gates are unchanged; UX43 stays last in the queue.
