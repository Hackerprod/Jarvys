> Current status: matching pre-write Full132/Play132 passed before local enablement. Final changed-source Full/Play1,127 passed; [R2 independent review is pending](BOT_PORTABILITY_P2_CANDIDATE_R2.md). The sections below preserve chronology, including OFF drafts and failures.

# B2 native snapshot conservation — second additive current-generation upgrade

Status: planning/implementation WIP, local tree admission OFF, no new migration writes enabled on existing v1 data. Independent P2 R1 remains FAIL. Its stricter transport requirement uncovered an actual storage gap: `MemoryTimelineRepository` normalizes Crew snapshot messages/bindings but discards their original `inputProvenance`; reading those rows cannot lawfully fabricate the missing parent/child proof.

## Chosen bounded extension

This is separate from the already-tested **WorkspaceRegistry v1→v2**. The native **Room agent.db current-generation v1→v2** adds one dedicated `crew_message_input_provenance` table, keyed by existing `(conversation_id, mission_id, message_id)` and cascade-FK to `crew_messages`. It stores all14 actual typed provenance fields. Eleven non-enum string fields use the established ExactUtf16 BLOB codec, kind/principal_role use strict TEXT enums, and assignment_revision uses INTEGER. No default/backfill/inference. Writer inserts the validated original object in the existing atomic snapshot replacement; reader hydrates the exact row before strict `CrewMessage` decoding.

There is no alternate memory/authority store. Existing separate conversation-input provenance cannot substitute: not every historical outbound snapshot message is an admitted inbox item, and synthesizing such an admission would violate authority. Existing snapshot headers retain only digests, not original provenance. Overloading refs/text/source IDs or relaxing strict decode is rejected.

## Compatibility and write gates

- Establish original v98 Room schema/identity from verified source/export, not a guessed regenerated v1. Check exact old schema/identity read-only before compatible upgrade; unsupported/future/malformed schemas remain rejected with data intact.
- One additive Room migration under native transaction; schema/identity/version validation and rollback/interruption proof, no destructive fallback or legacy import. Existing rows/types/values remain byte-identical. Old-binary downgrade is unsupported.
- Normal opening of existing v1 remains blocked without mutation while the compatible-upgrade gate is OFF. Explicit test-owned migration and new empty test v2 creation are used to establish proof; there is no application delivery at this stage. Enabling a compatible data upgrade does not enable app-open task dispatch.
- Old missing provenance is never reconstructed. Ordinary legacy messages retain their existing safe parsing; typed tree messages missing proof remain non-admissible. Raw database diagnosis/export must retain their rows rather than delete, rewrite or promote them.
- New table FK/cascade follows the existing snapshot replacement transaction. Cached readers, exact-schema preflight, export/doctor and source-title repair identities must support the current schema only where required, not through broad guard bypass.

## Preregistered proof

The ten previous compatibility classes remain; the class list now adds `RoomTreeProvenanceCompatibilityTest` and `CrewSnapshotProvenanceConservationTest` before execution. Cases must cover populated v1→v2 byte/type conservation, exact DDL/identity, interruption/rollback/reopen, unknown/future/malformed rejection, FK/cascade, original task/report/answer provenance and message Unicode round trips, absent/malformed proof denial and ordinary legacy data. New case counts will be recorded from actual results, not invented. Both flavors on identical bytes must pass before enabling the migration/tree local admission gates. Full native acceptance and independent implementation/migration review are still required afterward.

## Integrated OFF draft

Pinned v98 `schema1.json` and the identical test resource SHA256: `ab91fb220c7b78fb9e69e070d46b635bdada53f8ba3d1a8bfec6b8bad63465f3`; identity `1ad574a6ba44dd5117e182c835f4f670`. Preflight validates token-normalized exact entity/index/master DDL plus supported native expression indexes, trigger/view definitions and FTS families/shadow ownership. Arbitrary/changed triggers (including on Room identity update) and views are rejected before migration, not silently dropped. Migration adds only the new table; no auxiliary-index rebuild.

Nine native migration tests and seven snapshot-conservation tests are integrated but unrun. They include75-entity raw type/byte conservation as stress evidence, separately genuine canonical Central USER rows migrated through real Room with FK checks/DAO reads, native callback-installed schema conservation, rollback, actual SQLITE_FULL, no-write OFF behavior, corruption rejection and FK cascade. Neither synthetic stress rows nor these case counts are represented as executed evidence. Both compatible-upgrade and tree-admission flags remain OFF. Generated Room v2 schema must be captured from the compiler, reviewed and source-bound before acceptance.

The first integrated Full export compiled production and generated the actual Room v2 identity `a48c4a703630b98aa0e0c9ccb54d0912`; all75 original entity schema objects are unchanged and only the dedicated provenance entity is added. Test compilation stopped on three unqualified references to the existing nested `JarvysChatDatabaseProvider.DatabaseTriggers`. These are corrected to the actual native callback owner; no production bypass. The failed export/source-change marker is retained. The compiler-generated2.json is now captured before a fresh unchanged-source build; no pre-write PASS is asserted.

## First OFF execution: 127 cases, one real provider integration failure

Full compile2 succeeded with unchanged source. The12-class gate executed127 cases:126 passed, one failed, no errors/skips. The original ten classes (now111 with strict-codec negatives) and seven snapshot-conservation cases passed. Eight of nine migration cases passed, including rollback/disk-full. **No gate PASS or activation.**

The genuine populated v98 fixture exposed that Room's generated migration TableInfo rejects existing native partial/expression indexes absent from Room annotations. Removing/rebuilding or hiding these indexes is not acceptable. The authorized bounded replacement is a lifecycle-owned pre-Room native transaction: validate the complete pinned v98 schema, `BEGIN IMMEDIATE` and revalidate, add only the provenance table, validate the intended full v2 structure before updating identity/version, then commit atomically and open through the normal provider. Rollback preserves the original error and actual old data; unknown schemas never gain a new identity merely because their old hash matches. The pre-write tests must exercise the same provider path using explicit test-owned upgrade permission while the normal production gate remains OFF.

Self-review also identified that SQLite's FTS `shadow` classification alone is not exact schema proof. A native negative and exact bundled-engine shadow definitions/membership are required before accepting these auxiliary objects. Both corrections remain unimplemented/unvalidated at this checkpoint; the original failing Room/TableInfo evidence is retained, not weakened or deleted.

## Native upgrade replacement integrated OFF

The native FTS negative is concretely red: on pinned bundled SQLite3.50.1, a recreated `_config` with an extra column still reports type `shadow` and the earlier validator accepted it. The extractor passed; the rejection case failed as intended. Static exact module-specific shadow sets/DDL digests now replace suffix-only acceptance; no runtime schema probe or user-data copying is added.

The lifecycle-owned pre-Room upgrade is now integrated, unvalidated. It joins the schema worker's physical outcome before releasing its lifecycle fence if the caller is interrupted; startup recognition remains read-only. Same-file registered readers prevent v1 upgrade. Inside `BEGIN IMMEDIATE`, pinned v1 is revalidated, prospective v2 structure is checked while identity/version are still v1, and the generated identity is updated with an exact old-hash CAS before version publication/final validation/commit. TEXT identity type is mandatory. Exceptions preserve rollback failure as suppressed rather than claiming success. No auxiliary object is dropped/rebuilt.

The compatibility class now has14 cases, adding exact shadow denial, prospective validation before identity, post-identity/version failures, concurrent same-path preparation and a retained WAL read snapshot. Its OFF-provider negative uses explicit test-owned false permission and remains executable after normal gate activation; the positive uses explicit true permission through the same provider path without a global override. Both normal flags remain OFF pending fresh matching Full/Play proof and later independent review.

## Matching pre-write proof PASS; local acceptance enabled

Source `be768cb215d407f9c38773099a74987c4d09cad5`: Full132/0 and Play132/0, exactly12 preregistered classes, no errors/skips. Both exports before/after and current bytes match across1,987 app/testing files; canonical binding SHA256 `c067c722702bbb232c4fc739a3dce229b419e36c0794c46ecbea99c2e16f88f2`. This includes the genuine production-provider upgrade, prospective identity validation, interrupted publication, actual SQLITE_FULL, exact native auxiliary/shadow schema, cached-read/concurrency and snapshot conservation cases. Prior red/incomplete runs remain retained.

Only after this matching result, the normal compatible-upgrade and tree-admission flags are enabled for local native acceptance. This is **not independent P2 PASS or delivery approval**. Final changed-source Full/Play119-class acceptance includes the full132-case gate again; independent review must assess both original blockers and the native migration delta. Old-binary downgrade remains unsupported; no data clearing/reinstallation or physical/live-provider claim.

## Independent R2 FAIL: excluded executable objects and partial message search

R2 independently reran the original five B1/B2 negatives successfully, then found three new native SQLite failures: user triggers named `sqliteXreview` and `android_metadata` were omitted by the schema query, deleted retained fixture rows during identity UPDATE, and committed version2. A missing `trg_message_fts_insert` was also accepted. This is concrete host-fixture conservation failure, not a claim about the user's database. The Full/Play1,127 results remain historical for their exact source; they do not override independent FAIL.

Both normal flags are OFF again. The bounded repair enumerates all schema objects instead of SQL wildcard/name-only exclusions. Only exact supported metadata-table shape/type/identity and native implicit PK/UNIQUE index ownership may be recognized separately. Unknown executable objects must reject before writes. Message search accepts only native-supported absent, exact content-view-only (caught install failure), or complete view/FTS/shadows/all-three-trigger states; partial families cannot pass through to a later drop/rebuild. Actual SQLite negative/conservation tests and fresh matching pre-write proof are required before re-enablement. No new PASS is claimed.
