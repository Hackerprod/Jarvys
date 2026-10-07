#!/usr/bin/env bash
# Hermetic reaction checks or the full regression suite. Always unsigned.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/app"
mode="${1:-focused}"
[[ "$mode" == focused || "$mode" == all ]] || { echo 'Usage: validate_ux14.sh [focused|all]' >&2; exit 2; }
classes=(com.jarvys.agent.MessageReactionSubmissionTest com.jarvys.agent.MessageReactionTest com.jarvys.agent.ui.chat.MessageReactionUiTest
    com.jarvys.agent.ui.chat.MessageReactionVisualCaptureTest com.jarvys.agent.ApplicationIdentityTest
    com.jarvys.agent.ConversationCompactionTest com.jarvys.agent.UserDecisionScopeTest
    com.jarvys.agent.LocalRunAttachmentRecoveryTest com.jarvys.agent.AttachmentModelContextRecoveryTest)
args=(--no-daemon --max-workers=2 --console=plain -Dorg.gradle.jvmargs=-Xmx2048m
    -Pkotlin.compiler.execution.strategy=in-process -PunsignedBuild=true)
if [[ -n "${JARVYS_TEST_INIT_SCRIPT:-}" ]]; then args+=(-I "$JARVYS_TEST_INIT_SCRIPT"); fi
for flavor in Full Play; do
    args+=(":app:test${flavor}DebugUnitTest")
    if [[ "$mode" == focused ]]; then
        for cls in "${classes[@]}"; do args+=(--tests "$cls"); done
    fi
done
args+=(--continue)
"${GRADLE_BIN:-./gradlew}" "${args[@]}"
