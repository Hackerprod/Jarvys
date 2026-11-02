package com.jarvys.agent.workspaces

/** Current-generation additive claim metadata; no historical node is enrolled by upgrade. */
internal object WorkspaceCentralResumeSchema {
    const val VERSION = 3
    val CREATE_SQL = listOf(
        """CREATE TABLE bot_tree_central_resume (
            resume_id TEXT PRIMARY KEY NOT NULL, root_id TEXT NOT NULL, node_id TEXT NOT NULL,
            cause_kind TEXT NOT NULL CHECK(cause_kind IN ('SUSPENSION','CHILD_RESULT')), cause_id TEXT NOT NULL,
            source_certificate TEXT NOT NULL, source_digest TEXT NOT NULL,
            revision INTEGER NOT NULL CHECK(revision>0),
            state TEXT NOT NULL CHECK(state IN ('PREPARED','RESERVED','BOUND','INCORPORATED','CANCELLED','UNCERTAIN')),
            parent_cycle_id TEXT NOT NULL, parent_revision INTEGER NOT NULL CHECK(parent_revision>0),
            root_stop_epoch INTEGER NOT NULL CHECK(root_stop_epoch>=0),
            owner_process TEXT, owner_run TEXT, owner_token TEXT, successor_cycle TEXT,
            binding_receipt TEXT, incorporation_receipt TEXT,
            created_at INTEGER NOT NULL CHECK(created_at>0), updated_at INTEGER NOT NULL CHECK(updated_at>0),
            UNIQUE(root_id,cause_kind,cause_id),
            FOREIGN KEY(root_id,node_id) REFERENCES bot_tree_node(root_id,node_id))""",
        "CREATE INDEX bot_tree_central_resume_pending ON bot_tree_central_resume(root_id,node_id,state,cause_kind,created_at)"
    )
}
