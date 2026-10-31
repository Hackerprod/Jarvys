package com.jarvys.agent;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Deterministic local interleavings; no provider, permission or worker operation. */
public class LocalRunDirectoryCreationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static void ensure(File directory) throws Exception {
        Method method = LocalRunStore.class.getDeclaredMethod("ensureDirectory", File.class);
        method.setAccessible(true);
        try { method.invoke(null, directory); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }

    @Test public void anotherCreatorBetweenExistsAndMkdirIsAcceptedOnlyAsDirectory() throws Exception {
        File actual = new File(temporary.getRoot(), "shared-notes");
        File interleaving = new File(actual.getPath()) {
            @Override public boolean mkdirs() {
                assertTrue("The other creator must win this exact boundary", actual.mkdirs());
                return super.mkdirs(); // false: it now exists, although creation succeeded.
            }
        };
        ensure(interleaving);
        assertTrue(actual.isDirectory());
    }

    @Test public void failedCreationStillAbsentIsRejected() throws Exception {
        File absent = new File(temporary.getRoot(), "missing") {
            @Override public boolean mkdirs() { return false; }
        };
        assertThrows(IllegalStateException.class, () -> ensure(absent));
        assertFalse(absent.exists());
    }

    @Test public void fileCreatedAtThatBoundaryDoesNotMasqueradeAsDirectory() throws Exception {
        File actual = new File(temporary.getRoot(), "not-a-directory");
        File interleaving = new File(actual.getPath()) {
            @Override public boolean mkdirs() {
                try { assertTrue(actual.createNewFile()); }
                catch (java.io.IOException error) { throw new AssertionError(error); }
                return false;
            }
        };
        assertThrows(IllegalStateException.class, () -> ensure(interleaving));
        assertTrue(actual.isFile());
    }

    @Test public void existingDirectoryDoesNotAttemptCreation() throws Exception {
        File actual = temporary.newFolder("existing");
        ensure(new File(actual.getPath()) {
            @Override public boolean mkdirs() { throw new AssertionError("Already exists"); }
        });
        assertTrue(actual.isDirectory());
    }

    @Test public void ordinaryCreationStillCreatesTheDirectory() throws Exception {
        File directory = new File(temporary.getRoot(), "ordinary");
        ensure(directory);
        assertTrue(directory.isDirectory());
    }
}
