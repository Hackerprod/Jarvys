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
