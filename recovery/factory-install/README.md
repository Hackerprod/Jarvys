# Factory installation: explicit native approval and durable outcomes

Version 66 / 1.2.59-FACTORY-INSTALL adds a separate Coding install/status/cancel workflow for completed signed same-project artifacts. Opening review never installs anything. It binds project identity/scope, receipt digest, app/version, APK hash and signing certificate, revalidates the source after review and verifies its private APK snapshot before PackageInstaller commit.

Full alone requests Android's package-install permission. The user must already have enabled the per-source setting themselves; no permission or settings automation is offered. Play returns an explicit unavailable result and retains the signed project artifact. Generated runtime manifests, capabilities, shared preview core and signing identities remain unchanged.

The native screen is screenshot-protected and blocks Jarvys's device automation at dispatch. It requires a human review approval, then a separate human button to open Android's consent screen. A no-backup atomic journal restores that protection before app components after process restart. Committing is journaled before Binder; uncertain operations never replay. Private mutable callbacks bind a fixed nonce URI/session ID and cannot regress cancelled/opened states through late pending notifications. Only authenticated system success establishes session success. Abort, block, error, pre-commit cancellation and post-commit unknown outcomes remain distinct; cancellation does not prove rollback.

Backgrounding revokes uncommitted approval. Long hashing/staging runs outside the state monitor; final scope/token and native foreground checks happen at commit dispatch. Native recovery cannot close while staging is in flight or Android still has the tracked session. Corrupt journals retain protection. A create-before-journal crash can leave an uncommitted orphan session; it cannot install itself and is never silently resumed.

## Host acceptance

The first implementation passed 86 focused checks. Independent source audit then required one final foreground check after authority validation; that correction and its regression passed the fresh final 87 focused checks with the per-worker offline guard. The first full aggregate completed 2,848 tests with two failures in old exact-manifest expectations. The reviewed test-only corrections list the newly approved Full permission exactly, keep Play restricted, forbid privileged installation and assert a private callback receiver without intent filters. The complete aggregate is being repeated; comparative lint and three unsigned APK builds remain pending. Failed evidence is preserved; this checkpoint is not release acceptance or signing readiness.

## Remaining acceptance

All installer tests use synthetic backends and callbacks. Fixture signing uses ephemeral test keys only. No real device installation, unknown-source permission change, real signing-identity action or silent installer was executed. Android/OEM consent UI, source permission denial, update compatibility, data preservation, actual system lifecycle and recovery remain physical acceptance gates. UX34 phases beyond diagnostic phase 0 remain gated; UX43 stays last.
