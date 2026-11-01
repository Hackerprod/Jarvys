# P3 v99 R2 — corrected ordinary-install manifest; review pending

2026-11-01. **Independent packaging review pending.** Application source remains `8847012c69e9226de86608b33e7d1a932b36a866`; no application/test/schema/policy change. The previous signed hash8992ce92… remains **R1 FAIL, not for delivery** because it was marked testOnly.

## Corrected identity

- `Jarvys-v99-full-arm64-bot-trees-debug.apk`, **40,996,795 bytes**.
- Signed SHA256 **`db8736e25634262fbba55314a49a57912d9858ccf107bffdf37460a8391f5b50`**.
- Package `com.jarvys.agent`, version99 / `1.2.92-BOT-TREES`, Full DEBUG ARM64, minSDK24/targetSDK35.
- Original DEBUG certificate SHA256 `d2de47ccf48e02f58ecf4633392ff3f119e925aad1abaa7921c7ce163cfcec4e`; APK v2/v3 verify. No new signing identity.
- Actual manifest **has no android:testOnly attribute**. Full normalized manifest comparison against actual v98 permits only intended versionCode/versionName changes; every other component, exported flag, permission and application attribute is unchanged. No adb test-install workaround or physical-install guarantee.

## Exact build-input repair

Pinned AGP8.13.2's `ComponentUtils.isTestApk` treats a nonempty IDE ABI target as test-only when IDE_TEST_ONLY is unspecified. R1 supplied the ABI target without the explicit override. R2 declares **both**:

```
-Pandroid.injected.build.abi=arm64-v8a
-Pandroid.injected.testOnly=false
-PunsignedBuild=true
:app:assembleFullDebug
```

Same pinned Gradle8.13/JDK21/Android35.0.0 tools and platform36; forced reproduction adds `--rerun-tasks`. This corrects build metadata before normal signing, not the device's installation policy. Actual APK XML is checked rather than relying on later regenerated build-directory manifests.

Two consecutive forced same-cache rebuilds are byte-identical unsigned: **40,955,451 bytes**, SHA256 `6e0e1ae15695033ce68f039739f91a158fca53cdcb4bccca46175df17f00e31d`. First incremental ZIP layout differed with identical uncompressed payloads; retained, not called byte-identical. No independent-machine reproduction claim.

## Unchanged functional evidence and payload proof

R2 unsigned archive differs from R1 in **AndroidManifest.xml only**. All DEX/resources/assets/native contents are byte-identical to the already-reviewed versioned candidate. Signing preserves all417 unsigned payloads and adds only the expected three signature metadata entries. ZIP CRC/16KiB alignment, eight AArch64 ELF16KiB-compatible LOAD layouts, no desktop native/extra ABI, and original certificate verify.

All139 assets, eight native libraries and117 resource files remain byte-identical to actual delivered v98; Factory template unchanged. The resource table's two registry-preservation strings were already P2-accepted, not packaging changes. The four recovered native files still match actual v98 and source337fcac0; original source/license provenance retained. [Restoration manifest](BOT_PORTABILITY_V99_NATIVE_RESTORATION.json).

All1,994 application/testing source bytes and corrected-build before/after bindings equal the existing exact-version Full/Play export binding `a86c7fa3405d08d73f3fd1555b1add2565597aeb42cc40979d14b598fe794796`. **Retained** Full119/1,134 and Play119/1,134 have zero failures/errors/skips; included139 compatibility cases are not additive. Separate Full native-plan5/0 does not execute ARM64 binaries. [Host results](BOT_PORTABILITY_V99_HOST_RESULTS.json). These tests were not rerun for the manifest-only command-input correction; the unchanged source and exact nonmanifest payloads justify retaining their scope, subject to independent assessment.

## Limits

P2's scoped functional PASS retains the earlier unclassified15-second fixture-readiness failure; no timing reliability/latency certification or environmental-cause claim. No physical installation, live-provider/model-choice, power-loss, exhaustive concurrency or full upstream parity claim. Preserve v98/user data; additive current-generation upgrades are not legacy import/destructive reset. Old-binary downgrade after new writes is unsupported. No new P4/cost policy, Chief inheritance, unsafe replay, chat/menu or memory system. Prior failures and incomplete/interrupted builds remain preserved. **Do not deliver until independent P3 PASS.**
