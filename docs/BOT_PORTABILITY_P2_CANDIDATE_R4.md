# P2 R4 — preregistered final pair; scoped functional review pending

2026-11-01. Final application/test source **`23b1cd06d1308b25f1e036079836c5f89d895d99`**. Production code is unchanged from `d7dc2b63cb4eb2e25565300b25f32e6a873e7051`; the delta adds only failure-path test observability and documentation.

The **one preregistered final Full/Play pair** completed: **119 classes / 1,134 tests per flavor**, zero failures/errors/skips. Both include all139 compatibility cases and native39; counts are not additive. No repeated final attempt or tolerated failure. An earlier diagnostic-only compilation failed on Android's missing management API and ran no tests; its corrected source uses public host-JDK interfaces reflectively.

Both exports' before/after snapshots and current1,987 files match canonical SHA256 `65a3129b6ba1d6e4523604836149b510869c1bedf44951a4cd89cd1a8325f724` (`json.dumps(binding, sort_keys=True, separators=(',', ':'))`, UTF8). [Per-class results](BOT_PORTABILITY_P2_CANDIDATE_R4_RESULTS.json). [Unchanged class list](BOT_PORTABILITY_P2_ACCEPTANCE_CLASSES.json) SHA256 `ae7cb7a254d84b5c7387c3f0f51105480b0cefee2b3038354d2eb9ca290dddd9`. Documentation-only commits are distinct from tested source.

## Explicit unresolved timing limitation

**Independent R3's prior Play native execution remains FAIL:** one15-second child-readiness timeout, cause unclassified. It occurred before the durable WAIT/source assertions. Later bounded unchanged diagnostics passed, but do not prove the original cause was environmental or certify timing reliability. No speculative production fix, extended timeout, skipped assertion or retry-until-green policy was introduced.

The final test retains the exact15-second guard and every success assertion/order. Only on a failed wait, before child release/STOP, it captures monotonic elapsed time, nonblocking lifecycle identities/status, thread stacks, lock owners/held monitors/synchronizers, CPU times and supported deadlock IDs. It does not synchronously read a checkpoint/database or acquire worker locks. Unavailable diagnostics are reported without replacing the original assertion. [Diagnostic plan, evidence scope and closure criteria](BOT_PORTABILITY_P2_STARTUP_DIAGNOSTIC.md).

**This candidate requests scoped host functional acceptance, not clean-run timing/reliability certification. Independent review remains pending.** The reviewer must retain the original failed cohort and explicitly carry this limitation into any verdict. Physical device, actual installation, power-loss, live-provider/model-choice behavior and exhaustive scheduling are not validated.

## Reviewer map and preserved contracts

Inspect test-only diff `e0f58be1..23b1cd06`, especially `WorkspaceTreeNativeRuntimeTest.captureNativeStartupTimeout` and the failed-await callsite. The existing source-backed A–I contract and native migration repairs remain mapped in [R3](BOT_PORTABILITY_P2_CANDIDATE_R3.md), [R2](BOT_PORTABILITY_P2_CANDIDATE_R2.md), and the [original candidate](BOT_PORTABILITY_P2_CANDIDATE.md). Original eight independent B1–B4 negatives and139 compatibility cases were independently closed on both flavors in R3; those results are not falsely called a full independent broad rerun.

Additive current-generation Registry and Room v1→v2 conservation, exact authority/provenance, no uncertain STARTED replay, original progress-window policy, single Central/generalists/Engram and restored menu remain unchanged. No new APK/version/signing, policy/P4 activation or user-data deletion. Delivered v98 is untouched. **Old-binary downgrade after new writes is unsupported; never clear/reinstall to satisfy guards.** Prior R1/R2/R3 failures and failed builds remain preserved, not relabelled by this final pair.
