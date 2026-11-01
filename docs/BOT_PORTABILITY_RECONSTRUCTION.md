# Fresh P2 reconstruction after executor unavailability

2026-10-31. The previous local P2 candidate and bound test artifacts became unavailable after an executor snapshot change. Its observed local commit prefix was `2025d787`; the full object and bytes have not been recovered. **Its former test results do not validate this reconstruction.** No independent P2 PASS or delivery occurred.

This new checkout starts from verified remote backup `7d96ba6a7a6848de62f29e7c25569c0f7ac55a3d`, which contains the approved v98 production backup and selected tests, not a complete build-ready source archive. Existing files remain unchanged while missing test/build/support resources are supplemented from independently fetched remote `337fcac032e2c3f8a5dac01c8d51475f4b939cb5`. Exact paths and hashes are recorded in `BOT_PORTABILITY_RECONSTRUCTION_FILES.json`.

The one restored production dependency, `FactoryFileShareStore.kt`, is referenced by retained FactoryFileShareCoordinator/Provider; both consumers have identical blobs in these two remote commits. Removed legacy memory/migration source files are not restored. Supplementary older tests may require explicit compatibility review; they are not silently represented as the exact former v98 test suite.

A fresh compatible pre-write D3 gate, source-backed P2 implementation, current Full/Play validation and independent review are required. The upstream policy remains provenance-driven (no invented global quotas or mandatory budget). One Central chat, native generalists, Engram memory, restored menu, exact authority and no uncertain-effect replay remain constraints. No deletion/reinstallation, legacy data import, new signing identity, physical/provider claim or new APK is authorized by this recovery step.

## Fresh design gate

Reconstruction design R2 received independent documentary PASS on2026-10-31: `BOT_PORTABILITY_P2_RECONSTRUCTION_DESIGN.md`, SHA256 `8422696642d2d1a353ee99f50432b031407f24702f1dd5f68843cf7c8b35cd88`. Initial R1 accounting/lifecycle omissions were corrected: initial execution1, normal root completion, open-root-only progress reset, and retained needs_input49 followed by budget subtree cancellation without a terminal-report cap. This is permission to implement behind the disabled admission gate, not code/compatibility/test/device approval. Fresh pre-write D3 and implementation review remain mandatory.

## P3 measured native-resource gap and exact restoration

The first v99 Full ARM64 packaging comparison against the actual delivered v98 APK found four missing native payloads: libproot_exec, libproot_loader, libtalloc and libandroid-shmem. Existing Full LinuxProbe/ProotLauncher/LinuxEnvironment still require them; host P2 functional tests did not establish APK completeness. The incomplete unsigned APK and its identical forced rebuild remain diagnostic artifacts, not deliverables.

The four files are restored only at their original `app/app/src/full/jniLibs/arm64-v8a/` paths. Every byte matches BOTH verified source337fcac032e2c3f8a5dac01c8d51475f4b939cb5 and the actual v98 APK SHA256d70949e04935964d61ab6b7e9d352308c1ef7e1ed602fa6d8bb4b36e9676dd54. Original tools/PROOT_NOTICE.txt, proot_checksums.txt and fetch_proot.sh are retained unchanged for provenance/rebuild documentation; none was executed and no new upstream package/version was fetched. Existing license/source notices remain applicable, with no claim of broader publication-license review. [Exact file manifest](BOT_PORTABILITY_V99_NATIVE_RESTORATION.json).

All first-candidate packaged assets, including the Factory template, already match v98 byte-for-byte; the missing-native finding does not authorize unrelated restoration. Static readelf confirms the four retained ARM64 ELF load segments have16KiB alignment; this is not device execution proof. Fresh final assembly, same-cache reproduction, complete APK/native/signature checks, exact-source host regression and independent P3 review remain required before delivery.
