#!/usr/bin/env python3
import copy
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
sys.dont_write_bytecode = True
import final_host_surface as surface
import profile_producer_binding as audit
import test_profile_payloads as fixtures


class ProfileProducerControls(unittest.TestCase):
    def setUp(self):
        fixtures.ProfileControls.setUpClass()
        self.fixture = fixtures.ProfileControls()
        self.tmp = tempfile.TemporaryDirectory(prefix='profile-control-', dir=surface.HERE)
        self.addCleanup(self.tmp.cleanup)
        folder = Path(self.tmp.name)
        self.apk, self.prof, self.meta = (folder / n for n in ('candidate.apk', 'baseline.prof', 'baseline.profm'))
        self.p, self.m = self.fixture.inputs()
        self.prof.write_bytes(self.p); self.meta.write_bytes(self.m)
        self.package()
    def package(self, dex=None):
        with zipfile.ZipFile(self.apk, 'w') as z:
            z.writestr('classes.dex', self.fixture.data if dex is None else dex)
            z.writestr(surface.PROFILES[0], self.p); z.writestr(surface.PROFILES[1], self.m)
    def test_exact_producer_and_independent_parser_bound(self):
        result = audit.bind_pair(self.apk, self.prof, self.meta)
        self.assertEqual(surface.sha(self.p), result['producerFiles']['profile']['sha256'])
        self.assertEqual(2, len(result['independentParserInventory']['semanticProfile']['classes.dex']['methods']))
    def test_producer_profile_mismatch(self):
        self.prof.write_bytes(self.p + b'x')
        with self.assertRaisesRegex(ValueError, 'same-variant'): audit.bind_pair(self.apk, self.prof, self.meta)
    def test_producer_metadata_mismatch(self):
        self.meta.write_bytes(self.m + b'x')
        with self.assertRaisesRegex(ValueError, 'same-variant'): audit.bind_pair(self.apk, self.prof, self.meta)
    def test_matching_bad_profile_still_rejected_by_parser(self):
        self.p = b'invalid'; self.prof.write_bytes(self.p); self.package()
        with self.assertRaises(ValueError): audit.bind_pair(self.apk, self.prof, self.meta)
    def test_wrong_dex_crc_rejected(self):
        self.p, self.m = self.fixture.inputs(crc=0); self.prof.write_bytes(self.p); self.meta.write_bytes(self.m); self.package()
        with self.assertRaisesRegex(ValueError, 'CRC'): audit.bind_pair(self.apk, self.prof, self.meta)
    def test_unknown_profile_entry_rejected(self):
        with zipfile.ZipFile(self.apk, 'a') as z: z.writestr('assets/dexopt/other.prof', b'unknown')
        with self.assertRaisesRegex(ValueError, 'no profile-path wildcard'): audit.bind_pair(self.apk, self.prof, self.meta)
    def test_exact_variant_paths(self):
        paths = audit.producer_paths('/fixture', 'full-release')
        self.assertEqual(Path('/fixture/app/app/build/intermediates/binary_art_profile/fullRelease/compileFullReleaseArtProfile/baseline.prof'), paths['profile'])
        self.assertEqual(Path('/fixture/app/app/build/outputs/apk/full/release/app-full-release-unsigned.apk'), paths['apk'])
        paths = audit.producer_paths('/fixture', 'runtime-template')
        self.assertEqual(Path('/fixture/app/apk-runtime/build/intermediates/binary_art_profile_metadata/release/compileReleaseArtProfile/baseline.profm'), paths['metadata'])
    def test_debug_producer_target_forbidden(self):
        for target in ('full-debug', 'play-debug', 'play-release', '*'):
            with self.assertRaises(ValueError): audit.producer_paths('/fixture', target)
    def test_semantic_changes_never_flattened(self):
        a = {'classes.dex': {'methods': {'method1': 1, 'method2': 7}, 'classes': ['A'], 'metadataClasses': ['A']}}
        b = {'classes.dex': {'methods': {'method1': 3, 'method3': 6}, 'classes': ['B'], 'metadataClasses': ['B']}}
        r = audit.semantic_delta(a, b)['classes.dex']
        self.assertEqual({'method2': 7}, r['methodsRemoved']); self.assertEqual({'method3': 6}, r['methodsAdded'])
        self.assertEqual({'method1': {'before': 1, 'after': 3}}, r['methodFlagsChanged'])
        self.assertEqual(['A'], r['metadataClassesRemoved']); self.assertEqual(['B'], r['classesAdded'])
    def test_semantic_equality(self):
        value = {'classes.dex': {'methods': {'m': 7}, 'classes': [], 'metadataClasses': []}}
        self.assertEqual({}, audit.semantic_delta(value, copy.deepcopy(value)))


if __name__ == '__main__': unittest.main(verbosity=2)
