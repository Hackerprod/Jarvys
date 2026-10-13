#!/bin/sh
set -eu
cd "$(dirname "$0")"
JDK=${JDK:-${JAVA_HOME:?Set JAVA_HOME to the verified full Temurin JDK 21}}
if git -C "$PWD" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo 'Refusing generated guard artifacts inside a Git checkout; materialize externally first.' >&2
    exit 1
fi
mkdir -p build/smoke
"$JDK/bin/javac" --add-exports java.base/sun.net.www.protocol.http=ALL-UNNAMED --add-modules jdk.httpserver -cp guard-bootstrap.jar -d build/smoke smoke/guard/smoke/*.java
"$JDK/bin/jar" --create --file smoke.jar -C build/smoke . -C smoke META-INF
"$JDK/bin/java" -XX:-CreateCoredumpOnCrash --add-modules jdk.httpserver --add-exports java.base/sun.net.www.protocol.http=ALL-UNNAMED -javaagent:"$PWD/jarvys-offline-guard.jar" -cp smoke.jar guard.smoke.GuardSmoke
