#!/usr/bin/env python3
"""Read-only DEX/APK semantic inventory and conservative ordinal reconciliation.

Rebuilt 2026-10-10 after the executor reset. This is not a recovered prior helper.
Standard library only. build-tools dexdump provides resolved instruction operands;
an independent DEX reader supplies complete switch/array payloads, annotations,
static values and method-code bounds omitted or abbreviated by dexdump.
No package allowlists, no automatic approval of changed methods, no execution.
"""
import argparse
import copy
import collections
import difflib
import hashlib
import json
import pathlib
import re
import struct
import subprocess
import tempfile
import zipfile
import zlib

FORMAT = 'independent-symbolic-dex-v1'
LIMITS = ('Static host comparison only; no Android execution, lifecycle acceptance, '
          'authentication, signature trust, runtime API behavior or security proof. '
          'Debug information, code/file offsets, DEX table indices, ZIP layout and '
          'DEX ordering are intentionally excluded. Unrecognized input fails closed.')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def stable(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def digest(value):
    return hashlib.sha256(stable(value).encode()).hexdigest()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def decode_dump(data):
    # dexdump emits DEX MUTF-8 directly (CESU-8 surrogates and encoded NUL).
    return data.replace(b'\xc0\x80', b'\0').decode('utf-8', 'surrogatepass')


class Dex:
    """Minimal checked little-endian standard DEX reader; no production imports."""
    def __init__(self, data):
        self.data = data
        require(data[:4] == b'dex\n' and data[7:8] == b'\0', 'Not standard DEX')
        require(data[4:7] in (b'035', b'037', b'038', b'039', b'040'), 'Unsupported DEX version')
        require(self.u32(32) == len(data) and self.u32(36) == 112 and self.u32(40) == 0x12345678,
                'Invalid/unsupported DEX header')
        require(hashlib.sha1(data[32:]).digest() == data[12:32], 'DEX SHA-1 mismatch')
        require(zlib.adler32(data[12:]) & 0xffffffff == self.u32(8), 'DEX Adler-32 mismatch')
        self.strings = []
        for off in self.table(56, 4):
            length, p = self.uleb(self.u32(off))
            end = data.find(b'\0', p)
            require(end >= p, 'Unterminated DEX string')
            # MUTF-8 is CESU-8, including surrogate pairs and overlong encoded NUL.
            text = data[p:end].replace(b'\xc0\x80', b'\0').decode('utf-8', 'surrogatepass')
            require(len(text.encode('utf-16-le', 'surrogatepass')) // 2 == length, 'DEX string length mismatch')
            self.strings.append(text)
        self.types = [self.strings[self.u32(p)] for p in self.table(64, 4)]
        self.protos = []
        for p in self.table(72, 12):
            args = self.type_list(self.u32(p + 8))
            self.protos.append('(' + ''.join(args) + ')' + self.types[self.u32(p + 4)])
        self.fields = [(self.types[self.u16(p)], self.strings[self.u32(p + 4)], self.types[self.u16(p + 2)])
                       for p in self.table(80, 8)]
        self.methods = [(self.types[self.u16(p)], self.strings[self.u32(p + 4)], self.protos[self.u16(p + 2)])
                        for p in self.table(88, 8)]
        self.handles = []
        mapoff = self.u32(52)
        for p in range(mapoff + 4, mapoff + 4 + self.u32(mapoff) * 12, 12):
            typ, count, off = self.u16(p), self.u32(p + 4), self.u32(p + 8)
            if typ == 8:
                for h in range(off, off + 8 * count, 8):
                    kind, idx = self.u16(h), self.u16(h + 4)
                    require(kind <= 8 and self.u16(h + 2) == self.u16(h + 6) == 0, 'Bad method handle')
                    self.handles.append([kind, self.field_symbol(idx) if kind <= 3 else self.method_symbol(idx)])
        self.classes = {}
        self.code = {}
        for p in self.table(96, 32):
            desc = self.types[self.u32(p)]
            require(desc not in self.classes, 'Duplicate class definition')
            superidx = self.u32(p + 8)
            row = {'access': self.u32(p + 4), 'superclass': None if superidx == 0xffffffff else self.types[superidx],
                   'interfaces': self.type_list(self.u32(p + 12)), 'fields': {}, 'methods': {},
                   'annotations': self.annotation_directory(self.u32(p + 20))}
            cursor = self.u32(p + 24)
            counts = []
            if cursor:
                for _ in range(4):
                    v, cursor = self.uleb(cursor); counts.append(v)
            else:
                counts = [0, 0, 0, 0]
            static = []
            for group, count in zip(('static', 'instance'), counts[:2]):
                idx = 0
                for _ in range(count):
                    delta, cursor = self.uleb(cursor); idx += delta
                    flags, cursor = self.uleb(cursor)
                    owner, name, typ = self.fields[idx]
                    require(owner == desc, 'Field owner mismatch')
                    key = name + ':' + typ
                    row['fields'][key] = {'group': group, 'access': flags}
                    if group == 'static': static.append(key)
            vals = self.u32(p + 28)
            if vals:
                values, _ = self.array(vals)
                require(len(values) <= len(static), 'Too many static values')
                for key, value in zip(static, values): row['fields'][key]['value'] = value
            for group, count in zip(('direct', 'virtual'), counts[2:]):
                idx = 0
                for _ in range(count):
                    delta, cursor = self.uleb(cursor); idx += delta
                    flags, cursor = self.uleb(cursor)
                    codeoff, cursor = self.uleb(cursor)
                    owner, name, proto = self.methods[idx]
                    require(owner == desc, 'Method owner mismatch')
                    key = name + proto
                    require(key not in row['methods'], 'Duplicate method')
                    row['methods'][key] = {'group': group, 'access': flags, 'prototype': proto}
                    self.code[(desc, key)] = codeoff
            self.classes[desc] = row

    def read(self, off, size):
        require(0 <= off <= len(self.data) and 0 <= size <= len(self.data) - off, 'Read outside DEX')
        return self.data[off:off + size]

    def u16(self, p): return struct.unpack('<H', self.read(p, 2))[0]
    def u32(self, p): return struct.unpack('<I', self.read(p, 4))[0]
    def i32(self, p): return struct.unpack('<i', self.read(p, 4))[0]

    def uleb(self, p):
        result = 0
        for n in range(5):
            v = self.read(p, 1)[0]; p += 1; result |= (v & 127) << (7 * n)
            if not v & 128:
                require(result <= 0xffffffff, 'ULEB overflow')
                return result, p
        raise ValueError('Invalid ULEB')

    def table(self, p, size):
        count, off = self.u32(p), self.u32(p + 4)
        self.read(off, count * size)
        return range(off, off + count * size, size)

    def type_list(self, p):
        return [] if not p else [self.types[self.u16(p + 4 + 2 * n)] for n in range(self.u32(p))]

    def field_symbol(self, n):
        owner, name, typ = self.fields[n]; return owner + '.' + name + ':' + typ

    def method_symbol(self, n):
        owner, name, proto = self.methods[n]; return owner + '.' + name + ':' + proto

    def array(self, p):
        count, p = self.uleb(p); out = []
        for _ in range(count):
            value, p = self.value(p); out.append(value)
        return out, p

    def annotation(self, p):
        typ, p = self.uleb(p); count, p = self.uleb(p); values = {}
        for _ in range(count):
            name, p = self.uleb(p); value, p = self.value(p)
            require(self.strings[name] not in values, 'Duplicate annotation value')
            values[self.strings[name]] = value
        return {'type': self.types[typ], 'elements': values}, p

    def value(self, p):
        head = self.read(p, 1)[0]; p += 1; typ, arg = head & 31, head >> 5
        if typ in (0x1c, 0x1d, 0x1e):
            require(arg == 0, 'Invalid encoded value argument')
            if typ == 0x1c: out, p = self.array(p)
            elif typ == 0x1d: out, p = self.annotation(p)
            else: out = None
        elif typ == 0x1f:
            require(arg <= 1, 'Invalid boolean'); out = bool(arg)
        else:
            widths = {0: 1, 2: 2, 3: 2, 4: 4, 6: 8, 0x10: 4, 0x11: 8,
                      0x15: 4, 0x16: 4, 0x17: 4, 0x18: 4, 0x19: 4, 0x1a: 4, 0x1b: 4}
            require(typ in widths and arg < widths[typ], 'Unsupported encoded value')
            raw = self.read(p, arg + 1); p += arg + 1
            val = int.from_bytes(raw, 'little', signed=typ in (0, 2, 4, 6))
            if typ in (0x10, 0x11): out = hex(val << ((widths[typ] - arg - 1) * 8))
            elif typ == 0x15: out = self.protos[val]
            elif typ == 0x16: out = self.handles[val]
            elif typ == 0x17: out = self.strings[val]
            elif typ == 0x18: out = self.types[val]
            elif typ in (0x19, 0x1b): out = self.field_symbol(val)
            elif typ == 0x1a: out = self.method_symbol(val)
            else: out = val
        return {'type': typ, 'value': out}, p

    def annotation_set(self, off):
        out = []
        if off:
            for p in range(off + 4, off + 4 + 4 * self.u32(off), 4):
                item = self.u32(p); value, _ = self.annotation(item + 1)
                out.append({'visibility': self.read(item, 1)[0], 'annotation': value})
        return sorted(out, key=stable)

    def annotation_directory(self, off):
        if not off: return {'class': [], 'fields': {}, 'methods': {}, 'parameters': {}}
        out = {'class': self.annotation_set(self.u32(off)), 'fields': {}, 'methods': {}, 'parameters': {}}
        counts = [self.u32(off + n) for n in (4, 8, 12)]; p = off + 16
        for group, count in zip(('fields', 'methods', 'parameters'), counts):
            for _ in range(count):
                idx, at = self.u32(p), self.u32(p + 4); p += 8
                key = self.field_symbol(idx) if group == 'fields' else self.method_symbol(idx)
                if group == 'parameters':
                    value = [self.annotation_set(self.u32(at + 4 + 4 * n)) for n in range(self.u32(at))]
                else: value = self.annotation_set(at)
                require(key not in out[group], 'Duplicate annotations entry')
                out[group][key] = value
        return out

    def payload(self, codeoff, pc, opcode, labels, uses):
        p = codeoff + 16 + 2 * pc; ident = self.u16(p)
        if opcode == 'array-data':
            require(ident == 0x300, 'Wrong array payload identifier')
            width, count = self.u16(p + 2), self.u32(p + 4)
            require(width in (1, 2, 4, 8), 'Invalid array element width')
            return {'width': width, 'count': count, 'bytes': self.read(p + 8, width * count).hex()}
        require(ident == (0x100 if opcode == 'packed-switch-data' else 0x200), 'Wrong switch identifier')
        count = self.u16(p + 2)
        if opcode == 'packed-switch-data':
            first = self.i32(p + 4); keys = list(range(first, first + count)); targets = p + 8
        else:
            keys = [self.i32(p + 4 + 4 * n) for n in range(count)]; targets = p + 4 + 4 * count
        require(pc in uses, 'Unreferenced switch payload, manual review required')
        # Payload can legally have multiple users, with offsets relative to each switch.
        resolved = []
        for user in uses[pc]:
            resolved.append({'from': labels[user], 'cases': [[key, label(labels, user + self.i32(targets + 4 * n))]
                                                             for n, key in enumerate(keys)]})
        return resolved


CLASS = re.compile(r'^Class #\d+\s*-\s*$', re.M)
SECTION = re.compile(r'^  (Interfaces|Static fields|Instance fields|Direct methods|Virtual methods)\s+-\s*$', re.M)
MEMBER = re.compile(r'^    #\d+\s+: \(in ([^\n]+)\)\s*$', re.M)
INSTRUCTION = re.compile(r'^([0-9a-fA-F]+):[^|]*\|([0-9a-fA-F]+): (.*)$')
REFERENCE_COMMENT = re.compile(r'\s+//\s+(?:(?:string|type|field|method|proto|call_site|method_handle)@[0-9a-fA-F]+(?:,?\s*)?)+$')
UNRESOLVED = re.compile(r'\b(?:string|type|field|method|proto|call_site|method_handle)@[0-9a-fA-F]+\b')
BRANCHES = {'goto', 'goto/16', 'goto/32', 'packed-switch', 'sparse-switch', 'fill-array-data'}
PAYLOADS = {'packed-switch-data', 'sparse-switch-data', 'array-data'}


def attribute(text, name, quoted=False):
    expr = r"^\s+" + re.escape(name) + (r"\s+: '(.*)'\s*$" if quoted else r'\s+: (.*)$')
    match = re.search(expr, text, re.M)
    require(match is not None, 'Missing dexdump attribute ' + name)
    return match[1]


def label(labels, pc):
    require(pc in labels, 'Branch/catch target outside instruction boundaries: 0x%x' % pc)
    return labels[pc]


def parse_method(text, owner, group, binary=None):
    name = attribute(text, 'name', True); proto = attribute(text, 'type', True)
    key = name + proto; access = int(attribute(text, 'access').split()[0], 16)
    out = {'group': group, 'access': access, 'prototype': proto}
    if re.search(r'^\s+code\s+: \(none\)', text, re.M):
        out['body'] = None
        if binary: require(binary.code[(owner, key)] == 0, 'DEX/decdump code presence mismatch')
        return key, out
    registers, ins, outs = [int(attribute(text, x)) for x in ('registers', 'ins', 'outs')]
    size = int(attribute(text, 'insns size').split()[0])
    lines = []; raw = []
    for line in text.splitlines():
        match = INSTRUCTION.match(line)
        if match: raw.append((int(match[1], 16), int(match[2], 16), match[3]))
    require(raw and raw[0][1] == 0, 'Missing dexdump -d disassembly for ' + owner + key)
    require([x[1] for x in raw] == sorted(set(x[1] for x in raw)), 'Nonmonotonic instruction PCs')
    require(raw[-1][1] < size, 'Instruction outside code bounds')
    labels = {pc: 'I%d' % n for n, (_, pc, _) in enumerate(raw)}; labels[size] = 'END'
    codeoff = binary.code[(owner, key)] if binary else None
    if binary:
        require(codeoff and binary.u32(codeoff + 12) == size, 'Binary/decdump code size mismatch')
        require([binary.u16(codeoff + x) for x in (0, 2, 4)] == [registers, ins, outs], 'Code header mismatch')
        binary.read(codeoff + 16, size * 2)
        require(all(absolute == codeoff + 16 + 2 * pc for absolute, pc, _ in raw), 'Instruction file offset mismatch')
    uses = {}
    for _, pc, value in raw:
        op = value.split()[0]
        if op in ('packed-switch', 'sparse-switch', 'fill-array-data'):
            m = re.search(r', ([0-9a-fA-F]+)\s+//', value)
            require(m is not None, 'Unrecognized payload reference')
            uses.setdefault(int(m[1], 16), []).append(pc)
    for _, pc, value in raw:
        op = value.split()[0]
        require(op != '(invalid)' and 'unknown' not in op.lower(), 'Invalid opcode in dexdump')
        if op in PAYLOADS:
            require(binary is not None, 'Payload requires DEX bytes: ' + owner + key)
            value = {'opcode': op, 'payload': binary.payload(codeoff, pc, op, labels, uses)}
        else:
            value = REFERENCE_COMMENT.sub('', value)
            if binary and op in ('const-string', 'const-string/jumbo'):
                at = codeoff + 16 + 2 * pc
                idx = binary.u16(at + 2) if op == 'const-string' else binary.u32(at + 2)
                require(binary.read(at, 1)[0] == (0x1a if op == 'const-string' else 0x1b), 'Const-string opcode mismatch')
                reg = binary.read(at + 1, 1)[0]
                value = 'const-string v%d, %s' % (reg, json.dumps(binary.strings[idx], ensure_ascii=True))
            # Resolved strings can literally contain text such as method@0000.
            unquoted = re.sub(r'"(?:[^"\\]|\\.)*"', '"<string>"', value)
            require(not UNRESOLVED.search(unquoted), 'Unresolved symbolic operand: ' + value)
            if op in BRANCHES or op.startswith('if-'):
                m = re.search(r'(?:, | )([0-9a-fA-F]+)\s+// [+-][0-9a-fA-F]+$', value)
                require(m is not None, 'Unrecognized branch syntax: ' + value)
                value = value[:m.start(1)] + label(labels, int(m[1], 16))
            # Index growth can force jumbo; operand and every branch remain checked.
            if value.startswith('const-string/jumbo '): value = 'const-string ' + value[len('const-string/jumbo '):]
        lines.append(value)
    catch = re.search(r'^\s+catches\s+: (.*?)(?=^\s+(?:positions|locals)\s*:|\Z)', text, re.M | re.S)
    require(catch is not None, 'Missing catch table')
    catches = []
    if catch[1].strip() != '(none)':
        catchlines = catch[1].strip().splitlines(); count = int(catchlines[0]); current = None
        for rawline in catchlines[1:]:
            line = rawline.strip()
            if not line: continue
            m = re.fullmatch(r'0x([0-9a-fA-F]+) - 0x([0-9a-fA-F]+)', line)
            if m:
                current = {'start': label(labels, int(m[1], 16)), 'end': label(labels, int(m[2], 16)), 'handlers': []}
                catches.append(current); continue
            m = re.fullmatch(r'(.+) -> 0x([0-9a-fA-F]+)', line)
            require(m is not None and current is not None, 'Unknown catch syntax: ' + line)
            current['handlers'].append([m[1], label(labels, int(m[2], 16))])
        require(len(catches) == count and all(c['handlers'] for c in catches), 'Catch table size mismatch')
    out['body'] = {'registers': registers, 'ins': ins, 'outs': outs, 'instructions': lines, 'catches': catches}
    return key, out


def parse_dump(text, data=None):
    binary = Dex(data) if data is not None else None
    classes = {}
    for block in CLASS.split(text)[1:]:
        owner = attribute(block, 'Class descriptor', True)
        require(owner not in classes, 'Duplicate dexdump class')
        row = {'access': int(attribute(block, 'Access flags').split()[0], 16),
               'superclass': attribute(block, 'Superclass', True), 'interfaces': [], 'fields': {}, 'methods': {}}
        sections = list(SECTION.finditer(block))
        require([m[1] for m in sections] == ['Interfaces', 'Static fields', 'Instance fields', 'Direct methods', 'Virtual methods'],
                'Unknown/missing dexdump class section')
        for n, section in enumerate(sections):
            body = block[section.end():sections[n + 1].start() if n + 1 < len(sections) else len(block)]
            group = section[1]
            if group == 'Interfaces':
                row['interfaces'] = re.findall(r"^    #\d+\s+: '([^']+)'$", body, re.M)
                continue
            matches = list(MEMBER.finditer(body))
            for i, member in enumerate(matches):
                require(member[1] == owner, 'Dump member owner mismatch')
                part = body[member.end():matches[i + 1].start() if i + 1 < len(matches) else len(body)]
                part = re.split(r'^  source_file_idx', part, flags=re.M)[0]
                if group.endswith('methods'):
                    key, value = parse_method(part, owner, 'direct' if group == 'Direct methods' else 'virtual', binary)
                    require(key not in row['methods'], 'Duplicate dump method')
                    row['methods'][key] = value
                else:
                    key = attribute(part, 'name', True) + ':' + attribute(part, 'type', True)
                    require(key not in row['fields'], 'Duplicate dump field')
                    row['fields'][key] = {'group': 'static' if group == 'Static fields' else 'instance',
                                          'access': int(attribute(part, 'access').split()[0], 16)}
                    val = re.search(r'^\s+value\s+: (.*)$', part, re.M)
                    if val: row['fields'][key]['dumpValue'] = val[1]
        if binary:
            expected = binary.classes[owner]
            for key in ('access', 'superclass', 'interfaces'):
                require(row[key] == expected[key], 'Binary/dump class mismatch: ' + owner + ' ' + key)
            require(set(row['methods']) == set(expected['methods']), 'Binary/dump method inventory mismatch')
            require(set(row['fields']) == set(expected['fields']), 'Binary/dump field inventory mismatch')
            for name, method in row['methods'].items():
                require(all(method[k] == v for k, v in expected['methods'][name].items()), 'Binary/dump method metadata mismatch')
            for name, field in row['fields'].items():
                require(all(field[k] == expected['fields'][name][k] for k in ('access', 'group')), 'Field metadata mismatch')
            row['fields'] = expected['fields']; row['annotations'] = expected['annotations']
        classes[owner] = row
    require(classes, 'No DEX classes parsed')
    if binary: require(set(classes) == set(binary.classes), 'Binary/dump class inventory mismatch')
    return {'format': FORMAT, 'classes': classes, 'completeBinaryMetadata': binary is not None,
            'limits': LIMITS, 'dumpSha256': sha(text.encode('utf-8', 'surrogatepass')), 'dexSha256': sha(data) if data is not None else None}


def extract(path, dexdump):
    path = pathlib.Path(path)
    entries = {}
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), 'Duplicate ZIP entries')
            require(archive.testzip() is None, 'ZIP CRC failure')
            dex_names = {n for n in names if n.lower().endswith('.dex')}
            expected_names = {'classes.dex'} | {'classes%d.dex' % n for n in range(2, len(dex_names) + 1)}
            require(dex_names and dex_names == expected_names,
                    'Noncanonical, missing, or gapped DEX entries: ' + repr(sorted(dex_names)))
            entries = {n: archive.read(n) for n in sorted(dex_names)}
    else: entries = {path.name: path.read_bytes()}
    require(entries, 'No root DEX files')
    result = {'format': FORMAT, 'classes': {}, 'completeBinaryMetadata': True, 'limits': LIMITS,
              'source': str(path.resolve()), 'sourceSha256': sha(path.read_bytes()), 'dexFiles': {}}
    with tempfile.TemporaryDirectory(prefix='symbolic-dex-', dir=str(pathlib.Path(__file__).parent)) as temp:
        for n, data in sorted(entries.items()):
            target = pathlib.Path(temp) / pathlib.Path(n).name; target.write_bytes(data)
            run = subprocess.run([str(dexdump), '-d', str(target)], capture_output=True)
            require(run.returncode == 0, 'dexdump failed: ' + run.stderr.decode('utf-8', 'backslashreplace'))
            require(not run.stderr.strip(), 'dexdump emitted diagnostics requiring manual review: ' + run.stderr.decode('utf-8', 'backslashreplace'))
            item = parse_dump(decode_dump(run.stdout), data)
            item['dumpSha256'] = sha(run.stdout)
            require(not set(result['classes']) & set(item['classes']), 'Class duplicated across DEX files')
            result['classes'].update(item['classes'])
            result['dexFiles'][n] = {'sha256': sha(data), 'dumpSha256': item['dumpSha256'], 'classCount': len(item['classes'])}
    return result


ORDINAL_METHOD = re.compile(r'^(?:access\$\d+|lambda\$[^:()]+\$\d+)$')
ACC_SYNTHETIC = 0x1000
ORDINAL_CLASS = re.compile(r'^(.*(?:\$\$ExternalSynthetic(?:Lambda|Backport|Outline)|\$))\d+;$')


def translate(value, class_map, method_map):
    """Atomic replacement, so permutations never cascade or erase distinctions."""
    replacements = {old: new for old, new in class_map.items() if old != new}
    for (owner, old), new in method_map.items():
        oldname, proto = old.split('(', 1); newname = new.split('(', 1)[0]
        if oldname == newname: continue
        replacements[owner + '.' + oldname + ':(' + proto] = class_map.get(owner, owner) + '.' + newname + ':(' + proto
    expr = re.compile('|'.join(re.escape(x) for x in sorted(replacements, key=len, reverse=True))) if replacements else None
    def walk(obj):
        if isinstance(obj, str):
            # String literals are observable program data, never symbolic references.
            if obj.startswith(('const-string ', 'const-string/jumbo ')): return obj
            return expr.sub(lambda m: replacements[m[0]], obj) if expr else obj
        if isinstance(obj, list): return [walk(v) for v in obj]
        if isinstance(obj, dict):
            if obj.get('type') == 0x17 and set(obj) == {'type', 'value'}: return copy.deepcopy(obj)
            return {walk(k): (v if k == 'dumpValue' else walk(v)) for k, v in obj.items()}
        return obj
    return walk(value)


def transformed_classes(classes, cm, mm):
    result = {}
    for owner, value in classes.items():
        out = translate(value, cm, mm); methods = {}
        for old, body in value['methods'].items():
            name = mm.get((owner, old), old)
            # Prototype may mention a renamed synthetic class.
            name = translate(name, cm, {})
            require(name not in methods, 'Mapping collapses methods')
            methods[name] = translate(body, cm, mm)
        out['methods'] = methods; key = cm.get(owner, owner)
        require(key not in result, 'Mapping collapses classes')
        result[key] = out
    return result


def finalize_ordinal_mappings(before, after, class_map, method_map, proofs):
    cm,mm=dict(class_map),dict(method_map); rejected=[]
    class_proofs={p['before']:p for p in proofs if p['kind']=='class'}
    method_proofs={(p['owner'],p['before']):p for p in proofs if p['kind']=='method'}
    def drop_class(owner,reason):
        target=cm.pop(owner)
        rejected.append({'kind':'class','before':owner,'after':target,'reason':reason,'localProof':class_proofs[owner]})
    def drop_method(key,reason):
        target=mm.pop(key)
        rejected.append({'kind':'method','owner':key[0],'before':key[1],'after':target,'reason':reason,'localProof':method_proofs[key]})
    while True:
        changed=False; groups=collections.defaultdict(list)
        for owner in before:groups[cm.get(owner,owner)].append(owner)
        for target,owners in sorted(groups.items()):
            if len(owners)<2:continue
            movers=[o for o in owners if cm.get(o,o)!=o]
            require(movers,'Identity-only class collision')
            for owner in movers:drop_class(owner,'Noninjective class destination '+target);changed=True
        for owner,value in before.items():
            groups=collections.defaultdict(list)
            for old in value['methods']:
                new=translate(mm.get((owner,old),old),cm,{})
                groups[new].append(old)
            for target,keys in sorted(groups.items()):
                if len(keys)<2:continue
                movers=[k for k in keys if mm.get((owner,k),k)!=k]
                require(movers,'Identity-only method collision')
                for key in movers:drop_method((owner,key),'Noninjective method destination '+target);changed=True
        # Pruning one move can expose an upstream destination collision. Iterate
        # to injectivity before ever constructing a keyed result dictionary.
        if changed:continue
        mapped=transformed_classes(before,cm,mm)
        bad_classes=[owner for owner,target in cm.items() if owner!=target and mapped[target]!=after.get(target)]
        bad_methods=[]
        for (owner,old),target in mm.items():
            if old==target:continue
            final_owner=cm.get(owner,owner);final_key=translate(target,cm,{})
            if mapped[final_owner]['methods'][final_key]!=after.get(final_owner,{}).get('methods',{}).get(final_key):bad_methods.append((owner,old))
        for owner in bad_classes:drop_class(owner,'Final exact class proof no longer holds after mapping pruning')
        for key in bad_methods:drop_method(key,'Final exact method body/prototype/access proof no longer holds after mapping pruning')
        if bad_classes or bad_methods:continue
        kept=[p for p in proofs if (cm.get(p['before'])==p['after'] if p['kind']=='class' else mm.get((p['owner'],p['before']))==p['after'])]
        require(len(kept)+len(rejected)==len(proofs),'Candidate proof record lost or duplicated')
        require(len(mapped)==len(before),'Class inventory lost during finalization')
        for owner,value in before.items():require(len(mapped[cm.get(owner,owner)]['methods'])==len(value['methods']),'Method inventory lost during finalization')
        return mapped,sorted(kept,key=stable),sorted(rejected,key=stable)


def reconcile(before, after):
    cm, mm, proofs, ambiguous = {}, {}, [], []
    # Only ACC_SYNTHETIC, exact, one-to-one, same-owner/prototype/body/access matches can move an ordinal.
    # Iterate because accessor proofs can unlock lambda class proofs and vice versa.
    while True:
        changed = False
        for owner, oldclass in before.items():
            target = cm.get(owner, owner)
            if target not in after: continue
            oldgroups, newgroups = {}, {}
            for key, method in oldclass['methods'].items():
                if method['access'] & ACC_SYNTHETIC and ORDINAL_METHOD.fullmatch(key.split('(', 1)[0]) and (owner, key) not in mm:
                    sig = (re.sub(r'\d+$', '#', key.split('(', 1)[0]), digest(translate(method, cm, mm))); oldgroups.setdefault(sig, []).append(key)
            used = {v for (o, _), v in mm.items() if o == owner}
            for key, method in after[target]['methods'].items():
                if method['access'] & ACC_SYNTHETIC and ORDINAL_METHOD.fullmatch(key.split('(', 1)[0]) and key not in used:
                    newgroups.setdefault((re.sub(r'\d+$', '#', key.split('(', 1)[0]), digest(method)), []).append(key)
            for sig in oldgroups.keys() & newgroups.keys():
                olds, news = oldgroups[sig], newgroups[sig]
                if len(olds) == len(news) == 1:
                    old, new = olds[0], news[0]
                    # Same-name exact entries need no mapping, but reserve them consistently.
                    mm[(owner, old)] = new; changed = True
                    if old != new: proofs.append({'kind': 'method', 'owner': owner, 'before': old, 'after': new, 'exactBodyPrototypeAccessSha256': sig[1]})
        oldgroups, newgroups = {}, {}
        for owner, value in before.items():
            match = ORDINAL_CLASS.fullmatch(owner)
            if match and owner not in cm:
                method_names = [translate(mm.get((owner, key), key), cm, {}) for key in value['methods']]
                if len(method_names) != len(set(method_names)): continue  # Finalizer retains/reports collisions.
                mapped = transformed_classes({owner: value}, cm, mm)[owner]
                sig = digest(translate(mapped, {owner: '@SELF;'}, {}))
                oldgroups.setdefault((match[1], sig), []).append(owner)
        for owner, value in after.items():
            match = ORDINAL_CLASS.fullmatch(owner)
            if match and owner not in cm.values():
                sig = digest(translate(value, {owner: '@SELF;'}, {}))
                newgroups.setdefault((match[1], sig), []).append(owner)
        for family_sig in oldgroups.keys() & newgroups.keys():
            olds, news = oldgroups[family_sig], newgroups[family_sig]
            if len(olds) == len(news) == 1:
                old, new = olds[0], news[0]; cm[old] = new; changed = True
                if old != new: proofs.append({'kind': 'class', 'before': old, 'after': new, 'exactSelfNormalizedClassSha256': family_sig[1]})
        if not changed: break
    return finalize_ordinal_mappings(before, after, cm, mm, proofs)


def compare(before, after, allow_ordinals=True):
    require(before['format'] == after['format'] == FORMAT, 'Incompatible inventory format')
    old, new = before['classes'], after['classes']
    mapped, proofs, rejections = reconcile(old, new) if allow_ordinals else (old, [], [])
    added, removed = sorted(new.keys() - mapped.keys()), sorted(mapped.keys() - new.keys())
    changed = []
    unchanged = 0
    for owner in sorted(mapped.keys() & new.keys()):
        a, b = mapped[owner], new[owner]
        if a == b: unchanged += 1; continue
        row = {'class': owner, 'metadataChanges': {}, 'addedMethods': [], 'removedMethods': [], 'changedMethods': []}
        for key in a.keys() | b.keys():
            if key != 'methods' and a.get(key) != b.get(key): row['metadataChanges'][key] = {'before': a.get(key), 'after': b.get(key)}
        am, bm = a['methods'], b['methods']
        row['addedMethods'] = [{'method': key, 'after': bm[key]} for key in sorted(bm.keys() - am.keys())]
        row['removedMethods'] = [{'method': key, 'before': am[key]} for key in sorted(am.keys() - bm.keys())]
        for key in sorted(am.keys() & bm.keys()):
            if am[key] != bm[key]:
                diff = list(difflib.unified_diff(json.dumps(am[key], indent=2, sort_keys=True).splitlines(),
                                                json.dumps(bm[key], indent=2, sort_keys=True).splitlines(),
                                                fromfile='before', tofile='after', lineterm=''))
                row['changedMethods'].append({'method': key, 'before': am[key], 'after': bm[key], 'diff': diff})
        changed.append(row)
    equivalent = not (added or removed or changed)
    complete = bool(before.get('completeBinaryMetadata') and after.get('completeBinaryMetadata'))
    return {'format': FORMAT, 'equivalent': equivalent, 'passedCompleteSemanticComparison': equivalent and complete,
            'rawClassDeltaCounts': {'added': len(new.keys() - old.keys()), 'removed': len(old.keys() - new.keys()),
                                    'changed': sum(old[k] != new[k] for k in old.keys() & new.keys())},
            'completeBinaryMetadata': bool(before.get('completeBinaryMetadata') and after.get('completeBinaryMetadata')),
            'limits': LIMITS, 'ordinalMappings': proofs, 'ordinalMappingRejections': rejections, 'unchangedClasses': unchanged,
            'addedClasses': {k: new[k] for k in added}, 'removedClasses': {k: mapped[k] for k in removed},
            'changedClasses': changed,
            'summary': {'addedClasses': len(added), 'removedClasses': len(removed), 'changedClasses': len(changed),
                        'changedMethods': sum(len(x['changedMethods']) for x in changed),
                        'addedMethodsInExistingClasses': sum(len(x['addedMethods']) for x in changed),
                        'removedMethodsInExistingClasses': sum(len(x['removedMethods']) for x in changed),
                        'ordinalMappings': len(proofs), 'rejectedOrdinalMappings': len(rejections)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='command', required=True)
    p = subs.add_parser('extract'); p.add_argument('input', type=pathlib.Path); p.add_argument('--dexdump', required=True); p.add_argument('--output', required=True, type=pathlib.Path)
    p = subs.add_parser('parse-dump'); p.add_argument('input', type=pathlib.Path); p.add_argument('--dex', type=pathlib.Path); p.add_argument('--output', required=True, type=pathlib.Path)
    p = subs.add_parser('compare'); p.add_argument('before', type=pathlib.Path); p.add_argument('after', type=pathlib.Path); p.add_argument('--output', required=True, type=pathlib.Path); p.add_argument('--no-ordinal-mapping', action='store_true')
    args = parser.parse_args()
    if args.command == 'extract': out = extract(args.input, args.dexdump)
    elif args.command == 'parse-dump': out = parse_dump(decode_dump(args.input.read_bytes()), args.dex.read_bytes() if args.dex else None)
    else: out = compare(json.loads(args.before.read_text()), json.loads(args.after.read_text()), not args.no_ordinal_mapping)
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(out, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'output': str(args.output), 'classes': len(out.get('classes', {})), 'summary': out.get('summary'),
                      'equivalent': out.get('equivalent'), 'completeBinaryMetadata': out['completeBinaryMetadata']}))
    if args.command == 'compare' and not out['passedCompleteSemanticComparison']: raise SystemExit(2)


if __name__ == '__main__': main()
