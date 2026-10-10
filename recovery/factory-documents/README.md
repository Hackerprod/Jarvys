# UX42 F1: bounded documents (v67 host validation complete)

First SAF/binary vertical slice only; no FileProvider, camera, audio, network, broad storage,
signing change, real document/provider interaction, installation or physical acceptance.

The generated runtime requires its build-pinned, compatible Jarvys host. An exported native
host Activity authenticates the caller's exact installed APK/certificate/version against
current signing evidence, then owns native approval and SAF under the human-only automation
guard and a durable interaction journal. Unknown legacy/stale signing evidence fails closed.
Only one of the two known Jarvys host packages may be installed. These are deliberate,
currently conservative availability restrictions, not standalone SAF support.

Only a temporary single-document grant returns to the native runtime. JavaScript receives
an unpredictable, app/process/page-session-bound handle, never a URI or path. Sequential
32 KiB binary chunks, 16 MiB/handle, cumulative 32 MiB/session and five-minute expiry bound
access. Cancellation invalidates authority; it does not roll back a created/partly written
file or prove remote provider durability. Preview documents are unavailable.

New scope requires independent manifest/DEX review: only two fixed package-visibility
queries may be added to generated apps; zero Android permissions or new generated components.
The host adds the single exported authenticated broker Activity and fixed package queries.

A lost broker owner remains protected after restart. Native recovery requires the user to
close the old picker/task and explicitly acknowledge closure; it is human-reported, not
verified OS closure. Closed interactions cannot resume or return a grant.

Sources consulted 2026-10-10:
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/reference/android/content/ContentResolver
- https://developer.android.com/training/package-visibility/automatic
- https://developer.android.com/reference/android/app/Activity#finishActivity(int)

Source implementation and synthetic host validation are complete; signing and delivery receipts are below; physical acceptance remains separate.

## Staged validation checkpoint

The initial aggregate completed Full 2,932, Play 2,550 and runtime Debug/Release 67 each
with zero failures/errors/skips. The overall run failed: three API variants of the core
saturation test submitted a drain sentinel while its deliberately full queue remained full.
The reviewed fixture fix waits for all 16 queued fillers, preserving every production assertion.
A separate reviewed native test-receipt delta adds all six document methods to Coding test,
asserting preview UNAVAILABLE or undeclared CAPABILITY_DENIED (19 observations total).
Exactly those three source/test files differ from the aggregate freeze. Final focused
Full/Play, complete core Debug/Release, fresh lint and three APK builds are still pending.
Earlier full app/runtime results will be reported as staged coverage, never as a successful
full aggregate on the final delta. No physical provider or device acceptance is claimed.

## Final host validation

Final source commit: `6587d27e916ef6858002e77b5a79888cc8f18b53`.
All 995 app inputs were frozen through final gates and independent review.
The reviewed three-file delta passed 50 Full and 50 Play focused tests (PreviewRegistry
and ProjectService), complete core Debug/Release 119 each, and SDK 11. Every reported
test has zero failures/errors/skips and guarded offline worker evidence. The earlier
Full 2,932 / Play 2,550 / runtime 67+67 coverage remains explicitly staged; the earlier
overall aggregate failed and was not claimed as passed or fully rerun on the final delta.

Fresh lint exactly matches v66: Full 313, Play 300, runtime 3 and core 2 diagnostics,
with no additions/removals or suppressions. Independent source, evidence and all three
APK audits passed. The only release-only removed unrelated synthetic lambda was verified
as D8 renaming with equivalent symbolic bodies and unchanged target/callsite. APKs retain
reviewed permissions, native libraries, prior assets, and strict template/DEX validation.

Unsigned release SHA-256: `e7b55ae1f32d321b7b41bda8f6bc5d1b97fa40fa5f46d63e1b0474e32c97f4de`.
Version 67 / `1.2.60-FACTORY-DOCUMENTS`. Existing D7 test signing and native delivery completed.
The signing receipt restriction is latest-only: signing a newer artifact can make an older
installed generated app unavailable for documents until updated. Compatible Jarvys host
is required; coexistence of both known host packages is unavailable. This is disclosed,
not standalone SAF. No actual picker/provider, grant-cleanup or install acceptance claimed.

## Delivery receipt

Native attachment accepted 2026-10-10 at 05:46:26 UTC. Signed ARM64 test APK: 19,047,939 bytes;
SHA-256 `54e16ca983f571e7d0f84c37e1a2c81eafa5adfef82e6034fcb125d9e447c50b`.
Existing D7 identity verifies v2/v3 signatures, CRC and 16 KiB alignment. Manifest unchanged
from reviewed release; all 238 retained entries match exactly and only nine non-ARM64 native
libraries are omitted. Accepted sending does not prove download, installation or device acceptance.

Next scoped code stage: FileProvider sharing, with an independent design review of bounded
content ownership, minimal private directories, temporary grants, human-only chooser protection
and truthful chooser-opened versus delivered status. Other F1 families, F2/F3, UX34 phases1–4
and UX43 remain separately gated; UX43 is last.
