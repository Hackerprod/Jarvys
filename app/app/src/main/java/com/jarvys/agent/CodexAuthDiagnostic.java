package com.jarvys.agent;

import android.os.NetworkOnMainThreadException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.json.JSONObject;

/** Allowlisted authentication metadata. Never retains response bodies, URLs, or exception messages. */
public final class CodexAuthDiagnostic {
    private static final Set<String> OAUTH_CODES = new HashSet<>(Arrays.asList(
            "invalid_request", "invalid_client", "invalid_grant", "unauthorized_client",
            "unsupported_grant_type", "invalid_scope", "access_denied", "authorization_pending",
            "slow_down", "expired_token", "server_error", "temporarily_unavailable",
            "unsupported_response_type", "login_required", "consent_required", "interaction_required"));
    private static final Set<String> VALIDATION_REASONS = new HashSet<>(Arrays.asList(
            "missing_access_token", "missing_refresh_token", "missing_account", "invalid_expiry",
            "invalid_device_response", "invalid_json", "oversized_response", "invalid_callback",
            "state_mismatch", "denied", "expired", "interrupted", "readback_failed", "before_body_dns"));

    public enum Stage {
        BROWSER_LAUNCH("browser_launch"), CALLBACK("callback"), REQUEST_CODE("request_code"),
        POLL_APPROVAL("poll_approval"), TOKEN_EXCHANGE("token_exchange"), TOKEN_RESPONSE("token_response"),
        SAVE_SESSION("save_session"), REFRESH("refresh"), UNKNOWN("unknown");
        final String label;
        Stage(String label) { this.label = label; }
    }

    public final Stage stage;
    public final String category;
    public final int httpStatus;
    public final String providerCode;
    public final String exceptionType;
    public final String reason;

    private CodexAuthDiagnostic(Stage stage, String category, int httpStatus, String providerCode,
                                String exceptionType, String reason) {
        this.stage = stage == null ? Stage.UNKNOWN : stage;
        this.category = category;
        this.httpStatus = httpStatus >= 100 && httpStatus <= 599 ? httpStatus : 0;
        this.providerCode = OAUTH_CODES.contains(providerCode) ? providerCode : "";
        this.exceptionType = exceptionType == null ? "" : exceptionType;
        this.reason = VALIDATION_REASONS.contains(reason) ? reason : "";
    }

    public static CodexAuthDiagnostic http(Stage stage, int status, JSONObject response) {
        String code = "";
        if (response != null) {
            Object error = response.opt("error");
            if (error instanceof String) code = (String) error;
            else if (error instanceof JSONObject) code = ((JSONObject) error).optString("code", "");
            if (!OAUTH_CODES.contains(code)) code = response.optString("error_code", "");
        }
        return new CodexAuthDiagnostic(stage, "http_response", status, code, "", "");
    }

    public static CodexAuthDiagnostic validation(Stage stage, String reason) {
        return new CodexAuthDiagnostic(stage, "invalid_response", 0, "", "", reason);
    }

    public static CodexAuthDiagnostic invalidJson(Stage stage, int status) {
        return new CodexAuthDiagnostic(stage, "invalid_response", status, "", "JSONException", "invalid_json");
    }

    static CodexAuthDiagnostic beforeBodyDns(Stage stage) {
        return new CodexAuthDiagnostic(stage, "dns", 0, "", "UnknownHostException", "before_body_dns");
    }

    public static CodexAuthDiagnostic storage(Exception error) {
        return new CodexAuthDiagnostic(Stage.SAVE_SESSION, "storage", 0, "", type(error), "");
    }

    public static CodexAuthDiagnostic failure(Stage stage, Exception error) {
        if (error instanceof Failure) return ((Failure) error).diagnostic;
        String category = "internal";
        Throwable selected = error;
        int depth = 0;
        for (Throwable current = error; current != null && depth++ < 8; current = current.getCause()) {
            if (current instanceof SSLException || current instanceof CertificateException) {
                selected = current; category = "tls"; break;
            } else if (current instanceof UnknownHostException) {
                selected = current; category = "dns"; break;
            } else if (current instanceof SocketTimeoutException) {
                selected = current; category = "timeout"; break;
            } else if (current instanceof ConnectException || current instanceof SocketException) {
                selected = current; category = "connection";
            } else if (current instanceof NetworkOnMainThreadException) {
                selected = current; category = "main_thread_network"; break;
            } else if (current instanceof SecurityException) {
                selected = current; category = "permission"; break;
            } else if (current instanceof IOException && category.equals("internal")) {
                selected = current; category = "io";
            }
        }
        return new CodexAuthDiagnostic(stage, category, 0, "", type(selected), "");
    }

    private static String type(Throwable error) {
        if (error == null) return "";
        String name = error.getClass().getSimpleName();
        return name.matches("[A-Za-z][A-Za-z0-9]{0,79}") ? name : "Exception";
    }

    public String toDisplayText(String method) {
        String safeMethod = "browser".equals(method) || "device_code".equals(method) ? method : "unknown";
        StringBuilder text = new StringBuilder("Jarvys ").append(BuildConfig.VERSION_NAME)
                .append("\nmethod=").append(safeMethod).append("\nstage=").append(stage.label)
                .append("\ncategory=").append(category);
        switch (stage) {
            case REQUEST_CODE: case POLL_APPROVAL: case TOKEN_EXCHANGE: case TOKEN_RESPONSE: case REFRESH:
                text.append("\ntarget_host=auth.openai.com"); break;
            default: break;
        }
        if (httpStatus != 0) text.append("\nhttp=").append(httpStatus);
        if (!providerCode.isEmpty()) text.append("\noauth=").append(providerCode);
        if (!exceptionType.isEmpty()) text.append("\nexception=").append(exceptionType);
        if (!reason.isEmpty()) text.append("\nreason=").append(reason);
        return text.toString();
    }

    public static class Failure extends IllegalStateException {
        public final CodexAuthDiagnostic diagnostic;
        public Failure(CodexAuthDiagnostic diagnostic) {
            super("ChatGPT authentication failed");
            this.diagnostic = diagnostic;
        }
    }
}
