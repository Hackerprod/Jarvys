#!/usr/bin/env bash
# Produce an unsigned Full APK with a separate install identity. Never creates a key.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"
if [[ $# != 0 ]]; then echo "Usage: $0" >&2; exit 2; fi
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1280m -Dfile.encoding=UTF-8" \
    -PunsignedBuild=true -PrecoveryTestBuild=true :app:assembleFullDebug
cp app/build/outputs/apk/full/debug/app-full-debug-unsigned.apk Jarvys-Prueba-unsigned.apk
echo "Separate unsigned test APK: $DIR/Jarvys-Prueba-unsigned.apk"
echo "Package: com.jarvys.agent.recoverytest. A separately backed-up test key is required for signing."
