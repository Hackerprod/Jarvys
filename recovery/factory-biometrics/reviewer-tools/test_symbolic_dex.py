#!/usr/bin/env python3
"""Synthetic positive/negative controls for the rebuilt independent comparator."""
import copy
import json
import os
import pathlib
import re
import tempfile
import unittest
import zipfile
import symbolic_dex as audit

ROOT = pathlib.Path(__file__).parent
FIXTURES = pathlib.Path(os.environ.get('JARVYS_AUDIT_FIXTURES', ROOT)).resolve()

def stable_string(value): return '\n'.join(value)


def method(name='access$000', proto='()I', value=15):
    return {'group': 'direct', 'access': 0x1008, 'prototype': proto,
            'body': {'registers': 1, 'ins': 0, 'outs': 0,
                     'instructions': ['const/16 v0, #int %d // #%x' % (value, value), 'return v0'], 'catches': []}}


def klass(methods=None):
    return {'access': 1, 'superclass': 'Ljava/lang/Object;', 'interfaces': [], 'fields': {},
            'methods': methods or {}, 'annotations': {'class': [], 'fields': {}, 'methods': {}, 'parameters': {}}}


def inventory(classes):
    return {'format': audit.FORMAT, 'classes': classes, 'completeBinaryMetadata': True}


def dump(code, size=5, access='0x0009 (PUBLIC STATIC)'):
    return """Processing 'fixture.dex'...
Opened 'fixture.dex', DEX version '037'
Class #0            -
  Class descriptor  : 'LExample;'
  Access flags      : 0x0001 (PUBLIC)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
  Static fields     -
  Instance fields   -
  Direct methods    -
    #0              : (in LExample;)
      name          : 'run'
      type          : '()V'
      access        : %s
      code          -
      registers     : 1
      ins           : 0
      outs          : 0
      insns size    : %s 16-bit code units
%s
      catches       : (none)
      positions     :
        0x0000 line=10
      locals        :
  Virtual methods   -
  source_file_idx   : 1 (Fixture.java)
""" % (access, size, code)


class SymbolicTests(unittest.TestCase):
    def test_real_d8_fixture(self):
        item = audit.parse_dump(audit.decode_dump((FIXTURES / 'probe.dexdump.txt').read_bytes()), (FIXTURES / 'probe-dex/classes.dex').read_bytes())
        self.assertTrue(item['completeBinaryMetadata'])
        self.assertEqual(3, len(item['classes']))
        self.assertTrue(audit.compare(item, item)['equivalent'])
        cls = item['classes']['LSymbolicProbe;']
        self.assertTrue(cls['annotations']['class'])
        self.assertEqual('marker', cls['fields']['MARK:Ljava/lang/String;']['value']['value'])
        insns = cls['methods']['branch(I)I']['body']['instructions']
        self.assertTrue(any(isinstance(i, dict) and i['opcode'] == 'sparse-switch-data' for i in insns))
        self.assertTrue(cls['methods']['branch(I)I']['body']['catches'])
        array = cls['methods']['array()[I']['body']['instructions']
        self.assertTrue(any(isinstance(i, dict) and i['opcode'] == 'array-data' and i['payload']['count'] == 12 for i in array))
        dense = cls['methods']['dense(I)I']['body']['instructions']
        self.assertTrue(any(isinstance(i, dict) and i['opcode'] == 'packed-switch-data' for i in dense))
        self.assertIn('\\ud83d\\udd12', stable_string(cls['methods']['unicode()Ljava/lang/String;']['body']['instructions']))

    def test_indices_offsets_and_debug_ignored(self):
        a = dump('000100: 1a00 0100 |0000: const-string v0, "one" // string@0001\n'
                 '000104: 2802 |0002: goto 0004 // +0002\n'
                 '000106: 0000 |0003: nop\n000108: 0e00 |0004: return-void')
        b = dump('000200: 1b00 ffff 0100 |0000: const-string/jumbo v0, "one" // string@0001ffff\n'
                 '000206: 2802 |0003: goto 0005 // +0002\n'
                 '000208: 0000 |0004: nop\n00020a: 0e00 |0005: return-void', size=6).replace('line=10', 'line=987')
        report = audit.compare(audit.parse_dump(a), audit.parse_dump(b))
        self.assertTrue(report['equivalent']); self.assertFalse(report['completeBinaryMetadata'])

    def test_changed_branch_target_not_hidden(self):
        a = dump('000100: 2803 |0000: goto 0003 // +0003\n000102: 0000 |0001: nop\n000104: 0000 |0002: nop\n000106: 0e00 |0003: return-void', 4)
        b = a.replace('goto 0003 // +0003', 'goto 0002 // +0002')
        self.assertFalse(audit.compare(audit.parse_dump(a), audit.parse_dump(b))['equivalent'])

    def test_missing_disassembly_rejected(self):
        with self.assertRaisesRegex(ValueError, 'disassembly'): audit.parse_dump(dump(''))

    def test_unresolved_reference_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unresolved'):
            audit.parse_dump(dump('000100: 7100 0100 0000 |0000: invoke-static {}, method@0001\n000106: 0e00 |0003: return-void', 4))

    def test_payload_without_binary_rejected(self):
        with self.assertRaisesRegex(ValueError, 'requires DEX'):
            audit.parse_dump(dump('000100: 0002 |0000: sparse-switch-data (5 units)'))

    def test_bad_boundary_rejected(self):
        with self.assertRaisesRegex(ValueError, 'boundaries'):
            audit.parse_dump(dump('000100: 2804 |0000: goto 0004 // +0004\n000102: 0e00 |0001: return-void', 2))

    def test_invalid_opcode_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Invalid opcode'):
            audit.parse_dump(dump('000100: ff00 |0000: (invalid)\n000102: 0e00 |0001: return-void', 2))

    def test_checksum_failure_rejected(self):
        data = bytearray((FIXTURES / 'probe-dex/classes.dex').read_bytes()); data[-1] ^= 1
        with self.assertRaisesRegex(ValueError, 'SHA-1'): audit.Dex(bytes(data))

    def assert_bad_dex_archive(self, dex_names):
        with tempfile.TemporaryDirectory(prefix='negative-dex-archive-', dir=ROOT) as temp:
            path = pathlib.Path(temp) / 'input.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('assets/readme.txt', b'not a DEX file')
                for name in dex_names:
                    archive.writestr(name, (FIXTURES / 'probe-dex/classes.dex').read_bytes())
            # This must reject the APK inventory before attempting a tool invocation.
            with self.assertRaisesRegex(ValueError, 'Noncanonical, missing, or gapped DEX'):
                audit.extract(path, '/nonexistent-dexdump-must-not-run')

    def test_nested_extra_dex_rejected(self):
        self.assert_bad_dex_archive(['classes.dex', 'assets/hidden.dex'])

    def test_noncanonical_extra_dex_rejected(self):
        for extra in ('other.dex', 'classes1.dex', 'classes01.dex', 'classes0.dex', 'classes2.DEX'):
            with self.subTest(extra=extra):
                self.assert_bad_dex_archive(['classes.dex', extra])

    def test_multidex_gap_rejected(self):
        for names in (['classes.dex', 'classes3.dex'], ['classes.dex', 'classes2.dex', 'classes4.dex']):
            with self.subTest(names=names): self.assert_bad_dex_archive(names)

    def test_missing_primary_dex_rejected(self):
        self.assert_bad_dex_archive(['classes2.dex'])

    def test_no_dex_rejected(self):
        self.assert_bad_dex_archive([])

    def test_nonsynthetic_ordinal_name_rename_not_reconciled(self):
        for old, new in (('access$000()I', 'access$100()I'), ('lambda$run$0()I', 'lambda$run$1()I')):
            body = method(); body['access'] = 0x0009  # PUBLIC STATIC, deliberately not SYNTHETIC.
            a = inventory({'LApp;': klass({old: body})})
            b = inventory({'LApp;': klass({new: copy.deepcopy(body)})})
            with self.subTest(old=old):
                report = audit.compare(a, b)
                self.assertFalse(report['equivalent'])
                self.assertEqual([], report['ordinalMappings'])
                self.assertEqual(old, report['changedClasses'][0]['removedMethods'][0]['method'])
                self.assertEqual(new, report['changedClasses'][0]['addedMethods'][0]['method'])

    def test_exact_accessor_rename(self):
        before = inventory({'LApp;': klass({'access$000()I': method()})})
        after = inventory({'LApp;': klass({'access$100()I': method()})})
        report = audit.compare(before, after)
        self.assertTrue(report['equivalent']); self.assertEqual(1, len(report['ordinalMappings']))

    def test_accessor_permutation_and_calls(self):
        call = method(); call['body']['instructions'] = ['invoke-static {}, LApp;.access$000:()I', 'return v0']
        a = inventory({'LApp;': klass({'access$000()I': method(value=15), 'access$100()I': method(value=2), 'run()I': call})})
        b = copy.deepcopy(a)
        b['classes']['LApp;']['methods']['access$000()I'] = method(value=2)
        b['classes']['LApp;']['methods']['access$100()I'] = method(value=15)
        b['classes']['LApp;']['methods']['run()I']['body']['instructions'][0] = 'invoke-static {}, LApp;.access$100:()I'
        report = audit.compare(a, b)
        self.assertTrue(report['equivalent']); self.assertEqual(2, len(report['ordinalMappings']))

    def test_accessor_rename_must_preserve_prototype(self):
        a = inventory({'LApp;': klass({'access$000()I': method()})})
        b = inventory({'LApp;': klass({'access$100()J': method(proto='()J')})})
        self.assertFalse(audit.compare(a, b)['equivalent'])

    def test_accessor_rename_must_preserve_access(self):
        a = inventory({'LApp;': klass({'access$000()I': method()})}); b = copy.deepcopy(a)
        b['classes']['LApp;']['methods']['access$000()I']['access'] ^= 1
        self.assertFalse(audit.compare(a, b)['equivalent'])

    def test_mask_and_authentication_type_changes_exposed(self):
        for before, after in ((15, 255), (2, 1)):
            a = inventory({'LApp;': klass({'access$000()I': method(value=before)})})
            b = inventory({'LApp;': klass({'access$100()I': method(value=after)})})
            r = audit.compare(a, b)
            self.assertFalse(r['equivalent']); self.assertEqual([], r['ordinalMappings'])

    def test_ordinary_method_rename_not_reconciled(self):
        a = inventory({'LApp;': klass({'ordinary()I': method()})})
        b = inventory({'LApp;': klass({'renamed()I': method()})})
        self.assertFalse(audit.compare(a, b)['equivalent'])

    def test_lambda_name_family_preserved(self):
        a = inventory({'LApp;': klass({'lambda$old$0()I': method()})})
        b = inventory({'LApp;': klass({'lambda$new$1()I': method()})})
        self.assertFalse(audit.compare(a, b)['equivalent'])

    def test_ambiguous_accessor_bodies_remain_deltas(self):
        a = inventory({'LApp;': klass({'access$000()I': method(), 'access$100()I': method()})})
        b = inventory({'LApp;': klass({'access$200()I': method(), 'access$300()I': method()})})
        r = audit.compare(a, b)
        self.assertFalse(r['equivalent']); self.assertEqual([], r['ordinalMappings'])
        self.assertEqual(2, len(r['changedClasses'][0]['addedMethods']))

    def test_lambda_class_exact_rename(self):
        old = 'LApp$$ExternalSyntheticLambda0;'; new = 'LApp$$ExternalSyntheticLambda7;'
        body = method(); body['body']['instructions'] = ['iget v0, v0, ' + old + '.field:I', 'return v0']
        a = inventory({old: klass({'run()I': body})})
        b = inventory({new: audit.translate(a['classes'][old], {old: new}, {})})
        r = audit.compare(a, b)
        self.assertTrue(r['equivalent']); self.assertEqual(1, len(r['ordinalMappings']))

    def test_anonymous_class_exact_renumbering(self):
        old = 'LApp$13;'; new = 'LApp$14;'
        body = method(); body['body']['instructions'] = ['iget v0, v0, ' + old + '.field:I', 'return v0']
        a = inventory({old: klass({'run()I': body})})
        b = inventory({new: audit.translate(a['classes'][old], {old: new}, {})})
        self.assertTrue(audit.compare(a, b)['equivalent'])

    def test_lambda_class_different_method_stays_delta(self):
        a = inventory({'LApp$$ExternalSyntheticLambda0;': klass({'run()I': method(value=15)})})
        b = inventory({'LApp$$ExternalSyntheticLambda1;': klass({'run()I': method(value=2)})})
        self.assertFalse(audit.compare(a, b)['equivalent'])

    def test_lambda_class_different_owner_stays_delta(self):
        a = inventory({'LOne$$ExternalSyntheticLambda0;': klass()})
        b = inventory({'LTwo$$ExternalSyntheticLambda1;': klass()})
        self.assertFalse(audit.compare(a, b)['equivalent'])

    def test_literal_strings_never_remapped(self):
        old = 'LApp$$ExternalSyntheticLambda0;'; new = 'LApp$$ExternalSyntheticLambda1;'
        value = {'instructions': ['const-string v0, "' + old + '"', 'invoke-static {}, LApp;.access$000:()I'],
                 'constant': {'type': 0x17, 'value': old}, 'type': old}
        translated = audit.translate(value, {old: new}, {('LApp;', 'access$000()I'): 'access$100()I'})
        self.assertEqual(value['instructions'][0], translated['instructions'][0])
        self.assertEqual(old, translated['constant']['value']); self.assertEqual(new, translated['type'])
        self.assertIn('access$100', translated['instructions'][1])

    def test_new_class_preserved_in_full(self):
        a = inventory({'LApp;': klass()}); b = inventory({'LApp;': klass(), 'Landroidx/Surprise;': klass({'run()I': method()})})
        r = audit.compare(a, b)
        self.assertFalse(r['equivalent']); self.assertEqual(b['classes']['Landroidx/Surprise;'], r['addedClasses']['Landroidx/Surprise;'])

    def test_annotation_delta_preserved(self):
        a = inventory({'LApp;': klass()}); b = copy.deepcopy(a)
        b['classes']['LApp;']['annotations']['class'] = [{'visibility': 1, 'annotation': {'type': 'LExample;', 'elements': {}}}]
        r = audit.compare(a, b)
        self.assertFalse(r['equivalent']); self.assertIn('annotations', r['changedClasses'][0]['metadataChanges'])


if __name__ == '__main__': unittest.main(verbosity=2)
