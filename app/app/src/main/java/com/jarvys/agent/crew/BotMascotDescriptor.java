package com.jarvys.agent.crew;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable presentation metadata. This is not authority to publish a file or change a bot's tools. */
public final class BotMascotDescriptor {
    public static final String CONTRACT = "bot-mascot-v1";
    public static final String ARTBOARD = "Mascot";
    public static final String STATE_MACHINE = "MascotController";
    public static final String VIEW_MODEL = "MascotState";
    public static final String VALIDATION_LEVEL = "LOCAL_COMPILED";
    /** Stable wire values, independent of any UI enum ordinal. */
    public static final List<String> MODES = Collections.unmodifiableList(Arrays.asList(
            "Idle", "Thinking", "Working", "Queued", "WaitingProvider", "WaitingUser", "Done", "Error", "Interrupted"));
    public static final int MAX_RECEIPTS = 64;
    public static final int MAX_DESCRIPTION_CHARS = 1200;
    private static final String REF_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    public final String visualDescription;
    public final String packageRef;
    public final String sourceHash;
    public final String assetHash;
    public final String contract;
    public final String artboard;
    public final String stateMachine;
    public final String viewModel;
    public final String validationLevel;

    public BotMascotDescriptor(String packageRef, String sourceHash, String assetHash, String visualDescription) {
        this(packageRef, sourceHash, assetHash, visualDescription, CONTRACT, ARTBOARD, STATE_MACHINE, VIEW_MODEL, VALIDATION_LEVEL);
    }

    private BotMascotDescriptor(String packageRef, String sourceHash, String assetHash, String visualDescription, String contract,
            String artboard, String stateMachine, String viewModel, String validationLevel) {
        requireVisualDescription(visualDescription);
        requirePackageRef(packageRef);
        requireHash(sourceHash);
        requireHash(assetHash);
        if (!CONTRACT.equals(contract) || !ARTBOARD.equals(artboard) || !STATE_MACHINE.equals(stateMachine)
                || !VIEW_MODEL.equals(viewModel) || !VALIDATION_LEVEL.equals(validationLevel)) {
            throw CrewProfile.invalid("unsupported mascot contract or validation level");
        }
        this.visualDescription = visualDescription;
        this.packageRef = packageRef;
        this.sourceHash = sourceHash;
        this.assetHash = assetHash;
        this.contract = contract;
        this.artboard = artboard;
        this.stateMachine = stateMachine;
        this.viewModel = viewModel;
        this.validationLevel = validationLevel;
    }

    public static void requireVisualDescription(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > MAX_DESCRIPTION_CHARS)
            throw CrewProfile.invalid("mascot visual description must contain 1 to 1200 characters");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) throw CrewProfile.invalid("mascot visual description cannot contain control characters");
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw CrewProfile.invalid("mascot visual description contains invalid text");
            } else if (Character.isLowSurrogate(c)) throw CrewProfile.invalid("mascot visual description contains invalid text");
        }
    }
    public static void requirePackageRef(String value) {
        if (value == null || !value.matches(REF_PATTERN)) throw CrewProfile.invalid("invalid private mascot package reference");
    }
    public static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw CrewProfile.invalid("invalid mascot SHA-256");
    }
    public static void requireOperationId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) throw CrewProfile.invalid("invalid mascot operation identifier");
    }

    public JSONObject toJson() {
        try {
            JSONObject modes = new JSONObject();
            for (int i = 0; i < MODES.size(); i++) modes.put(Integer.toString(i), MODES.get(i));
            return new JSONObject().put("contract", contract).put("visualDescription", visualDescription).put("packageRef", packageRef)
                    .put("sourceHash", sourceHash).put("assetHash", assetHash).put("artboard", artboard)
                    .put("stateMachine", stateMachine).put("viewModel", viewModel).put("validationLevel", validationLevel)
                    .put("bindings", new JSONObject().put("mode", "number").put("reducedMotion", "boolean"))
                    .put("modes", modes);
        } catch (JSONException error) { throw CrewProfile.invalid("could not encode mascot descriptor", error); }
    }

    public static BotMascotDescriptor fromJson(JSONObject value) {
        CrewProfile.exactFields(value, "contract", "visualDescription", "packageRef", "sourceHash", "assetHash", "artboard",
                "stateMachine", "viewModel", "validationLevel", "bindings", "modes");
        Object rawBindings = value.opt("bindings"), rawModes = value.opt("modes");
        if (!(rawBindings instanceof JSONObject) || !(rawModes instanceof JSONObject)) throw CrewProfile.invalid("invalid mascot bindings");
        JSONObject bindings = (JSONObject) rawBindings, modes = (JSONObject) rawModes;
        CrewProfile.exactFields(bindings, "mode", "reducedMotion");
        if (!"number".equals(bindings.opt("mode")) || !"boolean".equals(bindings.opt("reducedMotion"))
                || modes.length() != MODES.size()) throw CrewProfile.invalid("invalid mascot bindings");
        for (int i = 0; i < MODES.size(); i++) {
            if (!MODES.get(i).equals(modes.opt(Integer.toString(i)))) throw CrewProfile.invalid("invalid mascot mode mapping");
        }
        return new BotMascotDescriptor(text(value, "packageRef"), text(value, "sourceHash"), text(value, "assetHash"),
                text(value, "visualDescription"), text(value, "contract"), text(value, "artboard"), text(value, "stateMachine"),
                text(value, "viewModel"), text(value, "validationLevel"));
    }

    private static String text(JSONObject value, String name) {
        Object field = value.opt(name);
        if (!(field instanceof String)) throw CrewProfile.invalid("invalid mascot descriptor field");
        return (String) field;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof BotMascotDescriptor)) return false;
        BotMascotDescriptor value = (BotMascotDescriptor) other;
        return visualDescription.equals(value.visualDescription) && packageRef.equals(value.packageRef) && sourceHash.equals(value.sourceHash) && assetHash.equals(value.assetHash)
                && contract.equals(value.contract) && validationLevel.equals(value.validationLevel);
    }
    @Override public int hashCode() { return Objects.hash(packageRef, sourceHash, assetHash, visualDescription, contract, validationLevel); }

    /** Durable assignment result retained even after later presentation or profile edits. */
    public static final class Receipt {
        public final String operationId;
        public final String payloadHash;
        public final int assignedRevision;
        public final BotMascotDescriptor mascot;

        public Receipt(String operationId, String payloadHash, int assignedRevision, BotMascotDescriptor mascot) {
            requireOperationId(operationId);
            requireHash(payloadHash);
            if (assignedRevision < 2 || mascot == null) throw CrewProfile.invalid("invalid mascot assignment receipt");
            this.operationId = operationId;
            this.payloadHash = payloadHash;
            this.assignedRevision = assignedRevision;
            this.mascot = mascot;
        }
        JSONObject toJson() {
            try { return new JSONObject().put("operationId", operationId).put("payloadHash", payloadHash)
                    .put("assignedRevision", assignedRevision).put("mascot", mascot.toJson()); }
            catch (JSONException error) { throw CrewProfile.invalid("could not encode mascot receipt", error); }
        }
        static Receipt fromJson(JSONObject value) {
            CrewProfile.exactFields(value, "operationId", "payloadHash", "assignedRevision", "mascot");
            if (!(value.opt("mascot") instanceof JSONObject)) throw CrewProfile.invalid("invalid mascot receipt");
            return new Receipt(text(value, "operationId"), text(value, "payloadHash"),
                    CrewProfile.positiveInteger(value, "assignedRevision"), BotMascotDescriptor.fromJson((JSONObject) value.opt("mascot")));
        }
    }
}
