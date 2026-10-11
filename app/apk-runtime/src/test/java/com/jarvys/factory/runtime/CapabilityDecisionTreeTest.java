package com.jarvys.factory.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class CapabilityDecisionTreeTest {
    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }

    @Test public void exhaustiveBranchExplorationPreservesShortCircuitAndRepeatedReads() throws Exception {
        CapabilityDecisionTree<Boolean> and = CapabilityDecisionTree.explore(c -> c.contains("share") && c.contains("documents"));
        CapabilityDecisionTree<Boolean> or = CapabilityDecisionTree.explore(c -> c.contains("share") || c.contains("documents"));
        CapabilityDecisionTree<Boolean> repeated = CapabilityDecisionTree.explore(c -> c.contains("share") == c.contains("share"));
        assertEquals(3, and.leaves()); assertEquals(3, or.leaves()); assertEquals(2, repeated.leaves());
        for (int mask = 0; mask < 8; mask++) {
            Set<String> selected = set();
            if ((mask & 1) != 0) selected.add("share");
            if ((mask & 2) != 0) selected.add("documents");
            if ((mask & 4) != 0) selected.add("storage");
            assertEquals((mask & 3) == 3, and.outcome(selected));
            assertEquals((mask & 3) != 0, or.outcome(selected));
            assertTrue(repeated.outcome(selected));
        }
    }
    @Test public void newlyReadCapabilityCreatesBranchesRatherThanBeingIgnored() throws Exception {
        CapabilityDecisionTree<Boolean> tree = CapabilityDecisionTree.explore(c -> c.contains("share") ^ c.contains("documents") ^ c.contains("storage"));
        assertEquals(8, tree.leaves());
        for (int mask = 0; mask < 8; mask++) {
            Set<String> selected = set();
            if ((mask & 1) != 0) selected.add("share");
            if ((mask & 2) != 0) selected.add("documents");
            if ((mask & 4) != 0) selected.add("storage");
            assertEquals(Integer.bitCount(mask) % 2 == 1, tree.outcome(selected));
        }
    }
    @Test public void unsupportedAccessAndUnknownMembershipFailInsteadOfProducingCachedSuccess() {
        assertThrows(AssertionError.class, () -> CapabilityDecisionTree.explore(c -> c.contains("not-a-capability")));
        assertThrows(AssertionError.class, () -> CapabilityDecisionTree.explore(c -> c.contains(null)));
        assertThrows(AssertionError.class, () -> CapabilityDecisionTree.explore(Set::size));
        assertThrows(AssertionError.class, () -> CapabilityDecisionTree.explore(c -> c.iterator().hasNext()));
        assertThrows(IllegalStateException.class, () -> CapabilityDecisionTree.explore(c -> { throw new IllegalStateException("fixture"); }));
    }
    @Test public void actualConfigWrapperPreservesOracleAndFixedFields() throws Exception {
        CapabilityDecisionTree<Boolean> tree = CapabilityDecisionTree.explore(c -> {
            FactoryConfig config = CapabilityDecisionTree.config(c);
            assertEquals("com.example.notes", config.appId); assertEquals("Notes", config.name);
            assertEquals("www/index.html", config.entryPoint); assertNull(config.documentBroker);
            assertThrows(UnsupportedOperationException.class, () -> config.capabilities.add("storage"));
            return config.capabilities.contains("storage");
        });
        assertEquals(2, tree.leaves());
        assertFalse(tree.outcome(Collections.emptySet())); assertTrue(tree.outcome(set("storage")));
    }
    @Test public void genuineWireInvalidArgumentsWinBeforeAnyAuthorizationBranch() throws Exception {
        CapabilityDecisionTree<String> tree = CapabilityDecisionTree.explore(c -> {
            try {
                BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                        BridgeProtocolTest.request("storage.get", "{\"key\":\"x\",\"undeclared\":true}"), CapabilityDecisionTree.config(c));
                fail("Invalid request accepted"); return "unreachable";
            } catch (FactoryException rejected) { return rejected.code; }
        });
        assertEquals(1, tree.leaves());
        assertEquals("INVALID_ARGUMENT", tree.outcome(Collections.emptySet()));
        assertEquals("INVALID_ARGUMENT", tree.outcome(set("storage")));
    }
    @Test public void changedOrMissingPinnedSourceFailsClosed() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("matrix-proof-fixture");
        java.nio.file.Path source = root.resolve("fixture.java");
        try {
            java.nio.file.Files.write(source, new byte[0]);
            String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
            CapabilityDecisionTree.requireSource(root, "fixture.java", emptySha);
            java.nio.file.Files.write(source, new byte[]{1});
            assertThrows(AssertionError.class, () -> CapabilityDecisionTree.requireSource(root, "fixture.java", emptySha));
            java.nio.file.Files.delete(source);
            assertThrows(java.io.IOException.class, () -> CapabilityDecisionTree.requireSource(root, "fixture.java", emptySha));
        } finally {
            java.nio.file.Files.deleteIfExists(source); java.nio.file.Files.deleteIfExists(root);
        }
    }
    @Test public void reviewedProductionSourcePerimeterIsPresentAndExact() throws Exception {
        CapabilityDecisionTree.requireReviewedProductionSources();
    }
}
