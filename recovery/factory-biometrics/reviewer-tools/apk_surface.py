#!/usr/bin/env python3
"""Independent read-only APK entry/AXML surface audit, reconstructed 2026-10-10.

Rebuilt from Android binary-format layout and the committed F0a3 helper's small
checked-reader pattern; not restored previous_audit_helpers.py/v79_apk_common.py.
No production imports, package allowlists, signing, build or execution.
Changes remain evidence for manual review unless an exact per-entry before/after
SHA-256 pair is supplied. Version normalization applies only to manifest root
android:versionCode/versionName attributes, and records the removed values.
"""
import argparse
import hashlib
import json
import pathlib
import struct
import zipfile

ANDROID = 'http://schemas.android.com/apk/res/android'
VERSION_KEYS = {ANDROID + ':versionCode', ANDROID + ':versionName'}


def require(ok, message):
    if not ok: raise ValueError(message)


def sha(data): return hashlib.sha256(data).hexdigest()


class Binary:
    def __init__(self, data): self.data = data
    def read(self, off, size):
        require(0 <= off <= len(self.data) and 0 <= size <= len(self.data) - off, 'Out-of-bounds resource read')
        return self.data[off:off + size]
    def u8(self, off): return self.read(off, 1)[0]
    def u16(self, off): return struct.unpack('<H', self.read(off, 2))[0]
    def u32(self, off): return struct.unpack('<I', self.read(off, 4))[0]
    def chunks(self, start, end):
        while start < end:
            typ, header, size = struct.unpack('<HHI', self.read(start, 8))
            require(8 <= header <= size <= end - start, 'Invalid resource chunk size')
            yield start, typ, header, size
            start += size
        require(start == end, 'Trailing resource data')
    def pool(self, off):
        require(self.u16(off) == 1, 'Not a string pool')
        header, size, count, flags, begin = self.u16(off + 2), self.u32(off + 4), self.u32(off + 8), self.u32(off + 16), self.u32(off + 20)
        require(header >= 28 and header + 4 * count <= size, 'Invalid string pool header')
        out = []
        def length(at, utf8):
            n = self.u8(at) if utf8 else self.u16(at); width = 1 if utf8 else 2; mask = 0x80 if utf8 else 0x8000
            if n & mask:
                second = self.u8(at + 1) if utf8 else self.u16(at + 2)
                return ((n & (mask - 1)) << (8 if utf8 else 16)) | second, at + width * 2
            return n, at + width
        for n in range(count):
            at = off + begin + self.u32(off + header + 4 * n); utf8 = bool(flags & 0x100)
            utf16_count, at = length(at, utf8)
            count, at = length(at, True) if utf8 else (utf16_count, at)
            width = 1 if utf8 else 2
            require(at + count * width + width <= off + size, 'String outside pool')
            require(self.read(at + count * width, width) == bytes(width), 'Missing string terminator')
            text = self.read(at, count * width).decode('utf-8' if utf8 else 'utf-16-le', 'surrogatepass')
            require(len(text.encode('utf-16-le', 'surrogatepass')) // 2 == utf16_count, 'String UTF-16 length mismatch')
            out.append(text)
        return out


def manifest(data):
    b = Binary(data)
    require(b.u16(0) == 3 and b.u32(4) == len(data), 'Invalid binary XML root')
    strings, ids, stack, nodes, namespace_stack, namespaces = None, [], [], [], [], []
    def string(n): return None if n == 0xffffffff else strings[n]
    for off, typ, header, size in b.chunks(b.u16(2), len(data)):
        if typ == 1:
            require(strings is None, 'Multiple string pools'); strings = b.pool(off)
        elif typ == 0x180:
            require(not ids and (size - header) % 4 == 0, 'Invalid/duplicate resource map')
            ids = [b.u32(p) for p in range(off + header, off + size, 4)]
        else:
            require(strings is not None and header >= 16, 'Missing pool/invalid XML node header')
            at = off + header
            if typ in (0x100, 0x101):
                pair = [string(b.u32(at)), string(b.u32(at + 4))]
                if typ == 0x100: namespace_stack.append(pair); namespaces.append(pair)
                else:
                    require(namespace_stack and namespace_stack.pop() == pair, 'Unbalanced namespaces')
            elif typ == 0x102:
                ns, name = string(b.u32(at)), string(b.u32(at + 4))
                start, width, count = b.u16(at + 8), b.u16(at + 10), b.u16(at + 12)
                require(width >= 20 and header + start + width * count <= size, 'Invalid attribute records')
                attrs = {}
                for n in range(count):
                    p = at + start + width * n
                    attr_ns, nameidx = string(b.u32(p)), b.u32(p + 4)
                    key = (attr_ns or '') + ':' + strings[nameidx]
                    require(key not in attrs and b.u16(p + 12) == 8 and b.u8(p + 14) == 0, 'Invalid/duplicate typed attribute')
                    kind, value = b.u8(p + 15), b.u32(p + 16)
                    attrs[key] = {'type': kind, 'value': string(value) if kind == 3 else value,
                                  'resourceId': ids[nameidx] if nameidx < len(ids) else 0}
                special = []
                keys = list(attrs)
                for delta in (14, 16, 18):
                    value = b.u16(at + delta); require(value <= count, 'Invalid special attribute index')
                    special.append(keys[value - 1] if value else None)
                nodes.append({'kind': 'element', 'path': '/'.join([v[1] for v in stack] + [name]),
                              'namespace': ns, 'attrs': attrs, 'specialAttributes': special})
                stack.append((ns, name))
            elif typ == 0x103:
                require(stack and stack.pop() == (string(b.u32(at)), string(b.u32(at + 4))), 'Unbalanced elements')
            elif typ == 0x104:
                require(stack, 'Text outside document element')
                kind, value = b.u8(at + 7), b.u32(at + 8)
                nodes.append({'kind': 'text', 'path': '/'.join(v[1] for v in stack), 'text': string(b.u32(at)),
                              'typedValue': {'type': kind, 'value': string(value) if kind == 3 else value}})
            else: raise ValueError('Unknown binary XML chunk 0x%x' % typ)
    require(not stack and not namespace_stack and nodes, 'Incomplete XML tree')
    require(sum(n['kind'] == 'element' and n['path'] == 'manifest' for n in nodes) == 1, 'Expected single manifest root')
    return {'namespaces': namespaces, 'nodes': nodes}


def inventory(path):
    path = pathlib.Path(path)
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        require(archive.testzip() is None, 'ZIP CRC mismatch')
        files = {n: archive.read(n) for n in names if not n.endswith('/')}
    require('AndroidManifest.xml' in files, 'Missing APK manifest')
    xml = manifest(files['AndroidManifest.xml'])
    return {'format': 'independent-apk-surface-v1', 'source': str(path.resolve()), 'sha256': sha(path.read_bytes()),
            'entries': {n: {'sha256': sha(data), 'size': len(data)} for n, data in sorted(files.items())},
            'manifest': xml,
            'permissions': [n for n in xml['nodes'] if n['path'].split('/')[-1].startswith('uses-permission')],
            'nativeLibraries': {n: sha(data) for n, data in sorted(files.items()) if n.startswith('lib/')},
            'resources': {n: sha(data) for n, data in sorted(files.items()) if n == 'resources.arsc' or n.startswith('res/')},
            'limits': 'Static ZIP/AXML evidence only; no signing trust, installation, execution or biometric result.'}


def normalize_manifest(value, ignore_version):
    value = json.loads(json.dumps(value)); versions = {}
    if ignore_version:
        for node in value['nodes']:
            if node['kind'] == 'element' and node['path'] == 'manifest':
                versions = {k: dict(node['attrs'][k]) for k in sorted(VERSION_KEYS) if k in node['attrs']}
                for key in versions: node['attrs'][key]['value'] = '<host-version-value>'
    return value, versions


def compare(before, after, allowed=None, ignore_host_version=False):
    require(before['format'] == after['format'] == 'independent-apk-surface-v1', 'Incompatible inventories')
    allowed = allowed or {}
    changes = []; approved = []; unused = []
    for name in sorted(before['entries'].keys() | after['entries'].keys()):
        a, b = before['entries'].get(name), after['entries'].get(name)
        if a == b:
            if name in allowed: unused.append(name)
            continue
        row = {'entry': name, 'before': a, 'after': b}
        hashes = {'beforeSha256': a['sha256'] if a else None, 'afterSha256': b['sha256'] if b else None}
        if name in allowed and hashes == allowed[name]: approved.append(row)
        else: changes.append(row)
    require(not set(allowed) - (before['entries'].keys() | after['entries'].keys()), 'Policy names nonexistent APK entry')
    a, av = normalize_manifest(before['manifest'], ignore_host_version)
    b, bv = normalize_manifest(after['manifest'], ignore_host_version)
    return {'format': 'independent-apk-surface-comparison-v1', 'beforeSha256': before['sha256'], 'afterSha256': after['sha256'],
            'manifestEquivalent': a == b, 'ignoreHostVersion': ignore_host_version,
            'hostVersionAttributes': {'before': av, 'after': bv}, 'permissionsUnchanged': before['permissions'] == after['permissions'],
            'nativeLibrariesUnchanged': before['nativeLibraries'] == after['nativeLibraries'],
            'resourcesUnchanged': before['resources'] == after['resources'],
            'exactPolicyMatchedChanges': approved, 'unexpectedEntryChanges': changes, 'unusedPolicyEntries': unused,
            'manifestBefore': a, 'manifestAfter': b,
            'passedExactSurfacePolicy': not changes and not unused and a == b and before['permissions'] == after['permissions']
                                      and before['nativeLibraries'] == after['nativeLibraries'] and before['resources'] == after['resources'],
            'limits': before['limits']}


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('inventory'); p.add_argument('apk', type=pathlib.Path); p.add_argument('--output', type=pathlib.Path, required=True)
    p = sub.add_parser('compare'); p.add_argument('before', type=pathlib.Path); p.add_argument('after', type=pathlib.Path); p.add_argument('--policy', type=pathlib.Path); p.add_argument('--ignore-host-version', action='store_true'); p.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.command == 'inventory': out = inventory(args.apk)
    else:
        out = compare(json.loads(args.before.read_text()), json.loads(args.after.read_text()),
                      json.loads(args.policy.read_text()) if args.policy else None, args.ignore_host_version)
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(out, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'output': str(args.output), 'unexpectedEntryChanges': len(out.get('unexpectedEntryChanges', [])),
                      'passedExactSurfacePolicy': out.get('passedExactSurfacePolicy')}))
    if args.command == 'compare' and not out['passedExactSurfacePolicy']: raise SystemExit(2)


if __name__ == '__main__': main()
