#!/usr/bin/env python3
"""Separate exact same-variant AGP profile producer + independent DEX parser binding.

A binding pass is not semantic approval of changed profile selections/flags, nor
APK approval. No profile exemption is fed back into the host surface audit.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import zipfile
sys.dont_write_bytecode = True
import final_host_surface as surface
import profile_payloads

TARGETS = {
    'full-release': ('app/app', 'fullRelease', 'compileFullReleaseArtProfile', 'full/release/app-full-release-unsigned.apk'),
    'runtime-template': ('app/apk-runtime', 'release', 'compileReleaseArtProfile', 'release/apk-runtime-release-unsigned.apk'),
}


def producer_paths(repo, target):
    surface.require(target in TARGETS, 'Only exact full-release and runtime-template targets are supported')
    module, variant, task, apk_path = TARGETS[target]
    build = Path(repo).resolve() / module / 'build'
    return {'apk': build / 'outputs/apk' / apk_path,
            'profile': build / 'intermediates/binary_art_profile' / variant / task / 'baseline.prof',
            'metadata': build / 'intermediates/binary_art_profile_metadata' / variant / task / 'baseline.profm',
            'variant': variant, 'task': ':' + module.split('/')[-1] + ':' + task}


def bind_pair(apk, profile, metadata):
    """No supplied expected hash can authorize a mismatch; actual producer bytes must bind."""
    with zipfile.ZipFile(apk) as archive:
        names = {n for n in archive.namelist() if n.startswith('assets/dexopt/')}
        surface.require(names == set(surface.PROFILES), 'Exact profile entry set required; no profile-path wildcard')
    parsed = profile_payloads.inventory(apk)
    producer = {}
    for key, path, parsed_key in (('profile', profile, 'profileSha256'), ('metadata', metadata, 'metadataSha256')):
        raw = Path(path).read_bytes()
        digest = surface.sha(raw)
        surface.require(digest == parsed[parsed_key], 'Packaged ' + key + ' differs from exact same-variant AGP producer')
        producer[key] = {'path': str(Path(path).resolve()), 'sha256': digest, 'size': len(raw)}
    return {'producerFiles': producer, 'independentParserInventory': parsed}


def semantic_delta(before, after):
    rows = {}
    for dex in sorted(before.keys() | after.keys()):
        a = before.get(dex, {'classes': [], 'metadataClasses': [], 'methods': {}})
        b = after.get(dex, {'classes': [], 'metadataClasses': [], 'methods': {}})
        methods_a, methods_b = a['methods'], b['methods']
        row = {
            'methodsRemoved': {k: methods_a[k] for k in sorted(methods_a.keys() - methods_b.keys())},
            'methodsAdded': {k: methods_b[k] for k in sorted(methods_b.keys() - methods_a.keys())},
            'methodFlagsChanged': {k: {'before': methods_a[k], 'after': methods_b[k]}
                                   for k in sorted(methods_a.keys() & methods_b.keys()) if methods_a[k] != methods_b[k]},
            'classesRemoved': sorted(set(a['classes']) - set(b['classes'])),
            'classesAdded': sorted(set(b['classes']) - set(a['classes'])),
            'metadataClassesRemoved': sorted(set(a['metadataClasses']) - set(b['metadataClasses'])),
            'metadataClassesAdded': sorted(set(b['metadataClasses']) - set(a['metadataClasses'])),
        }
        if any(row.values()) or (dex in before) != (dex in after):
            rows[dex] = row
    return rows


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo', required=True, type=Path)
    p.add_argument('--scope', default=surface.HELPERS / 'source-derived-v80-host-scope.json', type=Path)
    p.add_argument('--target', required=True, choices=tuple(TARGETS))
    p.add_argument('--apk', required=True, type=Path, help='Exact candidate artifact or byte-identical final snapshot')
    p.add_argument('--baseline-apk', required=True, type=Path, help='Matching baseline APK/template')
    p.add_argument('--output', default=surface.HERE / 'profile-producer-binding.json', type=Path)
    args = p.parse_args()
    result = {'format': 'v80-profile-producer-parser-binding-v1', 'target': args.target,
              'surfaceScopeOnly': True, 'producerAndDexBindingPassed': False,
              'profileSemanticApproved': False, 'fullArtifactApproved': False, 'buildProvenanceVerified': False,
              'limits': 'Exact current producer bytes and independent P010/metadata002 DEX binding only. '
                        'Changed method/class selections and flags require detailed review. Build freshness/provenance is separate.'}
    try:
        scope = surface.verified_scope(args.repo, args.scope)
        current = subprocess.check_output(['git', '-C', str(args.repo), 'rev-parse', 'HEAD:app']).decode().strip()
        surface.require(current == surface.APP_TREE, 'Current app tree differs from reviewed candidate')
        subprocess.run(['git', '-C', str(args.repo), 'diff', '--quiet', 'HEAD', '--', 'app'], check=True)
        paths = producer_paths(args.repo, args.target)
        candidate_hash = surface.sha(args.apk.read_bytes())
        producer_apk_hash = surface.sha(paths['apk'].read_bytes())
        surface.require(candidate_hash == producer_apk_hash, 'Candidate APK differs from exact target packaged output')
        result['sourceBinding'] = {'candidateAppTree': surface.APP_TREE,
                                   'scopeSha256': surface.sha(args.scope.read_bytes()),
                                   'agpVersionFromImmutableSource': scope['generatedProfiles']['agpVersion']}
        result['producer'] = {k: str(v) for k, v in paths.items()}
        result['packagedOutputSha256'] = producer_apk_hash
        result['candidate'] = bind_pair(args.apk, paths['profile'], paths['metadata'])
        result['baseline'] = profile_payloads.inventory(args.baseline_apk)
        result['semanticProfileDifferences'] = semantic_delta(result['baseline']['semanticProfile'],
                                                              result['candidate']['independentParserInventory']['semanticProfile'])
        result['semanticProfileIdentical'] = not result['semanticProfileDifferences']
        result['producerAndDexBindingPassed'] = True
        result['status'] = 'EXACT_PRODUCER_AND_PARSER_BOUND_SEMANTIC_REVIEW_PENDING'
        result['helperSourceSha256'] = {str(path): surface.sha(path.read_bytes()) for path in
                                       (Path(__file__), surface.HERE / 'final_host_surface.py',
                                        surface.HELPERS / 'profile_payloads.py', surface.HELPERS / 'symbolic_dex.py')}
    except Exception as exc:
        result['status'] = 'BLOCKED_PROFILE_BINDING'
        result['error'] = type(exc).__name__ + ': ' + str(exc)
    surface.write_report(args.output, result)
    print(json.dumps({'output': str(args.output.resolve()), 'status': result['status'],
                      'producerAndDexBindingPassed': result['producerAndDexBindingPassed'],
                      'profileSemanticApproved': False, 'fullArtifactApproved': False}))
    return 0 if result['producerAndDexBindingPassed'] else 2


if __name__ == '__main__': raise SystemExit(main())
