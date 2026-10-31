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
