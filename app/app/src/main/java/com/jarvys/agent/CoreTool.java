package com.jarvys.agent;

import java.util.Map;

/** Tool contract owned by the new conversation core; it has no Android/legacy dispatcher dependency. */
public interface CoreTool {
    interface ProgressListener { void onProgress(String message); }

    ToolSpec declaration();
    CoreToolResult execute(Map<String, Object> arguments, CancellationToken token);

    /** Optional live row updates for long operations; existing tools need not implement it. */
    default CoreToolResult execute(Map<String, Object> arguments, CancellationToken token,
                                   ProgressListener progress) {
        return execute(arguments, token);
    }
}
