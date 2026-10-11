#!/usr/bin/env python3
"""Tiny synthetic controls: no Gradle, dexdump, real DEX parser, or large APKs.

These test the new orchestration. The original pinned parser's independent
fixture controls remain applicable and are neither replaced nor relaxed.
"""
import copy
import hashlib
import json
import pathlib
import tempfile
import types
import unittest
import warnings
from unittest import mock
import zipfile

import reuse_unchanged_dex as reuse


def record(value=1):
    return {
        'access': 1, 'superclass': 'Ljava/lang/Object;', 'interfaces': [],
        'fields': {'constant:I': {'group': 'static', 'access': 25,
                                  'value': {'type': 4, 'value': value}}},
        'methods': {'run()V': {'group': 'virtual', 'access': 1, 'prototype': '()V',
            'body': {'registers': 2, 'ins': 1, 'outs': 0,
                     'instructions': ['const/4 v0, #%d' % value,
                         {'opcode': 'array-data', 'payload': {'width': 1, 'count': 2, 'bytes': '0001'}},
                         {'opcode': 'sparse-switch-data', 'payload': [{'from': 'I0', 'cases': [[7, 'I3']]}]},
                         'return-void'],
                     'catches': [{'start': 'I0', 'end': 'I3',
                                  'handlers': [['Ljava/lang/Exception;', 'I3']]}]}}},
        'annotations': {'class': [{'visibility': 1, 'annotation': {'type': 'LTag;',
                         'elements': {'value': {'type': 4, 'value': value}}}}],
                        'fields': {}, 'methods': {}, 'parameters': {}},
    }


def payload(classes, marker=0):
    return json.dumps({'classes': classes, 'nonsemanticMarker': marker}, sort_keys=True).encode()


class FakeDex:
    """Deliberately tiny raw metadata stand-in; not a DEX format implementation."""
    def __init__(self, data):
        self.classes = json.loads(data)['classes']
        self.code, self.headers = {}, {}
        for owner, row in self.classes.items():
            for name, method in row['methods'].items():
                body = method.pop('body')
                offset = 1 + len(self.code) * 8 if body is not None else 0
                self.code[(owner, name)] = offset
                if body is not None:
                    for delta, key in zip((0, 2, 4), ('registers', 'ins', 'outs')):
                        self.headers[offset + delta] = body[key]

    def u16(self, offset):
        return self.headers[offset]


HELPER = types.SimpleNamespace(FORMAT='synthetic-test-only', LIMITS='synthetic-test-only', Dex=FakeDex)


def parsed_item(data):
    return {'format': HELPER.FORMAT, 'limits': HELPER.LIMITS,
            'completeBinaryMetadata': True, 'classes': json.loads(data)['classes'],
            'dexSha256': reuse.sha(data), 'dumpSha256': reuse.sha(b'synthetic parser output:' + data)}


class ReuseControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.counter = 0

    def tearDown(self):
        self.temp.cleanup()

    def archive_path(self, entries):
        self.counter += 1
        path = self.root / ('probe-%d.apk' % self.counter)
        entries = entries.items() if isinstance(entries, dict) else entries
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as archive:
                for name, data in entries:
                    archive.writestr(name, data)
        return path

    def baseline_inventory(self, apk):
        inventory = {'format': HELPER.FORMAT, 'limits': HELPER.LIMITS,
                     'source': str(apk.path), 'sourceSha256': apk.sha256,
                     'completeBinaryMetadata': True, 'classes': {}, 'dexFiles': {}}
        for name in apk.names:
            data = apk.read(name)
            item = parsed_item(data)
            inventory['classes'].update(item['classes'])
            inventory['dexFiles'][name] = {'sha256': reuse.sha(data), 'dumpSha256': item['dumpSha256'],
                                           'classCount': len(item['classes'])}
        return inventory

    def run_case(self, old, new, mutate_inventory=None, mutate_parser=None):
        before_path, after_path = self.archive_path(old), self.archive_path(new)
        calls = []
        with reuse.Apk(before_path) as before, reuse.Apk(after_path) as after:
            inventory = self.baseline_inventory(before)
            pins = {'apkSha256': before.sha256, 'classes': len(inventory['classes']), 'dexFiles': len(before.names)}
            if mutate_inventory:
                mutate_inventory(inventory)
            def parser(name, data):
                calls.append(name)
                item = parsed_item(data)
                if mutate_parser:
                    mutate_parser(item)
                return item
            result, provenance = reuse.reconstruct(inventory, before, after, pins, HELPER, parser)
            before.verify_unchanged()
            after.verify_unchanged()
            expected = {}
            for name in after.names:
                item = parsed_item(after.read(name))
                self.assertFalse(set(expected) & set(item['classes']))
                expected.update(item['classes'])
            # Complete structural equality includes annotations, access, methods,
            # instruction bodies, complete array/switch payloads and catches.
            self.assertEqual(result['classes'], expected)
            self.assertEqual(provenance['candidateClassesCanonicalSha256'], reuse.canonical_sha(expected))
        return result, provenance, calls

    def test_all_unchanged_payloads_reuse_without_parser(self):
        entries = {'classes.dex': payload({'LA;': record()}), 'classes2.dex': payload({'LB;': record(2)})}
        result, provenance, calls = self.run_case(entries, dict(entries, **{'resource.txt': b'changed APK container'}))
        self.assertEqual(calls, [])
        self.assertEqual(provenance['reusedDexEntries'], ['classes.dex', 'classes2.dex'])
        self.assertTrue(all(row['extractionOrigin']['exactPayloadBytesEqual'] for row in result['dexFiles'].values()))
        self.assertNotEqual(provenance['candidate']['sha256'], provenance['baseline']['sha256'])

    def test_changed_payload_parsed_even_when_semantics_unchanged(self):
        data = {'LA;': record()}
        _, provenance, calls = self.run_case({'classes.dex': payload(data, 1)}, {'classes.dex': payload(data, 2)})
        self.assertEqual(calls, ['classes.dex'])
        self.assertEqual(provenance['reusedDexEntries'], [])

    def test_hash_match_without_byte_match_cannot_reuse(self):
        old = {'classes.dex': payload({'LA;': record()})}
        new = {'classes.dex': payload({'LA;': record(2)})}
        # Synthetic digest collision: equality of actual bytes is still required.
        with mock.patch.object(reuse, 'sha', return_value='0' * 64):
            _, provenance, calls = self.run_case(old, new)
        self.assertEqual(calls, ['classes.dex'])
        self.assertEqual(provenance['reusedDexEntries'], [])

    def test_changed_body_payload_catch_and_annotation_retained(self):
        changed = record(2)
        changed['methods']['run()V']['body']['instructions'][1]['payload']['bytes'] = '1122'
        changed['methods']['run()V']['body']['instructions'][2]['payload'][0]['cases'][0][0] = 99
        changed['methods']['run()V']['body']['catches'][0]['handlers'][0][0] = 'Ljava/lang/Throwable;'
        result, _, calls = self.run_case({'classes.dex': payload({'LA;': record()})},
                                        {'classes.dex': payload({'LA;': changed})})
        self.assertEqual(calls, ['classes.dex'])
        self.assertEqual(result['classes']['LA;'], changed)

    def test_class_moves_across_changed_dexs_remain_complete(self):
        old = {'classes.dex': payload({'LA;': record(), 'LB;': record(2)}),
               'classes2.dex': payload({'LC;': record(3)})}
        new = {'classes.dex': payload({'LA;': record(), 'LC;': record(3)}),
               'classes2.dex': payload({'LB;': record(2)})}
        _, provenance, calls = self.run_case(old, new)
        self.assertEqual(calls, ['classes.dex', 'classes2.dex'])
        self.assertEqual([item['descriptor'] for item in provenance['rawDescriptorDelta']['movedClasses']], ['LB;', 'LC;'])
        self.assertEqual(provenance['rawDescriptorDelta']['removedClasses'], [])

    def test_identical_payload_can_move_to_another_ordinal(self):
        a, b = payload({'LA;': record()}), payload({'LB;': record(2)})
        result, _, calls = self.run_case({'classes.dex': a, 'classes2.dex': b}, {'classes.dex': b, 'classes2.dex': a})
        self.assertEqual(calls, [])
        self.assertEqual(result['dexFiles']['classes.dex']['extractionOrigin']['baselineDexEntry'], 'classes2.dex')

    def test_added_and_removed_classes_are_reported_not_suppressed(self):
        _, provenance, calls = self.run_case({'classes.dex': payload({'LA;': record(), 'LB;': record(2)})},
                                             {'classes.dex': payload({'LA;': record(), 'LC;': record(3)})})
        self.assertEqual(calls, ['classes.dex'])
        self.assertEqual(provenance['rawDescriptorDelta']['addedClasses'], ['LC;'])
        self.assertEqual(provenance['rawDescriptorDelta']['removedClasses'], ['LB;'])

    def test_added_and_removed_dex_entries_preserve_class_coverage(self):
        a, b = payload({'LA;': record()}), payload({'LB;': record(2)})
        _, added, calls = self.run_case({'classes.dex': a}, {'classes.dex': a, 'classes2.dex': b})
        self.assertEqual(calls, ['classes2.dex'])
        self.assertEqual(added['rawDescriptorDelta']['addedDexEntries'], ['classes2.dex'])
        _, removed, calls = self.run_case({'classes.dex': a, 'classes2.dex': b}, {'classes.dex': a})
        self.assertEqual(calls, [])
        self.assertEqual(removed['rawDescriptorDelta']['removedClasses'], ['LB;'])
        self.assertEqual(removed['rawDescriptorDelta']['removedDexEntries'], ['classes2.dex'])

    def test_candidate_duplicate_class_across_changed_dexs_fails(self):
        a, b = payload({'LA;': record()}), payload({'LB;': record(2)})
        with self.assertRaisesRegex(ValueError, 'duplicated across candidate'):
            self.run_case({'classes.dex': a, 'classes2.dex': b},
                          {'classes.dex': a, 'classes2.dex': payload({'LA;': record(3)})})

    def test_baseline_duplicate_class_fails(self):
        a = payload({'LA;': record()})
        with self.assertRaisesRegex(ValueError, 'duplicated across baseline'):
            self.run_case({'classes.dex': a, 'classes2.dex': a}, {'classes.dex': a})

    def test_missing_primary_gap_nested_case_and_empty_dex_set_fail(self):
        a = payload({'LA;': record()})
        bad = [{'classes2.dex': a}, {'classes.dex': a, 'classes3.dex': a},
               {'classes.dex': a, 'nested/classes2.dex': a}, {'classes.DEX': a}, {'readme': b'no dex'}]
        for entries in bad:
            with self.subTest(entries=list(entries)):
                with self.assertRaisesRegex(ValueError, 'Noncanonical'):
                    with reuse.Apk(self.archive_path(entries)):
                        self.fail('Noncanonical archive accepted')

    def test_duplicate_zip_entries_fail(self):
        a = payload({'LA;': record()})
        with self.assertRaisesRegex(ValueError, 'Duplicate ZIP'):
            with reuse.Apk(self.archive_path([('classes.dex', a), ('classes.dex', a)])):
                self.fail('Duplicate entry accepted')

    def test_crc_failure_in_non_dex_entry_fails(self):
        path = self.archive_path({'classes.dex': payload({'LA;': record()}), 'other': b'UNIQUE_CRC_CONTENT'})
        data = path.read_bytes()
        self.assertEqual(data.count(b'UNIQUE_CRC_CONTENT'), 1)
        path.write_bytes(data.replace(b'UNIQUE_CRC_CONTENT', b'UNIQUE_CRC_CONTENX'))
        with self.assertRaises((ValueError, zipfile.BadZipFile)):
            with reuse.Apk(path):
                self.fail('Bad CRC accepted')

    def test_wrong_apk_inventory_helper_and_approval_pins_fail(self):
        path = self.archive_path({'classes.dex': payload({'LA;': record()})})
        with self.assertRaisesRegex(ValueError, 'APK SHA-256 pin mismatch'):
            with reuse.Apk(path, '0' * 64):
                self.fail('Wrong APK pin accepted')
        with self.assertRaisesRegex(ValueError, 'SHA-256 pin mismatch'):
            reuse.pinned_bytes(path, '0' * 64)
        with self.assertRaisesRegex(ValueError, 'SHA-256 pin mismatch'):
            reuse.load_approved_helper(path)
        evidence = copy.deepcopy(reuse.APPROVED_EVIDENCE)
        evidence['rows'][0]['classes'] -= 1
        evidence_path = self.root / 'evidence.json'
        evidence_path.write_text(json.dumps(evidence))
        with self.assertRaisesRegex(ValueError, 'Approved baseline evidence changed'):
            reuse.validate_evidence(evidence_path, 'full-debug')

    def test_approved_evidence_accepts_only_exact_constants(self):
        path = self.root / 'evidence.json'
        path.write_text(json.dumps(reuse.APPROVED_EVIDENCE))
        pins, digest = reuse.validate_evidence(path, 'play-debug')
        self.assertEqual(pins, reuse.PINS['play-debug'])
        self.assertEqual(digest, reuse.hash_file(path))

    def test_duplicate_json_key_and_nonfinite_constant_fail(self):
        for data in ('{"classes":{},"classes":{}}', '{"value":NaN}'):
            with self.assertRaises(ValueError):
                reuse.strict_json(data)

    def test_baseline_missing_extra_and_wrong_source_records_fail(self):
        old = {'classes.dex': payload({'LA;': record(), 'LB;': record(2)})}
        def missing(inv):
            del inv['classes']['LB;']
        def extra(inv):
            inv['classes']['LC;'] = record()
        def source(inv):
            inv['sourceSha256'] = '0' * 64
        def annotations(inv):
            inv['classes']['LA;']['annotations']['class'] = []
        def dex_count(inv):
            inv['dexFiles']['classes.dex']['classCount'] -= 1
        for mutation in (missing, extra, source, annotations, dex_count):
            with self.subTest(mutation=mutation.__name__):
                with self.assertRaises(ValueError):
                    self.run_case(old, old, mutate_inventory=mutation)

    def test_changed_parser_class_member_body_or_metadata_loss_fails(self):
        old = {'classes.dex': payload({'LA;': record()})}
        new = {'classes.dex': payload({'LA;': record(2)})}
        def missing_class(item):
            item['classes'].clear()
        def extra_class(item):
            item['classes']['LB;'] = record()
        def missing_method(item):
            item['classes']['LA;']['methods'].clear()
        def missing_body(item):
            item['classes']['LA;']['methods']['run()V']['body'] = None
        def missing_payloads(item):
            item['classes']['LA;']['methods']['run()V']['body']['instructions'] = []
        def missing_catches(item):
            del item['classes']['LA;']['methods']['run()V']['body']['catches']
        def missing_field(item):
            item['classes']['LA;']['fields'].clear()
        def wrong_access(item):
            item['classes']['LA;']['access'] = 2
        def partial(item):
            item['completeBinaryMetadata'] = False
        def wrong_source(item):
            item['dexSha256'] = '0' * 64
        for mutation in (missing_class, extra_class, missing_method, missing_body, missing_payloads,
                         missing_catches, missing_field, wrong_access, partial, wrong_source):
            with self.subTest(mutation=mutation.__name__):
                with self.assertRaises(ValueError):
                    self.run_case(old, new, mutate_parser=mutation)

    def test_input_modified_or_replaced_during_read_fails(self):
        for replace in (False, True):
            path = self.archive_path({'classes.dex': payload({'LA;': record()})})
            with self.subTest(replace=replace), reuse.Apk(path) as apk:
                if replace:
                    fresh = self.root / 'replacement.apk'
                    fresh.write_bytes(path.read_bytes())
                    fresh.replace(path)
                else:
                    with path.open('ab') as stream:
                        stream.write(b'mutation')
                with self.assertRaisesRegex(ValueError, 'changed|replaced'):
                    apk.verify_unchanged()

    def test_original_parse_call_sequence_is_preserved(self):
        data = payload({'LA;': record()})
        parsed = parsed_item(data)
        helper = types.SimpleNamespace(decode_dump=mock.Mock(return_value='decoded'),
                                       parse_dump=mock.Mock(return_value=parsed))
        output = types.SimpleNamespace(returncode=0, stdout=b'raw dump', stderr=b'')
        with mock.patch.object(reuse.subprocess, 'run', return_value=output) as run:
            result = reuse.parse_changed_payload(helper, '/pinned/dexdump', 'classes.dex', data, self.root)
        target = self.root / 'classes.dex'
        self.assertEqual(target.read_bytes(), data)
        run.assert_called_once_with(['/pinned/dexdump', '-d', str(target)], capture_output=True)
        helper.decode_dump.assert_called_once_with(b'raw dump')
        helper.parse_dump.assert_called_once_with('decoded', data)
        self.assertEqual(result['dumpSha256'], reuse.sha(b'raw dump'))

    def test_original_dexdump_failure_and_diagnostics_fail_closed(self):
        helper = types.SimpleNamespace(decode_dump=mock.Mock(), parse_dump=mock.Mock())
        for returncode, stderr in ((1, b'failure'), (0, b'warning')):
            output = types.SimpleNamespace(returncode=returncode, stdout=b'raw dump', stderr=stderr)
            with self.subTest(returncode=returncode), mock.patch.object(reuse.subprocess, 'run', return_value=output):
                with self.assertRaisesRegex(ValueError, 'dexdump'):
                    reuse.parse_changed_payload(helper, '/pinned/dexdump', 'classes.dex', b'data', self.root)
        helper.parse_dump.assert_not_called()

    def test_persisted_metadata_accepts_exact_surrogate_pair_representation(self):
        pair = chr(0xdb3f) + chr(0xdffd)
        scalar = chr(0xdfffd)
        self.assertNotEqual(pair, scalar)
        self.assertTrue(reuse.same_persisted_value(pair, scalar))
        raw = record()
        raw['fields']['constant:I']['value'] = {'type': 23, 'value': pair}
        raw['annotations']['class'][0]['annotation']['elements']['value'] = {'type': 23, 'value': pair}
        persisted = json.loads(json.dumps({'LA;': raw}, ensure_ascii=True))
        binary = FakeDex(payload(persisted))
        binary.classes['LA;']['fields']['constant:I']['value']['value'] = pair
        binary.classes['LA;']['annotations']['class'][0]['annotation']['elements']['value']['value'] = pair
        reuse.validate_records(persisted, binary, 'surrogate-positive')
        self.assertEqual(persisted['LA;']['fields']['constant:I']['value']['value'], scalar)
        self.assertEqual(binary.classes['LA;']['fields']['constant:I']['value']['value'], pair)

    def test_persisted_metadata_rejects_changed_or_unpaired_or_replaced_literal(self):
        pair = chr(0xdb3f) + chr(0xdffd)
        wrong = [chr(0xdfffc), chr(0xdb3f),
                 chr(0xdffd), chr(0xfffd),
                 r'\udb3f\udffd']
        original = record()
        original['fields']['constant:I']['value'] = {'type': 23, 'value': pair}
        persisted = json.loads(json.dumps({'LA;': original}, ensure_ascii=True))
        binary = FakeDex(payload(persisted))
        binary.classes['LA;']['fields']['constant:I']['value']['value'] = pair
        for replacement in wrong:
            with self.subTest(replacement=ascii(replacement)):
                changed = copy.deepcopy(persisted)
                changed['LA;']['fields']['constant:I']['value']['value'] = replacement
                self.assertFalse(reuse.same_persisted_value(pair, replacement))
                with self.assertRaisesRegex(ValueError, 'raw metadata mismatch.*fields'):
                    reuse.validate_records(changed, binary, 'surrogate-negative')

    def test_persisted_key_collisions_fail_even_when_both_objects_match(self):
        pair = chr(0xdb3f) + chr(0xdffd)
        scalar = chr(0xdfffd)
        for value in ({pair: 1, scalar: 1}, {'nested': [{pair: 1, scalar: 2}]}):
            with self.subTest(value=ascii(value)):
                with self.assertRaisesRegex(ValueError, 'Duplicate persisted JSON key'):
                    reuse.same_persisted_value(value, copy.deepcopy(value))
        # A genuine distinct literal key remains a visible difference.
        self.assertFalse(reuse.same_persisted_value({pair: 1}, {chr(0xdfffc): 1}))

    def test_persisted_equality_does_not_drop_metadata_or_coerce_json_types(self):
        self.assertFalse(reuse.same_persisted_value({'value': 1}, {'value': True}))
        self.assertFalse(reuse.same_persisted_value({'value': 'x'}, {}))
        self.assertFalse(reuse.same_persisted_value({'value': ['a', 'b']}, {'value': ['b', 'a']}))

    def test_output_is_new_and_content_bound(self):
        path = self.root / 'output.json'
        digest = reuse.write_new_json(path, {'retained': record()})
        self.assertEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertEqual(json.loads(path.read_bytes()), {'retained': record()})
        with self.assertRaisesRegex(ValueError, 'overwrite'):
            reuse.write_new_json(path, {'different': True})


if __name__ == '__main__':
    unittest.main(verbosity=2)
