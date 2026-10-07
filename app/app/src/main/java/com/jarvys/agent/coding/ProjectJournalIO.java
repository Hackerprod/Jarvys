package com.jarvys.agent.coding;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.jarvys.agent.CrewCheckpointStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import org.json.JSONException;
import org.json.JSONObject;

final class ProjectJournalIO {
  private ProjectJournalIO() {}

  static JSONObject read(File file) throws IOException {
    ProjectFileIO.Attributes before = ProjectFileIO.attributes(file);
    if (!before.isRegularFile() || before.isSymbolicLink()) {
      throw new IOException("Project recovery record is not an ordinary file");
    }
    long budget = CrewCheckpointStore.materializationBudget();
    if (before.size() > budget) {
      throw new IOException("Project recovery record cannot be safely materialized");
    }
    try (InputStream input = ProjectFileIO.openNoFollow(file);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if ((long) bytes.size() + count > budget) {
          throw new IOException(
              "Recovery record exceeds current memory headroom; evidence retained");
        }
        bytes.write(buffer, 0, count);
      }
      if (!ProjectScope.sameIdentity(before, ProjectFileIO.attributes(file))) {
        throw new IOException("Project recovery record changed while reading");
      }
      JSONObject envelope = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      if (envelope.getInt("format") != 1) {
        throw new IOException("Unknown project recovery record format");
      }
      String payload = envelope.getString("payload");
      if (!ProjectScope.sha256(payload.getBytes(StandardCharsets.UTF_8))
          .equals(envelope.getString("sha256"))) {
        throw new IOException("Project recovery checksum mismatch; original evidence retained");
      }
      return new JSONObject(payload);
    } catch (StackOverflowError malformed) {
      throw new IOException("Recovery record is too deeply nested; evidence retained", malformed);
    } catch (JSONException malformed) {
      throw new IOException(
          "Unreadable project recovery record; original evidence retained", malformed);
    }
  }

  static void write(File file, JSONObject value, boolean createOnly) throws IOException {
    File directory = file.getParentFile();
    ProjectScope.Anchor parent = new ProjectScope.Anchor(directory);
    File staged = File.createTempFile(".journal-", ".tmp", directory);
    try {
      String payload = value.toString();
      JSONObject envelope =
          new JSONObject()
              .put("format", 1)
              .put("payload", payload)
              .put("sha256", ProjectScope.sha256(payload.getBytes(StandardCharsets.UTF_8)));
      try (FileOutputStream output = new FileOutputStream(staged)) {
        output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
        output.getFD().sync();
      }
      parent.validate();
      if (createOnly) {
        ProjectFileIO.linkNoReplace(staged, file);
      } else {
        if (!ProjectFileIO.attributes(file).isRegularFile()) {
          throw new IOException("Project recovery record was replaced");
        }
        ProjectFileIO.atomicReplace(staged, file);
      }
      syncDirectory(directory);
      parent.validate();
    } catch (JSONException invalid) {
      throw new IOException("Could not encode project recovery record", invalid);
    } finally {
      if (staged.exists()) staged.delete();
    }
  }

  static void syncDirectory(File directory) throws IOException {
    if (Build.VERSION.SDK_INT >= 26) {
      Nio.syncDirectory(directory);
      return;
    }
    FileDescriptor descriptor = null;
    try {
      descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
      if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
        throw new IOException("Project recovery sync target is not a directory");
      }
      Os.fsync(descriptor);
    } catch (ErrnoException error) {
      throw new IOException("Could not sync project recovery directory", error);
    } finally {
      if (descriptor != null) {
        try {
          Os.close(descriptor);
        } catch (ErrnoException ignored) {
        }
      }
    }
  }

  private static final class Nio {
    private Nio() {}

    static void syncDirectory(File directory) throws IOException {
      boolean interrupted = Thread.interrupted();
      try {
        while (true) {
          try (FileChannel channel =
              FileChannel.open(
                  directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
            return;
          } catch (ClosedByInterruptException interruptedDuringSync) {
            interrupted = true;
            Thread.interrupted();
          }
        }
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }
}
