# v99 delivery checkpoint — independent scoped packaging PASS

2026-11-01. **P3 R2 PACKAGING PASS**, following P2 R4 scoped host-functional PASS. All three verified parts were sent on 2026-11-01 at 03:57:06 UTC and the delivery channel accepted the message. This establishes accepted sending, not user acknowledgment, download, installation or device execution.

## Approved identity

- Source: `8847012c69e9226de86608b33e7d1a932b36a866`; later documentation commits do not change APK source identity.
- APK: `Jarvys-v99-full-arm64-bot-trees-debug.apk`, 40,996,795 bytes.
- SHA256: `db8736e25634262fbba55314a49a57912d9858ccf107bffdf37460a8391f5b50`.
- Package `com.jarvys.agent`, versionCode 99, versionName `1.2.92-BOT-TREES`; Full DEBUG ARM64.
- Original DEBUG certificate SHA256: `d2de47ccf48e02f58ecf4633392ff3f119e925aad1abaa7921c7ce163cfcec4e`.
- Independent P3 R2 review receipt SHA256: `df662b8fd066ea29de688622e6471d6ac1d54635677428f37a1a22bc875681be`. Raw review and private evidence are not published.

Actual APK manifest differs from actual delivered v98 only in intended version fields; no `android:testOnly`. Original certificate and v2/v3 signatures, all payload identities, ARM64 ELF LOAD alignment and ZIP 16 KiB alignment passed independent packaging review. Native/assets/Factory provenance is unchanged. [Detailed R2 build inputs and evidence scope](BOT_PORTABILITY_V99_CANDIDATE_R2.md).

Two consecutive forced same-cache unsigned rebuilds match SHA256 `6e0e1ae15695033ce68f039739f91a158fca53cdcb4bccca46175df17f00e31d`; this is not independent-machine reproducibility. Build input explicitly includes `-Pandroid.injected.testOnly=false` with the ARM64 ABI target. The earlier R1 APK `8992ce92…` remains FAIL and must not be delivered.

## Delivery parts and reconstruction

[Machine-readable exact names, sizes and hashes](BOT_PORTABILITY_V99_DELIVERY_PARTS.json). Three ordered parts hold one ordinary ZIP containing only the approved APK. First two parts are 16,777,216 bytes each; third is 7,442,543 bytes. They are not individually installable APKs.

Keep all three in the same directory, concatenate in numeric order, then extract the resulting ZIP. For example:

```sh
cat Jarvys-v99-full-arm64-bot-trees-debug.zip.001 Jarvys-v99-full-arm64-bot-trees-debug.zip.002 Jarvys-v99-full-arm64-bot-trees-debug.zip.003 > Jarvys-v99-full-arm64-bot-trees-debug.zip
unzip Jarvys-v99-full-arm64-bot-trees-debug.zip
sha256sum Jarvys-v99-full-arm64-bot-trees-debug.apk
```

ZIP SHA256: `9b02603543df059b7ca5a9fc0eb9d1ef30c1e9aa9ce064364c9432a2f03d24a0`. All three uploaded parts were retrieved again; part sizes/hashes, reconstructed ZIP hash/CRC and extracted APK hash matched. Originals and v98 are preserved. No install, uninstall, data clearing or device setting change was performed.

## Functional evidence and limits

Exact-version host Full and Play each passed 119 classes / 1,134 tests with zero failures, errors or skips, including 139 compatibility cases (not additive); separate Full native-plan five tests do not execute ARM64 binaries. P3 R2 retains these existing results, not a new host rerun: source binding and every nonmanifest unsigned payload are identical. Independent reviewer accepted that scope. [Host results](BOT_PORTABILITY_V99_HOST_RESULTS.json).

Historical P2 R3 fixture-readiness timeout remains an unclassified failure; later acceptance does not establish its environmental cause or timing reliability. No physical-device, live-provider, model-selection, power-loss, exhaustive concurrency or full upstream parity validation is claimed. Additive current-generation upgrades conserve current data; they are not legacy import or destructive reset. **Old-binary downgrade after new writes is unsupported: do not clear data or reinstall as a migration workaround.** No new P4/cost policy or unrelated chat/menu/memory redesign is included.
