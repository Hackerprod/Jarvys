package com.jarvys.agent;

import java.io.File;
import java.util.concurrent.Callable;

public final class CodingJobConversationGuard {
  private final ConversationMetadataStore metadata;

  public CodingJobConversationGuard(File filesDirectory) {
    this.metadata = new ConversationMetadataStore(filesDirectory);
  }

  public <T> T active(String conversationId, Callable<T> action) throws Exception {
    T tCall;
    synchronized (ConversationMetadataStore.LOCK) {
      if (this.metadata.read(conversationId).deleted) {
        throw new IllegalStateException("This chat has been deleted; coding jobs are unavailable");
      }
      tCall = action.call();
    }
    return tCall;
  }
}
