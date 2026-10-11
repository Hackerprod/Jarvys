#!/usr/bin/env python3
"""Reconstruct a COMPLETE symbolic candidate inventory using exact-byte DEX reuse.

This is an extraction optimization, never a comparison policy or approval. Only
complete records from one of the three explicitly pinned v79 inventories can be
reused, and only after raw DEX equality, metadata, and inventory checks. Every
changed/new payload is processed by the original pinned dexdump/parse_dump path.
The ordinary whole-inventory comparator must still review every real delta.

Baseline dump hashes in reused rows describe historical baseline dump output,
not a dexdump invocation in this run. The per-entry origin and bound provenance
make that distinction explicit. No ordinal mapping or filtering occurs here.
"""
import argparse
import collections
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile
import types
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
O = HERE.parent
R = O.parents[1]
HELPER_SHA = 'd2f331fc834f999aebf199b15dcb4408cc4ea36590d0c266394994f2083520db'
DEXDUMP_SHA = 'ef273cb9e5a01ccbba046ea7c80d73203f0c908d2779b4c58e21091753c86049'
# Transcribed exactly from reviewer-three-baseline-dex-inventories.json.
APPROVED_EVIDENCE = {
    'scope': 'Static complete baseline inventories, no candidate approval',
    'baselineCommit': '5c177d32cfc1b13209a54131753f98cec3582d42',
    'helperSha256': HELPER_SHA,
    'rows': [
        {'variant': 'full-debug',
         'apkSha256': 'b58bca9c8d4361656e4c6dac56c83b379e39c9feee1011ea77a800f58922ad54',
         'inventorySha256': '618cd80c92dab5dfa834f764fe242ab309f7bff1beeeddd8246bf55a07bef5cf',
         'classes': 28407, 'dexFiles': 20, 'completeBinaryMetadata': True},
        {'variant': 'play-debug',
         'apkSha256': '8080755e80ff0583b0f25728fe37214fc2db892810f893751673425c092a42c7',
         'inventorySha256': 'abfa3e6a732d6d0dad43cdfdada5318fc40e84e37f1f2f5183eaf951f97477a1',
         'classes': 26044, 'dexFiles': 19, 'completeBinaryMetadata': True},
        {'variant': 'full-release',
         'apkSha256': '93976b3af088d9617da091b76b05752606fca56f08c352fa927a5afac0001b1e',
         'inventorySha256': '88259a750cecb0abdeda1ea52a9e5d25e93bf7de09da0195c719293b86d0eec2',
         'classes': 27001, 'dexFiles': 5, 'completeBinaryMetadata': True},
    ],
}
PINS = {row['variant']: row for row in APPROVED_EVIDENCE['rows']}
HEX_SHA = re.compile(r'[0-9a-f]{64}')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical_sha(value):
    digest = hashlib.sha256()
    for chunk in json.JSONEncoder(sort_keys=True, separators=(',', ':'), ensure_ascii=True).iterencode(value):
        digest.update(chunk.encode('ascii'))
    return digest.hexdigest()


def hash_stream(stream):
    stream.seek(0)
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
    stream.seek(0)
    return digest.hexdigest()


def hash_file(path):
    with pathlib.Path(path).open('rb') as stream:
        return hash_stream(stream)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key: ' + key)
        result[key] = value
    return result


def strict_json(data):
    def reject_constant(value):
        raise ValueError('Nonfinite JSON constant: ' + value)
    return json.loads(data, object_pairs_hook=unique_object, parse_constant=reject_constant)


def pinned_bytes(path, expected):
    data = pathlib.Path(path).read_bytes()
    require(sha(data) == expected, 'SHA-256 pin mismatch: ' + str(path))
    return data


def load_approved_helper(path):
    # Execute precisely the verified bytes, rather than re-reading through an
    # import loader. This also leaves the original helper and its API unchanged.
    path = pathlib.Path(path).resolve()
    data = pinned_bytes(path, HELPER_SHA)
    module = types.ModuleType('approved_symbolic_dex')
    module.__file__ = str(path)
    exec(compile(data, str(path), 'exec'), module.__dict__)
    return module


def validate_evidence(path, variant):
    data = pathlib.Path(path).read_bytes()
    require(strict_json(data) == APPROVED_EVIDENCE, 'Approved baseline evidence changed')
    require(variant in PINS, 'Unapproved baseline variant')
    return dict(PINS[variant]), sha(data)


class Apk:
    """One stable open APK, full ZIP CRC check, canonical contiguous DEX names.

    No APK copy or all-DEX in-memory cache is needed. Verify again before output
    so that a concurrent replacement/write cannot acquire the original binding.
    """
    def __init__(self, path, expected_sha=None):
        self.path = pathlib.Path(path).resolve()
        self.stream = self.path.open('rb')
        self.archive = None
        try:
            self.initial_stat = os.fstat(self.stream.fileno())
            self.sha256 = hash_stream(self.stream)
            if expected_sha is not None:
                require(self.sha256 == expected_sha, 'APK SHA-256 pin mismatch: ' + str(self.path))
            self.archive = zipfile.ZipFile(self.stream)
            names = self.archive.namelist()
            require(len(names) == len(set(names)), 'Duplicate ZIP entries')
            require(self.archive.testzip() is None, 'ZIP CRC failure')
            dex_names = {name for name in names if name.lower().endswith('.dex')}
            expected = {'classes.dex'} | {'classes%d.dex' % n for n in range(2, len(dex_names) + 1)}
            require(dex_names and dex_names == expected,
                    'Noncanonical, missing, or gapped DEX entries: ' + repr(sorted(dex_names)))
            self.names = tuple(sorted(dex_names))
        except BaseException:
            self.close()
            raise

    def read(self, name):
        require(name in self.names, 'DEX entry outside checked inventory: ' + name)
        return self.archive.read(name)

    def verify_unchanged(self):
        # File identity catches replacement, while hashing the still-open inode
        # catches modifications. Timestamps alone are not a content proof.
        current = self.path.stat()
        require((current.st_dev, current.st_ino) ==
                (self.initial_stat.st_dev, self.initial_stat.st_ino), 'APK path replaced during extraction')
        require(hash_stream(self.stream) == self.sha256, 'APK changed during extraction')
        require(hash_file(self.path) == self.sha256, 'APK path content changed during extraction')

    def close(self):
        if self.archive is not None:
            self.archive.close()
        self.stream.close()

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()


def require_unique_persisted_keys(value):
    """Fail before JSON serialization could collapse equivalent object keys."""
    if isinstance(value, dict):
        keys = set()
        for key, child in value.items():
            require(isinstance(key, str), 'Metadata JSON object key is not a string')
            encoded = json.dumps(key, ensure_ascii=True)
            require(encoded not in keys, 'Duplicate persisted JSON key: ' + encoded)
            keys.add(encoded)
            require_unique_persisted_keys(child)
    elif isinstance(value, (list, tuple)):
        for child in value:
            require_unique_persisted_keys(child)


def same_persisted_value(left, right):
    """Exact complete JSON equality, including every UTF-16 code unit.

    Dex decodes MUTF-8 as CESU-8 with surrogatepass; JSON loading combines valid
    surrogate pairs into a Python scalar. ensure_ascii encodes both forms to
    the same original UTF-16 sequence. Changed/unpaired/replacement characters
    remain different. No metadata is removed or rewritten in the inventory.
    """
    require_unique_persisted_keys(left)
    require_unique_persisted_keys(right)
    options = {'sort_keys': True, 'separators': (',', ':'),
               'ensure_ascii': True, 'allow_nan': False}
    return json.dumps(left, **options) == json.dumps(right, **options)


def validate_records(classes, binary, label):
    """Detect dropped/extra class/member records and lost binary metadata/body.

    Actual instructions, payloads and catches come unchanged from the pinned
    trusted baseline or the pinned full parser, never from a second normalizer.
    """
    require(set(classes) == set(binary.classes), label + ': raw class inventory mismatch')
    for owner, raw in binary.classes.items():
        row = classes[owner]
        require(set(row) == {'access', 'superclass', 'interfaces', 'fields', 'methods', 'annotations'},
                label + ': class record shape changed: ' + owner)
        for key in ('access', 'superclass', 'interfaces', 'fields', 'annotations'):
            require(same_persisted_value(row[key], raw[key]),
                    label + ': raw metadata mismatch: ' + owner + ' ' + key)
        require(set(row['methods']) == set(raw['methods']), label + ': method inventory mismatch: ' + owner)
        for name, metadata in raw['methods'].items():
            method = row['methods'][name]
            require(set(method) == {'group', 'access', 'prototype', 'body'},
                    label + ': method record shape changed: ' + owner + name)
            require(all(method[key] == value for key, value in metadata.items()),
                    label + ': method metadata mismatch: ' + owner + name)
            offset = binary.code[(owner, name)]
            body = method['body']
            require((body is None) == (offset == 0), label + ': method body presence mismatch: ' + owner + name)
            if body is not None:
                require(set(body) == {'registers', 'ins', 'outs', 'instructions', 'catches'},
                        label + ': method body record shape changed: ' + owner + name)
                require([body[key] for key in ('registers', 'ins', 'outs')] ==
                        [binary.u16(offset + delta) for delta in (0, 2, 4)],
                        label + ': code register metadata mismatch: ' + owner + name)
                require(isinstance(body['instructions'], list) and body['instructions'],
                        label + ': instructions missing: ' + owner + name)
                require(isinstance(body['catches'], list), label + ': catch data missing: ' + owner + name)


def prepare_baseline(inventory, apk, pins, helper):
    require(inventory.get('format') == helper.FORMAT, 'Baseline inventory format mismatch')
    require(inventory.get('completeBinaryMetadata') is True, 'Baseline binary metadata incomplete')
    require(inventory.get('limits') == helper.LIMITS, 'Baseline extractor limits mismatch')
    require(inventory.get('sourceSha256') == apk.sha256 == pins['apkSha256'], 'Baseline source SHA binding mismatch')
    require(isinstance(inventory.get('source'), str) and inventory['source'], 'Baseline source path missing')
    classes = inventory['classes']
    require(len(classes) == pins['classes'], 'Baseline class count mismatch')
    require(set(inventory['dexFiles']) == set(apk.names), 'Baseline DEX inventory mismatch')
    require(len(apk.names) == pins['dexFiles'], 'Baseline DEX count mismatch')
    membership, owners, by_sha = {}, {}, collections.defaultdict(list)
    for name in apk.names:
        data = apk.read(name)
        digest = sha(data)
        row = inventory['dexFiles'][name]
        require(row['sha256'] == digest, 'Baseline DEX SHA mismatch: ' + name)
        require(isinstance(row.get('dumpSha256'), str) and HEX_SHA.fullmatch(row['dumpSha256']),
                'Baseline dump SHA missing: ' + name)
        binary = helper.Dex(data)
        descriptors = set(binary.classes)
        require(descriptors, 'Baseline DEX has no classes: ' + name)
        require(row['classCount'] == len(descriptors), 'Baseline DEX class count mismatch: ' + name)
        require(descriptors <= set(classes), 'Baseline class records missing: ' + name)
        collision = descriptors & set(owners)
        require(not collision, 'Class duplicated across baseline DEX files: ' + repr(sorted(collision)))
        validate_records({owner: classes[owner] for owner in descriptors}, binary, 'Baseline ' + name)
        membership[name] = descriptors
        owners.update({owner: name for owner in descriptors})
        by_sha[digest].append(name)
    require(set(owners) == set(classes), 'Baseline raw descriptor inventory is incomplete or has extra records')
    require(sum(len(value) for value in membership.values()) == pins['classes'], 'Baseline raw class total mismatch')
    return membership, owners, by_sha


def parse_changed_payload(helper, dexdump, name, data, scratch):
    """The same dexdump -d, strict stderr, decode and parse routine as extract()."""
    target = pathlib.Path(scratch) / pathlib.Path(name).name
    target.write_bytes(data)
    run = subprocess.run([str(dexdump), '-d', str(target)], capture_output=True)
    require(run.returncode == 0, 'dexdump failed: ' + run.stderr.decode('utf-8', 'backslashreplace'))
    require(not run.stderr.strip(),
            'dexdump emitted diagnostics requiring manual review: ' + run.stderr.decode('utf-8', 'backslashreplace'))
    item = helper.parse_dump(helper.decode_dump(run.stdout), data)
    item['dumpSha256'] = sha(run.stdout)
    return item


def reconstruct(inventory, baseline, candidate, pins, helper, parse_payload):
    """Core with a tiny parser injection seam for synthetic tests, no CLI bypass.

    The CLI validates all trust pins before reaching this function, and supplies
    only parse_changed_payload with the pinned helper and dexdump executable.
    """
    membership, old_owners, by_sha = prepare_baseline(inventory, baseline, pins, helper)
    result = {'format': helper.FORMAT, 'classes': {}, 'completeBinaryMetadata': True,
              'limits': helper.LIMITS, 'source': str(candidate.path), 'sourceSha256': candidate.sha256,
              'dexFiles': {}}
    new_owners, origins = {}, {}
    reused, parsed = [], []
    for name in candidate.names:
        data = candidate.read(name)
        digest = sha(data)
        binary = helper.Dex(data)
        descriptors = set(binary.classes)
        require(descriptors, 'Candidate DEX has no classes: ' + name)
        collision = descriptors & set(new_owners)
        require(not collision, 'Class duplicated across candidate DEX files: ' + repr(sorted(collision)))
        matches = []
        for baseline_name in by_sha.get(digest, []):
            old_data = baseline.read(baseline_name)
            require(sha(old_data) == digest, 'Baseline DEX changed after validation')
            if old_data == data:
                matches.append(baseline_name)
        require(len(matches) <= 1, 'Ambiguous baseline payload reuse')
        if matches:
            old_name = matches[0]
            require(descriptors == membership[old_name], 'Equal-byte raw descriptor inventory mismatch')
            classes = {owner: inventory['classes'][owner] for owner in membership[old_name]}
            dump_sha = inventory['dexFiles'][old_name]['dumpSha256']
            origin = {'mode': 'reused-byte-identical-baseline-dex', 'baselineDexEntry': old_name,
                      'baselineDexSha256': digest, 'baselineDumpSha256': dump_sha,
                      'dumpHashMeaning': 'Historical baseline dexdump output, not generated in this run',
                      'exactPayloadBytesEqual': True}
            reused.append(name)
        else:
            item = parse_payload(name, data)
            require(item.get('format') == helper.FORMAT, 'Changed parser format mismatch')
            require(item.get('completeBinaryMetadata') is True, 'Changed parser binary metadata incomplete')
            require(item.get('limits') == helper.LIMITS, 'Changed parser limits mismatch')
            require(item.get('dexSha256') == digest, 'Changed parser DEX binding mismatch')
            require(isinstance(item.get('dumpSha256'), str) and HEX_SHA.fullmatch(item['dumpSha256']),
                    'Changed parser dump SHA missing')
            classes, dump_sha = item['classes'], item['dumpSha256']
            origin = {'mode': 'fresh-full-dexdump-and-parse_dump', 'dumpSha256': dump_sha,
                      'dumpHashMeaning': 'Raw dexdump stdout generated in this run'}
            parsed.append(name)
        validate_records(classes, binary, 'Candidate ' + name)
        require(not set(classes) & set(result['classes']), 'Candidate result would overwrite a class')
        result['classes'].update(classes)
        new_owners.update({owner: name for owner in descriptors})
        result['dexFiles'][name] = {'sha256': digest, 'dumpSha256': dump_sha,
                                    'classCount': len(classes), 'extractionOrigin': origin}
        origins[name] = {'sha256': digest, 'classCount': len(classes),
                         'descriptorsSha256': canonical_sha(sorted(descriptors)), **origin}
    require(set(new_owners) == set(result['classes']), 'Candidate raw/result class inventory mismatch')
    require(sum(row['classCount'] for row in result['dexFiles'].values()) == len(result['classes']),
            'Candidate class count lost or duplicated')
    require(set(reused).isdisjoint(parsed) and set(reused) | set(parsed) == set(candidate.names),
            'Candidate DEX coverage incomplete')
    common = set(old_owners) & set(new_owners)
    delta = {
        'addedClasses': sorted(set(new_owners) - set(old_owners)),
        'removedClasses': sorted(set(old_owners) - set(new_owners)),
        'movedClasses': [{'descriptor': owner, 'beforeDex': old_owners[owner], 'afterDex': new_owners[owner]}
                         for owner in sorted(common) if old_owners[owner] != new_owners[owner]],
        'removedDexEntries': sorted(set(baseline.names) - set(candidate.names)),
        'addedDexEntries': sorted(set(candidate.names) - set(baseline.names)),
    }
    provenance = {
        'method': 'Whole-payload exact-byte reuse; complete raw-Dex coverage; changed DEXs fully parsed',
        'scope': 'Extraction optimization only. No real delta suppression, ordinal relaxation, or approval.',
        'candidate': {'path': str(candidate.path), 'sha256': candidate.sha256,
                      'dexCount': len(candidate.names), 'classCount': len(result['classes'])},
        'baseline': {'path': str(baseline.path), 'sha256': baseline.sha256,
                     'inventoryReportedSource': inventory['source'],
                     'dexCount': len(baseline.names), 'classCount': len(inventory['classes'])},
        'reusedDexEntries': reused, 'freshlyParsedDexEntries': parsed,
        'dexEntries': origins, 'rawDescriptorDelta': delta,
        'candidateClassesCanonicalSha256': canonical_sha(result['classes']),
        'completeBinaryMetadata': True, 'limits': helper.LIMITS,
    }
    return result, provenance


def write_new_json(path, value):
    """Publish a complete JSON file without overwriting existing evidence."""
    path = pathlib.Path(path).resolve()
    require(not path.exists(), 'Refusing to overwrite output: ' + str(path))
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='ascii', prefix='.reuse-', suffix='.json',
                                         dir=path.parent, delete=False) as stream:
            temp = pathlib.Path(stream.name)
            json.dump(value, stream, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        # link() is atomic and fails if the destination has appeared meanwhile.
        os.link(temp, path)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)
    return hash_file(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', required=True, choices=sorted(PINS))
    parser.add_argument('--candidate', required=True, type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--provenance', required=True, type=pathlib.Path)
    parser.add_argument('--baseline', type=pathlib.Path)
    parser.add_argument('--baseline-inventory', type=pathlib.Path)
    parser.add_argument('--evidence', type=pathlib.Path, default=O / 'reviewer-three-baseline-dex-inventories.json')
    parser.add_argument('--helper', type=pathlib.Path, default=O / 'symbolic_dex.py')
    parser.add_argument('--dexdump', type=pathlib.Path, default=R / 'toolchain/android-sdk/build-tools/35.0.0/dexdump')
    args = parser.parse_args(argv)
    pins, evidence_sha = validate_evidence(args.evidence, args.variant)
    baseline_path = args.baseline or R / ('FACTORY-SENSORS-v79-validation/final-artifacts/app-%s-unsigned.apk' % args.variant)
    inventory_path = args.baseline_inventory or O / ('reconstructed-v79-%s.symbolic.json' % args.variant)
    inputs = [baseline_path, inventory_path, args.candidate, args.helper, args.dexdump, args.evidence, pathlib.Path(__file__)]
    outputs = [args.output.resolve(), args.provenance.resolve()]
    require(len(set(outputs)) == 2, 'Inventory and provenance outputs must differ')
    require(not set(outputs) & {path.resolve() for path in inputs}, 'Output would overwrite an input')
    require(not any(path.exists() for path in outputs), 'Output already exists')
    wrapper_sha = hash_file(__file__)
    helper = load_approved_helper(args.helper)
    require(hash_file(args.dexdump) == DEXDUMP_SHA, 'dexdump executable SHA-256 pin mismatch')
    inventory = strict_json(pinned_bytes(inventory_path, pins['inventorySha256']))
    with Apk(baseline_path, pins['apkSha256']) as baseline, Apk(args.candidate) as candidate:
        with tempfile.TemporaryDirectory(prefix='changed-dex-', dir=HERE) as scratch:
            def parse_payload(name, data):
                # Check before every invocation and again at completion.
                require(hash_file(args.dexdump) == DEXDUMP_SHA, 'dexdump executable changed')
                return parse_changed_payload(helper, args.dexdump, name, data, scratch)
            result, provenance = reconstruct(inventory, baseline, candidate, pins, helper, parse_payload)
        baseline.verify_unchanged()
        candidate.verify_unchanged()
    require(hash_file(args.helper) == HELPER_SHA, 'Helper changed during extraction')
    require(hash_file(args.dexdump) == DEXDUMP_SHA, 'dexdump changed during extraction')
    require(hash_file(inventory_path) == pins['inventorySha256'], 'Baseline inventory changed during extraction')
    require(hash_file(args.evidence) == evidence_sha, 'Baseline approval evidence changed during extraction')
    require(hash_file(__file__) == wrapper_sha, 'Wrapper changed during extraction')
    provenance.update({
        'variant': args.variant, 'baselineCommit': APPROVED_EVIDENCE['baselineCommit'],
        'baselineInventory': {'path': str(inventory_path.resolve()), 'sha256': pins['inventorySha256']},
        'baselineApprovalEvidence': {'path': str(args.evidence.resolve()), 'sha256': evidence_sha},
        'extractorHelper': {'path': str(args.helper.resolve()), 'sha256': HELPER_SHA},
        'dexdumpExecutable': {'path': str(args.dexdump.resolve()), 'sha256': DEXDUMP_SHA},
        'reuseWrapper': {'path': str(pathlib.Path(__file__).resolve()), 'sha256': wrapper_sha},
    })
    output_sha = write_new_json(args.output, result)
    provenance['candidateInventory'] = {'path': str(args.output.resolve()), 'sha256': output_sha}
    provenance_sha = write_new_json(args.provenance, provenance)
    print(json.dumps({'output': str(args.output.resolve()), 'sha256': output_sha,
                      'provenance': str(args.provenance.resolve()), 'provenanceSha256': provenance_sha,
                      'classes': len(result['classes']),
                      'reusedDexEntries': provenance['reusedDexEntries'],
                      'freshlyParsedDexEntries': provenance['freshlyParsedDexEntries'],
                      'completeBinaryMetadata': True, 'candidateApproval': False}, sort_keys=True))


if __name__ == '__main__':
    main()
