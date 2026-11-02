# Central availability implementation — WIP, gates OFF

Phase A source/host repair has independent scoped PASS; Phase B design has separate independent PASS. Neither certifies this new implementation.

First implementation slice is **uncompiled and untested**: shared OFF migration/admission policy; additive Registryv3 empty central-resume table/index; exact existing-schema recognition and prospective validation before version publication; read-only Doctor version recognition; initial conservation/hidden-trigger negatives. Registry platform exclusions now match object type, owner and exact platform DDL rather than hiding any object named android_metadata. No upgrade is invoked by ordinary reads, no historical root/submission is enrolled and no execution permission is minted by a row.

Remaining: full rollback/interruption/SQLITE_FULL/reader/FK compatibility matrix, Roomv3 companion capability, claim/parking/binding/incorporation protocol, default awaited delegation, provider-attempt fencing, normal USER admission and serial handoff, all closure/readers, paired pre-write gates, final native acceptance and independent implementation review. No new protocol activation, APK, physical-device or live-provider claim. Existing delivered v99 and prior FAIL evidence remain unchanged. Old-binary downgrade after new writes is unsupported.

## First integration checkpoint — still OFF / uncompiled

Native draft interfaces now connect Room atomic submissions and strict context readers, Registry suspension-first reservation/binding/incorporation, provider-only supersession and typed yield, and one serial service handoff. Typed successful terminal evidence preserves original USER/root while validating an incorporated steering owner. Source/Registry/lifecycle validation is outside the Room writer lease; exact cancellation/owner/source CAS remains inside. No authority is reconstructed from stored DTOs.

This is a backup of unfinished implementation, not acceptance. New schema/persistence/transport/Registry tests are authored but unrun. Next action is a diagnostic compile before further integration. Still pending: positive-submission and parked-root STOP wiring, both timeline exact recovery actions, durable restart reconstruction for a PREPARED suspension, omitted delegate AWAIT compatibility, complete rollback/reader/claim tests and native service verticals. Existing app admission/migration flags remain OFF.

## Diagnostic compile 1 retained

Production Kotlin/Java compiled. Test compilation failed on an unnecessary type argument in the new Registry fixture's `RuntimeEnvironment.getApplication()` call; no tests ran and no export/compatibility PASS resulted. That fixture invocation is corrected without changing assertions. KSP generated the new Roomv3 schema during the attempt, so the recorded source binding changed solely for that generated file; the run is not falsely labeled unchanged.

Generated v3 schema SHA256 `2944fdd8d02ee79d1c0ed56fc9fe41c0564106d687ee718c7627a0643f183c72`, identity `dd291fe24d57f2c67de8b0e9dadef7e0`, has the original76 entities unchanged plus central_turn_submissions. Generated table/index/FK definitions match the authored additive schema. Original v2 schema remains byte-identical. Adopted generated source is part of the next frozen diagnostic candidate. This static check is not runtime migration acceptance.
