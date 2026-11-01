# Native startup diagnostic and scoped acceptance boundary

2026-11-01. Independent R3 remains **FAIL**: its Play native39 run failed the15-second child-provider-entry latch in `confirmedParkSourceRemainsExactThroughNativeFinalPublicationBeforeWake`. Full independently passed; unchanged diagnostic39 rerun passed. All eight earlier independent negatives and139 compatibility cases passed both flavors. No historical result is relabelled.

The failed method had not reached its durable WAIT/checkpoint-source assertions. Logs prove the child was admitted/BOUND and RUNNING, and eventually entered its provider during failure cleanup. They do not identify why provider entry was delayed. A separate bounded observer experiment (twelve fresh launches of the unchanged target, then one original39-method cohort) did not reproduce it. Stack/lock-owner/CPU observations were retained; they establish neither a historical deadlock nor host-capacity cause. No production fix is justified by this evidence.

## Test-only change

The same child-entry latch,15-second deadline, execution ordering and all subsequent semantic assertions remain. On timeout only, before finally releases the child and cleanup stops workers, the test automatically captures monotonic time, nonblocking bot/assignment/cycle/status getters, all thread stacks, waiting lock/owner, held monitors/synchronizers and CPU times. It performs no checkpoint/DB read or worker-monitor acquisition. Diagnostic failure cannot replace the original assertion failure. Successful execution takes the original path without a new barrier, delay, retry or permission.

The15 seconds is a fixture-readiness prerequisite, not a newly specified product latency quota. Actual E/F acceptance still requires confirmed durable WAIT source preservation through final publication, exact successor consumption and no replay. The new diagnostic does not relax these assertions or declare the historical delay understood.

## Preregistered final validation

One fresh serialized Full/Play119-class pair on identical final bytes, unchanged runner/timeouts/assertions, is the next acceptance attempt. No retry-until-green policy. Both must pass; any further failure remains evidence and triggers review of its captured cause rather than automatic rerun. Independent review must evaluate a scoped functional verdict with the unresolved historical timing limitation explicit, not certify latency, all schedules, physical process/power loss or live-provider behavior. Prior production source remains unchanged; only the diagnostic test and documentation change.

The initial diagnostic-only compile failed because Android's Kotlin compile API omits java.lang.management. The test uses public host JDK interfaces reflectively instead; unsupported collection is reported without replacing the original assertion. Monotonic elapsed wait and available deadlock IDs are included. No acceptance tests ran on the failed compile; it remains retained separately.

The single preregistered final pair completed on source `23b1cd06d1308b25f1e036079836c5f89d895d99`: Full119/1,134 and Play119/1,134, zero failures/errors/skips, identical1,987-file binding `65a3129b6ba1d6e4523604836149b510869c1bedf44951a4cd89cd1a8325f724`. Independent scoped functional review is pending. The original failed readiness execution and unknown timing cause remain explicit limitations.
