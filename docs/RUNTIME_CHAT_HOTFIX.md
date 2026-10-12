# Runtime and chat hotfix v89

Delivered: version 89 / `1.2.82-RUNTIME-HOTFIX`, frozen source `896ae61513289f13cd26efb50bf088969214b425`. A screenshot of repeated workspace calls, internal model phases and a generic failure does not establish the device's actual exception or a provider outage.

## Display contract

The shared conversation renderer projects typed events before list layout, arrival animation and its working marker. Known internal model/device phases and assistant preambles accompanying tool calls are hidden. Typed Workspaces coordination calls, progress and successful tool cards are hidden. Final/partial answers, approvals, actionable errors, interrupted/not-started tools, explicit result/file cards and other useful tool cards remain visible. Unknown legacy tool identities are not guessed from display names or arbitrary text. The same projection applies on reopening history; stored model/tool evidence and recovery semantics are unchanged.

## Failure evidence and admission

Caught foreground failures use the existing bounded, content-free local diagnostic recorder within its private-content suppression scope. User wording attributes provider problems only to typed provider failures, not arbitrary exception-message substrings. The configured model-turn cap remains unchanged; its typed terminal exception identifies exhaustion without replaying work or claiming no effects occurred.

Diagnostic results opt into the existing whole-output contract: the loop returns complete JSON or an explicit context-budget error, never a successful sliced record. A rejected list can be requested with a smaller limit; a selected record can be requested alone. No budget is increased.

Central guidance reflects the saved Crew mode. Crew OFF is not silently enabled. A shared, read-only admission preflight validates existing role/tool/profile rules before a workspace assignment crosses STARTED. Known rejections stay PREPARED with a fixed actionable reason. Actual spawning rechecks the rules; any failure after STARTED remains uncertain and cannot be blindly replayed. Idempotency keys remain bound to the original request and payload.

No provider account, endpoint, permission or external action is exercised by the synthetic tests. The patch does not claim to diagnose the user's screenshot without its expanded tool output or local diagnostic record. Software validation and accepted APK delivery are complete; physical-device acceptance remains unverified.

## Validation and delivery

Fresh affected tests: 260 cases in 34 classes per Full/Play flavor, all passing, with two closed test workers reporting zero blocked attempts. Unselected app tests remain historical. Core/runtime/SDK evidence is retained through exact source and resolved-input equality. Two initial fixture compilation failures and interruptions remain preserved; the final tests and three builds passed. Both host lint tasks retain their failures: all 46 Full / 37 Play errors and other diagnostic identities exactly match v88. No detectors were disabled. Validation used a 3 GiB Gradle heap.

Independent exact APK, DEX, resource, profile, D7 signature and persisted-byte reviews passed. ARM64 delivery was accepted on 12 October 2026 at 18:47:12 UTC: 20,618,899 bytes, SHA-256 `e62e2ebd86ed6209006fadf404a1e3a360cc7dd87e7910b1bb1099aec20be7ef`. This does not establish installation, real-provider behavior or the reported phone failure's cause. New mascot/branding changes are outside this release.
