# Frozen lint metadata inputs

`lint_metadata_snapshot.py` prepares a controlled comparison of the reconstructed v79 baseline and frozen v80 candidate. It does not run Gradle or lint, download anything, edit application sources, copy analysis results, or establish a lint pass.

The installed AGP 8.13.2 forwards Gradle offline mode to lint 31.13.2. LintCliClient declines nonlocal URL opens in that mode, while dependency checks may use task-local cached metadata. Therefore an online baseline and empty-cache offline candidate are not equivalent diagnostic environments.

## Approved scope

The only copied files are below these task-local public metadata directories:

- maven.google: master-index.xml and group-index.xml
- maven-versions: maven-metadata.xml from the fixed known repository host names
- sdk_index: snapshot.gz
- The one tool-version marker lint-cache-version.txt, with exact bytes `Cache for Android Lint31.13.2` plus LF

Reviewed report-task caches are specifically app lintReportFullDebug/lintReportPlayDebug and runtime/core lintReportRelease. AGP AndroidLintTask initializes the same cache/version marker for reporting; actual preparation report caches contained only that marker. These do not broaden the permitted payload types.

The marker is required because the installed `LintTool.initializeLintCacheDir` deletes the entire task cache if its version marker is absent or mismatched. Its bytecode checks the derived version key, clears that task cache on mismatch, then creates this marker. The reviewer saved the installed-tool disassembly and hashes separately; those generated reports are not source-backup inputs. No API database, typo database, model, report or partial-analysis output is copied.

The helper rejects symlinks, hardlinked input files, traversal, unsupported modules/tasks/files, extra snapshot files, differing target metadata and file/directory collisions. It preflights every target before writing, preserves metadata payload modification times, and never deletes existing files. Missing target version markers can be initialized. Existing unrelated analysis outputs are untouched and are never accepted as fresh gate evidence.

## Sequence

Materialize this script under the external validation directory `FACTORY-BIOMETRICS-v80-validation/independent`. The CLI binds the sibling integration and v79-baseline project roots and the existing toolchain/gradle.lock. It takes that exact lock nonblockingly and refuses to work while Gradle owns it. Do not use a different lock or copy metadata during a build.

1. Finish the online, complete four-report v79 metadata-preparation run and preserve its run JSON, logs and reports. It is preparation, not the final matched offline baseline.
2. Invoke freeze with the exact detached v79 project, completed preparation run JSON, new external snapshot directory, and verified runner lock. The helper checks the exact v79 HEAD/app tree and clean application inputs. Record its returned manifest SHA-256 separately.
3. Review the snapshot inventory and record that digest outside the snapshot. Invoke seed on the candidate with `--manifest-sha256` set to that reviewed digest. Seed/verify never derive their own trust pin from the supplied manifest.
4. Run all four baseline reports freshly offline, then all four candidate reports freshly offline. Retain the separately reviewed narrow derivative-task exclusion only. Compare the complete diagnostic multisets and required counts, not counts alone.
5. Invoke verify for both projects with the same externally recorded digest after their gates. Verify rejects changed metadata bytes/inventory. Timestamps may be refreshed by lint without changing inputs; original snapshot times remain in its manifest.

Example command shapes, with absolute paths supplied by the coordinator:

    python3 lint_metadata_snapshot.py freeze --project BASELINE --run-json PREPARATION_RUN_JSON --snapshot NEW_EXTERNAL_SNAPSHOT --lock VERIFIED_RUNNER_LOCK
    python3 lint_metadata_snapshot.py seed --project CANDIDATE --snapshot SNAPSHOT --manifest-sha256 REVIEWED_DIGEST --lock VERIFIED_RUNNER_LOCK
    python3 lint_metadata_snapshot.py verify --project BASELINE --snapshot SNAPSHOT --manifest-sha256 REVIEWED_DIGEST --lock VERIFIED_RUNNER_LOCK
    python3 lint_metadata_snapshot.py verify --project CANDIDATE --snapshot SNAPSHOT --manifest-sha256 REVIEWED_DIGEST --lock VERIFIED_RUNNER_LOCK

The source-only backup allowlist for this helper is this README, lint_metadata_snapshot.py and test_lint_metadata_snapshot.py. Never put metadata payloads, cache snapshots, generated disassembly or test logs in that source backup. Synthetic controls run with `python3 -m unittest -v test_lint_metadata_snapshot`; they create only temporary fixture directories and do not touch real caches.

Remote metadata may legitimately reveal additional pre-existing dependency warnings. Do not suppress those, remove them from the comparison, edit cache contents to recover a desired count, or infer an exact historical delta from totals when historical XML is unavailable. Any gate-policy change requires a separate explicit review.
