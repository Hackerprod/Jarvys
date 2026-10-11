package com.jarvys.factory.runtime;

/**
 * Pure state planner. A plan is never an authorization or an executed effect.
 * Persist each transition with durable compare-and-set before performing its effect.
 */
public final class NotificationScheduleMachine {
    private NotificationScheduleMachine() { }
    public enum State {
        PENDING, ARMING, SCHEDULED, SCHEDULE_UNKNOWN,
        QUEUED_POLICY_UNAVAILABLE, QUEUED_APPROVAL, QUEUED_OS_BLOCKED, QUEUED_EXACT_PERMISSION,
        DISPATCH_RESERVED, ACCEPTED, DELIVERY_UNKNOWN, CANCELLED, REVOKED, EXPIRED
    }
    public enum Policy { ALLOWED, ASK, DENIED, UNAVAILABLE }
    public enum Effect { NONE, ARM, POST, CANCEL_ALARM }
    public static final class Plan {
        public final State state;
        public final Effect effect;
        public final String reason;
        private Plan(State state, Effect effect, String reason) { this.state = state; this.effect = effect; this.reason = reason; }
    }
    public static boolean terminal(State state) {
        require(state);
        return state == State.ACCEPTED || state == State.DELIVERY_UNKNOWN || state == State.CANCELLED
                || state == State.REVOKED || state == State.EXPIRED;
    }
    /** Initial scheduling or explicitly requested resume, never a hidden fallback from exact. */
    public static Plan resume(NotificationScheduleRequest.Schedule request, State current, long nowEpochMs,
                              Policy policy, boolean exactAvailable, boolean notificationsReady) {
        require(request, current, policy);
        if (nowEpochMs < 0) throw new IllegalArgumentException("Invalid clock");
        if (terminal(current) || current == State.DISPATCH_RESERVED || current == State.ARMING) return none(current, "terminal_or_in_flight");
        if (nowEpochMs > request.expiresAtEpochMs) return none(State.EXPIRED, "delivery_window_ended");
        Plan blocked = policy(policy); if (blocked != null) return blocked;
        if (nowEpochMs >= request.atEpochMs) return fire(request, current, nowEpochMs, policy, notificationsReady);
        if (request.precision == NotificationScheduleRequest.Precision.EXACT && !exactAvailable)
            return none(State.QUEUED_EXACT_PERMISSION, "android_exact_alarm_unavailable");
        return new Plan(State.ARMING, Effect.ARM, "live_authorization_required_at_arm");
    }
    /** Alarm callback: never arms a second alarm or executes application JavaScript. */
    public static Plan fire(NotificationScheduleRequest.Schedule request, State current, long nowEpochMs,
                            Policy policy, boolean notificationsReady) {
        require(request, current, policy);
        if (nowEpochMs < 0) throw new IllegalArgumentException("Invalid clock");
        if (terminal(current) || current == State.DISPATCH_RESERVED) return none(current, "terminal_or_in_flight");
        if (nowEpochMs < request.atEpochMs) return none(current, "not_due");
        if (nowEpochMs > request.expiresAtEpochMs) return none(State.EXPIRED, "delivery_window_ended");
        Plan blocked = policy(policy); if (blocked != null) return blocked;
        if (!notificationsReady) return none(State.QUEUED_OS_BLOCKED, "android_notification_not_ready");
        return new Plan(State.DISPATCH_RESERVED, Effect.POST, "persist_reservation_before_post_and_live_check_again");
    }
    public static Plan armed(State current, boolean accepted) {
        require(current); if (current != State.ARMING) return none(current, "stale_arm_result");
        return none(accepted ? State.SCHEDULED : State.SCHEDULE_UNKNOWN, accepted ? "alarm_manager_accepted" : "alarm_result_uncertain");
    }
    public static Plan posted(State current, boolean accepted) {
        require(current); if (current != State.DISPATCH_RESERVED) return none(current, "stale_post_result");
        return none(accepted ? State.ACCEPTED : State.DELIVERY_UNKNOWN, accepted ? "notify_returned_no_display_receipt" : "post_result_uncertain_no_auto_replay");
    }
    /** Only before invoking notify, with proof no effect was attempted, can a reservation be released. */
    public static Plan blockedBeforePost(State current, Policy policy, boolean notificationsReady) {
        require(current, policy);
        if (current != State.DISPATCH_RESERVED) return none(current, "stale_preflight_result");
        Plan blocked = policy(policy); if (blocked != null) return blocked;
        if (!notificationsReady) return none(State.QUEUED_OS_BLOCKED, "android_notification_not_ready");
        return none(current, "still_reserved");
    }
    /** Crash recovery preserves uncertain outcomes. It never blindly retries a post. */
    public static Plan recover(State current) {
        require(current);
        if (current == State.DISPATCH_RESERVED) return none(State.DELIVERY_UNKNOWN, "process_ended_during_dispatch");
        if (current == State.ARMING) return none(State.SCHEDULE_UNKNOWN, "process_ended_during_arm");
        return none(current, "unchanged");
    }
    /** Persist cancellation before cancelling the identical PendingIntent. Cannot recall an in-flight post. */
    public static Plan cancel(State current) {
        require(current);
        if (current == State.DISPATCH_RESERVED || current == State.ACCEPTED || current == State.DELIVERY_UNKNOWN)
            return none(current, "too_late_use_notifications_cancel_for_visible_id");
        if (current == State.CANCELLED) return new Plan(current, Effect.CANCEL_ALARM, "repeat_cleanup_same_identity");
        if (current == State.REVOKED || current == State.EXPIRED) return new Plan(current, Effect.CANCEL_ALARM, "terminal_cleanup_same_identity");
        return new Plan(State.CANCELLED, Effect.CANCEL_ALARM, "persist_tombstone_before_cleanup");
    }
    private static Plan policy(Policy policy) {
        switch (policy) {
            case DENIED: return none(State.REVOKED, "live_policy_denied");
            case ASK: return none(State.QUEUED_APPROVAL, "foreground_review_required");
            case UNAVAILABLE: return none(State.QUEUED_POLICY_UNAVAILABLE, "authenticated_host_unavailable");
            default: return null;
        }
    }
    private static Plan none(State state, String reason) { return new Plan(state, Effect.NONE, reason); }
    private static void require(Object... values) { for (Object value : values) if (value == null) throw new IllegalArgumentException("Missing state input"); }
}
