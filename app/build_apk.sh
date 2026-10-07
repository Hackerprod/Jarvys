#!/usr/bin/env bash
# Build the Jarvys Android app with the checked-in Gradle wrapper.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ ! -d "$ANDROID_HOME/platforms" || ! -d "$ANDROID_HOME/build-tools" ]]; then
    echo "Android SDK not found at $ANDROID_HOME (set ANDROID_HOME)." >&2
    exit 1
fi

if [[ ! -f debug.keystore ]]; then
    echo "Original debug.keystore is missing. Refusing to create a replacement signing key." >&2
    echo "Compile/test sources with Gradle; restore the original signing key before building an update." >&2
    exit 2
fi

./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1280m -Dfile.encoding=UTF-8" \
    :app:assemblePlayDebug :app:assembleFullDebug
cp "app/build/outputs/apk/full/debug/app-full-debug.apk" Jarvys.apk
cp "app/build/outputs/apk/play/debug/app-play-debug.apk" Jarvys-play.apk

VERSION_CODE="$(python3 -c 'import re; s=open("app/build.gradle.kts", encoding="utf-8").read(); print(re.search(r"versionCode\s*=\s*(\d+)", s).group(1))')"
VERSION_NAME="$(python3 -c 'import re; s=open("app/build.gradle.kts", encoding="utf-8").read(); print(re.search(r"versionName\s*=\s*\"([^\"]+)\"", s).group(1))')"
VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" python3 - <<'PY'
import datetime
import hashlib
import json
import os

def sha256(path):
    with open(path, "rb") as apk:
        return hashlib.sha256(apk.read()).hexdigest()

manifest = {
    "package": "com.jarvys.agent",
    "version_code": int(os.environ["VERSION_CODE"]),
    "version_name": os.environ["VERSION_NAME"],
    "flavor": "full",
    "sha256": sha256("Jarvys.apk"),
    "play_sha256": sha256("Jarvys-play.apk"),
    "built_at": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
}
with open("helper_manifest.json", "w", encoding="utf-8", newline="\n") as output:
    json.dump(manifest, output, indent=2)
    output.write("\n")
print(json.dumps(manifest, indent=2))
PY

echo "Build successful: $DIR/Jarvys.apk"
echo "Play-safe APK: $DIR/Jarvys-play.apk"
