package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrewContextArtifactsRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final CancellationToken token = CancellationToken.uncancellable();

    @Test public void redactsNestedCredentialsAndPreservesNonSecretEvidence() throws Exception {
        JSONObject safe = new JSONObject(CrewContextArtifacts.redact("{\"nested\":[{\"api_key\":\"secret-value\",\"grant\":\"saved-permission\",\"path\":\"src/Main.java\"}],\"result\":\"Bearer bearer-value\"}"));
        assertEquals("[REDACTED]", safe.getJSONArray("nested").getJSONObject(0).getString("api_key"));
        assertEquals("[REDACTED]", safe.getJSONArray("nested").getJSONObject(0).getString("grant"));
        assertEquals("src/Main.java", safe.getJSONArray("nested").getJSONObject(0).getString("path"));
        assertFalse(safe.toString().contains("bearer-value"));
        assertFalse(CrewContextArtifacts.redact("{ malformed password=secret-value }").contains("secret-value"));
        assertEquals("[REDACTED PRIVATE KEY]", CrewContextArtifacts.redact("-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----"));
    }

    @Test public void ownershipRestoresOnlyAfterContentDigestAndIdentityVerification() throws Exception {
        File root = temporary.newFolder();
        CrewContextArtifacts first = new CrewContextArtifacts(root, "chat-one", "bot-one");
        String id = first.save("Evidence: password=never-store-this", token);
        JSONObject ownership = first.snapshotOwnership();
        CrewContextArtifacts restored = new CrewContextArtifacts(root, "chat-one", "bot-one");
        assertFalse(read(restored, id).success);
        restored.restoreOwnership(ownership);
        assertTrue(read(restored, id).success);
        assertFalse(read(restored, id).content.contains("never-store-this"));
        assertTrue(read(restored, id).content.contains("UNTRUSTED CREW ARTIFACT"));
        CrewContextArtifacts anotherBot = new CrewContextArtifacts(root, "chat-one", "bot-two");
        assertFalse(read(anotherBot, id).success);
        assertThrows(IllegalStateException.class, () -> anotherBot.restoreOwnership(ownership));
        JSONObject invalid = new JSONObject(ownership.toString());
        invalid.getJSONObject(id).put("path", "../" + id + ".txt");
        assertThrows(IllegalStateException.class, () -> restored.restoreOwnership(invalid));
        assertTrue(read(restored, id).success);
    }

    @Test public void tamperingAndSymlinkAreRejectedEvenWhenFileLengthMatches() throws Exception {
        File root = temporary.newFolder();
        CrewContextArtifacts artifacts = new CrewContextArtifacts(root, "chat-one", "bot-one");
        String id = artifacts.save("abc", token);
        JSONObject ownership = artifacts.snapshotOwnership();
        File file = artifact(root, "chat-one", "bot-one", id);
        Files.write(file.toPath(), "xyz".getBytes(StandardCharsets.UTF_8));
        assertFalse(read(artifacts, id).success);
        assertThrows(IllegalStateException.class, artifacts::snapshotOwnership);
        assertThrows(IllegalStateException.class, () -> artifacts.restoreOwnership(ownership));
        Files.delete(file.toPath());
        File elsewhere = temporary.newFile();
        Files.write(elsewhere.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(file.toPath(), elsewhere.toPath());
        assertFalse(read(artifacts, id).success);
    }

    @Test public void paginationRetainsUnicodeAndUsesBotLocalOffsets() throws Exception {
        CrewContextArtifacts artifacts = new CrewContextArtifacts(temporary.newFolder(), "bot-one");
        StringBuilder original = new StringBuilder("😀東京é\n");
        for (int i = 0; i < 200; i++) original.append("evidence 😀 ");
        String source = original.toString();
        String id = artifacts.save(source, token);
        CoreTool reader = artifacts.recoveryTool(ignored -> 1024);
        StringBuilder recovered = new StringBuilder();
        int offset = 0;
        while (true) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("artifact_id", id);
            arguments.put("offset", offset);
            arguments.put("limit_chars", 11);
            CoreToolResult page = reader.execute(arguments, token);
            assertTrue(page.content, page.success);
            int start = page.content.indexOf('\n', page.content.indexOf('\n') + 1) + 1;
            String body = page.content.substring(start);
            assertFalse(body.isEmpty());
            assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)));
            recovered.append(body);
            offset += body.length();
            if (page.content.contains("next_offset=end\n")) break;
            assertTrue(offset <= source.length());
        }
        assertEquals(source, recovered.toString());
    }

    @Test public void cancellationAndChatDeletionDisableArtifactAccess() throws Exception {
        File root = temporary.newFolder();
        CrewContextArtifacts artifacts = new CrewContextArtifacts(root, "chat-one", "bot-one");
        String id = artifacts.save("evidence", token);
        CancellationToken cancelled = CancellationToken.cancellable();
        cancelled.cancel();
        assertThrows(CancellationException.class, () -> artifacts.save("new evidence", cancelled));
        assertThrows(CancellationException.class, () -> artifacts.recoveryTool(ignored -> 4096).execute(Collections.singletonMap("artifact_id", id), cancelled));
        new ConversationMetadataStore(root).update("chat-one", "deleted", true);
        assertFalse(read(artifacts, id).success);
        assertThrows(IllegalStateException.class, artifacts::snapshotOwnership);
        assertThrows(IllegalStateException.class, () -> artifacts.save("new evidence", token));
    }

    private CoreToolResult read(CrewContextArtifacts artifacts, String id) {
        return artifacts.recoveryTool(ignored -> 32768).execute(Collections.singletonMap("artifact_id", id), token);
    }
    private static File artifact(File root, String chat, String bot, String id) {
        return new File(root, "jarvys/crew-context/" + CrewCheckpointStore.digest(chat.getBytes(StandardCharsets.UTF_8)) + "/" + CrewCheckpointStore.digest(bot.getBytes(StandardCharsets.UTF_8)) + "/" + id + ".txt");
    }
}
