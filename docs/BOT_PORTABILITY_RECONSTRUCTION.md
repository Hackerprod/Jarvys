# Fresh P2 reconstruction after executor unavailability

2026-10-31. The previous local P2 candidate and bound test artifacts became unavailable after an executor snapshot change. Its observed local commit prefix was `2025d787`; the full object and bytes have not been recovered. **Its former test results do not validate this reconstruction.** No independent P2 PASS or delivery occurred.

This new checkout starts from verified remote backup `7d96ba6a7a6848de62f29e7c25569c0f7ac55a3d`, which contains the approved v98 production backup and selected tests, not a complete build-ready source archive. Existing files remain unchanged while missing test/build/support resources are supplemented from independently fetched remote `337fcac032e2c3f8a5dac01c8d51475f4b939cb5`. Exact paths and hashes are recorded in `BOT_PORTABILITY_RECONSTRUCTION_FILES.json`.

The one restored production dependency, `FactoryFileShareStore.kt`, is referenced by retained FactoryFileShareCoordinator/Provider; both consumers have identical blobs in these two remote commits. Removed legacy memory/migration source files are not restored. Supplementary older tests may require explicit compatibility review; they are not silently represented as the exact former v98 test suite.

A fresh compatible pre-write D3 gate, source-backed P2 implementation, current Full/Play validation and independent review are required. The upstream policy remains provenance-driven (no invented global quotas or mandatory budget). One Central chat, native generalists, Engram memory, restored menu, exact authority and no uncertain-effect replay remain constraints. No deletion/reinstallation, legacy data import, new signing identity, physical/provider claim or new APK is authorized by this recovery step.

## Fresh design gate

Reconstruction design R2 received independent documentary PASS on2026-10-31: `BOT_PORTABILITY_P2_RECONSTRUCTION_DESIGN.md`, SHA256 `8422696642d2d1a353ee99f50432b031407f24702f1dd5f68843cf7c8b35cd88`. Initial R1 accounting/lifecycle omissions were corrected: initial execution1, normal root completion, open-root-only progress reset, and retained needs_input49 followed by budget subtree cancellation without a terminal-report cap. This is permission to implement behind the disabled admission gate, not code/compatibility/test/device approval. Fresh pre-write D3 and implementation review remain mandatory.
