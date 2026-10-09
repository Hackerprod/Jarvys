#!/usr/bin/env python3
"""Independently inspect synthetic restored-key APK evidence; never opens a private key."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--fixtures', type=pathlib.Path, required=True)
parser.add_argument('--template', type=pathlib.Path, required=True)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()


def command(*values):
    return subprocess.check_output(values, text=True, stderr=subprocess.STDOUT)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def entries(path):
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None
        assert len(archive.namelist()) == len(set(archive.namelist()))
        return {name: archive.read(name) for name in archive.namelist()}


def dex(values):
    return {name: sha(value) for name, value in values.items() if re.fullmatch(r'classes\d*\.dex', name)}


baseline_dex = dex(entries(args.template))
assert baseline_dex
rows = []
for metadata in sorted(args.fixtures.glob('*/recovery-v[12].json')):
    record = json.loads(metadata.read_text())
    version = record['versionCode']
    assert version in (1, 2)
    assert record['ephemeralFixtureKeyOnly'] is True
    assert record['independentTemporaryStorage'] is True
    assert record['rawPrivateKeyPersisted'] is False
    assert record['deviceRestoreTested'] is False
    assert record['restoredFromEncryptedBackup'] is (version == 2)
    unsigned = metadata.with_name(metadata.stem + '-unsigned.apk')
    signed = metadata.with_name(metadata.stem + '-signed.apk')
    before, after = entries(unsigned), entries(signed)
    assert before == after, 'Signature changed APK payload'
    assert dex(before) == baseline_dex
    assert sha(unsigned.read_bytes()) == record['unsignedSha256']
    assert sha(signed.read_bytes()) == record['signedSha256']
    for path in (unsigned, signed):
        badging = command('aapt2', 'dump', 'badging', str(path))
        assert f"name='{record['appId']}' versionCode='{version}' versionName='{record['versionName']}'" in badging
        assert 'application-debuggable' not in badging
        assert 'uses-permission:' not in command('aapt2', 'dump', 'permissions', str(path))
        command('zipalign', '-c', '-P', '16', '4', str(path))
    verify = command('apksigner', 'verify', '--verbose', '--print-certs', str(signed))
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in verify
    assert 'Verified using v3 scheme (APK Signature Scheme v3): true' in verify
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)$', verify, re.M)
    assert certificates == [record['certificateSha256']]
    rows.append({'fixture': metadata.parent.name + '/' + metadata.stem,
                 'appId': record['appId'], 'versionCode': version,
                 'certificateSha256': certificates[0], 'signedSha256': record['signedSha256'],
                 'payloadPreserved': True, 'templateDexPreserved': True,
                 'officialSignatureAlignmentAndManifestChecks': True})
assert len(rows) >= 2 and len(rows) % 2 == 0
for offset in range(0, len(rows), 2):
    first, update = rows[offset:offset + 2]
    assert first['appId'] == update['appId']
    assert first['certificateSha256'] == update['certificateSha256']
    assert first['versionCode'] == 1 and update['versionCode'] == 2
result = {'passed': True, 'hostOnly': True, 'apkCount': 2 * len(rows), 'rows': rows,
          'physicalRestoreInstallationAndDataRetentionTested': False}
args.output.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
