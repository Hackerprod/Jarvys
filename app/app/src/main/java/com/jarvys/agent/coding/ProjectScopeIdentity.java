package com.jarvys.agent.coding;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ProjectScopeIdentity {
  final File journalDirectory;
  private final File manifest;
  private final List<ProjectScope.Anchor> privateAnchors;
  final String value;

  private ProjectScopeIdentity(
      String value,
      File manifest,
      File journalDirectory,
      List<ProjectScope.Anchor> privateAnchors) {
    this.value = value;
    this.manifest = manifest;
    this.journalDirectory = journalDirectory;
    this.privateAnchors = privateAnchors;
  }

  static File manifestPath(File anchor, String id) {
    return new File(anchor, "jarvys/coding-project-state/" + id + "/identity.json");
  }

  static ProjectScopeIdentity open(File anchor, String id, List<ProjectScope.Anchor> roots)
      throws IOException {
    JSONObject record;
    int i;
    List<ProjectScope.Anchor> privateAnchors = new ArrayList<>();
    privateAnchors.add(new ProjectScope.Anchor(anchor));
    String[] strArr = {"jarvys", "coding-project-state", id};
    File directory = anchor;
    for (int i2 = 0; i2 < 3; i2++) {
      String part = strArr[i2];
      validate(privateAnchors);
      directory = new File(directory, part);
      if (!ProjectFileIO.exists(directory)) {
        ProjectFileIO.mkdir(directory);
        ProjectJournalIO.syncDirectory(directory.getParentFile());
      }
      privateAnchors.add(new ProjectScope.Anchor(directory));
    }
    File manifest = new File(directory, "identity.json");
    try {
      String fingerprint = fingerprint(roots);
      File journal = new File(directory, "mutations");
      if (ProjectFileIO.exists(manifest)) {
        record = ProjectJournalIO.read(manifest);
        if (!ProjectFileIO.exists(journal)) {
          throw new IOException("Saved project mutation journal is missing; review required");
        }
        ProjectScope.Anchor journalAnchor = new ProjectScope.Anchor(journal);
        if (!key(journalAnchor).equals(record.getString("journalKey"))) {
          throw new IOException(
              "Saved project mutation journal directory was replaced; review required");
        }
        privateAnchors.add(journalAnchor);
        i = 1;
      } else {
        if (!ProjectFileIO.exists(journal)) {
          ProjectFileIO.mkdir(journal);
          ProjectJournalIO.syncDirectory(directory);
        }
        ProjectScope.Anchor journalAnchor2 = new ProjectScope.Anchor(journal);
        privateAnchors.add(journalAnchor2);
        record =
            new JSONObject()
                .put("schema", 1)
                .put("project", id)
                .put("fingerprint", fingerprint)
                .put("identity", "scope-v1:" + UUID.randomUUID() + ":" + fingerprint)
                .put("journalKey", key(journalAnchor2));
        validate(roots);
        validate(privateAnchors);
        i = 1;
        ProjectJournalIO.write(manifest, record, true);
      }
      if (record.getInt("schema") == i) {
        if (!id.equals(record.getString("project"))
            || !fingerprint.equals(record.getString("fingerprint"))) {
          throw new IOException(
              "Project root or parent was replaced since the saved identity; review required");
        }
        String identity = record.getString("identity");
        if (!identity.matches("scope-v1:[0-9a-f-]{36}:[0-9a-f]{64}")
            || !identity.endsWith(":" + fingerprint)) {
          throw new IOException("Invalid project identity; original evidence retained");
        }
        validate(roots);
        validate(privateAnchors);
        return new ProjectScopeIdentity(identity, manifest, journal, privateAnchors);
      }
      throw new IOException("Unknown project identity version; review required");
    } catch (JSONException invalid) {
      throw new IOException("Invalid project identity; original evidence retained", invalid);
    }
  }

  void validate() throws IOException {
    validate(this.privateAnchors);
    try {
      JSONObject record = ProjectJournalIO.read(this.manifest);
      if (record.getInt("schema") != 1
          || !this.value.equals(record.getString("identity"))
          || !key(this.privateAnchors.get(this.privateAnchors.size() - 1))
              .equals(record.getString("journalKey"))) {
        throw new IOException("Project durable identity changed; review required");
      }
    } catch (JSONException invalid) {
      throw new IOException("Invalid project durable identity", invalid);
    }
  }

  private static String key(ProjectScope.Anchor anchor) {
    return anchor.key.getClass().getName() + ":" + anchor.key;
  }

  private static String fingerprint(List<ProjectScope.Anchor> anchors) throws IOException {
    validate(anchors);
    JSONArray keys = new JSONArray();
    for (ProjectScope.Anchor anchor : anchors) {
      keys.put(anchor.path.getPath())
          .put(anchor.key.getClass().getName())
          .put(anchor.key.toString());
    }
    return ProjectScope.sha256(keys.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void validate(List<ProjectScope.Anchor> anchors) throws IOException {
    for (ProjectScope.Anchor anchor : anchors) {
      anchor.validate();
    }
  }
}
