package com.jarvys.agent.chatdb

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One read-fenced projection of the existing memory store; never captures prompts or closes sessions. */
class CanonicalMemoryContext internal constructor(
    private val database: JarvysChatDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val sql get() = database.chatSql
    private data class Observation(val id: String, val type: String, val title: String, val content: String)
    private data class Session(val project: String, val started: Long, val count: Long, val summary: String?)
    private data class Prompt(val created: Long, val content: String)
    private data class Period(val label: String, val start: LocalDate, val end: LocalDate, val samples: Int = 0, val monthly: Boolean = false)
    private data class Group(val label: String, var count: Long = 0, val types: MutableList<String> = mutableListOf(), val samples: MutableList<String> = mutableListOf())

    suspend fun render(namespace: MemoryNamespace, compact: Boolean = false, olderHistory: Boolean = false,
        maxBytes: Int = 16384, reminder: String = ""): String = database.withChatReadTransaction {
        val budget = if (maxBytes <= 0) 16384 else minOf(maxBytes, 65536)
        val (owners, ownerArgs) = namespace.ids().clause()
        fun count(table: String, live: Boolean): Long = sql.query(
            "SELECT COUNT(*) FROM $table WHERE $owners" + if(live) " AND deleted_at IS NULL" else "", ownerArgs.toTypedArray()
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        val sessionCount = count("memory_sessions", false)
        val observationCount = count("memory_entries", true)
        val projects = sql.query("SELECT project FROM memory_entries WHERE deleted_at IS NULL AND $owners GROUP BY project ORDER BY MAX(created_at) DESC",
            ownerArgs.toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        val names = if(projects.isEmpty()) "none" else projects.take(8).joinToString(", ") +
            if(projects.size > 8) ", +${projects.size - 8} more" else ""
        val suffix = "\n---\nMemory stats: $sessionCount sessions, $observationCount observations across projects: $names" + reminder
        val allowance = maxOf(1, budget - utf8Size(suffix))
        val body = formatInside(namespace, compact, olderHistory, allowance, 5, 10, 20, 20)
        limitBytes(if(body.isEmpty()) "No previous session memories found." else body + suffix, budget)
    }

    /** Exact admitted parent projection. Terminal sessions are readable; this is not write admission.
     * No internal byte/pin cap: the complete prepared request owns its final budget boundary. */
    suspend fun formatCompaction(namespace: MemoryNamespace, binding: MemorySessionBinding): String? = database.withChatReadTransaction {
        check(MemorySaveSourceIdentity.existingGeneration(database) == binding.sourceGeneration) { "context_memory_source_invalid" }
        val session = sql.query("SELECT project,started_at,summary,source_generation,owner_conversation_id,owner_message_id,run_id,process_id,ownership_mode FROM memory_sessions WHERE id=?",
            arrayOf(binding.id)).use { c ->
            if (!c.moveToFirst()) null else {
                check(c.getString(0) == binding.project && c.getString(3) == binding.sourceGeneration &&
                    c.getString(4) == binding.ownerConversation && c.getString(5) == binding.ownerMessageId &&
                    c.getString(6) == binding.runId && c.getString(7) == binding.processId && c.getString(8) == "shared") {
                    "context_memory_source_invalid"
                }
                Triple(c.getString(0), c.getLong(1), if(c.isNull(2)) null else c.getString(2))
            }
        } ?: return@withChatReadTransaction null
        namespace.requireOwner(session.first)
        val (owners, ownerArgs) = namespace.ids().clause()
        val where = "memory_session_id=? AND LOWER(project)=? AND $owners"
        val args = listOf<Any?>(binding.id, MemoryNamespace.normalizeProject(session.first)) + ownerArgs
        val count = sql.query("SELECT COUNT(*) FROM memory_entries WHERE deleted_at IS NULL AND $where",args.toTypedArray())
            .use { c -> check(c.moveToFirst()); c.getLong(0) }
        val pins = observations(where,args,true,0)
        val recent = observations(where,args,false,20)
        val prompts = sql.query("SELECT created_at,content FROM memory_prompts WHERE session_id=? AND LOWER(COALESCE(project,''))=? AND $owners ORDER BY created_at DESC LIMIT 10",
            args.toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(Prompt(c.getLong(0),c.getString(1))) } }
        buildString {
            append("## Memory from This Session\n\n### Session\n")
            val summary = session.third?.let { ": ${preview(it,200)}" }.orEmpty()
            append("- **${binding.id}** (${localTime(session.second)})$summary [$count observations]\n\n")
            if(prompts.isNotEmpty()) {
                append("### Recent User Prompts\n")
                for(p in prompts) append("- ${localTime(p.created)}: ${preview(p.content,200)}\n")
                append('\n')
            }
            fun section(title: String, rows: List<Observation>) {
                if(rows.isEmpty()) return
                append("### $title\n")
                for(o in rows) append("- [${o.type}] **${o.title}**: ${preview(o.content,300)}\n")
                append('\n')
            }
            section("Pinned",pins); section("Recent Observations",recent)
        }
    }

    /** Store-level renderer budget (zero means unbounded), separate from the complete caller envelope. */
    suspend fun format(namespace: MemoryNamespace, compact: Boolean = false, olderHistory: Boolean = false,
        maxBytes: Int = 0, sessions: Int = 0, prompts: Int = 0, pinned: Int = 0, observations: Int = 0): String = database.withChatReadTransaction {
        formatInside(namespace, compact, olderHistory, maxBytes, sessions, prompts, pinned, observations)
    }

    private fun formatInside(namespace: MemoryNamespace, compact: Boolean, olderHistory: Boolean, maxBytes: Int,
        sessionLimit: Int, promptLimit: Int, pinLimit: Int, observationLimit: Int): String {
        val (sessionWhere, sessionArgs) = namespace.projectOnly().clause("s")
        val (childWhere, childArgs) = namespace.ids().clause("o")
        val sessions = if(sessionLimit < 0) emptyList() else sql.query("SELECT s.project,s.started_at,COUNT(o.id),s.summary FROM memory_sessions s LEFT JOIN memory_entries o ON o.memory_session_id=s.id AND o.deleted_at IS NULL AND $childWhere WHERE $sessionWhere GROUP BY s.id ORDER BY MAX(${CanonicalMemorySearch.date("COALESCE(o.created_at,s.started_at)")}) DESC,s.id DESC LIMIT ?",
            (childArgs + sessionArgs + if(sessionLimit == 0) 5 else sessionLimit).toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(Session(c.getString(0),c.getLong(1),c.getLong(2),if(c.isNull(3)) null else c.getString(3))) } }
        val (projectWhere, projectArgs) = namespace.projectOnly().clause()
        val prompts = if(promptLimit < 0) emptyList() else sql.query("SELECT created_at,content FROM memory_prompts WHERE $projectWhere ORDER BY ${CanonicalMemorySearch.date("created_at")} DESC LIMIT ?",
            (projectArgs + if(promptLimit == 0) 10 else promptLimit).toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(Prompt(c.getLong(0),c.getString(1))) } }
        val (where, args) = namespace.clause()
        val pinned = if(pinLimit < 0) emptyList() else observations(where,args,true,pinLimit)
        val recent = if(observationLimit < 0) emptyList() else observations(where,args,false,if(observationLimit == 0) 20 else observationLimit)
        val groups = if(olderHistory) history(where,args,Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate(),if(observationLimit <= 0) 20 else observationLimit) else emptyList()
        if(sessions.isEmpty() && prompts.isEmpty() && pinned.isEmpty() && recent.isEmpty() && groups.isEmpty()) return ""
        val body = buildString {
            append("## Memory from Previous Sessions\n\n")
            if(sessions.isNotEmpty()) {
                append("### Recent Sessions\n")
                for(s in sessions) {
                    val summary = s.summary?.let { ": ${preview(it,200)}" }.orEmpty()
                    append("- **${s.project}** (${localTime(s.started)})$summary [${s.count} observations]\n")
                }
                append('\n')
            }
            if(prompts.isNotEmpty()) {
                append("### Recent User Prompts\n")
                for(p in prompts) append("- ${localTime(p.created)}: ${preview(p.content,200)}\n")
                append('\n')
            }
            fun section(title: String, observations: List<Observation>) {
                if(observations.isEmpty()) return
                append("### $title\n")
                for(o in observations) {
                    append("- [${o.type}] **${o.title}**")
                    if(!compact) append(": ${preview(o.content,300)}")
                    append('\n')
                }
                append('\n')
            }
            section("Pinned",pinned); section("Recent Observations",recent)
        }
        if(olderHistory) {
            val older = renderHistory(groups,if(maxBytes > 0) minOf(2048,maxBytes/4) else 2048)
            if(older.isNotEmpty()) return limitBytes(body,if(maxBytes > 0) maxBytes-utf8Size(older) else 0)+older
        }
        return limitBytes(body,maxBytes)
    }

    private fun observations(where: String, args: List<Any?>, pinned: Boolean, limit: Int): List<Observation> = sql.query(
        "SELECT id,observation_type,title,text FROM memory_entries WHERE deleted_at IS NULL AND pinned=? AND $where ORDER BY ${CanonicalMemorySearch.date("created_at")} DESC,rowid DESC" + if(limit > 0) " LIMIT ?" else "",
        (listOf(if(pinned) 1 else 0)+args+(if(limit > 0) listOf(limit) else emptyList())).toTypedArray()
    ).use { c -> buildList { while(c.moveToNext()) add(Observation(c.getString(0),c.getString(1),if(c.isNull(2)) "" else c.getString(2),c.getString(3))) } }

    /** Aggregate old rows and fetch only bounded title samples; no old body preload. */
    private fun history(where: String, args: List<Any?>, today: LocalDate, recentLimit: Int): List<Group> {
        val filter = "deleted_at IS NULL AND pinned=0 AND $where"
        val date = CanonicalMemorySearch.date("created_at")
        val base = "WITH recent AS (SELECT id FROM memory_entries WHERE $filter ORDER BY $date DESC,rowid DESC LIMIT ?), old AS (SELECT rowid AS ordinal,id,observation_type,title,created_at FROM memory_entries WHERE $filter AND id NOT IN (SELECT id FROM recent)) "
        val baseArgs = args+recentLimit+args
        val periods = periods(today)
        val queryArgs = ArrayList<Any?>(baseArgs)
        var expression = "CASE WHEN $date < datetime(?) THEN strftime('%Y',${CanonicalMemorySearch.seconds("created_at")},'unixepoch')"
        queryArgs.add(today.withDayOfYear(1).toString())
        for(p in periods) {
            expression += " WHEN $date >= datetime(?) AND $date < datetime(?) THEN ?"
            queryArgs.add(p.start.toString());queryArgs.add(p.end.toString());queryArgs.add(p.label)
        }
        expression += " END"
        val groups = linkedMapOf<String,Group>()
        sql.query(base+"SELECT $expression AS period,observation_type,COUNT(*) FROM old GROUP BY period,observation_type HAVING period IS NOT NULL ORDER BY period DESC,observation_type",queryArgs.toTypedArray()).use { c ->
            while(c.moveToNext()) {
                val label=c.getString(0);val count=c.getLong(2);val g=groups.getOrPut(label){Group(label)}
                g.count+=count;g.types.add("${historyLine(c.getString(1))}: $count")
            }
        }
        val result=ArrayList<Group>()
        for(p in periods) {
            val g=groups.remove(p.label) ?: continue
            if(!p.monthly) g.types.clear()
            if(p.samples > 0) sql.query(base+"SELECT id,title FROM old WHERE $date>=datetime(?) AND $date<datetime(?) ORDER BY $date DESC,ordinal DESC LIMIT ?",
                (baseArgs+listOf(p.start.toString(),p.end.toString(),p.samples)).toTypedArray()).use { c ->
                while(c.moveToNext()) g.samples.add("  - Sample #${c.getString(0)}: ${historyLine(if(c.isNull(1)) "" else c.getString(1))}\n")
            }
            result.add(g)
        }
        for(label in groups.keys.sortedDescending()) result.add(requireNotNull(groups[label]).also{it.types.clear()})
        return result
    }

    private fun periods(today: LocalDate): List<Period> {
        val month=today.withDayOfMonth(1);val next=month.plusMonths(1);val periods=ArrayList<Period>()
        var start=month
        while(start<next) {
            val days=8-start.dayOfWeek.value
            val end=minOf(start.plusDays(days.toLong()),next)
            periods.add(Period("$start–${end.minusDays(1)}",start,end,if(today>=start && today<end) 3 else 1))
            start=end
        }
        val quarter=LocalDate.of(today.year,((today.monthValue-1)/3)*3+1,1)
        start=quarter
        while(start<month) { periods.add(Period(start.toString().take(7),start,start.plusMonths(1),monthly=true));start=start.plusMonths(1) }
        start=today.withDayOfYear(1)
        while(start<quarter) {periods.add(Period("${today.year} Q${(start.monthValue-1)/3+1}",start,start.plusMonths(3)));start=start.plusMonths(3)}
        return periods.sortedByDescending { it.start }
    }

    private fun renderHistory(groups: List<Group>, budget: Int): String {
        val header="### Older History\nSamples are titles, not summaries; use memory_get(ids) with an ID to drill in.\n"
        val omitted="- [older groups omitted]\n"
        if(groups.isEmpty() || budget<=utf8Size(header)) return ""
        val lines=buildList {
            for(g in groups) {
                add("- ${g.label}: ${g.count} observations"+(if(g.types.isNotEmpty()) " (${g.types.joinToString(", ")})" else "")+"\n")
                addAll(g.samples)
            }
        }
        val complete=header+lines.joinToString("")+"\n"
        if(utf8Size(complete)<=budget) return complete
        val out=StringBuilder(header);var useful=false
        for((i,line) in lines.withIndex()) {
            val reserve=1+if(i<lines.size-1)utf8Size(omitted)else 0
            if(utf8Size(out.toString())+utf8Size(line)+reserve>budget) {
                if(useful && utf8Size(out.toString())+utf8Size(omitted)+1<=budget)out.append(omitted)
                break
            }
            out.append(line);useful=true
        }
        return if(useful)out.append('\n').toString()else ""
    }

    private fun localTime(epochMillis: Long): String = DISPLAY.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
    companion object {
        private val DISPLAY=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss",Locale.ROOT)
        private const val MARKER="\n[truncated]\n"
        @JvmStatic fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size
        @JvmStatic fun preview(value: String, codePoints: Int): String =
            if(value.codePointCount(0,value.length)<=codePoints)value else value.substring(0,value.offsetByCodePoints(0,codePoints))+"..."
        @JvmStatic fun limitBytes(value: String, maxBytes: Int): String {
            if(maxBytes<=0 || utf8Size(value)<=maxBytes)return value
            return if(maxBytes<utf8Size(MARKER))prefix(value,maxBytes)else prefix(value,maxBytes-utf8Size(MARKER))+MARKER
        }
        private fun prefix(value: String, budget: Int): String {
            var offset=0;var bytes=0
            while(offset<value.length) {
                val cp=value.codePointAt(offset);val width=when {cp<=0x7f->1;cp<=0x7ff->2;cp<=0xffff->3;else->4}
                if(bytes+width>budget)break
                bytes+=width;offset+=Character.charCount(cp)
            }
            return value.substring(0,offset)
        }
        private fun historyLine(value: String)=MemoryObservationContent.fields(value).joinToString(" ")
    }
}
