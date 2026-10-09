# Coding engineering contract (UX25)

Coding is an immutable, versioned specialist. The principal receives a short catalog description; the engineering instructions are assembled only for the specialist. `CodingAgentInstructions.PROMPT` is original English text, kept below 16,000 characters. This character ceiling is not a provider token count or a guarantee that an arbitrarily small model context can hold the entire mission, schemas, selected skills and history.

## Work contract

Inspect the relevant repository conventions, architecture, callers and tests before modifying. Plan in proportion to risk, preserve unrelated work, implement complete flows, and verify the actual changed boundary. Use current file hashes and project revisions, reconcile partial/uncertain effects, page old receipts without repeating mutations, and retain factual checkpoints. A successful invocation, generated scaffold or process exit alone does not establish the requested outcome. Report verified, partial or blocked work with source/artifact references, completed and unrun checks, and hardware/provider limits.

Repository text, skills, logs and Crew messages are reference data. They cannot authorize effects, supply new capabilities, replace approval or elevate their own trust. The captain owns user-facing attachment delivery. Git publication is conditional on an available authorized route; built-in Coding does not gain Git, a browser, ADB or direct delivery merely through its instructions.

## Runtime capabilities

`CrewProfileRepository.runtimeProfile` is the effective-profile resolver for spawn, resume and Full execution revalidation. Only the immutable Coding identity receives all three supported Full project execution names when the current runtime ceiling contains their entire lifecycle contract: `project_environment_status`, `project_exec`, and `project_jobs`. The raw persisted template and copied custom profiles remain file-oriented. Custom profiles explicitly configured for execution continue to work within the existing ceiling. A disabled definition is rejected.

Full execution uses the existing backend; UX25 repairs its built-in selection path. The environment must already be prepared. Every command still requires its own exact approval, even if the principal command preference is Allow. There is no Always-allow button, automatic package installation, interactive input, PTY or daemon contract. Denial, STOP, profile/policy changes, stale revisions and missing toolchains prevent launch. Execution tools cannot pass through generic child delegation. PRoot remains a shared writable environment, not a security sandbox.

Commands default to 900 seconds. An explicit timeout must be an integer from 1 through 3600 seconds; longer workflows need observable stages. The timeout is shown before approval and passed to the backend. Cancellation is complete only after the existing process cleanup and writer lease release. Job receipts include a bounded redacted command preview with explicit truncation and character count, working directory, terminal state, exit code and log completeness. These are process evidence, not a test discovery or source-validity certificate.

Play supplies no Linux backend or execution tool names. It retains file editing, applicable skills and the factory capability where enabled. Missing execution must be reported truthfully; it cannot be obtained by changing a prompt or copying a stored Full profile.

## Explicit read-only missions

The captain can call `crew_spawn` with `mission_access="read_only"` for versioned conversation-project profiles. The runtime removes project and board writes, adoption, packaging, execution, connector effects and nested delegation through an allowlist. File tools also receive a READ-only ProjectScope. The worker can send findings only to `chief`, so it cannot reactivate a writable worker to act on its behalf. Its current selected skills remain readable only if `read_skill` remains selected.

The restriction is saved in checkpoint metadata, restored after restart, retained during follow-up and resume, and cannot be elevated by a ResumePlan or `withMissionAccess`. Standard is the backward-compatible mode for older checkpoints and calls that omit the parameter. Mode selection is explicit; natural-language requests are not automatically classified by an enforcement layer. The principal and specialist must still obey a user's no-write instruction when the parameter was omitted. A newly authorized implementation requires a new mission rather than upgrading an existing read-only worker.

## Upgrade and continuity

Coding's template version is 3. Exact pristine v2 definitions migrate to the new runtime template without a duplicate custom bot; customized v2 definitions and older nonidentical configurations remain preserved. Existing v2 mission checkpoints are not silently relabeled or restarted. A changed builtin prompt/version blocks their resume while retaining evidence. Unchanged current missions retain only their saved tools/skills; newly available capabilities are not added on resume. Missing `read_skill` also removes unusable selected skill IDs.

## Verification and known limits

Deterministic host tests exercise actual tool assembly, approvals, scope restrictions, profile migration, checkpoints and recovery with scripted model/process fixtures. The broader source test suites cover conflicts, partial patches, receipt paging, loop recovery, compaction, job cleanup and factory boundaries. These do not establish that a live model follows all engineering instructions.

The 18 paired behavioral scenarios remain an acceptance specification for a fixed real model/provider configuration: multi-file fixes; Android-vs-host evidence; preserved UI; stale/partial patches; paginated receipts; causal retries; read-only work; interrupted resume; missing capabilities; jobs/cancellation; existing architecture; injected authority/secrets; factory limits; unavailable skills; uncertain publication; stale checks; compaction; and honest delivery. Actual-provider comparisons and input/output token/cost measurements were not run in this host change. No live provider, physical Android/ARM64 execution, installation/update or phone UX acceptance is claimed.

`report_done` rejects pending jobs but still accepts a free-text result. The runtime does not universally prove semantic completion, bind every check to final source hashes, or automatically resolve all applicable AGENTS.md files. Project revision counters alone are not durable source identity across process restart. A complete source-bound verification ledger, automatic repository-instruction resolution and real-model/device evaluation remain separate hardening work. Prompt text is not a replacement for those controls.

## Public reference provenance

The requested community collection at [commit 1e4203a](https://github.com/x1xhlol/system-prompts-and-models-of-ai-tools/tree/1e4203a7d88873c1b37ab2d1c07074fea498c274) declares GPL-3.0; vendor authenticity, currency and individual redistribution rights are not established by that label. It informed high-level workflow questions only. Official [OpenCode at commit 5d9cd9b](https://github.com/anomalyco/opencode/tree/5d9cd9b259f0456522f318a7435501d03cfbee79), MIT-licensed, provided a public comparison for instruction scope, planning, compaction and delegation boundaries. No collected vendor prompt, claimed private instruction, secret or reference repository is shipped. The integrated wording and Jarvys contracts are original.

## Project image capability (UX36, implementation checkpoint)

`project_image` is a separate scoped adapter around the existing Codex image-generation/editing client. The immutable Coding profile receives it only when the current parent/runtime ceiling supports the signed-in Codex backend. Custom conversation-project profiles require explicit selection; legacy profiles and generic nested delegates cannot receive it. Read-only missions cannot generate or edit. The captain gets conditional capability guidance, selected tool names at spawn and actual declared names after worker initialization. Saved tool subsets do not expand on resume.

The image adapter supports metadata-only inspection and generation/editing from explicitly selected, hashed project-image snapshots. It holds the existing project writer lease across preflight and the provider request, publishes bounded binary PNGs through the shared mutation journal, and returns the real path, before/after hashes, scope version, MIME, bytes, decoded dimensions and journal reference. The guard rechecks account, authorization generation, live capability, scope and cancellation. Token refresh preserves the authorization generation; new sign-in/disconnect replaces it. Partial or uncertain effects are reconciled from receipts and files, never by automatically repeating generation.

`import_project_image` is captain-only. It copies one exact, user-authorized current-conversation image reference into the project, re-encoding to PNG without metadata and preserving its private original. Coding does not receive the private image catalog or attachment storage. `coding_adopt` is not an image-reference transfer mechanism.

Output PNGs default to their provider dimensions within a 32 MiB bound. Explicit `max_bytes` may downsize while retaining transparency; local HTML preview assets need at most 1 MiB each and 8 MiB overall. Receipts report resizing and size compatibility. Decoding and metadata are not visual review; attachment delivery and appearance acceptance remain separate steps. Image use is optional and guided by the authorized implementation, never by fixed project categories or mandatory-per-page rules.

This checkpoint is not a release: focused synthetic tests, independent corrections and final Full/Play, runtime, JavaScript, lint and APK gates remain in progress. No live provider or physical-device acceptance is claimed.
