package com.jarvys.agent.crew;

import java.util.UUID;

/** Changes once per app process; persisted live bots from an older process are never resumed. */
public final class CrewProcessIdentity {
    public static final String ID = UUID.randomUUID().toString();
    private CrewProcessIdentity() { }
}
