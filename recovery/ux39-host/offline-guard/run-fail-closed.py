#!/usr/bin/env python3
"""Local subprocess-only startup checks; no external target, key or credential use."""
from pathlib import Path
import json
import os
import shutil
import subprocess

root = Path(__file__).resolve().parent
java = str(Path(os.environ.get('JDK', '/workspace/shared/Jarvys-recovery/toolchain/jdk-21.0.12.1+1')) / 'bin/java')
broken = root / 'build' / 'missing-bootstrap'
broken.mkdir(exist_ok=True)
shutil.copyfile(root / 'jarvys-offline-guard.jar', broken / 'jarvys-offline-guard.jar')
if (broken / 'guard-bootstrap.jar').exists():
    raise SystemExit('Missing-bootstrap test directory unexpectedly contains the helper')
unwritable = root / 'build' / 'unwritable-reports'
unwritable.mkdir(exist_ok=True)
unwritable.chmod(0o555)
import atexit
atexit.register(lambda: unwritable.chmod(0o755))
cases = [
    ('missing-bootstrap', [f'-javaagent:{broken}/jarvys-offline-guard.jar']),
    ('unsupported-arguments', [f'-javaagent:{root}/jarvys-offline-guard.jar=unsupported']),
    ('missing-report-directory', [f'-Djarvys.offlineGuard.reportDir={root}/build/nonexistent-reports', f'-javaagent:{root}/jarvys-offline-guard.jar']),
    ('relative-report-directory', ['-Djarvys.offlineGuard.reportDir=reports', f'-javaagent:{root}/jarvys-offline-guard.jar']),
    ('unwritable-report-directory', [f'-Djarvys.offlineGuard.reportDir={unwritable}', f'-javaagent:{root}/jarvys-offline-guard.jar']),
]
results = []
for label, flags in cases:
    result = subprocess.run([java, '-XX:-CreateCoredumpOnCrash', *flags, '-cp', str(root / 'smoke.jar'),
                             'guard.smoke.StartupMarker'], capture_output=True, text=True, cwd=root, timeout=15)
    output = result.stdout + result.stderr
    (root / 'reports' / f'fail-closed-{label}.txt').write_text(output)
    passed = result.returncode != 0 and 'APPLICATION_MAIN_REACHED' not in output and '[jarvys-offline-guard] installed' not in output
    results.append({'case': label, 'exitCode': result.returncode,
                    'mainReached': 'APPLICATION_MAIN_REACHED' in output, 'pass': passed})
    if not passed:
        raise SystemExit(f'Fail-closed case failed: {label}')
(root / 'reports' / 'fail-closed-results.json').write_text(json.dumps(results, indent=2) + '\n')
print(json.dumps(results, indent=2))
