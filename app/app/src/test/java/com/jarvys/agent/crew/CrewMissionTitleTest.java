package com.jarvys.agent.crew;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;

public class CrewMissionTitleTest {
    @Test public void normalizesOnlyPresentationWhitespaceAndPreservesLanguage() {
        assertEquals("Revisar fuentes del informe", CrewMissionTitle.agent(" \nRevisar\t fuentes\u00a0 del\u2003informe\r\n").title);
        assertEquals("分析任务结果", CrewMissionTitle.agent("分析任务结果").title);
        assertEquals("مراجعة نتائج المشروع", CrewMissionTitle.agent("مراجعة نتائج المشروع").title);
        assertEquals("Revisar 👩‍💻", CrewMissionTitle.agent("Revisar 👩‍💻").title);
        assertEquals("Cafe\u0301", CrewMissionTitle.agent("Cafe\u0301").title);
        assertEquals("Compare x > y and a < b", CrewMissionTitle.agent("Compare x > y and a < b").title);
        assertEquals("Review `parse()` behavior", CrewMissionTitle.agent("Review `parse()` behavior").title);
    }

    @Test public void limitsCodePointsRatherThanUtf16AndNeverCutsAnInstructionPrefix() {
        String sixtyEmoji = repeat("🚀", 60);
        assertEquals(120, sixtyEmoji.length());
        assertEquals(sixtyEmoji, CrewMissionTitle.agent(sixtyEmoji).title);
        assertEquals(60, CrewMissionTitle.agent(sixtyEmoji).title.codePointCount(0, sixtyEmoji.length()));
        assertFallback(CrewMissionTitle.agent(sixtyEmoji + "🚀"));
        assertEquals(repeat("界", 60), CrewMissionTitle.agent(repeat("界", 60)).title);
        assertFallback(CrewMissionTitle.agent(repeat("界", 61)));
        assertFallback(CrewMissionTitle.agent("Please review the entire project and then explain every problem in the architecture"));
    }

    @Test public void invalidMetadataHasOneNeutralFallbackAndNeverGuessesFromKeywords() {
        for (Object candidate : Arrays.asList(null, 17, new JSONObject(), "", " \n\t\u00a0 ",
                "Title\u0000", "Title\u202E", "Title\uD800", "\u200C", "\u200D", "\uFE0F", "\u0301", "\u200D\uFE0F\u0301")) {
            assertFallback(CrewMissionTitle.agent(candidate));
        }
        assertEquals("Contrastar perspectivas del ensayo", CrewMissionTitle.agent("Contrastar perspectivas del ensayo").title);
        assertFallback(CrewMissionTitle.stored("Plausible title", "unknown-source"));
        assertFallback(CrewMissionTitle.stored("Plausible title", CrewMissionTitle.FALLBACK));
    }

    @Test public void schemaTwoRoundTripAndInterruptionPreserveFullInstructions() {
        String original = "  Analiza estas condiciones.\n" + repeat("Conservar todos los detalles y límites.\n", 40) + "  ";
        String worker = "  Sólo revisa el segundo módulo.\n" + repeat("No cambies archivos.\n", 20);
        CrewBotSnapshot bot = new CrewBotSnapshot("bot", "coding", "Coding", "Inspector", "coding", worker,
                "RUNNING", "", "", "", Collections.emptyList(), 1L, 0L);
        CrewMissionSnapshot snapshot = new CrewMissionSnapshot("mission", "chat", "process", " Revisar\n arquitectura ",
                original, CrewMissionTitle.AGENT, "RUNNING", "", 1L, 0L, Collections.singletonList(bot), Collections.emptyList());
        assertEquals(2, snapshot.toJson().optInt("crewSchemaVersion"));
        CrewMissionSnapshot restored = CrewMissionSnapshot.fromJson(snapshot.toJson()).interrupted();
        assertEquals("Revisar arquitectura", restored.title);
        assertEquals(CrewMissionTitle.AGENT, restored.titleSource);
        assertEquals(original, restored.originalInstructions);
        assertEquals(worker, restored.bots.get(0).mission);
        assertEquals("INTERRUPTED", restored.status);
        assertEquals("INTERRUPTED", restored.bots.get(0).status);
        assertEquals(original, CrewMissionSnapshot.fromJson(restored.toJson()).originalInstructions);
    }

    @Test public void schemaOneKeepsRawLegacyInstructionsWithoutInventingTitle() throws Exception {
        String original = "  " + repeat("Inspect all original requirements.\n", 12) + "End.  ";
        JSONObject row = legacyRow(original);
        CrewMissionSnapshot restored = CrewMissionSnapshot.fromJson(row);
        assertNotNull(restored);
        assertEquals(original, restored.originalInstructions);
        assertEquals("", restored.title);
        assertEquals(CrewMissionTitle.FALLBACK, restored.titleSource);
        assertEquals(2, restored.toJson().getInt("crewSchemaVersion"));
        assertEquals(original, CrewMissionSnapshot.fromJson(restored.toJson()).originalInstructions);

        CrewMissionSnapshot shortLegacy = CrewMissionSnapshot.fromJson(legacyRow("  Revisar\n arquitectura  "));
        assertEquals("Revisar arquitectura", shortLegacy.title);
        assertEquals("  Revisar\n arquitectura  ", shortLegacy.originalInstructions);
        assertEquals(CrewMissionTitle.LEGACY, shortLegacy.titleSource);
    }

    @Test public void everyPublicConstructorAndMalformedStoredTitleUseSameValidation() throws Exception {
        String longValue = repeat("x", 61);
        CrewMissionSnapshot compatible = new CrewMissionSnapshot("mission", "chat", "process", longValue,
                "RUNNING", "", 1L, 0L, Collections.emptyList(), Collections.emptyList());
        assertEquals("", compatible.title);
        assertEquals(longValue, compatible.originalInstructions);
        JSONObject invalid = compatible.toJson().put("title", longValue).put("titleSource", CrewMissionTitle.AGENT);
        assertEquals("", CrewMissionSnapshot.fromJson(invalid).title);
        assertEquals(longValue, CrewMissionSnapshot.fromJson(invalid).originalInstructions);
        assertNull(CrewMissionSnapshot.fromJson(invalid.put("crewSchemaVersion", 3)));
    }

    private static JSONObject legacyRow(String title) throws Exception {
        return new JSONObject().put("crewSchemaVersion", 1).put("missionId", "legacy-mission")
                .put("conversationId", "chat").put("processId", "old-process").put("title", title)
                .put("status", "RUNNING").put("synthesis", "").put("startedAtMillis", 1L);
    }

    private static void assertFallback(CrewMissionTitle title) {
        assertEquals("", title.title);
        assertEquals(CrewMissionTitle.FALLBACK, title.source);
    }

    private static String repeat(String text, int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) value.append(text);
        return value.toString();
    }
}
