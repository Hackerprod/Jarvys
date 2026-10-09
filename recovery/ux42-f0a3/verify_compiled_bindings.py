#!/usr/bin/env python3
"""Independent, read-only APK binding audit; Python standard library only.

Binary-format references (not production parser imports):
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h
https://source.android.com/docs/core/runtime/dex-format

This checks compiled declarations and exact DEX preservation, not Android execution,
constructor behavior, signature trust, installation, WebView, SAF, or key custody.
Signature/alignment checks belong to ../ux42-f0a1/verify_factory_apks.py.
"""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

ANDROID = 'http://schemas.android.com/apk/res/android'
CLASSES = {
    'Lcom/jarvys/factory/runtime/FactoryActivity;': 'Landroid/app/Activity;',
    'Landroidx/core/app/CoreComponentFactory;': 'Landroid/app/AppComponentFactory;',
}
RESOURCES = {('drawable', 'factory_icon'), ('xml', 'factory_backup_rules')}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class Binary:
    def __init__(self, data):
        self.data = data

    def read(self, offset, size):
        require(0 <= offset <= len(self.data) and 0 <= size <= len(self.data) - offset,
                'Binary range outside file: %s + %s' % (offset, size))
        return self.data[offset:offset + size]

    def u8(self, offset):
        return self.read(offset, 1)[0]

    def u16(self, offset):
        return struct.unpack('<H', self.read(offset, 2))[0]

    def u32(self, offset):
        return struct.unpack('<I', self.read(offset, 4))[0]

    def chunks(self, start, end):
        while start < end:
            kind, header, size = struct.unpack('<HHI', self.read(start, 8))
            require(8 <= header <= size <= end - start, 'Invalid resource chunk')
            yield start, kind, header, size
            start += size
        require(start == end, 'Unconsumed resource bytes')

    def pool(self, start):
        require(self.u16(start) == 1, 'Expected string pool')
        header, size = self.u16(start + 2), self.u32(start + 4)
        count, flags, strings = self.u32(start + 8), self.u32(start + 16), self.u32(start + 20)
        require(header >= 28 and header + count * 4 <= size, 'Invalid string pool header')
        result = []
        def length(pos, utf8):
            if utf8:
                first = self.u8(pos)
                return (((first & 127) << 8) | self.u8(pos + 1), pos + 2) if first & 128 else (first, pos + 1)
            first = self.u16(pos)
            return (((first & 32767) << 16) | self.u16(pos + 2), pos + 4) if first & 32768 else (first, pos + 2)
        for i in range(count):
            pos = start + strings + self.u32(start + header + i * 4)
            utf8 = bool(flags & 256)
            n, pos = length(pos, utf8)
            if utf8:
                n, pos = length(pos, True)
            width = 1 if utf8 else 2
            require(pos + n * width + width <= start + size, 'String outside pool')
            require(self.read(pos + n * width, width) == bytes(width), 'Missing string terminator')
            result.append(self.read(pos, n * width).decode('utf-8' if utf8 else 'utf-16-le'))
        return result


def arsc(data):
    b = Binary(data)
    require(b.u16(0) == 2 and b.u32(4) == len(data), 'Invalid resources.arsc root')
    strings, found = None, []
    for off, kind, header, size in b.chunks(b.u16(2), len(data)):
        if kind == 1:
            require(strings is None, 'Duplicate global string pool')
            strings = b.pool(off)
        if kind != 0x200:
            continue
        require(strings is not None and header >= 284, 'Invalid resource package')
        package_id = b.u32(off + 8)
        package_name = b.read(off + 12, 256).decode('utf-16-le').split('\0', 1)[0]
        types = b.pool(off + b.u32(off + 268))
        keys = b.pool(off + b.u32(off + 276))
        type_offset = b.u32(off + 284) if header >= 288 else 0
        for pos, child, ch, cs in b.chunks(off + header, off + size):
            if child != 0x201:
                continue
            type_id, flags = b.u8(pos + 8), b.u8(pos + 9)
            count, entries_start = b.u32(pos + 12), b.u32(pos + 16)
            config_size = b.u32(pos + 20)
            require(config_size >= 28 and 20 + config_size <= ch, 'Invalid resource configuration')
            config = b.read(pos + 24, config_size - 4)
            density = b.u16(pos + 20 + 14)
            # Config size is metadata; only the density field may be nonzero.
            normalized = bytearray(config)
            normalized[10:12] = b'\0\0'
            configuration = ('nodpi' if density == 0xffff else 'default') if not any(normalized) and density in (0, 0xffff) else 'qualified'
            require(1 <= type_id <= len(types), 'Invalid resource type index')
            type_name = types[type_id - 1]
            require(not flags & ~3, 'Unsupported resource type flags')
            width = 4 if flags & 1 or not flags & 2 else 2
            require(ch + count * width <= entries_start <= cs, 'Invalid entry offset array')
            for i in range(count):
                if flags & 1:
                    index, rel = b.u16(pos + ch + i * 4), b.u16(pos + ch + i * 4 + 2) * 4
                elif flags & 2:
                    index, raw = i, b.u16(pos + ch + i * 2)
                    if raw == 0xffff:
                        continue
                    rel = raw * 4
                else:
                    index, rel = i, b.u32(pos + ch + i * 4)
                    if rel == 0xffffffff:
                        continue
                entry = pos + entries_start + rel
                require(entry + 8 <= pos + cs, 'Entry outside type chunk')
                entry_size, entry_flags, key = b.u16(entry), b.u16(entry + 2), b.u32(entry + 4)
                require(key < len(keys), 'Invalid resource key')
                name = keys[key]
                if (type_name, name) not in RESOURCES:
                    continue
                require(not entry_flags & (1 | 8), 'Binding requires a simple noncompact value')
                value = entry + entry_size
                require(entry_size >= 8 and value + 8 <= pos + cs and b.u16(value) == 8 and b.u8(value + 3) == 3,
                        'Binding is not a string path')
                resource_id = (package_id << 24) | ((type_id + type_offset) << 16) | index
                found.append({'type': type_name, 'name': name, 'id': '0x%08x' % resource_id,
                              'path': strings[b.u32(value + 4)], 'configuration': configuration,
                              'density': density, 'configurationHex': config.hex(), 'package': package_name})
    require({(r['type'], r['name']) for r in found} == RESOURCES, 'Missing compiled resource binding')
    require(len(found) == 2, 'Expected exactly one configuration per bound resource')
    for row in found:
        require(row['configuration'] == ('nodpi' if row['type'] == 'drawable' else 'default'),
                'Unexpected resource configuration: ' + str(row))
    return sorted(found, key=lambda r: r['type'])


def manifest(data):
    b, strings, nodes, stack = Binary(data), None, [], []
    require(b.u16(0) == 3 and b.u32(4) == len(data), 'Invalid binary XML root')
    for off, kind, header, size in b.chunks(b.u16(2), len(data)):
        if kind == 1:
            strings = b.pool(off)
        elif kind == 0x102:
            require(strings is not None and header >= 16, 'Invalid XML node')
            ext = off + header
            name = strings[b.u32(ext + 4)]
            start, width, count = b.u16(ext + 8), b.u16(ext + 10), b.u16(ext + 12)
            require(width >= 20 and header + start + count * width <= size, 'Invalid XML attributes')
            attrs = {}
            for i in range(count):
                pos = ext + start + width * i
                ns, key, typ, value = b.u32(pos), strings[b.u32(pos + 4)], b.u8(pos + 15), b.u32(pos + 16)
                key = (strings[ns] if ns != 0xffffffff else '') + ':' + key
                require(key not in attrs, 'Duplicate XML attribute')
                attrs[key] = {'type': typ, 'value': strings[value] if typ == 3 else value}
            nodes.append({'path': '/'.join(stack + [name]), 'attrs': attrs})
            stack.append(name)
        elif kind == 0x103:
            require(stack and stack[-1] == strings[b.u32(off + header + 4)], 'Unbalanced XML element')
            stack.pop()
    require(not stack, 'Unclosed XML elements')
    return nodes


def dex_classes(data):
    b = Binary(data)
    require(data[:4] == b'dex\n' and b.u32(32) == len(data) and b.u32(36) == 112 and b.u32(40) == 0x12345678,
            'Unsupported or invalid DEX header')
    require(hashlib.sha1(data[32:]).digest() == data[12:32], 'DEX SHA-1 mismatch')
    import zlib
    require(zlib.adler32(data[12:]) & 0xffffffff == b.u32(8), 'DEX checksum mismatch')
    def uleb(pos):
        value = 0
        for i in range(5):
            c = b.u8(pos); pos += 1
            value |= (c & 127) << (7 * i)
            if not c & 128:
                require(value <= 0xffffffff, 'ULEB overflow')
                return value, pos
        raise ValueError('Invalid ULEB128')
    def table(count_at, width):
        count, off = b.u32(count_at), b.u32(count_at + 4)
        b.read(off, count * width)
        return range(off, off + count * width, width)
    strings = []
    for pos in table(56, 4):
        _, start = uleb(b.u32(pos))
        end = data.find(b'\0', start)
        require(end >= 0, 'Unterminated DEX string')
        strings.append(data[start:end].replace(b'\xc0\x80', b'\0').decode('utf-8', 'surrogatepass'))
    types = [strings[b.u32(pos)] for pos in table(64, 4)]
    prototypes = []
    for pos in table(72, 12):
        params = b.u32(pos + 8)
        arguments = [] if params == 0 else [types[b.u16(params + 4 + i * 2)] for i in range(b.u32(params))]
        prototypes.append('(' + ''.join(arguments) + ')' + types[b.u32(pos + 4)])
    methods = [(types[b.u16(pos)], strings[b.u32(pos + 4)], prototypes[b.u16(pos + 2)]) for pos in table(88, 8)]
    found = []
    for pos in table(96, 32):
        descriptor, access, superclass = types[b.u32(pos)], b.u32(pos + 4), b.u32(pos + 8)
        if descriptor not in CLASSES:
            continue
        require(superclass != 0xffffffff and types[superclass] == CLASSES[descriptor], 'Unexpected superclass')
        require(access & 1 and not access & (0x200 | 0x400), 'Class is not public concrete')
        cursor = b.u32(pos + 24)
        require(cursor != 0, 'Missing class_data')
        counts = []
        for _ in range(4):
            count, cursor = uleb(cursor); counts.append(count)
        for count in counts[:2]:
            for _ in range(count):
                _, cursor = uleb(cursor); _, cursor = uleb(cursor)
        constructors = []
        for group, count in zip(('direct', 'virtual'), counts[2:]):
            method_index = 0
            for _ in range(count):
                delta, cursor = uleb(cursor); method_index += delta
                flags, cursor = uleb(cursor); code, cursor = uleb(cursor)
                owner, name, prototype = methods[method_index]
                require(owner == descriptor, 'Method owned by another class')
                if name == '<init>' and prototype == '()V':
                    require(group == 'direct' and flags & 1 and flags & 0x10000 and not flags & (8 | 0x100 | 0x400) and code != 0,
                            'No-arg constructor is not public concrete instance constructor')
                    b.read(code, 16)
                    constructors.append({'prototype': prototype, 'accessFlags': '0x%08x' % flags,
                                         'codeOffset': code, 'methodIndex': method_index})
        require(len(constructors) == 1, 'Expected one public no-arg constructor')
        found.append({'descriptor': descriptor, 'superclass': types[superclass], 'accessFlags': '0x%08x' % access,
                      'classDefOffset': pos, 'noArgConstructor': constructors[0]})
    return found


def tool_path(name):
    local = pathlib.Path(__file__).resolve().parents[3] / 'toolchain/android-sdk/build-tools/35.0.0' / name
    return str(local) if local.is_file() else shutil.which(name)


def command(tool, *args):
    return subprocess.check_output([tool, *args], text=True, errors='replace', stderr=subprocess.STDOUT)


def official_checks(path, resources, classes, dex_files):
    result = {}
    aapt = tool_path('aapt2')
    if aapt:
        dump = command(aapt, 'dump', 'resources', str(path))
        for row in resources:
            pattern = r'resource ' + row['id'] + r' ' + re.escape(row['type'] + '/' + row['name']) + r'\n((?:\s{6,}.*\n)*)'
            match = re.search(pattern, dump)
            require(match is not None, 'aapt2 resource ID/name mismatch')
            config = 'nodpi' if row['configuration'] == 'nodpi' else ''
            require('(' + config + ') (file) ' + row['path'] + ' ' in match[1], 'aapt2 resource path/config mismatch')
        xml = command(aapt, 'dump', 'xmltree', str(path), '--file', 'AndroidManifest.xml')
        for row, name in zip(resources, ('icon', 'dataExtractionRules')):
            require(re.search(r':' + name + r'\(0x[0-9a-f]+\)=@' + row['id'] + r'\b', xml) is not None,
                    'aapt2 manifest reference mismatch')
        for desc in CLASSES:
            require(desc[1:-1].replace('/', '.') in xml, 'aapt2 missing component name')
        permissions = command(aapt, 'dump', 'permissions', str(path))
        require('uses-permission:' not in permissions, 'aapt2 reports permissions')
        result['aapt2'] = {'tool': aapt, 'resourcesSha256': sha(dump.encode()), 'xmltreeSha256': sha(xml.encode()),
                            'permissionsSha256': sha(permissions.encode()), 'passed': True}
    else:
        result['aapt2'] = {'available': False}
    dexdump = tool_path('dexdump')
    if dexdump:
        hashes = {}
        with tempfile.TemporaryDirectory(prefix='ux42-binding-audit-') as temp:
            for name, data in dex_files.items():
                target = pathlib.Path(temp) / pathlib.Path(name).name
                target.write_bytes(data)
                dump = command(dexdump, str(target)).replace(str(target), name)
                hashes[name] = sha(dump.encode())
                for row in classes:
                    if row['dex'] != name:
                        continue
                    blocks = re.split(r'(?m)^Class #\d+\s*', dump)
                    blocks = [block for block in blocks if "Class descriptor  : '" + row['descriptor'] + "'" in block]
                    require(len(blocks) == 1, 'dexdump class mismatch')
                    block = blocks[0]
                    require("Superclass        : '" + row['superclass'] + "'" in block, 'dexdump superclass mismatch')
                    require(re.search(r'Access flags\s+: 0x0*' + format(int(row['accessFlags'], 16), 'x') + r'\b', block), 'dexdump class access mismatch')
                    require(re.search(r"name\s+: '<init>'\s+type\s+: '\(\)V'\s+access\s+: 0x0*" + format(int(row['noArgConstructor']['accessFlags'], 16), 'x') + r'\b', block), 'dexdump constructor mismatch')
        result['dexdump'] = {'tool': dexdump, 'dumpSha256': hashes, 'passed': True}
    else:
        result['dexdump'] = {'available': False}
    return result


def inspect(path, expected_dex=None):
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None and len(archive.namelist()) == len(set(archive.namelist())), 'Invalid ZIP')
        files = {name: archive.read(name) for name in archive.namelist()}
    resources = arsc(files['resources.arsc'])
    for row in resources:
        require(row['path'] in files and files[row['path']], 'Missing bound resource payload')
        row['payloadSha256'] = sha(files[row['path']])
    dex_files = {name: data for name, data in files.items() if name.endswith('.dex')}
    require(dex_files, 'Missing DEX')
    if expected_dex is not None:
        require(dex_files == expected_dex, 'DEX bytes differ from template')
    classes = []
    for name, data in sorted(dex_files.items()):
        classes.extend(dict(row, dex=name) for row in dex_classes(data))
    require(sorted(row['descriptor'] for row in classes) == sorted(CLASSES), 'Missing or duplicate class_defs')
    nodes = manifest(files['AndroidManifest.xml'])
    apps = [node for node in nodes if node['path'] == 'manifest/application']
    require(len(apps) == 1, 'Expected single application')
    app = apps[0]['attrs']
    for row, name in zip(resources, ('icon', 'dataExtractionRules')):
        require(app.get(ANDROID + ':' + name) == {'type': 1, 'value': int(row['id'], 16)}, 'Manifest resource reference mismatch')
    require(app.get(ANDROID + ':appComponentFactory') == {'type': 3, 'value': 'androidx.core.app.CoreComponentFactory'}, 'Manifest factory class mismatch')
    activities = [node for node in nodes if node['path'] == 'manifest/application/activity']
    require(any(node['attrs'].get(ANDROID + ':name') == {'type': 3, 'value': 'com.jarvys.factory.runtime.FactoryActivity'} for node in activities), 'Manifest activity class mismatch')
    require(not any(node['path'].split('/')[-1].startswith('uses-permission') for node in nodes), 'Manifest requests permission')
    row = {'apk': str(path.resolve()), 'apkSha256': sha(path.read_bytes()), 'resources': resources, 'classes': classes,
           'dexSha256': {name: sha(data) for name, data in sorted(dex_files.items())},
           'dexExactToTemplate': expected_dex is not None, 'manifestReferencesVerified': True, 'permissionCount': 0,
           'officialChecks': official_checks(path, resources, classes, dex_files)}
    return row, dex_files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', required=True, type=pathlib.Path)
    parser.add_argument('--fixtures', required=True, type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    args = parser.parse_args()
    template, dex = inspect(args.template)
    rows = []
    metadata_files = sorted(list(args.fixtures.rglob('[012].json')) + list(args.fixtures.rglob('recovery-v[12].json')))
    require(metadata_files, 'No fixture metadata found')
    covered = set()
    for metadata in metadata_files:
        record = json.loads(metadata.read_text())
        pair = []
        for kind in ('unsigned', 'signed'):
            path = metadata.with_name(metadata.stem + '-' + kind + '.apk')
            row, _ = inspect(path, dex)
            row.update(fixture=str(metadata.relative_to(args.fixtures)), signingState=kind)
            rows.append(row); pair.append(row); covered.add(path.resolve())
        require(pair[0]['resources'] == pair[1]['resources'] and pair[0]['classes'] == pair[1]['classes'], 'Signed/unsigned binding difference')
        if 'signedSha256' in record:
            require(pair[1]['apkSha256'] == record['signedSha256'], 'Signed fixture hash differs from metadata')
    discovered = {path.resolve() for pattern in ('*-unsigned.apk', '*-signed.apk') for path in args.fixtures.rglob(pattern)}
    require(discovered == covered, 'Some fixture APKs lack audited metadata')
    result = {'passed': True, 'hostOnly': True, 'fixtureCount': len(metadata_files), 'apkCount': len(rows) + 1,
              'template': template, 'rows': rows,
              'sources': ['https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h',
                          'https://source.android.com/docs/core/runtime/dex-format'],
              'scope': 'Independent Python ARSC, binary XML and DEX declaration decoding; exact fixture DEX bytes compared with template. Official tools complement this when available.',
              'limits': 'Host-only declaration evidence. No Android execution, installation, constructor behavior, WebView/SAF acceptance, key custody or signature trust claim. Signature checks remain in F0a1 audit.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'passed': True, 'fixtureCount': len(metadata_files), 'apkCount': len(rows) + 1, 'output': str(args.output)}))


if __name__ == '__main__':
    main()
