#!/usr/bin/env python3
"""Bounded proof for one exact delivered-v79/rebuilt-v79 template pair.

Does not modify or relax symbolic_dex.py. Not a general compiler-noise allowlist,
APK equality result, release gate, or proof of reflective/diagnostic identity.
"""
import argparse
import collections
import copy
import hashlib
import json
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
INPUTS = {
    'delivered-v79-template.symbolic.json': '41abdeadaa35f7efd0dcf684c8977ad9c4ba30e0cbc0794021949edb829436bf',
    'reconstructed-v79-template.symbolic.json': 'c6c00f400e9b45b01fea502d17e7f1dffab19371818c9818719a6670d592b30f',
    'delivered-vs-reconstructed-v79-template.semantic.json': 'f3044dde137eaa70711fc4ecbfc28f7a9d253ab422ac2a5095f747c530acafe9',
}
DB = 'Lcom/jarvys/factory/runtime/DatabaseRequest;'
RT = 'Lcom/jarvys/factory/runtime/FactoryRuntime;'
LM = 'Lcom/jarvys/factory/runtime/FactoryRuntime$$ExternalSyntheticLambda32;'
PD = 'Lcom/jarvys/factory/runtime/PrivateDatabase;'
VALIDATE = 'validate(Ljava/lang/String;Lorg/json/JSONObject;)V'
SHIM_NAME = '$r8$lambda$wPa24sCbFlYMF0HLDVXLMGD26OM'
SHIM = SHIM_NAME + '(' + PD + ')V'
SHIM_CALL = 'invoke-static {v0}, ' + RT + '.' + SHIM_NAME + ':(' + PD + ')V'
CLOSE_CALL = 'invoke-virtual {v0}, ' + PD + '.close:()V'
EXPECTED_CATCHES = [
    {'start': 'I13', 'end': 'I23', 'handlers': [['Lorg/json/JSONException;', 'I299']]},
    {'start': 'I35', 'end': 'I299', 'handlers': [['Lorg/json/JSONException;', 'I299']]},
]
CASE_TARGETS = {
    'database.info': (-1376304831, 285, 285),
    'database.transact': (-1276641283, 199, 199),
    'database.cancel': (37433933, 194, 193),
    'database.close': (278631365, 188, 188),
    'database.select': (499136719, 120, 120),
    'database.migrate': (1668566072, 35, 35),
}


def require(ok, message):
    if not ok: raise ValueError(message)


def sha(data): return hashlib.sha256(data).hexdigest()
def stable(data): return json.dumps(data, sort_keys=True, separators=(',', ':'))
def digest(data): return sha(stable(data).encode())
def index(label):
    require(re.fullmatch(r'I[0-9]+', label) is not None, 'Unexpected instruction label: ' + label)
    return int(label[1:])


def java_hash(text):
    value = 0
    for c in text: value = ((value * 31) + ord(c)) & 0xffffffff
    return value - 0x100000000 if value & 0x80000000 else value


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


def prove_validate(before, after):
    require({k: v for k, v in before.items() if k != 'body'} == {k: v for k, v in after.items() if k != 'body'}, 'validate method metadata differs')
    for body in (before['body'], after['body']):
        require({k: body[k] for k in ('registers', 'ins', 'outs')} == {'registers': 25, 'ins': 2, 'outs': 4}, 'Unexpected validate register contract')
        require(body['catches'] == EXPECTED_CATCHES, 'validate catch coverage differs')
        require(len(body['instructions']) == 304, 'Unexpected validate instruction count')
    a, b = Graph(before['body']), Graph(after['body']); pending = [(0, 0)]; forward = {}; reverse = {}; evidence = []
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
    for graph, mapped in ((a, forward), (b, reverse)):
        payloads = {i for i, value in enumerate(graph.instructions) if isinstance(value, dict)}
        gotos = {i for i in range(len(graph.instructions)) if graph.jump(i)}
        remaining = set(range(len(graph.instructions))) - set(mapped) - gotos - payloads
        require(remaining == {302} and graph.instructions[302] == 'nop // spacer', 'Unreviewed unreachable instructions')
        require(payloads == graph.used_payloads == {303}, 'An unreviewed or unused payload remains')
        require(gotos == graph.used_gotos, 'An unreviewed/unreachable goto remains')
    changed = [i for i, (x, y) in enumerate(zip(a.instructions, b.instructions)) if x != y]
    require(changed == [192, 193, 194, 195, 196, 197, 198, 289, 303], 'Delta exceeds the exact reviewed layout')
    cases = []
    for name, (key, old_target, new_target) in CASE_TARGETS.items():
        require(java_hash(name) == key, 'Wrong expected Java string hash')
        for graph, target in ((a, old_target), (b, new_target)):
            payload = graph.instructions[303]['payload']; require(len(payload) == 1 and payload[0]['from'] == 'I33', 'Unexpected switch user')
            require([key, 'I%d' % target] in payload[0]['cases'], 'Missing exact hash case')
            require(graph.instructions[target].endswith(', ' + json.dumps(name)), 'Case label string differs')
            require(graph.instructions[target + 1].endswith('Ljava/lang/String;.equals:(Ljava/lang/Object;)Z') and
                    graph.instructions[target + 2] == 'move-result v0' and
                    graph.instructions[target + 3] == 'if-eqz v0, I293', 'Case lacks exact equality guard and shared rejection')
        cases.append({'name': name, 'javaHashCode': key, 'beforeTarget': 'I%d' % old_target, 'afterTarget': 'I%d' % new_target,
                      'hashCollisionOrUnequalNameRejectsAt': 'I293'})
    require(len(a.instructions[303]['payload'][0]['cases']) == len(b.instructions[303]['payload'][0]['cases']) == 6, 'Extra switch case')
    # All explicit validator rejection sinks and normal exits must be in the bijection.
    rejects = [111, 114, 117, 173, 256, 281, 293, 296, 299]
    for at in rejects:
        require(forward[at] == at and a.instructions[at:at + 3] == b.instructions[at:at + 3] == [
            'invoke-static {}, ' + DB + '.invalid:()Lcom/jarvys/factory/runtime/FactoryException;',
            'move-result-object v0', 'throw v0'], 'Rejection sink changed')
    for at in (187, 284, 292): require(forward[at] == at and a.instructions[at] == 'return-void', 'Normal exit changed')
    return {'passed': True, 'method': DB + '->' + VALIDATE, 'beforeMethodSha256': digest(before), 'afterMethodSha256': digest(after),
            'instructionCountEach': 304, 'effectNodeBijectionCount': len(forward), 'gotoCounts': [len(a.used_gotos), len(b.used_gotos)],
            'allSwitchesAccountedFor': True, 'allHandlersAccountedFor': True, 'unreachableIdenticalSpacer': 'I302',
            'exactChangedInstructionIndices': changed, 'switchCases': sorted(cases, key=lambda x: x['javaHashCode']),
            'defaultRejectsAt': 'I293', 'invalidArgumentSinkStarts': ['I%d' % i for i in rejects], 'normalReturns': ['I187', 'I284', 'I292'],
            'catchTable': EXPECTED_CATCHES, 'movedEffectNodes': [{'before': 'I%d' % k, 'after': 'I%d' % v} for k, v in sorted(forward.items()) if k != v],
            'fullEffectAndEdgeCorrespondence': sorted(evidence, key=lambda x: index(x['before']))}


def executable_mentions(classes, fragment):
    out = []
    for owner, cls in classes.items():
        for name, method in cls['methods'].items():
            if method['body'] is None: continue
            for i, instruction in enumerate(method['body']['instructions']):
                if isinstance(instruction, str) and fragment in instruction:
                    out.append({'class': owner, 'method': name, 'instruction': i, 'text': instruction})
    return out


def prove_trampoline(before, after):
    old, new = before[RT]['methods'], after[RT]['methods']; shim = old[SHIM]
    require(SHIM not in new and shim == {'access': 0x1009, 'group': 'direct', 'prototype': '(' + PD + ')V',
        'body': {'catches': [], 'registers': 1, 'ins': 1, 'outs': 1, 'instructions': [CLOSE_CALL, 'return-void']}}, 'Removed method is not the exact stateless synthetic trampoline')
    oldrun, newrun = before[LM]['methods']['run()V'], after[LM]['methods']['run()V']
    expected_run = {'access': 0x11, 'group': 'virtual', 'prototype': '()V', 'body': {'registers': 2, 'ins': 1, 'outs': 1, 'catches': [],
        'instructions': ['iget-object v0, v1, ' + LM + '.f$0:' + PD, SHIM_CALL, 'return-void']}}
    require(oldrun == expected_run, 'Unexpected old lambda body')
    expected_new = copy.deepcopy(expected_run); expected_new['body']['instructions'][1] = CLOSE_CALL
    require(newrun == expected_new, 'Direct lambda differs from exact trampoline expansion')
    refs = executable_mentions(before, SHIM_NAME)
    require(refs == [{'class': LM, 'method': 'run()V', 'instruction': 1, 'text': SHIM_CALL}], 'Other compiled trampoline callers remain')
    require(not executable_mentions(after, SHIM_NAME), 'Rebuilt instructions retain trampoline reference')
    require(stable(before).count(SHIM_NAME) == 2 and SHIM_NAME not in stable(after), 'Unexpected noninstruction or reflective-name reference to trampoline')
    constructors = executable_mentions(before, 'new-instance v2, ' + LM)
    all_allocations = [row for row in executable_mentions(before, LM) if row['text'].startswith('new-instance ')]
    expected_alloc = [{'class': RT, 'method': 'retireDatabase()V', 'instruction': 8, 'text': 'new-instance v2, ' + LM}]
    require(all_allocations == constructors == expected_alloc, 'Lambda allocation is not exclusively the existing instance path')
    require([row for row in executable_mentions(after, LM) if row['text'].startswith('new-instance ')] == expected_alloc, 'Lambda allocation changed')
    retire = old['retireDatabase()V']; require(retire == new['retireDatabase()V'] and retire['access'] == 2, 'Retirement is not unchanged private instance code')
    require(retire['body']['instructions'][7] == 'invoke-static {v0}, Ljava/util/Objects;.requireNonNull:(Ljava/lang/Object;)Ljava/lang/Object;' and
            retire['body']['instructions'][9] == 'invoke-direct {v2, v0}, ' + LM + '.<init>:(' + PD + ')V', 'Capture construction/null guarantee changed')
    require(old['<clinit>()V'] == new['<clinit>()V'], 'FactoryRuntime class initializer changed')
    require(before[PD] == after[PD], 'PrivateDatabase implementation/metadata changed')
    close = before[PD]['methods']['close()V']
    require(close['access'] == 0x20001 and close['body']['instructions'][0] == 'monitor-enter v1', 'Unexpected close synchronization')
    require(before[LM]['methods']['<init>(' + PD + ')V'] == after[LM]['methods']['<init>(' + PD + ')V'], 'Captured receiver constructor differs')
    return {'passedForCompiledApplicationPath': True, 'removedMethod': RT + '->' + SHIM, 'removedMethodSha256': digest(shim),
            'lambdaRunBeforeSha256': digest(oldrun), 'lambdaRunAfterSha256': digest(newrun),
            'exactInlineExpansion': expected_new, 'onlyCompiledTrampolineCaller': refs,
            'onlyCompiledLambdaAllocation': all_allocations, 'retireDatabaseMethodSha256': digest(retire),
            'classInitializationArgument': 'The only compiled new-instance of Lambda32 is inside private instance FactoryRuntime.retireDatabase(); FactoryRuntime initialization has already occurred before this path. Its unchanged <clinit> side effects are not lost by removing the later static trampoline call.',
            'factoryRuntimeClinitSha256': digest(old['<clinit>()V']), 'privateDatabaseCloseSha256': digest(close),
            'closeBodyIncludingCatchAllMonitorRelease': close,
            'preserved': ['The same final captured PrivateDatabase receiver reaches one invoke-virtual close().',
                          'The shim has no local effect, branch, catch handler, extra argument, synchronization or result conversion.',
                          'PrivateDatabase.close and its explicit monitor-enter/exit and catch-all cleanup are exact unchanged records.',
                          'Null-check/cancellation/executor scheduling/RejectedExecutionException queue fallback in retireDatabase are unchanged.',
                          'Normal void completion and propagated exception object/type/side effects are unchanged on the compiled path.'],
            'limits': ['Removed public synthetic method membership is observably different to arbitrary reflection or external direct callers.',
                       'Exception stack traces, profiler/instrumentation frames, code layout/debug positions and instruction timing may differ.',
                       'This is bounded application-path behavioral equivalence, not universal VM-observable equivalence.']}


def inventory_delta(a, b):
    require(set(a) == set(b), 'Class additions/removals are outside this reconciliation')
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
    ], 'Observed delta differs from the exact three-class reviewed pair')
    return changed


def negative_controls(oldmethod, newmethod, oldclasses, newclasses):
    results = []
    def rejected(name, action):
        try: action()
        except ValueError as error: results.append({'case': name, 'rejected': True, 'reason': str(error)})
        else: raise ValueError('Negative control accepted: ' + name)
    def edit_instruction(n, value):
        copy_method = copy.deepcopy(newmethod); copy_method['body']['instructions'][n] = value
        return copy_method
    rejected('nonempty shared tail', lambda: prove_validate(oldmethod, edit_instruction(197, 'const/4 v10, #int 1 // #1')))
    rejected('unknown methods accepted', lambda: prove_validate(oldmethod, edit_instruction(34, 'goto/16 I292')))
    rejected('close hash-collision check inverted', lambda: prove_validate(oldmethod, edit_instruction(191, 'if-nez v0, I293')))
    rejected('too-large input no longer rejected', lambda: prove_validate(oldmethod, edit_instruction(20, 'if-gt v13, v14, I284')))
    rejected('migration version mismatch accepted', lambda: prove_validate(oldmethod, edit_instruction(53, 'if-ne v6, v0, I284')))
    rejected('invalid transact kind accepted', lambda: prove_validate(oldmethod, edit_instruction(254, 'if-eqz v2, I284')))
    altered = copy.deepcopy(newmethod); altered['body']['instructions'][303]['payload'][0]['cases'][2][1] = 'I188'
    rejected('cancel switch redirects to close', lambda: prove_validate(oldmethod, altered))
    altered_catch = copy.deepcopy(newmethod); altered_catch['body']['catches'][1]['end'] = 'I298'
    rejected('JSONException coverage shortened', lambda: prove_validate(oldmethod, altered_catch))
    altered_spacer = edit_instruction(302, 'return-void')
    rejected('unreachable data silently omitted', lambda: prove_validate(oldmethod, altered_spacer))
    a = dict(oldclasses); a[RT] = copy.deepcopy(oldclasses[RT]); a[RT]['methods'][SHIM]['body']['instructions'].insert(0, 'const/4 v0, #int 0 // #0')
    rejected('trampoline mutates receiver', lambda: prove_trampoline(a, newclasses))
    b = dict(newclasses); b[LM] = copy.deepcopy(newclasses[LM]); b[LM]['methods']['run()V']['body']['instructions'].insert(2, CLOSE_CALL)
    rejected('callback closes twice', lambda: prove_trampoline(oldclasses, b))
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--directory', type=pathlib.Path, default=ROOT); args = parser.parse_args(); directory = args.directory.resolve()
    loaded = {}
    for name, expected in INPUTS.items():
        raw = (directory / name).read_bytes(); require(sha(raw) == expected, 'Pinned input digest differs: ' + name); loaded[name] = json.loads(raw)
    old = loaded['delivered-v79-template.symbolic.json']; new = loaded['reconstructed-v79-template.symbolic.json']; delta = loaded['delivered-vs-reconstructed-v79-template.semantic.json']
    for item in (old, new):
        require(item['completeBinaryMetadata'] is True and item['format'] == 'independent-symbolic-dex-v1', 'Incomplete symbolic input')
        apk = pathlib.Path(item['source']); require(apk.is_file() and sha(apk.read_bytes()) == item['sourceSha256'], 'Inventory no longer binds its source APK')
        with zipfile.ZipFile(apk) as archive:
            require(archive.testzip() is None and len(archive.namelist()) == len(set(archive.namelist())), 'Invalid source APK ZIP')
            dexes = {n: sha(archive.read(n)) for n in archive.namelist() if n.lower().endswith('.dex')}
            require(dexes == {n: row['sha256'] for n, row in item['dexFiles'].items()}, 'DEX hashes no longer bind source APK')
    require(delta['equivalent'] is False and delta['summary'] == {'addedClasses': 0, 'removedClasses': 0, 'changedClasses': 3, 'changedMethods': 2,
            'addedMethodsInExistingClasses': 0, 'removedMethodsInExistingClasses': 1, 'ordinalMappings': 0}, 'Generic report changed')
    raw_delta = inventory_delta(old['classes'], new['classes'])
    validate = prove_validate(old['classes'][DB]['methods'][VALIDATE], new['classes'][DB]['methods'][VALIDATE])
    trampoline = prove_trampoline(old['classes'], new['classes'])
    controls = negative_controls(old['classes'][DB]['methods'][VALIDATE], new['classes'][DB]['methods'][VALIDATE], old['classes'], new['classes'])
    result = {'format': 'exact-v79-template-semantic-reconciliation-v1', 'passedBoundedReconciliation': True,
              'finalGateApproved': False, 'genericComparatorEquivalent': False, 'genericComparatorModified': False,
              'inputFileSha256': INPUTS, 'artifactBindings': {'deliveredApkSha256': old['sourceSha256'], 'reconstructedApkSha256': new['sourceSha256'],
                    'deliveredDex': old['dexFiles'], 'reconstructedDex': new['dexFiles'], 'currentApkAndDexBytesMatchInventories': True},
              'classesEach': len(old['classes']), 'exactlyUnchangedClasses': len(old['classes']) - 3, 'rawDelta': raw_delta,
              'databaseValidateProof': validate, 'closeTrampolineProof': trampoline, 'negativeControls': controls,
              'scope': 'Preparatory binding of this exact delivered-v79/reconstructed-v79 template pair. All six database cases, normal/rejection edges, loops and typed catches checked. No arbitrary reflection/stacktrace identity claim, no APK byte identity, no profile approval, no Android execution, no lint/test/build/release gate approval.'}
    output = directory / 'delivered-v79-template-bounded-reconciliation.json'; output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'output': str(output), 'passedBoundedReconciliation': True, 'effectNodeBijection': validate['effectNodeBijectionCount'],
                      'gotoCounts': validate['gotoCounts'], 'negativeControlsPassed': len(controls), 'finalGateApproved': False}))


if __name__ == '__main__': main()
