#!/usr/bin/env python3
"""Independent source/compositional-proof and focused fresh-JUnit audit. Never runs Gradle."""
import argparse
import collections
import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
V = OUT.parent
ROOT = V.parent
P = ROOT / 'integration'
OLD = ROOT / 'FACTORY-BIOMETRICS-v80-validation'
BASE = 'app/apk-runtime/src/test/java/com/jarvys/factory/runtime/'
REVIEWED_TESTS = {
    BASE + 'CapabilityDecisionTree.java': '428075fbac9ffc825995aa60aa71aa9f28b4241bde8f02e4932713a8f7315cff',
    BASE + 'CapabilityDecisionTreeTest.java': '1275d7440d722c456b1ccd99526651c12533a6142c45c1ca9098ef8bea027e93',
    BASE + 'BridgeProtocolTest.java': '0a72835e41301dbf6e330caf68a253de0ae377221aca314306fbe3abb18611e0',
}
GUARD_HASHES = {
    'jarvys-offline-guard.jar': '9224f15d36536ecaf14be6aaaba81650e52587b15a786762a4688e1d3a8bed9f',
    'guard-bootstrap.jar': '2b29e6ab92e06f4337047d4c8c35499030c252829d795e9f322a7058df8540e6',
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def inventory():
    tracked = subprocess.check_output(['git', 'ls-files', 'app'], cwd=P, text=True).splitlines()
    new = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '--', 'app'], cwd=P, text=True).splitlines()
    return set(tracked + new)


def source_review():
    baseline = json.loads((V / 'before-source.json').read_text())
    old_freeze = json.loads((OLD / 'source-freeze.json').read_text())
    require(baseline['files'] == old_freeze['files'], 'Pre-optimization files differ from v80 frozen input set')
    require(len(baseline['files']) == 1123, 'Unexpected frozen source count')
    require(inventory() == set(baseline['files']) | set(REVIEWED_TESTS), 'Unexpected added/deleted app input')
    for name, expected in baseline['files'].items():
        if name not in REVIEWED_TESTS:
            require(sha(P / name) == expected, 'Unreviewed input change: ' + name)
    recorded = json.loads((V / 'focused-test-source.json').read_text())
    require(set(recorded) == set(REVIEWED_TESTS), 'Focused source inventory differs')
    for name, expected in REVIEWED_TESTS.items():
        require(recorded[name]['sha256'] == expected == sha(P / name), 'Reviewed/frozen/current test bytes differ: ' + name)
        require(recorded[name]['mtimeBeforeRun'] is True, 'Launch did not record pre-run source existence')

    bridge = (P / (BASE + 'BridgeProtocolTest.java')).read_text()
    original = subprocess.check_output(['git', 'show', old_freeze['head'] + ':' + BASE + 'BridgeProtocolTest.java'], cwd=P, text=True)
    begin = '    @Test public void allCapabilitySelectionsMatchCatalogAndTypedValidators()'
    end = '    @Test public void sharingRejectsUrisPathsMimeWildcardsAndExtraAuthority()'
    a, b, c, d = original.index(begin), original.index(end), bridge.index(begin), bridge.index(end)
    require(original[:a] == bridge[:c] and original[b:] == bridge[d:], 'Another original test changed')
    fixture_end = '        assertEquals(42, calls.length);'
    require(original[a:original.index(fixture_end, a)] == bridge[c:bridge.index(fixture_end, c)], 'An original wire fixture changed')
    for statement in ['assertEquals(87, validLeaves);', 'assertEquals(42, invalidLeaves);', 'assertEquals(174, wireInvocations[0]);', 'assertEquals(1048576, checked);', 'assertEquals(88080384L, assertions);']:
        require(statement in bridge, 'Missing exhaustive accounting assertion: ' + statement)

    helper = (P / (BASE + 'CapabilityDecisionTree.java')).read_text()
    pins = re.findall(r'requireSource\(root, "([^"]+)", "([a-f0-9]{64})"\);', helper)
    require(len(pins) == len(dict(pins)) == 23, 'Source proof perimeter cardinality changed')
    expected_core = {'BridgeProtocol', 'FactoryConfig', 'StrictJson', 'FactoryException', 'BiometricAuthentication', 'SensorCapture', 'FactoryPresentation', 'DatabaseRequest', 'ExternalLaunchRequest', 'ContactPickRequest'}
    expected = {'factory-runtime-core/src/main/java/com/jarvys/factory/runtime/' + n + '.java' for n in expected_core}
    expected |= {str(f.relative_to(P / 'app')) for f in (P / 'app/factory-contract/src/main/java').rglob('*.java')}
    expected |= {'apk-runtime/build.gradle.kts', 'factory-runtime-core/build.gradle.kts', 'factory-contract/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts'}
    require(set(dict(pins)) == expected, 'Reviewed parser/helper/catalog initialization/build closure differs')
    for path, expected_sha in pins:
        require(sha(P / 'app' / path) == expected_sha, 'Proof source pin mismatch: ' + path)
    tests = {}
    for cls in ('BridgeProtocolTest', 'CapabilityDecisionTreeTest'):
        names = re.findall(r'@Test\s+public\s+void\s+(\w+)\s*\(', (P / (BASE + cls + '.java')).read_text())
        require(len(names) == len(set(names)) and names, 'Duplicate/missing source testcase: ' + cls)
        tests['com.jarvys.factory.runtime.' + cls] = sorted(names)
    require(len(tests['com.jarvys.factory.runtime.CapabilityDecisionTreeTest']) == 7, 'Helper test count changed')
    return {
        'productionAndAllOtherAppInputsUnchanged': True,
        'originalFrozenInputs': 1123,
        'currentAppInputs': len(inventory()),
        'changedOrAddedTestFiles': REVIEWED_TESTS,
        'allOriginalWireFixturesAndOtherTestBodiesPreserved': True,
        'reviewedSourcePins': dict(pins),
        'expectedTestNames': tests,
        'compositionalProof': {
            'basis': 'Independently reviewed config membership-only observation, no config escape or capability-derived hidden state, deterministic helper closure, fixed non-capability fields, frozen compiler/dependencies.',
            'enumeratedRealConfigurationsPerVariant': 1048576,
            'logicalOutcomeAssertionsPerVariant': 88080384,
            'validLeaves': 87,
            'invalidLeaves': 42,
            'realWireValidationInvocationsPerMatrix': 174,
            'coverage': 'Both answers explored at every previously unassigned supported capability read. Parsed membership replays each full configuration. Invalid fixture trees have no membership decision; invalid arguments precede authorization.',
            'limits': 'Source binding and reviewed deterministic behavior justify composition. This is not 88 million direct wire invocations, a formal JVM equivalence proof, or device evidence.',
        },
    }


def focused_review(run_dir, source):
    run_path = run_dir / 'run.json'
    run = json.loads(run_path.read_text())
    require(run.get('exit_code') == 0 and run.get('finish_epoch', 0) >= run['start_epoch'], 'Focused run incomplete or failed')
    log = Path(run['log'])
    text = log.read_text()
    require('BUILD SUCCESSFUL' in text and 'shutdown-report-write-failed' not in text, 'Focused run/guard completion not proven')
    tasks = ['testDebugUnitTest', 'testReleaseUnitTest']
    requested = [x for x in run['args'] if x.startswith(':')]
    require(requested == [':apk-runtime:' + t for t in tasks], 'Unexpected focused tasks')
    require('--offline' in run['args'] and '--info' in run['args'], 'Required gate flags absent')
    require(any(x.endswith('/fresh-tests.init.gradle') for x in run['args']), 'Fresh test harness absent')
    filters = [run['args'][i + 1] for i, arg in enumerate(run['args']) if arg == '--tests']
    require(filters == list(source['expectedTestNames']) * 2, 'Unexpected focused test filters')
    for name in REVIEWED_TESTS:
        require((P / name).stat().st_mtime <= run['start_epoch'], 'Source written after run start: ' + name)
    for name, expected in GUARD_HASHES.items():
        require(sha(ROOT / 'UX39-recovered-validation/offline-guard' / name) == expected, 'Reviewed guard artifact changed')
    suites, guards, matrices = [], [], []
    for task in tasks:
        full = ':apk-runtime:' + task
        require(('FRESH_GUARDED_TEST_TASK ' + full + ' run=' + run['label']) in text, 'Fresh marker missing: ' + full)
        directory = P / 'app/apk-runtime/build/test-results' / task
        xmls = sorted(directory.glob('TEST-*.xml'))
        require(len(xmls) == len(source['expectedTestNames']), 'Unexpected suite inventory: ' + task)
        names = set()
        for xml in xmls:
            require(run['start_epoch'] <= xml.stat().st_mtime <= run['finish_epoch'] + 1, 'Stale test XML: ' + str(xml))
            suite = ET.parse(xml).getroot()
            name = suite.get('name')
            require(name in source['expectedTestNames'] and name not in names, 'Unknown/duplicate suite')
            names.add(name)
            cases = suite.findall('testcase')
            require(sorted(t.get('name') for t in cases) == source['expectedTestNames'][name], 'Case names differ from complete class source: ' + name)
            require(int(suite.get('tests')) == len(cases), 'Case count mismatch')
            require(all(int(suite.get(k, '0')) == 0 for k in ('failures', 'errors', 'skipped')), 'Nonpassing suite: ' + name)
            require(all(not any(t.findall(k) for k in ('failure', 'error', 'skipped')) for t in cases), 'Nonpassing case: ' + name)
            target = OUT / 'focused-junit' / task / xml.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(xml, target)
            require(sha(target) == sha(xml), 'Archived JUnit copy differs')
            suites.append({'task': full, 'suite': name, 'tests': len(cases), 'timeSeconds': float(suite.get('time')), 'xmlSha256': sha(xml), 'archive': str(target)})
            for case in cases:
                if case.get('name') == 'allCapabilitySelectionsMatchCatalogAndTypedValidators':
                    matrices.append({'task': full, 'seconds': float(case.get('time'))})
        require(names == set(source['expectedTestNames']), 'Missing focused class')
        directory = run_dir / ('apk-runtime-' + task) / 'guard'
        installs = sorted(directory.glob('*.installed.json'))
        shutdowns = sorted(directory.glob('*.shutdown.json'))
        require(installs and {p.name.replace('.installed.json', '') for p in installs} == {p.name.replace('.shutdown.json', '') for p in shutdowns}, 'Unpaired worker guards: ' + task)
        for path in installs:
            end = path.with_name(path.name.replace('.installed.json', '.shutdown.json'))
            first, last = json.loads(path.read_text()), json.loads(end.read_text())
            keys = {'pid', 'jvmStartedAtEpochMillis', 'installedAtEpochMillis'}
            require(set(first) == keys and set(last) == keys | {'shutdownAtEpochMillis', 'blockedAttempts'}, 'Guard schema differs')
            require(all(type(v) is int for v in last.values()) and all(first[k] == last[k] for k in keys), 'Guard identity/type mismatch')
            require(first['jvmStartedAtEpochMillis'] <= first['installedAtEpochMillis'], 'Guard install precedes JVM')
            require(run['start_epoch'] * 1000 <= first['installedAtEpochMillis'] <= last['shutdownAtEpochMillis'] <= (run['finish_epoch'] + 1) * 1000, 'Guard interval outside run')
            require(last['blockedAttempts'] >= 0, 'Invalid blocked count')
            require(path.name == 'guard-%s-%s.installed.json' % (first['pid'], first['jvmStartedAtEpochMillis']), 'Guard filename identity mismatch')
            guards.append({'task': full, 'installedSha256': sha(path), 'shutdownSha256': sha(end), **last})
    launches = [line for line in text.splitlines() if line.startswith("Starting process 'Gradle Test Executor")]
    require(len(launches) == len(guards), 'Launched workers lack exact guard receipts')
    require(all('-javaagent:' in line and 'jarvys-offline-guard.jar' in line and '-ea ' in line for line in launches), 'Worker launch lacks reviewed guard/assertions')
    require(len(matrices) == 2, 'Both exhaustive matrices did not finish')
    return {'run': str(run_path), 'runSha256': sha(run_path), 'logSha256': sha(log), 'tests': sum(x['tests'] for x in suites), 'suites': suites, 'matrices': matrices, 'guardWorkers': len(guards), 'blockedAttempts': sum(g['blockedAttempts'] for g in guards), 'guards': guards, 'guardScope': 'Reviewed per-Test-worker HTTP(S) guard; no general OS/network/device isolation claim'}


def reuse_review():
    approval_path = OLD / 'independent/reviewer-final-three-apk-approval.json'
    approval = json.loads(approval_path.read_text())
    require(approval['approved'] is True and approval['junitPassed'] == 8776 and approval['sdkPassed'] == 41, 'Prior v80 approval unavailable')
    rows = []
    for row in approval['variants']:
        path = Path(row['apk'])
        require(sha(path) == row['sha256'] and path.stat().st_size == row['bytes'], 'Prior APK bytes changed')
        rows.append({k: row[k] for k in ('variant', 'sha256', 'bytes')})
    delivery_path = OLD / 'independent/reviewer-v80-signed-library-binding.json'
    delivery = json.loads(delivery_path.read_text())
    require(delivery['status'] == 'PASSED', 'Prior delivery binding unavailable')
    for kind in ('signed', 'library'):
        row = delivery[kind]
        require(sha(row['path']) == row['sha256'] and Path(row['path']).stat().st_size == row['bytes'], 'Prior signed/persisted bytes changed')
    require(delivery['signed']['sha256'] == delivery['library']['sha256'], 'Delivery/persistence bytes differ')
    return {'priorApprovalSha256': sha(approval_path), 'priorSourceCommit': approval['sourceCommit'], 'apkInputsUnchanged': True, 'priorApkBytesRechecked': rows, 'signedAndPersistedBytesRecheckedSha256': delivery['signed']['sha256'], 'priorDeliveryBindingSha256': sha(delivery_path), 'historicalTestCount': 8776, 'historicalSdkCount': 41, 'newFullAggregateClaim': False, 'newApkBuildOrDelivery': False, 'limits': 'Historical approval is reused only for unchanged production/build inputs and exact existing artifact bytes. New test-source validation is focused evidence; changed test sources require affected runtime lint. Physical installation/authentication remains unverified.'}


def lint_review(run_dir):
    run_path = run_dir / 'run.json'
    run = json.loads(run_path.read_text())
    require(run.get('exit_code') == 0 and run.get('finish_epoch', 0) >= run['start_epoch'], 'Affected lint run incomplete or failed')
    log = Path(run['log'])
    text = log.read_text()
    require('BUILD SUCCESSFUL' in text, 'Lint did not complete successfully')
    require([arg for arg in run['args'] if arg.startswith(':')] == [':apk-runtime:lintReportRelease'], 'Unexpected affected lint scope')
    require('--offline' in run['args'] and '--info' in run['args'], 'Lint metadata/evidence flags absent')
    require(any(arg.endswith('/fresh-lint.init.gradle') for arg in run['args']), 'Fresh lint harness absent')
    require('-x' not in run['args'] and '--exclude-task' not in run['args'], 'Unexpected lint task exclusion')
    for task in ('lintAnalyzeRelease', 'lintAnalyzeReleaseUnitTest', 'lintReportRelease'):
        require(re.search(r'^> Task :apk-runtime:' + task + r'\s*$', text, re.M), 'Affected lint task did not execute freshly: ' + task)
    before = V / 'lint-metadata-before.json'
    after = V / 'lint-metadata-after.json'
    before_value, after_value = json.loads(before.read_text()), json.loads(after.read_text())
    require(before_value == after_value, 'Before/after metadata receipts differ')
    require(before_value == json.loads((OUT / 'reviewer-lint-metadata-preflight.json').read_text()), 'Runtime lint metadata differs from independent preflight')
    require(before.stat().st_mtime <= run['start_epoch'] and after.stat().st_mtime >= run['finish_epoch'], 'Metadata checks not ordered around run')
    current = subprocess.check_output(['python', str(OUT / 'review_lint_metadata_scope.py'), '--compare-with', str(before)], text=True)
    require(json.loads(current) == before_value, 'Current metadata differs')
    fresh = V / 'apk-runtime-lint-results-release.xml'
    old = OLD / 'apk-runtime-lint-results-release.xml'
    require(run['start_epoch'] <= fresh.stat().st_mtime <= run['finish_epoch'] + 1, 'Archived lint report stale')
    actual = P / 'app/apk-runtime/build/reports/lint-results-release.xml'
    require(sha(actual) == sha(fresh), 'Archived lint report differs from actual output')
    def canonical(element):
        return (element.tag, tuple(sorted(element.attrib.items())), (element.text or '').strip(), tuple(canonical(child) for child in element))
    previous, updated = ET.parse(old).getroot(), ET.parse(fresh).getroot()
    previous_issues = collections.Counter(canonical(x) for x in previous.findall('issue'))
    updated_issues = collections.Counter(canonical(x) for x in updated.findall('issue'))
    require(previous.attrib == updated.attrib, 'Lint report metadata changed')
    require(previous_issues == updated_issues and sum(updated_issues.values()) == 5, 'Complete lint diagnostics changed')
    return {'run': str(run_path), 'runSha256': sha(run_path), 'logSha256': sha(log), 'reportSha256': sha(fresh), 'historicalV80ReportSha256': sha(old), 'completeDiagnosticElementsEqualToV80': True, 'diagnostics': 5, 'metadataBeforeSha256': sha(before), 'metadataAfterSha256': sha(after), 'metadataVerifierSha256': sha(OUT / 'review_lint_metadata_scope.py'), 'originalFrozenMetadataFiles': 66, 'originalTaskCaches': 19, 'preservedExtraAppVitalTaskCaches': 2, 'cacheWritesOrDeletesByVerifier': False, 'scope': 'Fresh complete affected apk-runtime release main/unit-test lint plus report. Other three historical v80 reports are unchanged evidence, not newly executed.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path)
    parser.add_argument('--lint-run', type=Path)
    parser.add_argument('--source-only', action='store_true')
    args = parser.parse_args()
    require(args.source_only or args.run, '--run or --source-only is required')
    source = source_review()
    result = {'approvedSourceProof': True, 'approvedFocusedExecution': False, 'source': source, 'historicalEvidenceReuse': reuse_review(), 'reviewedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'reviewerScriptSha256': sha(__file__)}
    if not args.source_only:
        result['focusedExecution'] = focused_review(args.run.resolve(), source)
        result['approvedFocusedExecution'] = True
    if args.lint_run:
        require(not args.source_only, 'Final lint review also requires focused run')
        result['affectedLint'] = lint_review(args.lint_run.resolve())
        result['approvedFocusedChange'] = True
    destination = OUT / ('reviewer-matrix-final.json' if args.lint_run else 'reviewer-matrix-source-proof.json' if args.source_only else 'reviewer-matrix-focused.json')
    destination.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'receipt': str(destination), 'sourceApproved': True, 'focusedExecutionApproved': result['approvedFocusedExecution'], 'tests': result.get('focusedExecution', {}).get('tests')}))


if __name__ == '__main__':
    main()
