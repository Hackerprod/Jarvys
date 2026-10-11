#!/usr/bin/env python3
"""Portable exact delivered-v79/reconstructed-v79 HOST bounded reconciliation.

Reads only the pinned APK binaries and pinned Python/decdump tools. Complete
class records and raw comparison are rederived; no inventory JSON input is used.
Semantic output is deterministic. Real temporary paths and raw dump hashes are
reported separately as runtime provenance, never rewritten into historical evidence.

An external audit, never a generic comparator exemption or release approval.
Graph implementation copied unchanged from the prior template proof; every
host position, method, input, control, and artifact binding is independently pinned.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
import pathlib
import re
import zipfile
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent
O = ROOT.parent
R = ROOT.parents[2] if len(ROOT.parents) > 2 else ROOT
CANONICAL_PINS = {'delivered': {'classes': '4d15334c7aedb42a054763196719d8aa78f293a05ea4c189cb8e64aabffcc911', 'semanticInventory': 'd20643baf89939e4723bc3974166f19fac7904cc86c173ce7ad86cb4da0c61a6'}, 'reconstructed': {'classes': 'c589120d8659654fde80a542d88485d945edf17a72e44797e5ef25392ee62cf4', 'semanticInventory': 'f618c5cf86939e1f061598ab5814e97a05db493f9ee89272fb0b39dc4c675358'}, 'delivered-vs-reconstructed-v79-host-classes5.semantic.json': '89f45e2b6a5a1b0648d42226ee26dbf6c9ffd0ce0a0c22e5a1f9f6b51d85ea37', 'delivered-vs-reconstructed-v79-host-entry-delta.json': 'd58106bf4fcf7476bdbc558dbd82997133be61583ae5f45b3c70485aca0e5160', 'reviewedProofCore': 'bb9b4bbf8027c100e7900aa9c85d44f29ea28c50c1126f5c9e00bce72cc4c9d1'}
DEXDUMP_SHA = 'ef273cb9e5a01ccbba046ea7c80d73203f0c908d2779b4c58e21091753c86049'
REVIEWED_PROOF_SOURCE_SHA = '0421f948cccfa2850853ad787a12f2392c4a03743cb6e7038f829de4947e51f7'
SYMBOLIC_HELPER_SHA = 'd2f331fc834f999aebf199b15dcb4408cc4ea36590d0c266394994f2083520db'
GRAPH_SOURCE_SHA = 'e3d8790604442297a777f9c8ba6e400d4afd41d299e3d45420a437b2082e208d'
APKS = [
    (R.parent / 'jarvys-v79-restored/Jarvys-Factory-sensors-full-v79-arm64-test.apk',
     'a79969819f9616a6261d19e0a91318add7f35de7d0aa1a742d032cd6f280896f'),
    (R / 'FACTORY-SENSORS-v79-validation/final-artifacts/app-full-release-unsigned.apk',
     '93976b3af088d9617da091b76b05752606fca56f08c352fa927a5afac0001b1e'),
]
DEX_PINS = {
    'classes.dex': ['5c62a1314e47560e2cd72620bd25e06dc37f1770ed689942dbc5045674fcbff6'] * 2,
    'classes2.dex': ['2bd861827cddf7d9f710acd65633ff4714356fa1f432b2987d129b8aa52535d7'] * 2,
    'classes3.dex': ['d4e706846c8d230ae1fbacfb3a250362c43d173380813d8a163993cdbeace5bd'] * 2,
    'classes4.dex': ['70c04f03cd099f7f3d710005cf3dda590c4daf6af5483af102cf8b15901c1ace'] * 2,
    'classes5.dex': ['767bbbf531bfb8d3d5dfeed906a95e725f3be3c44bd1faf1a7deb47cdce48b6d',
                     '6d1ec49611def972f3901f842660b5695f384d5c66095d863c916769cb972f27'],
}
DB = 'Lcom/jarvys/factory/runtime/DatabaseRequest;'
RT = 'Lcom/jarvys/factory/runtime/FactoryRuntime;'
LM = 'Lcom/jarvys/factory/runtime/FactoryRuntime$$ExternalSyntheticLambda33;'
PD = 'Lcom/jarvys/factory/runtime/PrivateDatabase;'
VALIDATE = 'validate(Ljava/lang/String;Lorg/json/JSONObject;)V'
SHIM_NAME = '$r8$lambda$wPa24sCbFlYMF0HLDVXLMGD26OM'
SHIM = SHIM_NAME + '(' + PD + ')V'
SHIM_CALL = 'invoke-static {v0}, ' + RT + '.' + SHIM_NAME + ':(' + PD + ')V'
CLOSE_CALL = 'invoke-virtual {v0}, ' + PD + '.close:()V'
CATCHES = [
    [{'start': 'I13', 'end': 'I23', 'handlers': [['Lorg/json/JSONException;', 'I259']]},
     {'start': 'I31', 'end': 'I259', 'handlers': [['Lorg/json/JSONException;', 'I259']]}],
    [{'start': 'I13', 'end': 'I23', 'handlers': [['Lorg/json/JSONException;', 'I258']]},
     {'start': 'I31', 'end': 'I258', 'handlers': [['Lorg/json/JSONException;', 'I258']]}],
]
CASE_TARGETS = {
    'database.info': (-1376304831, 245, 244),
    'database.transact': (-1276641283, 177, 176),
    'database.cancel': (37433933, 172, 171),
    'database.close': (278631365, 166, 166),
    'database.select': (499136719, 104, 104),
    'database.migrate': (1668566072, 31, 31),
}
GOTOS = [[30, 73, 94, 144, 149, 171, 176, 213, 222, 240, 249],
         [30, 73, 94, 144, 149, 170, 175, 212, 221, 239]]

def require(ok, message):
    if not ok: raise ValueError(message)

def sha(data): return hashlib.sha256(data).hexdigest()
def stable(data): return json.dumps(data, sort_keys=True, separators=(',', ':'))
def digest(data): return sha(stable(data).encode())
def index(label):
    require(re.fullmatch(r'I[0-9]+', label) is not None, 'Unexpected label: ' + label)
    return int(label[1:])
def java_hash(text):
    value = 0
    for c in text: value = ((value * 31) + ord(c)) & 0xffffffff
    return value - 0x100000000 if value & 0x80000000 else value

def pinned(path, expected):
    raw = path.read_bytes()
    require(sha(raw) == expected, 'Pinned digest differs: ' + str(path))
    return raw

class Graph:
    """Effect-preserving labeled CFG; only unconditional goto is a silent step."""
    def __init__(self, body):
        self.body = body; self.instructions = body['instructions']; self.used_gotos = set(); self.used_payloads = set()

    def jump(self, n):
        value = self.instructions[n]
        return re.fullmatch(r'goto(?:/16|/32)? (I\d+)', value) if isinstance(value, str) else None

    def collapse(self, n):
        seen = set()
        while True:
            require(0 <= n < len(self.instructions), 'CFG target outside body')
            jump = self.jump(n)
            if not jump: return n
            require(n not in seen, 'Unconditional goto cycle is not an approved layout change')
            seen.add(n); self.used_gotos.add(n); n = index(jump[1])

    def node(self, n):
        n = self.collapse(n); instruction = self.instructions[n]
        require(isinstance(instruction, str), 'Execution reaches payload data')
        op = instruction.split()[0]; edges = []; effects = instruction
        if op.startswith('if-'):
            match = re.fullmatch(r'(if-[^ ]+ .+), (I\d+)', instruction)
            require(match is not None, 'Unrecognized conditional syntax')
            effects = match[1]; edges += [('condition-true', index(match[2])), ('condition-false', n + 1)]
        elif op in ('sparse-switch', 'packed-switch'):
            match = re.fullmatch(r'(sparse-switch|packed-switch) (v\d+), (I\d+)', instruction)
            require(match is not None, 'Unrecognized switch')
            at = index(match[3]); payload = self.instructions[at]; self.used_payloads.add(at)
            require(isinstance(payload, dict) and payload['opcode'] == op + '-data', 'Incorrect switch payload kind')
            entries = [row for row in payload['payload'] if row['from'] == 'I%d' % n]
            require(len(entries) == 1, 'Missing/ambiguous switch user')
            cases = entries[0]['cases']; require(len({x[0] for x in cases}) == len(cases), 'Duplicate switch key')
            effects = op + ' ' + match[2]
            edges += [('case:%d' % key, index(target)) for key, target in cases]
            edges.append(('switch-default', n + 1))
        elif op == 'throw' or op.startswith('return'):
            pass
        else:
            require(op not in ('fill-array-data',) and not op.startswith('goto'), 'Unexpected unsupported CFG primitive')
            edges.append(('next', n + 1))
        # Compare the ordered typed exception dispatch for every effect node. This
        # overapproximates which instructions throw, without discarding a handler.
        handlers = []
        for catch in self.body['catches']:
            if index(catch['start']) <= n < index(catch['end']):
                for typ, target in catch['handlers']:
                    handlers.append(typ); edges.append(('exception:%d:%s' % (len(handlers) - 1, typ), index(target)))
        return effects, handlers, [(tag, self.collapse(target)) for tag, target in edges]


def effect_bijection(before, after):
    a, b = Graph(before), Graph(after)
    pending = [(0, 0)]; forward = {}; reverse = {}; evidence = []
    while pending:
        left, right = pending.pop(); left, right = a.collapse(left), b.collapse(right)
        if left in forward:
            require(forward[left] == right, 'One before effect maps to different after effects'); continue
        require(right not in reverse, 'Different before effects map to one after effect')
        forward[left] = right; reverse[right] = left
        op_a, handlers_a, edges_a = a.node(left); op_b, handlers_b, edges_b = b.node(right)
        require(op_a == op_b, 'Effect mismatch at I%d / I%d: %r != %r' % (left, right, op_a, op_b))
        require(handlers_a == handlers_b, 'Ordered catch type mismatch')
        require([tag for tag, _ in edges_a] == [tag for tag, _ in edges_b], 'Branch/switch/rejection edge labels differ')
        pending += [(x[1], y[1]) for x, y in zip(edges_a, edges_b)]
        evidence.append({'before': 'I%d' % left, 'after': 'I%d' % right, 'effect': op_a,
            'orderedExceptionTypes': handlers_a,
            'edges': [{'label': x[0], 'before': 'I%d' % x[1], 'after': 'I%d' % y[1]} for x, y in zip(edges_a, edges_b)]})
    return a, b, forward, reverse, sorted(evidence, key=lambda x: index(x['before']))


def prove_validate(before, after):
    require({k: v for k, v in before.items() if k != 'body'} == {k: v for k, v in after.items() if k != 'body'}, 'validate method metadata differs')
    for j, method in enumerate((before, after)):
        body = method['body']
        require({k: v for k, v in body.items() if k not in ('instructions', 'catches')} == {'registers': 20, 'ins': 2, 'outs': 5}, 'Unexpected host validate register contract')
        require(body['catches'] == CATCHES[j], 'Host validate catch coverage differs')
        require(len(body['instructions']) == [264, 262][j], 'Unexpected host validate instruction count')
    a, b, forward, reverse, evidence = effect_bijection(before['body'], after['body'])
    accounting = []
    for j, (graph, mapped) in enumerate(((a, forward), (b, reverse))):
        payloads = {i for i, value in enumerate(graph.instructions) if isinstance(value, dict)}
        gotos = {i for i in range(len(graph.instructions)) if graph.jump(i)}
        remaining = set(range(len(graph.instructions))) - set(mapped) - gotos - payloads
        require(remaining == [{262}, set()][j], 'Unreviewed unreachable instruction')
        if j == 0: require(graph.instructions[262] == 'nop // spacer', 'Removed instruction is not the exact alignment nop')
        require(payloads == graph.used_payloads == [{263}, {261}][j], 'Unreviewed or unused host switch payload')
        require(gotos == graph.used_gotos == set(GOTOS[j]), 'Unreviewed, unreachable or unexpected goto')
        accounting.append({'recordCount': len(graph.instructions), 'reachableEffects': len(mapped),
            'gotos': [{'at': 'I%d' % n, 'instruction': graph.instructions[n], 'resolvedEffect': 'I%d' % graph.collapse(n)} for n in sorted(gotos)],
            'payloads': ['I%d' % n for n in sorted(payloads)],
            'unreachable': [{'at': 'I%d' % n, 'instruction': graph.instructions[n]} for n in sorted(remaining)]})
    require(len(forward) == 251, 'Unexpected effect-node count')
    cases = []
    for name, (key, old_target, new_target) in CASE_TARGETS.items():
        require(java_hash(name) == key, 'Incorrect expected Java string hash')
        for j, (graph, target, payload_at, reject_at) in enumerate(((a, old_target, 263, 253), (b, new_target, 261, 252))):
            payload = graph.instructions[payload_at]['payload']
            require(len(payload) == 1 and payload[0]['from'] == 'I29', 'Unexpected switch user')
            require(len(payload[0]['cases']) == 6 and [key, 'I%d' % target] in payload[0]['cases'], 'Missing exact method hash case')
            require(graph.instructions[target] == 'const-string v2, ' + json.dumps(name) if name != 'database.migrate'
                    else graph.instructions[target] == 'const-string v6, ' + json.dumps(name), 'Method case name differs')
            reg = 'v6' if name == 'database.migrate' else 'v2'
            require(graph.instructions[target + 1:target + 4] == [
                'invoke-virtual {v0, ' + reg + '}, Ljava/lang/String;.equals:(Ljava/lang/Object;)Z',
                'move-result v0', 'if-eqz v0, I%d' % reject_at], 'Hash collision/equality rejection guard differs')
        require(forward[old_target] == new_target, 'Method case entry does not map')
        cases.append({'name': name, 'javaHashCode': key, 'before': 'I%d' % old_target, 'after': 'I%d' % new_target,
                      'equalityGuardRejectsUnequalNames': ['I253', 'I252']})
    reject_pairs = [(95,95), (98,98), (101,101), (150,150), (223,222), (241,240), (253,252), (256,255), (259,258)]
    invalid = ['invoke-static {}, ' + DB + '.invalid:()Lcom/jarvys/factory/runtime/FactoryException;', 'move-result-object v0', 'throw v0']
    require([n for n,x in enumerate(a.instructions) if x == invalid[0]] == [x for x,y in reject_pairs], 'Extra/missing delivered rejection sink')
    require([n for n,x in enumerate(b.instructions) if x == invalid[0]] == [y for x,y in reject_pairs], 'Extra/missing rebuilt rejection sink')
    for old, new in reject_pairs:
        require(forward[old] == new and a.instructions[old:old+3] == b.instructions[new:new+3] == invalid, 'Rejection sink changed')
    returns = [(165,165), (244,243), (252,251)]
    require([i for i,x in enumerate(a.instructions) if x == 'return-void'] == [x for x,y in returns] and
            [i for i,x in enumerate(b.instructions) if x == 'return-void'] == [y for x,y in returns], 'Normal exits changed')
    for old,new in returns: require(forward[old] == new, 'Normal exit does not map')
    require(a.collapse(30) == 253 and b.collapse(30) == 252, 'Unknown method default does not reject')
    require(forward[170] == 248 and a.instructions[170] == b.instructions[248] == 'const/4 v6, #int 0 // #0', 'Moved shared empty-argument initializer differs')
    require(a.instructions[250:253] == b.instructions[249:252] == [
        'new-array v0, v6, [Ljava/lang/String;', 'invoke-static {v1, v0}, ' + DB + '.exact:(Lorg/json/JSONObject;[Ljava/lang/String;)V', 'return-void'], 'Shared empty-argument rejection tail differs')
    tails = []
    for name in ('database.close', 'database.cancel', 'database.info'):
        _, old, new = CASE_TARGETS[name]
        require(a.collapse(old + 4) == 170 and b.collapse(new + 4) == 248, 'Case bypasses zero-initialized exact-empty arguments')
        tails.append({'method': name, 'successfulEqualityToSharedZeroInitializer': ['I170', 'I248']})
    return {'passed': True, 'method': DB + '->' + VALIDATE,
        'beforeMethodSha256': digest(before), 'afterMethodSha256': digest(after), 'effectNodeBijectionCount': len(forward),
        'recordAccounting': {'before': accounting[0], 'after': accounting[1]}, 'switchCases': sorted(cases, key=lambda x: x['javaHashCode']),
        'defaultRejectsAt': ['I253', 'I252'], 'rejectionSinkCorrespondence': reject_pairs, 'normalExitCorrespondence': returns,
        'catchTables': CATCHES, 'sharedEmptyArgumentTail': tails,
        'fullEffectAndEdgeCorrespondence': evidence,
        'argument': 'Every reachable register/effect instruction is identical under a one-to-one labeled edge mapping, including every conditional, loop, switch key, default edge, normal return and ordered typed exception edge. Only unconditional gotos are silent. Complete record accounting permits exactly the delivered unreachable alignment nop, with no remaining unaccounted instruction.'}


def executable_mentions(classes, fragment):
    out = []
    for owner, cls in classes.items():
        for name, method in cls['methods'].items():
            if method['body'] is None: continue
            for i, instruction in enumerate(method['body']['instructions']):
                if isinstance(instruction, str) and fragment in instruction:
                    out.append({'class': owner, 'method': name, 'instruction': i, 'text': instruction})
    return out


def inventory_delta(a, b):
    require(set(a) == set(b) and len(a) == 8445, 'Unexpected classes5 class membership')
    changed = []
    for owner in sorted(a):
        if a[owner] == b[owner]: continue
        require({k: v for k, v in a[owner].items() if k != 'methods'} == {k: v for k, v in b[owner].items() if k != 'methods'}, 'Class metadata differs: ' + owner)
        am, bm = a[owner]['methods'], b[owner]['methods']
        changed.append({'class': owner, 'added': sorted(bm.keys() - am.keys()), 'removed': sorted(am.keys() - bm.keys()),
                        'changed': sorted(k for k in am.keys() & bm.keys() if am[k] != bm[k])})
    require(changed == [
        {'class': DB, 'added': [], 'removed': [], 'changed': [VALIDATE]},
        {'class': LM, 'added': [], 'removed': [], 'changed': ['run()V']},
        {'class': RT, 'added': [], 'removed': [SHIM], 'changed': []},
    ], 'Observed host delta exceeds the exact three-class reviewed pair')
    return changed


def prove_trampoline(before, after):
    old, new = before[RT]['methods'], after[RT]['methods']; shim = old[SHIM]
    require(SHIM not in new and shim == {'access': 0x1009, 'group': 'direct', 'prototype': '(' + PD + ')V',
        'body': {'catches': [], 'registers': 1, 'ins': 1, 'outs': 1, 'instructions': [CLOSE_CALL, 'return-void']}}, 'Removed method is not the exact stateless synthetic trampoline')
    oldrun, newrun = before[LM]['methods']['run()V'], after[LM]['methods']['run()V']
    expected_run = {'access': 0x11, 'group': 'virtual', 'prototype': '()V', 'body': {'registers': 2, 'ins': 1, 'outs': 1, 'catches': [],
        'instructions': ['iget-object v0, v1, ' + LM + '.f$0:' + PD, SHIM_CALL, 'return-void']}}
    require(oldrun == expected_run, 'Unexpected delivered host lambda body')
    expected_new = copy.deepcopy(expected_run); expected_new['body']['instructions'][1] = CLOSE_CALL
    require(newrun == expected_new, 'Host direct lambda differs from exact trampoline expansion')
    refs = executable_mentions(before, SHIM_NAME)
    require(refs == [{'class': LM, 'method': 'run()V', 'instruction': 1, 'text': SHIM_CALL}], 'Other classes5 trampoline callers remain')
    require(not executable_mentions(after, SHIM_NAME), 'Rebuilt classes5 retains trampoline reference')
    require(stable(before).count(SHIM_NAME) == 2 and SHIM_NAME not in stable(after), 'Unexpected metadata or reflective-name reference to trampoline')
    lm_metadata = {'access': 0x1011, 'annotations': {'class': [], 'fields': {}, 'methods': {}, 'parameters': {}},
        'fields': {'f$0:' + PD: {'access': 0x1011, 'group': 'instance'}}, 'interfaces': ['Ljava/lang/Runnable;'], 'superclass': 'Ljava/lang/Object;'}
    for classes in (before, after):
        require({k:v for k,v in classes[LM].items() if k != 'methods'} == lm_metadata, 'Lambda capture or class contract changed')
    ctor_name = '<init>(' + PD + ')V'
    ctor = {'access': 0x11001, 'group': 'direct', 'prototype': '(' + PD + ')V', 'body': {'catches': [], 'registers': 2, 'ins': 2, 'outs': 1,
        'instructions': ['invoke-direct {v0}, Ljava/lang/Object;.<init>:()V', 'iput-object v1, v0, ' + LM + '.f$0:' + PD, 'return-void']}}
    for classes in (before, after):
        require(classes[LM]['methods'][ctor_name] == ctor and set(classes[LM]['methods']) == {ctor_name, 'run()V'}, 'Lambda constructor or method set changed')
        writes = [r for r in executable_mentions(classes, LM + '.f$0:') if r['text'].startswith('iput')]
        require(writes == [{'class': LM, 'method': ctor_name, 'instruction': 1, 'text': ctor['body']['instructions'][1]}], 'Captured receiver is written outside exact constructor')
        allocations = [r for r in executable_mentions(classes, LM) if r['text'].startswith('new-instance ')]
        require(allocations == [{'class': RT, 'method': 'retireDatabase()V', 'instruction': 8, 'text': 'new-instance v2, ' + LM}], 'Lambda allocation is not exclusive to the existing FactoryRuntime instance path')
        ctors = [r for r in executable_mentions(classes, LM + '.<init>:')]
        require(ctors == [{'class': RT, 'method': 'retireDatabase()V', 'instruction': 9, 'text': 'invoke-direct {v2, v0}, ' + LM + '.<init>:(' + PD + ')V'}], 'Unexpected lambda constructor caller')
    retire = old['retireDatabase()V']
    expected_retire = {'access': 2, 'group': 'direct', 'prototype': '()V', 'body': {'registers': 4, 'ins': 1, 'outs': 2,
        'catches': [{'start': 'I6', 'end': 'I11', 'handlers': [['Ljava/util/concurrent/RejectedExecutionException;', 'I12']]}],
        'instructions': ['iget-object v0, v3, ' + RT + '.database:' + PD, 'const/4 v1, #int 0 // #0',
            'iput-object v1, v3, ' + RT + '.database:' + PD, 'if-nez v0, I5', 'return-void',
            'invoke-virtual {v0}, ' + PD + '.cancel:()V', 'iget-object v1, v3, ' + RT + '.io:Ljava/util/concurrent/ThreadPoolExecutor;',
            'invoke-static {v0}, Ljava/util/Objects;.requireNonNull:(Ljava/lang/Object;)Ljava/lang/Object;',
            'new-instance v2, ' + LM, 'invoke-direct {v2, v0}, ' + LM + '.<init>:(' + PD + ')V',
            'invoke-virtual {v1, v2}, Ljava/util/concurrent/ThreadPoolExecutor;.execute:(Ljava/lang/Runnable;)V', 'return-void',
            'iget-object v1, v3, ' + RT + '.retiredDatabases:Ljava/util/Queue;',
            'invoke-interface {v1, v0}, Ljava/util/Queue;.add:(Ljava/lang/Object;)Z', 'return-void']}}
    require(retire == new['retireDatabase()V'] == expected_retire, 'Capture construction, null guard, cancellation, scheduling or fallback changed')
    require(old['<clinit>()V'] == new['<clinit>()V'], 'FactoryRuntime class initializer changed')
    require(before[PD] == after[PD], 'PrivateDatabase implementation or metadata changed')
    close = before[PD]['methods']['close()V']
    require(close['access'] == 0x20001 and close['body']['instructions'][0] == 'monitor-enter v1', 'Unexpected close synchronization')
    require(close['body']['catches'] == [{'start': 'I1', 'end': 'I6', 'handlers': [['<any>', 'I8']]},
            {'start': 'I9', 'end': 'I10', 'handlers': [['<any>', 'I8']]}], 'Close catch-all monitor release changed')
    # Existing instances normally follow successful class initialization. Also
    # inspect application-local initializer dependencies to rule out constructing
    # an owner reentrantly from the actual compiled initializer chain.
    dependencies = [RT[:-1] + '$$ExternalSyntheticLambda44;', RT[:-1] + '$$ExternalSyntheticLambda45;',
                    'Lcom/jarvys/factory/runtime/PreviewProfiles;', RT[:-1] + '$5;']
    init_instructions = old['<clinit>()V']['body']['instructions']
    app_allocs = [x.split(', ',1)[1] for x in init_instructions if x.startswith('new-instance ') and 'Lcom/jarvys/' in x]
    require(app_allocs == dependencies, 'Unexpected application allocation in FactoryRuntime initializer')
    require([x for x in init_instructions if x.startswith('invoke') and 'Lcom/jarvys/' in x] == [
        'invoke-direct {v9}, ' + dependencies[0] + '.<init>:()V',
        'invoke-direct {v9}, ' + dependencies[1] + '.<init>:()V',
        'invoke-direct {v0}, ' + dependencies[2] + '.<init>:()V',
        'invoke-direct {v0}, ' + dependencies[3] + '.<init>:()V'], 'Unexpected application call in owner initializer')
    initialization_dependencies = []
    for owner in dependencies:
        require(before[owner] == after[owner], 'Initializer dependency implementation changed')
        cls = before[owner]
        require(cls['superclass'] == 'Ljava/lang/Object;' and '<clinit>()V' not in cls['methods'], 'Initializer dependency can trigger unreviewed superclass or static effects')
        body = cls['methods']['<init>()V']['body']
        if 'PreviewProfiles;' in owner:
            expected = ['invoke-direct {v1}, Ljava/lang/Object;.<init>:()V', 'new-instance v0, Ljava/util/HashSet;',
                'invoke-direct {v0}, Ljava/util/HashSet;.<init>:()V', 'iput-object v0, v1, ' + owner + '.live:Ljava/util/Set;', 'return-void']
        else: expected = ['invoke-direct {v0}, Ljava/lang/Object;.<init>:()V', 'return-void']
        require(body['catches'] == [] and body['instructions'] == expected, 'Initializer dependency constructor has extra effects')
        initialization_dependencies.append({'class': owner, 'classSha256': digest(cls), 'noClassInitializer': True,
                                          'constructor': cls['methods']['<init>()V']})
    provider = 'Lcom/jarvys/factory/runtime/PreviewProfiles$Provider;'
    require(before[provider] == after[provider] and before[provider]['interfaces'] == [] and
        all(m['body'] is None and m['access'] == 0x401 for m in before[provider]['methods'].values()),
        'Initializer application interface has executable methods or initialization')
    owner_allocations = [r for r in executable_mentions(before, RT) if r['text'].startswith('new-instance ') and r['text'].endswith(RT)]
    require(owner_allocations == [{'class': 'Lcom/jarvys/agent/apkfactory/FactoryPreviewActivity;',
        'method': 'createRuntime$app_fullRelease(Landroid/widget/FrameLayout;Lcom/jarvys/agent/coding/FactoryPreviewRegistry$Session;)Lcom/jarvys/agent/apkfactory/FactoryPreviewActivity$RuntimeHandle;',
        'instruction': 17, 'text': 'new-instance v5, ' + RT}], 'Unexpected compiled FactoryRuntime allocation path')
    require([r for r in executable_mentions(after, RT) if r['text'].startswith('new-instance ') and r['text'].endswith(RT)] == owner_allocations,
        'Compiled FactoryRuntime allocation path changed')
    return {'passedForCompiledApplicationPath': True, 'removedMethod': RT + '->' + SHIM, 'removedMethodSha256': digest(shim),
        'lambdaRunBeforeSha256': digest(oldrun), 'lambdaRunAfterSha256': digest(newrun), 'exactInlineExpansion': expected_new,
        'onlyClasses5TrampolineCaller': refs, 'onlyClasses5LambdaAllocation': allocations, 'capturedReceiverConstructor': ctor,
        'retireDatabase': retire, 'onlyCompiledOwnerAllocation': owner_allocations,
        'initializerApplicationDependencies': initialization_dependencies, 'applicationProviderInterfaceHasNoExecutableBodies': True,
        'factoryRuntimeClinitSha256': digest(old['<clinit>()V']),
        'privateDatabaseSha256': digest(before[PD]), 'closeBodyIncludingCatchAllMonitorRelease': close,
        'classInitializationArgument': 'The only host compiled Lambda33 allocation and constructor invocation are inside private instance FactoryRuntime.retireDatabase(). The sole compiled owner allocation is the unchanged FactoryPreviewActivity.createRuntime path. The owner initializer only constructs four application helpers whose exact constructors and absence of static initializers are checked; its application Provider interface has only abstract methods, and none of this initialization constructs an owner reentrantly. Under ordinary Android/JVM initialization and standard Java-library behavior, FactoryRuntime is already initialized before this queued callback, so eliminating the later static shim does not remove first-initialization effects or waiting. Reflective/unsafe/native or custom-library manipulation is excluded.',
        'preserved': ['One invocation of identical PrivateDatabase.close on the same final captured receiver.',
            'Exact constructor write, capture read, non-null check, prior cancellation, scheduling and rejected-execution fallback.',
            'No shim argument conversion, local effect, branch, handler, lock or return conversion.',
            'Identical synchronized close implementation, including catch-all monitor release.',
            'Normal void completion and application-level propagated exception object/type/side effects under the stated path assumptions.'],
        'limits': ['Removing the public synthetic method changes method membership for arbitrary reflection or external direct callers.',
            'Exception stack traces, profiling/instrumentation frames, timing, debug locations, verification/linkage/resource failures can differ.',
            'No claim covers dynamic code loading, reflective/unsafe/native mutation, every VM observable, or Android execution.']}

def verify_bytes(raw, expected, label):
    require(sha(raw) == expected, 'Pinned bytes differ: ' + label)
    return raw


def verify_content(value, expected, label):
    actual = digest(value)
    require(actual == expected, 'Canonical content pin differs: ' + label)
    return actual


def canonical_inventory(parsed):
    # No class/member/annotation/field/body record is dropped or rewritten.
    # Only parser provenance is replaced by explicit binary origin below.
    require(set(parsed) == {'format', 'classes', 'completeBinaryMetadata', 'limits', 'dumpSha256', 'dexSha256'},
            'Unexpected parser top-level fields require review')
    require(parsed['format'] == 'independent-symbolic-dex-v1' and parsed['completeBinaryMetadata'] is True,
            'Incomplete or unsupported parser result')
    return {k: parsed[k] for k in ('format', 'classes', 'completeBinaryMetadata', 'limits')}


def load_and_bind(args):
    pinned(args.symbolic_helper, SYMBOLIC_HELPER_SHA)
    pinned(args.graph_source, GRAPH_SOURCE_SHA)
    pinned(args.dexdump, DEXDUMP_SHA)
    spec = importlib.util.spec_from_file_location('pinned_host_symbolic_dex', args.symbolic_helper)
    symbolic = importlib.util.module_from_spec(spec); spec.loader.exec_module(symbolic)
    apk_paths = [args.before_apk, args.after_apk]
    entries = []; reports = []; dex_sources = []; unchanged_dex_scan = []; defined_classes = set()
    provenance = {'kind': 'actual-runtime-provenance-excluded-from-semantic-digest',
        'apkPaths': [str(x.resolve()) for x in apk_paths],
        'toolPaths': {'symbolicHelper': str(args.symbolic_helper.resolve()), 'graphSource': str(args.graph_source.resolve()),
                      'dexdump': str(args.dexdump.resolve())}, 'temporaryRoot': str(args.temp_root.resolve()), 'dexdumpRuns': []}
    for side, apk in enumerate(apk_paths):
        expected = APKS[side][1]; pinned(apk, expected)
        with zipfile.ZipFile(apk) as archive:
            require(archive.testzip() is None and len(archive.namelist()) == len(set(archive.namelist())), 'Invalid source APK ZIP')
            entry_hashes = {n: sha(archive.read(n)) for n in archive.namelist()}; entries.append(entry_hashes)
            dexes = {n: h for n,h in entry_hashes.items() if n.lower().endswith('.dex')}
            require(dexes == {n: v[side] for n,v in DEX_PINS.items()}, 'Exact host DEX byte pins differ')
            raw = verify_bytes(archive.read('classes5.dex'), DEX_PINS['classes5.dex'][side], 'APK classes5.dex')
            dex_sources.append(raw)
            if side == 0:
                for name in sorted(set(dexes) - {'classes5.dex'}):
                    binary = symbolic.Dex(archive.read(name))
                    require(SHIM_NAME not in binary.strings and LM not in binary.types and RT not in binary.types, 'Unchanged host DEX can reference removed shim, Lambda33 or FactoryRuntime')
                    require(not defined_classes.intersection(binary.classes), 'Duplicate host class definitions')
                    defined_classes.update(binary.classes)
                    require(not [m for m in binary.methods if m[1] == SHIM_NAME] and not [m for m in binary.methods if m[0] == LM], 'Unchanged DEX has shim/lambda method reference')
                    unchanged_dex_scan.append({'dex': name, 'sha256': dexes[name], 'classCount': len(binary.classes),
                        'lambdaTypeAbsent': True, 'factoryRuntimeTypeAbsent': True, 'shimStringAbsent': True, 'shimOrLambdaMethodIdsAbsent': True})
            reports.append({'artifact': ['delivered-v79-host-apk', 'reconstructed-v79-host-apk'][side],
                'sha256': expected, 'entries': len(entry_hashes), 'dexSha256': dexes})
    entry_delta = {'beforeApkSha256': APKS[0][1], 'afterApkSha256': APKS[1][1], 'beforeEntries': len(entries[0]), 'afterEntries': len(entries[1]),
        'changes': {n: {'before': entries[0].get(n), 'after': entries[1].get(n)} for n in sorted(entries[0].keys() | entries[1].keys()) if entries[0].get(n) != entries[1].get(n)}}
    verify_content(entry_delta, CANONICAL_PINS['delivered-vs-reconstructed-v79-host-entry-delta.json'], 'full APK entry delta')
    inventories = []; rederived = []; alignment = []
    args.temp_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='host-proof-', dir=args.temp_root) as temporary:
        for side, raw in enumerate(dex_sources):
            label = ['delivered', 'reconstructed'][side]
            source = pathlib.Path(temporary) / (label + '-classes5.dex')
            source.write_bytes(raw)
            require(source.read_bytes() == raw, 'Temporary DEX differs from pinned APK entry')
            print('Recreating complete classes5 records: ' + label, flush=True)
            run = subprocess.run([str(args.dexdump.resolve()), '-d', str(source)], capture_output=True)
            require(run.returncode == 0 and not run.stderr.strip(), 'dexdump regeneration failed or emitted diagnostics')
            parsed = symbolic.parse_dump(symbolic.decode_dump(run.stdout), raw)
            require(parsed['dexSha256'] == sha(raw), 'Parser source DEX binding differs')
            content = canonical_inventory(parsed)
            classes_sha = verify_content(content['classes'], CANONICAL_PINS[label]['classes'], label + ' complete classes')
            content_sha = verify_content(content, CANONICAL_PINS[label]['semanticInventory'], label + ' complete semantic inventory')
            require(len(content['classes']) == 8445 and not defined_classes.intersection(content['classes']), 'Unexpected or duplicated classes5 definitions')
            provenance['dexdumpRuns'].append({'artifact': label, 'actualTemporaryDexPath': str(source),
                'temporaryDexSha256': sha(raw), 'actualRawDumpSha256': sha(run.stdout),
                'parserDecodedDumpSha256': parsed['dumpSha256'], 'realInputPathOccursInPreamble': str(source).encode() in run.stdout.split(b'Class #0', 1)[0]})
            require(provenance['dexdumpRuns'][-1]['realInputPathOccursInPreamble'], 'DEX dump does not name the actual temporary input')
            inventories.append(content)
            rederived.append({'artifact': label, 'apkSha256': APKS[side][1], 'entry': 'classes5.dex',
                'entrySha256': sha(raw), 'classes': len(content['classes']), 'classesContentSha256': classes_sha,
                'semanticInventoryContentSha256': content_sha, 'allClassMethodMetadataAndInstructionsRecreated': True,
                'extractorHelperSha256': SYMBOLIC_HELPER_SHA, 'dexdumpExecutableSha256': DEXDUMP_SHA})
            print('Verified complete classes5 records: ' + label, flush=True)
            del parsed, run
            binary = symbolic.Dex(raw)
            require(not [h for h in binary.handles if SHIM_NAME in stable(h) or LM in stable(h)], 'Unexpected method-handle access to trampoline or lambda')
            require([m for m in binary.methods if m[1] == SHIM_NAME] == [[(RT, SHIM_NAME, '(' + PD + ')V')], []][side], 'Unexpected raw DEX shim method IDs')
            code = binary.code[(DB, VALIDATE)]; size = binary.u32(code + 12); payload_pc = size - 26
            require(payload_pc % 2 == 0 and binary.u16(code + 16 + 2 * payload_pc) == 0x0200 and
                    binary.u16(code + 18 + 2 * payload_pc) == 6, 'Sparse-switch payload is not aligned exact six-key data')
            if side == 0:
                require(binary.u16(code + 16 + 2 * (payload_pc - 1)) == 0 and
                        binary.u16(code + 16 + 2 * (payload_pc - 2)) == 0x0027, 'Delivered spacer is not raw 0000 after terminal throw v0')
            else:
                require(binary.u16(code + 16 + 2 * (payload_pc - 1)) == 0x0027, 'Rebuilt payload does not follow terminal throw directly')
            alignment.append({'methodCodeOffset': code, 'codeUnits': size, 'payloadPcInCodeUnits': payload_pc,
                'payloadByteOffsetAlignedToFour': (code + 16 + 2 * payload_pc) % 4 == 0,
                'paddingNopBeforePayload': side == 0, 'terminalThrowV0BeforePaddingOrPayload': True})
    old, new = inventories
    delta = symbolic.compare(old, new, allow_ordinals=False)
    delta_sha = verify_content(delta, CANONICAL_PINS['delivered-vs-reconstructed-v79-host-classes5.semantic.json'], 'full raw no-ordinal comparator delta')
    return old, new, delta, {
        'apks': reports, 'completeZipEntryDeltaRecomputed': True, 'fullZipEntryDelta': entry_delta,
        'fourUnchangedDexesByteIdenticalAndExcludeShimOrLambda': unchanged_dex_scan,
        'wholeHostClassCountEach': sum(x['classCount'] for x in unchanged_dex_scan) + 8445,
        'classes5InventoriesRederivedFromActualApkBytes': rederived,
        'dexdumpExecutableSha256': DEXDUMP_SHA, 'rawValidatePayloadAlignment': alignment,
        'classes5NoShimOrLambdaMethodHandles': True, 'fullRawComparatorDeltaSha256': delta_sha,
        'limits': 'Other ZIP payload deltas are reproduced and remain separate review scope: template/hash, profile, ABI additions and removed v1 signature metadata. This script grants no approval to those changes.'}, provenance


def check_raw_report(delta, old, new, actual):
    require(delta['completeBinaryMetadata'] is True and delta['equivalent'] is False and delta['passedCompleteSemanticComparison'] is False,
            'Raw generic comparator no longer reports the expected difference')
    require(delta['summary'] == {'addedClasses': 0, 'removedClasses': 0, 'changedClasses': 3, 'changedMethods': 2,
        'addedMethodsInExistingClasses': 0, 'removedMethodsInExistingClasses': 1, 'ordinalMappings': 0, 'rejectedOrdinalMappings': 0}, 'Generic report summary differs')
    require(delta['addedClasses'] == delta['removedClasses'] == {} and delta['ordinalMappings'] == delta['ordinalMappingRejections'] == []
        and delta['unchangedClasses'] == 8442 and delta['rawClassDeltaCounts'] == {'added':0, 'changed':3, 'removed':0}, 'Generic report scope differs')
    require([x['class'] for x in delta['changedClasses']] == [x['class'] for x in actual], 'Generic changed class list differs')
    for item, observed in zip(delta['changedClasses'], actual):
        c = item['class']
        require(item['metadataChanges'] == {} and item['addedMethods'] == [], 'Unexpected metadata or added method in raw delta')
        require([x['method'] for x in item['changedMethods']] == observed['changed'] and [x['method'] for x in item['removedMethods']] == observed['removed'], 'Generic method list differs')
        for method in item['changedMethods']:
            require(method['before'] == old[c]['methods'][method['method']] and method['after'] == new[c]['methods'][method['method']], 'Raw method delta not bound to inventories')
        for method in item['removedMethods']:
            require(method['before'] == old[c]['methods'][method['method']], 'Raw removed method not bound to inventory')


def controls(old, new):
    oldmethod, newmethod = old[DB]['methods'][VALIDATE], new[DB]['methods'][VALIDATE]
    positives = []
    for name, left, right in [('delivered graph reflexivity', oldmethod, oldmethod), ('reconstructed graph reflexivity', newmethod, newmethod),
                             ('actual cross-artifact graph bijection', oldmethod, newmethod)]:
        a,b,f,r,e = effect_bijection(left['body'],right['body'])
        require(len(f) == 251, 'Positive control lost reachable effects')
        positives.append({'case':name, 'passed':True, 'effectNodes':len(f)})
    negatives = []
    def rejected(name, action):
        try: action()
        except ValueError as error: negatives.append({'case':name,'rejected':True,'reason':str(error)})
        else: raise ValueError('Negative control accepted: ' + name)
    def instruction(n, value):
        m=copy.deepcopy(newmethod);m['body']['instructions'][n]=value;return m
    for name,n,value in [
        ('shared tail creates nonempty argument list',248,'const/4 v6, #int 1 // #1'),
        ('unknown method returns successfully',30,'goto/16 I251'),
        ('close hash collision guard inverted',169,'if-nez v0, I252'),
        ('oversized input accepted',20,'if-gt v13, v14, I243'),
        ('migration version mismatch accepted',47,'if-ne v5, v0, I243'),
        ('invalid transact kind accepted',220,'if-eqz v3, I243'),
        ('empty insert values accepted',236,'if-eqz v2, I243'),
        ('migration loop exits early',94,'goto/16 I243'),
        ('close bypasses shared zero initializer',170,'goto/16 I249'),
        ('cancel runs transact case',175,'goto/16 I176'),
        ('shared exact-argument call removed',250,'return-void'),
        ('unconditional goto cycle',170,'goto I170'),
    ]: rejected(name, lambda n=n,value=value:prove_validate(oldmethod,instruction(n,value)))
    m=copy.deepcopy(newmethod);m['body']['instructions'][261]['payload'][0]['cases'][2][1]='I166'
    rejected('cancel switch redirects to close',lambda:prove_validate(oldmethod,m))
    m2=copy.deepcopy(newmethod);m2['body']['instructions'][261]['payload'][0]['cases'].append([123,'I244'])
    rejected('extra switch key silently accepted',lambda:prove_validate(oldmethod,m2))
    m3=copy.deepcopy(newmethod);m3['body']['catches'][1]['end']='I257'
    rejected('JSONException coverage shortened',lambda:prove_validate(oldmethod,m3))
    m4=copy.deepcopy(newmethod);m4['body']['catches'][1]['handlers'][0][0]='Ljava/lang/Exception;'
    rejected('handler type broadened',lambda:prove_validate(oldmethod,m4))
    m5=copy.deepcopy(oldmethod);m5['body']['instructions'][262]='return-void'
    rejected('removed unreachable instruction is not alignment nop',lambda:prove_validate(m5,newmethod))
    def altered(classes, owner, edit):
        result=dict(classes);result[owner]=copy.deepcopy(classes[owner]);edit(result[owner]);return result
    a=altered(old,RT,lambda c:c['methods'][SHIM]['body']['instructions'].insert(0,'const/4 v0, #int 0 // #0'))
    rejected('trampoline mutates captured receiver',lambda:prove_trampoline(a,new))
    b=altered(new,LM,lambda c:c['methods']['run()V']['body']['instructions'].insert(2,CLOSE_CALL))
    rejected('callback closes twice',lambda:prove_trampoline(old,b))
    b2=altered(new,LM,lambda c:c['fields']['f$0:'+PD].update(access=0x1001))
    rejected('capture loses final field contract',lambda:prove_trampoline(old,b2))
    b3=altered(new,RT,lambda c:c['methods']['retireDatabase()V']['body']['instructions'].__setitem__(9,'invoke-direct {v2, v1}, '+LM+'.<init>:('+PD+')V'))
    rejected('constructor receives another capture',lambda:prove_trampoline(old,b3))
    b4=altered(new,RT,lambda c:c['methods']['<clinit>()V']['body']['instructions'].insert(0,'const/4 v0, #int 0 // #0'))
    rejected('FactoryRuntime initializer changed',lambda:prove_trampoline(old,b4))
    b5=altered(new,PD,lambda c:c['methods']['close()V']['body']['instructions'].__setitem__(0,'nop // spacer'))
    rejected('PrivateDatabase close loses locking',lambda:prove_trampoline(old,b5))
    a2=altered(old,RT,lambda c:c['methods']['retireDatabase()V']['body']['instructions'].append(SHIM_CALL))
    rejected('second compiled shim caller introduced',lambda:prove_trampoline(a2,new))
    b6=altered(new,RT,lambda c:c['methods']['retireDatabase()V']['body']['instructions'].append('new-instance v2, '+LM))
    rejected('second lambda allocation introduced',lambda:prove_trampoline(old,b6))
    binit=altered(new,'Lcom/jarvys/factory/runtime/PreviewProfiles;',lambda c:c['methods']['<init>()V']['body']['instructions'].insert(1,'new-instance v0, '+RT))
    rejected('initializer helper constructs owner reentrantly',lambda:prove_trampoline(old,binit))
    b7=altered(new,DB,lambda c:c.update(access=c['access']^0x10))
    rejected('unreviewed changed class metadata',lambda:inventory_delta(old,b7))
    b8=altered(new,PD,lambda c:c['methods']['close()V']['body']['instructions'].__setitem__(0,'nop // spacer'))
    rejected('unreviewed fourth changed class',lambda:inventory_delta(old,b8))
    substituted=altered(old,DB,lambda c:c.update(access=c['access']^1))
    rejected('inventory pin does not allow substituted input',lambda:verify_content(substituted,CANONICAL_PINS['delivered']['classes'],'delivered complete classes'))
    return {'positive':positives,'negative':negatives,'negativeCount':len(negatives)}


def reproducibility_tamper_controls(old, new, args):
    results = []
    def rejected(name, action):
        try: action()
        except ValueError as error: results.append({'case': name, 'rejected': True, 'reason': str(error)})
        else: raise ValueError('Tamper control accepted: ' + name)
    for name, path, expected in [
        ('tampered delivered APK bytes', args.before_apk, APKS[0][1]),
        ('tampered reconstructed APK bytes', args.after_apk, APKS[1][1]),
        ('tampered symbolic helper', args.symbolic_helper, SYMBOLIC_HELPER_SHA),
        ('tampered graph lineage source', args.graph_source, GRAPH_SOURCE_SHA),
        ('tampered dexdump executable', args.dexdump, DEXDUMP_SHA),
    ]:
        raw = path.read_bytes(); changed = bytes([raw[0] ^ 1]) + raw[1:]
        rejected(name, lambda changed=changed, expected=expected, name=name: verify_bytes(changed, expected, name))
    with zipfile.ZipFile(args.before_apk) as archive: raw = archive.read('classes5.dex')
    rejected('tampered classes5 DEX bytes', lambda: verify_bytes(raw[:-1] + bytes([raw[-1] ^ 1]), DEX_PINS['classes5.dex'][0], 'classes5.dex'))
    fixture = {'format': old['format'], 'completeBinaryMetadata': True, 'limits': old['limits'],
               'classes': {}, 'dumpSha256': '0' * 64, 'dexSha256': '0' * 64}
    changed = dict(fixture); changed['completeBinaryMetadata'] = False
    rejected('incomplete binary metadata', lambda: canonical_inventory(changed))
    changed2 = dict(fixture); changed2['unexpectedParserData'] = {'mustNotBeDropped': True}
    rejected('unrecognized parser field is not discarded', lambda: canonical_inventory(changed2))
    # Entire class dictionaries remain part of the pin, including fields, methods,
    # instruction payloads and annotations. Test a lost method and added metadata.
    changed_classes = dict(old['classes']); changed_classes[DB] = copy.deepcopy(old['classes'][DB])
    del changed_classes[DB]['methods'][VALIDATE]
    rejected('omitted complete method record', lambda: verify_content(changed_classes, CANONICAL_PINS['delivered']['classes'], 'delivered complete classes'))
    changed_classes2 = dict(old['classes']); changed_classes2[DB] = copy.deepcopy(old['classes'][DB])
    changed_classes2[DB]['unexpectedMetadata'] = {'mustNotBeDropped': True}
    rejected('new class metadata is not discarded', lambda: verify_content(changed_classes2, CANONICAL_PINS['delivered']['classes'], 'delivered complete classes'))
    return results


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before-apk', type=pathlib.Path, default=APKS[0][0])
    parser.add_argument('--after-apk', type=pathlib.Path, default=APKS[1][0])
    parser.add_argument('--symbolic-helper', type=pathlib.Path, default=O/'symbolic_dex.py')
    parser.add_argument('--graph-source', type=pathlib.Path, default=O/'reconcile_delivered_v79_template.py')
    parser.add_argument('--dexdump', type=pathlib.Path, default=R/'toolchain/android-sdk/build-tools/35.0.0/dexdump')
    parser.add_argument('--temp-root', type=pathlib.Path, default=pathlib.Path(tempfile.gettempdir()))
    parser.add_argument('--output', required=True, type=pathlib.Path, help='Deterministic semantic proof output; no historical JSON inputs required')
    parser.add_argument('--provenance-output', required=True, type=pathlib.Path, help='Actual runtime paths and raw dump hashes, excluded from semantic digest')
    return parser.parse_args()


def main():
    args = parse_args(); proof_sha = sha(pathlib.Path(__file__).read_bytes())
    # Refuse to overwrite any source/binary input or to conflate semantic evidence
    # with path-dependent runtime provenance.
    input_paths = {p.resolve() for p in (args.before_apk,args.after_apk,args.symbolic_helper,args.graph_source,args.dexdump,pathlib.Path(__file__))}
    require(args.output.resolve() != args.provenance_output.resolve(), 'Semantic and provenance outputs must be distinct')
    require(args.output.resolve() not in input_paths and args.provenance_output.resolve() not in input_paths, 'Output would overwrite input')
    old,new,delta,bindings,provenance = load_and_bind(args)
    raw = inventory_delta(old['classes'],new['classes'])
    check_raw_report(delta,old['classes'],new['classes'],raw)
    validate = prove_validate(old['classes'][DB]['methods'][VALIDATE],new['classes'][DB]['methods'][VALIDATE])
    trampoline = prove_trampoline(old['classes'],new['classes'])
    core = {'rawDelta':raw,'databaseValidateProof':validate,'closeTrampolineProof':trampoline}
    core_sha = verify_content(core,CANONICAL_PINS['reviewedProofCore'],'original reviewed complete proof core')
    calibrations = controls(old['classes'],new['classes'])
    tamper = reproducibility_tamper_controls(old,new,args)
    require(len(calibrations['positive']) == 3 and calibrations['negativeCount'] == 29 and len(tamper) == 10, 'Control inventory changed')
    require(sha(pathlib.Path(__file__).read_bytes()) == proof_sha, 'Proof script changed during execution')
    for apk, expected in ((args.before_apk,APKS[0][1]),(args.after_apk,APKS[1][1])): pinned(apk,expected)
    pinned(args.symbolic_helper,SYMBOLIC_HELPER_SHA); pinned(args.graph_source,GRAPH_SOURCE_SHA); pinned(args.dexdump,DEXDUMP_SHA)
    result = {'format':'exact-v79-host-canonical-bounded-reconciliation-v2','passedBoundedReconciliation':True,
        'finalGateApproved':False,'genericComparatorEquivalent':False,'genericComparatorModified':False,
        'canonicalInventoryAndDeltaPins':CANONICAL_PINS,'symbolicHelperSha256':SYMBOLIC_HELPER_SHA,'copiedGraphSourceSha256':GRAPH_SOURCE_SHA,
        'reviewedOriginalProofSourceSha256':REVIEWED_PROOF_SOURCE_SHA,'proofScriptSha256':proof_sha,'artifactBindings':bindings,
        'classes5ClassCountEach':8445,'exactlyUnchangedClasses5Classes':8442,**core,'reviewedProofCoreSha256':core_sha,
        'fullRawComparatorDelta':delta,'controls':calibrations,'reproducibilityTamperControls':tamper,
        'canonicalizationContract': 'Complete classes dictionaries and all method/field/annotation/instruction/payload/catch data are retained without rewriting. Format, binary completeness and limits are also pinned. The full no-ordinal comparator delta is recomputed and pinned. Only path-dependent extractor provenance (input paths and raw dump hashes) is kept in the separate truthful runtime report. No prior JSON, log, report or markdown is an input.',
        'scope':'Exact pinned delivered-v79/reconstructed-v79 HOST APK pair. Existing effect/edge and close-trampoline proofs are preserved byte-for-byte in source and reproduce the original complete proof-core digest. No new equivalence exemption, reflection/stacktrace identity, whole-APK equivalence, profile/ABI/signature approval, Android execution, build/lint/release approval or production/helper edits.'}
    semantic_bytes = (json.dumps(result,indent=2,sort_keys=True)+'\n').encode()
    provenance['semanticResultSha256'] = sha(semantic_bytes)
    provenance['proofScriptSha256'] = proof_sha
    for path in (args.output,args.provenance_output): path.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_bytes(semantic_bytes)
    args.provenance_output.write_text(json.dumps(provenance,indent=2,sort_keys=True)+'\n')
    print(json.dumps({'output':str(args.output),'semanticResultSha256':sha(semantic_bytes),'passedBoundedReconciliation':True,
        'positiveControlsPassed':3,'negativeControlsPassed':29,'additionalReproducibilityTamperControlsPassed':10,
        'reviewedProofCoreSha256':core_sha,'finalGateApproved':False}))

if __name__ == '__main__': main()
