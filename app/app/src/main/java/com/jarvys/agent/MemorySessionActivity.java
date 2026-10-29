package com.jarvys.agent;

import com.jarvys.agent.chatdb.MemoryObservationContent;
import com.jarvys.agent.chatdb.MemorySessionBinding;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local reminders only. Neither a handle nor its counters grant memory authority. */
public final class MemorySessionActivity {
  private static final Duration THRESHOLD = Duration.ofMinutes(10);
  private static final Map<String, MemorySessionActivity> GENERATIONS = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Map<String, Handle> sessions = new HashMap<>();
  private final Map<String, Instant> projectLastSave = new HashMap<>();

  public MemorySessionActivity(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock); }
  static MemorySessionActivity forGeneration(String generation) {
    return GENERATIONS.computeIfAbsent(generation, ignored -> new MemorySessionActivity(Clock.systemUTC()));
  }

  /** Caller supplies the actual acknowledged binding, never an ID parsed from a model argument. */
  public synchronized Handle open(MemorySessionBinding binding) {
    String key = binding.getSourceGeneration() + ":" + binding.getId();
    Handle result = new Handle(this, key, binding.getSourceGeneration(), binding.getProject(), clock.instant());
    sessions.put(key, result);
    return result;
  }
  synchronized Map<String, java.util.List<Long>> snapshotCounts() {
    Map<String, java.util.List<Long>> result=new HashMap<>();
    sessions.forEach((key,value) -> result.put(key, java.util.List.of(value.toolCalls,value.saves)));
    return java.util.Collections.unmodifiableMap(result);
  }
  private boolean current(Handle handle) { return !handle.closed && sessions.get(handle.key) == handle; }
  private void expire(Instant now) {
    projectLastSave.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().plus(THRESHOLD)));
  }
  private static String project(String value) { return MemoryObservationContent.trimSpace(value == null ? "" : value); }

  public static final class Handle {
    private final MemorySessionActivity owner;
    private final String key;
    private final String boundProject;
    private final String generation;
    private final Instant startedAt;
    private Instant lastSaveAt;
    private long toolCalls;
    private long saves;
    private boolean available = true;
    private boolean closed;
    private Handle(MemorySessionActivity owner, String key, String generation, String project, Instant startedAt) {
      this.owner=owner; this.key=key; this.generation=generation; this.boundProject=project; this.startedAt=startedAt;
    }
    public String project() { return boundProject; }
    public void setAvailable(boolean value) { synchronized(owner) { if(owner.current(this)) available=value; } }
    public void recordToolCall() { synchronized(owner) {
      if(owner.current(this) && available) toolCalls=Math.addExact(toolCalls,1L);
    } }
    public void recordSave(String project) { synchronized(owner) {
      if(!owner.current(this) || !available) return;
      Instant now=owner.clock.instant(); saves=Math.addExact(saves,1L); lastSaveAt=now;
      recordProjectAt(project,now);
    } }
    public void recordProjectSave(String project) { synchronized(owner) {
      if(owner.current(this) && available) recordProjectAt(project,owner.clock.instant());
    } }
    private void recordProjectAt(String value, Instant now) {
      String project=MemorySessionActivity.project(value);
      if(project.isEmpty()) return;
      owner.expire(now); owner.projectLastSave.put(generation+":"+project,now);
    }
    public String primaryReminder(String value) { synchronized(owner) {
      Instant now=owner.clock.instant(); String project=MemorySessionActivity.project(value);
      if(!project.isEmpty()) owner.expire(now);
      if(!owner.current(this) || !available || Duration.between(startedAt,now).compareTo(THRESHOLD)<0) return "";
      if(saves==0 && toolCalls<=5) return "";
      Duration elapsed=Duration.between(lastSaveAt==null?startedAt:lastSaveAt,now);
      if(elapsed.compareTo(THRESHOLD)<0 || !project.isEmpty() && owner.projectLastSave.containsKey(generation+":"+project)) return "";
      return "\n\n⚠️ No mem_save calls for this project in " + elapsed.toMinutes()
          + " minutes. Did you make any decisions, fix bugs, or discover something worth persisting?";
    } }
    /** Only the actual tool-name token differs from the primary transport literal. */
    public String reminder(String project) { return primaryReminder(project).replace("No mem_save calls", "No memory_save calls"); }
    public long toolCallCount() { synchronized(owner) { return toolCalls; } }
    public long saveCount() { synchronized(owner) { return saves; } }
    public void close() { synchronized(owner) {
      if(owner.sessions.get(key)==this) owner.sessions.remove(key);
      closed=true;
    } }
  }
}
