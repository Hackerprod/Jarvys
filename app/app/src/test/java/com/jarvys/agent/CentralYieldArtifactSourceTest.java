package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Actual native artifact retention, then read-only source verification. No dispatch capabilities. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, shadows=ArtifactOsShadow.class)
public class CentralYieldArtifactSourceTest {
    @Rule public final ChatTestStore chat = new ChatTestStore();
    private final String session="main";
    private static final class Fixture {
        File files, artifact; String id, text; JSONObject metadata;
    }
    private Fixture retained() throws Exception {
        Fixture f=new Fixture(); f.files=chat.newFilesDirectory();
        LocalRunStore store=chat.localRunStore(f.files);
        store.appendConversationMessage(session,"user","native retention source");
        StringBuilder text=new StringBuilder();
        for(int i=0;i<5000;i++) text.append("native evidence 東京 😀\n");
        // Keep the real native artifact below its documented full-content bound.
        f.text=text.toString();
        MainChatTranscriptStore nativeStore=new MainChatTranscriptStore(f.files,session,store);
        MainChatTranscriptStore.Retained result=nativeStore.retain(f.text);
        assertEquals(1,result.artifactIds.size()); f.id=result.artifactIds.get(0);
        f.artifact=new File(new StorageGeneration(f.files).directory("crew-context"),
                CrewCheckpointStore.digest(session.getBytes(StandardCharsets.UTF_8))+"/"+
                CrewCheckpointStore.digest("main-chat-transcript".getBytes(StandardCharsets.UTF_8))+"/"+f.id+".txt");
        f.metadata=new JSONObject().put("path",f.id+".txt").put("sha256",f.id)
                .put("chars",f.text.length()).put("bytes",f.artifact.length());
        return f;
    }
    @Test public void actualNativeLongUnicodeArtifactIsReadExactlyWithoutMetadataWrites() throws Exception {
        Fixture f=retained(); long modified=f.artifact.lastModified();
        assertEquals(f.text,CrewContextArtifacts.readConfirmedSource(f.files,session,f.id,f.metadata));
        assertEquals(modified,f.artifact.lastModified());
    }
    @Test public void missingCorruptAndWrongOwnershipFailClosed() throws Exception {
        Fixture f=retained();
        assertThrows(IOException.class,()->CrewContextArtifacts.readConfirmedSource(f.files,"other",f.id,f.metadata));
        byte[] original=Files.readAllBytes(f.artifact.toPath()); original[0]^=1;
        Files.write(f.artifact.toPath(),original);
        assertThrows(IOException.class,()->CrewContextArtifacts.readConfirmedSource(f.files,session,f.id,f.metadata));
        Files.delete(f.artifact.toPath());
        assertThrows(IOException.class,()->CrewContextArtifacts.readConfirmedSource(f.files,session,f.id,f.metadata));
    }
    @Test public void oversizedFractionalAndPathAliasMetadataAreDenied() throws Exception {
        Fixture f=retained();
        JSONObject huge=new JSONObject(f.metadata.toString()).put("chars",MainChatTranscriptStore.MAX_ARTIFACT_CHARS+1);
        assertThrows(IOException.class,()->CrewContextArtifacts.readConfirmedSource(f.files,session,f.id,huge));
        JSONObject fractional=new JSONObject(f.metadata.toString()).put("bytes",(double)f.artifact.length());
        assertThrows(IOException.class,()->CrewContextArtifacts.readConfirmedSource(f.files,session,f.id,fractional));
        JSONObject alias=new JSONObject(f.metadata.toString()).put("path","../"+f.id+".txt");
        assertThrows(IOException.class,()->CrewContextArtifacts.readConfirmedSource(f.files,session,f.id,alias));
    }
    @Test public void symlinkAndDeterministicReplacementAfterReadAreDenied() throws Exception {
        Fixture f=retained(); File target=new File(f.files,"retained-copy");
        Files.copy(f.artifact.toPath(),target.toPath()); Files.delete(f.artifact.toPath());
        Files.createSymbolicLink(f.artifact.toPath(),target.toPath());
        assertThrows(IOException.class,()->CrewContextArtifacts.readConfirmedSource(f.files,session,f.id,f.metadata));
        Files.delete(f.artifact.toPath()); Files.copy(target.toPath(),f.artifact.toPath());
        assertThrows(IOException.class,()->CrewContextArtifacts.readConfirmedSource(f.files,session,f.id,f.metadata,()->{
            try { Files.delete(f.artifact.toPath()); Files.copy(target.toPath(),f.artifact.toPath());
                Files.setLastModifiedTime(f.artifact.toPath(),java.nio.file.attribute.FileTime.fromMillis(1));
            } catch(IOException error){throw new IllegalStateException(error);}
        }));
        // The unchanged production entry still checks the complete file and digest.
        assertEquals(f.text,CrewContextArtifacts.readConfirmedSource(f.files,session,f.id,f.metadata));
    }
}
