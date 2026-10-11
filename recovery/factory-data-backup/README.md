# Factory selected SQLite data backup v83: implementation checkpoint

This is an in-progress source recovery checkpoint, not release approval. Baseline is v82
`fff911becd9d1a49e4438008a611df90459b9d69`. Version 83 is
`1.2.76-FACTORY-DATA-BACKUP`. Tests, lint, APK review and delivery are not yet complete.

## Bounded contract

Generated applications explicitly declare an initially empty table allowlist. Both existing
`database` and `documents` capabilities are required; no new Android permission or capability
is introduced. Export/import selects at most eight allowed SQLite tables, with a canonical,
strictly validated, versioned plaintext archive limited to 1 MiB. Package/certificate/schema
labels establish compatibility, not cryptographic provenance of imported data.

The existing document-handle boundary supplies imported bytes. The pinned Jarvys native host
independently verifies installed source and signed receipt policy, displays all outgoing/incoming values and replacement target row counts/deletion impact,
requires explicit human acknowledgement and discloses potential private content and provider
cloud synchronization. Arbitrary strings cannot be guaranteed secret-free. The default-empty
allowlist is not a secret detector; each request supplies its exact nonempty table set. Signing-identity backup is a separate feature.

Export uses a native document picker only after review; JavaScript receives neither paths nor
URIs. Provider close/request success does not establish durable storage or cloud delivery.
Import supports explicit `insertOnly` and destructive `replaceSelectedTables` modes. Each
selected-table snapshot and each restore uses one SQLite transaction. Restore rechecks the
reviewed schema and selected-table preimage within its transaction; unrelated tables remain
untouched. No transaction remains open across human review or provider I/O. Validation,
conflict and known precommit failure roll back together; uncertain commit is not success.
No permissions, signing keys, nonexportable keys or unrelated settings are restored.

The five-minute operation, bounded transfers, cancellation, same-operation callback proof,
scoped host review handoff and durable recovery admission retain fail-closed lifecycle rules.
Provider cleanup that blocks retains its admission; a timeout is not a forced-stop guarantee.
Cancellation or reply loss during or after commit may mean data committed. No automatic
retry or replay follows process death or uncertain completion.

## Validation status

Independent design review required changes before implementation. Source-purity review of
the exact argument-only validation perimeter approved proof-pin changes before they were
applied. The existing 20-capability exhaustive subset proof remains required. SDK tests and
preliminary guarded tests are being run; failures and corrections will be retained. This
checkpoint does not claim final passing counts, lint equivalence, audited APKs or delivery.

Physical Android Binder/picker/provider behavior, installation/update, cloud durability and
user-data recovery are not established by host tests. Preferences, broader merge/migration,
remaining Factory families and existing network/TTS gates remain open. UX43 remains last.
