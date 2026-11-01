> Independent P3 R1: **FAIL for delivery** of signed hash8992ce92… because its actual manifest contains android:testOnly=true. P2 functional PASS is unchanged. Old bytes retained; bounded build-input correction must receive fresh artifact review. No adb test-install workaround.

# P3 v99 packaging candidate — independent review pending

2026-11-01. P2 has [independent scoped host functional PASS](BOT_PORTABILITY_P2_CHECKPOINT.md); this separate packaging gate is **pending independent PASS**, not delivery approval.

## Exact identity

- Source: `8847012c69e9226de86608b33e7d1a932b36a866`. Since P2 PASS, only version identity and exact previously delivered Full native resources/provenance were added; no functional Kotlin/Java/schema/policy edits.
- APK: `Jarvys-v99-full-arm64-bot-trees-debug.apk`, **40,996,795 bytes**.
- Signed SHA256: **`8992ce92e247bfce8733287781a60933cb58277d43b90fcad9a38e8cfa56c227`**.
- Package `com.jarvys.agent`; versionCode99 / `1.2.92-BOT-TREES`; Full DEBUG, ARM64; minSDK24, targetSDK35, compileSDK36.
- Original authorized DEBUG certificate SHA256: `d2de47ccf48e02f58ecf4633392ff3f119e925aad1abaa7921c7ce163cfcec4e`. Actual v98 and v99 signatures both verify with that certificate. No replacement key or production identity.

## Exact-source host acceptance

Full/Play each **119 classes / 1,134 tests**, zero failures/errors/skips, on identical final1,994 app/testing files. Included139 compatibility cases are not additive. Separate Full `ProotLauncherProbeTest`5/0 checks native launcher/probe plans without running these ARM64 binaries; not added to the common cohort. [Per-class results](BOT_PORTABILITY_V99_HOST_RESULTS.json).

Both exports and assembly/rebuild before/after/current source maps match canonical binding SHA256 `a86c7fa3405d08d73f3fd1555b1add2565597aeb42cc40979d14b598fe794796` (`json.dumps(binding, sort_keys=True, separators=(',', ':'))`, UTF8). Documentation-only commits do not change the built source identity. The class list remains `BOT_PORTABILITY_P2_ACCEPTANCE_CLASSES.json`, SHA256 `ae7cb7a254d84b5c7387c3f0f51105480b0cefee2b3038354d2eb9ca290dddd9`.

## Artifact checks and bounded reconstruction repair

- ZIP CRC/integrity, manifest/version/package, Android ARM64 native set, signing and16KiB ZIP alignment verified. All eight ELF files are AArch64 with16KiB-compatible LOAD alignment/offsets. This is static compatibility evidence, not device execution.
- Every one of139 assets and eight native payloads equals the **actual delivered v98 APK**, not only a source assertion. Factory template SHA256 remains `d3b1854af44deaeaaefb1a11a72cbb434ef1df7075e167ae2a48a79b583f7a54`. Permissions unchanged. No desktop SQLite native library or additional ABI.
- Signing preserves all417 unsigned archive payloads byte-for-byte and adds exactly the expected three JAR signing metadata entries. apksigner verifies APK v2/v3 signatures; no claim of v1/v4 verification.
- Actual v98 was reassembled from its three retained delivery parts, ZIP CRC checked and exact APK hash `d70949e04935964d61ab6b7e9d352308c1ef7e1ed602fa6d8bb4b36e9676dd54` verified. It remains unchanged.
- Initial packaging exposed four missing Full PRoot/talloc/shmem binaries in the reconstructed checkout. They were restored at original paths using exact bytes matching BOTH actual v98 and verified source337fcac0, together with original license/source/pinned-package provenance. No native payload or fetch script executed, no upstream version change. [File hashes/provenance](BOT_PORTABILITY_V99_NATIVE_RESTORATION.json), [incident and scope](BOT_PORTABILITY_RECONSTRUCTION.md).

## Reproduction and retained failures

Two consecutive forced Gradle rebuilds on the same pinned toolchain/cache produce identical unsigned bytes: **40,955,463 bytes**, SHA256 `666310e93be267938f3c8497d5d525e930152c15780dc2c0083394535859ef94`. This is **same-cache reproduction, not independent-machine/clean-download proof**. The first incremental package after adding native resources had identical uncompressed payloads but a different ZIP layout/size; that observation is preserved rather than called byte-identical.

Build uses the project's pinned Gradle8.13, JDK21, Android build-tools35.0.0/platform36, `-PunsignedBuild=true`, `-Pandroid.injected.build.abi=arm64-v8a`, `:app:assembleFullDebug`; reproduction adds `--rerun-tasks`. External signing uses only the original approved identity through non-echo input. No signing material is a source/backup artifact.

The initial offline assembly lacked pinned desugaring jars and failed; official configured-repository bootstrap supplied the existing versions. A subsequently interrupted session with no terminal marker remains unknown, not success. The next completed assembly succeeded. Incomplete pre-restoration APKs are retained as diagnostics only, never candidates for delivery.

## Limits carried forward

P2's earlier independent Play child-readiness timeout remains **FAIL, cause unclassified**; later bounded diagnostics and final passes do not certify timing reliability or label it environmental. Automatic failure-boundary diagnostics preserve the original15s guard and assertions. [Full timing limitation](BOT_PORTABILITY_P2_STARTUP_DIAGNOSTIC.md).

No physical device, actual user installation, live-provider/model-choice, process/power-loss, exhaustive concurrency, universal Android compatibility or full upstream parity claim. Single Central, generalists, Engram and v97 menu remain; no new cost/P4 policy, Chief inheritance or unsafe uncertain replay. Current Registry/Room upgrades are additive and guarded, not legacy import or destructive reset. **Old-binary downgrade after new writes is unsupported; never clear/reinstall user data.** Independent packaging review and authorized delivery still remain.
