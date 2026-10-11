package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Closed JSON adapter for the shared immutable capacity contract; never performs admission. */
public final class ResourceBudgetsJson {
    private ResourceBudgetsJson() { }
    public static ResourceBudgets parse(JSONObject object) throws FactoryException {
        try {
            if (object == null || object.length() > 15) throw new IllegalArgumentException();
            Map<String, Object> root = new LinkedHashMap<>();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next(); Object value = object.get(key);
                if (value instanceof JSONObject) {
                    JSONObject section = (JSONObject) value;
                    if (section.length() > 16) throw new IllegalArgumentException();
                    Map<String, Object> fields = new LinkedHashMap<>(); Iterator<String> names = section.keys();
                    while (names.hasNext()) { String name = names.next(); fields.put(name, section.get(name)); }
                    value = fields;
                }
                root.put(key, value);
            }
            return ResourceBudgets.parse(root);
        } catch (Exception invalid) { throw new FactoryException("INVALID_CONFIG", "Invalid per-app resource budgets."); }
    }
    public static JSONObject toJson(ResourceBudgets value) { return new JSONObject(value.toMap()); }
}
