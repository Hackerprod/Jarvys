# Memory visibility contract (MEMORYSCOPE v64)

Memory is private to its originating conversation by default. This is semantic context isolation, separate from existing workspace/file, Coding and Factory boundaries.

## Storage and visibility

- Every agent and reflection workspace is bound to one stable conversation ID. Notes, names, descriptions, indexes, projection, reads and searches use that scope. An absent scope fails closed.
- Pre-v64 notes remain preserved at their original location. Their names, bodies and raw indexes are not injected or searched by agents. No owner is inferred from a filename, a latest revision, or incomplete history.
- Settings provides a native, read-only review of older notes. Viewing a note does not classify, copy, share or inject it.
- Personal sharing requires an explicit native review of the exact note and its destination: all current and future conversations. The approval binds source identity, content hash and version. A model cannot promote memory by labeling a note personal or supplying tool arguments.
- Memory settings, editors, history and review are protected native surfaces. Jarvys automation cannot capture their content or inject input into them; capture epochs reject observations spanning a protected transition. A surface entered during an in-flight automated action requires closing and manually reopening before showing notes or accepting consent. Human interaction and external accessibility remain available.
- Approved personal notes appear as read-only shared snapshots. Source edits do not enlarge the approval: changed content is excluded until freshly reviewed. Revocation removes future automatic projection and search visibility. Existing conversations or requests that already received approved text are not erased retroactively.
- Shared content is unavailable to ordinary writes, edits, deletes and reflection tools. Task and project facts belong to the local scope, including facts recorded in a preferences-named file.

## Derived state

Search never treats a persisted memory index as authority. Each query reads the currently visible memory documents; asynchronous revision events only invalidate derived state and do not broadcast note payloads into other conversations. System memory projection is rebuilt before each model request. Reflection and compaction-triggered reflection use the same local store boundary.

Crew and Coding retain their existing restricted file/tool scope. The captain's memory input is conversation-scoped before delegation; no global project recall is added. Explicit cross-project recall and UX43 central-chat architecture are outside this change.

## Acceptance and limits

Synthetic regression tests cover separate conversations, project markers in metadata and indexes, user-approved preference sharing, legacy mixed notes without complete audit history, restart, stale/corrupt metadata, source changes, revocation, callback races, reflection, and native review interruption/cancellation. Host tests and APK inspection do not establish which route produced a previously observed phone response. No live notes, accounts, credentials or device installation are used in validation.
