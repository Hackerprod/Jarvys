#!/usr/bin/env bash
# Build the Jarvys Android app with the checked-in Gradle wrapper.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

UNSIGNED=false
case "${1:-}" in
    --unsigned) UNSIGNED=true ;;
    "") ;;
    *) echo "Usage: $0 [--unsigned]" >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then echo "Usage: $0 [--unsigned]" >&2; exit 2; fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ ! -d "$ANDROID_HOME/platforms" || ! -d "$ANDROID_HOME/build-tools" ]]; then
    echo "Android SDK not found at $ANDROID_HOME (set ANDROID_HOME)." >&2
    exit 1
fi

if [[ "$UNSIGNED" != true && ! -f debug.keystore ]]; then
    echo "Original debug.keystore is missing. Refusing to create a replacement signing key." >&2
    echo "Compile/test sources with Gradle; restore the original signing key before building an update." >&2
    exit 2
fi

./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1280m -Dfile.encoding=UTF-8" \
    -PunsignedBuild="$UNSIGNED" :app:assemblePlayDebug :app:assembleFullDebug
if [[ "$UNSIGNED" == true ]]; then
    FULL_APK="Jarvys-unsigned.apk"
    PLAY_APK="Jarvys-play-unsigned.apk"
    cp "app/build/outputs/apk/full/debug/app-full-debug-unsigned.apk" "$FULL_APK"
    cp "app/build/outputs/apk/play/debug/app-play-debug-unsigned.apk" "$PLAY_APK"
else
    FULL_APK="Jarvys.apk"
    PLAY_APK="Jarvys-play.apk"
    cp "app/build/outputs/apk/full/debug/app-full-debug.apk" "$FULL_APK"
    cp "app/build/outputs/apk/play/debug/app-play-debug.apk" "$PLAY_APK"
fi

VERSION_CODE="$(python3 -c 'import re; s=open("app/build.gradle.kts", encoding="utf-8").read(); print(re.search(r"versionCode\s*=\s*(\d+)", s).group(1))')"
VERSION_NAME="$(python3 -c 'import re; s=open("app/build.gradle.kts", encoding="utf-8").read(); print(re.search(r"versionName\s*=\s*\"([^\"]+)\"", s).group(1))')"
UNSIGNED="$UNSIGNED" FULL_APK="$FULL_APK" PLAY_APK="$PLAY_APK" VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" python3 - <<'PY'
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
    "sha256": sha256(os.environ["FULL_APK"]),
    "signed": os.environ["UNSIGNED"] != "true",
    "play_sha256": sha256(os.environ["PLAY_APK"]),
    "built_at": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
}
manifest_path = "helper_manifest.unsigned.json" if os.environ["UNSIGNED"] == "true" else "helper_manifest.json"
with open(manifest_path, "w", encoding="utf-8", newline="\n") as output:
    json.dump(manifest, output, indent=2)
    output.write("\n")
print(json.dumps(manifest, indent=2))
PY

echo "Build successful: $DIR/$FULL_APK"
echo "Play-safe APK: $DIR/$PLAY_APK"
