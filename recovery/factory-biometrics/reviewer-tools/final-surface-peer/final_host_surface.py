#!/usr/bin/env python3
"""Source-driven v79/v80 *surface-scope only* audit; never a complete APK approval.

Read-only inputs. No Gradle, signing, installation, APK execution, hash-pair policy,
package allowlist or META-INF/profile wildcard. The CLI only writes under this
helper's directory. DEX/profile detailed audits remain explicitly outstanding.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
HELPERS = Path(os.environ.get('JARVYS_AUDIT_HELPERS', HERE.parent)).resolve()
sys.path.insert(0, str(HELPERS))
import apk_surface as axml
import derive_expected_scope as source_scope

APP_TREE = '119dac1fee793fa306656438447f491cad0a7cb0'
BASELINE_COMMIT = '5c177d32cfc1b13209a54131753f98cec3582d42'
PROFILES = ('assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm')
TEMPLATE = 'assets/apk_factory/template.apk'
TEMPLATE_SHA = 'assets/apk_factory/template.sha256'
SDK = 'assets/factory-sdk.js'
MANIFEST = 'AndroidManifest.xml'
VARIANTS = ('full-debug', 'play-debug', 'full-release')
DEX_RE = re.compile(r'classes(?:(?:[2-9]|[1-9][0-9]+))?\.dex\Z')
LIMITS = ('Surface-scope only. Does not approve DEX behavior, profile semantics, signing, '
          'source-to-binary build provenance/freshness, installation or biometric behavior. '
          'Producer binding identifies the exact on-disk output; build provenance requires separate evidence.')


def require(ok, why):
    if not ok:
        raise ValueError(why)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def payload_record(data):
    return {'sha256': sha(data), 'size': len(data)}


def read_apk(data, label):
    """Reuse the reviewed AXML parser; retain only source/binding payloads."""
    entries, retained = {}, {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate ZIP entries: ' + label)
        for info in archive.infolist():
            name = info.filename
            require(name and not name.startswith('/') and '\\' not in name and
                    all(p not in ('', '.', '..') for p in name.rstrip('/').split('/')),
                    'Noncanonical ZIP path: ' + name)
            require(not (info.flag_bits & 1), 'Encrypted ZIP entry: ' + name)
            body = archive.read(info)  # ZipFile verifies each CRC while reading.
            require(not info.is_dir() or not body, 'Nonempty directory entry: ' + name)
            entries[name] = payload_record(body)
            if name == MANIFEST or name.startswith('assets/'):
                retained[name] = body
    require(MANIFEST in retained, 'Missing APK manifest: ' + label)
    manifest = axml.manifest(retained[MANIFEST])
    return {'label': label, 'sha256': sha(data), 'size': len(data),
            'entries': entries, 'payloads': retained, 'manifest': manifest}


def snapshot(path):
    path = Path(path).resolve()
    return read_apk(path.read_bytes(), str(path))


def permissions(manifest):
    return [n for n in manifest['nodes'] if n['path'].split('/')[-1].startswith('uses-permission')]


def root(manifest):
    return next(n for n in manifest['nodes'] if n['kind'] == 'element' and n['path'] == 'manifest')


def difference_rows(a, b):
    return [{'entry': n, 'before': a['entries'].get(n), 'after': b['entries'].get(n)}
            for n in sorted(a['entries'].keys() | b['entries'].keys())
            if a['entries'].get(n) != b['entries'].get(n)]


def source_record_matches(apk, entry, expected):
    actual = apk['entries'].get(entry)
    if expected is None:
        return actual is None
    return actual == {'sha256': expected['sha256'], 'size': expected['bytes']}


def check(failures, ok, message):
    if not ok:
        failures.append(message)
    return bool(ok)


def check_source_assets(a, b, rows, prefix, failures):
    bindings = []
    for row in rows:
        name = 'assets/' + row['sourcePathUnderAppTree'].removeprefix(prefix)
        good = all(source_record_matches(apk, name, row[side]) for apk, side in ((a, 'before'), (b, 'after')))
        check(failures, good, 'Immutable source payload mismatch: ' + name)
        bindings.append({'entry': name, 'source': row, 'exactBothSides': good})
    return bindings


def check_dex_and_profiles(a, b, expected_profiles, failures):
    dex = []
    sets = []
    for side, apk in (('before', a), ('after', b)):
        names = {n for n in apk['entries'] if DEX_RE.fullmatch(n)}
        require_names = {'classes' + ('' if i == 1 else str(i)) + '.dex' for i in range(1, len(names) + 1)}
        check(failures, bool(names) and names == require_names, side + ': missing/noncontiguous canonical DEX')
        invalid = [n for n in apk['entries'] if n.lower().endswith('.dex') and not DEX_RE.fullmatch(n)]
        check(failures, not invalid, side + ': noncanonical DEX paths: ' + ', '.join(sorted(invalid)))
        profiles = {n for n in apk['entries'] if n.startswith('assets/dexopt/')}
        check(failures, profiles == set(expected_profiles), side + ': exact profile entry set mismatch')
        sets.append(names)
    layout_equal = check(failures, sets[0] == sets[1],
                         'DEX entry names changed; separate packing/class-inventory review required')
    for n in sorted(sets[0] | sets[1]):
        dex.append({'entry': n, 'before': a['entries'].get(n), 'after': b['entries'].get(n),
                    'changed': a['entries'].get(n) != b['entries'].get(n),
                    'classification': 'PENDING_DEX_DETAILED_AUDIT' if layout_equal else 'BLOCKED_DEX_STRUCTURE_REVIEW'})
    profiles = [{'entry': n, 'before': a['entries'].get(n), 'after': b['entries'].get(n),
                 'changed': a['entries'].get(n) != b['entries'].get(n),
                 'classification': 'PENDING_PROFILE_PRODUCER_AND_SEMANTIC_AUDIT'} for n in expected_profiles]
    return dex, profiles, layout_equal


def audit_template(a, b, scope):
    failures = []
    manifest_equal = check(failures, a['manifest'] == b['manifest'], 'Template semantic manifest changed')
    permissionless = check(failures, not permissions(a['manifest']) and not permissions(b['manifest']),
                           'Template must be permissionless on both sides')
    sdk_ok = check(failures, source_record_matches(a, SDK, scope['sdkBinding']['before']) and
                   source_record_matches(b, SDK, scope['sdkBinding']['after']), 'Template SDK source hash/size mismatch')
    source_bindings = check_source_assets(a, b, scope['unchangedSourceDomains']['otherRuntimeAssets']['sourceHashes'],
                                         'apk-runtime/src/main/assets/', failures)
    dex, profiles, layout_equal = check_dex_and_profiles(a, b, PROFILES, failures)
    changes = difference_rows(a, b)
    for row in changes:
        n = row['entry']
        if n == SDK:
            classification = 'SOURCE_SDK_EXACT' if sdk_ok else 'FAILED_SOURCE_SDK_BINDING'
        elif n == MANIFEST:
            classification = 'IDENTICAL_SEMANTIC_AXML' if manifest_equal and permissionless else 'FAILED_TEMPLATE_MANIFEST'
        elif n in {d['entry'] for d in dex}:
            classification = 'PENDING_DEX_DETAILED_AUDIT' if layout_equal else 'BLOCKED_DEX_STRUCTURE_REVIEW'
        elif n in PROFILES and row['before'] is not None and row['after'] is not None:
            classification = 'PENDING_PROFILE_PRODUCER_AND_SEMANTIC_AUDIT'
        else:
            classification = 'UNEXPECTED_SURFACE_DELTA'
            failures.append('Unexpected template payload delta: ' + n)
        row['classification'] = classification
    return {'surfaceScopeOnly': True, 'surfacePassed': not failures, 'fullArtifactApproved': False,
            'before': identity(a), 'after': identity(b), 'manifestExact': manifest_equal,
            'manifestPermissionless': permissionless, 'sdkExactSourceBinding': sdk_ok,
            'unchangedRuntimeSourceBindings': source_bindings, 'differingPayloads': changes,
            'pendingDexDetailedAudit': dex, 'pendingProfileDetailedAudit': profiles,
            'failures': failures, 'limits': LIMITS}


def identity(apk):
    return {k: apk[k] for k in ('label', 'sha256', 'size')}


def audit_host(a, b, baseline_template, candidate_template, scope, variant, recovery_test=False):
    require(variant in VARIANTS, 'Unsupported variant')
    failures = []
    manifest_a, versions_a = axml.normalize_manifest(a['manifest'], True)
    manifest_b, versions_b = axml.normalize_manifest(b['manifest'], True)
    versions_ok = True
    suffix = '-test' if recovery_test else ''
    for side, values in (('before', versions_a), ('after', versions_b)):
        wanted = {axml.ANDROID + ':versionCode': scope['hostVersion'][side]['versionCode'],
                  axml.ANDROID + ':versionName': scope['hostVersion'][side]['versionName'] + suffix}
        versions_ok &= check(failures, set(values) == set(wanted) and
                             all(values[k]['value'] == v for k, v in wanted.items()),
                             side + ': host version does not match source-derived v79/v80 value')
    manifest_ok = check(failures, manifest_a == manifest_b, 'Host semantic manifest changed beyond root version values')
    expected_package = 'com.jarvys.agent.recoverytest' if recovery_test else 'com.jarvys.agent'
    for side, apk in (('before', a), ('after', b)):
        check(failures, root(apk['manifest'])['attrs'].get(':package', {}).get('value') == expected_package,
              side + ': wrong host application identity')
    permissions_ok = check(failures, permissions(a['manifest']) == permissions(b['manifest']), 'Host permission declarations changed')
    for side, apk in (('before', a), ('after', b)):
        forbidden = [n for n in permissions(apk['manifest']) if n['attrs'].get(axml.ANDROID + ':name', {}).get('value')
                     in ('android.permission.USE_BIOMETRIC', 'android.permission.USE_FINGERPRINT')]
        check(failures, not forbidden, side + ': biometric permission must not be declared by host')
        check(failures, SDK not in apk['entries'], side + ': unexpected top-level SDK asset')
    guidance = scope['changedGuidanceAssets']
    require(len(guidance) == 11 and len({g['hostApkEntry'] for g in guidance}) == 11, 'Expected exactly 11 source guidance changes')
    bindings = {}
    for row in guidance:
        n = row['hostApkEntry']
        bindings[n] = check(failures, source_record_matches(a, n, row['before']) and source_record_matches(b, n, row['after']),
                            'Guidance immutable source payload mismatch: ' + n)
    changed_guidance = {r['entry'] for r in difference_rows(a, b)} & bindings.keys()
    check(failures, changed_guidance == set(bindings), 'Exactly 11 changed/new guidance payloads required')
    source_assets = check_source_assets(a, b, scope['unchangedSourceDomains']['otherHostAssets']['sourceHashes'],
                                       'app/src/main/assets/', failures)
    template_bindings = {}
    hash_bindings = {}
    for side, apk, exact_template in (('before', a, baseline_template), ('after', b, candidate_template)):
        raw = apk['payloads'].get(TEMPLATE)
        good = check(failures, raw is not None and payload_record(raw) ==
                     {'sha256': exact_template['sha256'], 'size': exact_template['size']},
                     side + ': embedded template does not match exact runtime producer/template input')
        template_bindings[side] = good
        hash_bindings[side] = check(failures, raw is not None and apk['payloads'].get(TEMPLATE_SHA) == (sha(raw) + '\n').encode('ascii'),
                                    side + ': template SHA asset must equal lowercase embedded SHA-256 plus exactly one LF')
    nested = audit_template(baseline_template, candidate_template, scope)
    check(failures, nested['surfacePassed'], 'Nested template surface failed')
    expected_profiles = PROFILES if variant == 'full-release' else ()
    dex, profiles, layout_equal = check_dex_and_profiles(a, b, expected_profiles, failures)
    changes = difference_rows(a, b)
    for row in changes:
        n = row['entry']
        if n == MANIFEST:
            classification = 'SOURCE_ROOT_VERSION_ONLY_SEMANTIC_AXML' if manifest_ok and versions_ok and permissions_ok else 'FAILED_HOST_MANIFEST'
        elif n in bindings:
            classification = 'SOURCE_GUIDANCE_EXACT' if bindings[n] else 'FAILED_SOURCE_GUIDANCE_BINDING'
            row['source'] = next(g for g in guidance if g['hostApkEntry'] == n)
        elif n == TEMPLATE:
            classification = ('EXACT_RUNTIME_TEMPLATE_PRODUCER_WITH_NESTED_SURFACE_PENDING_DEX_PROFILES'
                              if all(template_bindings.values()) and nested['surfacePassed'] else 'FAILED_TEMPLATE_BINDING_OR_SURFACE')
        elif n == TEMPLATE_SHA:
            classification = 'EXACT_EMBEDDED_TEMPLATE_DIGEST' if all(hash_bindings.values()) else 'FAILED_TEMPLATE_DIGEST'
        elif n in {d['entry'] for d in dex}:
            classification = 'PENDING_DEX_DETAILED_AUDIT' if layout_equal else 'BLOCKED_DEX_STRUCTURE_REVIEW'
        elif n in expected_profiles and row['before'] is not None and row['after'] is not None:
            classification = 'PENDING_PROFILE_PRODUCER_AND_SEMANTIC_AUDIT'
        else:
            classification = 'UNEXPECTED_SURFACE_DELTA'
            failures.append('Unexpected host payload delta: ' + n)
        row['classification'] = classification
    return {'surfaceScopeOnly': True, 'surfacePassed': not failures, 'fullArtifactApproved': False,
            'variant': variant, 'recoveryTestBuild': recovery_test, 'before': identity(a), 'after': identity(b),
            'manifestEquivalentExceptExactVersionValues': manifest_ok and versions_ok,
            'manifestVersionAttributes': {'before': versions_a, 'after': versions_b},
            'permissionsUnchanged': permissions_ok, 'guidanceBindings': bindings,
            'unchangedHostSourceBindings': source_assets, 'templateBindings': template_bindings,
            'templateShaAssetBindings': hash_bindings, 'templateSurface': nested,
            'differingPayloads': changes, 'pendingDexDetailedAudit': dex,
            'pendingProfileDetailedAudit': profiles, 'failures': failures,
            'allEntryPayloadHashes': {'before': a['entries'], 'after': b['entries']}, 'limits': LIMITS}


def verified_scope(repo, scope_path):
    require(not sys.flags.optimize, 'Run without Python -O: immutable scope derivation relies on assertions')
    supplied = json.loads(Path(scope_path).read_text())
    require(source_scope.CANDIDATE_APP_TREE == APP_TREE and source_scope.BASELINE == BASELINE_COMMIT,
            'Scope derivation helper is not pinned to reviewed source identities')
    regenerated = source_scope.derive(repo)
    require(regenerated == supplied, 'Saved source scope differs from fresh immutable Git derivation')
    require(supplied['candidateAppTree'] == APP_TREE and supplied['baselineCommit'] == BASELINE_COMMIT,
            'Wrong source identities')
    return regenerated


def write_report(path, report):
    path = Path(path).resolve()
    require(path.is_relative_to(HERE) and path.suffix == '.json', 'Report must be a JSON file within ' + str(HERE))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--scope', type=Path, default=HELPERS / 'source-derived-v80-host-scope.json')
    p.add_argument('--baseline-directory', type=Path, required=True)
    p.add_argument('--candidate-directory', type=Path, required=True)
    p.add_argument('--baseline-template', type=Path, required=True,
                   help='Matched reconstructed v79 runtime template, not a different delivered template')
    p.add_argument('--recovery-test', action='store_true')
    p.add_argument('--output', type=Path, default=HERE / 'final-host-surface.json')
    args = p.parse_args()
    result = {'format': 'source-driven-v80-final-host-surface-v1', 'surfaceScopeOnly': True,
              'surfacePassed': False, 'fullArtifactApproved': False,
              'buildProvenanceVerified': False, 'artifactIdentityBinding': 'Exact APK SHA-256 values below must be bound to independently archived built-artifacts/provenance records.',
              'limits': LIMITS}
    try:
        repo = args.repo.resolve()
        scope = verified_scope(repo, args.scope)
        result['sourceBinding'] = {'candidateAppTree': APP_TREE, 'baselineCommit': BASELINE_COMMIT,
                                   'scopeSha256': sha(Path(args.scope).read_bytes()), 'freshImmutableDerivationMatched': True}
        current = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD:app']).decode().strip()
        require(current == APP_TREE, 'Current HEAD app tree is not the reviewed candidate')
        subprocess.run(['git', '-C', str(repo), 'diff', '--quiet', 'HEAD', '--', 'app'], check=True)
        require(not subprocess.check_output(['git', '-C', str(repo), 'ls-files', '--others', '--exclude-standard', '--', 'app']),
                'Untracked nonignored app source exists; build-source identity requires review')
        # This fixed path is the producer named by the immutable Gradle source.
        candidate_template_path = repo / 'app/apk-runtime/build/outputs/apk/release/apk-runtime-release-unsigned.apk'
        baseline_template = snapshot(args.baseline_template)
        candidate_template = snapshot(candidate_template_path)
        result['templateInputs'] = {'before': identity(baseline_template), 'after': identity(candidate_template)}
        result['variants'] = []
        for variant in VARIANTS:
            name = 'app-' + variant + '-unsigned.apk'
            before = snapshot(args.baseline_directory / name)
            after = snapshot(args.candidate_directory / name)
            result['variants'].append(audit_host(before, after, baseline_template, candidate_template,
                                                 scope, variant, args.recovery_test))
        result['surfacePassed'] = all(v['surfacePassed'] for v in result['variants'])
        result['status'] = ('SURFACE_ONLY_PASS_DEX_AND_PROFILES_PENDING' if result['surfacePassed'] else 'SURFACE_FAILED')
        result['helperSourceSha256'] = {str(path): sha(path.read_bytes()) for path in
                                       (Path(__file__), HELPERS / 'apk_surface.py', HELPERS / 'derive_expected_scope.py')}
    except (OSError, ValueError, KeyError, IndexError, AssertionError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        result['status'] = 'BLOCKED_INPUT_OR_SOURCE_FAILURE'
        result['error'] = type(exc).__name__ + ': ' + str(exc)
    write_report(args.output, result)
    print(json.dumps({'output': str(args.output.resolve()), 'status': result['status'],
                      'surfaceScopeOnly': True, 'surfacePassed': result['surfacePassed'], 'fullArtifactApproved': False}))
    return 0 if result['surfacePassed'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
