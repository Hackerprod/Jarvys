#!/usr/bin/env python3
"""Verify the explicit source allowlist and optionally archive only those files."""
from pathlib import Path
import argparse
import gzip
import hashlib
import io
import json
import tarfile
import subprocess

ROOT = Path(__file__).resolve().parent
MANIFEST = 'source-manifest.json'

def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def require_external(path):
    if not path.is_absolute():
        raise ValueError('An absolute external path is required')
    resolved = path.resolve()
    probe = resolved if resolved.is_dir() else resolved.parent
    while not probe.exists():
        probe = probe.parent
    # Managed executors may have an empty protected .git marker. Only a real
    # discoverable worktree is a checkout; do not read protected marker contents.
    result = subprocess.run(['git', '-C', str(probe), 'rev-parse', '--is-inside-work-tree'],
                            capture_output=True, text=True)
    if result.returncode == 0 and result.stdout.strip() == 'true':
        raise ValueError('Generated files must remain outside Git checkouts')
    if resolved == ROOT or ROOT in resolved.parents:
        raise ValueError('Generated files must remain outside the source package')
    return resolved

def verified_sources():
    manifest = json.loads((ROOT / MANIFEST).read_text())
    expected = manifest['files']
    actual = {str(p.relative_to(ROOT)) for p in ROOT.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
    if actual != set(expected) | {MANIFEST}:
        raise ValueError('Source package allowlist differs from actual files')
    for relative, digest in expected.items():
        if Path(relative).is_absolute() or '..' in Path(relative).parts:
            raise ValueError('Invalid source manifest path')
        path = ROOT / relative
        if path.is_symlink() or sha256(path) != digest:
            raise ValueError('Source package hash or symlink mismatch: ' + relative)
    return expected

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, help='Optional new absolute external .tar.gz filename')
    args = parser.parse_args()
    expected = verified_sources()
    if args.archive:
        destination = require_external(args.archive)
        if not destination.parent.is_dir():
            raise ValueError('Archive parent directory must already exist')
        # Explicit static allowlist: no recursive directory archive, host logs or generated files.
        with destination.open('xb') as raw:
            with gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode='w') as archive:
                    for relative in sorted(set(expected) | {MANIFEST}):
                        path = ROOT / relative
                        data = path.read_bytes()
                        member = tarfile.TarInfo('factory-biometric-v80-host/' + relative)
                        member.size = len(data)
                        member.mode = 0o755 if path.stat().st_mode & 0o111 else 0o644
                        member.mtime = 0
                        archive.addfile(member, io.BytesIO(data))
        print('SOURCE_ARCHIVE_SHA256=' + sha256(destination))
    print('VERIFIED_SOURCE_FILES=' + str(len(expected)))

if __name__ == '__main__':
    main()
