package com.jarvys.agent.chatdb

import androidx.room.migration.Migration

/**
 * Room schema migrations are additive and registered here. Future columns must be appended to the
 * end of the entity/table declaration and added with an explicit version-to-version migration.
 * Every migration that recreates any triggered table must call
 * [JarvysChatDatabaseProvider.DatabaseTriggers.create]; Room also reapplies the idempotent triggers
 * on every connection open because it does not export or validate them.
 */
internal object Migrations {
    val ALL: Array<Migration> = emptyArray()
}

