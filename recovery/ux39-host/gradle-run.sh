#!/usr/bin/env bash
set -euo pipefail
source /workspace/shared/Jarvys-recovery/toolchain/env.sh
# Use this executor's existing proxy only for the dependency-fetching Gradle process.
# This does not alter network policy or system settings. Test JVMs use an offline guard.
read -r host port < <(python3 - <<'PY'
import os,urllib.parse
u=urllib.parse.urlparse(os.environ['HTTPS_PROXY']); assert u.hostname and u.port and not u.username
print(u.hostname,u.port)
PY
)
export GRADLE_OPTS="-Dhttp.proxyHost=$host -Dhttp.proxyPort=$port -Dhttps.proxyHost=$host -Dhttps.proxyPort=$port -Dhttp.nonProxyHosts=localhost\|127.*\|[::1] -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
exec flock /workspace/shared/Jarvys-recovery/toolchain/gradle.lock "$GRADLE_HOME/bin/gradle" --no-daemon --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts' -Pkotlin.compiler.execution.strategy=in-process -PunsignedBuild=true "$@"
