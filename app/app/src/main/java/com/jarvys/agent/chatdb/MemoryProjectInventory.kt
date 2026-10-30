package com.jarvys.agent.chatdb

/** Retained-domain inventory, not Registry choices or the destructive administration preview. */
class MemoryProjectInventoryItem(
    val name: String,
    val observationCount: Long,
    val sessionCount: Long,
    val promptCount: Long,
    directories: List<String>?,
) {
    val directories: List<String>? = directories?.let { java.util.Collections.unmodifiableList(ArrayList(it)) }
    init { require(observationCount >= 0 && sessionCount >= 0 && promptCount >= 0) }
}

class MemoryProjectInventory(projects: List<MemoryProjectInventoryItem>) {
    val projects: List<MemoryProjectInventoryItem> = java.util.Collections.unmodifiableList(ArrayList(projects))
    val count: Long get() = projects.size.toLong()
}
