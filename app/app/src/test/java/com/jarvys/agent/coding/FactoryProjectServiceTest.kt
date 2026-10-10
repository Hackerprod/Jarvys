package com.jarvys.agent.coding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.apkfactory.FactorySigningScope
import com.jarvys.agent.apkfactory.FactorySigningIdentity
import com.jarvys.agent.apkfactory.TemplateApk
import com.jarvys.agent.connectors.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactoryProjectServiceTest {
    @get:Rule val folder=TemporaryFolder()
    private inner class Fixture(val enableEphemeralSigning:Boolean=false) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val scope=ProjectScopeStore(folder.newFolder()).open("factory-${UUID.randomUUID()}")
        val root=scope.rootDirectory()
        @Volatile var active=true
        var requested=0
        var lastSummary:ApprovalSummary?=null
        var obtained=0
        var signedRecord:JSONObject?=null
        var ephemeralIdentity:FactorySigningIdentity.Identity?=null
        var identityState=FactorySigningIdentity.State(false,null,0)
        var decision=ApprovalDecision.DENIED
        var duringApproval:()->Unit={}
        var afterRecord:()->Unit={}
        val identities=object:FactorySigningIdentity(context) {
            override fun state(appId:String)=identityState
            override fun obtainAfterApproval(appId:String,approvedState:State):Identity {
                check(approvedState==state(appId))
                obtained++;check(enableEphemeralSigning) { "No real keys are generated in this test" }
                ephemeralIdentity?.let { return it }
                val pair=java.security.KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
                val certificate=com.jarvys.agent.apkfactory.EphemeralFactoryCertificate.create(pair.public.encoded){data ->
                    java.security.Signature.getInstance("SHA256withRSA").run{initSign(pair.private);update(data);sign()}
                }
                return Identity(pair.private,certificate,ProjectScope.sha256(certificate.encoded)).also{ephemeralIdentity=it}
            }
            override fun recordSigned(appId:String,fingerprint:String,versionCode:Int,apkSha:String,scope:FactorySigningScope?) {
                check(enableEphemeralSigning);check(fingerprint==ephemeralIdentity!!.fingerprint)
                check(versionCode>identityState.lastVersion)
                assertNotNull(scope)
                identityState=State(true,fingerprint,versionCode,scope,apkSha)
                signedRecord=JSONObject().put("appId",appId).put("fingerprint",fingerprint).put("versionCode",versionCode).put("sha256",apkSha)
                afterRecord()
            }
        }
        lateinit var gate:ApprovalGate
        val service:FactoryProjectService
        init {
            gate=ApprovalGate(presenter=object:ApprovalPresenter {
                override fun show(id:String,summary:ApprovalSummary) {
                    lastSummary=summary;requested++;assertFalse(summary.allowAlwaysAvailable)
                    if (identityState.mode == FactorySigningIdentity.RECOVERABLE) {
                        assertTrue(summary.lines.any{it.contains("recoverable signing identity")})
                        assertTrue(summary.lines.any{it.contains("not independent proof")})
                    } else {
                        assertTrue(summary.lines.any{it.contains("non-exportable")})
                        assertTrue(summary.lines.any{it.contains("uninstalling Jarvys")})
                    }
                    duringApproval();gate.resolve(id,decision)
                }
                override fun update(id:String,decision:ApprovalDecision)=Unit
            })
            service=FactoryProjectService(context,scope,"test",gate,{active},identities)
            File(root,"web").mkdir()
            File(root,"web/index.html").writeText("<!doctype html><title>Notebook</title><script src='/factory-sdk.js'></script><script src='/www/app.js'></script>")
            File(root,"web/app.js").writeText("document.title='Notebook';")
            File(root,"icon.json").writeText("""{"schemaVersion":1,"background":"#223344","shapes":[{"type":"circle","cx":96,"cy":96,"r":64,"fill":"#FFFFFF"}]}""")
            spec("org.example.notebook",1)
        }
        fun spec(id:String,version:Int,capabilities:List<String> = listOf("storage","export")){File(root,"factory.json").writeText(JSONObject().put("schemaVersion",1).put("appId",id).put("name","Mi cuaderno")
            .put("versionCode",version).put("versionName","$version.0").put("capabilities",JSONArray(capabilities))
            .put("webDir","web").put("icon","icon.json").toString())}
        fun build(path:String="notes.apk")=service.build("factory.json",path,scope.version(),CancellationToken.cancellable())
    }
    @Test fun mapsAndPhoneOnlyBuildsEmbedVerifiedHostPinsAndNoPermissions() {
        for (capability in listOf("maps", "phone", "email", "sms")) {
            val f = Fixture()
            val packageManager = f.context.packageManager
            val own = packageManager.getPackageInfo(f.context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            val originalSigning = own.signingInfo
            val synthetic = android.content.pm.Signature(byteArrayOf(7, 12, 24, 42))
            own.signingInfo = android.content.pm.SigningInfo().also {
                org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowSigningInfo>(it).setSignatures(arrayOf(synthetic))
            }
            org.robolectric.Shadows.shadowOf(packageManager).installPackage(own)
            try {
                f.spec("org.example.typed$capability", 1, listOf(capability))
                f.build()
                ZipFile(File(f.root, "notes.apk")).use { zip ->
                    val configText = zip.getInputStream(zip.getEntry("assets/factory-app.json")).bufferedReader().use { it.readText() }
                    val config = com.jarvys.factory.runtime.FactoryConfig.parse(configText, "org.example.typed$capability")
                    assertEquals(setOf(capability), config.capabilities)
                    assertEquals(f.context.packageName, config.documentBroker.packageName)
                    assertEquals(ProjectScope.sha256(synthetic.toByteArray()), config.documentBroker.certificateSha256)
                }
                val manifest = TemplateApk.inspect(File(f.root, "notes.apk").readBytes())
                assertTrue(manifest.permissions.isEmpty())
                assertEquals(listOf("com.jarvys.agent", "com.jarvys.agent.recoverytest"), manifest.plan.queries)
                assertEquals(0, f.requested); assertEquals(0, f.obtained)
            } finally {
                own.signingInfo = originalSigning
                org.robolectric.Shadows.shadowOf(packageManager).installPackage(own)
            }
        }
    }
    @Test fun installationRejectsUnsignedBuildWithoutApprovalOrSession() {
        val f = Fixture(); val built = f.build()
        assertThrows(Exception::class.java) { f.service.installation("install", "notes.apk", built.getString("sha256"), f.scope.version(), CancellationToken.cancellable()) }
        assertEquals(0, f.requested); assertEquals(0, f.obtained)
    }
    @Test fun signedInstallationRoutesToHumanReviewOrExplicitPlayLimitation() {
        val install = com.jarvys.agent.apkfactory.InstallTestFixture()
        try {
            val f = Fixture(true); val built = f.build(); f.decision = ApprovalDecision.APPROVED
            val signed = f.service.sign("notes.apk", built.getString("sha256"), "signed.apk", f.scope.version(), CancellationToken.cancellable())
            val result = f.service.installation("install", "signed.apk", signed.getString("sha256"), f.scope.version(), CancellationToken.cancellable())
            assertEquals(if (com.jarvys.agent.BuildConfig.FLAVOR == "full") "awaiting_user" else "unavailable_in_play", result.getString("state"))
            assertEquals(signed.getString("sha256"), result.getString("apk_sha256"))
            assertEquals(signed.getString("certificate_sha256"), result.getString("certificate_sha256"))
            assertEquals(0, install.backend.creates); assertEquals(0, install.backend.commits)
            assertEquals(1, f.requested)
            if (com.jarvys.agent.BuildConfig.FLAVOR == "full") {
                File(f.root, "signed.apk").delete()
                val status = f.service.installation("install_status", "signed.apk", signed.getString("sha256"), f.scope.version(), CancellationToken.cancellable())
                assertEquals("awaiting_user", status.getString("state"))
                val cancelled = f.service.installation("install_cancel", "signed.apk", signed.getString("sha256"), f.scope.version(), CancellationToken.cancellable())
                assertEquals("cancelled_before_commit", cancelled.getString("state"))
            }
        } finally { install.close() }
    }
    @Test fun installationRechecksProjectScopeAndDoesNotAcceptCopiedSignedPath() {
        val f = Fixture(true); val built = f.build(); f.decision = ApprovalDecision.APPROVED
        val signed = f.service.sign("notes.apk", built.getString("sha256"), "signed.apk", f.scope.version(), CancellationToken.cancellable())
        File(f.root, "signed.apk").copyTo(File(f.root, "copy.apk"))
        assertThrows(Exception::class.java) { f.service.installation("install", "copy.apk", signed.getString("sha256"), f.scope.version(), CancellationToken.cancellable()) }
        assertThrows(Exception::class.java) { f.service.installation("install", "signed.apk", signed.getString("sha256"), 0, CancellationToken.cancellable()) }
        f.active = false
        assertThrows(Exception::class.java) { f.service.installation("install_status", "signed.apk", signed.getString("sha256"), f.scope.version(), CancellationToken.cancellable()) }
        assertEquals(1, f.requested)
    }
    @Test fun factoryTestBindsActualBuildWithoutKeysWritesOrInstalledClaims() {
        val f=Fixture(); val built=f.build(); val version=f.scope.version()
        val result=f.service.harness("test","notes.apk",built.getString("sha256"),version,CancellationToken.cancellable())
        assertEquals("passed",result.getString("state")); assertEquals("shared_core_test",result.getString("mode"))
        assertEquals(built.getString("sha256"),result.getString("apk_sha256"))
        assertEquals(version,f.scope.version()); assertEquals(0,f.requested); assertEquals(0,f.obtained)
        assertFalse(result.toString().contains(f.root.absolutePath))
    }
    @Test fun factoryHarnessRejectsHashScopeAndAvailabilityChanges() {
        val f=Fixture(); val built=f.build(); val sha=built.getString("sha256")
        assertThrows(Exception::class.java) { f.service.harness("test","notes.apk",sha,0,CancellationToken.cancellable()) }
        f.active=false
        assertThrows(Exception::class.java) { f.service.harness("test","notes.apk",sha,f.scope.version(),CancellationToken.cancellable()) }
        f.active=true; File(f.root,"notes.apk").appendText("changed")
        assertThrows(Exception::class.java) { f.service.harness("test","notes.apk",sha,f.scope.version(),CancellationToken.cancellable()) }
        assertEquals(0,f.obtained)
    }
    @Test fun factoryHarnessRejectsCopiedArtifactWithoutMatchingReceiptAndCancelledRun() {
        val f=Fixture(); val built=f.build(); val sha=built.getString("sha256")
        File(f.root,"notes.apk").copyTo(File(f.root,"copy.apk"))
        assertThrows(Exception::class.java) { f.service.harness("test","copy.apk",sha,f.scope.version(),CancellationToken.cancellable()) }
        val token=CancellationToken.cancellable().apply { cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) { f.service.harness("preview","notes.apk",sha,f.scope.version(),token) }
    }
    @Test fun previewStatusBindsRealLongScopeVersionAndRetainsCancelledReceipt() {
        val f=Fixture(); val built=f.build(); val sha=built.getString("sha256")
        val token=CancellationToken.cancellable()
        assertEquals(1L,f.scope.version())
        try {
            val launch=f.service.harness("preview","notes.apk",sha,1L,token)
            assertEquals("launch_requested",launch.getString("state"))
            val status=f.service.harness("preview_status","notes.apk",sha,1L,CancellationToken.cancellable())
            assertEquals("launch_requested",status.getString("state"))
            assertEquals(launch.getString("build_id"),status.getString("build_id"))
            token.cancel()
            val cancelled=f.service.harness("preview_status","notes.apk",sha,1L,CancellationToken.cancellable())
            assertEquals("revoked",cancelled.getString("state"))
        } finally { token.cancel() }
    }
    @Test fun factoryHarnessRejectsReadDeniedAndForgedReceiptMetadata() {
        val f=Fixture(); val built=f.build(); val sha=built.getString("sha256")
        val denied=FactoryProjectService(f.context,f.scope.restrict(setOf(ProjectScope.Capability.WRITE)),"test",f.gate,{true},f.identities)
        assertThrows(Exception::class.java) { denied.harness("test","notes.apk",sha,f.scope.version(),CancellationToken.cancellable()) }
        val file=receiptFile(f,sha); val original=file.readText()
        for (field in listOf("state","projectIdentity","projectId","templateSha256","apkSha256")) {
            file.writeText(JSONObject(original).put(field,"forged").toString())
            assertThrows(Exception::class.java) { f.service.harness("test","notes.apk",sha,f.scope.version(),CancellationToken.cancellable()) }
        }
        file.writeText(JSONObject(original).apply { getJSONObject("sources").put("web/index.html","0".repeat(64)) }.toString())
        assertThrows(Exception::class.java) { f.service.harness("test","notes.apk",sha,f.scope.version(),CancellationToken.cancellable()) }
        file.writeText(JSONObject(original).apply { getJSONObject("spec").put("name","Changed") }.toString())
        assertThrows(Exception::class.java) { f.service.harness("test","notes.apk",sha,f.scope.version(),CancellationToken.cancellable()) }
        assertEquals(0,f.requested); assertEquals(0,f.obtained)
    }
    @Test fun factoryToolRejectsExtraArgumentsMissingBindingsAndNonIntegerVersion() {
        val f=Fixture(); val tool=ApkFactoryTools.Tool(f.service)
        val valid=mapOf<String,Any>("action" to "test", "input_path" to "notes.apk", "expected_sha256" to "0".repeat(64), "expected_scope_version" to 0L)
        for (arguments in listOf(valid + ("javascript" to "secret"), valid - "expected_sha256", valid + ("expected_scope_version" to 0.0))) {
            val result=tool.execute(arguments,CancellationToken.cancellable())
            assertFalse(result.success)
        }
    }
    @Test fun completeOfflineBuildPreservesDexAndProvenanceWithNoAndroidPermissions() {
        val f=Fixture();val result=f.build();assertFalse(result.getBoolean("signed"));assertEquals(0,f.obtained)
        val apk=File(f.root,"notes.apk");assertEquals(result.getString("sha256"),ProjectScope.sha256(apk.readBytes()))
        val info=TemplateApk.inspect(apk.readBytes());assertEquals("org.example.notebook",info.appId);assertEquals("Mi cuaderno",info.label)
        assertTrue(info.permissions.isEmpty());assertEquals(1,f.scope.version())
        ZipFile(apk).use{zip->
            assertNotNull(zip.getEntry("assets/factory-provenance.json"));assertNotNull(zip.getEntry("assets/factory-sdk.js"))
            assertEquals("document.title='Notebook';",zip.getInputStream(zip.getEntry("assets/www/app.js")).bufferedReader().readText())
            val config=JSONObject(zip.getInputStream(zip.getEntry("assets/factory-app.json")).bufferedReader().readText())
            assertEquals("org.example.notebook",config.getString("appId"));assertEquals(2,config.getJSONArray("capabilities").length())
        }
    }
    @Test fun sameSourcesProduceSameUnsignedBytesAcrossDistinctOutputPaths() {
        val f=Fixture();val first=f.build("one.apk");val second=f.build("two.apk")
        assertEquals(first.getString("sha256"),second.getString("sha256"));assertArrayEquals(File(f.root,"one.apk").readBytes(),File(f.root,"two.apk").readBytes())
    }
    @Test fun twoPackageIdsAndAnUpdateHaveIndependentIdentityAndStableRuntimeDex() {
        val f=Fixture();f.build("a.apk");f.spec("org.example.other",1);f.build("b.apk");f.spec("org.example.notebook",2);f.build("a2.apk")
        val a=TemplateApk.inspect(File(f.root,"a.apk").readBytes());val b=TemplateApk.inspect(File(f.root,"b.apk").readBytes());val a2=TemplateApk.inspect(File(f.root,"a2.apk").readBytes())
        assertNotEquals(a.appId,b.appId);assertEquals(a.appId,a2.appId);assertEquals(2,a2.versionCode)
        fun dex(name:String)=ZipFile(File(f.root,name)).use{it.getInputStream(it.getEntry("classes.dex")).readBytes()}
        assertArrayEquals(dex("a.apk"),dex("b.apk"));assertArrayEquals(dex("a.apk"),dex("a2.apk"))
    }

    private fun receiptFile(f:Fixture,sha:String):File = File(f.context.noBackupFilesDir,
        "apk-factory/builds/${f.scope.id()}/$sha-${ProjectScope.sha256("notes.apk".toByteArray())}.json")
    private fun legacyReceipt(f:Fixture,keepCurrentContract:Boolean):String {
        val built=f.build();val oldSha=built.getString("sha256");val file=File(f.root,"notes.apk")
        val legacy=com.jarvys.agent.apkfactory.TemplateApkTest.legacyArtifactFixture(file.readBytes())
        val sha=ProjectScope.sha256(legacy);file.writeBytes(legacy)
        val receipt=JSONObject(receiptFile(f,oldSha).readText()).put("apkSha256",sha).put("apkBytes",legacy.size)
        if(!keepCurrentContract) {receipt.remove("manifestContract");receipt.remove("dexSha256")}
        receiptFile(f,sha).writeText(receipt.toString());return sha
    }
    @Test fun legacyLayoutAndV1ReceiptReachApprovalWithoutCreatingKeys() {
        val f=Fixture();val sha=legacyReceipt(f,false)
        assertThrows(Exception::class.java){f.service.sign("notes.apk",sha,"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(1,f.requested);assertEquals(0,f.obtained);assertFalse(File(f.root,"signed.apk").exists())
        assertTrue(f.lastSummary!!.lines.any{it.contains("older window behavior")})
        assertTrue(f.lastSummary!!.lines.any{it=="Decoded Android permissions: none"})
        assertTrue(f.lastSummary!!.lines.any{it.contains("com.jarvys.factory.runtime.FactoryActivity")})
        assertEquals(sha,ProjectScope.sha256(File(f.root,"notes.apk").readBytes()))
    }
    @Test fun approvedCurrentArtifactSignsVerifiesAndPublishesWithEphemeralIdentity() {
        successfulSigningPreservesContract(false)
    }
    @Test fun approvedLegacyReceiptSignsItsExactHistoricalPlanWithEphemeralIdentity() {
        successfulSigningPreservesContract(true)
    }
    private fun successfulSigningPreservesContract(legacy:Boolean) {
        val f=Fixture(enableEphemeralSigning=true)
        val sha=if(legacy) legacyReceipt(f,false) else f.build().getString("sha256")
        val unsigned=File(f.root,"notes.apk").readBytes();val version=f.scope.version()
        f.decision=ApprovalDecision.APPROVED
        val result=f.service.sign("notes.apk",sha,"signed.apk",version,CancellationToken.cancellable())
        val output=File(f.root,"signed.apk");val signed=output.readBytes()
        assertTrue(result.getBoolean("signed"));assertEquals(1,f.requested);assertEquals(1,f.obtained)
        assertEquals(version+1,f.scope.version());assertEquals(f.scope.version(),result.getLong("scope_version"))
        assertArrayEquals(unsigned,File(f.root,"notes.apk").readBytes())
        TemplateApk.verifyUnchangedPayload(unsigned,signed)
        val expected=TemplateApk.Spec("org.example.notebook","Mi cuaderno",1,"1.0",listOf("storage","export"))
        val info=if(legacy) TemplateApk.verifyExistingV1(signed,expected) else TemplateApk.verify(signed,expected)
        assertEquals(if(legacy) com.jarvys.factory.contract.ManifestPlan.Profile.V1_BEFORE_SAFE_AREA
            else com.jarvys.factory.contract.ManifestPlan.Profile.CURRENT,info.plan.profile)
        val verified=com.android.apksig.ApkVerifier.Builder(output).setMinCheckedPlatformVersion(24).build().verify()
        assertTrue(verified.isVerified);assertTrue(verified.isVerifiedUsingV2Scheme);assertTrue(verified.isVerifiedUsingV3Scheme)
        val fingerprint=ProjectScope.sha256(verified.signerCertificates.single().encoded)
        assertEquals(fingerprint,result.getString("certificate_sha256"));assertEquals(fingerprint,f.signedRecord!!.getString("fingerprint"))
        assertEquals("org.example.notebook",f.signedRecord!!.getString("appId"));assertEquals(1,f.signedRecord!!.getInt("versionCode"))
        val signedSha=ProjectScope.sha256(signed)
        assertEquals(signedSha,result.getString("sha256"));assertEquals(signedSha,f.signedRecord!!.getString("sha256"))
        val receipt=File(f.context.noBackupFilesDir,"apk-factory/builds/${f.scope.id()}/$signedSha-${ProjectScope.sha256("signed.apk".toByteArray())}.json")
        assertEquals("published",JSONObject(receipt.readText()).getString("state"))
        assertEquals(legacy,f.lastSummary!!.lines.any{it.contains("older window behavior")})
    }
    @Test fun signedUpdatesDiscloseExpansionReductionAndUnchangedScope() {
        val f=Fixture(enableEphemeralSigning=true); f.decision=ApprovalDecision.APPROVED
        fun signVersion(version:Int,capabilities:List<String>) {
            f.spec("org.example.notebook",version,capabilities)
            val path="v$version.apk";val build=f.build(path)
            f.service.sign(path,build.getString("sha256"),"signed-v$version.apk",f.scope.version(),CancellationToken.cancellable())
        }
        signVersion(1,listOf("storage"))
        assertTrue(f.lastSummary!!.lines.any{it.contains("First signing")})
        val firstFingerprint=f.identityState.fingerprint
        signVersion(2,listOf("storage","export"))
        assertTrue(f.lastSummary!!.lines.any{it=="Scope additions (capabilities): export"})
        signVersion(3,listOf("storage"))
        assertTrue(f.lastSummary!!.lines.any{it=="Scope removals (capabilities): export"})
        signVersion(4,listOf("storage"))
        assertTrue(f.lastSummary!!.lines.any{it.contains("Effective scope unchanged")})
        assertEquals(firstFingerprint,f.identityState.fingerprint)
        assertEquals(4,f.identityState.lastVersion)
    }
    @Test fun unresolvedRestoredHistoryBlocksBeforeApprovalAndKeyAccess() {
        val f=Fixture(); f.identityState=FactorySigningIdentity.State(true,"a".repeat(64),1,
            mode=FactorySigningIdentity.RECOVERABLE,continuityKnown=false,continuityResolution="restored_unknown")
        f.spec("org.example.notebook",2); val build=f.build(); val before=f.identityState
        val error=assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertTrue(error.message!!.contains("history is unresolved")); assertEquals(0,f.requested); assertEquals(0,f.obtained)
        assertEquals(before,f.identityState); assertFalse(File(f.root,"signed.apk").exists())
    }
    @Test fun recoverableApprovalDisclosesUserDeclaredFloorAndDenialPreservesIdentity() {
        val f=Fixture(); f.identityState=FactorySigningIdentity.State(true,"a".repeat(64),5,
            mode=FactorySigningIdentity.RECOVERABLE,continuityKnown=true,continuityResolution="user_declared_floor")
        f.spec("org.example.notebook",6); val build=f.build(); val before=f.identityState
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(1,f.requested);assertEquals(0,f.obtained);assertEquals(before,f.identityState)
        assertTrue(f.lastSummary!!.lines.any{it.contains("user_declared_floor; floor 5")})
        assertFalse(f.lastSummary!!.lines.any{it.contains("Create a new")})
        assertTrue(f.lastSummary!!.lines.any{it.contains("not proof of restoration")})
    }
    @Test fun missingLegacyBaselineDisclosesUnknownAndDenialPreservesState() {
        val f=Fixture(); f.identityState=FactorySigningIdentity.State(true,"a".repeat(64),1)
        f.spec("org.example.notebook",2);val build=f.build();val before=f.identityState
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertTrue(f.lastSummary!!.lines.any{it.contains("Scope expansion cannot be determined")})
        assertFalse(f.lastSummary!!.lines.any{it.contains("Effective scope unchanged") || it.contains("First signing")})
        assertEquals(before,f.identityState);assertNull(f.signedRecord);assertEquals(0,f.obtained)
    }
    @Test fun concurrentScopeRecordChangeCannotReuseApproval() {
        val f=Fixture(enableEphemeralSigning=true);val build=f.build();f.decision=ApprovalDecision.APPROVED
        f.duringApproval={f.identityState=f.identityState.copy(recordSha256="changed")}
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(1,f.requested);assertEquals(0,f.obtained);assertNull(f.signedRecord)
        assertFalse(File(f.root,"signed.apk").exists())
    }
    @Test fun interruptedPublicationKeepsReservedVersionAndScope() {
        val f=Fixture(enableEphemeralSigning=true);val build=f.build();f.decision=ApprovalDecision.APPROVED
        f.afterRecord={File(f.root,"signed.apk").writeText("collision after signature")}
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertNotNull(f.identityState.lastScope);assertEquals(1,f.identityState.lastVersion)
        assertNotNull(f.identityState.lastApkSha256)
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"retry.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(1,f.requested);assertEquals(1,f.obtained)
        val sha=f.identityState.lastApkSha256!!
        val receipt=File(f.context.noBackupFilesDir,"apk-factory/builds/${f.scope.id()}/$sha-${ProjectScope.sha256("signed.apk".toByteArray())}.json")
        assertEquals("publication_unconfirmed",JSONObject(receipt.readText()).getString("state"))
    }
    @Test fun inspectExposesVerifiedResourceAndComponentBindings() {
        val f=Fixture();val inspected=f.service.inspect(CancellationToken.cancellable())
        val bindings=inspected.getJSONObject("resourceBindings")
        assertTrue(bindings.has("icon"));assertTrue(bindings.has("backup"))
        for(role in listOf("icon","backup")) {
            val binding=bindings.getJSONObject(role)
            assertTrue(binding.getInt("id") != 0);assertTrue(binding.getString("path").startsWith("res/"))
            assertTrue(binding.getString("type").isNotEmpty());assertTrue(binding.getString("name").isNotEmpty())
        }
        assertTrue(inspected.getJSONObject("componentDex").has("com.jarvys.factory.runtime.FactoryActivity"))
    }
    @Test fun newlyRecordedContractCannotFallBackToTheLegacyLayout() {
        val f=Fixture();val sha=legacyReceipt(f,true)
        assertThrows(Exception::class.java){f.service.sign("notes.apk",sha,"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(0,f.requested);assertEquals(0,f.obtained)
    }
    @Test fun staleDexInventoryInReceiptFailsBeforeApproval() {
        val f=Fixture();val built=f.build();val sha=built.getString("sha256");val file=receiptFile(f,sha)
        val receipt=JSONObject(file.readText());receipt.getJSONObject("dexSha256").put("classes.dex","0".repeat(64));file.writeText(receipt.toString())
        assertThrows(Exception::class.java){f.service.sign("notes.apk",sha,"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(0,f.requested);assertEquals(0,f.obtained)
    }
    @Test fun cancellationDuringApprovalCannotCreateIdentityOrPublish() {
        val f=Fixture();val built=f.build();val token=CancellationToken.cancellable();f.decision=ApprovalDecision.APPROVED
        f.duringApproval={token.cancel()}
        assertThrows(Exception::class.java){f.service.sign("notes.apk",built.getString("sha256"),"signed.apk",f.scope.version(),token)}
        assertEquals(1,f.requested);assertEquals(0,f.obtained);assertFalse(File(f.root,"signed.apk").exists())
    }
    @Test fun collisionAndSymlinkInputsNeverOverwriteExistingContent() {
        val f=Fixture();File(f.root,"notes.apk").writeText("existing")
        assertThrows(Exception::class.java){f.build()};assertEquals("existing",File(f.root,"notes.apk").readText())
        val outside=folder.newFile().apply{writeText("outside")}
        Files.createSymbolicLink(File(f.root,"web/private.txt").toPath(),outside.toPath())
        assertThrows(Exception::class.java){f.build("new.apk")};assertFalse(File(f.root,"new.apk").exists())
    }
    @Test fun unsupportedCapabilityAndStaleProjectVersionFailBeforePublication() {
        val f=Fixture();val file=File(f.root,"factory.json");file.writeText(JSONObject(file.readText()).put("capabilities",JSONArray(listOf("camera"))).toString())
        assertThrows(Exception::class.java){f.build()};assertFalse(File(f.root,"notes.apk").exists())
        f.spec("org.example.notebook",1)
        assertThrows(Exception::class.java){f.service.build("factory.json","notes.apk",f.scope.version()+1,CancellationToken.cancellable())}
    }
    @Test fun cancelledAndRevokedRunsCannotBuild() {
        val f=Fixture();val token=CancellationToken.cancellable();token.cancel()
        assertThrows(Exception::class.java){f.service.build("factory.json","notes.apk",0,token)}
        f.active=false;assertThrows(Exception::class.java){f.build()};assertFalse(File(f.root,"notes.apk").exists())
    }
    @Test fun signingDenialDoesNotCreateAKeyOrOutputAndKeepsUnsignedApk() {
        val f=Fixture();val build=f.build()
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(1,f.requested);assertEquals(0,f.obtained);assertFalse(File(f.root,"signed.apk").exists());assertTrue(File(f.root,"notes.apk").isFile)
    }
    @Test fun modifiedArtifactOrWrongProjectCannotBorrowBuildReceipt() {
        val f=Fixture();val build=f.build();File(f.root,"notes.apk").appendText("changed")
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(0,f.requested)
        val other=Fixture();File(f.root,"notes.apk").copyTo(File(other.root,"copied.apk"))
        assertThrows(Exception::class.java){other.service.sign("copied.apk",build.getString("sha256"),"signed.apk",other.scope.version(),CancellationToken.cancellable())}
        assertEquals(0,other.requested)
    }
    @Test fun revocationDuringApprovalStopsBeforeKeyCreation() {
        val f=Fixture();val build=f.build();f.decision=ApprovalDecision.APPROVED;f.duringApproval={f.active=false}
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(1,f.requested);assertEquals(0,f.obtained);assertFalse(File(f.root,"signed.apk").exists())
    }
    @Test fun cancelledWhileWaitingForGlobalSigningLockCannotCreateIdentity() {
        val f=Fixture(); val build=f.build(); f.decision=ApprovalDecision.APPROVED
        val lock=FactoryProjectService::class.java.getDeclaredField("SIGN_LOCK").apply{isAccessible=true}.get(null)
        val held=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        val holder=java.util.concurrent.Executors.newSingleThreadExecutor()
        val signer=java.util.concurrent.Executors.newSingleThreadExecutor()
        val token=CancellationToken.cancellable()
        f.duringApproval={holder.submit{ synchronized(lock) {held.countDown(); release.await()} }; assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS))}
        try {
            val future=signer.submit<Boolean>{
                try {f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),token);false}
                catch (_:Exception){true}
            }
            assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS)); token.cancel();release.countDown()
            assertTrue(future.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,f.obtained)
            assertFalse(File(f.root,"signed.apk").exists())
        } finally {release.countDown();holder.shutdownNow();signer.shutdownNow()}
    }
    @Test fun emptyDirectoryTreeCannotBypassTraversalQuota() {
        val f=Fixture()
        for(i in 0..256) File(f.root,"web/dir$i").mkdir()
        assertThrows(Exception::class.java){f.build()};assertFalse(File(f.root,"notes.apk").exists())
    }
    @Test fun revalidationHashRemainsBoundedWhenFileGrows() {
        val f=Fixture();File(f.root,"grow.txt").writeBytes(ByteArray(1025))
        assertThrows(Exception::class.java){FactoryProjectFiles.sha(f.scope,"grow.txt",1024,CancellationToken.cancellable())}
    }
    @Test fun androidLenientJsonCannotBypassDepthGuardUsingCommentsOrSingleQuotes() {
        val f=Fixture()
        val attack="{/*\"*/\"x\":"+"[".repeat(2000)+"0"+"]".repeat(2000)+"/*\"*/}"
        File(f.root,"factory.json").writeText(attack)
        assertThrows(Exception::class.java){f.build()}
        for(value in listOf("{#\"\n\"x\":1}","{'x':1}","{unquoted:1}","{\"x\":1,\"x\":2}")) {
            assertThrows(Exception::class.java){com.jarvys.agent.apkfactory.FactoryJson.objectFrom(value.toByteArray(),16*1024)}
        }
        assertFalse(File(f.root,"notes.apk").exists())
    }
    @Test fun toolSchemaRejectsExtraFieldsAndCannotDelegate() {
        val f=Fixture();val tool=ApkFactoryTools.Tool(f.service)
        assertFalse(tool.canDelegate());assertFalse(tool.execute(mapOf("action" to "inspect","command" to "echo bad"),CancellationToken.cancellable()).success)
        assertTrue(tool.execute(mapOf("action" to "inspect"),CancellationToken.cancellable()).success)
    }
}
