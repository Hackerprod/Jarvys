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
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BridgeProtocol.java", "25dccbabd20ac6f7480bbac80f31d3732606c52ae4af5c37781232a8eafc5ae8");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/FactoryConfig.java", "13ed6986b7c8a659367664ec6145762f12e8374f5b5526aa99fffa4958ebd104");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StrictJson.java", "02e6ad92f28332fca3ea2466d435d5da3944cbdb55b06488b7fa18105853034e");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/FactoryException.java", "03cc2dd0ce63975d9ba31e813df29bd08b6a6834cdef28be6f220a33f2a98331");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BiometricAuthentication.java", "58bde0dd6fb3741a6a5adac9fe53039f5b0ae9eb2cf947969c086fb1a74e86a4");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SensorCapture.java", "3fb36bb7f3efdaae45bbd6befe80e41e57b52456863df0bb185e170bf042983c");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/FactoryPresentation.java", "3e9e6c36b3703f3e753f6864935626389b70a17ea2969997133eed28e078d238");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/DatabaseBackupRequest.java", "8ff28b9394e8cb799cb87878cc599394b6217dc75cef85e6bcf5975c59653714");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StorageBackupRequest.java", "a2876f9cc78fbd4c677bc758ba0d8e12843efa6a2b9f427ca4fa63b180707982");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/DatabaseRequest.java", "cccaa19b1119054866ca84d33e57a94b669bff6d9f23fee3e3463bf71620d735");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/ExternalLaunchRequest.java", "053527bf71f2eba9103de06b28b4ac3e837af9e26d8b474c1aceb891b969ee5e");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/ContactPickRequest.java", "1decc696fa1407d15ca0b6112f21f21866a2970026974284e1f4d357b97937a6");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NetworkRequest.java", "ad1adb9311818b3e474381e4522d33b63558b3e383b35d56022e09b4e5451c28");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NetworkPingRequest.java", "ee37da36764608e4178573cdb46571b03eac7fc3414b2faeec7c00ec97989582");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationRequest.java", "6586a0454209579f1a4fef456cb41e2e951d3b63e1dfc0321d726a5a76725a87");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SpeechRequest.java", "6499ebef49a41983e92f16f2a8d78e10db7f880ba41a83db21fa514a6815bdaf");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/BrowserUrl.java", "d617912e9365b7b525cb8fddf5187f26f3db1dbfc42bf979bc328bbdbd58c8f3");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/CapabilityCatalog.java", "8236bad337851b32a949ecd1d55254bfd3097d8b9478b0a0dc3082babffd51df");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ContactPickSpec.java", "ffac2ad67a7d0f71c5dc7582d4e571a7755e921995e0f69389abf0fa883c3706");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ExternalLaunchSpec.java", "cfd9edef4ca7dff114597553c812a869c77e3fcdeca11c23b1c8e3a16ac35c1c");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestAudit.java", "f57e086e96b8bf0bcfe75ef6dd6094b0648292154a8b705b248089b05c82fe89");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestNodes.java", "36b85fadf92437039a001d0d18889084d2584381bc6015eb5df1a0b223654de6");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestPlan.java", "d0acacbdaf20728a5ef8281bf3f69b356aeb11591919d9b52a94592126451e5d");
        requireSource(root, "factory-contract/src/main/java/com/jarvys/factory/contract/ManifestXml.java", "1881c6578152731a6b318b4540bfe89452c3d7d1ae317b02b89a5f1d8c2b29cd");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BleRequest.java", "b20849ed56031274f64eb5014badf20e5a5137fecfc0cadaa25bf1d3367b5005");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BleBytes.java", "5cf70580b052bc7b91864c01cb231ffdd0a57d1a0fb2c33aca4e3bf5b606f631");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/BleCentral.java", "6e397d0cb7454586e61a77569f1382cb95c131a769ca5b19eaaacdbd9552265c");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NativeHttpJobs.java", "ce89cb26cb89c5a994bac9e39dfa470e90577fcbc2f9939e90c8ae8231124968");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/HttpJobRequest.java", "05d03d78772f15d9498821f7c15fb7e2638feace038eb52fc551a9fb16d03094");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NfcRequest.java", "bde39be4a4c34bfd854c821c3de43ca215bd7f69b163cf388cc6a19088d1b111");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NativeWidgetEffects.java", "d0e92e35b4d9d3af95e561df4d4073b37a4049d0b8974068335d02de6869f230");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/WidgetModel.java", "87411dd266bfbe7c097733643e2095472199e141bc5b77a47ef3bf4f088fc221");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/WidgetRequest.java", "0549a602591e21c7e89e03ce063c912fad19d7ab1b00d00d19579b8b4a1e484b");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/WidgetJson.java", "d5d57e44f8873ade4d5429e8ab8d7a18f3e6fd6ce3752bf04728faf8fc5b94e4");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/WidgetLedger.java", "f8ae48cc05ba84e7ea4911cb734217b55cc7d7ad3fcb394e2e35ab4968368484");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationActionEvents.java", "3816bc151bdba46697ff3582cc5ba31ab884cc76a4ad42c984272ed720850369");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SecretEffects.java", "5260e43ef7af3472ed8df984751bbc9a51bae0c4ed9c2019eb392387ca5a0f4f");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SecretRequest.java", "c9e940489fc2e1a4518ed8db6a095c5fd1d218aa2b2776f929172142e850e0a0");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/IncomingShareRequest.java", "48faea4172a760f5fc63c7e143a35dcfdd32f4db78f12a62b9e8a1c5139ba53f");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NetworkTransferRequest.java", "f80f4db8d0306f8dd0d35cc79fc5a08afbc58c92d38844e5a5a6eebf5028f41e");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/VoiceRecognitionRequest.java", "62e3af180eec91903927a8e89db58b80f6d18f810c1740eaafa288e28ae180b1");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/LocationRequest.java", "decc183771a28ec6a1456e9879420bcaaeb25c77f21a5edeaab9124aca642ccb");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/RecordingRequest.java", "777d53b458d7566fd4c5a68fc9da807bee8aa6ed61d0e517e7e6dbf5fccd7b4d");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationScheduleRequest.java", "ae5e276a1233ffdb122c1ecec16812952a6284c05d6411542f0a04faa735f590");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/NotificationScheduleControl.java", "8c339f9108af0f8f5dfbd87b66608409af766145fa66233ab81f457f735ceb7a");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/SensorSubscriptionRequest.java", "55298314f715459a99a6e21f3fe50f5a466c39190261f282fcc5669371e6152f");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StepsRequest.java", "a357a64117701b13ab9fc0319429114f1842b2e0b1916f2afcb28e516177c61b");
        requireSource(root, "factory-runtime-core/src/main/java/com/jarvys/factory/runtime/StorageRequest.java", "d946ecebd0c15cee4cc75aac7003d060f6c71abf708ee794e22e9f1f75b8fb94");
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
        requireSource(root, "build.gradle.kts", "4ef83b5c2a876e53c715a576b7e5e481b73f00f2ac3a8c0cee3ab91abfb27352");
        requireSource(root, "settings.gradle.kts", "1aa8b2e30baccbe752194278a7ec3d74b0b13c7d556033587e84e7b17afd72a5");
    }
    static void requireSource(Path root, String filename, String expected) throws Exception {
        byte[] bytes = Files.readAllBytes(root.resolve(filename));
        StringBuilder actual = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) actual.append(String.format("%02x", value & 255));
        if (!expected.equals(actual.toString())) throw new AssertionError(filename + " changed: independently re-review the capability factoring proof");
    }
}
