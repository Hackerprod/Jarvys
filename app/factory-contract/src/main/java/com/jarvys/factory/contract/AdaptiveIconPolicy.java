package com.jarvys.factory.contract;

import java.util.Objects;

/** Optional launcher artwork data. It grants no native capability or resource/compiler access. */
public final class AdaptiveIconPolicy {
    public final String foreground, background;
    /** Padding per side as a percentage of the complete adaptive layer canvas. Zero preserves authored layout. */
    public final int insetPercent;
    public AdaptiveIconPolicy(String foreground, String background) { this(foreground, background, 0); }
    public AdaptiveIconPolicy(String foreground, String background, int insetPercent) {
        if (foreground == null || foreground.isEmpty() || foreground.length() > 240 || foreground.startsWith("/") || foreground.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Adaptive foreground must be a relative project path");
        for (int i=0;i<foreground.length();i++) if (Character.isISOControl(foreground.charAt(i)))
            throw new IllegalArgumentException("Invalid adaptive foreground path");
        for (String part:foreground.split("/", -1)) if (part.isEmpty() || part.startsWith(".") || part.trim().isEmpty())
            throw new IllegalArgumentException("Adaptive foreground cannot traverse or use hidden paths");
        String lower=foreground.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".json")) throw new IllegalArgumentException("Adaptive foreground must be PNG or geometric JSON");
        if (background == null || !background.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Adaptive background must be opaque #RRGGBB");
        if (insetPercent < 0 || insetPercent > 40) throw new IllegalArgumentException("Adaptive insetPercent must be an integer from 0 to 40");
        this.foreground=foreground; this.background=background; this.insetPercent=insetPercent;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof AdaptiveIconPolicy)) return false;
        AdaptiveIconPolicy that=(AdaptiveIconPolicy)other;
        return foreground.equals(that.foreground) && background.equals(that.background) && insetPercent==that.insetPercent;
    }
    @Override public int hashCode() { return Objects.hash(foreground,background,insetPercent); }
}
