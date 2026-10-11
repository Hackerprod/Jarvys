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
     * Proof perimeter: capability-dependent decisions read membership, never Set identity or
     * class, escape, or capability-derived hidden state. Validators also read immutable
     * config.resources: this proof fixes each argument fixture and ResourceBudgets.legacy().
     * It does not exhaust numeric budgets or the argument language. FactoryConfig derives no
     * hidden state from the Set. The oracle alone cannot detect getClass/identity tests or a future derived field.
     * The parser/helper closure and dependency declarations are pinned too: wire evaluation must
     * have deterministic outcomes without executing native effects or reading ambient state.
     * Unused JVM class initialization is not a zero-allocation claim. ANY pinned source edit fails closed until the
     * affected portion of this proof is independently re-reviewed.
     * Do not regenerate these pins as a build step or merely update them to make a test pass.
     */
    static void requireReviewedProductionSources() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BridgeProtocol.java")))
            root = root.getParent();
        if (root == null) throw new AssertionError("Reviewed production source is required for the compositional proof");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BridgeProtocol.java", "14bfb1fac103ac378ed697f4985f5b02c97fd1eb1e54255d2a356483c005ce12");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/FactoryConfig.java", "f70cb304ec638786ae84a1d2519f520015e0ea5ac6e50eb9a6be5f8914baa731");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StrictJson.java", "02e6ad92f28332fca3ea2466d435d5da3944cbdb55b06488b7fa18105853034e");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/FactoryException.java", "03cc2dd0ce63975d9ba31e813df29bd08b6a6834cdef28be6f220a33f2a98331");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BiometricAuthentication.java", "58bde0dd6fb3741a6a5adac9fe53039f5b0ae9eb2cf947969c086fb1a74e86a4");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SensorCapture.java", "3fb36bb7f3efdaae45bbd6befe80e41e57b52456863df0bb185e170bf042983c");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/FactoryPresentation.java", "3e9e6c36b3703f3e753f6864935626389b70a17ea2969997133eed28e078d238");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/DatabaseBackupRequest.java", "00d1c0fc2a94591820deb812d1b1fccf33c88524ce1b27e44b88ca5bd6ae2523");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StorageBackupRequest.java", "dbe6e03d3afc90000a0e994bf17fe1e1a6a0f8afa2c0e8b285acc22ee635f9fd");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/DatabaseRequest.java", "66bfe160d7739fbb345b4d0c1c94df4b037e518256c640a473c0cd3441b0bdb7");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/ExternalLaunchRequest.java", "053527bf71f2eba9103de06b28b4ac3e837af9e26d8b474c1aceb891b969ee5e");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/ContactPickRequest.java", "1decc696fa1407d15ca0b6112f21f21866a2970026974284e1f4d357b97937a6");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NetworkRequest.java", "4481ad15ae155681588cfceb75fa0464bbc891a1ea5ab362410817ccfab9b5ba");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NetworkPingRequest.java", "e1ec4c2265e1aacd7edfb0fe8d4716b1902ce5ef90f69e83f3a61af2d3f3106a");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationRequest.java", "296679b0e054f75653105b11b0cec5dd6b5c3b2701a77eadaf0083c2b3c8579d");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SpeechRequest.java", "31933b6f416f70fd3b0a20e2677a219c7f0f815e1d727ec44ee4dbe9e586b696");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/BrowserUrl.java", "d617912e9365b7b525cb8fddf5187f26f3db1dbfc42bf979bc328bbdbd58c8f3");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/CapabilityCatalog.java", "0a26af3d37ba5d14462e1ec430bb17b7684946dc2e114e9bcec994f0a37d6be2");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ContactPickSpec.java", "ffac2ad67a7d0f71c5dc7582d4e571a7755e921995e0f69389abf0fa883c3706");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ExternalLaunchSpec.java", "cfd9edef4ca7dff114597553c812a869c77e3fcdeca11c23b1c8e3a16ac35c1c");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestAudit.java", "f57e086e96b8bf0bcfe75ef6dd6094b0648292154a8b705b248089b05c82fe89");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestNodes.java", "36b85fadf92437039a001d0d18889084d2584381bc6015eb5df1a0b223654de6");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestPlan.java", "1bdcc4ee98a5035fb46e43f61d082a27089678285ded46386eb4ed644c1e83a5");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestXml.java", "1881c6578152731a6b318b4540bfe89452c3d7d1ae317b02b89a5f1d8c2b29cd");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BleRequest.java", "9a82aac33309149b36c3ef01e309d431af612fea1670f962b1152e94153a5e97");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BleBytes.java", "5cf70580b052bc7b91864c01cb231ffdd0a57d1a0fb2c33aca4e3bf5b606f631");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BleCentral.java", "6e397d0cb7454586e61a77569f1382cb95c131a769ca5b19eaaacdbd9552265c");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NativeHttpJobs.java", "ce89cb26cb89c5a994bac9e39dfa470e90577fcbc2f9939e90c8ae8231124968");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/HttpJobRequest.java", "db0ed44b301b11df8cbb755acaf4336ce55e34756f1157bdcd3d5db3e9aa378c");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NfcRequest.java", "d5d4537af0a9fc2c7e36509dc361cf53e76e73fb540066d7c1475166dd6e3b4d");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NativeWidgetEffects.java", "66a7eddbe133c77c9a68fb00d1217a698b3c00c00607e37c92cb282d4893a602");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/WidgetModel.java", "ca76d85659970495c044c4c5790ca612909dd8a147cb6b5b9fa5dc5663025f07");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/WidgetRequest.java", "cbb2e62c2e9531f77abd7ad250e2a627f4ff3d7a6a3c1765cb6428f33b74b80e");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/WidgetJson.java", "d5d57e44f8873ade4d5429e8ab8d7a18f3e6fd6ce3752bf04728faf8fc5b94e4");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/WidgetLedger.java", "f8ae48cc05ba84e7ea4911cb734217b55cc7d7ad3fcb394e2e35ab4968368484");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationActionEvents.java", "3816bc151bdba46697ff3582cc5ba31ab884cc76a4ad42c984272ed720850369");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SecretEffects.java", "4a1fe9e7984ed7cd3ac157182a0d194fdad7310545d687412829e5048d5f889b");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SecretRequest.java", "c9f679baa89a62f6661c356b78573a435b71fa812bbbfddd9443d1a46b34f17e");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/IncomingShareRequest.java", "3200685a961ae6c6ec22ac10f3417035c0e3da6326b69f021f1e63b5bb74d316");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NetworkTransferRequest.java", "0a175c8f641cf45d3146374717a583f67239b8af13e5f3523c200a428487db15");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/VoiceRecognitionRequest.java", "e2264591129fa62104377898752dcee356c8fdab95b8994139e183c6ca25b967");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/LocationRequest.java", "c0671f64b9adbca0f6a27314aa0021d362d8befa956da4a86d039eb0f08cea56");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/RecordingRequest.java", "8d8a42e5ce8bd16ce619feaaa380a9ada6d4a4b52a7288aef0d2f3905ca9fa09");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationScheduleRequest.java", "956436120e18ce761ca95c95a21605b0e8494585e2597dadd214b8a209e27884");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationScheduleControl.java", "9243993a81ecc57786ed664de42a4bad1e7d4f8b10f8a25de9b3602b9700bf22");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SensorSubscriptionRequest.java", "55298314f715459a99a6e21f3fe50f5a466c39190261f282fcc5669371e6152f");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StepsRequest.java", "c4402cdcad798653d622a8d43555da8a2ee5bbf13a7a22d5e2bf8a008873ab2f");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StorageRequest.java", "930b2bbd93ed6c33115c39a30ae9fa958e0310ddb4f1c02469da2ca5f6ebf82d");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BackupJson.java", "189cedd42f38de3c4df03ffbaf56dea45dad3efe539bbad9c04767558083fa90");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/ResourceBudgetsJson.java", "33e619e19ccf82473ce4d7a70b9dcc0f9f514496c2f7275581b470473f4b564d");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/IncomingSharePolicyJson.java", "03138b099f4e4338b8d3f826b1ee5d25fdaa679a72783737d9e8170af12d18d9");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationPolicyJson.java", "832785a9d69d0e77354bd4b4fd283f0d72e915921b4b435168cfe1bbb47f4303");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BluetoothPolicyJson.java", "b6433fd1bdb2a005af28b346a30af17f1a594b7f1992e23357fa4d570cf9065b");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ResourceBudgets.java", "a947404586b7d513cbf9e45bfcf436f6f375f7c29827cd6671f62d745652644c");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/BluetoothPolicy.java", "8c2723046967723001946522a370ad3254d4942846b9e5df1a314c20db52a2fa");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/F3ManifestPolicy.java", "dfea09a9b0054f161d15706bbee13165e5ee864ffbfb13233f52ba4fe14b30a7");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/IncomingSharePolicy.java", "45e8a6859c5a0545591754a7e1bfa70399a3f8072251ae095b1b2bc97eb4be6f");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/NotificationPolicy.java", "ccf2a64d0df7b3654e67fb1c111f6070fd94f5dd26c62287cca733a85448fa3f");
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
