#!/usr/bin/env python3
"""Materialize source copies into the reviewed external H/V relative layout."""
from pathlib import Path
import argparse
import sys
sys.dont_write_bytecode = True
from package_sources import ROOT, require_external, verified_sources

HOST_FILES = ['gradle-run.sh', 'run_gradle_gate.py', 'fresh-tests.init.gradle',
              'fresh-lint.init.gradle', 'warm-test-runtime-v80.init.gradle']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', required=True, type=Path)
    args = parser.parse_args()
    expected = verified_sources()
    recovery = require_external(args.recovery_root)
    host = recovery / 'UX39-recovered-validation'
    validation = recovery / 'FACTORY-BIOMETRICS-v80-validation'
    for directory in (host, validation):
        if directory.is_symlink():
            raise ValueError('Refusing symlinked H/V orchestration directories')
    copies = [(ROOT / name, host / name) for name in HOST_FILES]
    copies += [(ROOT / name, validation / Path(name).name)
               for name in sorted(expected) if name.startswith('orchestration/')]
    for _, target in copies:
        resolved_target = require_external(target)
        if recovery not in resolved_target.parents:
            raise ValueError('Orchestration target escapes the selected recovery root')
        if target.exists() or target.is_symlink():
            raise ValueError('Refusing to overwrite an existing orchestration source: ' + target.name)
    for source, target in copies:
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(source.read_bytes())
        target.chmod(source.stat().st_mode & 0o777)
    print('EXTERNAL_HOST_HARNESS=' + str(host))
    print('EXTERNAL_V80_ORCHESTRATION=' + str(validation))
    print('Set toolchain, lock and factory evidence paths before any authorized run.')

if __name__ == '__main__':
    main()
