package com.jarvys.agent.crew;
import com.jarvys.agent.ToolActivity;
import java.util.*;
/** Display-only reduction. Canonical messages remain append-only and mailboxes are untouched. */
public final class CrewActivityTimeline {
    private CrewActivityTimeline() {}
    public static List<CrewMessage> interruptUnfinished(List<CrewMessage> messages, String botId) {
        List<CrewMessage> retained=new ArrayList<>(messages);
        Map<String,List<ToolActivity>> groups=new LinkedHashMap<>();
        for(CrewMessage message:messages) if(message.from.equals(botId) && message.activity!=null)
            groups.computeIfAbsent(message.activity.executionId,ignored->new ArrayList<>()).add(message.activity);
        for(List<ToolActivity> group:groups.values()) {
            ToolActivity value=ToolActivity.project(group,true);
            if(ToolActivity.definitive(value.stage) || "tool_interrupted".equals(value.stage)) continue;
            ToolActivity interruption=new ToolActivity(value.executionId+"/recovered-interruption",value.executionId,value.callId,
                value.toolName,value.displayName,value.skillId,value.skillName,"tool_interrupted","",value.previewId,value.auditDetail,
                value.reflectionSource,value.timestampMillis);
            CrewMessage owner=messages.stream().filter(m->m.from.equals(botId) && m.activity!=null
                    && m.activity.executionId.equals(value.executionId)).findFirst().get();
            retained.add(new CrewMessage(interruption.eventId,owner.conversationId,botId,botId,CrewMessage.Type.STATUS,
                    value.displayName,Collections.emptyList(),interruption.timestampMillis,interruption));
        }
        return retained;
    }
    public static List<CrewMessage> project(List<CrewMessage> messages, List<CrewBotSnapshot> bots) {
        List<CrewMessage> rows=new ArrayList<>(); Map<String,Integer> positions=new LinkedHashMap<>();
        Map<String,List<ToolActivity>> evidence=new LinkedHashMap<>(); Set<String> active=new HashSet<>();
        for(CrewBotSnapshot bot:bots) if(bot.active()) active.add(bot.id);
        for(CrewMessage message:messages) {
            if(message.activity==null) { rows.add(message); continue; }
            String key=message.conversationId+"/"+message.from+"/"+message.activity.executionId;
            if(!positions.containsKey(key)) { positions.put(key,rows.size());rows.add(message);evidence.put(key,new ArrayList<>()); }
            List<ToolActivity> events=evidence.get(key);events.add(message.activity);
            int index=positions.get(key);rows.set(index,rows.get(index).withActivity(ToolActivity.project(events,active.contains(message.from))));
        }
        return Collections.unmodifiableList(rows);
    }
}
