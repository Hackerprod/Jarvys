# Rebuilt independent APK/DEX helpers

Reconstructed from scratch on 2026-10-10 after the executor reset. The former external `previous_audit_helpers.py` and `v79_apk_common.py` were unavailable and were not reused. Only files in this `independent` validation directory were written. No production changes, Gradle commands, network installation, signing, Android execution, or real biometric activity were performed.

## Deliverables

- `symbolic_dex.py`: read-only standard-library DEX/APK extraction, complete symbolic inventories, conservative ordinal reconciliation and full changed-method reports.
- `apk_surface.py`: read-only ZIP/AXML inventory and exact per-entry before/after SHA-256 change policy, with independent manifest/permission/resource/native-library checks.
- `rebuild_probes.py`, `SymbolicProbe.java`, `ProbeManifest.xml`: source-only fixture regeneration, with pinned local tool paths and generated provenance.
- `test_symbolic_dex.py`, `test_apk_surface.py`: 42 fixture-backed controls; `test_ordinal_finalizer.py`: 12 standalone synthetic mapping-collision controls.
- `SymbolicProbe.java`, `ProbeManifest.xml`, `probe-classes/`, `probe-dex/`, `probe.dexdump.txt`, `probe-v1.apk`, `probe-v2.apk`: synthetic audit inputs built only with the restored pinned JDK, D8 and aapt2. These APKs are unsigned test inputs, not delivery artifacts.

## Source-only recovery after an executor reset

Durable source backups should contain exactly these implementation/input files (plus this README and the checksum list):

- symbolic_dex.py
- apk_surface.py
- rebuild_probes.py
- test_symbolic_dex.py
- test_apk_surface.py
- test_ordinal_finalizer.py
- SymbolicProbe.java
- ProbeManifest.xml

Do not include probe-classes, probe-dex, any rebuilt-probes directory, *.class, *.dex, *.apk, or probe.dexdump.txt in source backups. Logs and JSON reports are optional validation evidence, not source inputs. `helper-source-sha256.txt` records the complete source/input/document list and hashes.

Once the pinned toolchain has been restored, from this directory run:

```sh
python3 rebuild_probes.py \
  --toolchain /workspace/scratch/2ac40fb4b2af/Jarvys-recovery/toolchain \
  --output rebuilt-probes
JARVYS_AUDIT_FIXTURES="$PWD/rebuilt-probes" \
  python3 -m unittest -v test_symbolic_dex test_apk_surface test_ordinal_finalizer
```

The output must be new or empty; the generator does not overwrite old fixtures. It performs no downloads and requires these already-present files below --toolchain:

- jdk-21.0.12.1+1/bin/java and bin/javac
- android-sdk/build-tools/35.0.0/d8, lib/d8.jar, dexdump and aapt2
- android-sdk/platforms/android-36/android.jar

If restored elsewhere, --jdk, --build-tools and --android-jar override those exact paths individually. The generator records all source/tool SHA-256 hashes, commands and generated output hashes in probe-provenance.json. Its probe-build.log contains command output. Neither file is required to reproduce the fixture inputs. DEX, APK and class bytes rebuilt in the separate rebuilt-probes-portability directory matched the original fixtures byte-for-byte; all 42 tests passed against that separate directory (portability-tests.log). The dexdump text naturally includes its input file path, which is ignored by symbolic parsing.

## DEX use

```sh
TOOLS=/workspace/scratch/2ac40fb4b2af/Jarvys-recovery/toolchain/android-sdk/build-tools/35.0.0
python3 symbolic_dex.py extract BEFORE.apk --dexdump "$TOOLS/dexdump" --output before.symbolic.json
python3 symbolic_dex.py extract AFTER.apk --dexdump "$TOOLS/dexdump" --output after.symbolic.json
python3 symbolic_dex.py compare before.symbolic.json after.symbolic.json --output semantic-delta.json
```

`extract` accepts a raw standard DEX or an APK containing root classes.dex/classes2.dex/etc. APKs must contain a contiguous canonical root classes.dex/classes2.dex/... set. Any extra, nested, differently cased, malformed or gapped .dex name fails closed, including a missing primary classes.dex. Every class/method is retained; multidex placement is ignored but class duplicates are rejected. All extraction scratch files are created and removed under this helper's own directory. Reports are written only to the explicitly supplied output location. `compare` exits 2 if semantic deltas remain or either inventory lacks complete binary metadata. A changed comparison is expected for v79→v80 and must be reviewed; exit 2 does not mean the application is incorrect.

DEX binary parsing independently verifies SHA-1/Adler-32, type/prototype/member tables, declared access flags, superclasses, interfaces, static values, annotations, method parameters' annotations and complete abbreviated payloads. DEX versions 035/037/038/039/040 are supported; unsupported formats fail rather than silently disappearing. build-tools dexdump -d supplies instruction operands. DEX table indices and absolute offsets are removed. Relative branch, switch and catch destinations become instruction ordinal labels. Registers, access flags, method kind, prototypes, literal values, ordered instructions, catches and control flow remain. Array bytes and every packed/sparse switch key/target are decoded independently. Actual DEX const-string values are read directly, including CESU-8/modified-UTF-8 Unicode, instead of trusting dexdump's display rendering. Only const-string/jumbo width is canonicalized. Other compiler changes, such as trampolines or nop alignment, remain deltas.

Ordinal reconciliation is limited to access$<digits>, lambda$<same family>$<digits>, owner$<digits> anonymous classes and owner$$ExternalSyntheticLambda/Backport/Outline<digits>. Method candidates on both sides must carry ACC_SYNTHETIC; a manually named access$/lambda$ method is never eligible. A mapping needs a unique exact same-family body+prototype+access match (methods), or exact complete class content after replacing its own descriptor (classes). Previously proven symbol mappings can unlock additional exact matches. All proofs are rechecked after final symbol translation. The mapping is atomic, including ordinal permutations. String literals and encoded string values are never renamed. Ambiguous matches remain deltas. There is no package/class allowlist and no exception based on a class merely looking generated.

Candidate correspondences must also be globally injective over the complete old inventory, including entries that retain their original names. The finalizer repeatedly rejects moves into still-occupied class/method names and any dependent move whose final exact-body proof no longer holds. It records every rejected candidate and its original local proof under `ordinalMappingRejections`; rejection leaves the original entries visible as genuine deltas. Closed permutations and exact chains into unused names remain eligible. Class/method inventory cardinality and complete proof accounting are checked. An unresolved prototype-only collision without an attributable moved method remains a hard failure. This narrows accepted correspondences and does not expand the eligible name families.

The report includes raw class delta counts, every mapping and its exact-content proof digest, every added/removed class in full, class metadata changes, and every added/removed/changed method with complete before/after bodies and unified diffs. The comparator does not interpret deltas as approved security behavior.

`parse-dump DUMP --dex INPUT.dex --output RESULT.json` permits saved text inspection. Without --dex it is explicitly partial; payloads fail closed and a partial-only comparison cannot pass the CLI's complete semantic audit status.

## APK surface use

```sh
python3 apk_surface.py inventory BEFORE.apk --output before.surface.json
python3 apk_surface.py inventory AFTER.apk --output after.surface.json
python3 apk_surface.py compare before.surface.json after.surface.json \
  --ignore-host-version --policy exact-entry-policy.json --output surface-delta.json
```

The optional policy is an object keyed by an exact APK entry name. Each value must be exactly `{"beforeSha256":"...","afterSha256":"..."}`; use JSON null on a missing side for a deliberate added/removed entry. No wildcard is supported. Wrong or unused policy entries prevent a pass. Do not generate a policy from all observed differences and treat that as independent expected-scope verification: derive each expected path and exact hash from reviewed source/build evidence. DEX entry hashes, embedded APK/template/hash assets, SDK assets, guidance entries, AndroidManifest.xml, and AGP baseline.prof/profm changes all need explicit separate justification. No signing metadata is silently excluded.

`--ignore-host-version` changes only root android:versionCode/versionName values to a common placeholder. Their existence, types, Android resource IDs and every other manifest attribute/node/namespace remain checked; original version attributes appear in the report. Permission nodes, resources.arsc/all res entries and all lib entries are independently required unchanged even if their ZIP-entry hashes were listed in a policy. APK changes may be reviewed with a policy, but no action or release permission is conferred by this tool.

## Tests and scope

```sh
python3 -m unittest -v test_symbolic_dex test_apk_surface test_ordinal_finalizer
```

The real D8 probe covers sparse and packed switches, a 12-element fill-array payload, exception handling, generated lambda class/method, runtime annotation/static value metadata, and a non-BMP Unicode string. Tests additionally cover offset/index/debug-only changes; changed branches, masks and authentication type constants; checksums; method prototypes/access; exact accessor permutations; anonymous/lambda class renumbering; ambiguous bodies; literal protection; unknown opcodes/references; and complete negative APK/version/permission/resource/native/profile controls, rejected noncanonical/gapped DEX archives, and nonsynthetic access$/lambda$ name changes.

Static host evidence only. This does not verify execution, real authentication, API availability, cancellation effectiveness, lifecycle/authority security, signature trust, installation or device acceptance. Compiled strong-mask 15, authentication type 2, non-CryptoObject authenticate overload, API-30 gating, cancel/poison behavior, lifecycle and authority control flow must still be reviewed in the actual extracted biometric methods. Pre-reset counts and suspected D8 noise are historical hypotheses, not inputs to these helpers' pass criteria.
