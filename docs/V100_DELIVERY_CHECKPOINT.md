# v100 — scoped host and independent packaging PASS

## Approved artifact

- `Jarvys-v100-full-arm64-central-available-debug.apk`, **41,250,747 bytes**.
- SHA256 **`8401f07de04af9eca9f8e573d697cce4642529b748d21b9f7b6a4a105bd7da57`**.
- `com.jarvys.agent`, versionCode100 / versionName`1.2.93-CENTRAL-AVAILABLE`, Full ARM64, original DEBUG certificate `d2de47ccf48e02f58ecf4633392ff3f119e925aad1abaa7921c7ce163cfcec4e`; v2/v3 verified, testOnly absent/false.
- Build source `9da390a01d10c1a6887f92e3af5e937c22b108ef`, tree `95b30510862ba65f85e9a471052e5521bb40bf53`. Subsequent documentation-only commits do not change signed bytes or tested app source.

Phase A and [Phase B](V99_CENTRAL_IMPLEMENTATION_PASS.md) retain independent scoped host PASS. Final-version Full and Play each passed **148 classes / 1,358 tests**, zero failures/errors/skips with exact matching names and unchanged bindings. The packaging reviewer independently inspected those results, not reran them; prior Phase B reviewer independently executed314+2 per variant. [Final-version host evidence](V100_FINAL_VERSION_HOST_RESULTS.json).

The new clean-context packaging review returned **PASS** on the exact signed hash above. Report SHA256 `9dcd9b3434fa21437a289b5e946c3cc79fbf83d29f9686bebde3527efc68efcf`; binding record SHA256 `5abd5bb39d7353ccb03b51a453f9fef52d4f6a8f206266ece8730dd1679a0a16`. Raw review/logs remain private.

## Reproduction and compatibility

Forced offline rebuild executed143/143 tasks and reproduced the complete unsigned APK (`238bce3525be652674357ae1209c69cdfe7de42ee57ce266c016a0827e798bd5`) exactly. Separately signing that repeat produced the exact approved signed bytes. This is same-cache/same-environment reproducibility, not an independent-machine claim. [Sanitized packaging evidence](V100_UNSIGNED_PACKAGING_RESULTS.json), SHA256 `2cd3b3ba896d0f4a2a9be7882110080aeb8868f97d0007bfb58b2412ef415b3b`.

Compared with actual delivered v99, semantic manifest differences are only intended versions; two raw label IDs resolve to unchanged names/values. Existing resources are conserved, with seven reviewed Central status/interruption strings added. All eight ARM64 native libraries are byte-identical to v99 and independently pass ELF16KiB checks; ZIP alignment also passes. Existing compressed native packaging is not represented as direct-mmap alignment. No unrelated menu/permission/component changes.

The embedded Factory template matches its packaged SHA (`784c876eb382ac7e8c530f0b3b88c0cd6a296ff36901a68da180e9ed79ba3fa1`). It is not byte-identical to v99: static DEX inspection localized equivalent compiler shared-branch layout changes in two methods, with no new calls/classes/capabilities; the profile delta is its exact corresponding DEX CRC. Other template entries, manifest and resources remain identical. Factory source modules are unchanged. This is static classification, not executed equivalence proof; the final forced rebuild reproduces the template exactly.

## Delivery preparation

The approved APK is contained in one ZIP split into16MiB parts `.001`, `.002`, `.003`. All three uploaded parts were retrieved again and verified byte-for-byte; reassembly passes ZIP CRC and the exact APK hash. [Part names, sizes and hashes](V100_DELIVERY_MANIFEST.json). The user channel accepted the single three-part delivery on2026-11-02 at16:35:36UTC. Acceptance does not imply download, installation or phone validation. No duplicate delivery was sent.

Keep all three parts together, open `.001`, and do not mix them with v99 parts. The original identity permits an update over the existing app without clearing data. Do not uninstall/reset to test this repair. Binary downgrade after newer storage writes is unsupported.

## Retained limits

All original FAILs and intermediate build/collector/interruption outcomes remain in [packaging history](V100_PACKAGING_PROGRESS.md) and [implementation progress](V99_CENTRAL_AVAILABILITY_PROGRESS.md). No phone-specific cause or reliability, live-provider behavior, universal response latency, remote computation/billing termination or exhaustive concurrency is proven. Central availability applies to sealed inference and safe boundaries, not arbitrary committed-effect interruption/replay. One general-purpose Central chat, menu, user data and Engram remain preserved. No device installation or data mutation was performed during validation.
