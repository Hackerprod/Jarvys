package com.jarvys.factory.contract;

/** Signed opt-in to platform notification scheduling; immediate notifications remain unchanged. */
public final class NotificationPolicy {
    public static final String ALARM_RECEIVER = "com.jarvys.factory.runtime.FactoryNotificationAlarmReceiver";
    public static final String RECOVERY_RECEIVER = "com.jarvys.factory.runtime.FactoryNotificationRecoveryReceiver";
    public static final String BOOT_PERMISSION = "android.permission.RECEIVE_BOOT_COMPLETED";
    public static final String EXACT_PERMISSION = "android.permission.SCHEDULE_EXACT_ALARM";
    public static final String EXACT_ACTION = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";
    public final boolean scheduling, exactAlarms;
    public NotificationPolicy(boolean scheduling, boolean exactAlarms) {
        if (exactAlarms && !scheduling) throw new IllegalArgumentException("Exact alarms require notification scheduling");
        this.scheduling = scheduling; this.exactAlarms = exactAlarms;
    }
    public static NotificationPolicy disabled() { return new NotificationPolicy(false, false); }
    public static void validateSelection(Iterable<String> capabilities, NotificationPolicy policy) {
        if (policy == null) return;
        for (String capability : capabilities) if ("notifications".equals(capability)) return;
        throw new IllegalArgumentException("notifications configuration requires the notifications capability");
    }
    @Override public boolean equals(Object other) { return other instanceof NotificationPolicy && scheduling == ((NotificationPolicy) other).scheduling && exactAlarms == ((NotificationPolicy) other).exactAlarms; }
    @Override public int hashCode() { return (scheduling ? 2 : 0) + (exactAlarms ? 1 : 0); }
}
