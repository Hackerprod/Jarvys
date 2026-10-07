package com.jarvys.agent.crew;

import android.content.Context;

import com.jarvys.agent.R;

import java.util.List;

/** Pure Crew activity projection for the existing foreground notification. */
public final class CrewNotificationPolicy {
    private CrewNotificationPolicy() { }

    public static final class State {
        public final int activeBots;
        public final boolean keepService;
        public final boolean showCrewActivity;

        State(int activeBots) {
            this.activeBots = activeBots;
            this.keepService = activeBots > 0;
            this.showCrewActivity = activeBots > 0;
        }

        public String notificationText(Context context) {
            return activeBots > 0
                    ? context.getResources().getQuantityString(R.plurals.crew_active_bots, activeBots, activeBots)
                    : context.getString(R.string.agent_notification_text);
        }
    }

    public static State evaluate(List<CrewMissionSnapshot> snapshots) {
        int active = 0;
        if (snapshots != null) for (CrewMissionSnapshot mission : snapshots) {
            if (mission == null) continue;
            for (CrewBotSnapshot bot : mission.bots) {
                if ("RUNNING".equals(bot.status) || "WAITING".equals(bot.status)) active++;
            }
        }
        return new State(active);
    }
}
