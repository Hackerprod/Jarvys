package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.*

/** Test-only package seam. Receives the actual live execute4 witness, never fabricates one. */
internal object TreeUserTargetControlAssertions {
    fun rejectTransfers(app:Context,core:WorkspaceTreeStore,target:TreeNode,payload:String,other:String,
        actual:EffectInvocation,admission:CentralTreeAdmission,token:CancellationToken) {
        val context=WorkspaceMutationContext("cancel",target.workspaceId,actual.centralSource(),actual,Runnable {},admission,token)
        context.bindControlArguments(payload)
        val grant=TreeUserTargetControl.capture(app,context,target,"cancel",payload)
        check(runCatching { grant.transact(core,"reply",payload) { _,_,_->Unit } }.isFailure)
        check(runCatching { grant.transact(core,"cancel",other) { _,_,_->Unit } }.isFailure)
        context.close()
        check(runCatching { grant.transact(core,"cancel",payload) { _,_,_->Unit } }.isFailure)
    }
}
