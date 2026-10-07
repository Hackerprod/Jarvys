package com.jarvys.agent.skills

/** Orders the write transaction so a rejected entrypoint never reaches disk or the skill index. */
internal object SkillWorkspaceMutation {
    fun commit(
        skillMarkdown: String?,
        validateMarkdown: () -> Unit,
        writeFile: () -> Unit,
        initializeEnabledState: () -> Unit,
        rescan: () -> Unit,
    ) {
        if (skillMarkdown != null) validateMarkdown()
        writeFile()
        if (skillMarkdown != null) initializeEnabledState()
        rescan()
    }
}
