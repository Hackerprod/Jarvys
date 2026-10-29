package com.jarvys.agent;

import java.util.regex.Pattern;

/** Pure content heuristic shared by transcript sanitization and workspace/skills search. */
public final class ContentSecretHeuristic {
    private ContentSecretHeuristic() { }

    // Defense-in-depth heuristic, not a secret scanner: redaction/placeholder examples are exempt, but false positives remain possible.
    private static final Pattern SECRET_PATTERNS = Pattern.compile(
            "(?is)(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
                    + "|\\bAKIA[0-9A-Z]{16}\\b"
                    + "|\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"
                    + "|\\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\\b"
                    + "|\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}"
                    + "|\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"
                    + "|\\b(?:password|passwd|api[_ -]?key|secret)\\s*[:=]\\s*(?!<redacted>|redacted\\b|example\\b|placeholder\\b|your[_ -]?)([^\\s,;]{4,}))"
    );

    public static boolean containsLikelySecret(String content) {
        return content != null && SECRET_PATTERNS.matcher(content).find();
    }
}
