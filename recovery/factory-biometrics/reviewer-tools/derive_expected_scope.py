#!/usr/bin/env python3
"""Read immutable Git trees to derive v80 expected APK scope; never inspect build outputs.
No Gradle, downloads, repository writes, observed-difference policy or APK approval.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess

BASELINE = '5c177d32cfc1b13209a54131753f98cec3582d42'
CANDIDATE_APP_TREE = 'ff2791d1e7c808d2edc4f9fa2902b3c6eb9637d8'
GUIDANCE = 'app/src/main/assets/skills/com.jarvys.apk-factory/'
HOST_ASSETS = 'app/src/main/assets/'
SDK = 'apk-runtime/src/main/assets/factory-sdk.js'


def sha(data): return hashlib.sha256(data).hexdigest()


def derive(repo):
    def git(*args): return subprocess.check_output(['git', '-C', str(repo), *args])
    before_tree = git('rev-parse', BASELINE + ':app').decode().strip()
    assert git('cat-file', '-t', CANDIDATE_APP_TREE).strip() == b'tree'
    def inventory(tree):
        out = {}
        for entry in git('ls-tree', '-rz', tree).split(b'\0'):
            if not entry: continue
            metadata, path = entry.split(b'\t', 1)
            mode, kind, oid = metadata.decode().split()
            if kind == 'blob': out[path.decode()] = oid
        return out
    before, after = inventory(before_tree), inventory(CANDIDATE_APP_TREE)
    def data(tree, path): return git('show', tree + ':' + path)
    def record(path):
        out = {'sourcePathUnderAppTree': path}
        for label, tree, index in [('before', before_tree, before), ('after', CANDIDATE_APP_TREE, after)]:
            raw = data(tree, path) if path in index else None
            out[label] = None if raw is None else {'gitBlob': index[path], 'sha256': sha(raw), 'bytes': len(raw)}
        return out
    changed = sorted(p for p in before.keys() | after.keys() if before.get(p) != after.get(p))
    host_changed = [p for p in changed if p.startswith(HOST_ASSETS)]
    expected_guidance = sorted([GUIDANCE + 'SKILL.md', GUIDANCE + 'references/manifest.json'] +
                              [GUIDANCE + 'references/' + n + '.md' for n in ('basics', 'biometrics', 'contacts-calendar', 'database', 'documents-media', 'external-actions', 'lifecycle', 'presentation', 'sensors')])
    assert host_changed == expected_guidance, 'Unexpected host asset source delta'
    guidance = []
    for path in host_changed:
        row = record(path); row['hostApkEntry'] = 'assets/' + path.removeprefix(HOST_ASSETS)
        guidance.append(row)
    manifest = json.loads(data(CANDIDATE_APP_TREE, GUIDANCE + 'references/manifest.json'))
    assert set(manifest) == {'version', 'sha256'} and manifest['version'] == 'factory-guidance-v80'
    raw_core = data(CANDIDATE_APP_TREE, GUIDANCE + 'SKILL.md')
    # Mirror SkillMarkdownParser.parse source normalization/body extraction;
    # SkillRepository passes parsed.body, not the raw frontmatter-bearing file, to the 8 KiB budget.
    source = raw_core.decode('utf-8').removeprefix('\ufeff').replace('\r\n', '\n').replace('\r', '\n')
    lines = source.split('\n')
    assert lines[0] == '---'
    closing = next(n for n in range(1, len(lines)) if lines[n] == '---')
    core = '\n'.join(lines[closing + 1:]).strip().encode('utf-8')
    assert len(raw_core) <= 256 * 1024  # SkillMarkdownParser.MAX_DOCUMENT_BYTES.
    assert b'Guidance version: factory-guidance-v80.' in core and len(core) <= 8192
    total = 0
    for name, expected in manifest['sha256'].items():
        value = data(CANDIDATE_APP_TREE, GUIDANCE + 'references/' + name + '.md'); total += len(value)
        assert sha(value) == expected and len(value) <= 16384
        assert value.startswith(('# Factory guidance factory-guidance-v80 / ' + name + '\n').encode())
        assert ('- ' + name + ':').encode() in core
    assert len(manifest['sha256']) == 9 and total <= 65536
    def domain(name, select):
        a = {p: v for p, v in before.items() if select(p)}; b = {p: v for p, v in after.items() if select(p)}
        assert a == b, 'Unexpected source change in ' + name
        return {'unchanged': True, 'files': len(a),
                'sourceIndexSha256': sha(json.dumps(a, sort_keys=True, separators=(',', ':')).encode()),
                'gitBlobIndex': a}
    domains = {
        'manifests': domain('manifests', lambda p: p.endswith('/AndroidManifest.xml')),
        'resources': domain('resources', lambda p: re.search(r'/src/(main|full|play|debug|release)/res/', p) is not None),
        'nativeLibraries': domain('native libraries', lambda p: '/jniLibs/' in p),
        'otherHostAssets': domain('other host assets', lambda p: p.startswith(HOST_ASSETS) and p not in host_changed),
        'otherRuntimeAssets': domain('other runtime assets', lambda p: p.startswith('apk-runtime/src/main/assets/') and p != SDK),
        'otherBuildConfiguration': domain('other build configuration', lambda p: p != 'app/build.gradle.kts' and (p.endswith('.gradle.kts') or p == 'gradle.properties' or p.startswith('gradle/'))),
    }
    for domain_name in ('manifests', 'nativeLibraries', 'otherHostAssets', 'otherRuntimeAssets'):
        domains[domain_name]['sourceHashes'] = [record(p) for p in domains[domain_name]['gitBlobIndex']]
    build_before = data(before_tree, 'app/build.gradle.kts').decode()
    build_after = data(CANDIDATE_APP_TREE, 'app/build.gradle.kts').decode()
    assert build_before.replace('versionCode = 79', 'versionCode = 80').replace('1.2.72-FACTORY-SENSORS', '1.2.73-FACTORY-BIOMETRICS') == build_after
    manifest_source = data(CANDIDATE_APP_TREE, 'app/src/main/AndroidManifest.xml')
    runtime_manifest = data(CANDIDATE_APP_TREE, 'apk-runtime/src/main/AndroidManifest.xml')
    assert b'USE_BIOMETRIC' not in manifest_source and b'USE_BIOMETRIC' not in runtime_manifest
    kotlin = data(CANDIDATE_APP_TREE, 'app/src/main/java/com/jarvys/agent/skills/FactoryGuidanceBundle.kt').decode()
    assert 'factory-guidance-v80' in kotlin and '"biometrics"' in kotlin
    catalog_match = re.search(r'val NAMES:.*?listOf\((.*?)\)\)', kotlin, re.S)
    assert catalog_match and set(re.findall(r'"([^"]+)"', catalog_match[1])) == set(manifest['sha256'])
    assert b'id("com.android.application") version "8.13.2"' in data(CANDIDATE_APP_TREE, 'build.gradle.kts')
    sdk = record(SDK); sdk['hostLocation'] = 'assets/apk_factory/template.apk!/assets/factory-sdk.js'
    prod_code = [p for p in changed if '/src/main/' in p and p.endswith(('.java', '.kt'))]
    return {
        'format': 'source-derived-v80-host-scope-v1', 'artifactsVerified': False,
        'baselineCommit': BASELINE, 'baselineAppTree': before_tree, 'candidateAppTree': CANDIDATE_APP_TREE,
        'scope': 'Immutable Git source derivation only. Not an apk_surface.py change policy and not candidate artifact verification.',
        'comparisonConstraint': 'Compare the same distribution (full/play), build type (debug/release), recoveryTestBuild identity, ABI selection and toolchain/dependency inputs.',
        'hostVersion': {'before': {'versionCode': 79, 'versionName': '1.2.72-FACTORY-SENSORS'},
                        'after': {'versionCode': 80, 'versionName': '1.2.73-FACTORY-BIOMETRICS'},
                        'recoveryTestSuffix': '-test', 'applicationIdUnchanged': True,
                        'buildConfigurationOnlyVersionLinesChanged': True,
                        'compiledManifestExpectation': 'All semantic manifest nodes, namespaces, attributes/resource IDs/types remain identical except root android:versionCode/versionName values. Permissions/components/authorities/queries/minSdk/targetSdk remain identical.'},
        'changedGuidanceAssets': guidance,
        'guidanceChecks': {'version': manifest['version'], 'rawSkillFileBytes': len(raw_core),
                           'parsedCoreContentBytes': len(core), 'parsedCoreSha256': sha(core),
                           'coreBudgetBytes': 8192, 'coreBudgetAppliesTo': 'SkillMarkdownParser.parse(SKILL.md).body passed to FactoryGuidanceBundle.load by SkillRepository',
                           'coreBudgetPassed': len(core) <= 8192, 'referenceBytes': total,
                           'parserSource': record('app/src/main/java/com/jarvys/agent/skills/SkillMarkdownParser.kt'),
                           'loaderCallsiteSource': record('app/src/main/java/com/jarvys/agent/skills/SkillRepository.kt'),
                           'references': len(manifest['sha256']), 'manifestHashesMatchSource': True,
                           'loaderSource': record('app/src/main/java/com/jarvys/agent/skills/FactoryGuidanceBundle.kt')},
        'generatedTemplateBindings': {
            'hostEntries': ['assets/apk_factory/template.apk', 'assets/apk_factory/template.sha256'],
            'producer': 'app/app/build.gradle.kts:115-129 prepareApkFactoryTemplate',
            'producerInput': 'app/apk-runtime/build/outputs/apk/release/apk-runtime-release-unsigned.apk',
            'requirements': ['Embedded template bytes equal the exact fresh reviewed unsigned apk-runtime release output.',
                             'template.sha256 bytes equal lowercase hexadecimal SHA-256(embedded template bytes) followed by exactly one LF.',
                             'Embedded SDK asset bytes equal candidate source SDK hash recorded below.',
                             'Embedded template manifest remains permissionless and otherwise unchanged; conditional USE_BIOMETRIC belongs only to generated biometrics-capable application profiles.',
                             'Embedded template resources, native libraries and every non-SDK asset remain unchanged against the matched baseline template.'],
            'consumer': 'app/app/src/main/java/com/jarvys/agent/coding/FactoryProjectService.kt:38-43',
            'templateSha256': None, 'reasonHashPending': 'Requires the final fresh build output; cannot be calculated from sources alone.'},
        'sdkBinding': sdk,
        'generatedProfiles': {'exactCandidateEntryNames': ['assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'],
                              'agpVersion': '8.13.2', 'sourceProfileFiles': [p for p in after if p.endswith(('.prof', '.profm')) or 'baseline-prof' in p],
                              'requirements': ['No broad assets/dexopt exemption.', 'Any changed profile entry must exactly match independently identified same-variant AGP output, with recorded SHA-256 and DEX binding where available.', 'Source-only analysis does not establish expected profile bytes or authorize profile additions/removals.'],
                              'candidateHashes': None},
        'unchangedSourceDomains': domains,
        'compiledSurfaceRequirements': ['Every host permissions declaration unchanged; do not add USE_BIOMETRIC or USE_FINGERPRINT to the host.',
                                        'resources.arsc and all res/ entry payloads unchanged for matched variants.',
                                        'All lib/ entries unchanged, including dependency libraries; source JNI equality alone does not prove packaged/stripped-library equality.',
                                        'All APK entries outside exact source-backed guidance changes, template/hash, separately reviewed DEX/BuildConfig/manifest/profile changes remain unchanged.',
                                        'No top-level assets/factory-sdk.js is introduced in the host.',
                                        'Signing-block/certificate checks are separate; no META-INF wildcard exclusion.'],
        'changedProductionCodeSources': [record(p) for p in prod_code],
        'dexReviewRequirements': ['New biometric implementation and exact changed source families require symbolic compiled control-flow review.',
                                  'Generated com.jarvys.agent.BuildConfig version constants are expected to change.',
                                  'D8 ordinal shifts must be justified by exact method/class proofs; no package-wide allowlist.'],
        'changedSourcePaths': changed,
    }


def markdown(report):
    rows = report['changedGuidanceAssets']; g = report['guidanceChecks']; d = report['unchangedSourceDomains']
    out = ['# Source-derived v80 host APK scope', '',
           'Status: source expectations only; final candidate APK/template/profile bytes are not verified.', '',
           'Baseline commit: ' + report['baselineCommit'],
           'Baseline app tree: ' + report['baselineAppTree'],
           'Candidate app tree: ' + report['candidateAppTree'] + ' (a Git tree, not a commit).', '',
           'Compare identical full/play, debug/release, recovery-test identity and build inputs.', '',
           '## Expected changes', '',
           '1. Host AndroidManifest.xml: only root versionCode 79 → 80 and versionName 1.2.72-FACTORY-SENSORS → 1.2.73-FACTORY-BIOMETRICS; append -test on both sides for recovery builds. No permission/component/authority/query/SDK change.',
           '2. Exactly 11 source-owned guidance ZIP entries: nine references (biometrics is new), SKILL.md, and references/manifest.json. Payloads must match the hashes below.',
           '3. assets/apk_factory/template.apk must equal the exact fresh reviewed unsigned apk-runtime release APK. assets/apk_factory/template.sha256 must be its lowercase SHA-256 plus one LF. These generated hashes remain pending.',
           '4. SDK changes only inside the embedded template at assets/factory-sdk.js. No new top-level host SDK asset is expected.',
           '5. DEX changes require independent symbolic review of the source families listed in JSON, including generated BuildConfig version constants. Ordinal noise is never a package exemption.',
           '6. Only assets/dexopt/baseline.prof and assets/dexopt/baseline.profm are recognized profile names for a later exact same-variant AGP-output comparison. Their bytes and any addition/removal cannot be approved from source alone. AGP remains 8.13.2.', '',
           '## Exact candidate guidance payload hashes', '']
    for row in rows:
        relative = row['hostApkEntry'].removeprefix('assets/skills/com.jarvys.apk-factory/')
        out.append('- ' + relative + ': ' + row['after']['sha256'] + (' (new)' if row['before'] is None else ''))
    out += ['', f'Guidance self-check: version {g["version"]}; {g["rawSkillFileBytes"]} raw SKILL.md file bytes; {g["parsedCoreContentBytes"]} parsed core-content bytes (the 8,192-byte core budget applies to this parsed body); {g["referenceBytes"]} bytes across nine references. All manifest hashes, header/version/catalog references and byte budgets match source.', '',
            'SDK candidate SHA-256: ' + report['sdkBinding']['after']['sha256'],
            'SDK baseline SHA-256: ' + report['sdkBinding']['before']['sha256'], '',
            '## Required unchanged surfaces', '',
            f'- All {d["manifests"]["files"]} source manifests are byte-identical. Host and bare template gain no biometric permission; the runtime-core manifest remains empty. Generated biometric-capable application profiles are a separate declaration audit.',
            f'- All {d["resources"]["files"]} production resource sources and {d["nativeLibraries"]["files"]} checked-in JNI libraries are byte-identical. Require exact compiled resources.arsc/res/ and lib/ payload parity for matched variants, including dependency libraries.',
            f'- All {d["otherHostAssets"]["files"]} other host source assets and {d["otherRuntimeAssets"]["files"]} non-SDK runtime assets are byte-identical.',
            '- Dependencies, module build settings and packaging are unchanged. The host build script differs only in its two version lines.',
            '- Every other host ZIP-entry payload remains unchanged; signing evidence is separate, with no META-INF wildcard.', '',
            'Full before/after source hashes, immutable Git blob evidence, production-code scope and generated-output binding requirements: source-derived-v80-host-scope.json.', '',
            'No Gradle, signing, generated-artifact payload inspection, repository writes or observed-diff allowlist generation was performed.']
    return '\n'.join(out) + '\n'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo', required=True, type=pathlib.Path); p.add_argument('--output-directory', required=True, type=pathlib.Path)
    args = p.parse_args(); result = derive(args.repo); args.output_directory.mkdir(parents=True, exist_ok=True)
    for name, content in [('source-derived-v80-host-scope.json', json.dumps(result, indent=2, sort_keys=True) + '\n'),
                          ('SOURCE-DERIVED-v80-HOST-SCOPE.md', markdown(result))]:
        (args.output_directory / name).write_text(content)
    print(json.dumps({'sourceDerived': True, 'artifactsVerified': False, 'guidanceChanges': len(result['changedGuidanceAssets']),
                      'unchangedDomains': {k: v['files'] for k, v in result['unchangedSourceDomains'].items()},
                      'outputDirectory': str(args.output_directory)}))


if __name__ == '__main__': main()
