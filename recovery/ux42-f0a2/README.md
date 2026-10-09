# UX42 F0a-2: typed construction foundation

This bounded slice adds compiled-code, immutable manifest-node constructors and a deterministic AXML encoder. It is not the complete selective-manifest rollout or Runtime2. No new schema, SDK, permission, component, capability or native handler is exposed by the project specification. `ManifestPlan` still rejects every nonempty catalog contribution; the six implemented capabilities all contribute nothing and preserve the zero-permission launcher.

## Construction vocabulary

- Standard and API-23 permission declarations, with optional API-24–36 maximum.
- Named Android hardware features with an explicit required boolean.
- Private activity/service/receiver declarations and private providers. Exported is always false for these constructors. Provider authorities derive from the application ID plus a bounded suffix; URI grants are explicit, not implicit.
- Intent actions/categories and bounded MIME/scheme data; no arbitrary URI, component, extras, flags, host/path pattern or XML injection.
- Package and intent queries. Provider queries, arbitrary Android software features, foreground-service types and additional intent-data shapes remain unsupported.
- String/integer/boolean/resource metadata. Resource references are construction data, not proof of an existing XML/type/name binding. Activating a future catalog contribution requires auditing the actual compiled resources and implementing its handler, policy and acceptance gates first.

The additional nodes are exercised only as non-installable host construction fixtures. They do not attest that the referenced fixture component classes or resources implement any functionality. Nothing installs, grants hardware access, signs with a new identity, calls another app, or contacts the network.

## Invariants

The production encoder accepts the existing derived plan. Project JSON and JavaScript have no node-construction or catalog-registration API. The old base-only production parser remains a separate ceiling check; the independent read-only auditor compares parent identity and every typed attribute. Repeated sibling paths are represented without conflating parents; lookup by an ambiguous path fails. The writer reserves Android attribute-name string slots separately from ordinary strings to prevent resource-map/name collisions, emits canonical booleans, and bounds the complete tree/string/attribute output.

Existing resource-package/icon/backup verification, complete DEX name/hash/byte preservation, authenticated templates, historical receipt profiles and unchanged signed ZIP payload checks remain required. Encoding changes manifest bytes deterministically; it does not change DEX between the authenticated template and each generated app. Updating shared compiled code changes the template's own DEX and does not update previously generated apps.

## Evidence

Run the focused `ManifestNodesTest` and `TemplateApkTest`, then the fresh Full/Play and runtime Debug/Release aggregates with the reviewed per-worker offline guard. The fixture output directory is selected by the host-only `jarvys.factory.evidenceDir` JVM property. Independently inspect construction AXML using `verify_manifest_nodes.py` and official SDK aapt2; additionally retain generated APK/signature/payload evidence from `../ux42-f0a1/verify_factory_apks.py`.

Compare fresh lint diagnostic multisets with the prior release rather than suppressing inherited debt. Build Full/Play unsigned debug and Full unsigned release, review the actual artifact contents, then prepare the separately authorized existing D7 ARM64 test delivery. Host checks are not physical Android, Keystore, WebView, installation/update, permission or resource-handler acceptance.

UX34 later phases retain their separate gates. UX43 remains last. F0a integration of effective new contributions, resource bindings and richer approval presentation, F0b/F0c/F1–F3, and all physical acceptance remain open.
