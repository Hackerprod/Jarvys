package com.jarvys.agent.flavor

/** Identifiers used to validate stored profiles. Play supplies no execution tools or runtime. */
class CodingExecutionTools private constructor() {
    companion object {
        const val EXEC = "project_exec"
        const val JOBS = "project_jobs"
        const val STATUS = "project_environment_status"
        @JvmField val NAMES = listOf(EXEC, JOBS, STATUS)
    }
}
