# Jarvys recovery: ephemeral host JVM HTTP(S) guard

This is a new, standalone reimplementation of the stated previous contract, not recovered original bytes. This recovery directory archives source only. Execute the guard and keep its compiled artifacts/evidence outside the checkout; it is not an application dependency. No Gradle command, repository/source/Git operation, OS network setting change, credential creation, or real external-peer test was performed to build or smoke-test it.

## Exact use

Use the restored, verified JDK 21 at:

`/workspace/shared/Jarvys-recovery/toolchain/jdk-21.0.12.1+1`

Add this option to **every actual test worker JVM**, as its **first javaagent**:

```
-javaagent:/workspace/shared/Jarvys-recovery/UX39-recovered-validation/offline-guard/jarvys-offline-guard.jar
```

For per-fork attribution, also pass an **existing, writable, absolute, fresh-run evidence directory**:

```
-Djarvys.offlineGuard.reportDir=/absolute/path/to/this-run-evidence
```

This optional property is not an agent argument. A supplied empty/relative/non-directory/unwritable path fails premain before application main. The final directory itself must not be a symlink. An omitted property preserves stdout-only operation. No directory is automatically created by the agent.

Keep `guard-bootstrap.jar` next to the agent JAR. Its relative location is supplied by the agent manifest's `Boot-Class-Path`. The agent uses only premain; attaching it to an already-running test JVM is not supported. No runtime `--add-opens`, `--add-exports`, classpath addition, or system proxy setting is required. The agent makes its narrow JVM-local module export/read edges using Instrumentation. No OS security or network setting is changed.

Putting the flag on only a Gradle launcher or daemon does not cover test workers. Do not inject it globally through `JAVA_TOOL_OPTIONS`: that can unnecessarily guard dependency-resolution/build JVMs. Prewarm permitted dependency downloads separately; there is no Maven allowlist. The standalone guard smoke stage did not run Gradle; the separate host harness supplies Test-task wiring.

Optional crash-diagnostic suppression for the intentional fail-startup smoke cases is `-XX:-CreateCoredumpOnCrash`; it is not a guard requirement.

## Required per-fork evidence

A successful installation emits exactly:

```
[jarvys-offline-guard] installed hooks=7 localhost=verified-loopback
```

If localhost cannot be verified, the final field is `localhost=denied` and exact localhost URLs will be blocked. Numeric loopback remains permitted.

On normal JVM shutdown, the guard emits:

```
[jarvys-offline-guard] blockedAttempts=N
```

`N` is the atomic per-JVM number of denials, excluding the one network-free premain installation probe. It is an attempt count, not a unique request/host count; retries can increment it again. The marker is emitted even when `N=0`. The guard never logs request URLs, hosts, authentication, headers, query parameters, or payloads. It does not change the application's or JDK's own logging configuration.

When reportDir is supplied, the guard writes two collision-safe `CREATE_NEW` records. It never overwrites existing records:

- `guard-<pid>-<jvmStartedAtEpochMillis>.installed.json`
- `guard-<pid>-<jvmStartedAtEpochMillis>.shutdown.json`

The install JSON contains only numeric `pid`, `jvmStartedAtEpochMillis`, and `installedAtEpochMillis` fields. The shutdown JSON repeats those fields and adds numeric `shutdownAtEpochMillis` and `blockedAttempts`. No command line, environment, URL, authentication, header, payload, or class/test name is written. Match each Test Executor PID and process start time to exactly one install and shutdown pair within the run. Validate the JSON, equality of the common fields, timestamp ordering, and expected process exit/test results. A missing/partial/unmatched shutdown record or `shutdown-report-write-failed` log means incomplete evidence; never interpret it as zero. A crash or a directory that becomes unwritable after installation can prevent final evidence. The count is the snapshot at the guard shutdown hook; unrelated concurrently executing shutdown hooks can run later.

Capture stdout/stderr for each worker and require its install marker before its tests, its normal shutdown/count marker afterward, and the test process/result status. Missing install evidence means that fork is not verified as guarded. Missing shutdown evidence (for example forced kill) leaves the final count incomplete; it is not evidence of zero egress. Do not assume a build/test pass proves all forks were injected. A positive blocked count is a finding to inspect, not itself proof the tested behavior passed.

## Implementation and interception points

The manifest loads a tiny bootstrap policy helper and calls `guard.agent.Agent.premain`. Premain rejects unsupported arguments, non-JDK-21 runtime feature versions, missing helper/bootstrap loading, unavailable retransformation, unmodifiable targets, missing method shapes, or transformation failures. Exceptions escape premain so the JVM cannot continue into application main. Instrumentation's normally ignored transformer errors are recorded and checked explicitly. A synthetic URL with a custom handler that cannot perform network I/O verifies interception before the installed marker appears.

The JDK's own bundled internal ASM implementation is used only while instrumenting three JDK classes. No external ASM or application library is needed. Seven method-entry probes are installed and verified:

1. `java.net.URL.openConnection()` checks the URL before invoking any handler.
2. `java.net.URL.openConnection(Proxy)` checks target and explicit proxy before invoking any handler.
3. `sun.net.www.protocol.http.HttpURLConnection.plainConnect()` checks the target, including direct built-in connection construction that skipped `URL.openConnection`.
4. `HttpURLConnection.URLtoSocketPermission(URL)` checks the target before permission processing, including redirect target processing.
5. `HttpURLConnection.followRedirect0(String,int,URL)` checks the new target before logging, mutating URL state, or reconnecting; includes the 305 proxy redirect path.
6. `sun.net.www.http.HttpClient.openServer()` checks target and selected proxy before opening the built-in HTTP(S) transport.
7. `HttpClient.openServer(String,int)` checks target, proxy and actual transport endpoint before `doConnect` resolves/connects.

Inspected JDK source confirms that built-in HTTPS uses the HTTP client transport base and the shared HttpURLConnection redirect path. This avoids the ordinary redirect and per-URL custom-handler gaps of a URLStreamHandlerFactory-only implementation. File/non-HTTP URL behavior is left unchanged.

## Host policy

- Permit strict dotted-decimal IPv4 127/8 literals, with no abbreviated, integer, leading-zero or alternate forms.
- Permit the IPv6 loopback value `::1`, including equivalent full/compressed hex forms. Reject zones, IPv4-mapped addresses and non-loopback literals.
- Permit exact case-insensitive `localhost` only when every address returned by its startup resolution is loopback. No `.localhost` suffixes or arbitrary host aliases are permitted.
- An explicit/selected proxy must itself have an allowed host, and any already-resolved proxy address must be loopback. Target checks still apply when the proxy is local.
- Unknown hostnames are denied by string/literal checks before their DNS lookup or connection in the covered paths.

Localhost is verified once at premain. The guard is not a defense against a hostile/changing resolver or hosts configuration that remaps localhost afterward. Use explicit numeric loopback fixtures for the strongest assumptions. A trusted local proxy can relay traffic of its own; the guard does not control other processes or treat arbitrary loopback services as trusted by authentication.

## Scope and limits

This is an accidental-egress guard for reviewed host-side JDK URL/HttpURLConnection test paths. **It is not whole-OS, arbitrary-socket or adversarial-code isolation.** It does not cover raw Socket/SocketChannel/DatagramSocket, `java.net.http.HttpClient`, native transports, OkHttp/other independently implemented clients, other processes/subprocesses, Android/device/emulator networking, non-HTTP protocols, or traffic emitted by a local fixture/proxy process. Arbitrary custom handler code that receives an allowed URL (or runs during URL parsing), callbacks such as a custom ProxySelector, another agent, or code using direct/native transport can perform activity outside these hooks. An external HTTP(S) URL passed to either normal URL.openConnection API is blocked even with a custom handler.

Use the first-javaagent order; earlier agents can execute code before this guard. Do not claim that unguarded preparation processes or a previously running JVM were protected. Do not claim full HTTPS positive/redirect end-to-end TLS verification: the smoke tests verify external HTTPS URL/custom-handler blocking and a real loopback TCP/TLS ClientHello, but intentionally create no key or certificate and do not complete a successful TLS response. HTTPS redirect coverage is established by inspected shared JDK implementation, not a full successful HTTPS fixture.

Tested runtime is Eclipse Temurin **21.0.12.1+1**. The agent validates JDK feature 21 and exact required method shapes; a different JDK 21 implementation/build still needs its own source review and smoke run. JDK internals are not a stable public API.

## Build and verification

- Source: `src/guard/agent/{Agent,Transformer}.java`, `src/guard/bootstrap/OfflineGuard.java`
- Build: `./build.sh`
- Bounded smoke: `./run-smoke.sh`
- Startup fail-closed tests (after smoke compilation): `python3 run-fail-closed.py`
- Concurrent per-JVM evidence attribution: `python3 run-evidence-smoke.py`
- All generated outputs remain in this directory.

Final smoke: **52 checks pass, 100 blocked attempts, zero non-localhost DNS resolver calls**. A smoke-only InetAddressResolverProvider tripwire rejects all non-localhost hostname resolutions before they can reach platform DNS. Negative numeric targets use `openConnection` only, which does not connect even if a regression returns a connection. Other negative peers use reserved `.invalid` names, so a guard regression hits the local DNS tripwire instead of a real external peer.

Verified real loopback IPv4 and IPv6 HTTP GET, verified localhost HTTP GET, relative loopback redirect, POST payload, local explicit HTTP proxy, external redirect statuses 301/302/303/305/307, explicit and implicit HTTP/SOCKS proxy rejection, HTTP and HTTPS custom handlers and both URL overloads, direct built-in HttpURLConnection construction, strict host forms, a real HTTPS TLS ClientHello to a loopback socket, concurrent count accuracy, and zero external DNS calls. Fixture handler request counts were exact. Missing-bootstrap, unsupported-argument, missing-report-directory, relative-report-directory, and unwritable-report-directory startup tests all exit 1 without entering application main. Two simultaneous JVMs have independently matched install/shutdown records with exact expected counts of 100 and 0. Sensitive synthetic URL/auth/query/redirect/body sentinels are absent from the final smoke log.

Evidence:

- `reports/smoke.txt`
- `reports/install-smoke.txt`
- `reports/fail-closed-results.json` and `reports/fail-closed-*.txt`
- `reports/attribution-results.json` and its linked per-process evidence/logs
- `reports/log-redaction.txt`
- `reports/jdk-path-inspection.txt`
- `reports/artifact-sha256.txt`, `reports/source-sha256.txt`, `reports/provenance.json`
