package com.jarvys.agent.chatdb

import android.content.Context

/** Test-only direct lifecycle entry: no extra helper thread can obscure the lock-queue oracle. */
object CentralSourceLifecycleTestSupport {
    @JvmStatic fun currentDatabaseCloser(context: Context): Runnable {
        val database = JarvysChatDatabaseProvider.getInstance(context)
        return Runnable { JarvysChatDatabaseProvider.closeDatabase(database) }
    }
}
