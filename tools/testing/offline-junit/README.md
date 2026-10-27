# Offline JVM test runner

Test-only harness for running explicitly selected JUnit/Robolectric classes without standard JVM network access. No production behavior is changed.

## Preparation

Use an already-installed JDK 21, Android toolchain, Gradle dependencies, and Robolectric instrumented Android jars. This harness does not install or download them.

Export each flavor's resolved test configuration with the repository's reviewed offline build setup:

```sh
./gradlew --offline --init-script tools/testing/offline-junit/export.init.gradle \
  -PofflineJUnitExportDir=/path/to/local-metadata :app:exportFullOfflineJUnit
# Use exportPlayOfflineJUnit for Play.
```

Run from the Gradle project root and adjust the init-script path accordingly. Preserve any required existing build exclusions/settings. The export task resolves the Test task's compilation/resource prerequisites, **not its Test action**. Review the task graph first. The export contains the exact classpath order, working directory, system properties, JVM arguments and assertion mode, including AGP's generated Robolectric configuration/resources.

Provide a reviewed JSON array of exact test class names, without wildcards. If Gradle includes absent optional generated-output directories, provide a separately reviewed JSON array of their exact paths. The actual missing set must match; missing archives are forbidden. The harness neither removes entries nor creates those directories.

```sh
python tools/testing/offline-junit/run.py \
  --export /path/to/local-metadata/Full.json \
  --classes /path/to/reviewed-classes.json \
  --java /path/to/jdk21/bin/java \
  --results /path/to/new-private-results \
  --robolectric-dependency-dir /path/to/existing-instrumented-android-jars \
  --allow-missing-classpath /path/to/reviewed-optional-directories.json
```

Omit `--allow-missing-classpath` when no entries are missing. Only reviewed JVM options `--add-opens=java.base/java.io=ALL-UNNAMED` and `-Djava.security.manager=allow` are accepted. Assertion mode is preserved. Proxy and JVM-injection environment variables are stripped. Export/build remains a separate offline Gradle operation outside the test runtime guard.

## Isolation and evidence

Each selected class runs in its own JVM. The launcher installs its guard **before class loading/test discovery**, blocks standard JVM DNS/socket/connect/listen/accept and subprocess creation, and retains the guard until process exit. A swallowed unexpected denial still fails the run. The only `checkExec` exemption is an immediate caller with declaring class exactly `java.io.File` and method `canExecute`, a pure permission query used by Conscrypt; generic execute permissions and actual process creation remain denied. There is no command/path allowlist.

This is **not an OS network namespace or a native-code networking sandbox**. Robolectric JNI libraries may load; native networking is outside SecurityManager's guarantee. Tests requiring subprocesses fail rather than receiving a bypass. Existing nested network ClassRules retain their own denial accounting; expected smoke probes are intercepted by the inner guard first.

Unsupported guard installation, initialization errors, no executed tests, event/count mismatch, timeouts, missing XML and nonzero exits fail. XML records test names, durations, failures, assumptions and ignored tests; listener rows must reconcile with JUnit executed-plus-ignored counts. Results are not inferred from a successful build.

Calibration was completed against the guarded Gradle run for `JvmNetworkDeniedRuleTest` and `CodingMissionAccessRuntimeTest`: **21 tests per flavor, Full and Play**, with matching names and outcomes. Broader regression validation was still pending when this harness was copied; this statement is not a claim that the complete product or S6 is finished.

**Keep raw XML, logs, metadata exports and absolute-path summaries private/local.** They may contain fixtures or failure details and must not be included in code/documentation backups. Publish only sanitized counts, names and artifact hashes. No generated result files belong in this directory.
