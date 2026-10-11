# Factory packaged local UI v81: host-validated checkpoint

Version 81 / `1.2.74-FACTORY-LOCAL-UI` implements fixed-inclusion Pure.css 3.0.0
resources. The reviewed source passed affected host validation and independent three-APK
audit. Signed ARM64 test APK delivery was accepted on 2026-10-11 at 05:19:24.403737 UTC.
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

## Retained preflight failure and rendering boundary

The first guarded focused run completed 144 tests with one failure: the new guidance
reference discovery exceeded the preexisting stricter 7,680-byte core-headroom assertion.
The 8 KiB loader ceiling was not exceeded. The original assertion was preserved; only
redundant discovery wording was compacted, keeping all family references and biometric
boundaries. Corrected parsed core is 7,676 bytes. Three guarded JVMs completed with paired
receipts. This failed run is retained separately and is not release evidence.

The shipped-example test now exports its public synthetic APK through the existing opt-in,
bounded, readback-verified test evidence helper. Full/Play use distinct task directories.

A desktop Chromium smoke was attempted with its sandbox retained. Startup failed before
page load at process_singleton socket permission, including the approved escalation. No
rendering, screenshots or interaction checks passed. The exposed alternate CUA route targets
the user's computer and was not used. A local-only, exact-CSP harness and failure evidence
are retained externally; Android WebView/device compatibility remains unverified.

## Final host validation and artifact review

Frozen source `c0005a1e1f7decea8ba660dae805dea8bbc19ec9`, app tree
`d179b3f740d979c15e3c2b49c35b603a66d664fb`: all 1,140 inputs remained exact through
validation. Independent review approved the affected-test selection and reuse boundary.
Six named Factory integration/guidance classes ran freshly in both host flavors (137 each);
complete runtime Debug/Release suites passed 86 each and complete shared-core suites
passed 976 each. Total 2,398, zero failures/errors/skips; separate SDK tests passed 41/41.
Unrelated full host suites remain historical evidence, not freshly rerun in v81.

Six test JVMs produced paired guard receipts. The Java HTTP(S) guard recorded two blocked
attempts, one per host flavor; URL/test/cause attribution is unknown. This does not prove
all network paths were intercepted or zero egress. Existing heap/fork/worker/assertion
limits and all 23 fail-closed matrix source/build pins were retained without rehashing.

Four fresh lint reports exactly match the calibrated v80 reports, including full recursive
diagnostics and attributes: Full 321 / Play 311 / runtime 5 / core 4. No additions/removals
or suppression changes; this establishes equivalence, not a lint-clean project. All 66
controlled metadata files and the two previously documented cache markers remained exact.
The reviewed vital-task exclusion preserved the complete fresh runtime release analysis.

All three APK packaging tasks ran freshly and passed, using normal compile/resource cache
reuse, not a clean rebuild. Independent review verified archive-to-build bytes and the
explicit FullRelease producer ordering. The three binary audits covered complete class
inventories, every changed method/metadata item, manifest/resources/native payloads,
version/guidance, exact catalog and embedded template. Release alignment/goto encodings
were justified by finite method proofs, not blanket normalization. Exact unchanged DEX
reuse was bound to prior audited bytes; all changed DEX entries were parsed anew.
Release profile selections were verified semantically with exact producer/index/CRC binding.

| Unsigned APK | Bytes | SHA-256 |
| --- | ---: | --- |
| FullDebug | 35,307,327 | `2648fd135c3acea15be63590cd738319048b08f2869ea4b73175a718172c5e37` |
| PlayDebug | 33,695,549 | `239bb9e4ffadb649464e4d8d21e9d563a45ca634ae0e8286ab3158bd30e6962a` |
| FullRelease | 27,585,920 | `e3b9fe81a81b0fd691cbfef59b3eba1117ab0a93f14e4fe28d764a0c319c6757` |

The shipped local-UI example was built in both flavors. Identical example APKs
(5,418,922 bytes, SHA-256 `06d775b19b63343b184418f0e181fd355f1755aae3dcc02e1312177b7b60942e`)
passed static resource/link/catalog/provenance checks with zero capabilities/permissions
and byte-identical template DEX. This is packaging evidence, not a successful rendering test.
GitHub returned no workflow runs or commit statuses for the frozen source; no CI pass is claimed.
See [sanitized evidence summary](evidence-summary.json). Raw logs, generated APKs and local
executor paths are deliberately excluded from this repository receipt.

Strict Factory TTS remains blocked separately. Remaining F1/F2/F3, UX34 acceptance gates,
UX44 documentary work and UX43-last remain open; this checkpoint does not finish the Factory.

## Signed delivery receipt

The existing D7 test identity signed the approved FullRelease as ARM64-only. Native delivery
was accepted on 2026-10-11 at 05:19:24.403737 UTC: **19,385,510 bytes**, SHA-256
`539204b0f246a7b048516f17d519a04f2044b3fa23f6ef5c01876e319fd46128`.
An independent auditor verified v2/v3 signatures and the same D7 certificate, manifest
81 / `1.2.74-FACTORY-LOCAL-UI`, CRC, 16 KiB ZIP alignment and ARM64-only libraries.
All 255 retained ZIP entries preserve the approved unsigned bytes; exactly nine non-ARM64
libraries were removed and three signature entries added. Independently retrieved persisted
Library version 0 bytes match the signed APK exactly. This is a test APK, not production signing.
Delivery acceptance does not establish download, installation, update or phone behavior;
ZIP alignment does not prove ELF compatibility with 16 KiB pages. Rendering remains unverified.
