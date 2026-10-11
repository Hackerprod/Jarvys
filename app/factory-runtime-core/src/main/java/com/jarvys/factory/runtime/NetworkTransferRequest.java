package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import org.json.JSONObject;

/** Immutable, exact-scope HTTP/document transfer. Handles are instance-bound authority, never paths. */
public final class NetworkTransferRequest {
    public final NetworkRequest http;
    public final boolean upload;
    public final String handle;
    public final long offset, length, maxBytes;
    public final int timeoutMs, connectTimeoutMs, readTimeoutMs;

    private NetworkTransferRequest(JSONObject args, boolean upload, ResourceBudgets budgets) throws Exception {
        if (args == null || budgets == null) throw invalid();
        this.upload = upload;
        if (upload) FactoryConfig.exactKeys(args, "url", "method", "headers", "handle", "offset", "length", "maxBytes",
                "connectTimeoutMs", "readTimeoutMs", "timeoutMs", "maxHeaderBytes", "redirect", "maxRedirects", "allowHttpsDowngrade");
        else FactoryConfig.exactKeys(args, "url", "method", "headers", "handle", "offset", "maxBytes", "body", "bodyBase64", "maxRequestBytes",
                "connectTimeoutMs", "readTimeoutMs", "timeoutMs", "maxHeaderBytes", "redirect", "maxRedirects", "allowHttpsDowngrade");
        Object token = args.opt("handle");
        if (!(token instanceof String) || !((String) token).matches("[a-f0-9]{64}")) throw invalid();
        handle = (String) token;
        offset = number(args, "offset", 0, 0, budgets.documents.handleBytes);
        maxBytes = number(args, "maxBytes", upload ? budgets.documents.handleBytes : budgets.documents.handleBytes - offset, 1, budgets.documents.handleBytes);
        length = upload ? number(args, "length", -1, 0, maxBytes) : 0;
        if ((upload ? length : maxBytes) > budgets.documents.handleBytes - offset) throw invalid();
        long timeoutCeiling = Math.min(budgets.documents.leaseMs, 86400000L);
        timeoutMs = (int) number(args, "timeoutMs", Math.min(30000L, timeoutCeiling), 1, timeoutCeiling);
        connectTimeoutMs = (int) number(args, "connectTimeoutMs", Math.min(10000L, timeoutCeiling), 1, timeoutCeiling);
        readTimeoutMs = (int) number(args, "readTimeoutMs", Math.min(10000L, timeoutCeiling), 1, timeoutCeiling);
        JSONObject network = new JSONObject();
        for (String name : new String[]{"url", "method", "headers", "maxHeaderBytes", "redirect", "maxRedirects", "allowHttpsDowngrade"})
            if (args.has(name)) network.put(name, args.get(name));
        if (upload) {
            if (!network.has("method")) network.put("method", "POST");
            // Empty typed body reuses NetworkRequest's body/method safety validation, without payload allocation.
            network.put("bodyBase64", "").put("maxRequestBytes", 0);
            if (!network.has("redirect")) network.put("redirect", "error");
        } else for (String name : new String[]{"body", "bodyBase64", "maxRequestBytes"})
            if (args.has(name)) network.put(name, args.get(name));
        network.put("maxResponseBytes", 0);
        http = NetworkRequest.fromRequest(network);
    }

    public static NetworkTransferRequest upload(JSONObject args, ResourceBudgets budgets) throws FactoryException {
        return parse(args, true, budgets);
    }
    public static NetworkTransferRequest download(JSONObject args, ResourceBudgets budgets) throws FactoryException {
        return parse(args, false, budgets);
    }
    private static NetworkTransferRequest parse(JSONObject args, boolean upload, ResourceBudgets budgets) throws FactoryException {
        try { return new NetworkTransferRequest(args, upload, budgets); }
        catch (FactoryException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }

    /** Bind this entire object in the single operation lease; do not log private headers/URL/body. */
    public JSONObject authorizationDetails() throws FactoryException {
        try {
            JSONObject details = http.authorizationDetails();
            details.remove("probe"); details.remove("responseType"); details.remove("maxResponseBytes");
            details.put("operation", upload ? "network.upload" : "network.download")
                    .put("handle", handle).put("offset", offset).put("maxBytes", maxBytes)
                    .put("timeoutMs", timeoutMs).put("connectTimeoutMs", connectTimeoutMs).put("readTimeoutMs", readTimeoutMs)
                    .put("documentMode", upload ? "read" : "write").put("atomicReplacement", false);
            if (upload) {
                details.remove("bodySha256"); details.remove("bodyPreview"); details.remove("bodyPreviewTruncated");
                details.remove("bodyEncoding"); details.remove("maxRequestBytes");
                details.put("length", length).put("bodyBytes", length).put("bodySource", "document_handle")
                        .put("contentSnapshotBound", false).put("redirectReplayAllowed", false);
            } else details.put("bodySource", "inline");
            return details;
        } catch (Exception failure) { throw new FactoryException("UNAVAILABLE", "Transfer review details unavailable."); }
    }
    private static long number(JSONObject args, String key, long fallback, long minimum, long maximum) throws Exception {
        Object value = args.has(key) ? args.get(key) : Long.valueOf(fallback);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue();
        if (number < minimum || number > maximum) throw invalid();
        return number;
    }
    private static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid typed HTTP transfer arguments or document budget."); }
}
