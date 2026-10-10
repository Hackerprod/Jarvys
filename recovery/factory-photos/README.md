# v69 / 1.2.62-FACTORY-PHOTOS

Status: host validation and independent three-APK audit passed. Existing-D7 signing and native delivery pending. Not a delivery receipt.

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

## Final host validation

Final source checkpoint `989760b5188436c898483f50947b07706cfce059`, app tree
`149f51e70b0acfea411596601bd2ecd96a05fb0c`: all 1,028 source inputs stayed frozen
through the final aggregate, fresh lint and three APK builds. Earlier pending statements above
record the historical sequence and are superseded by these verified final results.

- Full 3,065; Play 2,683; runtime 69+69; core 232+232: 6,350 tests, zero failures,
  errors or skips. SDK 13 passed. All 17 JVMs have verified offline guard install/shutdown
  evidence; 16 external-network attempts were blocked by the host test guards.
- Fresh lint diagnostic multisets exactly match v68: 313/300/3/2. No suppression added.
- Independent source/history/evidence and all three actual APK audits passed. Prior cases
  preserved with four explicit exhaustive-profile test renames; permissions, prior resources,
  native libraries and unrelated assets preserved. Only reviewed photo components, queries
  and seven bilingual strings were added. Embedded generated template remains zero-permission.
- Actual release DEX verifies the API30 guard and exact public-fcntl synthetic outline,
  plus older regular-provider-descriptor fallback. The initial audit's direct-reference
  assumption was corrected against actual D8 output; no app change was needed.

Unsigned APK SHA-256 identities:
- Full Debug (35,015,376 bytes): `9f28e8eead86f7679c8ff7762e66c38893b20b33547fd8222989d0e55671ce05`
- Play Debug (33,403,486 bytes): `fc3b95ddc07be1787ab3a06ec5ed61cd1e9ff58868c483cb658226e771fa1f6c`
- Full Release (27,336,825 bytes): `6ca2b7bfeb03108b36531fa2b00a80da991f3710832502742cff012b1be792a3`

Existing generated apps still require one compatible installed Jarvys host and the exact latest
signed-APK receipt; later signing can require updating the installed generated app. Revocation
cannot retract copies or already-open descriptors. Original EXIF/location metadata may remain,
cloud-backed picker data may be selected, and the external camera may retain its own copy.
Seek/reopen-only cameras are unavailable, without thumbnail fallback. API24–29 nonregular
selected-provider streams are unavailable. Provider I/O may stall despite cancellation; bounded
worker and cross-broker admission remain held until actual completion. Five-minute cleanup
and synthetic tests do not prove physical camera/provider behavior. Real Binder/pipe, picker,
camera, grants, lifecycle, install/update and data preservation remain unverified. No real photo
or user data was accessed, captured, shared or automatically sent during this implementation.
