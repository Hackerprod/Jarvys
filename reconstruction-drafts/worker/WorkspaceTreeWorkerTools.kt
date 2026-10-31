package com.jarvys.agent

import com.jarvys.agent.crew.*

/** Only the native enrolled worker route. No captain facade or legacy delegation fallback. */
internal object WorkspaceTreeWorkerTools {
    @JvmStatic fun create(manager:CrewManager,bot:CrewManager.Bot,scope:CoreToolRegistry,skills:List<String>):List<CoreTool> {
        val owner=manager.treeSupport(bot) ?: return emptyList()
        val tools=scope.handlers().filter { it.canDelegate()&&!CrewManager.isCaptainOnly(it.declaration().name) }
            .map { it.declaration().name }.filter { it !in setOf("delegate_subtask","wait_children") }.toMutableList()
        val mayDelegate=bot.role.tools.contains("delegate_subtask")&&bot.role.missionAccess!=CrewMissionAccess.READ_ONLY
        if(mayDelegate)tools.add("delegate_subtask") // capability may descend; handler is never generic-delegable.
        return (if(mayDelegate) listOf("delegate_subtask","wait_children") else listOf("wait_children")).map { name ->
            val properties=if(name=="delegate_subtask") linkedMapOf("objective" to "string","role" to "string","name" to "string",
                "tools" to "array","skills" to "array","mode" to "string","reply" to "string") else linkedMapOf("ids" to "array","mode" to "string")
            val spec=ToolSpec(name,"native/workspace-tree",if(name=="delegate_subtask")
                "Admit a durable child of this worker, not a new root. Modes work, consult (read-only), notify. Reply result or none. Only current tools $tools and skills $skills. No Chief authority. Receipt is not child completion."
                else "Yield at a confirmed native checkpoint for exact direct child IDs, ANY or ALL. ANY retains siblings. A final return implicitly awaits remaining awaited/unseen children. Never replay uncertain work.",
                "crew",ToolSpec.Status.IMPLEMENTED,properties,if(name=="delegate_subtask")listOf("objective")else listOf("ids","mode"))
            object:CoreTool {
                override fun declaration()=spec
                override fun canDelegate()=false
                override fun requiresInteractiveWorkspace()=true
                override fun accessClassification()=CoreTool.AccessClassification.WRITE
                override fun executionMode()=CoreTool.ExecutionMode.SEQUENTIAL
                override fun responseCallPolicy()=if(name=="wait_children")CoreTool.ResponseCallPolicy.EXCLUSIVE_YIELD else CoreTool.ResponseCallPolicy.MULTIPLE_PER_RESPONSE
                override fun execute(args:Map<String,Any?>,token:CancellationToken)=CoreToolResult.failure("Exact native prepared invocation required")
                override fun execute(args:Map<String,Any?>,token:CancellationToken,progress:CoreTool.ProgressListener?,invocation:EffectInvocation?):CoreToolResult {
                    token.throwIfCancelled();check(manager.treeSupport(bot)===owner&&bot.token===token)
                    val call=checkNotNull(invocation);val witness=checkNotNull(call.crewSource())
                    witness.requireArguments(args);witness.requireInvocationCurrent()
                    val source=witness.source()
                    check(source.botId==bot.id&&source.cycleId==bot.cycleId()&&source.scopeIdentity==bot.scopeIdentity()&&source.toolName==name)
                    owner.requireCurrentDispatch(bot,token)
                    val result=if(name=="delegate_subtask") {
                        check(mayDelegate&&bot.role.missionAccess!=CrewMissionAccess.READ_ONLY)
                        require((args["objective"] as? String)?.isNotBlank()==true)
                        require(tools.containsAll(strings(args["tools"]))&&skills.containsAll(strings(args["skills"])))
                        require((args["mode"]?:"work") in setOf("work","consult","notify"))
                        require((args["reply"]?:if(args["mode"]=="notify")"none"else"result") in setOf("none","result"))
                        call.admit(EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,"native",name,null,"native_tree_contract"))
                        owner.prepareInvocation(bot,token,witness,args,"ADMISSION")
                        owner.delegateChild(bot,token,witness,args,tools.toList(),skills.toList())
                    } else {
                        val ids=strings(args["ids"]);require(ids.isNotEmpty()&&ids.distinct().size==ids.size)
                        val mode=when((args["mode"] as? String)?.uppercase()){ "ANY"->CrewManager.WaitMode.ANY;"ALL"->CrewManager.WaitMode.ALL;else->error("Exact join required") }
                        call.admit(EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,"native",name,null,"native_tree_contract"))
                        owner.prepareInvocation(bot,token,witness,args,"WAIT_ARM")
                        manager.requestTreeWait(bot,token,witness,ids,mode)
                        CoreToolResult.finish("native-tree-wait:${checkNotNull(bot.treeWaitTicket()).waitId}\nSuspended for exact children, not task completion.")
                    }
                    check(manager.treeSupport(bot)===owner);owner.recordInvocationResult(bot,token,witness,result)
                    return result
                }
            }
        }
    }
    private fun strings(value:Any?):List<String>{if(value==null)return emptyList();require(value is List<*>&&value.all { it is String&&it.isNotBlank() });return value.map { it as String }}
}
