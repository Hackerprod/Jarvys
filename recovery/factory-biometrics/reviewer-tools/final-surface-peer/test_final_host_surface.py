#!/usr/bin/env python3
"""Synthetic binary-AXML/ZIP controls. No build tools, Gradle, signing or execution."""
import copy
import io
from pathlib import Path
import struct
import sys
import unittest
import zipfile
sys.dont_write_bytecode = True
import final_host_surface as audit


def xml(version=79, name='1.2.72-FACTORY-SENSORS', package='com.jarvys.agent', permission=None,
        version_type=16, version_id=0x0101021b, label='App'):
    """Minimal fixture serializer only; production parsing comes from apk_surface."""
    strings = ['android', audit.axml.ANDROID, 'manifest', 'versionCode', 'versionName',
               'package', name, package, 'uses-permission', 'name', 'application', 'label', label]
    if permission:
        strings.append(permission)
    def idx(s): return strings.index(s) if s is not None else 0xffffffff
    chunks = []
    pool, offsets = b'', []
    for s in strings:
        b = s.encode()
        assert len(b) < 128
        offsets.append(len(pool))
        pool += bytes([len(s), len(b)]) + b + b'\0'
    pool += b'\0' * (-len(pool) % 4)
    size = 28 + 4 * len(strings) + len(pool)
    chunks.append(struct.pack('<HHIIIIII', 1, 28, size, len(strings), 0, 0x100, 28 + 4 * len(strings), 0)
                  + struct.pack('<' + 'I' * len(strings), *offsets) + pool)
    ids = [0] * len(strings)
    ids[idx('versionCode')] = version_id
    ids[idx('versionName')] = 0x0101021c
    ids[idx('name')] = 0x01010003
    ids[idx('label')] = 0x01010001
    chunks.append(struct.pack('<HHI', 0x180, 8, 8 + 4 * len(ids)) + struct.pack('<' + 'I' * len(ids), *ids))
    def node(kind, data): return struct.pack('<HHIII', kind, 16, 16 + len(data), 1, 0xffffffff) + data
    chunks.append(node(0x100, struct.pack('<II', idx('android'), idx(audit.axml.ANDROID))))
    def start(tag, attributes):
        records = b''
        for namespace, key, kind, value in attributes:
            raw = idx(value) if kind == 3 else 0xffffffff
            records += struct.pack('<IIIHBBI', idx(namespace), idx(key), raw, 8, 0, kind, idx(value) if kind == 3 else value)
        return node(0x102, struct.pack('<IIHHHHHH', 0xffffffff, idx(tag), 20, 20, len(attributes), 0, 0, 0) + records)
    def end(tag): return node(0x103, struct.pack('<II', 0xffffffff, idx(tag)))
    version_value = name if version_type == 3 else version
    chunks.append(start('manifest', [(audit.axml.ANDROID, 'versionCode', version_type, version_value),
                                     (audit.axml.ANDROID, 'versionName', 3, name), (None, 'package', 3, package)]))
    if permission:
        chunks += [start('uses-permission', [(audit.axml.ANDROID, 'name', 3, permission)]), end('uses-permission')]
    chunks += [start('application', [(audit.axml.ANDROID, 'label', 3, label)]), end('application'), end('manifest')]
    chunks.append(node(0x101, struct.pack('<II', idx('android'), idx(audit.axml.ANDROID))))
    body = b''.join(chunks)
    return struct.pack('<HHI', 3, 8, 8 + len(body)) + body


def zipped(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for n, data in entries.items(): z.writestr(n, data)
    return out.getvalue()


def record(data):
    return None if data is None else {'sha256': audit.sha(data), 'bytes': len(data), 'gitBlob': 'fixture'}


class Fixture:
    def __init__(self, variant='full-debug'):
        self.variant = variant
        self.scope = {'hostVersion': {'before': {'versionCode': 79, 'versionName': '1.2.72-FACTORY-SENSORS'},
                                      'after': {'versionCode': 80, 'versionName': '1.2.73-FACTORY-BIOMETRICS'}},
                      'changedGuidanceAssets': [], 'sdkBinding': {'before': record(b'old sdk'), 'after': record(b'new sdk')},
                      'unchangedSourceDomains': {'otherHostAssets': {'sourceHashes': []}, 'otherRuntimeAssets': {'sourceHashes': []}}}
        self.templates = []
        self.hosts = []
        for side in ('before', 'after'):
            new = side == 'after'
            t = {'AndroidManifest.xml': xml(1, 'FACTORY_VERSION', 'com.jarvys.factory.template'),
                 audit.SDK: b'new sdk' if new else b'old sdk', 'assets/www/app.js': b'common runtime asset',
                 'resources.arsc': b'template resources', 'res/f.xml': b'template binary resource',
                 'lib/arm64-v8a/libtest.so': b'template native bytes', 'META-INF/source.txt': b'exact metadata',
                 'classes.dex': b'new template dex pending' if new else b'old template dex pending',
                 audit.PROFILES[0]: b'new template prof pending' if new else b'old template prof pending',
                 audit.PROFILES[1]: b'template metadata pending'}
            self.templates.append(t)
            h = {'AndroidManifest.xml': xml(80 if new else 79, self.scope['hostVersion'][side]['versionName']),
                 'resources.arsc': b'host resources', 'res/r.xml': b'host resource',
                 'lib/arm64-v8a/libtest.so': b'host native bytes', 'assets/common.txt': b'common host asset',
                 'META-INF/source.txt': b'exact metadata',
                 'classes.dex': b'new host dex pending' if new else b'old host dex pending'}
            if variant == 'full-release':
                h[audit.PROFILES[0]] = b'new host prof pending' if new else b'old host prof pending'
                h[audit.PROFILES[1]] = b'host metadata pending'
            for i in range(11):
                if new or i != 10: h['assets/skills/guide/' + str(i)] = (side + '-guidance-' + str(i)).encode()
            self.hosts.append(h)
        for i in range(11):
            n = 'assets/skills/guide/' + str(i)
            self.scope['changedGuidanceAssets'].append({'hostApkEntry': n, 'sourcePathUnderAppTree': 'app/src/main/' + n,
                                                       'before': record(self.hosts[0].get(n)), 'after': record(self.hosts[1].get(n))})
        for domain, prefix, entry, data in [('otherHostAssets', 'app/src/main/', 'assets/common.txt', b'common host asset'),
                                          ('otherRuntimeAssets', 'apk-runtime/src/main/', 'assets/www/app.js', b'common runtime asset')]:
            self.scope['unchangedSourceDomains'][domain]['sourceHashes'].append(
                {'sourcePathUnderAppTree': prefix + entry, 'before': record(data), 'after': record(data)})
        self.reembed()
    def reembed(self):
        for i in (0, 1):
            raw = zipped(self.templates[i])
            self.hosts[i][audit.TEMPLATE] = raw
            self.hosts[i][audit.TEMPLATE_SHA] = (audit.sha(raw) + '\n').encode()
    def run(self):
        a, b = [audit.read_apk(zipped(e), 'host ' + str(i)) for i, e in enumerate(self.hosts)]
        ta, tb = [audit.read_apk(zipped(e), 'template ' + str(i)) for i, e in enumerate(self.templates)]
        return audit.audit_host(a, b, ta, tb, self.scope, self.variant)


class FinalSurfaceControls(unittest.TestCase):
    def setUp(self): self.f = Fixture()
    def rejected(self):
        r = self.f.run()
        self.assertFalse(r['surfacePassed'], r)
        self.assertFalse(r['fullArtifactApproved'])
        return r
    def test_expected_source_bound_delta_all_three_variants(self):
        for v in audit.VARIANTS:
            r = Fixture(v).run()
            self.assertTrue(r['surfacePassed'], r['failures'])
            self.assertTrue(r['surfaceScopeOnly'])
            self.assertFalse(r['fullArtifactApproved'])
            self.assertEqual(11, len(r['guidanceBindings']))
            self.assertTrue(r['pendingDexDetailedAudit'])
            self.assertEqual(2 if v == 'full-release' else 0, len(r['pendingProfileDetailedAudit']))
    def test_resources_changed(self):
        self.f.hosts[1]['resources.arsc'] += b'x'; self.rejected()
    def test_res_payload_changed(self):
        self.f.hosts[1]['res/r.xml'] += b'x'; self.rejected()
    def test_native_changed(self):
        self.f.hosts[1]['lib/arm64-v8a/libtest.so'] += b'x'; self.rejected()
    def test_native_added(self):
        self.f.hosts[1]['lib/x86/libtest.so'] = b'x'; self.rejected()
    def test_manifest_wrong_version_code(self):
        self.f.hosts[1][audit.MANIFEST] = xml(81, '1.2.73-FACTORY-BIOMETRICS'); self.rejected()
    def test_manifest_wrong_version_name(self):
        self.f.hosts[1][audit.MANIFEST] = xml(80, 'wrong'); self.rejected()
    def test_manifest_version_type_not_normalized(self):
        self.f.hosts[1][audit.MANIFEST] = xml(80, '1.2.73-FACTORY-BIOMETRICS', version_type=3); self.rejected()
    def test_manifest_version_resource_id_not_normalized(self):
        self.f.hosts[1][audit.MANIFEST] = xml(80, '1.2.73-FACTORY-BIOMETRICS', version_id=123); self.rejected()
    def test_manifest_application_label_change(self):
        self.f.hosts[1][audit.MANIFEST] = xml(80, '1.2.73-FACTORY-BIOMETRICS', label='Other'); self.rejected()
    def test_host_permission_added(self):
        self.f.hosts[1][audit.MANIFEST] = xml(80, '1.2.73-FACTORY-BIOMETRICS', permission='android.permission.USE_BIOMETRIC'); self.rejected()
    def test_unchanged_biometric_host_permission_forbidden(self):
        for i in (0, 1):
            s = ('before', 'after')[i]
            self.f.hosts[i][audit.MANIFEST] = xml(79 + i, self.f.scope['hostVersion'][s]['versionName'], permission='android.permission.USE_BIOMETRIC')
        self.rejected()
    def test_guidance_candidate_wrong(self):
        self.f.hosts[1]['assets/skills/guide/0'] += b'x'; self.rejected()
    def test_guidance_baseline_wrong(self):
        self.f.hosts[0]['assets/skills/guide/0'] += b'x'; self.rejected()
    def test_guidance_missing(self):
        del self.f.hosts[1]['assets/skills/guide/10']; self.rejected()
    def test_guidance_new_entry_preexists_baseline(self):
        self.f.hosts[0]['assets/skills/guide/10'] = b'wrong'; self.rejected()
    def test_expected_guidance_unchanged(self):
        self.f.hosts[1]['assets/skills/guide/0'] = self.f.hosts[0]['assets/skills/guide/0']; self.rejected()
    def test_other_asset_changed(self):
        self.f.hosts[1]['assets/common.txt'] += b'x'; self.rejected()
    def test_both_other_assets_corrupt_source_binding(self):
        for h in self.f.hosts: h['assets/common.txt'] += b'x'
        self.rejected()
    def test_unknown_asset_added(self):
        self.f.hosts[1]['assets/unexpected.txt'] = b'x'; self.rejected()
    def test_top_level_sdk_added(self):
        self.f.hosts[1][audit.SDK] = b'new sdk'; self.rejected()
    def test_meta_inf_has_no_wildcard(self):
        self.f.hosts[1]['META-INF/source.txt'] += b'x'; self.rejected()
    def test_template_digest_uppercase(self):
        self.f.hosts[1][audit.TEMPLATE_SHA] = self.f.hosts[1][audit.TEMPLATE_SHA].upper(); self.rejected()
    def test_template_digest_extra_newline(self):
        self.f.hosts[1][audit.TEMPLATE_SHA] += b'\n'; self.rejected()
    def test_template_digest_no_newline(self):
        self.f.hosts[1][audit.TEMPLATE_SHA] = self.f.hosts[1][audit.TEMPLATE_SHA].rstrip(); self.rejected()
    def test_template_digest_wrong_hash(self):
        self.f.hosts[1][audit.TEMPLATE_SHA] = b'0' * 64 + b'\n'; self.rejected()
    def test_template_same_payloads_different_producer_archive(self):
        # ZIP archive bytes matter, even when unpacked payloads are the same.
        self.f.hosts[1][audit.TEMPLATE] += b'extra zip comment-like suffix'
        self.f.hosts[1][audit.TEMPLATE_SHA] = (audit.sha(self.f.hosts[1][audit.TEMPLATE]) + '\n').encode()
        self.rejected()
    def test_baseline_template_wrong(self):
        self.f.hosts[0][audit.TEMPLATE] = self.f.hosts[1][audit.TEMPLATE]
        self.f.hosts[0][audit.TEMPLATE_SHA] = self.f.hosts[1][audit.TEMPLATE_SHA]
        self.rejected()
    def test_template_permission_added(self):
        self.f.templates[1][audit.MANIFEST] = xml(1, 'FACTORY_VERSION', 'com.jarvys.factory.template', permission='android.permission.USE_BIOMETRIC')
        self.f.reembed(); self.rejected()
    def test_template_both_permission_nonempty(self):
        for t in self.f.templates: t[audit.MANIFEST] = xml(1, 'FACTORY_VERSION', 'com.jarvys.factory.template', permission='android.permission.INTERNET')
        self.f.reembed(); self.rejected()
    def test_template_manifest_label_change(self):
        self.f.templates[1][audit.MANIFEST] = xml(1, 'FACTORY_VERSION', 'com.jarvys.factory.template', label='Other')
        self.f.reembed(); self.rejected()
    def test_template_sdk_not_source(self):
        self.f.templates[1][audit.SDK] += b'x'; self.f.reembed(); self.rejected()
    def test_template_resource_changed(self):
        self.f.templates[1]['resources.arsc'] += b'x'; self.f.reembed(); self.rejected()
    def test_template_native_changed(self):
        self.f.templates[1]['lib/arm64-v8a/libtest.so'] += b'x'; self.f.reembed(); self.rejected()
    def test_template_asset_changed(self):
        self.f.templates[1]['assets/www/app.js'] += b'x'; self.f.reembed(); self.rejected()
    def test_template_metadata_changed(self):
        self.f.templates[1]['META-INF/source.txt'] += b'x'; self.f.reembed(); self.rejected()
    def test_debug_profile_addition_closed(self):
        self.f.hosts[1][audit.PROFILES[0]] = b'x'; self.rejected()
    def test_release_profile_removed_closed(self):
        self.f = Fixture('full-release'); del self.f.hosts[1][audit.PROFILES[1]]; self.rejected()
    def test_unknown_profile_closed(self):
        self.f.hosts[1]['assets/dexopt/other.prof'] = b'x'; self.rejected()
    def test_profile_payload_stays_pending_without_blanket_approval(self):
        r = Fixture('full-release').run()
        row = next(v for v in r['differingPayloads'] if v['entry'] == audit.PROFILES[0])
        self.assertEqual('PENDING_PROFILE_PRODUCER_AND_SEMANTIC_AUDIT', row['classification'])
        self.assertFalse(r['fullArtifactApproved'])
    def test_dex_addition_blocks_structure(self):
        self.f.hosts[1]['classes2.dex'] = b'x'; self.rejected()
    def test_noncanonical_dex_not_pending(self):
        self.f.hosts[1]['assets/hidden.dex'] = b'x'; r = self.rejected()
        self.assertEqual('UNEXPECTED_SURFACE_DELTA', next(x for x in r['differingPayloads'] if x['entry'] == 'assets/hidden.dex')['classification'])
    def test_dex_payload_only_pending(self):
        r = self.f.run()
        self.assertEqual('PENDING_DEX_DETAILED_AUDIT', next(x for x in r['differingPayloads'] if x['entry'] == 'classes.dex')['classification'])
    def test_every_difference_has_proof_classification(self):
        r = self.f.run()
        expected = {n for n in self.f.hosts[0].keys() | self.f.hosts[1].keys() if self.f.hosts[0].get(n) != self.f.hosts[1].get(n)}
        self.assertEqual(expected, {x['entry'] for x in r['differingPayloads']})
        self.assertTrue(all(x['classification'] for x in r['differingPayloads']))
    def test_malformed_axml_fails_closed(self):
        data = dict(self.f.hosts[1]); data[audit.MANIFEST] = b'invalid'
        with self.assertRaises(ValueError): audit.read_apk(zipped(data), 'bad')
    def test_duplicate_zip_entry_fails_closed(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as z:
            z.writestr(audit.MANIFEST, xml()); z.writestr(audit.MANIFEST, xml())
        with self.assertRaisesRegex(ValueError, 'Duplicate'): audit.read_apk(stream.getvalue(), 'duplicate')
    def test_report_path_cannot_escape_helper_directory(self):
        with self.assertRaisesRegex(ValueError, 'within'): audit.write_report(Path('/tmp/forbidden-surface-output.json'), {})


if __name__ == '__main__': unittest.main(verbosity=2)
