#!/bin/sh
set -eu
cd "$(dirname "$0")"
JDK=${JDK:-/workspace/shared/Jarvys-recovery/toolchain/jdk-21.0.12.1+1}
mkdir -p build/smoke
"$JDK/bin/javac" --add-exports java.base/sun.net.www.protocol.http=ALL-UNNAMED --add-modules jdk.httpserver -cp guard-bootstrap.jar -d build/smoke smoke/guard/smoke/*.java
"$JDK/bin/jar" --create --file smoke.jar -C build/smoke . -C smoke META-INF
"$JDK/bin/java" -XX:-CreateCoredumpOnCrash --add-modules jdk.httpserver --add-exports java.base/sun.net.www.protocol.http=ALL-UNNAMED -javaagent:"$PWD/jarvys-offline-guard.jar" -cp smoke.jar guard.smoke.GuardSmoke
