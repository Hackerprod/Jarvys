# P4A progress — 2026-11-04

Status: independently reviewed design, reproduced baseline defects, integrated candidate with new-write gate OFF. No implementation PASS, new APK, phone or live-provider validation.

## Evidence and gates

Design commit 5968f8ff715d452a4222e354be5e800aee93bdad, document SHA-256 44af5c302c2a6d1bbbd381774a0496b4ef62f472c6b457735f0b010abedec040 received scoped independent design PASS. The immutable source fingerprint additionally binds canonical source sequence/role/text/thread/decision; contextual reads compare the complete accepted USER digest to its committed submission receipt, including coherent multi-field mutation negatives.

[Baseline Full evidence](BOT_PORTABILITY_P4A_BASELINE_RED.json) preserves both expected failures and the separate calibration failure. No Play baseline was run. The original diagnostic source is preserved unchanged outside normal test sources; new acceptance tests use actual native owners instead of restoring argument-only authority. The calibration repair only supplies the project's existing lstat shadow, retaining the real Application and all production guards.

[Pre-write cohort](BOT_PORTABILITY_P4A_PREWRITE_PLAN.json) is preregistered and unrun: 18 storage/proof methods, seven real bootstrap/conservation methods and one OFF-only assertion, plus separate auxiliary checks. The OFF assertion will be archived unchanged outside normal app test sources before activation, not silently skipped in a permanently failing default suite. Final ON validation retains all 25 storage/bootstrap methods plus native acceptance. No new writes are enabled before matching Full/Play evidence and independent D3 review.

## Bounded implementation details requiring independent review

- One Room transaction commits selected-option consumption, genuine threaded USER, strict immutable acceptance proof and existing Central PREPARED. Exact duplicate receipts do not remint a live permit. Inline source identity is physical generation + Central + thread + canonical message occurrence; the accepted USER is the next affordance source.
- Source and one-level referent fingerprints are retained. Context reads compare the full stored root, not merely mutually consistent edited proof fields. Missing receipt/proof fails closed; ordinary legacy history remains historical without backfill. Generic ordinary-send guards remain intact.
- The current physical generation was previously lazy. Existing bootstrap now composes ensure-Central and the existing generation key in one native transaction; existing valid identity is preserved, malformed/non-TEXT identity is rejected, and descriptor reads stay read-only. No schema/table addition or destructive reset.
- UI capture binds the displayed immutable event before asynchronous work, including visible Central/chat, lifecycle and option snapshot. Canvas's actual assistant reply route is corrected and receives a per-render exact selection fence, not a sequence-only retarget. No visual/menu redesign.
- Notification URI and extras bind generation, source, kind and source fingerprint. Only RemoteInput is narrowly mutable; receiver remains explicit/non-exported. Canonical publication is serialized to prevent an older publisher overwriting the newly rotated affordance. This is not a full hide/suppression publication protocol or physical-device proof.
- Native STOP denies its captured pending interactions and existing unclaimed/queued P4 permits, including after ordinary interaction close. Registration and binding are atomic under the native interaction lock without lifecycle/Room acquisition in that lock. Ordinary UI close does not cancel accepted work; existing durable STOP semantics remain in force. Later interactions are not retroactively included by this new denial snapshot.

## Limits

P4A is only the acceptance/handoff subset of the first P4 unit. Exact-evidence hide and source-snapshot conservation are subsequent work; no arbitrary semantic-equivalence, quiet-hours, fatigue threshold or timer is introduced. The source-backed P4B design remains unimplemented. Old binaries are not promised to understand new typed positive submissions. Existing v100 delivery and historical failures remain unchanged.
