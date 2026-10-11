package com.jarvys.factory.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable signed declaration of generic Android share intake. Never an Android permission. */
public final class IncomingSharePolicy {
    public static final int MAX_MIME_TYPES = 32;
    public final List<String> mimeTypes;
    public final boolean multiple;
    public IncomingSharePolicy(Iterable<String> mimeTypes, boolean multiple) {
        if (mimeTypes == null) throw new IllegalArgumentException("Missing incoming share MIME types");
        List<String> values = new ArrayList<>(); String previous = null;
        for (String mime : mimeTypes) {
            if (!validMimePattern(mime) || values.size() >= MAX_MIME_TYPES ||
                    (previous != null && previous.compareTo(mime) >= 0))
                throw new IllegalArgumentException("Incoming share MIME patterns must be valid, lowercase, sorted and unique");
            values.add(mime); previous = mime;
        }
        if (values.isEmpty()) throw new IllegalArgumentException("Missing incoming share MIME types");
        this.mimeTypes = Collections.unmodifiableList(values); this.multiple = multiple;
    }
    public static boolean validMimePattern(String value) {
        return value != null && value.length() <= 127 && (value.equals("*/*") ||
                value.matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/(\\*|[a-z0-9][a-z0-9!#$&^_.+-]{0,62})"));
    }
    /** A declaration is present exactly when selected; existing document authority stays explicit. */
    public static void validateSelection(Iterable<String> capabilities, IncomingSharePolicy policy) {
        boolean selected = false, documents = false;
        for (String capability : capabilities) {
            if ("incomingShare".equals(capability)) selected = true;
            if ("documents".equals(capability)) documents = true;
        }
        if (selected != (policy != null)) throw new IllegalArgumentException("incomingShare capability requires its exact configuration");
        if (selected && !documents) throw new IllegalArgumentException("incomingShare requires documents to consume shared handles");
    }
    @Override public boolean equals(Object other) {
        return other instanceof IncomingSharePolicy && multiple == ((IncomingSharePolicy) other).multiple &&
                mimeTypes.equals(((IncomingSharePolicy) other).mimeTypes);
    }
    @Override public int hashCode() { return 31 * mimeTypes.hashCode() + (multiple ? 1 : 0); }
}
