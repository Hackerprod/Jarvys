#!/usr/bin/env python3
"""Verify optional file evidence can distinguish simultaneous JVMs without URL data."""
from pathlib import Path
import json
import os
import subprocess
import time

root = Path(__file__).resolve().parent
java = str(Path(os.environ.get('JDK', '/workspace/shared/Jarvys-recovery/toolchain/jdk-21.0.12.1+1')) / 'bin/java')
directory = root / 'reports' / f'attribution-smoke-{time.time_ns()}'
directory.mkdir()
common = [java, '-XX:-CreateCoredumpOnCrash', '--add-modules', 'jdk.httpserver',
          '--add-exports', 'java.base/sun.net.www.protocol.http=ALL-UNNAMED',
          f'-Djarvys.offlineGuard.reportDir={directory}',
          f'-javaagent:{root}/jarvys-offline-guard.jar', '-cp', str(root / 'smoke.jar')]
cases = [('guard.smoke.GuardSmoke', 100), ('guard.smoke.StartupMarker', 0)]
processes = [(subprocess.Popen(common + [main], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, cwd=root), main, count)
             for main, count in cases]
results = []
for process, main, expected in processes:
    out, err = process.communicate(timeout=20)
    (directory / f'process-{process.pid}.log').write_text(out + err)
    assert process.returncode == 0, 'Process failed'
    matches = list(directory.glob(f'guard-{process.pid}-*.installed.json'))
    assert len(matches) == 1, 'Missing/colliding install evidence'
    install_path = matches[0]
    final_path = Path(str(install_path).replace('.installed.json', '.shutdown.json'))
    install = json.loads(install_path.read_text())
    final = json.loads(final_path.read_text())
    assert set(install) == {'pid','jvmStartedAtEpochMillis','installedAtEpochMillis'}
    assert set(final) == set(install) | {'shutdownAtEpochMillis','blockedAttempts'}
    assert install['pid'] == process.pid
    assert all(final[k] == v for k,v in install.items())
    assert final['jvmStartedAtEpochMillis'] <= final['installedAtEpochMillis'] <= final['shutdownAtEpochMillis']
    assert final['blockedAttempts'] == expected
    assert '[jarvys-offline-guard] installed hooks=7' in err
    assert f'[jarvys-offline-guard] blockedAttempts={expected}' in err
    assert all(isinstance(v,int) for v in install.values()) and all(isinstance(v,int) for v in final.values())
    results.append({'pid':process.pid,'expectedBlockedAttempts':expected,'actualBlockedAttempts':final['blockedAttempts'],
                    'installFile':str(install_path.relative_to(root)),'shutdownFile':str(final_path.relative_to(root)),'pass':True})
(root / 'reports' / 'attribution-results.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))
