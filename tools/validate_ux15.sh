#!/usr/bin/env bash
# Bots catalog, scoped configuration, icon and navigation checks. Never signs or installs.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/app"
mode="${1:-focused}"
[[ "$mode" == focused || "$mode" == all ]] || { echo 'Usage: validate_ux15.sh [focused|all]' >&2; exit 2; }
args=(--no-daemon --max-workers=2 --console=plain -Dorg.gradle.jvmargs=-Xmx2048m
    -Pkotlin.compiler.execution.strategy=in-process -PunsignedBuild=true)
if [[ -n "${JARVYS_TEST_INIT_SCRIPT:-}" ]]; then args+=(-I "$JARVYS_TEST_INIT_SCRIPT"); fi
for flavor in Full Play; do
    args+=(":app:test${flavor}DebugUnitTest")
    if [[ "$mode" == focused ]]; then
        for pattern in 'com.jarvys.agent.*Bot*' 'com.jarvys.agent.*Crew*' 'com.jarvys.agent.*Drawer*' \
            'com.jarvys.agent.AppNavigationBackPolicyTest' 'com.jarvys.agent.UserDecisionScopeTest'; do
            args+=(--tests "$pattern")
        done
    fi
done
args+=(--continue)
"${GRADLE_BIN:-./gradlew}" "${args[@]}"
