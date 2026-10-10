# Factory guidance factory-guidance-v78 / presentation

Use with the always-loaded Factory core; this reference grants no tools or approvals.

## Presentation preferences (v77 API, unchanged)

`presentation` is the eighteenth capability. SDK 2 and schema/protocol 1 remain unchanged.
It adds three Promise methods on `Jarvys.presentation`, available only in installed generated
apps. These are private per-app preferences: no broker, Android permissions, system settings,
host preference changes or arbitrary manifest attributes. Preview rejects all three methods
with `UNAVAILABLE`; it never changes Jarvys's theme or orientation.

- `get({})` reads the requested pair, defaulting to `{theme:"system",orientation:"system"}`.
- `set({theme,orientation})` requires exactly both keys. Theme is `system`, `light` or `dark`;
  orientation is `system`, `portrait` or `landscape`. Both values are validated before saving.
- `reset({})` requests the same pair as `set({theme:"system",orientation:"system"})`.

Results expose requested `theme` and `orientation`, observed `effectiveTheme` (`light`/`dark`)
and `configurationOrientation` (`portrait`/`landscape`/`undefined`), plus
`orientationGuaranteed:false` and `recreationRequested`. A successful write additionally reports
`preferencesCommitted:true`; this is omitted for reads and no-write results and is not proof
of physical durability. An unchanged pair is a no-op only when it equals both stored choices
and the immutable pair applied at this Activity's bootstrap. Observed orientation does not
control that comparison because Android can ignore the request. A saved choice whose queued
recreation was canceled can require recreation on a later explicit request for the same pair.

Save application state before changing presentation: Activity recreation discards current JS
state. A receipt does not guarantee delivery to JavaScript, recreation or the requested visual
result. Queued callbacks are canceled on navigation, pause and close; saved choices may instead
apply on the next launch. Inspect `get` after interruption rather than blindly replaying.
SharedPreferences `commit()` failure reports an error and requests no recreation, but Android
may already have changed in-memory preferences despite failed persistence. A subsequent read
must not be interpreted as proof of a successful disk write. Pending native UI must not overlap
another presentation callback/recreation.

The native light/dark platform theme is chosen before Activity creation. The context override
changes only the configuration's night-mode bits, preserving font scale, locale, density and
other user settings. The existing UX35 safe-area container remains the sole system-bar/cutout/IME
inset owner, including after recreation. Authored CSS must adapt to light/dark colors;
`prefers-color-scheme` behavior depends on the installed WebView provider. There is no forced
recoloring of arbitrary web content. See Android's [WebView dark-theme guidance](https://developer.android.com/develop/ui/views/layout/webapps/dark-theme).

Orientation is a request, not a guarantee. The current template still targets API 35. Android's
[target-36 large-screen behavior](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability)
generally ignores orientation restrictions at smallest width >=600dp; OEM policy, multi-window
and other device conditions can also limit requests. Keep responsive, scrollable layouts and
verify actual device behavior. Host tests cannot establish WebView CSS behavior, visible
rotation, physical preference durability or ARM64 acceptance.

Adaptive icons/resources, TTS/voice, sensors, biometrics, backup, local UI and F2/F3 remain
pending. This bounded API does not complete those roadmaps or relax their acceptance gates.
