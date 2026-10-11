#!/usr/bin/env python3
"""Read-only exact v80 lint metadata verification, retaining two known marker-only APK-build caches.

This neither seeds nor deletes cache files. It takes the existing Gradle runner lock. The original
19-task/66-file public metadata perimeter is unchanged; both additional app vital cache trees must
contain only the exact reviewed lint marker, plus two existing empty metadata directories in the
analysis task. Optional before/after comparison includes those two
markers' identity, bytes and modification times. Existing analysis/API/typo caches stay untouched.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent
ROOT = OUT.parent.parent
PROJECT = ROOT / 'integration'
OLD = ROOT / 'FACTORY-BIOMETRICS-v80-validation'
HELPER = OLD / 'independent/lint_metadata_snapshot.py'
HELPER_SHA = '6f2f0326044c7f04b170fc4795925ab253c0dd0d60ed69206eca93e462fd6d91'
MANIFEST_SHA = 'f11ea0825a962be1f0250dce8d974cf48538d55e67519ab239413414334a03d2'
EXTRA_TASKS = {
    'app/app/build/intermediates/lint-cache/lintVitalAnalyzeFullRelease',
    'app/app/build/intermediates/lint-cache/lintVitalReportFullRelease',
}
EXTRA_EMPTY_DIRECTORIES = {
    'app/app/build/intermediates/lint-cache/lintVitalAnalyzeFullRelease/maven.google',
    'app/app/build/intermediates/lint-cache/lintVitalAnalyzeFullRelease/sdk_index',
}


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify():
    require(sha(HELPER) == HELPER_SHA, 'Original reviewed snapshot verifier changed')
    spec = importlib.util.spec_from_file_location('reviewed_lint_snapshot', HELPER)
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    with helper.lock(ROOT / 'toolchain/gradle.lock'):
        manifest = helper.load_snapshot(OLD / 'lint-metadata-snapshot', MANIFEST_SHA)
        require(len(manifest['tasks']) == 19 and len(manifest['files']) == 66, 'Original snapshot cardinality changed')
        expected_tasks = set(manifest['tasks']) | EXTRA_TASKS
        actual_tasks = set()
        for module in helper.MODULES:
            root = helper.safe(PROJECT / 'app' / module / 'build/intermediates/lint-cache')
            require(root.is_dir(), 'Missing module cache root')
            for task in root.iterdir():
                helper.safe(task)
                require(task.is_dir(), 'Unexpected task-cache entry')
                actual_tasks.add(str(task.relative_to(PROJECT)))
        require(actual_tasks == expected_tasks, 'Unreviewed extra/missing task cache')

        files, domains = {}, []
        for relative_task in manifest['tasks']:
            task = helper.safe(PROJECT / relative_task)
            marker = helper.safe(task / 'lint-cache-version.txt')
            require(marker.read_bytes() == helper.MARKER, 'Version marker changed')
            relative = str(marker.relative_to(PROJECT))
            helper.allowed(relative)
            files[relative] = helper.file_record(marker)
            for domain in helper.DOMAINS:
                folder = helper.safe(task / domain)
                if not folder.exists():
                    continue
                require(folder.is_dir(), 'Metadata domain is not a directory')
                domains.append(str(folder.relative_to(PROJECT)))
                for child in sorted(folder.rglob('*')):
                    helper.safe(child)
                    if child.is_dir():
                        continue
                    relative = str(child.relative_to(PROJECT))
                    helper.allowed(relative)
                    files[relative] = helper.file_record(child)
        require(helper.same_bytes(files, manifest['files']), 'Original 66 metadata bytes/inventory differ')
        require(sorted(domains) == manifest['directories'], 'Original metadata domain inventory differs')

        extras = {}
        for relative_task in sorted(EXTRA_TASKS):
            task = helper.safe(PROJECT / relative_task)
            children = sorted(task.rglob('*'))
            expected = {task / 'lint-cache-version.txt'} | {PROJECT / d for d in EXTRA_EMPTY_DIRECTORIES if str(Path(d).parent) == relative_task}
            require(set(children) == expected, 'Extra vital cache gained files/directories')
            for child in children:
                helper.safe(child)
                if str(child.relative_to(PROJECT)) in EXTRA_EMPTY_DIRECTORIES:
                    require(child.is_dir() and not list(child.iterdir()), 'Extra metadata directory is no longer empty')
            marker = helper.safe(task / 'lint-cache-version.txt')
            require(marker.read_bytes() == helper.MARKER, 'Extra vital cache version differs')
            extras[str(marker.relative_to(PROJECT))] = helper.file_record(marker)

        canonical_bytes = json.dumps({n: {'sha256': r['sha256'], 'bytes': r['bytes']} for n, r in sorted(files.items())}, sort_keys=True, separators=(',', ':')).encode()
        return {
            'verifiedFiles': 66,
            'verifiedOriginalTasks': 19,
            'manifestSha256': MANIFEST_SHA,
            'originalMetadataInventorySha256': hashlib.sha256(canonical_bytes).hexdigest(),
            'project': str(PROJECT),
            'permittedExtraTasks': sorted(EXTRA_TASKS),
            'permittedExtraEmptyDirectories': sorted(EXTRA_EMPTY_DIRECTORIES),
            'extraMarkerFiles': extras,
            'originalVerifierSha256': HELPER_SHA,
            'scope': 'Read-only exact original public-metadata/version-marker inputs plus two pre-existing app vital task caches containing exact markers and two explicitly empty metadata directories. No cache writes or deletions.',
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compare-with', type=Path)
    args = parser.parse_args()
    result = verify()
    if args.compare_with:
        require(result == json.loads(args.compare_with.read_text()), 'Before/after scoped metadata inventory or extra-marker identity changed')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
