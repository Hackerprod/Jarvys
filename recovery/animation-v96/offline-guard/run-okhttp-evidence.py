#!/usr/bin/env python3
"""Prove current child-loader smoke sidecars and concurrent definition/failed-loader attribution."""
from pathlib import Path
import json
import os
import subprocess
import time
root = Path(__file__).resolve().parent
jdk = Path(os.environ.get('JDK') or os.environ['JAVA_HOME'])
lib = Path(os.environ['OKHTTP_LIB_DIR']).resolve()
report = root / 'reports' / ('okhttp-evidence-' + str(time.time_ns()))
report.mkdir()
rows = []
for label, missing, expected_complete in [('two-good-loaders', False, True), ('failed-then-good-loaders', True, False)]:
    directory = report / label
    directory.mkdir()
    paths = [lib / name for name in ('okhttp-jvm-5.2.3.jar', 'okio-jvm-3.16.4.jar', 'kotlin-stdlib-2.2.20.jar', 'annotations-13.0.jar')]
    if missing:
        paths.insert(0, root / 'build' / 'okhttp-negative' / 'missing-target.jar')
    command = [str(jdk / 'bin/java'), '-XX:-CreateCoredumpOnCrash', '-Djarvys.offlineGuard.okhttp=required',
        f'-Djarvys.offlineGuard.smokeMissingLoader={str(missing).lower()}', f'-Djarvys.offlineGuard.reportDir={directory}',
        f'-javaagent:{root}/jarvys-offline-guard.jar', '-cp', f'{root}/okhttp-smoke.jar:{root}/smoke.jar',
        'guard.okhttp.MultiLoaderSmoke', *map(str, paths)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=20, cwd=root)
    output = result.stdout + result.stderr
    (directory / 'process.log').write_text(output)
    assert result.returncode == 0 and 'OKHTTP_MULTILOADER_PASS' in output, label
    files = list(directory.glob('*.installed.json'))
    assert len(files) == 1, label
    install = json.loads(files[0].read_text())
    final = json.loads(Path(str(files[0]).replace('.installed.json', '.shutdown.json')).read_text())
    assert all(final[k] == v for k, v in install.items()), label
    assert final['okhttpRequired'] is True and final['okhttpComplete'] == expected_complete, label
    assert final['okhttpVerifiedLoaders'] == 2 and final['okhttpVerifiedHooks'] == 10, label
    assert final['okhttpTransformFailures'] == 0, label
    assert final['okhttpVerificationFailures'] == int(missing), label
    assert final['blockedAttempts'] == 0, label
    rows.append({'case': label, 'verifiedLoaders': 2, 'verifiedHooks': 10, 'complete': expected_complete, 'pass': True})
# Current positive smoke was rebuilt/rerun before this evidence check.
smoke_files = list((root / 'reports' / 'okhttp').glob('*.shutdown.json'))
latest = max(smoke_files, key=lambda p: p.stat().st_mtime_ns)
final = json.loads(latest.read_text())
assert final['okhttpComplete'] is True and final['okhttpVerifiedLoaders'] == 1 and final['okhttpVerifiedHooks'] == 5
assert final['okhttpTransformFailures'] == 0 and final['okhttpVerificationFailures'] == 0
assert final['blockedAttempts'] == 18
output = (root / 'reports' / 'okhttp-smoke.txt').read_text()
assert 'checks=99 blocked=18 externalDns=0 externalSockets=0 fixtureHits=8 loader=URLClassLoader' in output
assert 'private?secret' not in output and '.invalid' not in output
rows.append({'case': 'actual-child-loader-positive', 'verifiedLoaders': 1, 'verifiedHooks': 5, 'blockedAttempts': 18, 'pass': True})
(root / 'reports' / 'okhttp-evidence-results.json').write_text(json.dumps(rows, indent=2) + '\n')
print(json.dumps(rows, indent=2))
