# v70 / 1.2.63-FACTORY-AUDIO

Status: implementation and synthetic tests in progress. No completed validation or APK delivery claim.

Independent design review and current official Android documentation preceded edits. Local
binary playback replaces the blocked strict-TTS proposal; TTS/recognition remain pending.
Only canonical 44-byte-header PCM16 WAV, mono/stereo, 8–48 kHz, at most 30 seconds and a
6 MiB envelope. Opaque untouched document handles, exact latest signed APK/host authentication,
native human one-shot Play, protected durable recovery, authenticated terminal cancellation,
audio focus and lifecycle teardown. No external engine, codec, URI/path API or network addition.

Source tests are synthetic. No actual sound, device, user file or third-party data transmission.
Full fresh aggregate, exact lint multiset baseline 313/300/3/2, three actual APK audits and
separate existing-D7 ARM64 signing/native delivery remain release gates. Detailed host reports
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
