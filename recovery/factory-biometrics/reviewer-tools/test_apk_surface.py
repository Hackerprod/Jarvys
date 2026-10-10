#!/usr/bin/env python3
import copy
import os
import pathlib
import unittest
import apk_surface as audit

ROOT = pathlib.Path(__file__).parent
FIXTURES = pathlib.Path(os.environ.get('JARVYS_AUDIT_FIXTURES', ROOT)).resolve()


class SurfaceTests(unittest.TestCase):
    def setUp(self):
        self.a = audit.inventory(FIXTURES / 'probe-v1.apk')
        self.b = audit.inventory(FIXTURES / 'probe-v2.apk')
        self.policy = {'AndroidManifest.xml': {'beforeSha256': self.a['entries']['AndroidManifest.xml']['sha256'],
                                              'afterSha256': self.b['entries']['AndroidManifest.xml']['sha256']}}

    def test_real_manifest(self):
        self.assertEqual(1, len(self.a['permissions']))
        permission = self.a['permissions'][0]['attrs'][audit.ANDROID + ':name']
        self.assertEqual('android.permission.USE_BIOMETRIC', permission['value'])
        self.assertEqual(0x01010003, permission['resourceId'])

    def test_self_equivalence(self):
        self.assertTrue(audit.compare(self.a, self.a)['passedExactSurfacePolicy'])

    def test_version_only_explicit_policy(self):
        self.assertTrue(audit.compare(self.a, self.b, self.policy, True)['passedExactSurfacePolicy'])
        self.assertFalse(audit.compare(self.a, self.b, self.policy, False)['passedExactSurfacePolicy'])

    def test_version_type_change_not_ignored(self):
        modified = copy.deepcopy(self.b)
        modified['manifest']['nodes'][0]['attrs'][audit.ANDROID + ':versionCode']['type'] = 3
        self.assertFalse(audit.compare(self.a, modified, self.policy, True)['manifestEquivalent'])

    def test_version_resource_id_change_not_ignored(self):
        modified = copy.deepcopy(self.b)
        modified['manifest']['nodes'][0]['attrs'][audit.ANDROID + ':versionCode']['resourceId'] ^= 1
        self.assertFalse(audit.compare(self.a, modified, self.policy, True)['manifestEquivalent'])

    def test_missing_version_not_ignored(self):
        modified = copy.deepcopy(self.b)
        del modified['manifest']['nodes'][0]['attrs'][audit.ANDROID + ':versionCode']
        self.assertFalse(audit.compare(self.a, modified, self.policy, True)['manifestEquivalent'])

    def test_permission_delta_not_ignored(self):
        modified = copy.deepcopy(self.b)
        for node in modified['manifest']['nodes']:
            if node['path'].endswith('/uses-permission'):
                node['attrs'][audit.ANDROID + ':name']['value'] = 'android.permission.USE_FINGERPRINT'
        self.assertFalse(audit.compare(self.a, modified, self.policy, True)['manifestEquivalent'])

    def test_mismatching_expected_hash_preserved(self):
        policy = copy.deepcopy(self.policy); policy['AndroidManifest.xml']['afterSha256'] = '0' * 64
        result = audit.compare(self.a, self.b, policy, True)
        self.assertEqual(1, len(result['unexpectedEntryChanges']))
        self.assertFalse(result['passedExactSurfacePolicy'])

    def test_profile_changes_not_broadly_allowed(self):
        modified = copy.deepcopy(self.b)
        modified['entries']['assets/dexopt/baseline.prof'] = {'sha256': 'f' * 64, 'size': 3}
        result = audit.compare(self.a, modified, self.policy, True)
        self.assertEqual('assets/dexopt/baseline.prof', result['unexpectedEntryChanges'][0]['entry'])
        self.assertFalse(result['passedExactSurfacePolicy'])

    def test_resources_and_native_stay_enforced(self):
        for key in ('nativeLibraries', 'resources'):
            modified = copy.deepcopy(self.b); modified[key]['unexpected'] = 'f' * 64
            self.assertFalse(audit.compare(self.a, modified, self.policy, True)['passedExactSurfacePolicy'])

    def test_unused_policy_preserved(self):
        result = audit.compare(self.a, self.a, self.policy)
        self.assertEqual(['AndroidManifest.xml'], result['unusedPolicyEntries'])
        self.assertFalse(result['passedExactSurfacePolicy'])

    def test_unknown_policy_entry_rejected(self):
        with self.assertRaisesRegex(ValueError, 'nonexistent'):
            audit.compare(self.a, self.b, {'unknown': {'beforeSha256': None, 'afterSha256': 'f' * 64}})


if __name__ == '__main__': unittest.main(verbosity=2)
