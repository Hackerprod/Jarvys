# Factory configurable autonomy, v84 development checkpoint

Status: reviewed implementation checkpoint; development continues in a larger batch under the user-requested single final validation workflow. No APK from this revision has been signed or delivered. The earlier preferences-backup checkpoint was paused and is not release evidence for these changes.

## Implemented authority model

`FactoryOperationPolicy` is one persisted authority source for 32 operations (the original 20 plus text sharing/export, HTTP requests/probes, ICMP ping, notification channel/permission/show/cancel, and speech voices/speak/stop): document open/create, photo pick/capture, audio play, browser open, contact pick, file sharing, maps, dialer, email/SMS editors, calendar editor, SQLite and preference backup export/import, signing, installation, and clipboard writing. The initial default is `ALLOW_ALWAYS`; the user can select `ASK`, `DENY`, or an inherited default for each authenticated app/certificate and operation. First signing uses a project-bound provisional identity and durably binds existing choices to the resulting certificate. Changing relevant authority invalidates active grants before later checked effects; old grants do not revive when a preference changes back.

`ALLOW_ALWAYS` removes Jarvys approval cards, mandatory review checkboxes, and secondary Use/Play approvals from these flows. `ASK` presents one meaningful review. `DENY` stops broker resource creation. Caller-side bounded read-only request/snapshot preparation can occur before the host authenticates and evaluates the request. The authority editor remains a protected user-controlled surface, preventing an executing agent from silently overriding the user's DENY.

Coding execution uses its saved Coding setting, otherwise an explicitly saved legacy Linux choice, otherwise ALLOW_ALWAYS. This does not silently change other connectors or the main Linux agent's default. STOP, project/mission ownership, writer leases, and command launch checks remain.

## Gates that remain, and why

- Android document/photo/contact selection, source install grants, applicable PackageInstaller UI, and genuine biometric UI remain Android requirements. Jarvys does not synthesize or bypass those grants.
- Package, UID, certificate, signing-key access, exact artifact and version continuity, document authorities, scoped data formats, and transaction integrity remain checked.
- The current source batch replaces latest-only caller trust with a bounded, durable authenticated signed-artifact history: 64 recent records plus the installed artifact pin. Each record binds the exact APK identity, version and capability scope. This new implementation still awaits batch execution validation.
- The runtime is a packaged, precompiled DEX. It exposes the implemented SDK surface; it does not compile arbitrary Kotlin/Java or provide every Android API. Existing generated APKs retain their packaged runtime; rebuilding adopts runtime-local additions such as the clipboard broker. Exact legacy manifest/receipt verification remains supported. Existing resource quotas are retained rather than replaced with unsafe unbounded buffers.
- Local storage and haptics are not separate entries in the 32-operation host policy. Text sharing and export now use the shared policy. This is not a claim that every SDK method has a new individual setting.
- Historical unknown outcomes remain unknown. Recovery can retire local state without a human-only global automation lock, but cannot claim an external Android screen closed or replay an uncertain write/commit.
- An issued URI grant or an already-open file descriptor cannot be recalled retroactively. New file-sharing opens and metadata access recheck current authority; existing descriptors may remain usable until their owner closes them.
- Backup protocol v2 checks live host authorization through its supported write/transaction-commit boundaries. Authenticated legacy v1 remains supported with its last host authorization boundary; it cannot halt an already authorized effect after handoff. Neither protocol rolls back an irreversible successful commit because later policy changes.
- Preview remains isolated and simulated. A simulated receipt, including clipboard `performed=false`, does not establish a live native effect.

## Verification so far

- First Full and Play production Kotlin/resource compilation passed on 2026-10-11, before subsequent review fixes. This is compile evidence, not final release validation.
- JavaScript SDK: 48 fresh tests passed, no failures or omissions.
- Independent review covered persisted authority failure, certificate migration, stale first-sign ownership, and callback revocation. Additional runtime/transaction and lifecycle review is being incorporated.
- First focused attempt is preserved as failed: app test compilation exposed stale test calls; 1,418 runtime tests ran with 55 failures and no errors/skips. APK-runtime passed 92 tests. Diagnosed fixes address two synthetic UID fixture assignments, a clipboard-query expectation, and immediate cancellation snapshot disposal. The new permission editor also rejects full/partial overlay gestures. Those fixes passed fresh verification in the second focused attempt.
- Second focused attempt completed 2,626 fresh tests: runtime core 1,371 and APK-runtime 92 passed; Full host ran 1,163 with 15 failures, no errors or omissions. Twelve failures are stale cross-family startup/recovery expectations; three API variants expose one browser-default identity-drift fallback bug. The exact-recipient correction and owner-local fixture updates are now independently reviewed; the next gate is pending. Four guarded test workers completed with no blocked network attempts.
- Corrected Full host scope completed 1,209 tests with no failures/errors/skips; the final-source JavaScript SDK rerun passed 48 tests. Play production and unit-test compilation completed, then its newly started test execution was deliberately interrupted when the user requested batch development with one final global validation. Play tests are incomplete.
- Lint comparison, generated DEX/APK audit and delivery were not started for this checkpoint. They are deferred to the final batch validation, rather than repeated after each small implementation. Physical-device acceptance remains unverified.

Private execution evidence stays outside Git. This document records product scope and honest validation status, not private tool logs or credentials.

## Native-services batch in progress

The packaged runtime source now includes HTTP(S) requests, HTTP header probes, real ICMP through fixed Android ping/ping6 executables, own-app notifications and normal platform TTS. ICMP never falls back to HTTP; missing utilities, unsupported options and platform denials remain explicit. TTS may use engine networking and fallback. Notification acceptance does not prove display or reading. New capabilities are unavailable in preview. The 23-capability / 59-method catalog and 32-operation policy describe implemented source, not device acceptance.

New focused fake-process/connection/engine, authorization and lifecycle tests have been authored. Compilation and small necessary checks precede one final global validation; no new APK has been audited, signed or delivered. Cross-host ASK transition protection is implemented with live peer epochs and action leases, without restoring the old host-coexistence ban; host compilation/tests remain pending. OS gestures already in flight across a fresh peer installation/restart remain a disclosed boundary, not absolute cross-process isolation.

Targeted native batch check: core/template production Java compilation passed. The 199-case new-runtime selection completed with 17 failures: 14 lacked the Android 13 Robolectric artifact and three used a launcher fixture that did not actually return a foreign component. The fixture is corrected and the official checksum-verified artifact restored; rerun remains pending. All other selected classes passed. SDK54 passed. Failed evidence is retained; no global pass is claimed.
