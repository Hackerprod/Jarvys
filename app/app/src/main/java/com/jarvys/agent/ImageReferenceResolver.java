package com.jarvys.agent;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class ImageReferenceResolver {
    static final int MAX_REFERENCES = 5;
    private final AttachmentStore attachments;
    private final Context context;
    private final LocalRunStore conversations;
    private final GeneratedImageStore generated;
    private final String sessionId;

    ImageReferenceResolver(Context context, String sessionId) {
        this.context = context;
        this.sessionId = sessionId;
        this.conversations = new LocalRunStore(context);
        this.attachments = new AttachmentStore(context);
        this.generated = new GeneratedImageStore(context);
    }

    static List<String> parse(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("image_refs must be an array of image references");
        }
        List<?> values = (List<?>)raw;
        if (values.size() > 5) {
            throw new IllegalArgumentException("Codex accepts at most five image references per edit");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String) || !ConversationImageReference.valid((String)value)) {
                throw new IllegalArgumentException("Use an exact image_ref from this conversation, not a path, URL or index");
            }
            result.add((String)value);
        }
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("Each selected image reference must be unique");
        }
        return Collections.unmodifiableList(result);
    }

    List<ImageEditInput> resolve(List<String> selected, CancellationToken token) {
        token.throwIfCancelled();
        Map<String, ConversationImageReference> available = new LinkedHashMap<>();
        for (ConversationImageReference item : this.conversations.readConversationImageReferences(this.sessionId)) {
            available.put(item.reference, item);
        }
        List<File> files = new ArrayList<>();
        for (String reference : selected) {
            token.throwIfCancelled();
            ConversationImageReference item2 = available.get(reference);
            if (item2 == null) {
                throw new IllegalArgumentException("The selected image does not belong to this conversation");
            }
            try {
                files.add(item2.attachment == null ? this.generated.resolve(this.sessionId, item2.generatedPath) : this.attachments.resolve(this.sessionId, item2.attachment));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The selected image is unavailable in this conversation");
            }
        }
        long budget = Math.max(1L, AttachmentImagePreparer.availableHeapBytes() / ((long)Math.max(1, files.size())));
        List<ImageEditInput> result = new ArrayList<>();
        for (File file : files) {
            result.add(AttachmentImagePreparer.prepareForEdit(this.context, file, budget, token));
        }
        return Collections.unmodifiableList(result);
    }
}
