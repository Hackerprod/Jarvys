package com.jarvys.agent;

public final class ToolResult {
    public final String name;
    public final boolean success;
    public final Object value;
    public final String error;

    private ToolResult(String name, boolean success, Object value, String error) {
        this.name = name;
        this.success = success;
        this.value = value;
        this.error = error;
    }

    public static ToolResult success(String name, Object value) {
        return new ToolResult(name, true, value, null);
    }

    public static ToolResult failure(String name, String error) {
        return new ToolResult(name, false, null, error);
    }
}
