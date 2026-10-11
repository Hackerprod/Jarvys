# Bounded Factory source capacity

Factory website capacity is resolved from the signed `resourceBudgets.packaging` declaration. Missing fields preserve the existing defaults:

| Field | Default | Implementation maximum |
| --- | ---: | ---: |
| `sourceFiles` | 128 | 512 |
| `singleSourceBytes` | 1 MiB | 16 MiB |
| `totalSourceBytes` | 8 MiB | 32 MiB |
| `apkBytes` | 32 MiB | 32 MiB |

These are website-source allowances, not promises that every maximum fits simultaneously. Icon and adaptive-foreground source inputs keep their independent 1 MiB bound. The complete archive must still fit 512 ZIP entries, 16 MiB per ZIP entry, 32 MiB expanded payload and 32 MiB APK bytes. Runtime DEX/resources, fixed SDK/UI files, configuration, provenance, alignment and ZIP directory overhead consume the complete-archive envelope. Signing overhead must fit the declared and technical APK byte bounds too.

The transformer calculates the exact complete STORED ZIP size and entry count before cloning replacement assets or allocating its output buffer. Its verifier checks the aggregate expanded bound before inflating or copying the next entry. Increasing website declarations cannot raise these independent limits. Work beyond 32 MiB requires a file-backed whole-build, verifier and signing redesign; increasing byte-array or integer ceilings is not that redesign.

## Source and preview enforcement

A metadata-only traversal checks each regular file against the resolved source declaration and freezes its size before materialization. The subsequent no-follow reads reject growth, shrinking or content changes observed during hashing. Final publication re-hashes website files with their declared per-source bounds; icon inputs retain their separate bound. Project boundaries, cancellation, writer leases, exclusive publication, exact source hashes and recovery receipts remain in force.

Project paths retain the non-hidden, relative, traversal-free 240-character grammar. ZIP names retain their existing independent restricted grammar and 240-character limit. Directory visits are bounded by `sourceFiles * 120`, derived from the maximum path component count; empty directories consume this budget. This replaces the unrelated 256-entry and 12-level restrictions while keeping traversal finite.

Preview extraction uses the receipt's resolved packaging declaration, which is checked against the packaged runtime configuration. Snapshot copies enforce those same source bounds, including before allocation. Fixed runtime UI assets retain their independent pinned size/hash verification. Preview evidence remains an observation of the shared runtime, not installed-app validation.

## Process ownership and heap admission

One Factory service archive operation may own buffers at a time. Build, sign, inspection, harness and installation preparation acquire a non-waiting process-local lease. Contention returns an explicit resource-busy failure; cancellation and exceptional returns release ownership. Installation status/cancel remain available without this lease. Signing keeps ownership while an explicit Ask decision is pending because its input artifact is still resident; default Allow always introduces no approval UI or wait.

Admission uses actual planned source sizes and actual archive/expanded sizes, not every declared maximum. The estimates are overflow-checked and compared with `Runtime.maxMemory - (totalMemory - freeMemory)`:

- Bounded read: 16 MiB headroom + 3 × archive bytes
- Build: 16 MiB headroom + 8 × website bytes + 2 × template archive bytes + 5 × template expanded bytes
- Artifact verification/signing/preview: 16 MiB headroom + 4 × archive bytes + 3 × expanded bytes

When a positive plan exceeds measured available heap, admission issues at most one garbage-collection hint and remeasures the actual heap once. The runtime can ignore the hint or introduce latency; the same strict refusal remains if space is insufficient. There is no allocation retry loop or out-of-memory-as-success behavior.

The multipliers conservatively cover retained input, defensive copies, growing read buffers, complete output arrays and expanded verification maps; headroom covers icons, metadata and small objects. Some already-resident bytes are deliberately counted again. This is advisory admission, not a heap reservation or a guarantee against Android OOM, fragmentation, other subsystems, storage exhaustion or OS limits. A valid declared budget can still be refused for available resources.

## Verification boundary

Targeted direct Java tests cover exact ZIP composition, large entries, compressed expanded-size rejection, heap estimates, overflow, contention and release. Direct Kotlin compilation covers the service, specification, source traversal and preview changes. Source/preview probes exercise actual project/NIO/cancellation code with a synthetic API-level constant and repository UI-asset fixture. Full Gradle, packaged-template, APK generation/signing and device/WebView validation are separate release checks; this change does not claim them.
