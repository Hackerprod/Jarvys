package com.jarvys.agent.workspaces

/** Additive coordination metadata only; no existing assignment is enrolled by schema upgrade. */
internal object WorkspaceTreeSchema {
    const val VERSION = 2
    val CREATE_SQL = listOf(
        """CREATE TABLE bot_tree_root (
            root_id TEXT PRIMARY KEY NOT NULL, storage_generation TEXT NOT NULL,
            owner_central_session_id TEXT NOT NULL, origin_user_message_id TEXT NOT NULL,
            origin_digest TEXT NOT NULL, source_receipt TEXT NOT NULL, policy_version INTEGER NOT NULL CHECK(policy_version=1),
            revision INTEGER NOT NULL CHECK(revision>0), window_epoch INTEGER NOT NULL CHECK(window_epoch>=0),
            requests INTEGER NOT NULL CHECK(requests>=0), executions INTEGER NOT NULL CHECK(executions>=0),
            wakes INTEGER NOT NULL CHECK(wakes>=0), admissions_closed INTEGER NOT NULL CHECK(admissions_closed IN (0,1)),
            wakes_closed INTEGER NOT NULL CHECK(wakes_closed IN (0,1)), close_reason TEXT,
            created_at INTEGER NOT NULL CHECK(created_at>0), updated_at INTEGER NOT NULL CHECK(updated_at>0),
            UNIQUE(storage_generation,owner_central_session_id,origin_user_message_id))""",
        """CREATE TABLE bot_tree_node (
            root_id TEXT NOT NULL, node_id TEXT NOT NULL, parent_node_id TEXT,
            depth INTEGER NOT NULL CHECK(depth>=0), mode TEXT NOT NULL CHECK(mode IN ('CENTRAL','WORK','CONSULT','NOTIFY')),
            reply TEXT NOT NULL CHECK(reply IN ('AWAIT','NONE')), workspace_id TEXT, assignment_id TEXT,
            assignment_revision INTEGER NOT NULL CHECK(assignment_revision>=0), bot_id TEXT,
            cycle_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>0),
            state TEXT NOT NULL CHECK(state IN ('QUEUED','STARTED','RUNNING','WAITING','NEEDS_INPUT','TERMINAL','UNCERTAIN')),
            source_receipt TEXT NOT NULL, execution_event_id TEXT, terminal_ref TEXT, terminal_hash TEXT,
            terminal_outcome TEXT, report_receipt TEXT, control_intent TEXT,
            admissions_closed INTEGER NOT NULL CHECK(admissions_closed IN (0,1)),
            wakes_closed INTEGER NOT NULL CHECK(wakes_closed IN (0,1)), stop_epoch INTEGER NOT NULL CHECK(stop_epoch>=0),
            created_at INTEGER NOT NULL CHECK(created_at>0), updated_at INTEGER NOT NULL CHECK(updated_at>0),
            PRIMARY KEY(root_id,node_id), UNIQUE(workspace_id,assignment_id),
            FOREIGN KEY(root_id) REFERENCES bot_tree_root(root_id),
            FOREIGN KEY(root_id,parent_node_id) REFERENCES bot_tree_node(root_id,node_id),
            FOREIGN KEY(workspace_id,assignment_id) REFERENCES assignment(workspace_id,assignment_id))""",
        "CREATE INDEX bot_tree_parent ON bot_tree_node(root_id,parent_node_id,node_id)",
        """CREATE TABLE bot_tree_event (
            root_id TEXT NOT NULL, event_key TEXT NOT NULL, node_id TEXT NOT NULL, cycle_id TEXT NOT NULL,
            node_revision INTEGER NOT NULL CHECK(node_revision>0), kind TEXT NOT NULL,
            receipt TEXT NOT NULL, source_receipt TEXT NOT NULL, payload_digest TEXT NOT NULL,
            payload_ref TEXT NOT NULL, wait_id TEXT, created_at INTEGER NOT NULL CHECK(created_at>0),
            PRIMARY KEY(root_id,event_key), FOREIGN KEY(root_id,node_id) REFERENCES bot_tree_node(root_id,node_id))""",
        "CREATE INDEX bot_tree_event_node ON bot_tree_event(root_id,node_id,kind,node_revision)",
        """CREATE TABLE bot_tree_wait (
            root_id TEXT NOT NULL, wait_id TEXT NOT NULL, parent_node_id TEXT NOT NULL,
            parent_cycle_id TEXT NOT NULL, parent_revision INTEGER NOT NULL CHECK(parent_revision>0),
            revision INTEGER NOT NULL CHECK(revision>0), children_json TEXT NOT NULL,
            join_mode TEXT NOT NULL CHECK(join_mode IN ('ALL','ANY')), implicit_wait INTEGER NOT NULL CHECK(implicit_wait IN (0,1)),
            state TEXT NOT NULL CHECK(state IN ('ARMING','WAITING','READY','STARTED','ACKNOWLEDGED','CANCELLED')),
            source_receipt TEXT NOT NULL, checkpoint_certificate TEXT, checkpoint_sha256 TEXT,
            selected_causes TEXT, successor_intent TEXT, owner_process TEXT, owner_generation TEXT, owner_token TEXT,
            incorporation_receipt TEXT, created_at INTEGER NOT NULL CHECK(created_at>0), updated_at INTEGER NOT NULL CHECK(updated_at>0),
            PRIMARY KEY(root_id,wait_id), FOREIGN KEY(root_id,parent_node_id) REFERENCES bot_tree_node(root_id,node_id))""",
        "CREATE INDEX bot_tree_wait_parent ON bot_tree_wait(root_id,parent_node_id,state)"
    )
}
