package com.jarvys.agent.chatdb

import android.content.Context
import com.jarvys.agent.workspaces.WorkspaceRegistry
import com.jarvys.agent.workspaces.WorkspaceIdentity

/** Operation-local optional canonical grant, never worker provenance or a model-selected binding. */
internal class MissionReceiptAuthorization private constructor(
    private val owned:Set<String>,val policyDenied:Boolean,
) {
    fun allows(executionSession:String):Boolean = executionSession in owned
    companion object {
        fun currentOwnership(context:Context):MissionReceiptAuthorization {
            val owned=linkedSetOf<String>()
            WorkspaceRegistry(context.applicationContext).use { registry ->
                var after:String?=null
                do {
                    val records=registry.listWorkspaces(100,after)
                    records.filter { it.ownerCentralSessionId==WorkspaceIdentity.CENTRAL_SESSION }.forEach {
                        check(owned.add(it.executionSessionId)) { "mission_workspace_identity_conflict" }
                    }
                    after=records.lastOrNull()?.workspaceId
                } while(records.size==100)
            }
            return MissionReceiptAuthorization(java.util.Collections.unmodifiableSet(owned),false)
        }
        fun policyDenied()=MissionReceiptAuthorization(emptySet(),true)
    }
}
