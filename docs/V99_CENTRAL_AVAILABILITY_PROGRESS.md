# Central availability implementation — WIP, local acceptance enabled

Phase A source/host repair has independent scoped PASS; Phase B design has separate independent PASS. The paired pre-write compatibility gate now passes; native functional acceptance is in progress with local gates enabled. No Phase B implementation PASS or new deliverable is claimed. Earlier OFF checkpoints below are historical.

First implementation slice is **uncompiled and untested**: shared OFF migration/admission policy; additive Registryv3 empty central-resume table/index; exact existing-schema recognition and prospective validation before version publication; read-only Doctor version recognition; initial conservation/hidden-trigger negatives. Registry platform exclusions now match object type, owner and exact platform DDL rather than hiding any object named android_metadata. No upgrade is invoked by ordinary reads, no historical root/submission is enrolled and no execution permission is minted by a row.

Remaining: full rollback/interruption/SQLITE_FULL/reader/FK compatibility matrix, Roomv3 companion capability, claim/parking/binding/incorporation protocol, default awaited delegation, provider-attempt fencing, normal USER admission and serial handoff, all closure/readers, paired pre-write gates, final native acceptance and independent implementation review. No new protocol activation, APK, physical-device or live-provider claim. Existing delivered v99 and prior FAIL evidence remain unchanged. Old-binary downgrade after new writes is unsupported.

## First integration checkpoint — still OFF / uncompiled

Native draft interfaces now connect Room atomic submissions and strict context readers, Registry suspension-first reservation/binding/incorporation, provider-only supersession and typed yield, and one serial service handoff. Typed successful terminal evidence preserves original USER/root while validating an incorporated steering owner. Source/Registry/lifecycle validation is outside the Room writer lease; exact cancellation/owner/source CAS remains inside. No authority is reconstructed from stored DTOs.

This is a backup of unfinished implementation, not acceptance. New schema/persistence/transport/Registry tests are authored but unrun. Next action is a diagnostic compile before further integration. Still pending: positive-submission and parked-root STOP wiring, both timeline exact recovery actions, durable restart reconstruction for a PREPARED suspension, omitted delegate AWAIT compatibility, complete rollback/reader/claim tests and native service verticals. Existing app admission/migration flags remain OFF.

## Diagnostic compile 1 retained

Production Kotlin/Java compiled. Test compilation failed on an unnecessary type argument in the new Registry fixture's `RuntimeEnvironment.getApplication()` call; no tests ran and no export/compatibility PASS resulted. That fixture invocation is corrected without changing assertions. KSP generated the new Roomv3 schema during the attempt, so the recorded source binding changed solely for that generated file; the run is not falsely labeled unchanged.

Generated v3 schema SHA256 `2944fdd8d02ee79d1c0ed56fc9fe41c0564106d687ee718c7627a0643f183c72`, identity `dd291fe24d57f2c67de8b0e9dadef7e0`, has the original76 entities unchanged plus central_turn_submissions. Generated table/index/FK definitions match the authored additive schema. Original v2 schema remains byte-identical. Adopted generated source is part of the next frozen diagnostic candidate. This static check is not runtime migration acceptance.

## Diagnostic 2 and exact restart-checkpoint repair draft

Frozen source `74e27f3732e3e91c56a5e969e13ffd0b6011b689` compiled and ran Full8 classes/70 tests with zero failures/errors/skips, unchanged source/artifacts. [Exact diagnostic manifest](V99_CENTRAL_DIAGNOSTIC_2.json). This is not complete D3 or final acceptance and does not validate subsequent source.

A concrete recovery gap was found: existing transcript rows do not reconstruct every full loop field, and historical checkpoint/artifact codecs sanitize or truncate. A digest plus process-local copy is insufficient for the promised explicit PREPARED restart recovery. The new **uncompiled/untested** draft reuses CrewCheckpointStore session/generation/path verification, materialization budget, immutable envelope, fsync/atomic rename/readback machinery with an explicit CENTRAL_YIELD namespace (no fake worker/Coding scope). Legacy codec remains unchanged. A strict separate codec retains full UTF-16 content, sources, attachments/images, inputs, tool lifecycle and incorporation evidence. The Registry certificate binds immutable checkpointReferenceId and payload hash; a reader returns evidence only, never permission. Blob persistence precedes parking; an orphan blob cannot dispatch. Missing/mutated/uncertain evidence refuses recovery. Exact fresh native action, Room source verification and Registry recovery CAS remain required.

The inference return/supersession transition is also made atomic under its existing lease monitor; ordered tests cover both outcomes without claiming reproduction of the former microscopic interleaving. Added22 tests in this delta are UNRUN. Gates remain OFF; no new artifact or runtime PASS.

## Native control/recovery integration checkpoint — uncompiled, OFF

Draft integration now includes exact PREPARED suspension recovery descriptors/actions, immutable checkpoint readback plus canonical Room/artifact/attachment-source verification, pending-input exclusion from reflection, chronological control staleness fences, existing-row recovery controls on both timelines, and STOP/catalog/terminal linkage. Registry predecessor-denial links close STOP during successor binding without cancelling an unrelated new USER root. Omitted delegation policy resolves against both retained assignment modes inside native authority/Registry serialization; only a genuinely new omitted request defaults to AWAIT. Existing started/uncertain rows are observations, never re-executed.

The real native service vertical fixture is authored with an enabled precondition and is intentionally excluded from pre-write tests while flags remain OFF. No new tests in this checkpoint have run. Remaining bounded corrections before compiling include receipt-owned durable skill selections, retaining attachments after committed/uncertain append followed by registration failure, and exact displayed Canvas recovery revision fences. These are actual conservation/authority gaps, not deferred production behavior or PASS claims.

## Native action conservation correction — still OFF

The pending corrections now persist and reuse receipt-owned skill selections, retain actual committed/uncertain attachment bytes after a post-append registration failure, and require the exact displayed recovery fence in both Canvas checks. A newer row cannot silently replace the selected recovery target. These changes and their native fixtures are not yet compiled or run.

Pre-write reconciliation identified remaining explicit proofs: mixed Room/Registry readiness, genuine populated native v2-to-v3 provider opening and Doctor/export, and checkpoint precommit/readback failure conservation. They are being added before activation; the earlier70-test diagnostic is not a complete compatibility gate. Native USER mutation paths were inspected: assistant-only editing rejects USER, stable-ID append refuses conflicting content, and pending-source regeneration is denied. No additional production editor change was needed.

## Pre-write proof additions — authored, not yet run

The compatibility fixtures now cover composed populated native v2-to-v3 provider opening, actual upgraded Doctor/export readers and both partial-upgrade capability vectors. OFF-policy rejection is explicitly not claimed as proof of live admission; native mixed-schema rejection will also be exercised after the pre-write gate. The Room writer checks companion availability before appending a USER.

Checkpoint fixtures now retain nonempty incorporation/provider-output/compaction fields and inject pre-write, file-sync, rename and readback failures. A package-private CENTRAL-only fault seam defaults to no-op; ordinary Crew writes are unchanged. Explicit suspension recovery additionally supplies the current canonical storage generation to the native snapshot reader. Same-files lifetime reset and blob-commit/park-failure behavior remain required native integration acceptance. No gate has been enabled.

## Resume lineage corrections under preparation

Read-only source review found two concrete integration gaps before the next compilation: incorporated STEER attribution was process-local and would be lost after suspension restore; the suspension context validator required unrelated newer completed USER sources from global protection. The bounded correction conserves ordered, canonically revalidated incoming-owner evidence without ACK/replay and scopes only the exact suspension reader to retained/native-successor sources. Generic readers and original root/budget authority remain unchanged. These are source-traced defects, not executed red reproductions or a phone-cause claim. Native regression fixtures must validate final-owner attribution, unrelated-root exclusion, and changed/missing-source refusal.

The prepared3448e540 compatibility candidate was backed up but no compile/test was started before these findings. No new runtime result supersedes diagnostic2. All flags remain OFF.

## Pre-write compile3 retained FAIL

Exact180bf6c3 source remained unchanged. Production Kotlin compilation failed before tests because CentralYieldSourceEvidence imported CentralContinuationRecord from the wrong package. The import now targets the existing native class; its existing pure structural comparator is public without changing behavior. No tests or Play ran, and no compatibility PASS resulted. The correction will be validated by a fresh serialized attempt.

## Pre-write attempt4 retained results

Exacte4d722e9 Full compilation passed with unchanged source. The guarded12-class/125-test OFF cohort then finished with2 failures,0 errors/skips and unchanged source/artifacts; Play was not started. One assertion compared JSON object insertion order instead of canonical structural equality. The other exact-source fixture was rejected for unresolved tool effects; its native batch-completion contract is being checked, without relaxing the uncertainty guard. These results are not a compatibility PASS.

Source tracing established that the unresolved-effects failure was a verifier defect, not a missing fixture batch-terminal event. ModelTranscriptRepository retains call STARTED and a separate RESULT receipt; InteractionProtection uses this native pair. The verifier now accepts only STARTED/RESULT or NEVER_LAUNCHED/NEVER_LAUNCHED with exact tool name and strictly ordered integer lifecycle/result sequences. Missing, uncertain, mismatched and restarted effects remain rejected. The original long-payload positive fixture is retained, with pre-result denial and a new native lifecycle matrix. The next pre-write selector has126 tests.

## Paired pre-write gate PASS; local functional acceptance enabled

Sourcea3f65d53b2a75ae6d160f16cf9833f294ca6af8d passed Full126 and Play126 across the same12 preregistered classes,0 failures/errors/skips; source/artifact bindings stayed unchanged. Both prior failed attempts remain recorded. This compatibility gate permits local migration/admission activation for native acceptance, not a Phase B functional PASS or delivery approval.

The three native gates are now enabled for that local acceptance. Six additional authority-boundary tests join the existing fixtures: exact fresh recovery rejects mutated blob/USER/STEER; real minted claims reject wrong token, unconfirmed/foreign-run witnesses and revoked reservation; STOP before successor dispatch forbids replay. Resumed provider payload must exclude the unrelated fresh USER/answer. These tests are authored and UNRUN. The exact-final source will rerun the compatibility cohort and native/baseline Full/Play acceptance before independent review.

## Enabled native attempt1 retained FAIL

Source dd5f7453 compiled, then the15-class/152-test enabled cohort finished with25 failures,0 errors/skips and unchanged source/artifacts. Baseline union and Play were not started. The shared production defect was the native steering cursor reading positively registered NEW_TURN rows; existing incorporation guards correctly rejected them. The cursor now excludes all registered NEW_TURN states transactionally, including completed rows, while genuine STEER and ordinary retained inputs remain eligible. A native regression covers these distinctions and reopen.

Other diagnosed failures concern fixtures: attachment copying requires content URIs, tool responses retain their existing workspace_data envelope, and fixed v1/v2 compatibility must select that explicit opener rather than enabled v3 defaults. The Registry read test now verifies non-migrating ordinary reads under either policy. Durable unknown-trigger rejection remains intact; the park-failure fixture must target a connection-local fault only after actual confirmed checkpoint persistence. No timeout or production authority guard is relaxed.

## Enabled native attempt2 retained FAIL

Source8b0a0a81 compiled and executed153 focused tests:148 pass,5 fail,0 errors/skips, with unchanged source/artifacts. The new-USER/old-root native flow and all8 default-delegation cases now pass. Remaining failures involve fixture FileProvider cache isolation, raw platform SQLite lacking the registered native FTS module for corruption-negative setup, and the exact STOP assertion under diagnosis. No broad union or Play was started; this is not a functional PASS.

The remaining fixture corrections preserve native contracts: clear only AndroidX test root-cache state between Robolectric methods; mutate corruption targets through the actual registered Room connection with FTS triggers intact; issue STOP from an external native caller while the real claimed owner is held at a deterministic prebind barrier. The STOP assertion now preserves earlier callback failures and cannot mistake its own assertion for a native denial. The prior null-denial observation was inconclusive, not a demonstrated production STOP defect. No production code changed in this correction.

## Broad diagnostic and exact STOP RED; corrections awaiting execution

The focused15/153 cohort passed on917c4667, but the broader Full135/1278 union retained51 failures (0 errors/skips); no Play followed. An adjacent audit identified the missing durable predecessor-root closure after STOP between reservation and successor binding. Stronger tests on79400906 with unchanged production then reproduced3 failures among16 native boundary cases: predecessor denial, generic STOP durable closure, and queued-child policy. [Exact sanitized failure bindings](V99_CENTRAL_UNION_AND_STOP_RED_FAILURES.json).

The candidate correction reuses the actual captured suspension permit and native stopRequester. Native service STOP latches both exact cycles before blocking work; all USER-stop exit paths attempt durable predecessor and successor closure independently, preserving failures. A cancellation listener is released only after the physical owner unwinds. Generic global callback timing remains unchanged, not promised synchronous. Started children may finish/report without waking the stopped parent; never-started owned children cancel, without reset/replay or reaching another root.

The UI queue projection now runs its evidence reads through the existing guarded bridge executor, without eager database creation, main-thread guard relaxation, new executor or worker-to-admission-lock inversion. P1 claims for already-enrolled v99 Registryv2 roots explicitly perform the proven compatible Registry upgrade only after native live-permit/payload checks and Roomv3 readiness, before consuming the permit or publishing STARTED; root eligibility is revalidated afterward. Ordinary eligibility reads and un-enrolled historical P1 remain non-migrating.

Fixture adaptations preserve existing contracts: CentralTreeService24 now uses actual native Send/Intent/ActiveRun rather than inventing a fresh tree through reflection; retained negative cases remain plain. Existing tool mocks implement the new automatic-delegation method while retaining cancellation/error/payload assertions; the explicitly legacy backend uses explicit detached mode. STOP persistence faults use connection-local native TEMP triggers with the actual failure marker checked, leaving durable schema validation intact. These corrections are not yet compiled or green.

## Exact STOP correction GREEN; affected acceptance remains FAIL

On319a99a1, the strengthened16-case native boundary cohort passed with unchanged source/artifacts. This closes the three executed prebind-STOP regressions for that candidate; the earlier RED remains retained. The subsequent seven-class affected cohort finished145 tests with7 failures,0 errors/skips, also unchanged. MainAgentInteraction54, fresh-USER STOP6 and the two tool cohorts54 all passed. No broad union or Play followed this failed affected run.

Five remaining failures were diagnosed as fixture incompatibilities: terminal fixtures supplied an invented runId instead of the actual STARTED claim; detached-worker STOP targeted a different global controller instead of the captured actual provider token; UI test setup bootstrapped storage on the main thread; and a negative assertion expected a different exception subtype. Their semantic assertions are retained. Two regeneration failures exposed a real compatibility gap: successful registered USER sources were denied ordinary native regeneration, and hiding the original assistant invalidated its retained completion receipt. The bounded repair preserves exact native regenerated-marker evidence and explicit one-use ordinary regeneration only; it cannot reset a submission, grant tree authority, replay an uncertain source, or mint a new root. These follow-up changes are not yet validated.

The ordinary regeneration path remains deliberately without tree admission. Consequently it does not install the delegated-root yield boundary: a new USER can be queued while regeneration is running, but processing waits for that ordinary provider turn or STOP. This is a source-traced availability limitation, not a proven device cause or a completed general-responsiveness guarantee. Independent implementation review must assess it against the Central-availability objective; granting fictitious tree authority is not an acceptable workaround.
