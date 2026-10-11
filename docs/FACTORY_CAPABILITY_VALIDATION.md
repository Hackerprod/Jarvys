# Factory capability validation scope

The current catalog has 33 capabilities and 134 methods. Enumerating every raw capability subset would require 8,589,934,592 configurations; an `int` shift is not a valid enumeration at this size. The current test design factors observable dependencies instead of silently reducing the global loop or using pairwise samples as proof.

## Bridge authorization

Each method has an explicit typed synthetic input and an independently declared primary capability and additional dependencies. A test-only membership oracle explores every membership query reachable from the actual validator. Unexpected capability queries fail. Each complete truth table is checked with irrelevant capabilities absent and present; valid configurations also pass the real configuration parser and wire validator. Impossible configuration selections are explicitly rejected by that parser and are not counted as successful wire checks.

Invalid extra-field inputs are separate cases. Dependency checks that precede argument parsing retain their actual error precedence: jobs and secret-backed HTTP check network first, document HTTP transfers check documents first. Bluetooth retains its typed INVALID_REQUEST parser result for unknown fields. The original 46-method corpus remains a historical typed-input regression.

This is exhaustive capability factoring for each fixed input fixture and immutable legacy resource-budget slice. It is not exhaustive over arbitrary argument values, numeric budgets, native Android effects or device behavior. Those require their separate parser, boundary, lifecycle and integration tests. Source pins cover the reviewed parser/configuration/dependency closure and fail on any change until independent review; updating a hash to silence a failure is prohibited.

## Manifest and resource selection

Historical profiles retain their explicit fixtures. Current selection tests exhaust the interacting dependency partition: permission unions are idempotent, broker selection is a Boolean OR with distinct speech query bits, and cross-family collisions include BLE/location and jobs/scheduled-notification boot handling. The relevant F3 Cartesian partition has 864 cases. Singleton, pairwise and full-set cases supplement this partition and are labelled sampled checks, not a substitute for the dependency proof.

Compiled AXML, resource-table identities, exact DEX bindings and generated APK inspection remain separate acceptance requirements. A static proof or passing host suite cannot establish that the final packaged template contains the expected resources or works on physical Android hardware.

## Development status

The dependency factorization and parser purity perimeter were independently reviewed for current source. Focused tests, final combined suites and exact artifact checks retain their own receipts. This document does not claim that an unrun final build, lint, APK audit or device test passed.
