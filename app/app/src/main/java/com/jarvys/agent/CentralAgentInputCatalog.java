package com.jarvys.agent;

import com.jarvys.agent.workspaces.WorkspaceChanges;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps the workspace catalog in sync with newly acknowledged Central AGENT inputs. */
final class CentralAgentInputCatalog {
  private CentralAgentInputCatalog() {}

  static boolean acknowledge(LocalRunStore store, String sessionId, List<String> messageIds,
      Set<String> agentMessageIds) {
    List<String> changedIds = store.markConversationMessagesIncorporated(sessionId, messageIds);
    if (!WorkspaceIdentity.CENTRAL_SESSION.equals(sessionId) || changedIds.isEmpty()
        || agentMessageIds == null || agentMessageIds.isEmpty()) return false;
    Set<String> changed = new HashSet<>(changedIds);
    for (String agentId : agentMessageIds) {
      if (changed.contains(agentId)) {
        WorkspaceChanges.changed();
        return true;
      }
    }
    return false;
  }
}
