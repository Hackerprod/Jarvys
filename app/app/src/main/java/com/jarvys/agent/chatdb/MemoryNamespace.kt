package com.jarvys.agent.chatdb

import android.content.Context
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import java.text.Normalizer

/** Operation-local selection plus independently resolved ownership; never a persisted permission. */
class MemoryNamespace private constructor(
    val project: String,
    val scope: String,
    private val authorized: Set<String>,
    private val projectFilter: String?,
    private val scopeFilter: String?,
) {
    internal fun clause(alias: String = ""): Pair<String,List<Any?>> {
        val p=if(alias.isEmpty()) "" else "$alias."
        val args=ArrayList<Any?>()
        // Only private-constructor, Registry-derived authorized keys are literals. Caller selectors remain binds.
        val keys = authorized.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        val parts=arrayListOf("${p}project IN ($keys)")
        projectFilter?.let {parts.add("${p}project=?");args.add(it)}
        scopeFilter?.let {parts.add("${p}scope=?");args.add(it)}
        return parts.joinToString(" AND ") to args
    }
    fun hasSameOwners(other: MemoryNamespace): Boolean = authorized == other.authorized
    internal fun owns(project:String) = project in authorized
    internal fun selectWrite(requestedProject:String?, rawScope:String?):MemoryNamespace {
        require(requestedProject == null || trimSpace(requestedProject).isNotEmpty()) { "memory_project_empty" }
        val selected = requestedProject?.let(::normalizeProject) ?: CENTRAL
        require(selected in authorized) { "memory_project_unknown" }
        val scope = normalizeScope(rawScope)
        return MemoryNamespace(selected,scope,authorized,selected,scope)
    }
    internal fun requireOwner(project:String) {require(project in authorized){"memory_owner_denied"}}
    internal fun partition() = MemoryNamespace(project,scope,authorized,project,scope)
    internal fun projectOnly() = MemoryNamespace(project,scope,authorized,projectFilter,null)
    internal fun ids() = MemoryNamespace(project,scope,authorized,null,null)

    companion object {
        const val CENTRAL = "jarvys-central"
        @JvmStatic fun central() = MemoryNamespace(CENTRAL,"project",setOf(CENTRAL),CENTRAL,"project")
        @JvmStatic fun centralRead() = MemoryNamespace(CENTRAL,"project",setOf(CENTRAL),CENTRAL,null)
        @JvmStatic fun normalizeScope(value:String?):String = when(simpleLower(trimSpace(value.orEmpty()))) {
            "personal" -> "personal"; "global" -> "global"; else -> "project"
        }
        private fun whiteSpace(cp:Int):Boolean = cp in 0x9..0xd || cp == 0x20 || cp == 0x85 || cp == 0xa0 ||
            cp == 0x1680 || cp in 0x2000..0x200a || cp == 0x2028 || cp == 0x2029 || cp == 0x202f || cp == 0x205f || cp == 0x3000
        private fun trimSpace(value:String):String {
            var first=0; var last=value.length
            while(first<last && whiteSpace(value.codePointAt(first))) first+=Character.charCount(value.codePointAt(first))
            while(last>first && whiteSpace(value.codePointBefore(last))) last-=Character.charCount(value.codePointBefore(last))
            return value.substring(first,last)
        }
        private fun simpleLower(value:String):String = buildString {
            var offset=0
            while(offset<value.length) { val cp=value.codePointAt(offset); appendCodePoint(Character.toLowerCase(cp)); offset+=Character.charCount(cp) }
        }
        @JvmStatic fun normalizeProject(value:String):String = Normalizer.normalize(
            trimSpace(simpleLower(Normalizer.normalize(value,Normalizer.Form.NFC))),Normalizer.Form.NFC)
            .replace(Regex("-{2,}"),"-").replace(Regex("_{2,}"),"_")

        /** Only current Central exposes personal-memory tools; workspace existence alone is not caller authority. */
        @JvmStatic fun resolve(context:Context,callerConversation:String?,projectOverride:String?,rawScope:String?,
            allProjects:Boolean,read:Boolean,byId:Boolean=false):MemoryNamespace {
            require(callerConversation == null || callerConversation == CENTRAL){"memory_caller_denied"}
            val projects=linkedSetOf(CENTRAL)
            WorkspaceRegistry(context.applicationContext).use { registry ->
                var after:String?=null
                do {
                    val page=registry.listWorkspaces(100,after)
                    page.forEach { record ->
                        require(record.ownerCentralSessionId == CENTRAL &&
                            record.executionSessionId == WorkspaceIdentity.executionSessionId(record.workspaceId)){"memory_owner_denied"}
                        check(projects.add(normalizeProject(record.executionSessionId))){"memory_project_collision"}
                    }
                    after=page.lastOrNull()?.workspaceId
                } while(page.size == 100)
            }
            require(read || projectOverride == null || trimSpace(projectOverride).isNotEmpty()) { "memory_project_empty" }
            val explicit=projectOverride?.takeIf {trimSpace(it).isNotEmpty()}
            val selected=if(read && allProjects) CENTRAL else explicit?.let(::normalizeProject) ?: CENTRAL
            require(selected in projects){"memory_project_unknown"}
            val scope=normalizeScope(rawScope)
            val projectFilter=if(byId || read && (allProjects || rawScope == "personal" && explicit == null)) null else selected
            val scopeFilter=if(byId || read && rawScope.isNullOrEmpty()) null else scope
            return MemoryNamespace(selected,scope,java.util.Collections.unmodifiableSet(projects),projectFilter,scopeFilter)
        }
    }
}
