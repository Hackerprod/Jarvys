# Runtime and chat hotfix v89

Development only: version 89 / `1.2.82-RUNTIME-HOTFIX`. The delivered v88 APK remains separate. A screenshot of repeated workspace calls, internal model phases and a generic failure does not establish the device's actual exception or a provider outage.

## Display contract

The shared conversation renderer projects typed events before list layout, arrival animation and its working marker. Known internal model/device phases and assistant preambles accompanying tool calls are hidden. Typed Workspaces coordination calls, progress and successful tool cards are hidden. Final/partial answers, approvals, actionable errors, interrupted/not-started tools, explicit result/file cards and other useful tool cards remain visible. Unknown legacy tool identities are not guessed from display names or arbitrary text. The same projection applies on reopening history; stored model/tool evidence and recovery semantics are unchanged.

## Failure evidence and admission

Caught foreground failures use the existing bounded, content-free local diagnostic recorder within its private-content suppression scope. User wording attributes provider problems only to typed provider failures, not arbitrary exception-message substrings. The configured model-turn cap remains unchanged; its typed terminal exception identifies exhaustion without replaying work or claiming no effects occurred.

Diagnostic results opt into the existing whole-output contract: the loop returns complete JSON or an explicit context-budget error, never a successful sliced record. A rejected list can be requested with a smaller limit; a selected record can be requested alone. No budget is increased.

Central guidance reflects the saved Crew mode. Crew OFF is not silently enabled. A shared, read-only admission preflight validates existing role/tool/profile rules before a workspace assignment crosses STARTED. Known rejections stay PREPARED with a fixed actionable reason. Actual spawning rechecks the rules; any failure after STARTED remains uncertain and cannot be blindly replayed. Idempotency keys remain bound to the original request and payload.

No provider account, endpoint, permission or external action is exercised by the synthetic tests. The patch does not claim to diagnose the user's screenshot without its expanded tool output or local diagnostic record. Final integrated validation and APK delivery remain pending.
