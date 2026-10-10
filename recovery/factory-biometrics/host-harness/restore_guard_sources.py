#!/usr/bin/env python3
"""Materialize reviewed guard sources into a new external validation directory."""
from pathlib import Path
import argparse
import shutil
import sys
sys.dont_write_bytecode = True
from package_sources import ROOT, require_external, verified_sources

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validation-root', required=True, type=Path)
    args = parser.parse_args()
    expected = verified_sources()
    destination = require_external(args.validation_root) / 'offline-guard'
    if destination.exists():
        raise ValueError('Refusing to overwrite an existing guard directory')
    destination.mkdir(parents=True, exist_ok=False)
    for relative in sorted(expected):
        if not relative.startswith('offline-guard/'):
            continue
        source = ROOT / relative
        target = destination / Path(relative).relative_to('offline-guard')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(source.stat().st_mode & 0o777)
    print('GUARD_SOURCE_DIR=' + str(destination))
    print('Build and smoke externally, then obtain independent approval before changing hash pins.')

if __name__ == '__main__':
    main()
