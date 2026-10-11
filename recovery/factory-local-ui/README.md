# Factory packaged local UI v81: implementation checkpoint

Version 81 / `1.2.74-FACTORY-LOCAL-UI` implements fixed-inclusion Pure.css 3.0.0
resources. This checkpoint is not a validated release or delivered APK. Host test/lint,
artifact review and delivery evidence will be recorded only after the corresponding gates.
No Android WebView rendering, physical installation or OEM compatibility is claimed.

## Contract and supply chain

Two unmodified Pure distribution CSS files, full Pure BSD-3-Clause license, full embedded
normalize MIT notice and provenance are packaged as five exact runtime-owned resources.
The source is `app/factory-runtime-core/src/main/assets/factory-ui/pure-3.0.0/`.
Both host and template receive the same assets through their existing core dependency.
The generated APK retains its own bytes; no separately installed host supplies the library.
No capabilities, bridge methods, Android permissions, schema or SDK version were added.

Official npm package tarballs were retrieved as inert data. Their declared SHA-512 integrity
was verified before extraction; no lifecycle script, install, Grunt or compiler was executed.
- Pure: https://registry.npmjs.org/purecss/-/purecss-3.0.0.tgz
  SHA-256 `5528ad3bff7af7d1d67608ac5ee0ca7621da7aff7ce189acf3cd1c9a89d827d4`.
- Normalize notice: https://registry.npmjs.org/normalize.css/-/normalize.css-8.0.1.tgz
  SHA-256 `5cc172e0d5d062bc2a1a4bf85443e2eb260d3720e16527a0c1ac03b642f1de39`.

The actual Pure banner credits embedded normalize under MIT; the separately acquired
normalize package provides its complete notice. No additional normalize stylesheet loads.
Both selected CSS files were inspected without comments: no url(), @import, expressions,
behavior, scripts or source-map references. Copyright/provenance URLs inside preserved
comments do not create resource requests. Bundled PROVENANCE.json records exact extraction
members, archive integrity and file hashes, excluding itself. The compiled catalog also pins
PROVENANCE.json, resolving the self-hash problem. It does not claim a registry signature was
independently authenticated or a reproducible rebuild of upstream CSS was performed.

## Security and quotas

The finite catalog checks path, exact length and SHA-256. Template validation, post-build
validation and preview reject missing, changed, duplicate, unexpected and wrong-version UI
resources. Project replacement remains restricted to www/config/provenance; catalog files
cannot be overwritten through the Factory project. AssetPolicy accepts exact same-origin
catalog resources only, never an arbitrary prefix. Navigation stays limited to main-frame
www/*.html. CSP is unchanged, including no inline JS/eval/network/frames/workers.

Project web quotas remain 128 files / 1 MiB each / 8 MiB aggregate and the original scoped
path rules. Library overhead is separate and bounded to 32 KiB/file / 64 KiB aggregate.
Preview defensively copies bytes before validation. Its web quotas do not count library files.
No matrix source pins or validator/config/contract semantics were changed. Independent
pre-edit review approved this architecture and identified the strict catalog/extraction gates.

The usable example lives at `app/examples/factory-local-ui/`, with versioned CSS links,
responsive form/table/buttons and bounded session-only DOM state. Documentation and a
version/hash-bound Coding reference describe actual usage and limits. All preexisting
runtime capabilities and physical/device acceptance gates remain unchanged.
