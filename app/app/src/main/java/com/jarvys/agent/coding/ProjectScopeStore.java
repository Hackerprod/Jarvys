package com.jarvys.agent.coding;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class ProjectScopeStore {
  private static final ConcurrentHashMap<String, ProjectScope> OPEN = new ConcurrentHashMap<>();
  private final File anchor;

  public ProjectScopeStore(File appFilesAnchor) throws IOException {
    if (appFilesAnchor == null) {
      throw new IOException("App files directory is required");
    }
    this.anchor = appFilesAnchor.getCanonicalFile();
    if (!ProjectFileIO.isDirectory(this.anchor)) {
      throw new IOException("App files directory does not exist");
    }
  }

  public ProjectScope open(String conversationId) throws IOException {
    if (conversationId == null || conversationId.trim().isEmpty()) {
      throw new IOException("Conversation id is required");
    }
    String id = ProjectScope.sha256(conversationId.getBytes(StandardCharsets.UTF_8));
    String key = new File(anchor, "jarvys/coding-projects/" + id + "/root").getPath();
    synchronized (OPEN) {
      ProjectScope existing = OPEN.get(key);
      if (existing != null) {
        existing.durableIdentity();
        return existing;
      }
      List<ProjectScope.Anchor> anchors = new ArrayList<>();
      anchors.add(new ProjectScope.Anchor(anchor));
      File path = anchor;
      for (String part : new String[] {"jarvys", "coding-projects", id, "root"}) {
        for (ProjectScope.Anchor known : anchors) known.validate();
        path = new File(path, part);
        if (!ProjectFileIO.exists(path)) {
          if (ProjectFileIO.exists(ProjectScopeIdentity.manifestPath(anchor, id))) {
            throw new IOException("Saved project directory is missing; reopen requires review");
          }
          ProjectFileIO.mkdir(path);
          ProjectJournalIO.syncDirectory(path.getParentFile());
        }
        anchors.add(new ProjectScope.Anchor(path));
      }
      ProjectScopeIdentity identity = ProjectScopeIdentity.open(anchor, id, anchors);
      ProjectScope scope =
          new ProjectScope(
              id,
              conversationId,
              path,
              anchors,
              new ProjectScope.State(),
              EnumSet.allOf(ProjectScope.Capability.class),
              identity);
      scope.validate();
      OPEN.put(key, scope);
      return scope;
    }
  }
}
