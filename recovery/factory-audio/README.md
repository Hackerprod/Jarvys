# v70 / 1.2.63-FACTORY-AUDIO

Status: final host validation and independent three-APK audit passed. Existing-D7 ARM64 signing verified and native attachment accepted on 2026-10-10 at 09:38:18 UTC. Physical/device acceptance remains unverified.

Independent design review and current official Android documentation preceded edits. Local
binary playback replaces the blocked strict-TTS proposal; TTS/recognition remain pending.
Only canonical 44-byte-header PCM16 WAV, mono/stereo, 8–48 kHz, at most 30 seconds and a
6 MiB envelope. Opaque untouched document handles, exact latest signed APK/host authentication,
native human one-shot Play, protected durable recovery, authenticated terminal cancellation,
audio focus and lifecycle teardown. No external engine, codec, URI/path API or network addition.

Source tests are synthetic. No actual sound, device, user file or third-party data transmission.
Full fresh aggregate, exact lint multiset baseline 313/300/3/2 and three actual APK audits
passed. Separate existing-D7 ARM64 signing and native attachment acceptance also completed. Detailed host reports
stay outside the repository. Preserve v63–v69, all other family gates, UX34 and UX43-last.

Current contract and official references are in app/APK_FACTORY.md. Native callbacks/IPC are
not hard realtime guarantees; cancellation is requested, not confirmed audibility or stop.

## Validation sequence before final release gates

The first runtime/core check passed 270 core and 70 APK-runtime tests, plus 14 SDK tests.
Host Kotlin compilation passed. The initial combined focused run passed 36 host cases but
failed three newly added staged-cancellation cases (one per API24/28/32): the synthetic
Binder caller UID did not represent the installed host. The fixture now asserts the real
runtime verifier accepts the explicit synthetic host UID, without bypassing authentication.
The failure and original logs remain retained outside the repository.

The strengthened repeat passed 53 host tests and 279 core tests, with zero failures/errors/
skips. All eight JVMs have installed/shutdown offline-guard evidence, zero blocked attempts.
Coverage includes actual blocked fake native preparation across Activity replacement,
executor saturation, real Application startup over open/corrupt journals, control death,
and cancellation while REGISTER returns ACTIVE. These remain synthetic, never actual sound.

Initial fresh lint matches the exact v69 diagnostic multisets: 313/300/3/2, zero additions
or removals and no new suppression. Tests were strengthened during that preliminary lint,
so it is not the final frozen-source gate. A final audio-only nonce/admission cleanup ordering
change and its regression still require the next test run. Full fresh aggregate, final
frozen lint, three binaries, independent audit, signing and native delivery remain pending.

The final nonce/admission ordering delta passed 282 fresh guarded core tests, with zero
failures/errors/skips and zero blocked network attempts. Source is ready for the final freeze;
the aggregate, lint, APK audit and delivery gates remain pending.

The first frozen full aggregate completed 3,104 Full cases with one failure: an existing
skill-contract assertion required the explicit instruction “not a fixed notes application”,
removed during documentation compaction. The instruction is restored within the 16 KiB
budget; no assertion is weakened. Other modules did not run in that failed aggregate.
Original run, source freeze and Full XML are retained as failed evidence. A fresh full
aggregate and final lint/build/audit must use the repaired source.

The restored skill is 16,365 UTF-8 bytes and passes 18 focused unchanged skill-contract
cases. One wrong-working-directory retry failed before any tests and is retained as a setup
failure, not validation; the corrected invocation passed. Final pipeline restarts from the
repaired frozen source.

## Final host validation

This completed run supersedes the historical pending stages above. Source checkpoint
`6869187f7234de305d9cdeab8b1d723e246c8268`, app tree
`d299749fd85e233d23409f844df7e722c33f7841`, all 1,040 frozen inputs unchanged
before/after tests, lint, build and independent audit. Freeze SHA-256:
`2bdf30f3ec389f0f6b9180ea69744cec6967c7d5f183f16ea2e65863ce3d04c3`.

Fresh aggregate: Full 3,104; Play 2,722; APK runtime 70 + 70; core 282 + 282.
All 6,530 cases passed with zero failures/errors/skips. SDK: 14 passed.
All 17 test JVMs have independently verified matching offline-guard launch/install/shutdown
evidence. Sixteen external attempts were blocked (eight per host flavor); runtime/core had
zero blocked attempts. This is not a claim that no network was attempted globally.
Final lint matches exact diagnostic multisets 313/300/3/2, zero additions/removals and no
new suppressions. All three APKs passed actual ZIP/DEX, manifest/permission, resources,
assets, native-library and generated-template audit.

| Unsigned artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Full Debug | 35,064,664 | `b35c1bccf7ee2e4471877cca16e6ea0191c03d896720ebebf7d594d7bb790cc7` |
| Play Debug | 33,452,770 | `2a209deb6163afb9e6d89758209f5be7d21217e33ff8ea939831615fb234189f` |
| Full Release | 27,378,249 | `85a7e1bb7b63e70b93341ecdc9bff16b3df89bd27fa3186629760d49bf5379f7` |

Shared generated template SHA-256:
`7bf7d5351e7029b3586cf4e88814a2bba82b393910cfc5ec5f30c55f10c3943c`.
Historical tests are retained with three exhaustive-profile renames in v70; prior v64
memory-name migrations and the strengthened v66 installer-permission case remain recorded
in their normal history. No failed exploratory evidence is replaced by the passing run.

Native Play uses Android's current output and volume: other people or connected devices
may hear the sound. Playback is one-shot, never automatic resume. `playbackAttempted` does
not establish audibility; `audibilityConfirmed` remains false. Cancellation is requested,
not a hard realtime physical-stop guarantee. Focus, routing, Binder, playback, cleanup and
restart were exercised with synthetic host fixtures only. No actual sound, user media,
installation or physical acceptance was performed. TTS/recognition, other families,
F2/F3 and UX34 gates remain pending; UX43 stays last.

## Signed ARM64 delivery

`Jarvys-Factory-audio-full-v70-arm64-test.apk`: 19,174,971 bytes, SHA-256
`5b96ec210225ef4c7b77b343f438f57cbe989716de5e4d6f071fd7a84887d9be`.
Derived from the audited Full Release hash above. Existing D7 signer verified v2/v3;
ZIP CRC and 16 KiB alignment passed. Exact manifest retained, 239 retained entries byte
identical, only nine non-ARM64 native-library entries omitted (plus signature metadata).
Native attachment send was accepted on 2026-10-10 at 09:38:18 UTC. This establishes neither
download nor installation, device compatibility, audibility or physical acceptance.
Code and host-validation checkpoint: `fcae7f7dea3c819e17769ef325e8bb65902907ff`.
