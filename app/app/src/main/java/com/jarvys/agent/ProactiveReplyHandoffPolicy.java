package com.jarvys.agent;

/** Independent P4A format gate. Enable only after matching Full/Play D3 conservation review. */
public final class ProactiveReplyHandoffPolicy {
    private ProactiveReplyHandoffPolicy() {}
    public static boolean admissionEnabled() { return false; }
}
