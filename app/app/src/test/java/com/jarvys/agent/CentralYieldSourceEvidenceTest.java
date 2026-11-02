package com.jarvys.agent;

import static org.junit.Assert.*;
import com.jarvys.agent.chatdb.CentralUserRootCheckpoint;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.io.File;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, shadows=ArtifactOsShadow.class)
public class CentralYieldSourceEvidenceTest {
    @Rule public final ChatTestStore chat=new ChatTestStore();
    private final String session=WorkspaceIdentity.CENTRAL_SESSION;
    @Test public void nativeLongArgumentsResultsAndAttachmentSourcesRemainExact() throws Exception {
        File files=chat.newFilesDirectory();LocalRunStore store=chat.localRunStore(files);
        AttachmentStore attachments=new AttachmentStore(new StorageGeneration(files).chatRoot());
        ChatAttachment attachment=attachments.copyFromStream(session,"note.txt","text/plain",ChatAttachment.Kind.FILE,
                new java.io.ByteArrayInputStream("attachment evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String user=store.appendConversationMessage(session,"user","source",Collections.singletonList(attachment));
        CentralUserRootCheckpoint origin=store.captureCentralUserRoot(session,user);
        ConversationTurn userTurn=store.loadConversationContext(session).get(0);
        MainChatTranscriptStore transcript=new MainChatTranscriptStore(files,session,store);
        StringBuilder text=new StringBuilder();for(int i=0;i<15000;i++)text.append("evidence ");
        String full=text.toString();Map<String,Object> args=Collections.singletonMap("query",full);
        MainChatTranscriptStore.Retained retainedArgs=transcript.retain(new JSONObject(args).toString());
        MainChatTranscriptStore.Retained retainedResult=transcript.retain(full);
        JSONObject call=new JSONObject().put("id","call").put("name","read")
                .put("arguments",new JSONObject().put("_retained_arguments",retainedArgs.text))
                .put("artifactIds",new JSONArray(retainedArgs.artifactIds));
        store.appendModelTranscriptRow(session,new JSONObject().put("type","model_tool_calls").put("batchId","batch")
                .put("userMessageId",user).put("assistantText","").put("calls",new JSONArray().put(call)));
        store.appendModelTranscriptRow(session,new JSONObject().put("type","model_tool_started").put("batchId","batch").put("callId","call"));
        store.appendModelTranscriptRow(session,new JSONObject().put("type","model_tool_result").put("batchId","batch")
                .put("callId","call").put("toolName","read").put("lifecycle","RESULT").put("output",retainedResult.text)
                .put("artifactIds",new JSONArray(retainedResult.artifactIds)));
        ConversationTurn calls=ConversationTurn.toolCalls("",Collections.singletonList(new ModelReply.Call("call","read",args)));
        calls.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH,session,"batch",""));
        ConversationTurn result=ConversationTurn.toolResult("call","read",full);
        result.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH_RESULT,session,"batch","call"));
        List<ConversationTurn> exact=Arrays.asList(userTurn,calls,result);
        store.validateCentralYieldSources(origin,exact);
        ConversationTurn changed=ConversationTurn.toolResult("call","read",full+"changed");changed.bindSource(result.source());
        assertThrows(IllegalStateException.class,()->store.validateCentralYieldSources(origin,Arrays.asList(userTurn,calls,changed)));
        ConversationTurn missing=ConversationTurn.toolResult("call","read",full);
        assertThrows(IllegalStateException.class,()->store.validateCentralYieldSources(origin,Arrays.asList(userTurn,calls,missing)));
        assertEquals(1,userTurn.attachments.size());
        java.nio.file.Files.write(attachments.resolve(session,attachment).toPath(),"changed attachment!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->store.validateCentralYieldSources(origin,exact));
        // Evidence inspection did not create a queued native Send or an execution permit.
        assertTrue(store.readCentralSubmissions(session).isEmpty());
    }
}
