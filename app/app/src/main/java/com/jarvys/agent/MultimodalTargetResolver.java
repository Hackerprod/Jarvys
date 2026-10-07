package com.jarvys.agent;

import com.jarvys.agent.device.ScreenData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Structural-first resolver with provider vision/OCR fallback for non-semantic Android surfaces. */
public final class MultimodalTargetResolver {
    private final AgentModel model;

    public MultimodalTargetResolver(AgentModel model) {
        this.model = model;
    }

    public String explore(String query, String feedback, AgentState state, CancellationToken token) {
        List<Map<String, Object>> matches = textMatches(state, query);
        if (!matches.isEmpty()) return candidatesJson("UI hierarchy matches", matches, state.observation);
        ScreenData screen = requireScreen(state);
        String prompt = "Locate the requested visible UI target on this Android screenshot. "
                + "Return only JSON: {\"candidates\":[{\"label\":string,\"description\":string,\"coords\":[x,y]}],"
                + "\"fallback_message\":string}. Coordinates must be normalized 0-1000. "
                + "Query: " + query + "\nContext: " + (feedback == null ? "" : feedback);
        JSONObject answer = parseObject(model.visionQuery(prompt, screen, token));
        validateCandidates(answer.optJSONArray("candidates"));
        return answer.toString();
    }

    public String detectObjects(List<String> queries, AgentState state, CancellationToken token) {
        if (queries == null || queries.isEmpty()) throw new IllegalArgumentException("At least one detection query is required");
        ScreenData screen = requireScreen(state);
        String prompt = "Detect only the requested visible objects in this Android screenshot. "
                + "Return only JSON array [{\"label\":string,\"description\":string,\"coords\":[x,y]}]. "
                + "Coordinates are normalized 0-1000; omit objects that cannot be seen. Queries: " + queries;
        JSONArray answer = parseArray(model.visionQuery(prompt, screen, token));
        validateCandidates(answer);
        return answer.toString();
    }

    public String getOcrList(AgentState state, CancellationToken token) {
        ScreenData screen = requireScreen(state);
        String prompt = "Read all legible on-screen text in this Android screenshot. "
                + "Return only JSON array [{\"text\":string,\"coordinates\":[x,y]}], where coordinates "
                + "are normalized 0-1000 centers. Do not infer obscured text.";
        JSONArray results = parseArray(model.visionQuery(prompt, screen, token));
        for (int i = 0; i < results.length(); i++) {
            JSONObject row = results.optJSONObject(i);
            if (row == null || row.optString("text", "").trim().isEmpty()) {
                throw new IllegalStateException("OCR response must contain text objects");
            }
            JSONArray point = row.optJSONArray("coordinates");
            if (point != null && (point.length() != 2 || !normalized(point.optInt(0, -1))
                    || !normalized(point.optInt(1, -1)))) {
                throw new IllegalStateException("OCR coordinates must be normalized [x,y] pairs");
            }
        }
        return results.toString();
    }

    public String askPerception(String query, int nx, int ny, List<String> detectQueries,
                                AgentState state, CancellationToken token) {
        if (nx < 0 || nx > 1000 || ny < 0 || ny > 1000) {
            throw new IllegalArgumentException("Coordinate audit point must be normalized 0-1000");
        }
        List<String> sections = new ArrayList<>();
        List<Map<String, Object>> matches = textMatches(state, query);
        sections.add(matches.isEmpty() ? "UI text search: no hierarchy match"
                : candidatesJson("UI text search", matches, state.observation));
        Map<String, Object> hit = hitTest(state, nx, ny);
        sections.add(hit == null ? "Coordinate audit: no indexed element at [" + nx + "," + ny + "]"
                : "Coordinate audit [" + nx + "," + ny + "]: " + hit);
        if (detectQueries != null && !detectQueries.isEmpty()) {
            sections.add("Visual detection: " + detectObjects(detectQueries, state, token));
        }
        return join(sections, "\n");
    }

    private static List<Map<String, Object>> textMatches(AgentState state, String query) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (state.indexedElements == null || query == null || query.trim().isEmpty()) return result;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (Map<String, Object> item : state.indexedElements) {
            String label = String.valueOf(item.get("text") == null ? "" : item.get("text"));
            String id = String.valueOf(item.get("resource_id") == null ? "" : item.get("resource_id"));
            if (label.toLowerCase(Locale.ROOT).contains(needle) || id.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(item);
            }
        }
        return result;
    }

    private static Map<String, Object> hitTest(AgentState state, int nx, int ny) {
        if (state.observation == null || state.indexedElements == null) return null;
        int px = (int) (nx * state.observation.width / 1000.0);
        int py = (int) (ny * state.observation.height / 1000.0);
        for (Map<String, Object> item : state.indexedElements) {
            Object raw = item.get("bounds");
            if (!(raw instanceof int[])) continue;
            int[] bounds = (int[]) raw;
            if (px >= bounds[0] && px <= bounds[2] && py >= bounds[1] && py <= bounds[3]) return item;
        }
        return null;
    }

    private static String candidatesJson(String heading, List<Map<String, Object>> elements, ScreenData screen) {
        JSONArray candidates = new JSONArray();
        for (Map<String, Object> element : elements) {
            Object center = element.get("center");
            Object bounds = element.get("bounds");
            int[] physical = bounds instanceof int[] ? (int[]) bounds : null;
            int[] point = center instanceof int[] ? (int[]) center : null;
            if (point == null || physical == null) continue;
            JSONObject row = new JSONObject();
            try {
                row.put("index", element.get("index"));
                row.put("label", element.get("text"));
                row.put("resource_id", element.get("resource_id"));
                row.put("coords", new JSONArray().put((int) (point[0] * 1000.0 / screen.width))
                        .put((int) (point[1] * 1000.0 / screen.height)));
                row.put("bounds", new JSONArray().put((int) (physical[0] * 1000.0 / screen.width))
                        .put((int) (physical[1] * 1000.0 / screen.height))
                        .put((int) (physical[2] * 1000.0 / screen.width))
                        .put((int) (physical[3] * 1000.0 / screen.height)));
                candidates.put(row);
            } catch (org.json.JSONException e) {
                throw new IllegalStateException("Could not serialize structural target candidate", e);
            }
        }
        return heading + ": " + candidates;
    }

    private static ScreenData requireScreen(AgentState state) {
        if (state == null || state.observation == null) throw new IllegalStateException("Visual resolver has no current screenshot");
        return state.observation;
    }

    private static JSONObject parseObject(String response) {
        String json = jsonPayload(response, '{', '}');
        try { return new JSONObject(json); }
        catch (org.json.JSONException e) { throw new IllegalStateException("Vision response was not valid JSON object", e); }
    }

    private static JSONArray parseArray(String response) {
        String json = jsonPayload(response, '[', ']');
        try { return new JSONArray(json); }
        catch (org.json.JSONException e) { throw new IllegalStateException("Vision response was not valid JSON array", e); }
    }

    private static String jsonPayload(String response, char open, char close) {
        String value = response == null ? "" : response.trim();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n');
            int endFence = value.lastIndexOf("```");
            if (newline >= 0 && endFence > newline) value = value.substring(newline + 1, endFence).trim();
        }
        int first = value.indexOf(open), last = value.lastIndexOf(close);
        if (first < 0 || last < first) throw new IllegalStateException("Vision response did not contain the requested JSON payload");
        return value.substring(first, last + 1);
    }

    private static void validateCandidates(JSONArray candidates) {
        if (candidates == null) throw new IllegalStateException("Vision response omitted candidates array");
        if (candidates.length() > 10) throw new IllegalStateException("Vision response exceeded 10 target candidates");
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject item = candidates.optJSONObject(i);
            JSONArray coords = item == null ? null : item.optJSONArray("coords");
            if (item == null || item.optString("label", "").trim().isEmpty()
                    || coords == null || coords.length() != 2
                    || !normalized(coords.optInt(0, -1)) || !normalized(coords.optInt(1, -1))) {
                throw new IllegalStateException("Vision candidate " + i + " needs a label and normalized coords [x,y]");
            }
        }
    }

    private static boolean normalized(int value) {
        return value >= 0 && value <= 1000;
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }
}
