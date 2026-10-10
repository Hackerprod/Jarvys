# UX42 F0c: shared runtime preview and bounded tests

This delivery introduces a private Factory preview and Coding-only preview/test/status actions for exact verified builds. It does not introduce installation, new capabilities or runtime permissions. The ordinary HTML viewer remains separate and unprivileged.

## Architecture and authority

`:factory-runtime-core` contains the shared validators, dispatcher, asset policy, WebView controller, storage contract and lifecycle handling. The generated launcher uses installed-package binding and actual system adapters. The preview has explicit host/declared identity metadata, isolated native RAM storage and simulated export/share/clipboard/haptics. It requires an isolated WebView profile and fails closed when unsupported. Profile data can be disk-backed; owned orphan cleanup is best effort, preserves live/default/foreign profiles and reports pending cleanup without reusing profiles.

The snapshot comes from an exact completed project build receipt and verified APK. Binding includes durable project identity, scope version, build ID, source digest, template/APK hashes and app identity/version. Process-local launch tokens are one-shot; replacement, closure, relevant cancellation and revocation invalidate operations. Normal completed Coding work may retain its standalone token; this is not a promise that stopping already-terminal work cancels every preview. Backgrounding closes the native preview.

The native screen exposes Reset and Close, protects screenshots and labels simulated behavior. Sanitized receipts contain only fixed operation/outcome counts and metadata, not console strings, request arguments, storage values or private errors. Terminal receipts retain pending-cleanup evidence without retaining the Activity. `test` uses fixed synthetic inputs through the actual shared validators and dispatch; it does not evaluate project JavaScript or execute the APK's DEX.

## Verification status

Final frozen-source focused tests: 248 passed. Fresh aggregates: 2,812 Full, 2,430 Play, 65 Debug + 65 Release installed runtime, and 18 Debug + 18 Release shared core, without failures, errors or omissions. Sixteen guarded JVM forks were independently matched; SDK tests: 10 passed. Generated-package, manifest-node, compiled-binding and restored-identity fixture audits passed independently. Final lint and actual delivery artifact review are still pending at this checkpoint.

Early compilation attempts exposed a missing direct WebKit dependency and an unnecessary optional profile-installer dependency on the new library's test path; both were corrected. An exploratory passing test run preceded the last callback/description corrections and is not used as final-source evidence. Detailed execution reports remain separate from this source summary.

## Remaining acceptance

Robolectric/native seam tests and synthetic APK fixtures are not real WebView, installed-app or physical Android evidence. Profile/cookie cleanup, rendering, system UI, UID/permission/provider isolation, installation, updates and durable data retention require their respective real acceptance checks. Generated apps have no INTERNET permission, while the preview inherits Jarvys's INTERNET permission; interception/CSP settings and quiet traces do not prove absence of all Chromium traffic. An already-started installed external operation may complete after cancellation and is never automatically replayed.

F0c installation remains a separate explicitly authorized feature; F1–F3 are not enabled. Legacy and recoverable signing identities remain unchanged. UX34 keeps its independent phase gates and UX43 remains last in the queue.
