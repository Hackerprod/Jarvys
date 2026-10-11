package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Test-only exhaustive execution over a finite membership oracle, never a sampled matrix. */
final class CapabilityDecisionTree<T> {
    interface Evaluation<T> { T evaluate(Set<String> capabilities) throws Exception; }
    private final String decision;
    private final T outcome;
    private final CapabilityDecisionTree<T> absent, present;

    private CapabilityDecisionTree(T outcome) {
        this.decision = null; this.outcome = outcome; this.absent = null; this.present = null;
    }
    private CapabilityDecisionTree(String decision, CapabilityDecisionTree<T> absent, CapabilityDecisionTree<T> present) {
        this.decision = decision; this.outcome = null; this.absent = absent; this.present = present;
    }
    static <T> CapabilityDecisionTree<T> explore(Evaluation<T> evaluation) throws Exception {
        return explore(evaluation, new LinkedHashMap<String, Boolean>());
    }
    private static <T> CapabilityDecisionTree<T> explore(Evaluation<T> evaluation, Map<String, Boolean> assignments) throws Exception {
        try { return new CapabilityDecisionTree<>(evaluation.evaluate(new MembershipOracle(assignments))); }
        catch (NeedDecision next) {
            if (assignments.containsKey(next.capability) || assignments.size() >= CapabilityCatalog.NAMES.size())
                throw new AssertionError("Invalid or unbounded decision path");
            Map<String, Boolean> branch = new LinkedHashMap<>(assignments);
            branch.put(next.capability, false);
            CapabilityDecisionTree<T> absent = explore(evaluation, branch);
            branch.put(next.capability, true);
            return new CapabilityDecisionTree<>(next.capability, absent, explore(evaluation, branch));
        }
    }
    T outcome(Set<String> capabilities) {
        CapabilityDecisionTree<T> node = this;
        while (node.decision != null) node = capabilities.contains(node.decision) ? node.present : node.absent;
        return node.outcome;
    }
    int leaves() { return decision == null ? 1 : absent.leaves() + present.leaves(); }

    private static final class NeedDecision extends Error {
        final String capability;
        NeedDecision(String capability) { super(null, null, false, false); this.capability = capability; }
    }
    private static final class MembershipOracle extends AbstractSet<String> {
        private final Map<String, Boolean> assignments;
        MembershipOracle(Map<String, Boolean> assignments) {
            this.assignments = Collections.unmodifiableMap(new LinkedHashMap<>(assignments));
        }
        @Override public boolean contains(Object capability) {
            if (!(capability instanceof String) || !CapabilityCatalog.SUPPORTED.contains(capability))
                throw new AssertionError("Unknown capability queried: " + capability);
            Boolean answer = assignments.get(capability);
            if (answer == null) throw new NeedDecision((String) capability);
            return answer;
        }
        @Override public Iterator<String> iterator() { throw new AssertionError("Only capability membership is factored"); }
        @Override public int size() { throw new AssertionError("Only capability membership is factored"); }
    }

    /**
     * All non-capability fields equal the original matrix's real parsed configuration. The
     * private constructor wraps the oracle, without mutating production final fields. Actual
     * configurations are still parsed for every subset in the caller, not synthesized here.
     */
    static FactoryConfig config(Set<String> capabilities) throws Exception {
        Constructor<FactoryConfig> constructor = FactoryConfig.class.getDeclaredConstructor(
                String.class, String.class, String.class, Set.class, FactoryConfig.DocumentBroker.class);
        constructor.setAccessible(true);
        return constructor.newInstance("com.example.notes", "Notes", "www/index.html", capabilities, null);
    }

    /**
     * Proof perimeter: reviewed validate reads config only through membership, never identity,
     * other fields, escape or mutable global state. FactoryConfig derives no hidden state from
     * the Set. The oracle alone cannot detect getClass/identity tests or a future derived field.
     * The parser/helper closure and dependency declarations are pinned too: wire evaluation must
     * remain deterministic and side-effect-free. ANY pinned source edit fails closed until the
     * affected portion of this proof is independently re-reviewed.
     * Do not regenerate these pins as a build step or merely update them to make a test pass.
     */
    static void requireReviewedProductionSources() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BridgeProtocol.java")))
            root = root.getParent();
        if (root == null) throw new AssertionError("Reviewed production source is required for the compositional proof");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BridgeProtocol.java", "a25efc79c4f9a3b67216ad620a0ae1688f904a5290f476d5c041f6a9a4a0822c");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/FactoryConfig.java", "f9d9969fe25283a7078e7a938829b0eacbdc1f52098f9b6723fab9a73cff8af0");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StrictJson.java", "4dcf0e05a36b8c65c388aac61cd7b48e60e9ddc3a077e0bba801da8697fe2456");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/FactoryException.java", "03cc2dd0ce63975d9ba31e813df29bd08b6a6834cdef28be6f220a33f2a98331");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BiometricAuthentication.java", "58bde0dd6fb3741a6a5adac9fe53039f5b0ae9eb2cf947969c086fb1a74e86a4");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SensorCapture.java", "fc483b80bba74fd4226ae44ca499525553d55f3f601e08389d77944e7a4d3b7a");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/FactoryPresentation.java", "3e9e6c36b3703f3e753f6864935626389b70a17ea2969997133eed28e078d238");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/DatabaseBackupRequest.java", "00d1c0fc2a94591820deb812d1b1fccf33c88524ce1b27e44b88ca5bd6ae2523");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StorageBackupRequest.java", "dbe6e03d3afc90000a0e994bf17fe1e1a6a0f8afa2c0e8b285acc22ee635f9fd");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/DatabaseRequest.java", "340f56f5c22bb2b9a94f884b51b7e5caa494ed1b4246b6e44d85c87bd68b6680");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/ExternalLaunchRequest.java", "053527bf71f2eba9103de06b28b4ac3e837af9e26d8b474c1aceb891b969ee5e");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/ContactPickRequest.java", "1decc696fa1407d15ca0b6112f21f21866a2970026974284e1f4d357b97937a6");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NetworkRequest.java", "4481ad15ae155681588cfceb75fa0464bbc891a1ea5ab362410817ccfab9b5ba");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NetworkPingRequest.java", "e1ec4c2265e1aacd7edfb0fe8d4716b1902ce5ef90f69e83f3a61af2d3f3106a");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationRequest.java", "296679b0e054f75653105b11b0cec5dd6b5c3b2701a77eadaf0083c2b3c8579d");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SpeechRequest.java", "31933b6f416f70fd3b0a20e2677a219c7f0f815e1d727ec44ee4dbe9e586b696");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/BrowserUrl.java", "d617912e9365b7b525cb8fddf5187f26f3db1dbfc42bf979bc328bbdbd58c8f3");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/CapabilityCatalog.java", "c405346f65beb3198c02ea52cbbe864f109e048dc8b339458449c4e71337bb51");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ContactPickSpec.java", "ffac2ad67a7d0f71c5dc7582d4e571a7755e921995e0f69389abf0fa883c3706");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ExternalLaunchSpec.java", "cfd9edef4ca7dff114597553c812a869c77e3fcdeca11c23b1c8e3a16ac35c1c");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestAudit.java", "f57e086e96b8bf0bcfe75ef6dd6094b0648292154a8b705b248089b05c82fe89");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestNodes.java", "3f3aa7d6636fcb15414cb3ceeac8a1d1cfb8644c0e5e3cee40c814979e2a5f72");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestPlan.java", "60a56323c9c2853d8f52545b9f12d4e02223df6374090deca8ea0e0a3e90a1dc");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestXml.java", "1881c6578152731a6b318b4540bfe89452c3d7d1ae317b02b89a5f1d8c2b29cd");
        requireSource(root, "apk-runtime/build.gradle.kts", "a335e8c57225efc4393cc9e56fce5a92e5e17863b511b375c2f713a0bb1af03e");
        requireSource(root, "factory-runtime-core/build.gradle.kts", "95b84469bb9b88fdb3d7a93fab28c74513dd270a9f6d5e5f2aa5dc3825f353fc");
        requireSource(root, "factory-contract/build.gradle.kts", "7236bf6f1e86d471cbbc92759b60f519fb9aed5518fa5dbf3aa73407028e5e23");
        requireSource(root, "build.gradle.kts", "b7e8343ad7c348e87a1dbdcb0736e92416771bdb70490acd5f543249beba8a70");
        requireSource(root, "settings.gradle.kts", "1aa8b2e30baccbe752194278a7ec3d74b0b13c7d556033587e84e7b17afd72a5");
    }
    static void requireSource(Path root, String filename, String expected) throws Exception {
        byte[] bytes = Files.readAllBytes(root.resolve(filename));
        StringBuilder actual = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) actual.append(String.format("%02x", value & 255));
        if (!expected.equals(actual.toString())) throw new AssertionError(filename + " changed: independently re-review the capability factoring proof");
    }
}
