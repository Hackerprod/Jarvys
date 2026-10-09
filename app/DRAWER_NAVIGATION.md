# Drawer and archived chats (UX17/UX18)

Version 37 reorganizes navigation without changing conversation storage or scheduling behavior.

- The drawer title is Jarvys, with a 48dp search button on the right. Search expands only on request. The query and list position survive drawer dismissal and saved UI-state restoration. Clear removes the query; Cancel/Back removes the query and closes search. Closing the drawer hides the keyboard without discarding the query.
- Menu order is New chat, Bots, Scheduled tasks. Drawer navigation is guarded while it closes; repeated taps and drawer gestures cannot leave the destination covered by a cancelled close animation. The existing callbacks still create a new conversation or open the existing Bots grid. No existing conversation is erased by New chat.
- Pinned appears only when visible pinned chats exist. Pinned and current/ recent headings have no icons. Active/history rows are deduplicated by session, and pinned rows do not also appear in the current section. Chat rows retain titles and their action menu.
- Archived chats are absent from the drawer. Settings shows the archive entry only while the current history contains an archived conversation. The archive screen can open, restore, rename or delete records using the existing session-based action handler and shared confirmation dialogs. The final restore/deletion leaves a truthful empty screen; Back returns to Settings without a phantom entry. Managed system chats retain deletion protection.
- The new Scheduled tasks route is an informational placeholder. Its text explicitly says the new view is unavailable. The existing task runtime, existing schedules and conditional Settings task manager are unchanged. This stage creates no schedule or automation.
- New routes use the standard top bar and Back stack. The NavHost stays at one composition location, preserving its saveable state when switching between chat and other screens. Opening Settings, archives or the placeholder does not reset the composer, session or file-transfer ViewModel. The existing saved-session-before-stale-intent restoration order is preserved.

## Validation boundaries

Compose/Robolectric tests exercise English/Spanish, both themes, 320–360dp widths, 200% fonts, search state, exact action targets, conditional archive entry, delete confirmation and native tap flows. Native host captures allow layout review but cannot certify a physical keyboard, gesture navigation, device process death, TalkBack or phone rendering. Device acceptance remains part of the final consolidated delivery.

The native host navigation test preserves the same reading region and message anchors. It observes a one-time 42px shift on the first return to the transcript, followed by exact stability on a second round trip. Saved index/offset normalization while the unchanged asynchronous Markdown body is re-created is the likely explanation, inferred from measured row sizes rather than directly proven. Exact pixel-scroll preservation is not claimed. The final test requires the identical set of fully unobscured message anchors, center-message visibility, unchanged draft/session/data, and no cumulative drift. Physical-device behavior remains to be checked; no separate scroll framework was added.

The app package and permissions remain unchanged. Each completed stage produces verified unsigned development APKs for signing with the existing approved identity and delivery before moving to the next stage; signing is not part of the source build.

## UX30: drawer density and empty sections (v48)

The three primary navigation rows share a zero-gap group with a minimum 48dp click target and 8dp vertical content padding; labels can wrap and grow at large font scales. Chat group spacing, fixed header/search and profile/settings footer remain unchanged.

Pinned keeps its section heading and full pin/unpin state, but drawer rows omit the repeated Pinned marker. Only drawer overflow icons rotate the existing Lucide glyph vertically. Shared archived rows retain their existing marker and horizontal icon by default. Rename validation, long-press menus, archive/restore, managed-chat protection and explicit delete confirmation are unchanged.

Current chats, like Pinned, renders its heading only while it has visible rows. Normal empty sections have no placeholder. An explicit nonblank search with zero total matches still shows the localized no-results feedback; matches among pinned or active chats prevent false empty feedback. Blank searches, clearing, cancellation, Back and saved-state restoration retain their previous behavior.

The UX30 native capture matrix uses 320dp, English/Spanish, light/dark themes and 100/200% fonts, with navigation, three pinned rows matching the supplied layout, popup actions, empty drawer and empty search states. Tests verify actual minimum targets, wrapping, section semantics, exact action targets and shared archive defaults. Host validation is not physical-device or TalkBack acceptance.
