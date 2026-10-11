# Factory guidance factory-guidance-v86 / presentation

Declared presentation, installed private app preferences; no broker/permission/system-setting change. Preview UNAVAILABLE for all three APIs.
- presentation.get({}) reads requested theme/orientation, defaults system/system.
- presentation.set({theme,orientation}) requires both: theme system|light|dark; orientation system|portrait|landscape.
- presentation.reset({}) requests system/system.

Result theme,orientation,effectiveTheme(light|dark),configurationOrientation(portrait|landscape|undefined),orientationGuaranteed:false,recreationRequested. A successful write additionally preferencesCommitted:true, not proof of physical durability. No-op only when request matches stored pair and immutable Activity bootstrap pair, not observed orientation. A saved choice whose recreation was cancelled may require later explicit reapplication.
Save application state first: recreation loses JS state. Receipt does not guarantee delivery/recreation/appearance. Pause/navigation/close cancel callbacks; saved values may apply next launch. Inspect get after interruption, no blind replay. commit failure requests no recreation but Android memory may already differ; readback is no disk proof. Native UI must not overlap presentation recreation.

Theme applied before Activity creation changes night-mode bits only, preserving font scale/locale/density. Native safe-area/IME container stays sole inset owner. Local CSS must adapt; no automatic arbitrary-content recoloring. Orientation is an OS request and can be ignored by large-screen/OEM/multi-window behavior; responsive scrollable layout remains required. Host tests establish no WebView CSS/rotation/durability/ARM64 result.
