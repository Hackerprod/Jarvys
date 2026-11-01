# P2 R3 candidate — native schema repair; independent review pending

2026-11-01. Application/test source **`d7dc2b63cb4eb2e25565300b25f32e6a873e7051`**. Full and Play each pass **119 classes / 1,134 tests**, zero failures/errors/skips. Included compatibility cohort:139 cases in12 classes per flavor, not extra tests. **Independent R1/R2 FAIL records remain; no independent P2 PASS or delivery approval.**

Both exports' before/after snapshots and current1,987 app/testing files match canonical binding SHA256 `f8069bd83f4934766d9a1623d5dd444f9e432393acd789d4eb481579ca35939d` (`json.dumps(binding, sort_keys=True, separators=(',', ':'))`, UTF8). [Unchanged119-class list](BOT_PORTABILITY_P2_ACCEPTANCE_CLASSES.json) SHA256 `ae7cb7a254d84b5c7387c3f0f51105480b0cefee2b3038354d2eb9ca290dddd9`; [sanitized per-class results](BOT_PORTABILITY_P2_CANDIDATE_R3_RESULTS.json). Documentation-only commits are distinct from this tested application source.

## R2 findings, preserved and repaired

Independent R2 reran the original five B1/B2 cases successfully, then demonstrated three new native migration failures. Two legal user triggers (`sqliteXreview` and `android_metadata`) were excluded by the schema query, deleted retained fixture rows during Room identity UPDATE and committed version2. A missing message-FTS insert trigger was accepted. These are concrete host-fixture failures; no claim that the user's actual data is corrupted. Matching prior host tests did not override independent FAIL.

The repair is limited to `chatdb/RoomTreeProvenanceSchema.kt`, the existing admission flag, and `chatdb/RoomTreeProvenanceCompatibilityTest.kt`:

- Enumerate **all** sqlite_master objects, including type/name/owner/DDL. No SQL wildcard or name-only exclusion.
- Positively recognize only exact supported native metadata tables: `sqlite_sequence` canonical name/seq shape, and optional `android_metadata` canonical locale TEXT shape with TEXT values. Wrong object types, case/name lookalikes and malformed shapes are not exempt.
- Native implicit indexes require NULL DDL, exact `sqlite_autoindex_<owner>_<positive integer>` identity, actual table ownership and PRAGMA index_list confirmation of UNIQUE/PK origin, uniqueness and nonpartial form. Their owning table still passes full pinned DDL validation. No broad reserved-prefix allowance.
- Message search must be a complete exact view/FTS5/shadow/all-three-trigger family whenever any FTS table/trigger exists. Native `MessageSearchIndex.install` establishes two other legitimate states: wholly absent before installation or exact content view only after caught installation failure, because its cleanup removes only the FTS table/triggers. Those states remain accepted without the upgrade creating or rebuilding search objects. Partial/mixed families fail closed before any upgrade write.
- Existing transaction ordering, prospective full-schema validation before identity, rollback/error conservation, complete shadow definitions and old-row preservation are unchanged. Unknown schemas are not repaired, dropped, rewritten or awarded a new identity.

## Proof and reviewer entry points

The existing14 migration tests remain; seven new JUnit methods cover eight hidden trigger names,12 table/index/view lookalikes, malformed Android metadata, canonical metadata positive, each of nine missing message-search members, five mixed/incompatible states, and genuine complete/absent/view-only states. These mutation subcases are not counted as separate tests. Rejection verifies all75 old entities' typeof/hex rows, complete sqlite_master, identity TEXT/hash, version1 and exact file bytes unchanged. Complete valid native schemas still upgrade with original objects retained.

Both normal gates were OFF for fresh **Full139/Play139** on source `da2e08eaf86922fc94b372c2ede388d35d728c0e`, binding `689bb28604184301692932410f6ea333147c4762fa23effb47206cc5df7759d1`. Only after matching proof were gates enabled for local acceptance. Final Full/Play1,134 repeat the entire139-case cohort on changed source. [Full chronological gate and failures](BOT_PORTABILITY_P2_ROOM_PROVENANCE_GATE.md).

Review repair range `2efcc3a9..d7dc2b63` alongside [R2's B1/B2 and native upgrade map](BOT_PORTABILITY_P2_CANDIDATE_R2.md) and the [original A–I map](BOT_PORTABILITY_P2_CANDIDATE.md). Reexecute the independent hidden-trigger and partial-family cases against fresh exports, not old compiled code. The migration remains a separate additive current-generation Room v1→v2 change from Registry v1→v2; exact v98 schema provenance and generated v2 identity are unchanged.

## Limits

No new root/budget/privilege, global cost/call policy, Chief inheritance, uncertain replay, alternate memory, chat/menu redesign or APK/version/signing change. Delivered v98 remains untouched. No data deletion, legacy import or clean-install workaround; **old-binary downgrade after new writes is unsupported**. Host tests and injected interruptions do not prove physical-device/power-loss or live-provider behavior. All prior red/incomplete evidence is retained. No timeout/assertion weakening; host runner assertions and JVM network/process denial remain enabled, not claimed as a native OS sandbox. Independent P2 approval is still required.
