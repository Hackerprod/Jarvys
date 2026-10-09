# UX39: recoverable host validation checkpoint

Status on 2026-10-09: **build execution is on hold after two interrupted continuation sessions.** This directory records reproducible inputs and remaining gates; it is not a passing build or release certificate. Do not automatically restart builds from this document.

## Source and scope

- UX39 code checkpoint: `1a4e55f0b8513d20b39f884923a22ccba7b18b39`.
- App tree: `a04694cc0706547111d368d9169c140c48249ac9`; all 878 tracked app files remain unchanged.
- Pre-UX39 lint baseline: `33e64f8fa76a163c7dea128501c624535688a419`.
- Last previously verified documentation checkpoint: `b80c61f2060294215e2462253fa4227dd03d5b63`.
- This directory is outside `app/`. Its JVM agent is a host testing tool, never an APK dependency or native bridge.

`recovery-manifest.json` records completed, interrupted, historical and still-required checks separately. No signing key/password, private legacy contents, conversation quotes, raw application logs, APK, SDK/JDK archive or compiled guard JAR is included.

## Toolchain restoration

`toolchain-artifact-checksums.json` contains official download URLs, sizes, local SHA-256 values and the vendor-published checksums actually verified. The restored versions are Temurin JDK 21.0.12.1+1, Gradle 8.13, Android Platform 36 r2, Build-Tools 35.0.0 and command-line-tools 19.0. Repository pins remain AGP 8.13.2, Kotlin 2.2.20 and Robolectric 4.16. Node 24.19.0 passed the nine JavaScript tests.

The Android SDK agreement at https://developer.android.com/studio/terms was explicitly approved and recorded on 2026-10-09. The linked current terms are dated 2026-04-28, while Google's package metadata contains terms dated 2019-01-16. These were recorded distinctly; no old acceptance was invented. Restoring this documentation is not permission to manufacture a license hash or silently accept changed terms in a later environment. Use the official package/license workflow as authorized. Tools 23.0's separate downloader failed in this executor; official tools 19.0 recognized the installed packages. Android API compilation, D8 generation and zipalign verification passed.

The current external layout is:

- `/workspace/shared/Jarvys-recovery/integration`: clean repository checkout.
- `/workspace/shared/Jarvys-recovery/ux39-baseline`: detached worktree at `33e64f...`.
- `/workspace/shared/Jarvys-recovery/toolchain`: verified tools and disposable caches.
- `/workspace/shared/Jarvys-recovery/UX39-recovered-validation`: scripts, checksummed test SDKs and evidence.

The shell-only `toolchain/env.sh` sets `JAVA_HOME`, `GRADLE_HOME`, `GRADLE_USER_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_USER_HOME`, `TMPDIR` and `PATH` to that tree. It changes no system settings. The scripts in this archive deliberately retain these exact reviewed paths. If the layout changes, review all path edits before running; do not guess missing private files or credentials.

## Rebuild the guard outside the repository

Copy this directory's host scripts and `offline-guard/` sources to the external validation directory. Build/run only after execution is permitted again. The nested guard README explains its JDK-internal interception and limits. Its referenced raw reports are local evidence, not part of this source-only archive; the consolidated verified results are in `recovery-manifest.json`.

The reviewed guard passed 52 smoke checks, blocked 100 synthetic external attempts before non-localhost DNS, preserved real loopback HTTP, and passed five fail-before-main controls. Concurrent JVM attribution matched counts 100 and 0. HTTPS reached a loopback TLS ClientHello; a full successful HTTPS redirect was not executed.

Rebuild commands, from the external guard directory:

```sh
./build.sh
./run-smoke.sh
python3 run-fail-closed.py
python3 run-evidence-smoke.py
```

Rebuild ZIP timestamps can change the JAR hashes. `fresh-tests.init.gradle` intentionally pins the two reviewed artifact hashes and fails if they differ. A replacement build requires new smoke checks, source/artifact review and explicit re-pinning to the reviewed new artifacts; never remove the hash gate just to make a test run start. Preserve both adjacent JARs. Every Test worker must receive the guard as its first agent and a fresh absolute per-task report directory. The verifier requires matched numeric install/shutdown sidecars and actual Gradle launch evidence for every fork.

Scope: JDK URL/HttpURLConnection HTTP(S) paths under the documented trusted-fixture assumptions. It is not raw-socket, alternate-client, subprocess, Android-provider or OS network isolation. Exact localhost assumes its startup-verified mapping remains loopback. Use numeric loopback fixtures where possible.

## Resume only after the execution hold is resolved

All commands below are plans, **not claims that these gates have run successfully**. Run sequentially from the indicated worktree's `app/` directory. `run_gradle_gate.py` creates a unique evidence directory and records actual exit/times/log. No completed record means incomplete. Test runs require `--info` and fresh directories. Never recover a pass from interrupted output.

1. Complete official dependency warming without starting test JVMs:

```sh
python3 /workspace/shared/Jarvys-recovery/UX39-recovered-validation/run_gradle_gate.py warm-compile --info -I /workspace/shared/Jarvys-recovery/UX39-recovered-validation/warm-test-runtime.init.gradle restoreHostTestRuntimeCache :app:compileFullDebugUnitTestKotlin :app:compilePlayDebugUnitTestKotlin :apk-runtime:compileReleaseUnitTestJavaWithJavac
```

`warm-test-runtime.init.gradle` resolves only existing test runtime configurations; it adds no repository/dependency. `prewarm_robolectric.py` downloads the pinned Robolectric 4.16 instrumented SDKs from Maven Central and verifies published SHA-512 values. Their verified manifest is archived separately. This warming is outside the guard; application/provider test execution must not be used to fetch prerequisites.

2. Run a focused Full policy test with the reviewed guard and `--offline`, using `fresh-tests.init.gradle`, `:app:testFullDebugUnitTest --tests com.jarvys.agent.PreviewResponsePolicyTest`. Expect 16 cases, no failures/errors/skips. Copy verified focused XML into its run evidence before the aggregate overwrites reports.
3. Run fresh guarded aggregates, one task per run: `:app:testFullDebugUnitTest` (2,355), `:app:testPlayDebugUnitTest` (1,973), `:apk-runtime:testReleaseUnitTest` (64). Use `--offline --info -I <external>/fresh-tests.init.gradle`. Run `node --test --test-reporter=tap apk-runtime/tests/sdk.test.cjs` (9) again before final closure.
4. `verify_gates.py tests <XML-directory> <run.json> <task> <expected-count> --policy 16` verifies a main-app aggregate. Omit `--policy` for runtime. Missing/stale XML, failed/skipped task, missing agent injection, reused/mismatched sidecars or nonzero failures are not passes. Preserve per-run XML and logs before later tasks overwrite outputs.
5. Regenerate lint in both the detached baseline and current worktree with `--info -I <external>/fresh-lint.init.gradle`, requesting `:app:lintReportFullDebug :app:lintReportPlayDebug :apk-runtime:lintReportRelease`. Warm missing official lint dependencies separately if required. The external init disables only abort-on-existing-errors so full reports can be produced; it does not suppress diagnostics. Use `verify_gates.py lint <baseline.xml> <current.xml> <baseline-run.json> <current-run.json> <report-task>` for each variant. Historical reference counts were Full 46 errors/263 warnings/3 hints, Play 37/259/3, runtime 4 warnings. Fresh issue multisets, not old totals alone, are the gate.
6. Recheck `verify_gates.py source`. Build `:app:assembleFullDebug :app:assemblePlayDebug` with `-PunsignedBuild=true` (already set by `gradle-run.sh`). No signing key is required or read by this harness.
7. Before signing handoff, independently verify package `com.jarvys.agent`, label Jarvys, v56/1.2.49-UX39, unchanged permissions, ZIP/CRC, official 16 KiB alignment, unsigned state, exact per-entry content equivalence to Gradle outputs, launcher/factory resources, Play exclusions, absence of host guard/tests and immutable APK hashes. Comparison against a reconstructed baseline must be labelled as such: the delivered v55 bytes remain unavailable after the supported Library route returned 403 twice.

The original A6 signing material and 19 private legacy files are not in this recovery bundle. Signing identity and secure backup are handled separately; do not generate/read keys from these scripts or claim compatibility with the old signer. No v56 unsigned/signed APK has passed this recovery's gates.

## Residual product acceptance

UX39 adds a provider-dependent Connection-Allowlist response policy while preserving interactive JS/local assets and strict origin/manifest checks. WebView/Chromium 152 is the documented support milestone; compileSdk/targetSdk do not prove provider support. Older providers can ignore the header. The attempted Chromium 154 process never opened a page because startup was denied, including the one permitted retry. No alternate route was used. Real WebView/ICE positive/negative controls, native enforcement and packet observations remain open in `app/WEB_PREVIEW_ACCEPTANCE.md`.
