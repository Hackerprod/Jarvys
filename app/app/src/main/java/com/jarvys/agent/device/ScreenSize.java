package com.jarvys.agent.device;

public final class ScreenSize {
    public final int width;
    public final int height;

    public ScreenSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Screen dimensions must be positive");
        }
        this.width = width;
        this.height = height;
    }
}
