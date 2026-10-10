# Factory guidance maintenance v78

Version 78 / `1.2.71-FACTORY-GUIDANCE` passed final host validation and independent
source/three-APK audit. Signed native ARM64 test-APK delivery was accepted on
2026-10-10 at 19:14:09 UTC.
It adds no Factory runtime capability: v77 remains 18 capabilities, 36 methods, SDK 2/schema 1.
Accepted delivery does not prove download, installation, physical-device or real-model behavior.

## Contract

The reserved Coding-only Factory skill previously occupied 16,377 of 16,384 bytes and included
an inaccessible reference to a repository document. The new core remains under the unchanged
16 KiB skill limit and has an additional 8 KiB UTF-8 bound. The reviewed core is
7,167 UTF-8 bytes, leaving over 1 KiB below that stricter bound. It is appended to the eligible
Coding runtime's instructions, including read-only review, independently of transcript
compaction. It does not grant tools, access, approval or implementation authority.

Seven immutable APK references carry detailed API and lifecycle contracts. Existing
`read_skill(skill_id)` returns its original complete-body format; the optional `resource` and
`resource_version` must be supplied together using exact identifiers from the core. The code
allowlist, manifest hashes, guidance version, UTF-8 validation and byte bounds reject unknown,
missing, modified or oversized resources. There are no filesystem or remote fallbacks.

Complete-required tool output now either fits its model and applicable per-turn budget or
fails without claiming a partial load. Ordinary large Crew output remains recoverable through
its existing artifact mechanism. The unbounded mission execution policy remains unchanged.

Factory was already a reserved asset, not a seeded editable skill. APK upgrade therefore
replaces the bundled guidance without editing private skill files, usage/disabled preferences,
custom profiles, Coding profile version or other imported skills. Existing reserved-ID access,
workspace/import restrictions, scope reduction and delegation protections remain in place.

[Semantic mapping](semantic-mapping.json) records every v77 section and its destination.
All source paragraphs are retained verbatim except the explicitly recorded roadmap wording
clarification and the replacement of the inaccessible presentation pointer with the complete
existing v77 presentation contract. Critical scope, safety, uncertainty and device limits are
also present in the always-loaded core. The skill format version remains 1; guidance has a
separate release-bound version.

This maintenance does not enable TTS/voice, other unfinished F1, F2/F3, or any permission/provider
access. UX34 remains gated, UX44 documentary, UX43 last. Physical-device/model/provider behavior
and all earlier outstanding acceptance checks remain unverified.

## Intermediate checks

The first focused checks passed 85 Full and 85 Play cases, SDK30 passed, and exact lint
preserved all 313/300/3/2 inherited diagnostics. A subsequent reviewed core-only prose
refinement creates future catalog headroom without changing detail modules. Those earlier
passes are retained as intermediate evidence; the fresh final checks below supersede them. An initial
compile command failed before compilation because it used the repository rather than Android
build directory; the corrected command passed. No test assertions were weakened.

## Final frozen-source host receipt

Implementation checkpoint `6cc4817`, reviewed core-headroom refinement `806093d`. App tree
`ae7cd619f631286f3b0694f9253e653bf7d7f682`. All 1,113 inputs match across source, lint,
aggregate, build and after-build freezes. No cap increase, truncation or weakened assertion.

Final focused checks: 85 Full + 85 Play, including actual read-only Coding retrieval, every
resource, stale/unknown/path/byte/header/model/turn budgets, immutable hash-checked assets,
customized-file/settings/profile preservation, UTF-8 boundaries and all 18 prior sections.
Complete aggregate: Full 3,515; Play 3,133; runtime 74+74; shared core 734+734. Total 8,264,
zero failures/errors/skips. All prior v77 cases retained; 15 added cases per app variant.
SDK30/30 retains the same case inventory. All 17 aggregate test JVMs have independently
verified offline-guard installation/shutdown records; network attempts were blocked.
Exact lint diagnostic multisets remain 313/300/3/2, without additions, removals or suppressions.

Independent audit passed all three unsigned APKs and actual debug/release production control
flow. Permissions, components, queries, resources, native assets, Factory catalog/SDK and the
embedded v77 Factory template remain unchanged. No removed DEX classes or unexplained
optimization differences; the guidance assets match reviewed source and their manifest hashes.
The principal/custom/delegated scope is not expanded. No new provider access is added.

FullRelease unsigned: 27,530,502 bytes, SHA-256
`c024e43480df0cbce08e24de13b5d65d8b4520672fb276826275a7b6482816dc`.
This receipt is not a signed installable delivery or evidence of installation. Real-model
behavior, physical Android acceptance and earlier device/update/data-retention gates remain
unverified. TTS/voice, remaining F1/F2/F3, UX34/UX44/UX43 status is unchanged.

## Signed native delivery

The native send of `Jarvys-Factory-guidance-full-v78-arm64-test.apk` was accepted on
2026-10-10 at 19:14:09 UTC. It contains 19,327,392 bytes, SHA-256
`08bd8461b9a72e7d723efded22b53d6079e144fee6caa40b524c7425cb0712df`.
The existing approved D7 test identity signs APK v2/v3. CRC and 16 KiB ZIP alignment pass;
247 retained entries match the approved unsigned APK, with only nine non-ARM64 native
libraries omitted and signing metadata added. The manifest is unchanged by signing. Independent verification reproduced the signature,
payload and alignment checks and fetched the owned Library file through the supported flow;
its bytes are identical to the signed artifact.
ZIP alignment alone does not establish ELF 16 KiB-page compatibility. No installation or
physical acceptance was performed. The host/unsigned receipt above describes the earlier
validation artifact; it does not contradict this later signed delivery.
