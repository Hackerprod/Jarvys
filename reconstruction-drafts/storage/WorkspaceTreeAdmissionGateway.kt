package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CentralTreeAdmission

/** Only explicit native service admission upgrades the current Registry; ordinary open/read never does. */
internal object WorkspaceTreeAdmissionGateway {
    @JvmStatic fun openForNewAdmission(context:Context,authority:CentralTreeAdmission,token:CancellationToken):WorkspaceRegistry {
        check(WorkspaceTreePolicy.admissionEnabled()) { "Tree admissions are disabled" }
        authority.requireFreshDelegation(token);authority.revalidateSource(token)
        val registry=WorkspaceRegistry(context)
        try {
            authority.withCurrentWorkspaceTransaction(token) {
                authority.requireFreshDelegation(token)
                registry.upgradeTreeSchema()
            }
            return registry
        } catch(failure:Throwable) { registry.close();throw failure }
    }
    @JvmStatic fun findOriginalRoot(context:Context,workspace:String,assignment:String):TreeRoot? = WorkspaceRegistry(context).use { db ->
        val trees=WorkspaceTreeStore(db);val child=trees.findAssignment(workspace,assignment) ?: return@use null
        requireNotNull(trees.getRoot(child.rootId))
    }
}
