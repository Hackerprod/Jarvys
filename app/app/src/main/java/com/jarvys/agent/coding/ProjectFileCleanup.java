package com.jarvys.agent.coding;

import java.io.File;
import java.io.IOException;
import java.util.List;

final class ProjectFileCleanup {

  interface Observer {
    void beforeCleanup(String str, boolean z) throws IOException;
  }

  static final class Entry {
    final boolean directory;
    final File file;
    Object identity;
    final String relative;

    Entry(File file, String relative, boolean directory) {
      this.file = file;
      this.relative = relative;
      this.directory = directory;
    }

    void captureIdentity() throws IOException {
      this.identity = ProjectFileIO.attributes(this.file).fileKey();
    }
  }

  private ProjectFileCleanup() {}

  static void remove(
      ProjectScope scope,
      ProjectScope.WriterLease lease,
      Entry entry,
      List<String> warnings,
      Observer observer) {
    try {
      scope.validate();
      int slash = entry.relative.lastIndexOf(47);
      String strSubstring = ".";
      scope.resolve(slash < 0 ? "." : entry.relative.substring(0, slash));
      if (ProjectFileIO.exists(entry.file)) {
        ProjectFileIO.Attributes actual = ProjectFileIO.attributes(entry.file);
        if (entry.identity == null
            || !entry.identity.equals(actual.fileKey())
            || actual.isSymbolicLink()
            || entry.directory != actual.isDirectory()) {
          throw new IOException("Cleanup target identity changed");
        }
        if (observer != null) {
          observer.beforeCleanup(entry.relative, entry.directory);
        }
        scope.validate();
        if (slash >= 0) {
          strSubstring = entry.relative.substring(0, slash);
        }
        scope.resolve(strSubstring);
        ProjectFileIO.Attributes finalIdentity = ProjectFileIO.attributes(entry.file);
        if (!entry.identity.equals(finalIdentity.fileKey()) || finalIdentity.isSymbolicLink()) {
          throw new IOException("Cleanup target changed before deletion");
        }
        ProjectFileIO.delete(entry.file);
      }
    } catch (IOException | RuntimeException e) {
      warnings.add(
          (entry.directory ? "Directory" : "Staging file")
              + " remains or cleanup is unverified: "
              + entry.relative);
      try {
        lease.markChanged();
      } catch (IOException e2) {
        warnings.add("Scope version update could not be verified after cleanup failure");
      }
    }
  }
}
