#!/usr/bin/env bash
# Portable low-level launcher. Gate-specific runners are maintained separately.
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
: "${JARVYS_TOOLCHAIN_ENV:?Set the absolute reviewed shell-only toolchain env.sh path}"
: "${JARVYS_VALIDATION_ROOT:?Set an absolute external evidence directory}"
: "${JARVYS_RUN_LABEL:?Set a new evidence label for this invocation}"
[[ "$JARVYS_TOOLCHAIN_ENV" = /* && -f "$JARVYS_TOOLCHAIN_ENV" ]]
[[ "$JARVYS_VALIDATION_ROOT" = /* && -d "$JARVYS_VALIDATION_ROOT" ]]
source "$JARVYS_TOOLCHAIN_ENV"
: "${JAVA_HOME:?Verified full JDK required}"
: "${GRADLE_HOME:?Verified Gradle distribution required}"
: "${GRADLE_USER_HOME:?External Gradle cache required}"
: "${JARVYS_GRADLE_LOCK:?Set the absolute external Gradle lock path}"
[[ "$JARVYS_GRADLE_LOCK" = /* ]]
[[ -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/javac" && -x "$GRADLE_HOME/bin/gradle" ]]
# Only the Gradle launcher gets proxy settings. Application test workers are guarded.
# Use an existing credential-free executor proxy when present; never embed credentials.
proxy_opts=()
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    proxy=$(python3 - <<'PY_PROXY'
import os,urllib.parse
u=urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
if u.scheme not in ('http','https') or not u.hostname or not u.port or u.username or u.password:
    raise SystemExit('Proxy must be an existing credential-free HTTP(S) executor proxy')
print(u.hostname,u.port)
PY_PROXY
)
    read -r host port <<< "$proxy"
    proxy_opts=("-Dhttp.proxyHost=$host" "-Dhttp.proxyPort=$port" "-Dhttps.proxyHost=$host" "-Dhttps.proxyPort=$port")
fi
export GRADLE_OPTS="${proxy_opts[*]} -Dhttp.nonProxyHosts=localhost|127.*|[::1] -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
# Parent orchestration already requests this same init for aggregates. Normalize
# exact same-file references so the guard is installed once, always by this launcher.
args=()
while (($#)); do
    case "$1" in
        -I|--init-script)
            if (($# < 2)); then echo 'Missing init-script argument' >&2; exit 1; fi
            if [[ "$2" -ef "$SCRIPT_DIR/fresh-tests.init.gradle" ]]; then shift 2; continue; fi
            args+=("$1" "$2"); shift 2 ;;
        --init-script=*)
            candidate=${1#--init-script=}
            if [[ "$candidate" -ef "$SCRIPT_DIR/fresh-tests.init.gradle" ]]; then shift; continue; fi
            args+=("$1"); shift ;;
        -I?*)
            candidate=${1#-I}
            if [[ "$candidate" -ef "$SCRIPT_DIR/fresh-tests.init.gradle" ]]; then shift; continue; fi
            args+=("$1"); shift ;;
        *) args+=("$1"); shift ;;
    esac
done
exec flock "$JARVYS_GRADLE_LOCK" "$GRADLE_HOME/bin/gradle" \
    --no-daemon --console=plain --max-workers=1 \
    '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts' \
    -Pkotlin.compiler.execution.strategy=in-process -PunsignedBuild=true \
    -I "$SCRIPT_DIR/fresh-tests.init.gradle" "${args[@]}"
