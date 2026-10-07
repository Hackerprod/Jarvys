package com.jarvys.agent.connectors

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsConnectorTest {
    private open class FakeNotificationGateway : NotificationGateway {
        var receivedApp: String? = null
        var receivedLimit = 0
        var items = emptyList<NotificationSnapshot>()
        var dismissCount = 0
        var openCount = 0
        var replyCount = 0
        override fun recent(appFilter: String?, limit: Int): List<NotificationSnapshot> {
            receivedApp = appFilter
            receivedLimit = limit
            return items.filter { appFilter == null || it.appName.contains(appFilter, true) || it.packageName.contains(appFilter, true) }.take(limit)
        }
        override fun find(key: String) = items.firstOrNull { it.key == key }
        override fun canDismiss(key: String) = find(key) != null
        override fun canOpen(key: String) = find(key)?.hasContentIntent == true
        override fun canReply(key: String) = find(key)?.hasReplyAction == true
        override fun dismiss(key: String) { dismissCount++ }
        override fun open(key: String) { openCount++ }
        override fun reply(key: String, text: String) { replyCount++ }
    }

    private val token = com.jarvys.agent.CancellationToken.uncancellable()

    @Test fun bufferPrunesByTtlAndCapacityAndFiltersByApp() {
        var now = 1_000L
        val buffer = NotificationBuffer(maxEntries = 3, ttlMillis = 100, clock = { now })
        (1..4).forEach { index -> buffer.put(item(index, now, app = if (index == 4) "Chat" else "Mail")) }
        assertEquals(3, buffer.size())
        assertEquals("4", buffer.recent("chat", 10).single().key)
        now = 1_101
        assertEquals(0, buffer.size())
    }

    @Test fun listUsesTwentyByDefaultFiftyMaximumAppFilterAndUntrustedBoundedEnvelope() {
        val gateway = FakeNotificationGateway().apply {
            items = (1..51).map { index -> item(index, index.toLong(), title = "Title $index", text = "Body") }
        }
        val connector = NotificationsConnector(gateway) { true }
        val result = connector.invoke(NotificationsConnector.LIST_RECENT, JSONObject().put("appFilter", "Chat"), token)
        assertEquals("Chat", gateway.receivedApp)
        assertEquals(NotificationsConnector.DEFAULT_LIMIT + 1, gateway.receivedLimit)
        assertEquals(20, result.getJSONArray("items").length())
        assertTrue(result.getBoolean("untrusted_content"))
        assertTrue(result.getBoolean("truncated"))
        val max = connector.invoke(NotificationsConnector.LIST_RECENT,
            JSONObject().put("limit", NotificationsConnector.MAX_LIMIT), token)
        assertEquals(NotificationsConnector.MAX_LIMIT, max.getJSONArray("items").length())
        assertTrue(max.toString().toByteArray(Charsets.UTF_8).size <= ConnectorResultEnvelope.DEFAULT_MAX_BYTES)
    }

    @Test fun schemasAreTypedAndBounded() {
        val list = NotificationsConnector.listSchema()
        assertEquals("integer", list.getJSONObject("properties").getJSONObject("limit").getString("type"))
        assertEquals(50, list.getJSONObject("properties").getJSONObject("limit").getInt("maximum"))
        val reply = NotificationsConnector.replySchema()
        assertEquals(listOf("key", "replyText"), (0 until reply.getJSONArray("required").length()).map { reply.getJSONArray("required").getString(it) })
        assertEquals(NotificationsConnector.MAX_REPLY_CHARS,
            reply.getJSONObject("properties").getJSONObject("replyText").getInt("maxLength"))
    }

    @Test fun recentNotificationReadCarriesTheSourcePostedAtMillisForProactiveTurns() {
        val originalTime = 1_712_345_678_901L
        val gateway = FakeNotificationGateway().apply {
            items = listOf(item(88, originalTime, app = "Bank", title = "Transfer", text = "Received"))
        }
        val result = NotificationsConnector(gateway) { true }.invoke(
            NotificationsConnector.LIST_RECENT, JSONObject(), token,
        )
        assertEquals(originalTime, result.getJSONArray("items").getJSONObject(0).getLong("postedAtMillis"))
    }

    @Test fun longNotificationFieldsAndLargeResultAreTruncated() {
        val buffer = NotificationBuffer(maxEntries = 50, ttlMillis = 60_000) { 10_000 }
        repeat(50) { index ->
            buffer.put(item(index, 9_999, title = "T".repeat(260), text = "B".repeat(700)))
        }
        val gateway = object : FakeNotificationGateway() {
            override fun recent(appFilter: String?, limit: Int): List<NotificationSnapshot> = buffer.recent(appFilter, limit)
        }
        val result = NotificationsConnector(gateway) { true }.invoke(
            NotificationsConnector.LIST_RECENT, JSONObject().put("limit", 50), token,
        )
        assertTrue(result.getBoolean("untrusted_content"))
        assertTrue(result.getBoolean("truncated"))
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size <= ConnectorResultEnvelope.DEFAULT_MAX_BYTES)
        if (result.getJSONArray("items").length() > 0) {
            assertEquals(NotificationBuffer.MAX_TITLE_CHARS,
                result.getJSONArray("items").getJSONObject(0).getString("title").length)
            assertEquals(NotificationBuffer.MAX_TEXT_CHARS,
                result.getJSONArray("items").getJSONObject(0).getString("text").length)
        }
    }

    @Test fun replyDismissAndOpenMustPassApprovalBeforeGatewayActionsRun() {
        val gateway = FakeNotificationGateway().apply { items = listOf(item(1, 100, hasReply = true)) }
        val definition = NotificationsConnector.definition(gateway) { true }
        val summaries = mutableListOf<ApprovalSummary>()
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, rejectingApprovalGate(summaries::add))
        registry.register(definition)
        registry.connectFromSystemSettings(NotificationsConnector.ID)
        val actionArguments = mapOf(
            NotificationsConnector.DISMISS to JSONObject().put("key", "1"),
            NotificationsConnector.OPEN to JSONObject().put("key", "1"),
            NotificationsConnector.REPLY to JSONObject().put("key", "1").put("replyText", "Thanks"),
        )
        actionArguments.forEach { (operationName, arguments) ->
            val operation = definition.operations.first { it.name == operationName }
            val failure = runCatching { registry.invoke(definition, operation, arguments, token) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("no la reintentes"))
        }
        assertEquals(3, summaries.size)
        assertEquals(0, gateway.replyCount)
        assertEquals(0, gateway.dismissCount)
        assertEquals(0, gateway.openCount)
        assertTrue(summaries.any { it.title == "Reply to Notification" })
        assertTrue(summaries.all { it.permission == null })
    }

    @Test fun connectionStateFollowsNotificationListenerAccessAndRevocation() {
        var enabled = false
        val definition = NotificationsConnector.definition(FakeNotificationGateway()) { enabled }
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false })
        registry.register(definition)
        registry.connectFromSystemSettings(NotificationsConnector.ID)
        assertEquals(ConnectorState.PERMISSION_REVOKED, registry.state(NotificationsConnector.ID))
        assertTrue(registry.connectedDefinitions().isEmpty())
        enabled = true
        registry.refreshStates()
        assertEquals(ConnectorState.CONNECTED, registry.state(NotificationsConnector.ID))
        assertEquals(1, registry.connectedDefinitions().size)
        enabled = false
        registry.refreshStates()
        assertEquals(ConnectorState.PERMISSION_REVOKED, registry.state(NotificationsConnector.ID))
    }

    private fun item(
        id: Int,
        posted: Long,
        app: String = "Chat",
        title: String = "A title",
        text: String = "Notification body",
        hasReply: Boolean = false,
    ) = NotificationSnapshot("$id", "example.$app", app, title, text, posted, true, hasReply)
}
