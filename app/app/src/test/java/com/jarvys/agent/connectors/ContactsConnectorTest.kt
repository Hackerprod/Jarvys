package com.jarvys.agent.connectors

import android.Manifest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsConnectorTest {
    private class FakeContactsGateway : ContactsGateway {
        var query = ""
        var requestedLimit = 0
        var results = emptyList<ContactRecord>()
        var detail: ContactRecord? = null
        override fun search(query: String, limit: Int): List<ContactRecord> {
            this.query = query
            requestedLimit = limit
            return results.take(limit)
        }
        override fun find(contactId: Long): ContactRecord? = detail?.takeIf { it.id == contactId }
    }

    private val token = com.jarvys.agent.CancellationToken.uncancellable()

    @Test fun searchDefaultsToTwentyAndCapsAtFiftyWithUntrustedBoundedResults() {
        val gateway = FakeContactsGateway().apply {
            results = (1..51).map { ContactRecord(it.toLong(), "Contact $it", listOf("555$it"), listOf("c$it@example.test")) }
        }
        val connector = ContactsConnector(gateway) { true }
        val result = connector.invoke(ContactsConnector.SEARCH, JSONObject().put("query", "  Kai  "), token)
        assertEquals("Kai", gateway.query)
        assertEquals(ContactsConnector.DEFAULT_LIMIT + 1, gateway.requestedLimit)
        assertEquals(20, result.getJSONArray("items").length())
        assertTrue(result.getBoolean("untrusted_content"))
        assertTrue(result.getBoolean("truncated"))
        assertEquals("android.contacts", result.getString("source"))
        val capped = connector.invoke(ContactsConnector.SEARCH,
            JSONObject().put("query", "Kai").put("limit", ContactsConnector.MAX_LIMIT), token)
        assertEquals(ContactsConnector.MAX_LIMIT, capped.getJSONArray("items").length())
        assertEquals(ContactsConnector.MAX_LIMIT + 1, gateway.requestedLimit)
    }

    @Test fun contactTextAndAggregateResponseAreTruncatedAndMarkedUntrusted() {
        val gateway = FakeContactsGateway().apply {
            results = (1..50).map { ContactRecord(
                it.toLong(), "N".repeat(300), listOf("1".repeat(100)), listOf("e".repeat(300)),
                organization = "O".repeat(250), jobTitle = "J".repeat(150),
            ) }
        }
        val result = ContactsConnector(gateway) { true }.invoke(
            ContactsConnector.SEARCH, JSONObject().put("query", "N").put("limit", 50), token,
        )
        assertTrue(result.getBoolean("untrusted_content"))
        assertTrue(result.getBoolean("truncated"))
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size <= ConnectorResultEnvelope.DEFAULT_MAX_BYTES)
        val row = result.getJSONArray("items").getJSONObject(0)
        assertEquals(ContactsConnector.MAX_NAME_CHARS, row.getString("displayName").length)
        assertEquals(ContactsConnector.MAX_PHONE_CHARS, row.getJSONArray("phones").getString(0).length)
        assertEquals(ContactsConnector.MAX_EMAIL_CHARS, row.getJSONArray("emails").getString(0).length)
    }

    @Test fun detailUsesContactIdAndReturnsUntrustedFields() {
        val gateway = FakeContactsGateway().apply { detail = ContactRecord(73, "Sam", listOf("+1555123"), listOf("sam@example.test")) }
        val result = ContactsConnector(gateway) { true }.invoke(
            ContactsConnector.GET_DETAIL, JSONObject().put("contactId", 73), token,
        )
        assertEquals("Sam", result.getJSONArray("items").getJSONObject(0).getString("displayName"))
        assertTrue(result.getBoolean("untrusted_content"))
        assertTrue(runCatching {
            ContactsConnector(gateway) { true }.invoke(ContactsConnector.GET_DETAIL, JSONObject().put("contactId", -1), token)
        }.isFailure)
    }

    @Test fun schemasAreTypedAndCreateUsesSystemInsertIntentOnlyAfterApproval() {
        val searchSchema = ContactsConnector.searchSchema()
        assertEquals("string", searchSchema.getJSONObject("properties").getJSONObject("query").getString("type"))
        assertEquals(50, searchSchema.getJSONObject("properties").getJSONObject("limit").getInt("maximum"))
        val detailSchema = ContactsConnector.detailSchema()
        assertEquals("integer", detailSchema.getJSONObject("properties").getJSONObject("contactId").getString("type"))
        val createSchema = ContactsConnector.createSchema()
        assertEquals("string", createSchema.getJSONObject("properties").getJSONObject("displayName").getString("type"))

        val summaries = mutableListOf<ApprovalSummary>()
        val definition = ContactsConnector.definition(FakeContactsGateway()) { true }
        val preferences = FakeConnectorPreferences()
        val registry = ConnectorRegistry.createForTests(preferences, { true }, rejectingApprovalGate(summaries::add))
        registry.register(definition)
        registry.connect(ContactsConnector.ID)
        val operation = definition.operations.first { it.name == ContactsConnector.CREATE }
        val args = JSONObject().put("displayName", "Kai").put("phone", "+15550001").put("email", "kai@example.test")
        val rejected = runCatching { registry.invoke(definition, operation, args, token) }.exceptionOrNull()
        assertTrue(rejected?.message.orEmpty().contains("no la reintentes"))
        assertEquals(1, summaries.size)
        assertEquals(ApprovalIntentKind.CONTACT_INSERT, summaries.single().activityIntent?.kind)
        assertEquals("Kai", summaries.single().activityIntent?.extras?.get("name"))
        assertFalse(definition.writePermissions.contains(Manifest.permission.WRITE_CONTACTS))
        assertTrue(definition.readPermissions.contains(Manifest.permission.READ_CONTACTS))
    }

    @Test fun contactPermissionRevocationIsActionable() {
        var granted = true
        val definition = ContactsConnector.definition(FakeContactsGateway()) { granted }
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { granted })
        registry.register(definition)
        registry.connect(ContactsConnector.ID)
        granted = false
        registry.refreshStates()
        assertEquals(ConnectorState.PERMISSION_REVOKED, registry.state(ContactsConnector.ID))
        val failure = runCatching {
            registry.invoke(definition, definition.operations.first(), JSONObject().put("query", "x"), token)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("El permiso de Contacts fue revocado"))
    }
}
