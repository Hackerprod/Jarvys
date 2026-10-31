package com.jarvys.agent;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Native opt-in boundary isolates a question before any other tool effects or another inference. */
public class CoreAgentLoopExclusiveYieldTest {
    @Test public void parallelCallsBeforeAndAfterQuestionNeverLaunch() { assertIsolated(CoreTool.ExecutionMode.PARALLEL); }
    @Test public void sequentialCallsBeforeAndAfterQuestionNeverLaunch() { assertIsolated(CoreTool.ExecutionMode.SEQUENTIAL); }

    private void assertIsolated(CoreTool.ExecutionMode mode) {
        AtomicInteger effects=new AtomicInteger(), questions=new AtomicInteger(), models=new AtomicInteger();
        CoreTool other=tool("write", mode, CoreTool.ResponseCallPolicy.MULTIPLE_PER_RESPONSE,
                ()->{effects.incrementAndGet();return CoreToolResult.success("wrote");});
        CoreTool boundary=tool("arbitrary_native_boundary",mode,CoreTool.ResponseCallPolicy.EXCLUSIVE_YIELD,
                ()->{questions.incrementAndGet();return CoreToolResult.finish("Question retained with exact proof");});
        CoreAgentLoop loop=loop(models,Arrays.asList(other,boundary),Arrays.asList(call("before","write"),
                call("question","arbitrary_native_boundary"),call("after","write")));
        CoreAgentLoop.Result result=loop.run("Ask",Collections.emptyList(),CancellationToken.uncancellable(),null);
        assertEquals("COMPLETED",result.outcome);assertEquals(1,models.get());assertEquals(1,questions.get());assertEquals(0,effects.get());
        assertEquals("Question retained with exact proof",result.text);
        assertEquals("RESULT",loop.checkpointSnapshot().toolLifecycle.get("question"));
        assertEquals("NEVER_LAUNCHED",loop.checkpointSnapshot().toolLifecycle.get("before"));
        assertEquals("NEVER_LAUNCHED",loop.checkpointSnapshot().toolLifecycle.get("after"));
        assertNotNull(loop.checkpointSnapshot().reconciled());
    }

    @Test public void twoExclusiveCallsFailClosedBeforeAnyLaunch() {
        AtomicInteger effects=new AtomicInteger(),models=new AtomicInteger();
        CoreTool boundary=tool("boundary",CoreTool.ExecutionMode.PARALLEL,CoreTool.ResponseCallPolicy.EXCLUSIVE_YIELD,
                ()->{effects.incrementAndGet();return CoreToolResult.finish("should not run");});
        CoreAgentLoop loop=loop(models,Collections.singletonList(boundary),Arrays.asList(call("one","boundary"),call("two","boundary")));
        CoreAgentLoop.Result result=loop.run("Ask",Collections.emptyList(),CancellationToken.uncancellable(),null);
        assertEquals("PARTIAL",result.outcome);assertEquals(1,models.get());assertEquals(0,effects.get());
        assertEquals("NEVER_LAUNCHED",loop.checkpointSnapshot().toolLifecycle.get("one"));
        assertEquals("NEVER_LAUNCHED",loop.checkpointSnapshot().toolLifecycle.get("two"));
        assertNotNull(loop.checkpointSnapshot().reconciled());
    }

    @Test public void boundaryFailureDoesNotRunOtherCallsOrAskProviderAgain() {
        assertFailedBoundary(CoreToolResult.failure("Could not confirm persistence"));
    }
    @Test public void boundaryWithoutFinishCannotSilentlyResumeInference() {
        assertFailedBoundary(CoreToolResult.success("Not a durable yield receipt"));
    }
    private void assertFailedBoundary(CoreToolResult boundaryResult) {
        AtomicInteger effects=new AtomicInteger(),models=new AtomicInteger();
        CoreTool boundary=tool("boundary",CoreTool.ExecutionMode.PARALLEL,CoreTool.ResponseCallPolicy.EXCLUSIVE_YIELD,()->boundaryResult);
        CoreTool effect=tool("write",CoreTool.ExecutionMode.PARALLEL,CoreTool.ResponseCallPolicy.MULTIPLE_PER_RESPONSE,
                ()->{effects.incrementAndGet();return CoreToolResult.success("wrote");});
        CoreAgentLoop loop=loop(models,Arrays.asList(boundary,effect),Arrays.asList(call("question","boundary"),call("effect","write")));
        CoreAgentLoop.Result result=loop.run("Ask",Collections.emptyList(),CancellationToken.uncancellable(),null);
        assertEquals("PARTIAL",result.outcome);assertEquals(1,models.get());assertEquals(0,effects.get());
        assertEquals("NEVER_LAUNCHED",loop.checkpointSnapshot().toolLifecycle.get("effect"));
    }

    @Test public void invalidExclusiveArgumentsParkEveryOtherCallWithoutExecutingQuestion() {
        AtomicInteger effects=new AtomicInteger(),questions=new AtomicInteger(),models=new AtomicInteger();
        CoreTool boundary=new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("boundary","test","boundary","test",ToolSpec.Status.IMPLEMENTED,
                        Collections.singletonMap("question","string"),Collections.singletonList("question"));
            }
            @Override public ExecutionMode executionMode() {return ExecutionMode.PARALLEL;}
            @Override public ResponseCallPolicy responseCallPolicy() {return ResponseCallPolicy.EXCLUSIVE_YIELD;}
            @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token) {
                questions.incrementAndGet();return CoreToolResult.finish("question");
            }
        };
        CoreTool effect=tool("write",CoreTool.ExecutionMode.PARALLEL,CoreTool.ResponseCallPolicy.MULTIPLE_PER_RESPONSE,
                ()->{effects.incrementAndGet();return CoreToolResult.success("write");});
        CoreAgentLoop loop=loop(models,Arrays.asList(boundary,effect),Arrays.asList(call("before","write"),
                new ModelReply.Call("question","boundary",Collections.singletonMap("unexpected","invalid")),call("after","write")));
        CoreAgentLoop.Result result=loop.run("Ask",Collections.emptyList(),CancellationToken.uncancellable(),null);
        assertEquals("PARTIAL",result.outcome);assertEquals(1,models.get());assertEquals(0,effects.get());assertEquals(0,questions.get());
        assertEquals("NEVER_LAUNCHED",loop.checkpointSnapshot().toolLifecycle.get("question"));
        assertEquals("NEVER_LAUNCHED",loop.checkpointSnapshot().toolLifecycle.get("before"));
        assertEquals("NEVER_LAUNCHED",loop.checkpointSnapshot().toolLifecycle.get("after"));
        assertNotNull(loop.checkpointSnapshot().reconciled());
    }

    @Test public void ordinaryToolNamedAskChiefKeepsItsExistingMixedBatchSemantics() {
        AtomicInteger effects=new AtomicInteger(),models=new AtomicInteger();
        CoreTool ordinary=tool("ask_chief",CoreTool.ExecutionMode.SEQUENTIAL,CoreTool.ResponseCallPolicy.MULTIPLE_PER_RESPONSE,
                ()->CoreToolResult.finish("ordinary finish"));
        CoreTool effect=tool("write",CoreTool.ExecutionMode.SEQUENTIAL,CoreTool.ResponseCallPolicy.MULTIPLE_PER_RESPONSE,
                ()->{effects.incrementAndGet();return CoreToolResult.success("write");});
        CoreAgentLoop loop=loop(models,Arrays.asList(ordinary,effect),Arrays.asList(call("question","ask_chief"),call("effect","write")));
        CoreAgentLoop.Result result=loop.run("Ask",Collections.emptyList(),CancellationToken.uncancellable(),null);
        assertEquals("COMPLETED",result.outcome);assertEquals(2,models.get());assertEquals(1,effects.get());
        assertEquals("ordinary follow-up",result.text);
    }

    @Test public void arrivingSteeringDoesNotReopenExclusiveBoundary() {
        AtomicInteger models=new AtomicInteger();AtomicBoolean inputReady=new AtomicBoolean();
        CoreTool boundary=tool("boundary",CoreTool.ExecutionMode.PARALLEL,CoreTool.ResponseCallPolicy.EXCLUSIVE_YIELD,
                ()->{inputReady.set(true);return CoreToolResult.finish("retained question");});
        CoreAgentLoop.TurnContextProvider inbox=new CoreAgentLoop.TurnContextProvider() {
            @Override public List<CoreAgentLoop.IncomingMessage> takeDurableMessages() {
                return inputReady.compareAndSet(true,false) ? Collections.singletonList(CoreAgentLoop.IncomingMessage.mainChat("input","new input")) : Collections.emptyList();
            }
        };
        CoreAgentLoop loop=new CoreAgentLoop((transcript,prompt,declarations,token)->{
            models.incrementAndGet();return new ModelReply("",Collections.singletonList(call("question","boundary")));
        },new CoreToolRegistry(Collections.singletonList(boundary)),"","yield-steering",CorePromptBudget.standard(),null,
                CoreAgentLoop.ToolResultBudget.UNBOUNDED,inbox,null);
        CoreAgentLoop.Result result=loop.run("Ask",Collections.emptyList(),CancellationToken.uncancellable(),null);
        assertEquals("COMPLETED",result.outcome);assertEquals(1,models.get());assertEquals("retained question",result.text);
    }

    private static CoreAgentLoop loop(AtomicInteger models,List<CoreTool> tools,List<ModelReply.Call> calls) {
        return new CoreAgentLoop((transcript,prompt,declarations,token)->models.incrementAndGet()==1
                ?new ModelReply("",calls):new ModelReply("ordinary follow-up",Collections.emptyList()),new CoreToolRegistry(tools),"","exclusive-test");
    }
    private static ModelReply.Call call(String id,String name) {return new ModelReply.Call(id,name,Collections.emptyMap());}
    private interface Result {CoreToolResult get();}
    private static CoreTool tool(String name,CoreTool.ExecutionMode mode,CoreTool.ResponseCallPolicy policy,Result result) {
        return new CoreTool() {
            @Override public ToolSpec declaration() {return new ToolSpec(name,"test",name,"test",ToolSpec.Status.IMPLEMENTED,Collections.emptyMap(),Collections.emptyList());}
            @Override public ExecutionMode executionMode() {return mode;}
            @Override public ResponseCallPolicy responseCallPolicy() {return policy;}
            @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token) {return result.get();}
        };
    }
}
