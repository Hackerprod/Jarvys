#!/usr/bin/env python3
"""Rebuild audit test fixtures from two Java/XML source inputs, without downloads.

Uses only the caller's restored pinned JDK 21.0.12.1+1, Android build-tools 35.0.0
(D8/aapt2/dexdump), and platform android-36/android.jar. No Gradle or signing.
Outputs are compiled test fixtures and must not be included in source backups.
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(ok, message):
    if not ok: raise ValueError(message)


def rebuild(output, toolchain, jdk=None, build_tools=None, android_jar=None):
    toolchain = pathlib.Path(toolchain).resolve()
    jdk = pathlib.Path(jdk).resolve() if jdk else toolchain / 'jdk-21.0.12.1+1'
    build_tools = pathlib.Path(build_tools).resolve() if build_tools else toolchain / 'android-sdk/build-tools/35.0.0'
    android_jar = pathlib.Path(android_jar).resolve() if android_jar else toolchain / 'android-sdk/platforms/android-36/android.jar'
    output = pathlib.Path(output).resolve()
    inputs = {name: ROOT / name for name in ('SymbolicProbe.java', 'ProbeManifest.xml')}
    tools = {'java': jdk / 'bin/java', 'javac': jdk / 'bin/javac', 'd8': build_tools / 'd8',
             'd8.jar': build_tools / 'lib/d8.jar', 'aapt2': build_tools / 'aapt2',
             'dexdump': build_tools / 'dexdump', 'android.jar': android_jar}
    for label, path in {**inputs, **tools}.items():
        require(path.is_file(), 'Required local input/tool is absent: %s (%s)' % (label, path))
    for label in ('java', 'javac', 'd8', 'aapt2', 'dexdump'):
        require(os.access(tools[label], os.X_OK), 'Tool is not executable: ' + str(tools[label]))
    require(not output.exists() or output.is_dir() and not any(output.iterdir()),
            'Output must be a new or empty directory; existing fixtures are never overwritten')
    output.mkdir(parents=True, exist_ok=True)
    classes = output / 'probe-classes'; classes.mkdir()
    dex = output / 'probe-dex'; dex.mkdir()
    env = dict(os.environ, JAVA_HOME=str(jdk), LC_ALL='C.UTF-8', TZ='UTC')
    commands = []
    logfile = output / 'probe-build.log'
    def run(argv, stdout_file=None):
        argv = [str(x) for x in argv]
        result = subprocess.run(argv, cwd=output, env=env, capture_output=True)
        commands.append({'argv': argv, 'returnCode': result.returncode,
                         'stdoutSha256': hashlib.sha256(result.stdout).hexdigest(),
                         'stderrSha256': hashlib.sha256(result.stderr).hexdigest()})
        with logfile.open('ab') as log:
            log.write(('COMMAND ' + json.dumps(argv) + '\n').encode())
            log.write(result.stdout if stdout_file is None else b'[stdout saved to fixture file]\n')
            log.write(result.stderr)
            log.write(('EXIT %d\n\n' % result.returncode).encode())
        require(result.returncode == 0, 'Probe command failed; inspect ' + str(logfile))
        if stdout_file is not None: stdout_file.write_bytes(result.stdout)
    run([tools['java'], '-version'])
    run([tools['javac'], '--release', '8', '-d', classes, inputs['SymbolicProbe.java']])
    classfiles = sorted(classes.glob('*.class'))
    require(classfiles, 'javac emitted no class files')
    run([tools['d8'], '--min-api', '24', '--output', dex, *classfiles])
    require((dex / 'classes.dex').is_file(), 'D8 emitted no primary DEX')
    run([tools['dexdump'], '-d', dex / 'classes.dex'], output / 'probe.dexdump.txt')
    base = [tools['aapt2'], 'link', '--manifest', inputs['ProbeManifest.xml'], '-I', android_jar]
    run([*base, '-o', output / 'probe-v1.apk'])
    run([*base, '--version-code', '2', '--version-name', '2.0', '--replace-version', '-o', output / 'probe-v2.apk'])
    for name in ('probe-v1.apk', 'probe-v2.apk'):
        with zipfile.ZipFile(output / name) as archive:
            require(archive.testzip() is None and 'AndroidManifest.xml' in archive.namelist(), 'Invalid probe APK')
    outputs = {str(path.relative_to(output)): {'sha256': sha(path), 'size': path.stat().st_size}
               for path in sorted(output.rglob('*')) if path.is_file() and path != logfile}
    provenance = {'format': 'independent-source-only-probe-rebuild-v1',
                  'sourceInputs': {name: {'path': str(path), 'sha256': sha(path)} for name, path in inputs.items()},
                  'toolInputs': {name: {'path': str(path), 'sha256': sha(path)} for name, path in tools.items()},
                  'commands': commands, 'generatedOutputs': outputs,
                  'scope': 'Local synthetic fixtures only. No downloads, Gradle, signing, production edits or Android execution.',
                  'backupRule': 'Keep generator, Java/XML sources, helpers/tests and docs; exclude all generated output directories.'}
    (output / 'probe-provenance.json').write_text(json.dumps(provenance, indent=2, sort_keys=True) + '\n')
    return provenance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=pathlib.Path, help='New or empty generated-fixture directory')
    parser.add_argument('--toolchain', type=pathlib.Path, default=ROOT.parents[1] / 'toolchain')
    parser.add_argument('--jdk', type=pathlib.Path, help='Override pinned JDK root when restored elsewhere')
    parser.add_argument('--build-tools', type=pathlib.Path, help='Override build-tools/35.0.0 root')
    parser.add_argument('--android-jar', type=pathlib.Path, help='Override platform android-36/android.jar path')
    args = parser.parse_args()
    result = rebuild(args.output, args.toolchain, args.jdk, args.build_tools, args.android_jar)
    print(json.dumps({'output': str(args.output.resolve()), 'generatedFiles': len(result['generatedOutputs']),
                      'provenance': str(args.output.resolve() / 'probe-provenance.json')}))


if __name__ == '__main__': main()
