#!/usr/bin/env python3
"""Verify lazy test-loader failure gates and per-process evidence; no external networking."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import time
import zipfile

root = Path(__file__).resolve().parent
jdk = Path(os.environ.get('JDK') or os.environ['JAVA_HOME'])
lib = Path(os.environ['OKHTTP_LIB_DIR']).resolve()
if not jdk.is_absolute():
    raise SystemExit('Use an absolute verified JDK path')
if subprocess.run(['git', '-C', str(root), 'rev-parse', '--is-inside-work-tree'], capture_output=True).returncode == 0:
    raise SystemExit('Generated fixtures must remain outside Git')
original = lib / 'okhttp-jvm-5.2.3.jar'
assert hashlib.sha256(original.read_bytes()).hexdigest() == '68927bd7f203d214ff62ebe5e80a8bea8de29dcea321ebf90989b371a9969195'
work = root / 'build' / 'okhttp-negative'
work.mkdir(exist_ok=True)
for case in ('missing-target', 'malformed-shape'):
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(work / (case + '.jar'), 'w') as target:
        for entry in source.infolist():
            if case == 'missing-target' and entry.filename == 'okhttp3/internal/connection/ConnectPlan.class':
                continue
            contents = source.read(entry)
            if case == 'malformed-shape' and entry.filename == 'okhttp3/internal/connection/RealCall.class':
                assert b'\x00\x07execute' in contents
                contents = contents.replace(b'\x00\x07execute', b'\x00\x07executX', 1)
            target.writestr(entry, contents)

reports = root / 'reports' / ('okhttp-negative-' + str(time.time_ns()))
reports.mkdir()
results = []
for label, mode, jar, main, should_pass, transforms_failed in [
    ('valid-preflight', 'required', original, 'guard.okhttp.PreflightSmoke', True, 0),
    ('missing-all-targets', 'required', None, 'guard.okhttp.PreflightSmoke', False, 0),
    ('missing-target-preflight', 'required', work / 'missing-target.jar', 'guard.okhttp.PreflightSmoke', False, 0),
    ('missing-target-first-call', 'required', work / 'missing-target.jar', 'guard.okhttp.OkHttpSmoke', False, 0),
    ('malformed-shape-preflight', 'required', work / 'malformed-shape.jar', 'guard.okhttp.PreflightSmoke', False, 1),
    ('malformed-shape-first-call', 'required', work / 'malformed-shape.jar', 'guard.okhttp.OkHttpSmoke', False, 1),
    ('invalid-mode', 'true', original, 'guard.okhttp.PreflightSmoke', False, None),
    ('empty-mode', '', original, 'guard.okhttp.PreflightSmoke', False, None),
]:
    directory = reports / label
    directory.mkdir()
    paths = [root / 'okhttp-smoke.jar'] + ([jar] if jar else []) + [lib / f for f in (
        'okio-jvm-3.16.4.jar', 'kotlin-stdlib-2.2.20.jar', 'annotations-13.0.jar')]
    command = [str(jdk / 'bin/java'), '-XX:-CreateCoredumpOnCrash', '--add-modules', 'jdk.httpserver',
        f'-Djarvys.offlineGuard.okhttp={mode}', f'-Djarvys.offlineGuard.reportDir={directory}',
        f'-Djarvys.offlineGuard.smokeMain={main}', f'-javaagent:{root}/jarvys-offline-guard.jar',
        '-cp', f'{root}/okhttp-smoke.jar:{root}/smoke.jar', 'guard.okhttp.ChildLoaderSmoke', *map(str, paths)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=20, cwd=root)
    output = result.stdout + result.stderr
    (directory / 'process.log').write_text(output)
    marker = 'OKHTTP_PREFLIGHT_APPLICATION_REACHED' in output or 'OKHTTP_SMOKE_PASS' in output
    assert (result.returncode == 0) == should_pass, label
    assert marker == should_pass, label
    if transforms_failed is None:
        assert not list(directory.glob('*.installed.json')), label
        assert '[jarvys-offline-guard] installed' not in output, label
    else:
        installed_files = list(directory.glob('*.installed.json'))
        assert len(installed_files) == 1, label
        installed = json.loads(installed_files[0].read_text())
        final = json.loads(Path(str(installed_files[0]).replace('.installed.json', '.shutdown.json')).read_text())
        assert installed['okhttpRequired'] is True, label
        assert final['okhttpComplete'] == should_pass, label
        assert final['okhttpVerifiedLoaders'] == (1 if should_pass else 0), label
        assert final['okhttpVerifiedHooks'] == (5 if should_pass else 0), label
        assert final['okhttpTransformFailures'] == transforms_failed, label
        assert final['blockedAttempts'] == 0, label
        assert all(final[k] == v for k, v in installed.items()), label
    results.append({'case': label, 'exitCode': result.returncode, 'applicationMarkerReached': marker,
                    'expectedSuccess': should_pass, 'pass': True})
(root / 'reports' / 'okhttp-fail-closed-results.json').write_text(json.dumps(results, indent=2) + '\n')
print(json.dumps(results, indent=2))
