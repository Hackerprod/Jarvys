#!/usr/bin/env python3
"""Fetch or verify exact public prerequisite bytes. Does not install or accept terms."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
from urllib.parse import urlsplit
import sys
sys.dont_write_bytecode = True
from package_sources import ROOT, require_external, verified_sources

ALLOWED_HOSTS = {'github.com', 'services.gradle.org', 'dl.google.com', 'repo.maven.apache.org'}

def digest(path, algorithm):
    result = hashlib.new(algorithm)
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()

def verify(path, record, kind):
    expected_size = record['size_bytes'] if kind == 'toolchain' else record['bytes']
    if not path.is_file() or path.is_symlink() or path.stat().st_size != expected_size:
        raise ValueError('Missing, symlinked or wrong-size pinned artifact: ' + path.name)
    if digest(path, 'sha256') != record['sha256']:
        raise ValueError('Pinned SHA-256 mismatch: ' + path.name)
    if kind == 'robolectric' and digest(path, 'sha512') != record['sha512_verified']:
        raise ValueError('Pinned vendor SHA-512 mismatch: ' + path.name)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=['toolchain', 'robolectric'], required=True)
    parser.add_argument('--destination', required=True, type=Path)
    parser.add_argument('--sdk', type=int, help='Only this Robolectric SDK, for example 30')
    parser.add_argument('--verify-only', action='store_true', help='No network or output writes')
    args = parser.parse_args()
    verified_sources()
    destination = require_external(args.destination)
    records = json.loads((ROOT / (args.kind + '-artifact-checksums.json')).read_text())
    if args.sdk is not None:
        if args.kind != 'robolectric':
            raise ValueError('--sdk is only valid for Robolectric artifacts')
        records = [r for r in records if r['sdk'] == args.sdk]
    if not records:
        raise ValueError('No exact pinned artifacts selected')
    if not args.verify_only:
        destination.mkdir(parents=True, exist_ok=True)
    for record in records:
        filename = record['file'] if args.kind == 'toolchain' else record['name']
        if Path(filename).name != filename:
            raise ValueError('Invalid artifact basename')
        url = record['source_url'] if args.kind == 'toolchain' else record['url']
        parsed = urlsplit(url)
        if parsed.scheme != 'https' or parsed.hostname not in ALLOWED_HOSTS or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('Unexpected pinned public download URL')
        target = destination / filename
        if target.exists() or args.verify_only:
            verify(target, record, args.kind)
        else:
            descriptor, temporary = tempfile.mkstemp(prefix='.' + filename + '.', suffix='.partial', dir=destination)
            os.close(descriptor)
            partial = Path(temporary)
            try:
                subprocess.run(['curl', '--fail', '--location', '--proto', '=https', '--proto-redir', '=https', '--silent', '--show-error', '--retry', '2', '--max-time', '600', '--output', str(partial), url], check=True)
                verify(partial, record, args.kind)
                # Atomic non-overwriting publish: an existing target is never silently replaced.
                os.link(partial, target)
            finally:
                partial.unlink(missing_ok=True)
        print('VERIFIED_PINNED_ARTIFACT ' + filename)
    print('VERIFIED_ARTIFACT_COUNT=' + str(len(records)))

if __name__ == '__main__':
    main()
