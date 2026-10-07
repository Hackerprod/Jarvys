package com.jarvys.agent.crew;

import android.content.Context;

/** Conversation preference. C0 stores this in SharedPreferences; there is intentionally no UI. */
public enum CrewMode {
    OFF, AUTO, ALWAYS;
    public static CrewMode parse(String value) {
        if (value == null) return AUTO;
        for (CrewMode mode : values()) if (mode.name().equalsIgnoreCase(value.trim())) return mode;
        return AUTO;
    }
    public boolean enabled() { return this != OFF; }
    public boolean mustDelegate() { return this == ALWAYS; }

    public static CrewMode read(Context context) {
        return parse(context.getApplicationContext().getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)
                .getString("crew_mode", "auto"));
    }

    public static void write(Context context, CrewMode mode) {
        context.getApplicationContext().getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)
                .edit().putString("crew_mode", mode.name().toLowerCase(java.util.Locale.ROOT)).apply();
    }
}
