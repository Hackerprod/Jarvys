package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.connectors.ConnectionAccess
import com.jarvys.agent.connectors.ConnectionAccessProvider
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** Actual Runtime request adapter and CoreAgentModel; fake provider only records final logical inputs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[ArtifactOsShadow::class])
class CentralPreparedContextTest {
    private val storage=ChatTestStore()
    @get:Rule val rules:RuleChain=RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val central=WorkspaceIdentity.CENTRAL_SESSION
    private data class Seen(val instructions:String,val turns:List<ConversationTurn>,val prompt:String,val tools:List<ToolSpec>)
    private inner class Fixture {
        val files=storage.newFilesDirectory()
        val context:Context=object:ContextWrapper(ApplicationProvider.getApplicationContext<Context>()),ConnectionAccessProvider {
            private val connections=ConnectionAccess.forTests(emptyMap())
            override fun connectionAccess()=connections
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=files
            override fun createConfigurationContext(configuration:android.content.res.Configuration):Context =
                object:ContextWrapper(super.createConfigurationContext(configuration)) {
                    // A real locale-specific resource context must retain this fixture's storage owner.
                    override fun getApplicationContext():Context=this@Fixture.context
                    override fun getFilesDir()=files
                    override fun getDatabasePath(name:String)=this@Fixture.context.getDatabasePath(name)
                }
            override fun getDatabasePath(name:String):File=if(File(name).isAbsolute) File(name)
                else File(files,"databases/$name").also {it.parentFile!!.mkdirs()}
        }
        init {
            ChatDatabaseBridge.getInstance(context);storage.localRunStore(files)
            FreshStorageBootstrap.ensure(context)
            MemoryWritePolicy.forContext(context).setEnabledBlocking(true)
            ProviderSettings(context).apply { setProvider(ProviderSettings.Provider.OPENROUTER);setOpenRouterModel("offline/navigation") }
            check(context.getSharedPreferences("provider_context_metadata",0).edit()
                .putInt("model_offline/navigation",32768).putLong("fetched_offline/navigation",System.currentTimeMillis()).commit())
        }
        val bridge get()=ChatDatabaseBridge.getInstance(context)
        val db get()=JarvysChatDatabaseProvider.getInstance(context)
        val store get()=LocalRunStore(context)
        val seen=mutableListOf<Seen>()
        var failNext=false
        var rateLimitNext=false
        var scriptedReply:((Seen)->ModelReply)?=null
        val provider=object:ModelProviderClient {
            override fun complete(system:String,user:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,session:String,token:CancellationToken):ModelReply=error("Conversation provider required")
            override fun completeConversation(system:String,history:List<ConversationTurn>,user:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,session:String,token:CancellationToken):ModelReply {
                seen.add(Seen(system,history,user,tools))
                scriptedReply?.let { return it(seen.last()) }
                if(rateLimitNext){rateLimitNext=false;throw ProviderRateLimitException("synthetic rate limit",1L)}
                if(failNext){failNext=false;throw IllegalStateException("synthetic provider retry")}
                return ModelReply("Local result",emptyList())
            }
        }
        val runtime=CoreAgentRuntime(context,central,emptyList(),CorePromptBudget.standard(),provider)
        val registry=runtime.createTools()
        val model=runtime.requestModel(CoreAgentModel(context,provider,central),registry,true,null)
        fun save(text:String)=runBlocking{val database=db;JarvysChatDatabaseProvider.onQueryExecutor(database){PersonalAgentRepository(database).saveMemory(text,"curated",null,.5,null,MemorySaveOptions(title="Prepared context title")).id}}
        fun assertAttachmentStorage(attachment:ChatAttachment) {
            val localized=AppLanguageRuntime.localizedContext(context)
            assertSame(context,localized.applicationContext)
            assertEquals(StorageGeneration(context).chatRoot().canonicalFile,StorageGeneration(localized).chatRoot().canonicalFile)
            assertEquals(context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).canonicalFile,localized.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).canonicalFile)
            val original=AttachmentStore(context).resolve(central,attachment)
            assertTrue(original.isFile)
            assertEquals(original.canonicalFile,AttachmentStore(localized).resolve(central,attachment).canonicalFile)
        }
        fun prepare(prompt:String="Request")=model.prepareRequest(bridge.loadConversationContextSync(central),prompt,registry.declarations(),CancellationToken.uncancellable())
        fun dispatch(request:PreparedModelRequest)=model.completePrepared(request,CancellationToken.uncancellable(),null)
    }
    private fun assertCode(code:String,block:()->Unit){val error=runCatching(block).exceptionOrNull();assertNotNull(error);assertTrue(error.toString(),generateSequence(error){it.cause}.any{it.message==code})}

    @Test fun finalMeasuredSnapshotIncludesEscapedFreshMemoryDeclarationsAndPreparedAttachmentsExactly() {
        val f=Fixture();f.save("Initial <context> & exact factual content")
        val attachment=AttachmentStore(f.context).copyFromStream(central,"budget-document.txt","text/plain",null,java.io.ByteArrayInputStream("attachment body".toByteArray()))
        f.assertAttachmentStorage(attachment)
        val owner=f.store.appendConversationMessage(central,"user","Actual source prompt",listOf(attachment))
        f.assertAttachmentStorage(attachment)
        f.runtime.setMainChatSteeringCursor(owner);f.runtime.setCurrentUserMessageId(owner);f.runtime.setCurrentTurnOwnerMessageId(owner)
        val prepared=f.prepare()
        assertTrue(prepared.instructions.contains("Initial &lt;context&gt; &amp; exact factual content"))
        assertEquals(1,Regex("<curated_memory ").findAll(prepared.instructions).count())
        assertEquals(listOf(attachment.id),prepared.transcript.single().attachments.map{it.id})
        assertTrue(prepared.transcript.single().modelContent.contains("workspace_path="+JSONObject.quote("attachments/"+attachment.relativePath)))
        assertTrue(prepared.transcript.any{it.modelContent.contains("workspace_path=")})
        assertTrue(prepared.tools.any{it.name=="memory_search"})
        val declarations=buildString{for(tool in prepared.tools)append(tool.name).append('\n').append(tool.description).append('\n').append(tool.jsonSchema()).append('\n')}
        // This fixture has one plain user turn plus file metadata, no image/thinking/tool-call payload.
        assertEquals(1,prepared.transcript.size)
        val measuredBytes=listOf(prepared.instructions,declarations,prepared.prompt,prepared.transcript.single().renderedModelContent("adapter")).sumOf{it.toByteArray(Charsets.UTF_8).size}+24
        assertEquals((measuredBytes+3)/4,prepared.estimateTokens())
        f.dispatch(prepared)
        val seen=f.seen.single();assertSame(prepared.transcript,seen.turns);assertSame(prepared.tools,seen.tools)
        assertEquals(prepared.instructions,seen.instructions);assertEquals(prepared.prompt,seen.prompt)
        assertTrue(prepared.estimateTokens()>prepared.overheadTokens())
        f.save("Next request newly saved marker")
        val refreshed=f.prepare();assertTrue(refreshed.instructions.contains("Next request newly saved marker"));assertFalse(prepared.instructions.contains("Next request newly saved marker"))
        f.dispatch(refreshed);assertEquals(refreshed.instructions,f.seen.last().instructions)
        ChatDatabaseBridge.closeInstanceForTests(f.files)
        assertTrue(f.prepare().instructions.contains("Next request newly saved marker"))
        MemoryWritePolicy.forContext(f.context).setEnabledBlocking(false)
        val off=f.prepare();assertFalse(off.instructions.contains("<curated_memory "));assertFalse(off.instructions.contains("Next request newly saved marker"))
        f.dispatch(off);assertEquals(off.instructions,f.seen.last().instructions)
    }

    @Test fun overBudgetAndStaleProtectedSourceNeverReachProviderAndRetryRepreparesInstructions() {
        val f=Fixture();val owner=f.store.appendConversationMessage(central,"user","Protected original")
        f.runtime.setMainChatSteeringCursor(owner);f.runtime.setCurrentUserMessageId(owner);f.runtime.setCurrentTurnOwnerMessageId(owner)
        val tooLarge=f.prepare("Request "+"z".repeat(2_000_000))
        assertTrue(ConversationCompactionPolicy.shouldCompact(tooLarge.estimateTokens(),tooLarge.contextWindow))
        assertCode("context_protected_budget_exceeded"){f.dispatch(tooLarge)};assertTrue(f.seen.isEmpty())
        val old=f.prepare();f.store.appendConversationMessage(central,"user","New source after prepare")
        assertCode("context_protected_payload_unavailable"){f.dispatch(old)};assertTrue(f.seen.isEmpty())
        val beforeToggle=f.prepare()
        MemoryWritePolicy.forContext(f.context).setEnabledBlocking(false)
        assertCode("context_memory_unavailable"){f.dispatch(beforeToggle)};assertTrue(f.seen.isEmpty())
        MemoryWritePolicy.forContext(f.context).setEnabledBlocking(true)
        assertCode("context_memory_unavailable"){f.dispatch(beforeToggle)};assertTrue(f.seen.isEmpty())
        val retry=f.prepare();f.failNext=true
        assertCode("synthetic provider retry"){f.dispatch(retry)}
        f.save("Refreshed after a provider failure")
        val next=f.prepare();assertTrue(next.instructions.contains("Refreshed after a provider failure"))
        assertFalse(retry.instructions.contains("Refreshed after a provider failure"))
        f.dispatch(next);assertEquals(2,f.seen.size);assertEquals(next.instructions,f.seen.last().instructions)
    }
    @Test fun actualCoreLoopPreparedPreflightRefusesOversizeWithoutProviderOrTruncatedProtectedTurns() {
        val f=Fixture();val owner=f.store.appendConversationMessage(central,"user","Protected live input")
        f.runtime.setMainChatSteeringCursor(owner);f.runtime.setCurrentUserMessageId(owner);f.runtime.setCurrentTurnOwnerMessageId(owner)
        val originals=f.bridge.loadConversationContextSync(central)
        val loop=CoreAgentLoop(f.model,f.registry,"stale initial instructions are not the measured payload",central,CorePromptBudget.standard(),null)
        assertCode("context_protected_budget_exceeded"){loop.run("oversize "+"x".repeat(2_000_000),originals,CancellationToken.uncancellable(),null)}
        assertTrue(f.seen.isEmpty())
        originals.forEach{original->assertTrue(loop.transcriptSnapshot().any{it.source()==original.source() && it.content==original.content})}
    }

    @Test fun actualCoreLoopRetryRefreshesMemoryAfterTheRealWaitBoundary() {
        val f=Fixture();val owner=f.store.appendConversationMessage(central,"user","Retry source")
        f.runtime.setMainChatSteeringCursor(owner);f.runtime.setCurrentUserMessageId(owner);f.runtime.setCurrentTurnOwnerMessageId(owner)
        var waits=0
        val waiter=ProviderRetryWaiter({_,_->waits++;f.save("Memory admitted during provider retry wait")},{.5})
        f.rateLimitNext=true
        val loop=CoreAgentLoop(f.model,f.registry,"old instructions",central,CorePromptBudget.standard(),null,waiter)
        val result=loop.run("Continue retry",f.bridge.loadConversationContextSync(central),CancellationToken.cancellable(),null)
        assertEquals("COMPLETED",result.outcome);assertEquals(1,waits);assertEquals(2,f.seen.size)
        assertFalse(f.seen[0].instructions.contains("Memory admitted during provider retry wait"))
        assertTrue(f.seen[1].instructions.contains("Memory admitted during provider retry wait"))
        assertTrue(f.seen[1].turns.any{it.kind==ConversationTurn.Kind.MODEL_ERROR})
    }

    @Test fun attachmentExpansionAloneCrossesMeasuredBudgetAndPreventsDispatch() {
        val f=Fixture()
        val attachment=AttachmentStore(f.context).copyFromStream(central,"independent-attachment-budget.txt","text/plain",null,java.io.ByteArrayInputStream("fixture".toByteArray()))
        f.assertAttachmentStorage(attachment)
        val owner=f.store.appendConversationMessage(central,"user","Same visible text",listOf(attachment))
        f.assertAttachmentStorage(attachment)
        f.runtime.setMainChatSteeringCursor(owner);f.runtime.setCurrentUserMessageId(owner);f.runtime.setCurrentTurnOwnerMessageId(owner)
        val originals=f.bridge.loadConversationContextSync(central)
        val core=CoreAgentModel(f.context,f.provider,central);val token=CancellationToken.uncancellable()
        val plain=originals.map{it.withoutAttachments()}
        val base=core.prepareMainChat("Fixed instructions",plain,"",emptyList(),token)
        val expanded=core.prepareMainChat("Fixed instructions",originals,"",emptyList(),token)
        assertEquals(plain.map{it.content},expanded.transcript.map{it.content})
        assertTrue(expanded.transcript.sumOf{it.modelContent.length}>plain.sumOf{it.modelContent.length})
        assertTrue("Attachment model-facing expansion must affect the budget",expanded.estimateTokens()>base.estimateTokens())
        val threshold=ConversationCompactionPolicy.pressureThreshold(base.contextWindow)
        var padding="p".repeat((threshold-base.estimateTokens())*4)
        var within=core.prepareMainChat("Fixed instructions",plain,padding,emptyList(),token)
        while(within.estimateTokens()>threshold){padding=padding.dropLast(1);within=core.prepareMainChat("Fixed instructions",plain,padding,emptyList(),token)}
        val over=core.prepareMainChat("Fixed instructions",originals,padding,emptyList(),token)
        assertFalse(ConversationCompactionPolicy.shouldCompact(within.estimateTokens(),within.contextWindow))
        assertTrue(ConversationCompactionPolicy.shouldCompact(over.estimateTokens(),over.contextWindow))
        assertCode("context_protected_budget_exceeded"){core.completePrepared(over,token,null)}
        assertTrue(f.seen.isEmpty())
    }

    @Test fun ownershipSetRevocationAfterPreparationRejectsDispatchWithoutRepairOrCachedAuthority() {
        val f=Fixture();val workspace=WorkspaceRegistry(f.context).use{it.createWorkspace("prepared-owner","Prepared owner")}
        f.store.appendConversationMessage(central,"user","Ownership guarded request")
        val request=f.prepare()
        // Adversarial retained registry revocation between prepare and dispatch; no model permission is fabricated.
        android.database.sqlite.SQLiteDatabase.openDatabase(f.context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use{
            it.execSQL("DELETE FROM workspace WHERE workspace_id=?",arrayOf(workspace.workspaceId))
        }
        assertCode("context_memory_ownership_changed"){f.dispatch(request)}
        assertTrue(f.seen.isEmpty());assertTrue(WorkspaceRegistry(f.context).use{it.listWorkspaces()}.isEmpty())
    }

    /** Mutates only between real native preparation and real native completion; no runtime hook. */
    private inner class PreparationRace(closedHistory:Boolean=false, largeClosedHistory:Boolean=true) {
        val f=Fixture()
        init {
            if(closedHistory) repeat(12) { i ->
                val owner=f.store.appendConversationMessage(central,"user",
                    if(i<2 && largeClosedHistory) "Old closed source $i "+"x".repeat(30_000) else "Recent closed source $i")
                f.store.appendConversationMessage(central,"assistant",
                    if(i<2 && largeClosedHistory) "Old closed answer $i "+"y".repeat(30_000) else "Recent closed answer $i",
                    null,java.util.UUID.randomUUID().toString(),owner,"COMPLETED")
            }
        }
        val owner=f.store.appendConversationMessage(central,"user","Exact retained request")
        val scope=WorkspaceRegistry(f.context).use { it.createWorkspace("prepared-race", "Preparation race") }
        val assignment=WorkspaceRegistry(f.context).use { it.prepareAssignment(scope.workspaceId,
            "prepared-race-assignment", "prepared-race-request", "Owner request", "custom",
            "Observe retained evidence", emptyList(), "Worker", com.jarvys.agent.crew.CrewMissionAccess.STANDARD) }
        val originals=f.bridge.loadConversationContextSync(central)
        val flags=mutableListOf<Boolean>()
        val requests=mutableListOf<PreparedModelRequest>()
        val completed=mutableListOf<PreparedModelRequest>()
        var mutate:(Int,CancellationToken)->Unit={_,_->}
        var beforePreparation:(Int)->Unit={_ ->}
        var requestPrompt:(Int,String)->String={_,prompt->prompt}
        var completionFailure:((RuntimeException)->Unit)?=null
        val token=CancellationToken.cancellable()
        init {
            f.runtime.setMainChatSteeringCursor(owner)
            f.runtime.setCurrentUserMessageId(owner)
            f.runtime.setCurrentTurnOwnerMessageId(owner)
        }
        val model=object:CoreAgentLoop.Model by f.model {
            // Kotlin delegation does not forward these Java interface defaults.
            override fun usesPreparedRequests()=f.model.usesPreparedRequests()
            override fun onCompactionCompleted()=f.model.onCompactionCompleted()
            override fun prepareRequest(transcript:List<ConversationTurn>,prompt:String,tools:List<ToolSpec>,
                token:CancellationToken):PreparedModelRequest=prepareRequest(transcript,prompt,tools,token,false)
            override fun prepareRequest(transcript:List<ConversationTurn>,prompt:String,tools:List<ToolSpec>,
                token:CancellationToken,activityAttempt:Boolean):PreparedModelRequest {
                flags+=activityAttempt
                beforePreparation(flags.size)
                val request=f.model.prepareRequest(transcript,requestPrompt(flags.size,prompt),tools,token,activityAttempt)
                requests+=request
                mutate(requests.size,token)
                return request
            }
            override fun completePrepared(request:PreparedModelRequest,token:CancellationToken,
                listener:ModelProviderClient.TextDeltaListener?):ModelReply {
                completed+=request
                try { return f.model.completePrepared(request,token,listener) }
                catch(failure:RuntimeException) { completionFailure?.invoke(failure);throw failure }
            }
        }
        fun card(ordinal:Int) {
            val ref=com.jarvys.agent.workspaces.MissionRef.of(scope.workspaceId,assignment.assignmentId,assignment.missionId)
            f.store.appendMissionReferenceMessage(central,"Retained native mission card $ordinal",ref.toJson(),"preparation-card:$ordinal")
        }
        fun revokeOwner() {
            android.database.sqlite.SQLiteDatabase.openDatabase(f.context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path,
                null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DELETE FROM workspace WHERE workspace_id=?",arrayOf(scope.workspaceId))
            }
        }
        fun run():CoreAgentLoop.Result=CoreAgentLoop(model,f.registry,"Actual runtime instructions",central,
            CorePromptBudget.standard(),null).run("Continue",originals,token,null)
    }

    @Test fun nativeMissionCardDriftRepreparesOnceWithoutDispatchingStaleRequestOrDuplicatingActivity() {
        val race=PreparationRace()
        race.mutate={attempt,_->if(attempt==1)race.card(attempt)}
        assertEquals("COMPLETED",race.run().outcome)
        assertEquals(listOf(true,false),race.flags)
        assertEquals(2,race.requests.size);assertEquals(2,race.completed.size)
        assertNotSame(race.requests[0],race.requests[1])
        assertNotEquals(race.requests[0].protection.fingerprint,race.requests[1].protection.fingerprint)
        assertEquals(1,race.f.seen.size)
        assertEquals(race.requests[1].instructions,race.f.seen.single().instructions)
        assertEquals(race.originals.map { it.source() to it.content },race.f.seen.single().turns.map { it.source() to it.content })
        assertFalse(race.f.seen.single().turns.any { it.content.contains("Retained native mission card") })
        assertNotNull(race.f.store.readConversationMessageByStableId(central,"preparation-card:1"))
    }

    @Test fun secondNativeProtectionDriftFailsClosedBeforeAnyProviderRequest() {
        val race=PreparationRace();race.mutate={attempt,_->race.card(attempt)}
        assertCode("context_protection_changed"){race.run()}
        assertEquals(listOf(true,false),race.flags)
        assertEquals(2,race.requests.size);assertTrue(race.f.seen.isEmpty())
        assertEquals(race.originals.map { it.source() to it.content },race.f.bridge.loadConversationContextSync(central).map { it.source() to it.content })
    }

    @Test fun sourcePayloadMutationIsNotPreparationDriftAndNeverRetries() {
        val race=PreparationRace()
        race.mutate={_,_->runBlocking {
            val db=race.f.db
            db.withChatWriteTransaction { db.chatSql.execSQL(
                "UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind='text'",
                arrayOf("Changed retained source",central,race.owner)) }
        }}
        assertCode("context_protected_payload_changed"){race.run()}
        assertEquals(listOf(true),race.flags);assertTrue(race.f.seen.isEmpty())
    }

    @Test fun simultaneousCardDriftAndMemoryOwnerRevocationNeverReprepareIntoNewAuthority() {
        val race=PreparationRace();race.mutate={attempt,_->race.card(attempt);race.revokeOwner()}
        assertCode("context_memory_ownership_changed"){race.run()}
        assertEquals(listOf(true),race.flags);assertTrue(race.f.seen.isEmpty())
    }

    @Test fun simultaneousCardDriftAndMemoryDisableEnableNeverRefreshesRevokedTicket() {
        val race=PreparationRace()
        race.mutate={attempt,_->
            race.card(attempt)
            MemoryWritePolicy.forContext(race.f.context).setEnabledBlocking(false)
            MemoryWritePolicy.forContext(race.f.context).setEnabledBlocking(true)
        }
        assertCode("context_memory_unavailable"){race.run()}
        assertEquals(listOf(true),race.flags);assertTrue(race.f.seen.isEmpty())
    }

    @Test fun permittedReprepareCannotAdoptNewMemoryEpochBetweenAttempts() {
        val race=PreparationRace()
        race.mutate={attempt,_->if(attempt==1)race.card(attempt)}
        race.beforePreparation={attempt->if(attempt==2) {
            MemoryWritePolicy.forContext(race.f.context).setEnabledBlocking(false)
            MemoryWritePolicy.forContext(race.f.context).setEnabledBlocking(true)
        }}
        assertCode("context_memory_unavailable"){race.run()}
        assertEquals(listOf(true,false),race.flags);assertTrue(race.f.seen.isEmpty())
    }

    @Test fun simultaneousCardDriftAndCancellationNeverReprepareOrDispatch() {
        val race=PreparationRace();race.mutate={attempt,token->race.card(attempt);token.cancel()}
        assertThrows(java.util.concurrent.CancellationException::class.java){race.run()}
        assertEquals(listOf(true),race.flags);assertTrue(race.f.seen.isEmpty())
    }

    @Test fun sameMessageProviderExceptionIsNotTrustedPreDispatchDrift() {
        val race=PreparationRace()
        race.f.scriptedReply={throw IllegalStateException("context_protection_changed")}
        assertCode("context_protection_changed"){race.run()}
        assertEquals(listOf(true),race.flags);assertEquals(1,race.f.seen.size)
    }

    @Test fun actualStaleSignalForwardedByProviderCannotAuthorizeAnotherAttempt() {
        val race=PreparationRace();var captured:RuntimeException?=null
        race.mutate={attempt,_->if(attempt==1)race.card(attempt)}
        race.completionFailure={failure->if(captured==null)captured=failure}
        race.f.scriptedReply={throw requireNotNull(captured)}
        assertThrows(RuntimeException::class.java){race.run()}
        assertNotNull(captured)
        assertEquals(listOf(true,false),race.flags)
        assertEquals(2,race.requests.size);assertEquals(1,race.f.seen.size)
    }

    @Test fun nativeProtectionDriftAfterCommittedCompactionNeverReplaysCompactionOrDispatchesPrincipal() {
        val race=PreparationRace(closedHistory=true)
        val protected=race.originals.takeLast(21).map { it.source() to it.content }
        var summaries=0
        val summarizer=object:ModelProviderClient {
            override fun complete(system:String,user:String,images:List<com.jarvys.agent.device.ScreenData>,
                tools:List<ToolSpec>,session:String,token:CancellationToken):ModelReply=error("Conversation summary required")
            override fun completeConversation(system:String,history:List<ConversationTurn>,user:String,
                images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,session:String,
                token:CancellationToken):ModelReply {
                summaries++
                return ModelReply("Retained closed-prefix summary",emptyList())
            }
        }
        val compactor=ConversationCompactor(central,CoreAgentModel(summarizer,central),race.f.store)
        race.mutate={attempt,_->if(attempt==2)race.card(attempt)}
        val loop=CoreAgentLoop(race.model,race.f.registry,"Actual runtime instructions",central,
            CorePromptBudget.standard(),compactor)
        assertCode("context_protection_changed"){loop.run("Continue",race.originals,race.token,null)}
        assertEquals("One admitted preparation and one post-compaction reprepare, never stale refresh",listOf(true,false),race.flags)
        assertEquals(1,summaries);assertTrue(race.f.seen.isEmpty())
        assertEquals(1,race.completed.size)
        assertTrue(race.completed.single().sourceTranscript.any { it.kind==ConversationTurn.Kind.COMPACTION_SUMMARY })
        val retained=race.f.store.loadConversationContext(central)
        assertEquals(1,retained.count { it.kind==ConversationTurn.Kind.COMPACTION_SUMMARY })
        assertEquals(protected,retained.takeLast(21).map { it.source() to it.content })
    }

    @Test fun actualRuntimeStaleSignalCannotTransferAcrossRequestOrTokenAtLoopBoundary() {
        for(crossToken in listOf(false,true)) {
            val race=PreparationRace()
            val originalToken=if(crossToken)CancellationToken.cancellable() else race.token
            val original=race.f.model.prepareRequest(race.originals,"Continue",race.f.registry.declarations(),originalToken)
            race.card(1)
            val stale=assertThrows(CoreAgentRuntime.PreparedContextStale::class.java) {
                race.f.model.completePrepared(original,originalToken,null)
            }
            val forwarding=object:CoreAgentLoop.Model by race.model {
                override fun usesPreparedRequests()=race.model.usesPreparedRequests()
                override fun prepareRequest(transcript:List<ConversationTurn>,prompt:String,tools:List<ToolSpec>,
                    token:CancellationToken,activityAttempt:Boolean):PreparedModelRequest {
                    val fresh=race.model.prepareRequest(transcript,prompt,tools,token,activityAttempt)
                    return if(crossToken)original else fresh
                }
                override fun completePrepared(request:PreparedModelRequest,token:CancellationToken,
                    listener:ModelProviderClient.TextDeltaListener?):ModelReply { throw stale }
            }
            val loop=CoreAgentLoop(forwarding,race.f.registry,"Actual runtime instructions",central,CorePromptBudget.standard(),null)
            assertSame(stale,assertThrows(CoreAgentRuntime.PreparedContextStale::class.java) {
                loop.run("Continue",race.originals,race.token,null)
            })
            assertEquals(listOf(true),race.flags);assertTrue(race.f.seen.isEmpty())
        }
    }

    @Test fun oversizedRefreshWithRevokedMemoryTicketCannotStartCompactionOrCreateArtifacts() {
        val race=PreparationRace(closedHistory=true,largeClosedHistory=false)
        assertTrue("Real closed prefix must be eligible, not a vacuous no-compaction fixture",
            race.f.store.readInteractionProtection(central).protectedPrefixLimit(race.originals,emptySet())>0)
        val artifactsBefore=race.f.store.readModelArtifactRows(central).map { it.toString() }
        var summaries=0
        val summarizer=object:ModelProviderClient {
            override fun complete(system:String,user:String,images:List<com.jarvys.agent.device.ScreenData>,
                tools:List<ToolSpec>,session:String,token:CancellationToken):ModelReply=error("Conversation summary required")
            override fun completeConversation(system:String,history:List<ConversationTurn>,user:String,
                images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,session:String,
                token:CancellationToken):ModelReply {
                summaries++
                return ModelReply("Must not summarize on refresh",emptyList())
            }
        }
        val compactor=ConversationCompactor(central,CoreAgentModel(summarizer,central),race.f.store)
        race.mutate={attempt,_->if(attempt==1)race.card(attempt)}
        race.beforePreparation={attempt->if(attempt==2) {
            MemoryWritePolicy.forContext(race.f.context).setEnabledBlocking(false)
            MemoryWritePolicy.forContext(race.f.context).setEnabledBlocking(true)
        }}
        race.requestPrompt={attempt,prompt->if(attempt==2)prompt+"x".repeat(2_000_000) else prompt}
        val loop=CoreAgentLoop(race.model,race.f.registry,"Actual runtime instructions",central,
            CorePromptBudget.standard(),compactor)
        assertCode("context_protected_budget_exceeded"){loop.run("Continue",race.originals,race.token,null)}
        assertEquals(listOf(true,false),race.flags)
        assertEquals(2,race.requests.size);assertEquals(1,race.completed.size)
        assertEquals(0,summaries);assertTrue(race.f.seen.isEmpty())
        assertEquals(artifactsBefore,race.f.store.readModelArtifactRows(central).map { it.toString() })
        assertEquals(race.originals.map { it.source() to it.content },
            race.f.store.loadConversationContext(central).map { it.source() to it.content })
    }

    private class ActivityClock : java.time.Clock() {
        var now=java.time.Instant.EPOCH
        override fun instant()=now
        override fun getZone():java.time.ZoneId=java.time.ZoneOffset.UTC
        override fun withZone(zone:java.time.ZoneId):java.time.Clock { require(zone==java.time.ZoneOffset.UTC);return this }
    }
    private fun Fixture.activityRows():Map<String,List<List<String?>>> = runBlocking {
        val database=db
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
            listOf("memory_entries","memory_revisions","memory_save_operations","memory_session_finalizations","memory_prompts").associateWith { table ->
                database.chatSql.query("SELECT * FROM $table ORDER BY rowid").use { c -> buildList {
                    while(c.moveToNext()) add((0 until c.columnCount).map { i -> if(c.isNull(i)) null else
                        if(c.getType(i)==android.database.Cursor.FIELD_TYPE_BLOB) android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP) else c.getString(i) })
                } }
            }
        } }
    }
    private fun Fixture.runAdmitted(activity:MemorySessionActivity,owner:String):CoreAgentLoop.Result {
        runtime.withMemorySessionActivity(activity)
        runtime.setMainChatSteeringCursor(owner);runtime.setCurrentUserMessageId(owner);runtime.setCurrentTurnOwnerMessageId(owner)
        return runtime.runMainChat("Continue the actual admitted request",store.loadConversationContext(central),emptyList(),CancellationToken.cancellable(),null)
    }
    private val reminder="\n\n⚠️ No memory_save calls for this project in 10 minutes. Did you make any decisions, fix bugs, or discover something worth persisting?"

    @Test fun admittedProviderReceivesExactReminderThenCommittedSaveAndFinalizationSuppressItAndFinallyClearsActivity() {
        val f=Fixture();f.save("Existing factual context for the real request")
        val clock=ActivityClock();val activity=MemorySessionActivity(clock)
        val owner=f.store.appendConversationMessage(central,"user","Protected original nudge source")
        val before=f.activityRows();val counts=mutableListOf<List<Long>>()
        f.scriptedReply={ seen ->
            counts+=activity.snapshotCounts().values.single()
            assertTrue(seen.turns.any { it.source()?.sourceId==owner && it.content=="Protected original nudge source" })
            when(f.seen.size) {
                1 -> {
                    assertEquals(listOf(2L,0L),counts.last());assertFalse(seen.instructions.contains("No memory_save calls"))
                    clock.now=java.time.Instant.EPOCH.plusSeconds(600)
                    ModelReply("",(1..4).map { ModelReply.Call("nudge-search-$it","memory_search",mapOf("query" to "factual")) })
                }
                2 -> {
                    assertEquals(listOf(7L,0L),counts.last());assertTrue(seen.instructions.contains(reminder))
                    assertFalse(seen.instructions.contains("No mem_save calls"));assertEquals(before,f.activityRows())
                    ModelReply("",listOf(ModelReply.Call("nudge-save","memory_save",mapOf("title" to "Committed reminder response","content" to "Explicit committed response to the reminder"))))
                }
                3 -> {
                    assertEquals(listOf(8L,1L),counts.last());assertFalse(seen.instructions.contains("No memory_save calls"))
                    ModelReply("",listOf(ModelReply.Call("nudge-finalize","memory_session_finalize",mapOf("summary" to "Completed the explicit reminder exercise"))))
                }
                4 -> {
                    assertEquals(listOf(8L,1L),counts.last());assertFalse(seen.instructions.contains("No memory_save calls"))
                    ModelReply("Finished ordinarily after finalization",emptyList())
                }
                else -> error("Unexpected provider attempt")
            }
        }
        assertEquals("COMPLETED",f.runAdmitted(activity,owner).outcome)
        assertEquals(4,f.seen.size);assertTrue(activity.snapshotCounts().isEmpty())
        val turns=f.store.loadConversationContext(central)
        val saved=turns.single { it.kind==ConversationTurn.Kind.TOOL_RESULT && it.toolCallId=="nudge-save" }.content
        assertTrue(saved,saved.contains("operation_replayed=false"))
        val finalized=JSONObject(turns.single { it.kind==ConversationTurn.Kind.TOOL_RESULT && it.toolCallId=="nudge-finalize" }.content)
        assertFalse(finalized.getBoolean("already_finalized"))
        assertEquals(1,f.activityRows().getValue("memory_session_finalizations").size)
        assertTrue(f.bridge.personalAgentMemorySessions().single { it.ownerMessageId==owner }.endedAt!=null)
    }

    @Test fun actualReminderReservesUtf8EnvelopeBeforeEscapingAndKeepsProtectedOriginalAtProvider() {
        val f=Fixture()
        runBlocking { val database=f.db;JarvysChatDatabaseProvider.onQueryExecutor(database) {
            repeat(20) { i -> PersonalAgentRepository(database).saveMemory("Distinct Unicode context body $i", "curated",null,.5,null,
                MemorySaveOptions(title="Unicode $i "+"漢😀".repeat(400))) }
        } }
        val owner=f.store.appendConversationMessage(central,"user","Protected original remains exact: 漢😀")
        val clock=ActivityClock();val activity=MemorySessionActivity(clock)
        val memoryBefore=f.activityRows()
        f.scriptedReply={ seen -> when(f.seen.size) {
            1 -> {
                clock.now=java.time.Instant.EPOCH.plusSeconds(600)
                ModelReply("",(1..4).map { ModelReply.Call("unicode-search-$it","memory_search",mapOf("query" to "nudgeabsentneedle")) })
            }
            2 -> {
                assertEquals(listOf(7L,0L),activity.snapshotCounts().values.single())
                val escaped=seen.instructions.substringAfter("<curated_memory authority=\"factual context\">\n").substringBefore("\n</curated_memory>")
                val envelope=escaped.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&")
                val bytes=envelope.toByteArray(Charsets.UTF_8)
                assertTrue(bytes.size<=16_384);assertTrue(bytes.size>16_000)
                assertEquals(envelope,String(bytes,Charsets.UTF_8));assertFalse(envelope.contains('\uFFFD'))
                assertTrue(envelope.endsWith(reminder));assertTrue(envelope.contains("Memory stats:"))
                assertTrue(seen.turns.any { it.source()?.sourceId==owner && it.content=="Protected original remains exact: 漢😀" })
                assertEquals(memoryBefore,f.activityRows())
                ModelReply("Completed with protected input intact",emptyList())
            }
            else -> error("Unexpected Unicode provider attempt")
        } }
        val outcome=f.runAdmitted(activity,owner)
        val resultTurns=f.store.loadConversationContext(central).filter { it.kind==ConversationTurn.Kind.TOOL_RESULT }
        val budgetFailures=resultTurns.count { it.content.contains("memory_search_result_budget_exceeded") }
        assertEquals("Unicode consumer: reason=${outcome.interruptionReason}; providerCalls=${f.seen.size}; resultCount=${resultTurns.size}; searchBudgetFailures=$budgetFailures",
            "COMPLETED",outcome.outcome)
        assertEquals(0,budgetFailures);assertEquals(4,resultTurns.size)
        for(i in 1..4) {
            val result=resultTurns.single { it.toolCallId=="unicode-search-$i" }
            assertFalse(result.content.startsWith("Tool error:"))
            assertEquals(0,JSONObject(result.content).getJSONArray("results").length())
        }
        assertEquals(2,f.seen.size);assertTrue(activity.snapshotCounts().isEmpty())
    }

    @Test fun staleReminderPreparationOffAbaAndOwnerRevocationNeverDispatchTheStaleRequest() {
        for(fault in listOf("off","aba","owner")) {
            val f=Fixture();f.save("Stable factual context before $fault")
            val workspace=WorkspaceRegistry(f.context).use { it.createWorkspace("nudge-owner","Nudge owner") }
            val owner=f.store.appendConversationMessage(central,"user","Actual guarded reminder request")
            val clock=ActivityClock();val activity=MemorySessionActivity(clock)
            val before=f.activityRows();var armed=false;var changed=false;var attempted:List<Long>?=null
            f.scriptedReply={ _ ->
                check(f.seen.size==1) { "Stale memory-bearing request reached provider" }
                clock.now=java.time.Instant.EPOCH.plusSeconds(600);armed=true
                ModelReply("",(1..4).map { ModelReply.Call("guard-search-$it","memory_search",mapOf("query" to "factual")) })
            }
            assertCode(if(fault=="owner") "context_memory_ownership_changed" else "context_memory_unavailable") {
                runBlocking { ChatSqlInstrumentation.observeStatements({ statement ->
                    if(armed && statement.startsWith("SELECT COUNT(*) FROM memory_sessions WHERE")) {
                        armed=false;changed=true;attempted=activity.snapshotCounts().values.single()
                        if(fault=="owner") android.database.sqlite.SQLiteDatabase.openDatabase(
                            f.context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
                            it.execSQL("DELETE FROM workspace WHERE workspace_id=?",arrayOf(workspace.workspaceId))
                        } else {
                            MemoryWritePolicy.forContext(f.context).setEnabledBlocking(false)
                            if(fault=="aba") MemoryWritePolicy.forContext(f.context).setEnabledBlocking(true)
                        }
                    }
                }) { f.runAdmitted(activity,owner) } }
            }
            assertTrue(fault,changed);assertEquals(fault,listOf(7L,0L),attempted)
            assertEquals(fault,1,f.seen.size);assertTrue(fault,activity.snapshotCounts().isEmpty())
            assertEquals(fault,before,f.activityRows())
        }
    }

    @Test fun actualProviderRetryCountsANewContextAttemptWithoutResettingTheReminder() {
        val f=Fixture();f.save("Factual context for a real retry")
        val owner=f.store.appendConversationMessage(central,"user","Retry preserves this protected source")
        val clock=ActivityClock();val activity=MemorySessionActivity(clock)
        f.scriptedReply={ seen -> when(f.seen.size) {
            1 -> {
                assertEquals(listOf(2L,0L),activity.snapshotCounts().values.single())
                clock.now=java.time.Instant.EPOCH.plusSeconds(600)
                ModelReply("",(1..4).map { ModelReply.Call("retry-search-$it","memory_search",mapOf("query" to "factual")) })
            }
            2 -> {
                assertEquals(listOf(7L,0L),activity.snapshotCounts().values.single());assertTrue(seen.instructions.contains(reminder))
                throw ProviderRateLimitException("Synthetic admitted provider retry",1L)
            }
            3 -> {
                assertEquals(listOf(8L,0L),activity.snapshotCounts().values.single());assertTrue(seen.instructions.contains(reminder))
                assertTrue(seen.turns.any { it.kind==ConversationTurn.Kind.MODEL_ERROR })
                ModelReply("Retry completed",emptyList())
            }
            else -> error("Unexpected provider retry")
        } }
        assertEquals("COMPLETED",f.runAdmitted(activity,owner).outcome)
        assertEquals(3,f.seen.size);assertTrue(activity.snapshotCounts().isEmpty())
    }

    @Test fun realPrefixCompactionRepreparesWithinOneAttemptWithoutInflatingAdmittedActivity() {
        val f=Fixture();val clock=ActivityClock();val activity=MemorySessionActivity(clock)
        repeat(12) { i ->
            val text=if(i<2) "Old closed evidence $i "+"x".repeat(30_000) else "Recent closed source $i"
            val owner=f.store.appendConversationMessage(central,"user",text)
            f.store.appendConversationMessage(central,"assistant",if(i<2) "Closed response $i "+"y".repeat(30_000) else "Recent completed response $i",
                null,java.util.UUID.randomUUID().toString(),owner,"COMPLETED")
        }
        val owner=f.store.appendConversationMessage(central,"user","Protected current input after twelve closed owners")
        val protected=f.store.loadConversationContext(central).takeLast(21).map { it.source() to it.content }
        var technical=0;var extraction=0;var principal=0
        f.scriptedReply={ seen ->
            assertEquals(listOf(2L,0L),activity.snapshotCounts().values.single())
            if(seen.tools.map { it.name }.toSet()==setOf("user_profile_set","memory_save","memory_search","memory_get")) {
                extraction++
                ModelReply("NO_REPLY",emptyList())
            } else if(seen.tools.isEmpty()) {
                technical++
                ModelReply("{\"title\":\"Technical closed-prefix digest\",\"text\":\"Retained closed-prefix evidence\"}",emptyList())
            } else {
                assertTrue("Principal catalog must be real",seen.tools.any { it.name=="memory_session_finalize" })
                principal++
                assertTrue(seen.turns.any { it.kind==ConversationTurn.Kind.COMPACTION_SUMMARY })
                assertEquals(protected,seen.turns.takeLast(21).map { it.source() to it.content })
                ModelReply("One principal provider attempt after compaction",emptyList())
            }
        }
        assertEquals("COMPLETED",f.runAdmitted(activity,owner).outcome)
        assertTrue("Actual summary and digest calls must occur",technical>=2)
        assertTrue("Actual extraction pass must occur independently",extraction>=1);assertEquals(1,principal)
        assertTrue(f.store.loadConversationContext(central).any { it.kind==ConversationTurn.Kind.COMPACTION_SUMMARY })
        assertTrue(activity.snapshotCounts().isEmpty())
    }

    @Test fun actualNonMainRuntimeDoesNotAcquireActivityOrCanonicalSessionFromTheInjectedTracker() {
        val f=Fixture();val clock=ActivityClock();clock.now=java.time.Instant.EPOCH.plusSeconds(600)
        val activity=MemorySessionActivity(clock);val before=f.activityRows()
        assertTrue(f.bridge.personalAgentMemorySessions().isEmpty())
        f.scriptedReply={ seen ->
            assertTrue(activity.snapshotCounts().isEmpty());assertFalse(seen.instructions.contains("No memory_save calls"))
            ModelReply("Non-main runtime completed",emptyList())
        }
        // Public non-main execution only; this is not a proactive scheduler or private runKind oracle.
        f.runtime.withMemorySessionActivity(activity)
        assertEquals("COMPLETED",f.runtime.run("Actual non-main request",emptyList(),CancellationToken.cancellable(),null).outcome)
        assertEquals(1,f.seen.size);assertTrue(activity.snapshotCounts().isEmpty())
        assertTrue(f.bridge.personalAgentMemorySessions().isEmpty());assertEquals(before,f.activityRows())
    }

}
