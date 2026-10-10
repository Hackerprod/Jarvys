# UX42 F1: bounded documents (v67 work in progress)

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

This checkpoint is unfinished source, not a claim that tests/builds/security review passed.

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
