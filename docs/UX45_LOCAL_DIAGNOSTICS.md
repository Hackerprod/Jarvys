# UX45: local diagnostics

Version 86 / `1.2.79-LOCAL-DIAGNOSTICS` was signed with the retained D7 identity and sent on 12 October 2026 at 05:26 UTC. Source: `cc83f3b72b91a880d72ee3876f5910bb3363107a`. ARM64 delivery: 20,450,963 bytes; SHA-256 `17fbabd7b37df1128ed4b336476e7a8b66cf172e922ff80542a5de594f78cffd`. Independent persisted-file verification passed; device installation and acceptance remain unverified.

Affected host coverage passed: 650 Full cases (612 retained with exact input/impact reconciliation plus 38 fresh repaired cases) and 645 fresh Play cases. Unrun v85 host cases remain historical. Core/runtime/SDK evidence is retained through exact source/dependency/template equality. Both fresh lint XMLs are byte-identical to v85, including 46 Full and 37 Play historical errors; failed lint exits are preserved, not represented as lint-clean or a fresh passing aggregate. All three APK builds and Release Vital completed; independent DEX, resources, profiles and signed-payload audits passed. Test network guards blocked two legacy attempts in the first Full run and two in Play; the repaired Full run blocked zero. No global zero-attempt claim is made.

## Capture and privacy

The previous automatic HTTP error reporter is replaced by app-private local diagnostic metadata. The obsolete endpoint server helper is removed. No endpoint is contacted by this feature. Existing private-content suppression remains at compatibility and provider-request capture boundaries.

An application-installed uncaught Java/Kotlin handler records best-effort metadata and delegates to Android's existing handler. It does not recover a crashed process or hide its exception. Provider request failures are recorded inside their existing private scopes, with the identical exception rethrown. A failed request may later be retried successfully; it does not prove that the whole agent run failed. Expected cancellation is excluded. Arbitrary handled failures in other tools are not all intercepted.

Records exclude raw exception messages, response/request bodies, prompts, tool arguments/results, credentials, filenames, paths and thread names. They retain controlled origin/severity, numeric version/API/status, bounded exception types and verified structural stack symbols where available. Unknown symbols fall back to safe categories. Global fatal metadata is a separate capture boundary: earlier thread-local private scopes may already have unwound when an uncaught handler runs.

Storage is outside backup/workspace/run-history roots. There are 32 fixed slots, at most 16 KiB per record, and one staging file. Fixed slots and bounded record reads avoid unbounded directory scanning. Records are eligible for reading for seven days; expired bytes are reclaimed on later capture/overwrite, with no real-time erasure guarantee. Focused tests cover limits, corrupt rows, restart reads, symlinks, write failure, concurrent access and handler delegation. Device behavior remains unverified. A busy or failed writer may drop metadata; missing records do not establish absence of a failure.

## Model access

`app_diagnostics` is read-only and available in ordinary main chat. It lists recent summaries and reads or prepares a report for an exact returned record ID. It cannot read arbitrary paths, clear records, send reports or repair the app. Delegated bots and scheduled/proactive scopes do not receive the app-wide diagnostic store.

The native `diagnostics.read` policy defaults to Allow always; saved Ask requires one review of the selected read, and Deny blocks access. STOP and policy revocation are checked before disk access and before returning data.

Capture stays local. When the user asks the configured model to inspect an error, selected minimized metadata enters the normal provider conversation. This is not an offline-model guarantee. The model must distinguish recorded observations from hypotheses; the user chooses whether to share its report externally.

## Limits

No guarantee is made for native crashes, ANRs, OOM, force-kills, failures before handler installation, other processes, or every caught exception. Android/OEM behavior requires device acceptance. No real account requests, external telemetry calls or deliberate process crashes are needed for host tests.
