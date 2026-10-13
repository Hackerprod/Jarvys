#!/bin/sh
set -eu
cd "$(dirname "$0")"
JDK=${JDK:-${JAVA_HOME:?Set JAVA_HOME to verified JDK21}}
LIB=${OKHTTP_LIB_DIR:?Set OKHTTP_LIB_DIR to verified 5.2.3 dependency directory}
if git -C "$PWD" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo 'Refusing generated guard artifacts inside a Git checkout; materialize externally first.' >&2; exit 1
fi
CP="$LIB/okhttp-jvm-5.2.3.jar:$LIB/okio-jvm-3.16.4.jar:$LIB/kotlin-stdlib-2.2.20.jar:$LIB/annotations-13.0.jar"
mkdir -p build/okhttp-smoke reports/okhttp
"$JDK/bin/javac" --add-modules jdk.httpserver -cp "guard-bootstrap.jar:$CP" -d build/okhttp-smoke okhttp-smoke/guard/okhttp/*.java
"$JDK/bin/jar" --create --file okhttp-smoke.jar -C build/okhttp-smoke .
"$JDK/bin/java" -XX:-CreateCoredumpOnCrash --add-modules jdk.httpserver -Djarvys.offlineGuard.okhttp=required \
    -Djarvys.offlineGuard.reportDir="$PWD/reports/okhttp" -javaagent:"$PWD/jarvys-offline-guard.jar" \
    -cp "okhttp-smoke.jar:smoke.jar" guard.okhttp.ChildLoaderSmoke "$PWD/okhttp-smoke.jar" \
    "$LIB/okhttp-jvm-5.2.3.jar" "$LIB/okio-jvm-3.16.4.jar" "$LIB/kotlin-stdlib-2.2.20.jar" "$LIB/annotations-13.0.jar"
