package com.jarvys.factory.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewProfilesTest {
    private static final class Provider implements PreviewProfiles.Provider {
        final List<String> names = new ArrayList<>(), deleted = new ArrayList<>(), events = new ArrayList<>();
        boolean success = true, throwsDelete, throwsList;
        public List<String> names() { if (throwsList) throw new IllegalStateException(); return new ArrayList<>(names); }
        public boolean delete(String name) {
            events.add("delete"); deleted.add(name);
            if (throwsDelete) throw new IllegalStateException();
            if (success) names.remove(name);
            return success;
        }
    }
    @Test public void cleanupDeletesOwnedOrphansButNeverForeignDefaultOrLive() {
        PreviewProfiles profiles = new PreviewProfiles(); Provider provider = new Provider();
        String live = profiles.reserve(), orphan = new PreviewProfiles().reserve();
        provider.names.addAll(Arrays.asList(live, orphan, "Default", "foreign", PreviewProfiles.PREFIX + "not-a-uuid"));
        assertTrue(profiles.cleanup(provider)); assertEquals(Arrays.asList(orphan), provider.deleted);
        assertTrue(provider.names.contains(live));
    }
    @Test public void retireDestroysBeforeDeleteAndFalseResultRetriesOnNextCleanup() {
        PreviewProfiles profiles = new PreviewProfiles(); Provider provider = new Provider();
        String name = profiles.reserve(); provider.names.add(name); provider.success = false;
        assertFalse(profiles.retire(name, () -> provider.events.add("destroy"), provider));
        assertEquals(Arrays.asList("destroy", "delete"), provider.events);
        provider.success = true; assertTrue(profiles.cleanup(provider)); assertFalse(provider.names.contains(name));
    }
    @Test public void failedDestroyKeepsLeaseLiveAndNeverDeletes() {
        PreviewProfiles profiles = new PreviewProfiles(); Provider provider = new Provider();
        String name = profiles.reserve(); provider.names.add(name);
        assertFalse(profiles.retire(name, () -> { throw new IllegalStateException(); }, provider));
        assertTrue(profiles.cleanup(provider)); assertTrue(provider.deleted.isEmpty());
    }
    @Test public void failedDeleteAndListAreBoundedFailuresWithoutForeignFallback() {
        PreviewProfiles profiles = new PreviewProfiles(); Provider provider = new Provider();
        String name = profiles.reserve(); provider.names.add(name); provider.throwsDelete = true;
        assertFalse(profiles.retire(name, () -> {}, provider));
        assertFalse(profiles.cleanup(provider)); assertEquals(Arrays.asList(name, name), provider.deleted);
        provider.throwsList = true; assertFalse(profiles.cleanup(provider)); assertEquals(2, provider.deleted.size());
    }
    @Test public void reservedNamesAreUniqueAndExactOwnedFormat() {
        PreviewProfiles profiles = new PreviewProfiles(); String a = profiles.reserve(), b = profiles.reserve();
        assertNotEquals(a, b); assertTrue(PreviewProfiles.owned(a)); assertFalse(PreviewProfiles.owned(null));
        assertFalse(PreviewProfiles.owned(a + "/other"));
    }
}
