package com.jarvys.agent.device;

public enum SwipeDirection {
    UP, DOWN, LEFT, RIGHT;

    public static SwipeDirection parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Swipe direction is required");
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
