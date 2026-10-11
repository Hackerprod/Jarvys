package com.jarvys.factory.runtime;
/** Bounded single-process effect locks. Packaged notification components must share the default process. */
public final class NotificationEffectGate {
    private static final Object[] GATES = new Object[64], SCHEDULES = new Object[64];
    static { for (int i=0;i<GATES.length;i++) { GATES[i] = new Object(); SCHEDULES[i] = new Object(); } }
    /** Immediate show/cancel integration must use this same monitor around its final effect and action invalidation. */
    public static Object forId(String appId,int id) { return GATES[(31*appId.hashCode()+id)&63]; }
    static Object forSchedule(String token) { return SCHEDULES[token.hashCode()&63]; }
    private NotificationEffectGate() { }
}
