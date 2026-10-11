package com.jarvys.agent.apkfactory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FactorySpecTest {
    private fun spec() = JSONObject().put("schemaVersion",1).put("appId","org.example.notes").put("name","Notas útiles")
        .put("versionCode",1).put("versionName","1.0").put("capabilities",JSONArray(listOf("storage","export")))
        .put("webDir","web").put("icon","icon.json")
    private fun rejects(change: (JSONObject)->Unit) {
        val json=spec();change(json)
        try { FactorySpec.parse(json); fail("Invalid input accepted: $json") } catch (_: IllegalArgumentException) { }
    }
    @Test fun validSpecHasStableCapabilitiesAndExactRuntimeContract() {
        val value=FactorySpec.parse(spec())
        assertEquals(listOf("export","storage"),value.capabilities)
        val config=JSONObject(String(value.runtimeConfig()))
        assertEquals("www/index.html",config.getString("entryPoint"))
        assertEquals(setOf("schemaVersion","appId","name","entryPoint","capabilities"),config.keys().asSequence().toSet())
    }
    @Test fun reservedAndInvalidPackageIdsAreRejected() {
        for(id in listOf("com.jarvys.agent","com.jarvys.factory.template","android.hidden","single","org.Example.x","org.a/evil","org..x","org."+"x".repeat(125))) rejects{it.put("appId",id)}
    }
    @Test fun unknownAndDuplicateCapabilitiesFailInsteadOfAddingPermissions() {
        rejects{it.put("capabilities",JSONArray(listOf("camera")))}
        rejects{it.put("capabilities",JSONArray(listOf("storage","storage")))}
        rejects{it.put("capabilities","storage")}
        rejects{it.put("permissions",JSONArray(listOf("android.permission.INTERNET")))}
    }
    @Test fun onlyFinitePositiveIntegerVersionAccepted() {
        for(n in listOf<Any>(0,-1,1.0,2147483648L,"1")) rejects{it.put("versionCode",n)}
    }
    @Test fun schemaAndUnknownFieldsAreStrict() {
        rejects{it.put("schemaVersion",2)};rejects{it.put("command","execute")};rejects{it.remove("icon")}
    }
    @Test fun namesAndPathsBounded() {
        rejects{it.put("name","x\nname")};rejects{it.put("name","x".repeat(81))};rejects{it.put("versionName","")}
        for(path in listOf("/tmp/web","../web","x/../web",".git","x//y","x\\y","x/./y")) rejects{it.put("webDir",path)}
        rejects{it.put("icon","icon.svg")}
    }
    @Test fun nativeCapabilitiesCannotBeInventedByManifestFlags() {
        rejects{it.put("nativeCode","classes.dex")};rejects{it.put("providerAuthority","shared")}
    }
    @Test fun projectJsonRejectsDeepNestingAndMalformedUtf8BeforeRecursiveParsing() {
        val nested=("{\"x\":"+"[".repeat(2000)+"0"+"]".repeat(2000)+"}").toByteArray()
        assertThrows(Exception::class.java){FactoryJson.objectFrom(nested,16*1024)}
        assertThrows(Exception::class.java){FactoryJson.objectFrom(byteArrayOf(123,34,120,34,58,34,0xc3.toByte(),34,125),1024)}
        val valid=FactoryJson.objectFrom(spec().put("name","Brackets [ ] { } \"quoted\"").toString().toByteArray(),16*1024)
        assertTrue(valid.getString("name").contains("[ ]"))
    }
    @Test fun strictJsonEnforcesAdjacentDepthBoundaryAndAsciiEscapes() {
        fun nested(levels:Int)=("{\"x\":"+"[".repeat(levels)+"0"+"]".repeat(levels)+"}").toByteArray()
        FactoryJson.objectFrom(nested(7),16*1024)
        assertThrows(Exception::class.java){FactoryJson.objectFrom(nested(8),16*1024)}
        val nonAsciiEscape="{\"x\":\""+'\\'+"uＦＦＦＦ\"}"
        assertThrows(Exception::class.java){FactoryJson.objectFrom(nonAsciiEscape.toByteArray(),16*1024)}
    }
    @Test fun emptyCapabilitiesDoNotGainNativeAccess() {
        val value=FactorySpec.parse(spec().put("capabilities",JSONArray()))
        assertTrue(value.capabilities.isEmpty())
    }
    @Test fun all1048576SelectionsUseImmutableCatalogWithOnlyTheSelectiveBiometricPermission() {
        assertEquals(20, FactorySpec.CAPABILITIES.size)
        var checked = 0
        for (mask in 0 until 1048576) {
            val selected = FactorySpec.CAPABILITIES.filterIndexed { bit, _ -> mask and (1 shl bit) != 0 }
            val value = FactorySpec.parse(spec().put("capabilities", JSONArray(selected.reversed())))
            assertEquals(selected.sorted(), value.capabilities)
            assertEquals(1, JSONObject(String(value.runtimeConfig(if ("documents" in selected || "browser" in selected || "maps" in selected || "phone" in selected || "email" in selected || "sms" in selected || "contacts" in selected || "calendar" in selected) broker() else null))).getInt("schemaVersion"))
            for (name in selected) {
                val capability = com.jarvys.factory.contract.CapabilityCatalog.CAPABILITIES[name]!!
                assertEquals(if (name == "biometrics") setOf("android.permission.USE_BIOMETRIC") else emptySet<String>(), capability.permissions)
                assertEquals(if (name == "biometrics") 30 else 24, capability.minimumApi)
                assertTrue(capability.features.isEmpty())
                assertTrue(capability.components.isEmpty())
                assertTrue(capability.dependencies.isEmpty())
                assertTrue(capability.conflicts.isEmpty())
                assertFalse(capability.methods.isEmpty())
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (value.capabilities as MutableList<String>).add("camera")
            }
            checked++
        }
        assertEquals(1048576, checked)
    }

    @Test fun standaloneBiometricsConfigDeclaresOnlyItsCapabilityWithoutAnyHostPin() {
        val biometrics = FactorySpec.parse(spec().put("capabilities", JSONArray(listOf("biometrics"))))
        val config = com.jarvys.factory.runtime.FactoryConfig.parse(String(biometrics.runtimeConfig()), biometrics.appId)
        assertEquals(setOf("biometrics"), config.capabilities)
        assertNull(config.documentBroker)
        assertThrows(Exception::class.java) { biometrics.runtimeConfig(broker()) }
        rejects { it.put("permissions", JSONArray(listOf("android.permission.USE_BIOMETRIC"))) }
        rejects { it.put("features", JSONArray(listOf("android.hardware.fingerprint"))) }
        rejects { it.put("minSdkVersion", 30) }
    }

    @Test fun mapsAndPhoneOnlyGeneratedConfigPinsHostAndKeepsCapabilitiesIndependent() {
        for (capability in listOf("maps", "phone", "email", "sms", "contacts", "calendar")) {
            val typed = FactorySpec.parse(spec().put("capabilities", JSONArray(listOf(capability))))
            assertThrows(Exception::class.java) { typed.runtimeConfig() }
            val configBytes = typed.runtimeConfig(broker())
            val parsed = com.jarvys.factory.runtime.FactoryConfig.parse(String(configBytes), typed.appId)
            assertEquals(setOf(capability), parsed.capabilities)
            assertEquals("com.jarvys.agent", parsed.documentBroker.packageName)
            assertEquals("a".repeat(64), parsed.documentBroker.certificateSha256)
        }
    }
    @Test fun standaloneDatabaseConfigDoesNotRequireOrImplyHostCapabilities() {
        val database = FactorySpec.parse(spec().put("capabilities", JSONArray(listOf("database"))))
        val config = com.jarvys.factory.runtime.FactoryConfig.parse(String(database.runtimeConfig()), database.appId)
        assertEquals(setOf("database"), config.capabilities)
        assertNull(config.documentBroker)
    }
    private fun broker() = JSONObject().put("packageName","com.jarvys.agent").put("certificateSha256","a".repeat(64))
    @Test fun documentBrokerIsBuildOwnedAndCannotBeDeclaredInProjectSpec() {
        rejects { it.put("documentBroker",broker()) }
        val docs = FactorySpec.parse(spec().put("capabilities",JSONArray(listOf("documents"))))
        assertThrows(Exception::class.java) { docs.runtimeConfig() }
        val config = JSONObject(String(docs.runtimeConfig(broker())))
        assertEquals("com.jarvys.agent",config.getJSONObject("documentBroker").getString("packageName"))
        assertThrows(Exception::class.java) { FactorySpec.parse(spec()).runtimeConfig(broker()) }
        assertThrows(Exception::class.java) { docs.runtimeConfig(broker().put("packageName","org.example.untrusted")) }
    }


    @Test fun standalonePresentationConfigDoesNotRequireOrImplyHostCapabilities() {
        val presentation = FactorySpec.parse(spec().put("capabilities", JSONArray(listOf("presentation"))))
        val config = com.jarvys.factory.runtime.FactoryConfig.parse(String(presentation.runtimeConfig()), presentation.appId)
        assertEquals(setOf("presentation"), config.capabilities)
        assertNull(config.documentBroker)
        assertThrows(Exception::class.java) { presentation.runtimeConfig(broker()) }
    }

    @Test fun backupPolicyIsOptionalCanonicalBoundedAndRequiresBothCapabilities() {
        val old = FactorySpec.parse(spec())
        val empty = FactorySpec.parse(spec().put("dataBackup", JSONObject().put("tables", JSONArray())))
        assertTrue(old.dataBackupTables.isEmpty())
        assertEquals(old.toJson().toString(), empty.toJson().toString())
        assertArrayEquals(old.runtimeConfig(), empty.runtimeConfig())
        assertFalse(old.toJson().has("dataBackup"))
        assertEquals(spec().put("capabilities", JSONArray(listOf("export", "storage"))).toString(), old.toJson().toString())
        val legacyConfig = JSONObject().put("schemaVersion", 1).put("appId", old.appId).put("name", old.name)
            .put("entryPoint", "www/index.html").put("capabilities", JSONArray(old.capabilities)).toString().toByteArray(Charsets.UTF_8)
        assertArrayEquals(legacyConfig, old.runtimeConfig())
        val input = spec().put("capabilities", JSONArray(listOf("database", "documents")))
            .put("dataBackup", JSONObject().put("tables", JSONArray(listOf("notes", "tasks"))))
        val parsed = FactorySpec.parse(input)
        assertEquals(listOf("notes", "tasks"), parsed.dataBackupTables)
        assertEquals("[\"notes\",\"tasks\"]", parsed.toJson().getJSONObject("dataBackup").getJSONArray("tables").toString())
        val runtime = com.jarvys.factory.runtime.FactoryConfig.parse(String(parsed.runtimeConfig(broker())), parsed.appId)
        assertEquals(setOf("notes", "tasks"), runtime.dataBackupTables)
        assertThrows(UnsupportedOperationException::class.java) { (parsed.dataBackupTables as MutableList<String>).add("other") }
        for (policy in listOf<Any>(JSONObject.NULL, "notes", JSONArray(), JSONObject(),
            JSONObject().put("tables", "notes"), JSONObject().put("tables", JSONArray()).put("all", true))) {
            rejects { it.put("dataBackup", policy) }
        }
        for (tables in listOf(listOf<Any>("notes", "notes"), listOf("tasks", "notes"), listOf(1), listOf("id"),
            listOf("sqlite_master"), listOf("factory_schema"), listOf("Notes"), (0..8).map { "table$it" })) {
            rejects { it.put("capabilities", JSONArray(listOf("database", "documents"))).put("dataBackup", JSONObject().put("tables", JSONArray(tables))) }
        }
        for (caps in listOf(emptyList(), listOf("database"), listOf("documents"))) {
            rejects { it.put("capabilities", JSONArray(caps)).put("dataBackup", JSONObject().put("tables", JSONArray(listOf("notes")))) }
        }
        val maximum = (0..7).map { "table$it" }
        assertEquals(maximum, FactorySpec.parse(input.put("dataBackup", JSONObject().put("tables", JSONArray(maximum)))).dataBackupTables)
    }

    @Test fun storageBackupPolicyIsIndependentAndPreservesLegacySerialization() {
        val old = FactorySpec.parse(spec())
        assertTrue(old.dataBackupKeys.isEmpty())
        for (policy in listOf(JSONObject().put("keys", JSONArray()), JSONObject().put("tables", JSONArray()).put("keys", JSONArray()))) {
            val empty = FactorySpec.parse(spec().put("dataBackup", policy))
            assertEquals(old.toJson().toString(), empty.toJson().toString())
            assertArrayEquals(old.runtimeConfig(), empty.runtimeConfig())
        }
        val legacy = FactorySpec.parse(spec().put("capabilities", JSONArray(listOf("database", "documents")))
            .put("dataBackup", JSONObject().put("tables", JSONArray(listOf("notes")))))
        assertEquals(setOf("tables"), legacy.toJson().getJSONObject("dataBackup").keys().asSequence().toSet())
        assertEquals(legacy.toJson().toString(), FactorySpec.parse(legacy.toJson().apply {
            getJSONObject("dataBackup").put("keys", JSONArray())
        }).toJson().toString())
        val keys = listOf("A.key:1-", "settings")
        val input = spec().put("capabilities", JSONArray(listOf("storage", "documents")))
            .put("dataBackup", JSONObject().put("keys", JSONArray(keys)))
        val parsed = FactorySpec.parse(input)
        assertEquals(keys, parsed.dataBackupKeys)
        assertTrue(parsed.dataBackupTables.isEmpty())
        assertEquals(setOf("keys"), parsed.toJson().getJSONObject("dataBackup").keys().asSequence().toSet())
        val runtime = com.jarvys.factory.runtime.FactoryConfig.parse(String(parsed.runtimeConfig(broker())), parsed.appId)
        assertEquals(keys.toSet(), runtime.dataBackupKeys)
        assertTrue(runtime.dataBackupTables.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (parsed.dataBackupKeys as MutableList<String>).add("other") }
        for (invalid in listOf<Any>(JSONObject.NULL, "settings", listOf<Any>(1), listOf("a", "a"), listOf("b", "a"),
            listOf(""), listOf("a/b"), listOf("a b"), listOf("a".repeat(97)), (0..32).map { "key%02d".format(it) })) {
            val value = if (invalid is List<*>) JSONArray(invalid) else invalid
            rejects { it.put("capabilities", JSONArray(listOf("storage", "documents"))).put("dataBackup", JSONObject().put("keys", value)) }
        }
        for (caps in listOf(emptyList(), listOf("storage"), listOf("documents"), listOf("database", "documents"))) {
            rejects { it.put("capabilities", JSONArray(caps)).put("dataBackup", JSONObject().put("keys", JSONArray(keys))) }
        }
        val maximum = (0..31).map { "key%02d".format(it) }
        assertEquals(maximum, FactorySpec.parse(input.put("dataBackup", JSONObject().put("keys", JSONArray(maximum)))).dataBackupKeys)
        assertEquals(listOf("a".repeat(96)), FactorySpec.parse(input.put("dataBackup", JSONObject().put("keys", JSONArray(listOf("a".repeat(96)))))).dataBackupKeys)
        val combined = input.put("capabilities", JSONArray(listOf("storage", "database", "documents")))
            .put("dataBackup", JSONObject().put("keys", JSONArray(keys)).put("tables", JSONArray(listOf("notes"))))
        val both = FactorySpec.parse(combined)
        assertEquals(keys, both.dataBackupKeys)
        assertEquals(listOf("notes"), both.dataBackupTables)
        assertEquals(setOf("tables", "keys"), both.toJson().getJSONObject("dataBackup").keys().asSequence().toSet())
        for (caps in listOf(listOf("storage", "documents"), listOf("database", "documents")))
            assertThrows(IllegalArgumentException::class.java) { FactorySpec.parse(combined.put("capabilities", JSONArray(caps))) }
    }

}
