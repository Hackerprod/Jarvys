# Factory gyroscope v82: validation pending

Version 82 / `1.2.75-FACTORY-GYROSCOPE` adds bounded gyroscope capture to the existing
foreground sensors grant. SDK 2/schema 1/protocol 1 and 20 capabilities/42 methods remain.
No new permission, broker, required hardware feature, background capture or network path.

Android TYPE_GYROSCOPE angular velocity uses rad/s, three copied finite x/y/z values,
right-handed natural-device axes and includesGravity:false. No uncalibrated fallback,
pose inference, accuracy promise or physical-hardware claim. The same shared rate ceiling,
5-second/50-sample/16-KiB quotas, session authority, revocation and cleanup failure latch apply.
Preview remains UNAVAILABLE. The existing light/accelerometer contract is preserved.

Independent design review completed; implementation and synthetic regressions are prepared.
SDK42 passed. Final source/purity review, guarded affected host/runtime/core checks, exact lint,
three-APK audit and signed delivery remain pending. Earlier complete app-suite evidence may
only be reused for demonstrably unchanged unrelated source; no fresh full aggregate claimed.
No installation, real sensor capture, ARM64 behavior or WebView physical acceptance claimed.

Backup/restore, other sensors, TTS/voice, remaining F1/F2/F3 and physical acceptance are still
open. UX34 gates, UX44 documentary defaults and UX43-last retain their existing boundaries.
