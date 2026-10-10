# Factory TTS design gate: still pending

Reviewed 2026-10-10, after the delivered v69 photo slice. This is a documentation-only
finding, not a TTS implementation, version bump, APK build or physical test.

## Public API constraint

The proposed strict profile required native review of the exact engine/voice/text,
no unapproved engine fallback, installed voices declaring no network requirement,
and a bounded foreground utterance with durable automation protection. Android's
public `TextToSpeech` API does not expose the currently bound engine or a constructor
that disables fallback. `getDefaultEngine()` identifies the configured default,
not the engine actually handling a request. The Android 36 public SDK was inspected
independently; the current-engine getter is absent.

AOSP Android 11 uses the supplied context's service-binding path. From Android 12,
the public constructor uses the system TTS manager instead, bypassing a proposed
`ContextWrapper.bindService` restriction. A legacy-only implementation would not
provide the requested functionality on modern Android and is not being shipped.

Explicitly approving an enumerated set of candidate engines would describe the
fallback risk more honestly, but public engine/package/voice observations are not an
atomic recipient allowlist. They cannot establish the actual binding across engine
replacement, enablement, default changes or reconnection. Voice names are not an
authenticated engine identity. In the examined AOSP implementation, a remote-call
failure can start reconnection and returns an error for that call; this is **not**
evidence of automatic resubmission of that same text. Later calls may use a changed
binding, and the initial initialization listener has already been cleared.

This blocks the proposed strict Jarvys profile, not Android TTS in general. A future
TTS design must resolve or explicitly review its actual recipient and consent
contract on supported modern Android without hidden APIs, reflection or misleading
engine-pinning claims. A voice's declared offline flag is not proof that an external
engine cannot transmit data. Initialization/voice loading must also be reviewed;
no silent downloads or network-voice fallback are approved by this finding.

## Next bounded audio slice

Local binary PCM/WAV playback is the next proposed useful slice, using bounded opaque
document handles and native Play consent, without an external synthesis engine.
It still needs its own reviewed format/size/duration limits, exact caller identity,
foreground audio focus, cancellation/teardown, durable recovery, synthetic tests and
APK audit. It is not implemented by this document. TTS, microphone recognition and
cloud voice remain pending; F2/F3, UX34's later gates and UX43-last are unchanged.

No user text was sent to an engine, no engine was initialized, no speech was emitted,
and no device operation was performed. No new test, lint or APK success is claimed.

## Sources

- [Android TextToSpeech public API](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [Voice network requirement metadata](https://developer.android.com/reference/android/speech/tts/Voice)
- [Audio focus, including foreground requirements](https://developer.android.com/media/optimize/audio-focus)
- [Current AOSP TextToSpeech implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/tts/TextToSpeech.java): public constructor, `initTts`, `runAction`, `dispatchOnInit`, `SystemConnection`.
- [AOSP Android 11 source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/core/java/android/speech/tts/TextToSpeech.java)
- [AOSP Android 12 source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/core/java/android/speech/tts/TextToSpeech.java)
