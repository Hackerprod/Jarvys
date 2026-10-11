# Factory configurable autonomy, v84 development checkpoint

Status: implementation and validation in progress. No APK from this revision has been signed or delivered. The earlier preferences-backup checkpoint was paused and is not release evidence for these changes.

## Implemented authority model

`FactoryOperationPolicy` is one persisted authority source for 20 operations: document open/create, photo pick/capture, audio play, browser open, contact pick, file sharing, maps, dialer, email/SMS editors, calendar editor, SQLite and preference backup export/import, signing, installation, and clipboard writing. The initial default is `ALLOW_ALWAYS`; the user can select `ASK`, `DENY`, or an inherited default for each authenticated app/certificate and operation. First signing uses a project-bound provisional identity and durably binds existing choices to the resulting certificate. Changing relevant authority invalidates active grants before later checked effects; old grants do not revive when a preference changes back.

`ALLOW_ALWAYS` removes Jarvys approval cards, mandatory review checkboxes, and secondary Use/Play approvals from these flows. `ASK` presents one meaningful review. `DENY` stops broker resource creation. Caller-side bounded read-only request/snapshot preparation can occur before the host authenticates and evaluates the request. The authority editor remains a protected user-controlled surface, preventing an executing agent from silently overriding the user's DENY.

Coding execution uses its saved Coding setting, otherwise an explicitly saved legacy Linux choice, otherwise ALLOW_ALWAYS. This does not silently change other connectors or the main Linux agent's default. STOP, project/mission ownership, writer leases, and command launch checks remain.

## Gates that remain, and why

- Android document/photo/contact selection, source install grants, applicable PackageInstaller UI, and genuine biometric UI remain Android requirements. Jarvys does not synthesize or bypass those grants.
- Package, UID, certificate, signing-key access, exact artifact and version continuity, document authorities, scoped data formats, and transaction integrity remain checked.
- Signed caller validation currently relies on the latest signed artifact receipt. Supporting multiple retained signed artifacts requires an authenticated per-artifact capability history; simply dropping the latest-artifact check would discard that evidence. This checkpoint does not claim to remove that limitation.
- The runtime is a packaged, precompiled DEX. It exposes the implemented SDK surface; it does not compile arbitrary Kotlin/Java or provide every Android API. Existing resource quotas are retained rather than replaced with unsafe unbounded buffers.
- Local storage/haptics and direct runtime text-sharing/export paths are not separate entries in the 20-operation host policy. This is not a claim that every SDK method has a new individual setting.
- Historical unknown outcomes remain unknown. Recovery can retire local state without a human-only global automation lock, but cannot claim an external Android screen closed or replay an uncertain write/commit.
- An issued URI grant or an already-open file descriptor cannot be recalled retroactively. New file-sharing opens and metadata access recheck current authority; existing descriptors may remain usable until their owner closes them.
- Backup protocol v2 checks live host authorization through its supported write/transaction-commit boundaries. Authenticated legacy v1 remains supported with its last host authorization boundary; it cannot halt an already authorized effect after handoff. Neither protocol rolls back an irreversible successful commit because later policy changes.
- Preview remains isolated and simulated. A simulated receipt, including clipboard `performed=false`, does not establish a live native effect.

## Verification so far

- First Full and Play production Kotlin/resource compilation passed on 2026-10-11, before subsequent review fixes. This is compile evidence, not final release validation.
- JavaScript SDK: 48 fresh tests passed, no failures or omissions.
- Independent review covered persisted authority failure, certificate migration, stale first-sign ownership, and callback revocation. Additional runtime/transaction and lifecycle review is being incorporated.
- Focused native tests, final aggregate gates, lint comparison, generated DEX/APK audit, signing, and physical-device acceptance are not yet complete for this revision.

Private execution evidence stays outside Git. This document records product scope and honest validation status, not private tool logs or credentials.
