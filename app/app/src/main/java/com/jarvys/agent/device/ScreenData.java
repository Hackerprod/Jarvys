package com.jarvys.agent.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable screenshot + accessibility hierarchy observation, dimensioned in physical pixels. */
public final class ScreenData {
    public final byte[] screenshotBytes;
    public final String screenshotBase64;
    public final String uiHierarchyXml;
    public final List<Map<String, Object>> uiElements;
    public final int width;
    public final int height;
    public final double timestamp;
    public final String platform;

    public ScreenData(byte[] screenshotBytes, String screenshotBase64, String uiHierarchyXml,
                      List<Map<String, Object>> uiElements, int width, int height,
                      double timestamp, String platform) {
        if (screenshotBytes == null || screenshotBase64 == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A screen observation requires screenshot bytes and dimensions");
        }
        this.screenshotBytes = screenshotBytes.clone();
        this.screenshotBase64 = screenshotBase64;
        this.uiHierarchyXml = uiHierarchyXml;
        this.uiElements = Collections.unmodifiableList(new ArrayList<>(uiElements));
        this.width = width;
        this.height = height;
        this.timestamp = timestamp;
        this.platform = platform;
    }
}
