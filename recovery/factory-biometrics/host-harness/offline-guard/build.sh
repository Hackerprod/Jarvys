#!/bin/sh
set -eu
cd "$(dirname "$0")"
JDK=${JDK:-${JAVA_HOME:?Set JAVA_HOME to the verified full Temurin JDK 21}}
if git -C "$PWD" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo 'Refusing generated guard artifacts inside a Git checkout; materialize externally first.' >&2
    exit 1
fi
mkdir -p build/bootstrap build/agent reports
"$JDK/bin/javac" -d build/bootstrap src/guard/bootstrap/OfflineGuard.java
"$JDK/bin/javac" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -cp build/bootstrap -d build/agent src/guard/agent/*.java
"$JDK/bin/jar" --create --file guard-bootstrap.jar -C build/bootstrap .
printf 'Manifest-Version: 1.0\nPremain-Class: guard.agent.Agent\nCan-Retransform-Classes: true\nBoot-Class-Path: guard-bootstrap.jar\n\n' > build/MANIFEST.MF
"$JDK/bin/jar" --create --file jarvys-offline-guard.jar --manifest build/MANIFEST.MF -C build/agent .
sha256sum jarvys-offline-guard.jar guard-bootstrap.jar > reports/artifact-sha256.txt
