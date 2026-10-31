package com.jarvys.agent.workspaces;

import com.jarvys.agent.CoreToolResult;
import com.jarvys.agent.EffectInvocation;

/** Local producer tests; never mints Central provenance or enables an operational source. */
public final class WorkspaceMutationTestSupport {
    private WorkspaceMutationTestSupport() { }
    public static WorkspaceMutationContext create(String action, String workspace,
            EffectInvocation invocation, Runnable guard) {
        return new WorkspaceMutationContext(action, workspace, null, invocation, guard);
    }
    public static EffectInvocation.Receipt receipt(WorkspaceMutationContext context) {
        return context.finish(CoreToolResult.success("local producer return")).effectReceipt;
    }
}
