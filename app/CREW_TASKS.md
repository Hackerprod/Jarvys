# Crew task presentation

UX27 separates a mission's display title from its execution instructions.

## Agent-generated titles

The captain supplies optional `task_title` on the existing `crew_spawn` call, in the same model turn as delegation. It describes the complete user objective in the user's language, typically in three to six words. `name` remains the bot's name; `mission` remains its complete, self-contained execution instructions.

There is no extra title-generation model request, keyword detector, role-to-title map, or automatic semantic summarizer in the application. The title policy only checks presentation: valid Unicode, visible non-control text, normalized spaces, and at most 60 Unicode code points. Punctuation is plain text. The application does not claim that syntactic validation establishes a title's semantic quality.

The first valid title is accepted atomically before publishing that spawn's first mission snapshot. Per-mission publication is serialized by a nonblocking drainer, so an older fallback callback cannot overwrite a later accepted title. Further bots do not replace an accepted title. A mission initially lacking a valid title may accept the first valid title from a later spawn. Missing, invalid, or oversized display metadata does not block the worker or truncate the instructions. The UI uses a localized neutral “Untitled task” label until a valid title exists.

## Storage and recovery

Crew snapshot schema 2 stores `title`, `titleSource`, and `originalInstructions` separately. `titleSource` is `agent`, `legacy`, or `fallback`. The full original request, bot mission, messages, results, and execution transcript remain separate from the title. Presentation is never substituted into the worker's prompt.

The reader accepts schemas 1 and 2. For schema 1, the entire prior title is preserved as the original instructions. An already-short valid legacy label may be displayed; a long or invalid legacy label uses neutral fallback. Reading history does not rewrite the append-only ledger. Later normal snapshots carry the additive schema 2 fields.

Interruption, restoration, and new snapshots preserve the title metadata and instructions. Versioned bot checkpoints include mission presentation metadata so recovery without a ledger can retain it. When a validated checkpoint is newer than a stale fallback ledger snapshot, its accepted presentation can restore the missing title without replacing an existing accepted ledger title. Older checkpoints contain only the bot's instructions: those remain intact, but recovery leaves the unknown original user request empty rather than inventing it from the bot's mission. Viewing history never resumes work automatically.

## Presentation

Cards, completed mission summaries, detail headers, and result notifications use the same bounded title. The mission detail prioritizes the title and current status. Global Crew mode and Coding settings remain available through task options, outside the always-visible mission header. Full instructions are opened explicitly in a scrollable, selectable reading surface; the bot detail also retains its complete instructions there.

Activity uses the existing ordered message history. Status events receive a compact row, while substantive messages and results keep their content and reference targets. Sender identity is shown once. Tabs, bot navigation, references, and authorized stop/follow-up callbacks retain their existing scope. Main-chat overlay insets are not added to the mission's independently laid-out activity area.

Colors come from the existing Jarvys theme. Interactive controls retain accessible labels and minimum touch targets; text and full instructions remain available at increased font sizes. The model-generated title can be visually ellipsized in bounded headings without changing any stored instructions.

## Validation boundaries

Deterministic tests cover title normalization, legacy/schema 2 persistence, first-title stability, checkpoint recovery, and the existing model/tool flow. Native host Compose captures cover narrow widths, light/dark themes, English/Spanish, and increased text sizes. They do not establish live-model naming quality, Android device performance, physical installation/update, keyboard behavior, or TalkBack behavior. No permissions or Coding capability grants are introduced by task presentation.
