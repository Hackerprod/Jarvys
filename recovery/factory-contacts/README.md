# UX42 F1 v74: one human-selected contact datum

Version 74 / `1.2.67-FACTORY-CONTACTS` is being implemented and tested. No completed host,
release, signature or delivery gate is claimed yet. Calendar remains a separate pending slice.

A `contacts` capability exposes `contacts.pick({kind:"phone"|"email"})` with native human
picker launch and a second complete-value approval. Only one canonical selected item row is
queried; no address-book query, enrichment or caller-supplied URI is allowed. Android access
may use a temporary grant or existing host READ_CONTACTS, but permission alone never grants
selection authority. This explicit design revision avoids rejecting normal AOSP behavior that
omits redundant URI grants when general permission already exists. No new permission is added
or requested. Generated APKs remain permissionless and return only `{kind,value}`.

Separate authenticated host/source control, durable consumption/recovery, lifecycle revocation,
provider/picker identity checks, bounded accepted values and privacy-protected native review
are described in [APK_FACTORY](../../app/APK_FACTORY.md#single-contact-datum-selection-ux42-f1-v74).
All tests are synthetic; no user contacts, real picker, calls, calendar writes or external
transmissions are performed. Provider cancellation is best effort; a blocked worker retains
admission and automation protection. Accepted-value bounds do not bound provider allocations.

Independent design review approved the exact-row access model and identified lifecycle and
cursor-cancellation cases incorporated before final gates. Final source, tests, lint, three-APK
and independent release receipts will be recorded only after verified completion. Physical
compatibility/installation remain unverified. TTS/voice/remaining F1/F2/F3, UX34 gates,
UX44 documentation-only defaults and UX43-last are preserved.

## In-progress checks

Implementation checkpoint `d5d759f` is verified on the remote with normal history. First native
production compile passed; SDK 22/22 passed. Initial focused host run executed 209 tests and failed in four fixture
cases: three updated-system-provider flag variants and one expanded capability matrix missing
its contacts broker binding. Fixtures were corrected without weakening production. The initial
run is retained as failed, not a final pass; new activity/startup and blocked-cursor regressions
were added afterward. Corrected focused validation and final frozen-source gates remain pending.

Corrected focused gate passed 243 host tests, including 129 new contacts cases, and 577 shared-core
tests. The first complete aggregate ran 3,458 Full cases and failed one inherited preview assertion
that still expected 28 observations instead of 29. Only that count and the explicit contacts
preview-denial/unavailability assertion were corrected; all original cases remain. Independent
review permits reusing the exact pre-correction lint report because the sole app delta is this
assertion-only fixture. Original lint source freeze/timestamps are retained; equal final lint/test
input freezes are not claimed. A fresh complete six-suite aggregate and three builds will follow.

The second aggregate passed all 3,458 Full cases but failed two unchanged Play Compose cases:
concurrent measure/layout and a wrong-thread view update. Their default unconfined Compose test
dispatcher permitted IO completion to resume layout inline, matching an already corrected fixture
pattern elsewhere in this repository. Only the two rules now use `StandardTestDispatcher` with
its required opt-in/imports; every test body and assertion is unchanged. Both failed runs are
retained. Independent review approved this bounded test-only lint reuse; focused checks in both
flavors and a fresh complete aggregate remain required before release.

The first dispatcher-only focused Memory run exposed inadequate fixture readiness and ended
with missing-node failures plus JVM OOM; its evidence is retained. Compose's unbounded async
scroll loop was identified as a plausible OOM path, not proven by an OOM stack. Memory now uses
the repository's finite measured-scroll pattern with real scroll actions, test-clock settlement,
progress/full-visibility assertions, and destination readiness. All 20 cases, all 66 original
assertion calls and all three system-back actions remain. Independent review approved this
fixture-only adaptation. Isolated focused checks passed Memory 20/20 in each flavor and Workspace
2/2 in each flavor. Fresh aggregate, builds and final independent release audit remain pending.
