# Portable ephemeral JVM HTTP(S) guard sources

The original seven JDK method-entry probes and JDK smoke fixtures are preserved from the source archive at `recovery/ux39-host/offline-guard` in commit `50d4a9592152e61318147cec87bc812f9253912f`. A separately enabled extension covers the reviewed OkHttp 5.2.3 paths below. See the package root README for build, review, pinning and environment requirements. Set `JDK` or `JAVA_HOME` to the verified complete Temurin21.0.12.1+1 installation. Materialize/build outside the repository; compiled artifacts are never app dependencies.

The guard must be every Test worker's first `-javaagent`. Keep `guard-bootstrap.jar` beside `jarvys-offline-guard.jar`. The manifest's bootstrap path loads the helper; premain fails before application main on unsupported JVM/method shape, missing helper, unsupported arguments, transformation errors or unavailable requested evidence. No global `JAVA_TOOL_OPTIONS`, OS security/network setting, extra application dependency or key is required.

The seven method-entry probes are URL.openConnection (both overloads); HttpURLConnection.plainConnect, URLtoSocketPermission and followRedirect0; and HttpClient.openServer (both overloads). The original Temurin source ZIP confirms the HTTPS delegate/client inherit the shared connection, redirect and transport paths.

Only strict dotted-decimal IPv4 127/8, exact IPv6 loopback and startup-verified exact localhost are permitted for HTTP(S) targets and proxy endpoints. Ambiguous literals, external hosts and nonloopback proxies are denied before covered DNS/connect paths. File/non-HTTP URL handling is unchanged. Trusted numeric loopback fixtures provide the strongest assumptions.

The optional `-Djarvys.offlineGuard.reportDir=/absolute/existing/fresh/directory` writes collision-safe CREATE_NEW install/shutdown records. Records contain only numeric process ID, process start/install/shutdown times and blocked-attempt count. No URLs, auth, payloads or command lines are recorded. Missing or failed shutdown evidence is incomplete, never zero. Capture install/shutdown stdout/stderr markers as well as sidecars and process status.

Smoke expectations: 52 checks; 100 blocked synthetic attempts; zero non-localhost DNS resolver calls; five fail-before-main startup controls; concurrent JVM attribution 100 and 0. The smoke-only DNS provider blocks nonlocalhost lookups even if the guard regresses. Negative numeric URL checks do not connect; all other negative names are reserved `.invalid`. HTTP fixtures use real numeric loopback. HTTPS reaches a loopback TLS ClientHello without generating certificates or keys.

Scope excludes raw sockets/datagrams, java.net.http.HttpClient, OkHttp when the required extension is absent, independent transports, native clients, other processes, device/emulator traffic, changing/hostile localhost resolution, a local proxy's own relayed traffic and arbitrary malicious callbacks. It is accidental-egress protection for reviewed paths, not whole-system isolation. Rebuild timestamps may change JAR hashes: obtain fresh independent source/artifact review before repinning both exact hashes. Never weaken the hash gates.


## Required OkHttp 5.2.3 test-worker extension

Set `-Djarvys.offlineGuard.okhttp=required` on the actual app Test worker JVM, together with the existing first `-javaagent` and fresh absolute report directory. Leave the property absent for modules without OkHttp. Any other value, including `true`, `false`, or an empty value, fails premain. No application dependency, production code, client interceptor, global cookie/auth handler, or Gradle dependency is changed by this extension.

This is load-time instrumentation of each actual defining classloader. Premain does not preload a separate system-loader copy. The guard rejects already-loaded OkHttp and named-module/bootstrap definitions; supported test dependencies use unnamed classpath modules. Five additional entry hooks are: RealCall.execute, RealCall.enqueue, RealInterceptorChain.proceed, RouteSelector.resetNextInetSocketAddress, and ConnectPlan.connectSocket. The first two check the original destination before callbacks/dispatch; proceed checks internal redirects and interceptor rewrites; the final two check selected proxies before DNS and resolved socket endpoints before socket factories/connect. Loopback targets/proxies remain permitted. A custom DNS returning an external address for localhost is rejected before the socket factory. Arbitrary side effects in custom DNS, proxy selectors, event listeners, interceptors, socket factories, or other callbacks are outside this guard's sandbox scope.

Each entry first verifies all four required target classes in that same defining loader. Missing classes or missing hooks prevent entry even without an explicit preflight. The transformer pins exact original class SHA-256 values and validates the five expected descriptors; mismatch returns invalid class bytes because JVM instrumentation ignores thrown transformer exceptions. A malformed target therefore cannot silently run unguarded. Later retransformation failure leaves the prior guarded definition in place and poisons coverage evidence. Run this guard before coverage or mocking agents, and fail the run if another agent changes the expected original bytes.

Actual `okhttp-jvm-5.2.3.jar` SHA-256: `68927bd7f203d214ff62ebe5e80a8bea8de29dcea321ebf90989b371a9969195`. The four target-class hashes in OkHttpTransformer were also verified identical in `okhttp-android-5.2.3.aar` SHA-256 `f90959fb089e5014dd8772fde80316c263d1367bd63250818dee80a5ec667034`. The executable smoke uses JVM5.2.3, okio-jvm3.16.4, and kotlin-stdlib2.2.20.

For app test groups that do not execute any OkHttp call, explicitly preflight from the actual dependency loader before running tests, without adding a compile dependency on the helper:

```java
Class.forName("guard.bootstrap.OfflineGuard", false, null)
    .getMethod("verifyOkHttp", ClassLoader.class)
    .invoke(null, okhttp3.OkHttpClient.class.getClassLoader());
```

Installed evidence in required mode means the JDK probes and lazy transformer are ready, not that OkHttp classes are already verified. The install sidecar adds `okhttpRequired:true`. Shutdown evidence must additionally show `okhttpComplete:true`, positive `okhttpVerifiedLoaders`, `okhttpVerifiedHooks == 5 * okhttpVerifiedLoaders`, and zero `okhttpTransformFailures` and `okhttpVerificationFailures`. Required mode with no verified loader is incomplete. A missing-target loader poisons completeness even if another independent loader later verifies. Retain and check the `okhttp-verified` and `okhttp-shutdown` markers alongside the paired process sidecars. A failed or absent report is never zero/complete.

Deliberate test-only behavior: a denied original enqueue destination throws OfflineGuard.Blocked synchronously before enqueue dispatch. An allowed loopback call that redirects remotely reports Blocked through its ordinary async failure callback. This should not be mistaken for production callback semantics.

External-staging verification, after build.sh and run-smoke.sh:

```sh
export OKHTTP_LIB_DIR=/absolute/path/to/verified/dependency-jars
./run-okhttp-smoke.sh > reports/okhttp-smoke.txt 2>&1
python3 run-okhttp-fail-closed.py
python3 run-okhttp-evidence.py
```

The OkHttp smoke deliberately loads dependencies in a later URLClassLoader: 99 checks, 18 blocked attempts, zero external DNS/socket tripwire calls, eight loopback fixture hits. It covers copied clients/newCall, cloned calls, execute/enqueue, HTTP/HTTPS external destinations, 302/307 internal redirects, app/network interceptor rewrites, unresolved/resolved remote proxies, proxy selection, local proxy fixtures, and external custom DNS results. Eight startup/preflight/first-call controls include valid preflight, missing all/one target, mutated method shape, and malformed property modes. Two-loader concurrent verification and failed-then-good-loader evidence are checked separately. Existing JDK smoke (52 checks, 100 blocks), five fail-before-main controls and concurrent100/0 attribution remain unchanged.

The extension still does not provide universal network isolation. Raw sockets/datagrams, java.net.http.HttpClient, native/device/emulator/other-process traffic, local proxy relays, malicious callbacks or agents, and unsupported transports remain excluded. Keep independent tripwires in negative tests and reject incomplete evidence before attributing a worker as guarded.
