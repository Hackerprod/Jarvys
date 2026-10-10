# Factory guidance maintenance v78

Version 78 / `1.2.71-FACTORY-GUIDANCE` is in implementation and validation.
It adds no Factory runtime capability: v77 remains 18 capabilities, 36 methods, SDK 2/schema 1.
No completed host gate, APK audit, signed delivery or physical acceptance is claimed yet.

## Contract

The reserved Coding-only Factory skill previously occupied 16,377 of 16,384 bytes and included
an inaccessible reference to a repository document. The new core remains under the unchanged
16 KiB skill limit and has an additional 8 KiB UTF-8 bound. It is appended to the eligible
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
