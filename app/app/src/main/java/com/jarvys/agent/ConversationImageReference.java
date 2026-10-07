package com.jarvys.agent;

import org.json.JSONObject;

final class ConversationImageReference {
    static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    final ChatAttachment attachment;
    final String description;
    final String generatedPath;
    final String messageId;
    final String reference;

    private ConversationImageReference(String reference, String messageId, String description, ChatAttachment attachment, String generatedPath) {
        this.reference = reference;
        this.messageId = messageId;
        this.description = description;
        this.attachment = attachment;
        this.generatedPath = generatedPath;
    }

    static ConversationImageReference attachment(String messageId, ChatAttachment attachment) {
        return new ConversationImageReference("attachment:" + attachment.id, messageId, attachment.name, attachment, null);
    }

    static ConversationImageReference generated(String messageId, String path, String prompt) {
        if (path != null && path.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.png")) {
            return new ConversationImageReference("generated:" + path.substring(0, path.length() - 4), messageId, prompt, null, path);
        }
        return null;
    }

    static boolean valid(String reference) {
        return reference != null && reference.matches("(?:attachment|generated):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    JSONObject metadata() {
        try {
            return new JSONObject().put("image_ref", this.reference).put("user_message_id", this.messageId).put(this.attachment == null ? "image_prompt" : "name", this.description);
        } catch (Exception e) {
            throw new IllegalStateException("Could not describe image reference");
        }
    }
}
