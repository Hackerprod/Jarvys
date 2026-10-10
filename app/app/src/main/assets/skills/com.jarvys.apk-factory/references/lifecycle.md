# Factory guidance factory-guidance-v78 / lifecycle

Use with the always-loaded Factory core; this reference grants no tools or approvals.

## Project/build/sign
factory.json: exact schemaVersion=1, appId/name, positive versionCode, versionName, supported capabilities, relative webDir/icon; entry index.html. Inspect identity/branding/type/size/path limits.
Icon v1: opaque #RRGGBB background/fills;1–32 shapes, coords0..192: circle {type,cx,cy,r,fill}, rect {type,x,y,width,height,fill}, polygon {type,points,fill},3–32[x,y]. Inspect PNG; no invented files.
Config: schemaVersion/appId/name/entryPoint/capabilities, owned documentBroker when required. Never replace config/SDK/DEX/manifest; no credentials/account/signing/unrelated data.
Read back spec/sources; verify references/offline dependencies/branding/JS/escaping/capabilities. Inspection≠execution.
build: spec_path, fresh expected_scope_version, new relative output_path; no per-app compile. Keep path/hash/app/version/template. Error/interruption/conflict: reread state, preserve uncertainty, fresh outputs; no replay/overwrite. Filename proves no build/installability.
sign: input_path, exact expected_sha256, distinct output_path, current expected_scope_version; same-project receipt/in-app approval. No bypass/key export/debug keys/identity replacement.
Legacy AndroidKeyStore keys: non-exportable; losing them can block updates. New keys: native Factory identities/passphrase-encrypted export/import. No passphrases/keys in chat/tools/projects/JS. Source/APK backups cannot restore keys.
Report artifact/checks. No step here installs the APK.
## Handoff/manifest
Test workflows/storage/export/Unicode/bounds/reload/cancel. Authorized devices verify coexistence/same-key updates/data retention; host tests prove no ARM64 behavior. Return source/APK/identity/signature/checks/blockers/evidence.
18 capabilities/36 methods; SDK 2/schema 1. APK: zero permissions/launcher only; documents/browser/maps/phone/email/sms/contacts/calendar share two host queries. F0a nodes: fixtures, not JS APIs. Verify closed AXML/resources/DEX/signed payload, resourceBindings/componentDex, template authentication/latest signed scope; disclose unknown baselines. Signing proves no publishing/install/testing. Pre-UX35 window limits remain; unsupported layouts: same-ID/key higher-version rebuild.
## Recoverable keys (F0b)
Native secrets only; no legacy/conflicting-certificate bypass. Export proves no restore. Import authenticates encryption/key/certificate/appId, merges max version floors; unknown history blocks signing until native floor review. Declarations prove no latest release; old backups omit later receipts. Same-appId/key/higher-version rebuild. Disclose passphrase/sole-backup loss; back up key/project/data separately. Physical tests required for independent restore/data-preserving update.
## Preview/tests (F0c)
preview/preview_status/test: exact input_path/expected_sha256/expected_scope_version/current same-project build. Isolated storage/WebView; simulated export/share/clipboard/haptics, Reset/Close. documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/share.file/presentation: UNAVAILABLE. Runs as Jarvys; best-effort disk cleanup. Observations/callbacks prove no rendering/workflows. Shared-handler/validator tests run no project JS/browser/device. No installation/hardware/persistence/zero-network claims. Scope changes/cancel/native closure revoke. Rebuild old templates; retain device gates.
## Installation (F0c)
Only user-requested install: exact input_path/expected_sha256/expected_scope_version/completed signed receipt. Human-only full app/version/hash/certificate/receipt review/Android consent. Signing≠install approval. Never automate/grant unknown-source permission/bypass warnings/retry commit. Play unavailable. install_status/install_cancel: same fields, no APK bytes. Settings → Factory installations recovers changed access. Background revokes uncommitted approval; restart never replays. Only authenticated system success proves install; missing sessions/UI/postcommit cancel prove no success/rollback. Unknown outcomes block automation pending recovery. Device/update/data retention need physical tests.
Missing install permission: protected review/manual grants never resume; native-close/new request. Damaged-record recovery: human-only, cancel owned sessions, preserve unknown outcome; never erase app data/keys.
