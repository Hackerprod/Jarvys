package com.jarvys.agent.proactive

data class ProactiveEventBatch(
    val conversationKey: String,
    val events: List<ProactiveEvent>,
)

/** Groups only pending events newer than the processed/discarded notification postTime watermark. */
class ProactiveCandidateQueue(private val store: ProactiveEventStore) {
    fun nextBatches(): List<ProactiveEventBatch> {
        val grouped = LinkedHashMap<String, MutableList<ProactiveEvent>>()
        val watermark = store.candidatePostTimeBoundaryMillis()
        val pending = store.pending().sortedBy(ProactiveEvent::receivedAtMillis)
        pending.filter { it.receivedAtMillis < watermark }.forEach { event ->
            store.markDiscarded(event.id, "older_than_watermark")
        }
        pending.filter { it.receivedAtMillis >= watermark }.forEach { event ->
            grouped.getOrPut(conversationKey(event)) { mutableListOf() }.add(event)
        }
        return grouped.map { (key, events) ->
            ProactiveEventBatch(key, events.sortedBy(ProactiveEvent::receivedAtMillis))
        }.sortedBy { batch -> batch.events.firstOrNull()?.receivedAtMillis ?: Long.MIN_VALUE }
    }

    fun markProcessed(ids: Collection<String>) {
        ids.forEach(store::markProcessed)
    }

    private fun conversationKey(event: ProactiveEvent): String {
        val app = event.appPackage?.takeIf(String::isNotBlank) ?: event.sourceId
        val participantOrThread = event.sender?.takeIf(String::isNotBlank)
            ?: event.conversationThread?.takeIf(String::isNotBlank)
            ?: ""
        return "$app\u001f$participantOrThread"
    }
}
