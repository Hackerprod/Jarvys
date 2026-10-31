# Fresh reconstruction pre-write gate — additive proof PASS, acceptance pending

The exact seven classes in `BOT_PORTABILITY_P2_PREWRITE_CLASSES.json` are selected before execution, for both Full and Play on unchanged source. Fresh Full results are recorded below; matching Play is still pending. This is a focused compatibility gate, not the complete implementation acceptance suite or a recovery of prior test counts.

- New native-SQLite schema compatibility tests cover all populated v1 tables and byte/type conservation, exact v1/v2 DDL, no automatic enrollment, cached readers/doctor/export, transactional interruption and real SQLITE_FULL, malformed schemas and reopen.
- Existing current Registry tests exercise old assignment/control/read contracts.
- Current checkpoint durability, incorporation codecs, continuation and coordination histories cover strict typed decode, atomic replacement, size/fsync faults and conservation.
- Generation receipts fence source identity across storage lifecycle.

Admission remains OFF. No app-open upgrade is enabled. A focused failure blocks the gate; broader fixture/compilation failures are reported separately, never relabeled PASS. Gate success authorizes only local implementation validation; all changed checkpoint/authority code requires corresponding new tests and final-source repetition before independent implementation PASS. New typed worker-wait/control source is still an uncompiled draft outside app source at this checkpoint.

Version2 is an additive extension of the current generation, not a legacy import or destructive repair. Existing rows are not deleted/backfilled. Old-binary downgrade after new writes is unsupported; keeping the v98 APK is not a rollback guarantee.

## First execution diagnostic (not PASS)

Fresh Full cohort executed 81 cases: 57 failures, no skipped/error cases. All 57 failed during current-generation fixture initialization (schema6, Registry32, durability12, codec7). Inspection found the existing native bootstrap was called; these four restored/new test configurations lacked the established `ArtifactOsShadow`, so host Robolectric `lstat` produced false legacy-path observations. Added that existing real-filesystem adapter to the four configurations, without changing production guards or test assertions. Two checkpoint fixture target helpers also now address `StorageGeneration.directory`, rather than removed legacy paths. Original failures are retained; a fresh both-flavor run is required.

The subsequent strict disk-full diagnostic exposed a real transaction error-reporting defect: SQLite auto-aborted the full-disk transaction, then `endTransaction` threw a rollback error that replaced the original failure. The bounded fix preserves the original exception and adds cleanup failure as suppressed; cleanup-only failures still propagate. This does not claim rollback success: the existing exact row/type/schema/version/reopen assertions remain mandatory. No gate PASS is asserted by this change.

## Fresh Full verification (not both-flavor gate PASS)

After the bounded exception-preservation correction, the preregistered seven-class Full cohort passed **81 tests, 0 failures, 0 errors, 0 skipped** on unchanged exported source. The six schema cases include the original strict `SQLiteFullException` check and exact row/type/schema/version/reopen conservation checks; cleanup-error preservation did not replace those assertions. Previous failing runs remain diagnostic evidence, not overwritten results. Matching Play and all later integrated-source repetitions remain required; admission is still OFF.

## Both-flavor baseline gate PASS; integrated extension pending

The same preregistered cohort now passes Full **81/0** and Play **81/0**, with no errors/skips and identical source bindings across 1,943 files. Binding SHA-256: `82f952812cc6559f6398e88b5c795c87ae1e77bf02b460a410c97e248877c78e`. This is the pre-write baseline compatibility proof, not P2 implementation acceptance.

Before enabling local integrated admission, the nine-class list in `BOT_PORTABILITY_P2_INTEGRATED_PREWRITE_CLASSES.json` adds three tree-input codec and eleven typed wait/control checkpoint cases to the unchanged original seven classes. These new-source conservation/strict decode/CAS/fsync cases are preregistered before execution; expected total 95 per flavor. Integration remains OFF while they compile/run. Subsequent final-source acceptance and independent implementation review remain mandatory.

## Integrated run 1 — FAIL, activation still OFF

The integrated nine-class cohort executed 95 Full tests: 4 failures, 0 errors/skips. All original 81 and the three new input-codec cases passed. Four typed wait/control cases found two real defects: JSON copying normalized floating-point revision before validation, and raw surrogate code units were replaced during UTF-8 checkpoint writes. Validation now precedes copying; standard JSON surrogate escaping precedes existing payload/envelope encoding. The v1 schema, sanitizer and readers are unchanged. Failed assertions are retained, with stronger disk-vs-return checks and an additional exact JSON/redaction test. Expected integrated cohort is now **96 per flavor**; neither integrated gate PASS nor local activation is claimed.


## Matching integrated gate PASS; local candidate enabled

On source commit `7f1db768f9620ee160f2bdeafdb90fccd8982fae`, the exact nine preregistered classes passed **Full99/0 and Play99/0**, no errors or skipped cases. Source bindings match across 1,978 app/testing files; canonical sorted JSON binding SHA-256: `71faa6e15782a6ef9d6ba18898d4e5d7e62ee49a40499423aedbe8b0772fa0b3`. The three added schema cases prove transaction-connection ownership, nested rollback conservation, and strict schema rejection. A separate two-class OFF diagnostic cohort passed **Full28/0 and Play28/0**; these are not counted as compatibility cases. Earlier failing results remain preserved.

Local admission is now enabled solely for the authorized real native integration/acceptance candidate. This is not an independent implementation PASS or delivery approval. Final changed-source repetition of compatibility tests, all preregistered acceptance classes, and a fresh independent review remain mandatory. New app-open dispatch, uncertain replay, destructive migration and old-binary downgrade remain unsupported/forbidden as documented.


## Additive typed TASK delta — admission returned OFF

Focused native execution exposed that the older message DTO accepts only supervisor provenance, while genuine descendant assignments and typed tree report/answer inputs carry actual parent/child provenance. The bounded correction adds descriptive `TREE_TASK` to the existing binding codec and validates exact typed routes without granting execution authority. This is a new persisted codec case; local admission is OFF again until fresh matching Full/Play proof.

The preregistered integrated gate now contains ten classes: the prior nine plus `com.jarvys.agent.crew.CrewTreeMessageRoutingTest` (six cases), expected **105 tests per flavor**. It covers ordinary legacy metadata, exact UTF-16 codec round-trip, route/payload/owner/role/cycle mutation, missing binding and INBOX-only incorporation alongside prior checkpoint/schema conservation. Old records retain their exact types and readers; no backfill, deletion, new database schema or legacy import. Old binaries cannot be claimed to understand the new enum value; downgrade remains unsupported.


## Additive gate PASS and local validation re-enable

The exact ten classes passed **Full105/0 and Play105/0**, no errors/skips, with identical bindings across 1,980 app/testing files at source commit `30f9e54c`. Canonical sorted JSON binding SHA-256: `956933a3cbe5430aa57605cec464ad4d42be95c4059af5a8b6f82732d83b6926`. No source changed during either export/test preparation. Local admission is re-enabled solely for actual native acceptance. Full final-source repetition and independent implementation review remain required; this is not P2 PASS or delivery.
