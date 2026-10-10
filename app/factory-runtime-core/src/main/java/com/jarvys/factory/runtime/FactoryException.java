package com.jarvys.factory.runtime;

/** Stable, non-sensitive error sent to the app; never includes native exception details. */
public final class FactoryException extends Exception {
    public final String code;
    public FactoryException(String code, String message) { super(message); this.code = code; }
}
