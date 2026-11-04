# P4A implementation draft checkpoint — 2026-11-04

UNCOMPILED / UNRUN / NEW-WRITE GATE OFF. These relative-path patches preserve a work-in-progress implementation; they are not applied app source, a release, or an implementation PASS. They target the v100 production baseline e0801e05caca2f4857ace19e1d7033cd8fe7a356. The independently reviewed design is docs/BOT_PORTABILITY_P4A_HANDOFF_DESIGN.md at 5968f8ff; design PASS does not validate these bytes.

Scope: atomic native proactive reply acceptance, immutable source and referent linkage, existing Central PREPARED/explicit recovery, exact displayed UI and notification identity, and deny-only STOP ownership. No schema addition, new menu/chat/memory, inherited privileges, numeric policy, automatic uncertain replay, or APK change. The core gate remains OFF.

The four patches cover separate source sets: core storage/format and D3 tests; native owner/service/dispatcher adapters; UI/notification/Canvas selection and tests; native interaction acceptance tests. They require integration and compilation together. Static review has already prompted corrections; further defects may remain. Baseline red regressions and all candidate tests are pending execution. Existing historical failures remain preserved; no device or live-provider claim.

Before activation: integrate only reviewed changes, execute matching Full/Play pre-write compatibility and conservation tests, then native acceptance and independent implementation review. Do not apply patches to a different baseline without checking every hunk. Later commits will supersede this snapshot; these files are a recoverable checkpoint, not the authoritative app build.
