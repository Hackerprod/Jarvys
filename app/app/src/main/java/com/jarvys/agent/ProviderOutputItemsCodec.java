package com.jarvys.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Structural persistence for provider-specific assistant output items. Values are never rendered. */
final class ProviderOutputItemsCodec {
    private ProviderOutputItemsCodec() { }

    static JSONArray encode(List<ModelReply.ProviderOutputItem> items) throws Exception {
        JSONArray encoded = new JSONArray();
        if (items == null) return encoded;
        for (ModelReply.ProviderOutputItem item : items) {
            JSONObject value = new JSONObject();
            value.put("kind", item.kind.name());
            value.put("value", item.value);
            encoded.put(value);
        }
        return encoded;
    }

    static List<ModelReply.ProviderOutputItem> decode(JSONArray encoded) {
        if (encoded == null) return Collections.emptyList();
        List<ModelReply.ProviderOutputItem> result = new ArrayList<>();
        try {
            for (int i = 0; i < encoded.length(); i++) {
                JSONObject item = encoded.getJSONObject(i);
                String value = item.getString("value");
                switch (ModelReply.ProviderOutputItem.Kind.valueOf(item.getString("kind"))) {
                    case REASONING: result.add(ModelReply.ProviderOutputItem.reasoning(value)); break;
                    case MESSAGE: result.add(ModelReply.ProviderOutputItem.message(value)); break;
                    case TOOL_CALL: result.add(ModelReply.ProviderOutputItem.toolCall(value)); break;
                }
            }
            return Collections.unmodifiableList(result);
        } catch (Exception invalid) {
            // Never include raw provider item data in persistence or diagnostic exceptions.
            throw new IllegalArgumentException("Stored provider output items are invalid");
        }
    }
}
