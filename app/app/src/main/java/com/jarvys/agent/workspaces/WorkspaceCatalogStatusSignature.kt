package com.jarvys.agent.workspaces

import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewProcessIdentity

/** Stable identity/status projection for the aggregate catalog row; phase and progress are omitted. */
object WorkspaceCatalogStatusSignature {
    @JvmStatic
    fun from(snapshot: CrewMissionSnapshot): String = snapshot.bots
        .sortedWith(compareBy<CrewBotSnapshot>({ it.id.orEmpty() }, { it.status.orEmpty() }))
        .joinToString(separator = ";") { bot ->
            val id = bot.id.orEmpty()
            val status = if (snapshot.processId != CrewProcessIdentity.ID && bot.active()) "INTERRUPTED"
                else bot.status.orEmpty()
            "${id.length}:$id${status.length}:$status"
        }
}
