# Factory capability matrix: compositional wire validation

This is test infrastructure, not an application feature. Production sources, dependency
versions, assets, version code and delivered v80 APK bytes are unchanged. No new APK is built.

## Coverage and proof

The former bridge matrix called the same 42 valid and 42 invalid JSON requests for every one
of 1,048,576 capability subsets. Each Debug/Release instance made 88,080,384 direct calls.
The revised matrix retains all subsets, all real `FactoryConfig.parsePreview` calls and all
88,080,384 expected-outcome assertions per instance. It additionally checks the parsed set's
size and every membership bit. Other manifest/packager/dispatcher exhaustive matrices and all
adversarial bridge tests are unchanged.

Repeated direct validation is replaced by an exhaustive compositional proof, not sampling:

1. For each unchanged wire fixture, execute the real `BridgeProtocol.validate` with a test-only
   capability membership oracle. The existing private constructor wraps it in an unmodifiable
   set. All other configuration values match the original parsed matrix. No final field is
   changed, and production code has no test hook.
2. When a membership answer is unknown, interrupt that execution with a private `Error` and
   recursively execute both answers. Known answers remain fixed along each path; repeated
   reads are consistent. Unsupported set iteration/size and unknown capabilities fail.
3. Store the actual operation identity or `FactoryException.code` at every reachable leaf.
   The reviewed fixtures produce 87 valid leaves and 42 invalid leaves, using 174 genuine
   wire executions including interrupted discovery calls. All invalid-extra-field requests
   fail before capability membership. Short-circuit conjunctions have three reachable leaves.
4. Enumerate every real parsed subset and select each tree's outcome by actual parsed set
   membership. Independently assert the same catalog identity / CAPABILITY_DENIED predicate
   and INVALID_ARGUMENT precedence as before. Exact subset/assertion totals are asserted.

Soundness depends on deterministic, side-effect-free validators, capability dependence only
through membership, no config identity inspection/escape, and no hidden capability-derived
constructor state. These were reviewed in the frozen production sources. Runtime Set checks
alone cannot prove these properties. Therefore the full validator/helper source perimeter,
contract sources and dependency declarations are SHA-256 pinned by the test. A missing or
changed file fails closed. Source pins are review gates, not an automatically refreshed cache.
The host gate must compile from the same frozen sources it verifies; source hashes alone are
not a claim about an arbitrary externally supplied classpath.

## Updating the proof for a capability or validator change

Do not update hashes just to make tests pass. Independently review the changed source and
its transitive helpers for the properties above; extend the pinned perimeter if a new helper
or dependency is introduced. Verify the new fixture list equals the entire method catalog,
retain all subset enumeration, recompute reachable paths with the oracle, and explain changed
leaf/invocation counts. Keep direct positive/adversarial wire tests for the new behavior.
If validation gains unsupported state/config access, redesign the proof or restore direct
validation for the affected dependency; never silently reuse cached outcomes.

The helper's own tests cover short-circuit AND/OR, repeated queries, exhaustive three-input
parity, new dependencies, unknown capabilities, unsupported operations, exception propagation,
configuration wrapping, genuine wire-error precedence and missing/modified source pins.

## Evidence scope

Fresh guarded Debug/Release focused execution passed 24 cases each (17 original bridge tests
and seven new proof-helper tests), zero failures/errors/skips. Matrix JUnit durations were
38.437 s / 42.347 s; the prior v80 aggregate on this restored executor recorded 2,067.081 s /
2,112.006 s for those same cases. The new two-task run took 118.098 s wall time including
configuration/compilation/other focused tests. These measured case times come from different
execution contexts, not a repeated controlled benchmark or measured whole-build speedup.
Two fresh Test JVMs retained the reviewed offline guard and 1,280 MiB heap, with paired
installation/shutdown receipts and no blocked attempts in this focused run. Independent
review approved the proof, final source, both test variants and retained APK/delivery bindings.

Fresh `apk-runtime:lintReportRelease` (including main and unit-test analysis) completed in
57.062 s. Its five complete diagnostics exactly match v80; none added, removed or suppressed.
All 66 frozen metadata/version-marker files across the original 19 task caches remain exact
before and after. The original metadata preflight rejected two pre-existing FullRelease vital
caches from the final v80 APK build; a reviewed read-only inventory check explicitly preserves
both markers and two empty metadata directories. Nothing was deleted or silently excluded.
The prior full v80 aggregate is historical evidence for unchanged inputs, not a newly run
full aggregate. Runtime unit-test lint was affected and was checked freshly. APK/signature,
SDK and unchanged module evidence may be reused only with exact source/input identity.


## Reuse and recovery

Of 1,123 original v80 app inputs, only `BridgeProtocolTest.java` changed; all other 1,122 are
byte-identical. Two test-only helper sources were added. Existing production build inputs,
SDK assets and the three independently approved APKs, signed delivery and independently
retrieved delivery bytes remain unchanged. v80 remains the application version; this cut
closes no additional Factory capability or physical-device acceptance gate.

`evidence-summary.json` preserves the sanitized case timings and boundaries; no raw logs,
APK binaries, credentials or cache payloads are committed. The source-only independent
review scripts are in `reviewer-tools/`; their expected restoration location is the validation
workspace's `FACTORY-MATRIX-validation/independent/`, alongside the existing v80 evidence.
Their assertions require the actual frozen source/JUnit/guard/lint artifacts and cannot turn
missing historical evidence into a fresh pass.
