# v69 / 1.2.62-FACTORY-PHOTOS

Status: implementation checkpoint, host review and validation in progress. Not a release receipt.

Photos are a separate declared capability requiring documents. The exact latest signed APK
and unique compatible host are authenticated. Only native human actions launch a trusted
system picker or camera and return the photo. Cancellation/restart retains durable automation
protection and explicit uncertain-outcome recovery. Existing v63–v68 behavior is preserved.

Capture uses an exact one-use write-only URI backed by a bounded reliable pipe, no filesystem
path and no broad camera/gallery permission. Requires RESULT_OK plus clean EOF. Cameras
requiring seek/reopen are unsupported; there is no thumbnail fallback. Selected/captured input
is restricted to JPEG/PNG, 8 MiB, 4096 pixels per side and 12 megapixels, with bounded decoded
allocation. Original metadata remains. No automatic sending or Jarvys gallery publication.

A reverse native FileShareTransfer transports immutable chunks into page-scoped document
read handles. Preview is unavailable. URI/path/Binder authority is never exposed to JavaScript.
Physical camera/picker/Binder interoperability, grants, installation and data preservation
remain unverified. Other F1/F2/F3 families, gated UX34 phases and UX43-last remain unchanged.

Runtime focused validation passed 232 core tests, 69 APK-runtime tests and 13 Node SDK tests.
The initial core run had six new test-helper queue failures; the corrected helper passed.
The first host focused run had 45 tests with four fixture failures (unmodeled Robolectric pipe
syscalls and a missing fixture deadline). A subsequent fixture-shadow compile collision was
retained as failed. Explicit synthetic syscall shadows now exercise unchanged production loops;
bitmap decoding is native. Later focused runs passed 53, 55, 58 and 61 tests respectively.
The last adds native Close/Back cancellation and never-opened camera-output coverage. All
seven final focused JVMs have offline guard install/shutdown evidence, zero blocked attempts.

Final source additionally makes camera-open visibility volatile and retains cross-broker
admission until actual cancelled materialization cleanup; its 62nd ownership regression awaits
the full aggregate. Independent static reviews approve bounded output, expiry, stale callback,
recovery and final ownership paths. These are not physical pipe/camera acceptance claims.

Full six-module aggregate, exact lint comparison, three-APK audit and separate existing-D7
signing/native delivery remain pending. Code and Pending are backed up before these longer
gates. Detailed host reports, synthetic execution logs and artifacts stay outside this repository.

Initial lint is retained as a failed release gate: Full 318/Play 305, each with four new API30
fcntl diagnostics and one Context-singleton warning; runtime 3/core 2 remained unchanged.
The repair guards public fcntl behind API30, uses the private camera pipe's sole-reader
poll/read invariant on API24–29, and restricts older selected-provider descriptors to regular
files. Shared descriptor flags and uninterruptible provider I/O remain explicitly disclosed.
The singleton now retains application PackageManager instead of a Context field. No lint or
deprecation suppression was added.

The repaired host source passes 81 focused tests, including 19 API24/29/30 compatibility cases
and cross-broker pending ownership. Seven guarded JVMs report zero blocked network attempts.
The missing API30 Robolectric SDK was prewarmed from pinned Maven Central bytes with published
SHA-512 verification; test JVMs remain offline. The fourth manifest/catalog exhaustive test
now covers all 256 subsets. Fresh full aggregate/lint/build/APK audit still remain pending.
