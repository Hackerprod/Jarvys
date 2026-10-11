package com.jarvys.factory.runtime;
/** Store-level signal; the activity bridge maps it to CAPACITY_EXCEEDED without losing the unconsumed token. */
public final class NotificationLedgerCapacityException extends IllegalStateException {
    public NotificationLedgerCapacityException() { super("Notification action inbox capacity exceeded"); }
}
