# UX42 F0b: recoverable identities for new Factory apps

The native identity screen is under Settings → Device → Factory identities. It is a private Activity with no intent filters or new Android permission. No factory tool, JavaScript bridge or project schema accepts secret input. The generated runtime remains unchanged: six capabilities, ten methods, schema/SDK v1 and zero permissions.

## Identity policy and migration

Schema-v1 AndroidKeyStore signing keys remain non-exportable. They are not converted, deleted, rotated or replaced when unavailable. New users may explicitly choose a recoverable identity before the first signing; the ordinary sign approval still discloses creation of a non-exportable key if no identity was prepared.

A recoverable identity uses a new RSA-3072 key and exact self-signed certificate. The backup container has a fixed versioned header authenticated as AES-GCM additional data, PBKDF2-HMAC-SHA256 with 600,000 iterations and a fresh 32-byte salt, and AES-256-GCM with a fresh 12-byte nonce. The bounded encrypted payload includes appId, exact PKCS#8 key/certificate, fingerprint and known version/hash evidence. Unknown algorithms/versions/parameters, extra or truncated data, incorrect passphrases, invalid certificate/key pairs and mismatched existing identities fail closed. This is an application-specific identity format, not a generic PKCS#12 importer.

The native screen obtains a document destination before asking for a passphrase. New local identity creation follows successful encrypted-file write and bounded exact readback. Local private bytes are encrypted under a separate per-app AndroidKeyStore AES key and saved in no-backup storage. Its alias cannot collide with any legacy signing alias. The local wrapper is not a portable backup. Passphrases are not retained, placed in saved state, logged, returned in receipts or sent to the agent. UI and owned buffers are cleared on interruption; complete erasure of JVM/provider/keyboard copies is not guaranteed.

## Restore and continuity

Import verifies the encrypted file, displays appId/certificate/version and requires explicit approval. Existing recoverable records require the same certificate. The maximum of the existing and backup version floors is preserved; contradictory known hashes for the same version are rejected. Missing or interrupted local protection can be repaired only with a matching backup. A new durable reservation pins the identity before creating local protection. Record writes sync and read back the exact bytes; failed/uncertain writes cannot authorize publication.

Every restore marks the latest release unknown and blocks signing. The user must review available APKs and receipts, including releases on other installations, then explicitly declare a floor no lower than the known one. That declaration is disclosed as such; Jarvys cannot prove absence of later releases. Raising the floor clears stale hash/scope evidence. This does not reconstruct missing projects, receipts or app data. Signing retains its exact project-build-receipt checks, strictly increasing versions, per-action approval and signed-version reservation before publication. A shared process lock and whole-record digest prevent stale restore/export/reconciliation approval from overwriting newer signing history.

Signing and record persistence are not one atomic operation. An interruption after cryptographic signing but before a committed identity reservation can retain private signed staging and its receipt while the recorded floor remains older. That staging is outside project output and is not exposed by a recovery/publication API. Failure to verify the reservation prevents publication; a retry requires fresh approval. The monotonic guarantee covers committed reservations and publication-uncertain outputs, not every private signing operation across every crash window. Any future staging-publication path must reconcile that evidence before exposing it.

## Acceptance limits

Host tests use synthetic keys in RAM, temporary encrypted records and independent storage contexts. They cover crypto attacks, interrupted writes, legacy refusal, version conflicts, concurrency, UI denial/cancellation and an independently restored same-certificate APK update. They do not establish behavior of a hardware AndroidKeyStore, real document provider, independent installed device, PackageManager update or generated-app data retention.

A saved/read-back backup is not a successful restore. Physical acceptance still requires a new explicitly approved identity on Android, restoring its backup in an independent installation, verifying the certificate, signing/installing a higher-version update and checking retained app data. A lost passphrase or sole backup can permanently prevent updates. Production hardening and device acceptance remain open; UX34 keeps its separate phase gates and UX43 remains last.
