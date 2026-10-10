# v80 portable host recovery sources

This is a source-only recovery package for the Jarvys v80 host-validation workflow. It is separate from production application sources and contains no completed host reports, APKs, compiled JARs, SDK/JDK archives, keys, credentials, device data or signing material. It does not establish v80 test, lint, APK or biometric-device acceptance.

The guard Java and smoke sources are byte-identical to `recovery/ux39-host/offline-guard` at commit `50d4a9592152e61318147cec87bc812f9253912f`. The standalone guard's two compiled artifacts were rebuilt and independently reviewed before the exact replacement hashes were pinned. `guard-reviewed-artifacts.json` binds the source and expected artifacts; it is provenance/pin metadata, not a replacement for fresh runtime evidence.

The portable launcher and parameterized init scripts in this package require their own independent review before adoption. Existing approved host runs are unaffected by this source package.

## Ownership and contents

- `gradle-run.sh`: low-level serial unsigned Gradle launcher, always loading the guarded Test init script exactly once, including when orchestration also names the same init. It never creates `env.sh`, accepts SDK terms, generates credentials or signs APKs.
- `run_gradle_gate.py`: the existing host gate logger with its host directory derived from its materialized location; it creates a unique run directory and records actual exit/timing/log metadata.
- `fresh-tests.init.gradle`: fresh per-task temp/home/guard records, offline Robolectric SDKs, first-agent validation, `--offline` execution requirement and exact two-JAR SHA-256 gates.
- `fresh-lint.init.gradle`: forces fresh app lint reports and disables only abort-on-existing-errors. Actual baseline/current issue-multiset comparison remains a separate required gate.
- `warm-test-runtime-v80.init.gradle`: resolves actual AGP Test task classpaths for Full/Play and both runtime modules without starting tests or adding repositories/dependencies. The earlier raw-configuration resolver is not used because v80 local-variant resolution requires the actual Test classpath attributes.
- `offline-guard/`: original Java/smoke inputs plus portable build/smoke scripts. Generated outputs must remain outside a checkout.
- `restore_guard_sources.py`: verifies the source manifest and materializes a new external guard directory. It refuses overwrite.
- `orchestration/`: the coordinating task's existing run_gate.py, freeze_source.py, compare_lint.py and three init scripts. Their current logic is preserved; only the factory-evidence root is parameterized.
- `restore_orchestration.py`: copies those reviewed sources into the required external H/V layout without overwriting existing scripts.
- `restore_pinned_artifacts.py`: downloads or verifies exact public prerequisite bytes. It does not extract/install software or accept terms. SDK30 (`11-robolectric-6757853-i7`) is included alongside the other pinned Robolectric 4.16 SDKs.
- `package_sources.py`: verifies the complete explicit source allowlist and optionally produces a deterministic source-only archive, refusing overwrite.

The orchestration copies preserve the coordinating task's current implementation rather than introducing alternate gate runners. `orchestration-source-binding.json` records their source/copy hashes and the sole factory-evidence root substitution. Independent profile/gate/DEX reviewer sources remain owned separately and should be preserved alongside this package when stable. No reviewer or orchestration host reports are copied.

## Explicit path contract

Set these variables to the actual reviewed environment. Paths shown below are placeholders, not existing locations:

```sh
export JARVYS_TOOLCHAIN_ENV=/absolute/external/toolchain/env.sh
export JARVYS_VALIDATION_ROOT=/absolute/external/validation
export JARVYS_GRADLE_LOCK=/absolute/external/toolchain/gradle.lock
export JARVYS_RUN_LABEL=unique-fresh-label
export JARVYS_FACTORY_EVIDENCE_ROOT=/absolute/external/UX42-fixtures
```

The caller owns the shell-only `env.sh`. It must provide verified `JAVA_HOME`, `GRADLE_HOME`, `GRADLE_USER_HOME`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, `ANDROID_USER_HOME`, `TMPDIR` and `PATH` values. No machine-specific `env.sh` is distributed. All output/cache directories must already be authorized for the current task. `JARVYS_VALIDATION_ROOT` must exist before launching Gradle. Each test task's evidence directory must be new.

The Test init expects both adjacent guard JARs in `$JARVYS_VALIDATION_ROOT/offline-guard` and verified offline Robolectric JARs in `$JARVYS_VALIDATION_ROOT/robolectric-sdks`. The launcher always loads the package's `fresh-tests.init.gradle`. It may use an existing credential-free `HTTPS_PROXY` only for the Gradle process; it does not set global `JAVA_TOOL_OPTIONS` or change OS/network settings. No proxy is required when the environment does not need one.

First materialize the sources into the external layout below. Never execute orchestration from this source archive in place. From the selected reviewed checkout's `app/` directory, the materialized gate runners call the materialized launcher with their requested tasks and additional init scripts. Use a unique run label and preserve actual exit/timing/log metadata. Actual Test tasks require `--offline --info`. Dependency warming/compilation is separate and must never execute tests to discover/download prerequisites. Warming may omit `--offline`, but any inadvertently selected Test task fails before its worker starts.


## External orchestration layout and ordering

Materialize into a new external recovery root R, which contains the reviewed checkout and detached reconstructed baseline separately:

```text
R/
  integration/                       current reviewed v80 checkout
  v79-baseline/                      detached reconstructed baseline checkout
  toolchain/                         caller-owned tools/env/cache
  UX39-recovered-validation/          H: launcher/init/guard/SDK/run evidence
  FACTORY-BIOMETRICS-v80-validation/   V: materialized orchestration and v80 evidence
  FACTORY-SENSORS-v79-validation/     final reconstructed offline baseline evidence
    metadata-preparation/             completed online preparation evidence only
  UX42-fixtures/                     synthetic factory evidence (caller-selected root)
```

```sh
python3 restore_orchestration.py --recovery-root /absolute/external/R
python3 restore_guard_sources.py --validation-root /absolute/external/R/UX39-recovered-validation
```

The helpers only copy source files. They do not create checkouts, accept terms, populate source freezes, run Gradle, build APKs or declare checks passing. Existing H/V source files cause materialization to stop rather than overwrite a live harness. Symlinked H/V directories, targets escaping the selected recovery root, and real checkout targets are rejected before any source write. The archived `run_gate.py` derives R/H/integration/v79-baseline from its location in V; retain those exact directory names.

Before baseline execution, `run_gate.py` requires baseline HEAD `5c177d32cfc1b13209a54131753f98cec3582d42`, app tree `805c0cc19c5aa9b8bb50649c0e2aef2a238cff43`, clean app inputs and matching before/after baseline hashes. This is explicitly a reconstructed baseline. Freeze the current v80 source independently and preserve it across all gates. Merely creating a new freeze does not establish the correctness of its selected source.

Complete authorized compilation and actual Test classpath producer prerequisites before running `restoreHostTestRuntimeCache` with `warm-test-runtime-v80.init.gradle`. Compilation alone may not produce runtime JARs: inspect the actual Test classpath build dependencies and execute every required producer without executing tests. Verify the inputs exist. The warmer resolves actual Test classpaths; it does not invoke missing producers or promise that compilation alone is sufficient. Do not execute Test tasks as a warming shortcut. After dependencies and exact Robolectric SDKs are warmed, actual tests are offline and guarded. Lint runs all four Full/Play/runtime reports with only `:apk-runtime:lintVitalRelease` excluded. This narrow reviewed workaround addresses the reproduced AGP graph defect where `lintVitalReportRelease` is disabled but its dependent `lintVitalRelease` still consumes missing files. Full release runtime analysis and `:apk-runtime:lintReportRelease` remain requested and must execute freshly, alongside the other three reports. No other task exclusion, diagnostic suppression or ordering shim is introduced. Compare exact baseline/current issue multisets, not totals alone; the preserved v80 comparison also expects 313/300/3/2 issues.

The coordinating task invokes the materialized `run_gate.py` with `baseline-prepare`, `baseline`, `baseline-build`, `lint`, `aggregate` or `build` only when their prerequisites and authority are satisfied. `baseline-prepare` runs the complete four-report reconstructed-baseline lint stage online and archives its preparation outputs only under `FACTORY-SENSORS-v79-validation/metadata-preparation`. After the separately reviewed metadata freeze/seed below, `baseline` runs the complete four-report final baseline offline; candidate `lint` likewise remains offline. All three lint modes retain only the narrow vital-task exclusion above. `baseline-build` remains a separate normal three-target unsigned build with normal vital tasks and no exclusions; current `build` likewise has no lint exclusion. Candidate `lint`, `aggregate` and `build` retain `--offline`. Its six aggregate Test tasks and 41 JavaScript tests do not substitute for per-fork guard verification, source binding, signed/unsigned APK inspection or real device acceptance.

The earlier combined baseline attempt failed on the AGP vital-task graph defect. It remains a failed run; partial output and previously captured reports are not fresh passing evidence. Splitting the subsequent stages and preserving this workaround does not establish that any final v80 gate has passed.

### Matched lint metadata prerequisites

Verified AGP 8.13.2 propagates Gradle offline mode to lint 31.13.2. Lint dependency diagnostics can also depend on task-local public remote metadata, so an online baseline and an empty-cache offline candidate are not equivalent inputs. Metadata differences remain an open reproducibility issue until the matched offline runs and post-run verification establish the result; this source correction does not claim the drift is resolved.

1. Complete `baseline-prepare` online and preserve its successful run JSON, logs and all four fresh reports under the separate metadata-preparation directory. Failed, interrupted or partial preparation cannot authorize a snapshot. Preparation reports are not the final offline baseline.
2. Have the independent reviewer use the separately reviewed `independent/lint_metadata_snapshot.py`, its tests and `README-LINT-METADATA.md` to freeze the prepared public metadata and exact lint-version markers. Bind the snapshot to the completed preparation run, exact reconstructed baseline HEAD/app tree, and a separately recorded reviewed manifest SHA-256.
3. Under the same existing nonblocking Gradle lock, have the reviewer seed the candidate from that approved digest and verify both projects before the offline comparison. The allowlist covers only reviewed task-local Google Maven indexes, Maven version metadata, SDK index payloads and the exact `Cache for Android Lint31.13.2` plus LF marker. It excludes API/typo databases, models, reports and partial-analysis results. No real snapshot payload belongs in this source package.
4. Run `baseline` and candidate `lint` with matching frozen sources/tooling, the same approved metadata inputs and `--offline`. Both must produce their complete fresh four-report evidence. The narrow vital-task exclusion remains identical on both sides.
5. Have the reviewer verify metadata bytes/inventory for both projects against the same externally recorded digest after their gates, then review the actual complete diagnostic multisets. Do not infer a pass from preparation output or an unchanged total.

These are required orchestration prerequisites; `run_gate.py` does not itself freeze, seed or verify metadata. The reviewer owns the real snapshot/seed and associated checks. Retain the historical expected issue counts 313/300/3/2 unchanged until a separate explicit gate-policy review. Additional remote-metadata warnings must not be suppressed, removed from comparisons or hidden by editing caches to recover desired totals. No final lint or other v80 gate pass is established by this package update.


## Restore exact prerequisites

First verify this package:

```sh
python3 package_sources.py
```

The portable manifests contain expected public URL, length and SHA-256 values; Robolectric entries additionally pin the publisher-verified SHA-512. Existing mismatched artifacts are rejected instead of overwritten. These are exact historical tool versions, not a request to download an unreviewed latest release.

```sh
python3 restore_pinned_artifacts.py --kind toolchain --destination /absolute/external/toolchain/downloads
python3 restore_pinned_artifacts.py --kind robolectric --destination /absolute/external/validation/robolectric-sdks
python3 restore_pinned_artifacts.py --kind robolectric --sdk 30 --destination /absolute/external/validation/robolectric-sdks --verify-only
```

Downloading does not install or authorize new agreements. Extract/install through the official permitted workflow. Use the originally pinned complete Temurin JDK21.0.12.1+1 rather than a JRE-only system installation. Do not create a license file/hash to imply historical acceptance. If the current official Android SDK workflow requires terms acceptance, obtain appropriate authorization first. No licenses, key material or credentials are included here.

## Rebuild and review the guard

Materialize sources outside the repository and source package:

```sh
python3 restore_guard_sources.py --validation-root /absolute/external/validation
export JDK=/absolute/external/toolchain/jdk-21.0.12.1+1
cd /absolute/external/validation/offline-guard
./build.sh
./run-smoke.sh
python3 run-fail-closed.py
python3 run-evidence-smoke.py
```

Capture the actual exit status and stdout/stderr for every command. `build.sh` and `run-smoke.sh` refuse to generate files in a Git checkout. Preserve original sources, JDK/archive/source-ZIP identity, artifact and per-entry hashes, all smoke/fail-closed/concurrent sidecars, and synthetic-log redaction checks in a new external evidence directory. A rebuilt ZIP can have different timestamps and therefore different whole-JAR hashes even when its class payloads match.

**A new hash mismatch is a stop, never a reason to disable a gate.** Require complete fresh smoke checks and independent review of source, JDK identity, JAR contents and exact hashes. Only after explicit approval may the two exact hashes in `fresh-tests.init.gradle` and `guard-reviewed-artifacts.json` be updated together. Never auto-repin during download/build or accept arbitrary adjacent JARs.

The expected smoke contract is 52 checks, 100 synthetic blocked attempts and zero non-localhost DNS resolver calls; five invalid-startup controls must exit before application main. Two simultaneous smoke JVMs must have matched numeric install/shutdown records with counts exactly 100 and 0. These expectations are not fresh passing evidence by themselves.

## Evidence and scope limits

Every actual Test worker must be launched with this guard as the first javaagent. Require Gradle `--info` launch evidence, exact report directory, install/shutdown markers and one valid matched sidecar pair per worker; reject missing, stale, reused, partial or unmatched evidence. Successful Gradle output alone does not establish guarded coverage. A positive blocked count is a finding to inspect, not proof of test success. Lint requires fresh baseline and current complete issue multisets. Freeze the current v80 sources separately; the old 878-file UX39 source snapshot is historical and cannot establish v80 equivalence.

This guard covers reviewed JDK URL/HttpURLConnection HTTP(S) paths under trusted loopback-fixture assumptions. It is not raw-socket, alternative-client, subprocess, Android/WebView-provider or OS isolation. Exact localhost is verified at startup only. The HTTPS smoke reaches a real loopback TLS ClientHello; it does not create a certificate or complete a successful HTTPS response/redirect. No real biometric/device tests, real user data or external HTTP fixture traffic are authorized by this package.

## Source-only backup

After independent review, verify and archive only the explicit source allowlist:

```sh
python3 package_sources.py --archive /absolute/external/factory-biometric-v80-host-source.tar.gz
```

The helper rejects unlisted/mismatched sources and refuses to overwrite an archive. It does not add host reports or recursively archive an arbitrary working directory. Any later source edits require a newly reviewed source manifest; do not regenerate it merely to hide a failed integrity check. Publishing or committing is handled separately under the current task's authorization.
