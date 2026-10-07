package com.jarvys.agent.device;

import java.util.Map;

public final class ElementMatch {
    public final Map<String, Object> element;
    public final int[] center;
    public final String error;

    public ElementMatch(Map<String, Object> element, int[] center, String error) {
        this.element = element;
        this.center = center;
        this.error = error;
    }

    public boolean isFound() {
        return element != null && center != null && error == null;
    }
}
