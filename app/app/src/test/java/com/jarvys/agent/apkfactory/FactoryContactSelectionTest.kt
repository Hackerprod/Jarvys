package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic installed system identities and exact-row provider only. Never accesses real contacts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 32, 35], application = FactoryContactFixtureApplication::class)
class FactoryContactSelectionTest {
    private val context: FactoryContactFixtureApplication get() = ApplicationProvider.getApplicationContext()
    private val selection get() = FactoryContactSelection(context)
    private lateinit var fixture: FactoryContactTestPackages.Fixture
    private val executor = Executors.newSingleThreadExecutor()
    private fun <T> io(block: () -> T): T = executor.submit(Callable(block)).get(5, TimeUnit.SECONDS)
    private fun denied(block: () -> Unit) = assertTrue("Expected exact-row contact rejection", runCatching(block).isFailure)
    private fun read(kind: String = "phone", result: Intent = FactoryContactTestPackages.result(), target: FactoryContactSelection.Target = selection.discover(kind), signal: CancellationSignal = CancellationSignal(), active: () -> Unit = {}) = io { selection.read(target, kind, result, signal, active) }
    @Before fun setup() { context.resetContactPermissions(); fixture = FactoryContactTestPackages.install(context) }
    @After fun cleanup() { executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); context.resetContactPermissions() }
    @Test fun exactActionPickPhoneAndEmailHaveNoGenericContactOrChooserAuthority() {
        for ((kind, type) in listOf("phone" to ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE, "email" to ContactsContract.CommonDataKinds.Email.CONTENT_TYPE)) {
            val target = selection.discover(kind); val intent = selection.verifiedIntent(target, kind)
            assertEquals(Intent.ACTION_PICK, intent.action); assertEquals(type, intent.type); assertEquals(fixture.picker.component, intent.component)
            assertNull(intent.data); assertNull(intent.clipData); assertNull(intent.selector); assertNull(intent.extras); assertNull(intent.categories)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals(fixture.picker.uid, target.picker.uid); assertEquals(7L, target.picker.version); assertEquals(99L, target.picker.updated)
            assertEquals(1, target.picker.certificates.size); assertEquals(64, target.picker.certificates.single().length)
        }
        for (kind in listOf("", "Phone", "all", "contact")) denied { selection.discover(kind) }
        assertTrue(fixture.provider.queries.isEmpty())
    }
    @Test fun onlyCanonicalPositiveLongDataRowUrisAreAccepted() {
        for (id in listOf("1", "42", Long.MAX_VALUE.toString())) {
            for (flags in listOf(0, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                val value = FactoryContactTestPackages.result("content://com.android.contacts/data/$id", flags)
                assertEquals(value.data, FactoryContactSelection.selectedUri(value))
            }
        }
        val invalid = listOf("content://com.android.contacts/data", "content://com.android.contacts/data/", "content://com.android.contacts/data/0", "content://com.android.contacts/data/-1",
            "content://com.android.contacts/data/01", "content://com.android.contacts/data/+1", "content://com.android.contacts/data/9223372036854775808", "content://com.android.contacts/data/11111111111111111111",
            "content://com.android.contacts/data/42/", "content://com.android.contacts/data/42/extra", "content://com.android.contacts/data/42?limit=1", "content://com.android.contacts/data/42#fragment",
            "content://com.android.contacts/data/%34%32", "content://com.android.contacts/data/４２", "content://com.android.contacts/contacts/42", "content://com.android.contacts/contacts/lookup/key/42",
            "content://com.android.contacts/raw_contacts/42", "content://com.android.contacts/data/../42", "content://com.android.contacts:80/data/42", "content://0@com.android.contacts/data/42",
            "content://10@com.android.contacts/data/42", "content://com.android.contacts.evil/data/42", "CONTENT://com.android.contacts/data/42", "file:///data/42", "https://com.android.contacts/data/42")
        for (uri in invalid) denied { FactoryContactSelection.selectedUri(FactoryContactTestPackages.result(uri)) }
        denied { FactoryContactSelection.selectedUri(Intent()) }
    }
    @Test fun resultCannotSmuggleRoutingExtrasAdditionalGrantsOrClipPayloads() {
        val valid = FactoryContactTestPackages.result()
        val invalid = mutableListOf(Intent(valid).putExtra("name", "Synthetic contact"), Intent(valid).setAction(Intent.ACTION_PICK),
            Intent(valid).setComponent(ComponentName("example.other", "example.other.Picker")), Intent(valid).setPackage("example.other"), Intent(valid).addCategory(Intent.CATEGORY_DEFAULT),
            Intent(valid).apply { selector = Intent("synthetic") }, Intent(valid).apply { sourceBounds = Rect(0, 0, 1, 1) },
            Intent(valid).setDataAndType(valid.data, "vnd.android.cursor.dir/contact"),
            Intent(valid).apply { clipData = ClipData.newPlainText("fixture", "secret") },
            Intent(valid).apply { clipData = ClipData.newIntent("fixture", Intent("synthetic")) },
            Intent(valid).apply { clipData = ClipData.newRawUri("fixture", Uri.parse("content://com.android.contacts/data/43")) },
            Intent(valid).apply { clipData = ClipData.newRawUri("fixture", data).apply { addItem(ClipData.Item(data)) } })
        for (flags in listOf(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, Intent.FLAG_ACTIVITY_NEW_TASK)) invalid += Intent(valid).addFlags(flags)
        if (Build.VERSION.SDK_INT >= 29) invalid += Intent(valid).apply { identifier = "hidden" }
        invalid.forEach { denied { FactoryContactSelection.selectedUri(it) } }
        assertEquals(valid.data, FactoryContactSelection.selectedUri(Intent(valid).apply { clipData = ClipData.newRawUri("fixture", data) }))
    }
    @Test fun missingOrdinaryThirdPartyDisabledPrivateAndPermissionGuardedPickersFailClosed() {
        val activity = fixture.picker.resolve.activityInfo
        activity.applicationInfo.flags = 0; denied { selection.discover("phone") }; activity.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM
        activity.enabled = false; denied { selection.discover("phone") }; activity.enabled = true
        activity.exported = false; denied { selection.discover("phone") }; activity.exported = true
        activity.permission = "example.PRIVATE"; denied { selection.discover("phone") }; activity.permission = null
        activity.applicationInfo.enabled = false; denied { selection.discover("phone") }; activity.applicationInfo.enabled = true
        shadowOf(context.packageManager).setResolveInfosForIntent(FactoryContactSelection.pick("phone"), emptyList()); denied { selection.discover("phone") }
    }
    @Test fun ambiguousSystemPickerAndExcessiveResultsFailClosedButDuplicatesAreOneIdentity() {
        val pm = shadowOf(context.packageManager); val other = FactoryContactTestPackages.installPicker(context, "example.other.contacts", 20005)
        pm.setResolveInfosForIntent(FactoryContactSelection.pick("phone"), listOf(fixture.picker.resolve, other.resolve)); denied { selection.discover("phone") }
        pm.setResolveInfosForIntent(FactoryContactSelection.pick("phone"), List(33) { fixture.picker.resolve }); denied { selection.discover("phone") }
        pm.setResolveInfosForIntent(FactoryContactSelection.pick("phone"), listOf(fixture.picker.resolve, fixture.picker.resolve))
        assertEquals(fixture.picker.component, selection.discover("phone").picker.component)
    }
    @Test fun updatedSystemPickerWorksButSuspendedOtherProfileAndHostOwnedIdentityDoNot() {
        val app = fixture.picker.resolve.activityInfo.applicationInfo
        app.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP; assertNotNull(selection.discover("phone"))
        app.flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_SUSPENDED; denied { selection.discover("phone") }; app.flags = ApplicationInfo.FLAG_SYSTEM
        val originalUid = app.uid; app.uid = 120003; fixture.picker.installed.applicationInfo!!.uid = app.uid; denied { selection.discover("phone") }
        app.uid = originalUid; fixture.picker.installed.applicationInfo!!.uid = originalUid
        app.uid = context.applicationInfo.uid; denied { selection.discover("phone") }
    }
    @Test fun missingUnsignedAndExcessivelySignedPickersCannotAcquireIdentity() {
        fixture.picker.signatures(emptyArray()); denied { selection.discover("phone") }
        fixture.picker.signatures(Array(5) { Signature(byteArrayOf(it.toByte(), 1)) }); denied { selection.discover("phone") }
        fixture.picker.signatures(arrayOf(Signature(FactoryContactTestPackages.CERT)))
        fixture.picker.resolve.activityInfo.applicationInfo.packageName = "example.wrong.contacts"; denied { selection.discover("phone") }
    }
    @Test fun providerMustBeEnabledExportedSystemAndInSameUser() {
        val provider = fixture.providerInfo; val app = provider.applicationInfo
        provider.enabled = false; denied { selection.discover("phone") }; provider.enabled = true
        provider.exported = false; denied { selection.discover("phone") }; provider.exported = true
        app.flags = 0; denied { selection.discover("phone") }; app.flags = ApplicationInfo.FLAG_SYSTEM
        app.enabled = false; denied { selection.discover("phone") }; app.enabled = true
        app.flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_SUSPENDED; denied { selection.discover("phone") }; app.flags = ApplicationInfo.FLAG_SYSTEM
        app.uid = 120004; fixture.providerPackage.installed.applicationInfo!!.uid = app.uid; denied { selection.discover("phone") }
    }
    @Test fun legitimateSharedSystemProviderIsPinnedWithoutRequiringUriGrantDeclaration() {
        fixture.providerInfo.grantUriPermissions = false
        fixture.providerPackage.installed.sharedUserId = "android.uid.shared"
        shadowOf(context.packageManager).setPackagesForUid(fixture.providerPackage.uid, fixture.providerPackage.packageName, "example.system.peer")
        val target = selection.discover("phone"); assertEquals(fixture.providerPackage.component, target.provider.component)
    }
    @Test fun pickerAndProviderCertificateVersionUpdateAndUidAreRevalidatedBeforeDispatch() {
        val target = selection.discover("phone")
        for (party in listOf(fixture.picker, fixture.providerPackage)) {
            party.signatures(arrayOf(Signature(byteArrayOf(9, 8, 7)))); denied { selection.verifiedIntent(target, "phone") }
            party.signatures(arrayOf(Signature(FactoryContactTestPackages.CERT))); party.installed.versionCode = 8
            denied { selection.verifiedIntent(target, "phone") }; party.installed.versionCode = 7
            party.installed.lastUpdateTime = 100; denied { selection.verifiedIntent(target, "phone") }; party.installed.lastUpdateTime = 99
            val app = if (party === fixture.picker) party.resolve.activityInfo.applicationInfo else fixture.providerInfo.applicationInfo
            app.uid = party.uid + 1; party.installed.applicationInfo!!.uid = app.uid; denied { selection.verifiedIntent(target, "phone") }
            app.uid = party.uid; party.installed.applicationInfo!!.uid = party.uid
        }
        assertNotNull(selection.verifiedIntent(target, "phone"))
    }
    @Test fun explicitScopedGrantReadsExactlyOneFixedProjectionWithoutBulkQueryOrNormalization() {
        context.uriReadGranted = true
        assertEquals(FactoryContactCoordinatorTest.PHONE, read())
        assertEquals(1, fixture.provider.queries.size)
        val query = fixture.provider.queries.single()
        assertEquals(Uri.parse(FactoryContactCoordinatorTest.ROW_URI), query.uri)
        assertEquals(listOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1), query.projection)
        assertNull(query.selection); assertNull(query.args); assertNull(query.sort)
        assertTrue(fixture.provider.cursors.single().isClosed)
        assertTrue(context.uriChecks.all { it == Uri.parse(FactoryContactCoordinatorTest.ROW_URI) })
    }
    @Test fun alreadyGrantedReadContactsAllowsOnlyExactSelectedRowWithNoReturnedGrantFlag() {
        context.contactsReadGranted = true
        fixture.provider.rows = listOf(arrayOf(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, FactoryContactCoordinatorTest.EMAIL))
        assertEquals(FactoryContactCoordinatorTest.EMAIL, read("email", FactoryContactTestPackages.result(flags = 0)))
        assertEquals(1, fixture.provider.queries.size)
        assertEquals(Uri.parse(FactoryContactCoordinatorTest.ROW_URI), fixture.provider.queries.single().uri)
        assertEquals(listOf("mimetype", "data1"), fixture.provider.queries.single().projection)
    }
    @Test fun returnedReadFlagWithoutActualGrantOrPermissionCannotQuery() {
        denied { read() }; denied { read(result = FactoryContactTestPackages.result(flags = 0)) }
        assertTrue(fixture.provider.queries.isEmpty())
    }
    @Test fun ambientReadContactsNeverOverridesMissingOwnedCallbackAuthority() {
        context.contactsReadGranted = true
        denied { read(result = FactoryContactTestPackages.result(flags = 0), active = { error("No authenticated picker callback") }) }
        assertTrue(fixture.provider.queries.isEmpty())
    }
    @Test fun malformedUriOrWrongResultMimeIsRejectedBeforeAnyProviderQuery() {
        context.contactsReadGranted = true
        denied { read(result = FactoryContactTestPackages.result("content://com.android.contacts/data")) }
        val result = FactoryContactTestPackages.result().apply { setDataAndType(data, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) }
        denied { read(result = result) }; assertTrue(fixture.provider.queries.isEmpty())
    }
    @Test fun readerRejectsMainThreadAndCancellationBeforeQuery() {
        context.uriReadGranted = true; val target = selection.discover("phone")
        denied { selection.read(target, "phone", FactoryContactTestPackages.result(), CancellationSignal()) {} }
        denied { read(target = target, signal = CancellationSignal().apply { cancel() }) }; assertTrue(fixture.provider.queries.isEmpty())
    }
    @Test fun changedPickerOrProviderBeforeReadCannotQueryEvenWithAmbientPermission() {
        context.contactsReadGranted = true; val target = selection.discover("phone")
        fixture.picker.installed.lastUpdateTime = 100; denied { read(target = target) }; fixture.picker.installed.lastUpdateTime = 99
        fixture.providerPackage.installed.versionCode = 8; denied { read(target = target) }; assertTrue(fixture.provider.queries.isEmpty())
    }
    @Test fun exactTwoStringColumnsAndRequestedMimeAreMandatory() {
        context.uriReadGranted = true
        for (columns in listOf(arrayOf("data1", "mimetype"), arrayOf("mimetype", "data1", "display_name"), arrayOf("mimetype"))) {
            fixture.provider.columns = columns; fixture.provider.rows = listOf(Array<Any?>(columns.size) { "fixture" })
            denied { read() }; assertTrue(fixture.provider.cursors.last().isClosed)
        }
        fixture.provider.columns = arrayOf("mimetype", "data1")
        for (row in listOf(arrayOf<Any?>(null, "fixture"), arrayOf<Any?>(1, "fixture"), arrayOf<Any?>(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, "fixture"),
            arrayOf<Any?>(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, null), arrayOf<Any?>(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, 123),
            arrayOf<Any?>(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, byteArrayOf(1, 2)))) {
            fixture.provider.rows = listOf(row); denied { read() }; assertTrue(fixture.provider.cursors.last().isClosed)
        }
    }
    @Test fun emptyMultipleNullAndFailingCursorsNeverReturnAValue() {
        context.uriReadGranted = true
        fixture.provider.rows = emptyList(); denied { read() }
        val row = arrayOf<Any?>(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "fixture")
        fixture.provider.rows = listOf(row, row); denied { read() }
        fixture.provider.returnNull = true; denied { read() }; fixture.provider.returnNull = false
        fixture.provider.failure = SecurityException("Synthetic provider denied access"); denied { read() }
        assertTrue(fixture.provider.cursors.all { it.isClosed })
    }
    @Test fun invalidOverlongControlOrMalformedUnicodeDataNeverLeavesReader() {
        context.uriReadGranted = true
        for (value in listOf("", " ", "x".repeat(257), "one\ntwo", "one\u202Etwo", "one\u0000two", "\uD800")) {
            fixture.provider.rows = listOf(arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, value))
            denied { read() }; assertTrue(fixture.provider.cursors.last().isClosed)
        }
    }
    @Test fun cancellationRevocationAndPermissionLossDuringReadDiscardTheDatum() {
        context.uriReadGranted = true; val signal = CancellationSignal()
        fixture.provider.onQuery = { signal.cancel() }; denied { read(signal = signal) }; assertTrue(fixture.provider.cursors.last().isClosed)
        var active = true; fixture.provider.onQuery = { active = false }
        denied { read(active = { check(active) }) }; assertTrue(fixture.provider.cursors.last().isClosed)
        fixture.provider.onQuery = { context.uriReadGranted = false }; denied { read() }; assertTrue(fixture.provider.cursors.last().isClosed)
    }
    @Test fun providerIdentityChangedWhileReadingDiscardsExactDatum() {
        context.uriReadGranted = true
        fixture.provider.onQuery = { fixture.providerPackage.installed.lastUpdateTime = 100 }
        denied { read() }; assertEquals(1, fixture.provider.queries.size); assertTrue(fixture.provider.cursors.single().isClosed)
    }
}

/** Only permission answers are faked; host picker/proof/callback/reader logic remains production. */
class FactoryContactFixtureApplication : Application() {
    @Volatile var uriReadGranted = false
    @Volatile var contactsReadGranted = false
    val uriChecks = mutableListOf<Uri>()
    override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int {
        synchronized(uriChecks) { uriChecks += uri }
        return if (uriReadGranted && modeFlags == Intent.FLAG_GRANT_READ_URI_PERMISSION) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }
    override fun checkSelfPermission(permission: String): Int = if (permission == android.Manifest.permission.READ_CONTACTS) {
        if (contactsReadGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    } else super.checkSelfPermission(permission)
    fun resetContactPermissions() { uriReadGranted = false; contactsReadGranted = false; uriChecks.clear() }
}

internal object FactoryContactTestPackages {
    val CERT = byteArrayOf(0x30, 0x04, 0x01, 0x02, 0x03, 0x04)
    class Party(val context: Context, val packageName: String, val uid: Int, val className: String, val resolve: ResolveInfo) {
        val component get() = ComponentName(packageName, className)
        val installed: PackageInfo get() = shadowOf(context.packageManager).getInternalMutablePackageInfo(packageName)
        fun signatures(values: Array<Signature>) {
            installed.signatures = values
            if (Build.VERSION.SDK_INT >= 28) installed.signingInfo = ReflectionHelpers.newInstance(SigningInfo::class.java).also { shadowOf(it).setSignatures(values) }
        }
    }
    class Fixture(val picker: Party, val providerPackage: Party, val provider: FactorySyntheticContactProvider) {
        val providerInfo: ProviderInfo get() = providerPackage.installed.providers!!.single()
    }
    fun result(uri: String = FactoryContactCoordinatorTest.ROW_URI, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) = Intent().setData(Uri.parse(uri)).addFlags(flags)
    fun installPicker(context: Context, packageName: String = "example.system.contacts", uid: Int = 20003): Party {
        val app = ApplicationInfo().apply { this.packageName = packageName; this.uid = uid; enabled = true; flags = ApplicationInfo.FLAG_SYSTEM }
        val activity = ActivityInfo().apply { this.packageName = packageName; name = "$packageName.Picker"; applicationInfo = app; enabled = true; exported = true }
        val info = PackageInfo().apply { this.packageName = packageName; applicationInfo = app; activities = arrayOf(activity); versionCode = 7; lastUpdateTime = 99 }
        val pm = shadowOf(context.packageManager); pm.installPackage(info); pm.setPackagesForUid(uid, packageName)
        val resolve = ResolveInfo().apply { activityInfo = activity; isDefault = true; filter = IntentFilter(Intent.ACTION_PICK).apply { addCategory(Intent.CATEGORY_DEFAULT) } }
        val party = Party(context, packageName, uid, activity.name, resolve); party.signatures(arrayOf(Signature(CERT)))
        for (kind in listOf("phone", "email")) pm.addResolveInfoForIntentNoDefaults(FactoryContactSelection.pick(kind), resolve)
        return party
    }
    fun install(context: Context): Fixture {
        val picker = installPicker(context); val packageName = "example.system.contactsprovider"; val uid = 20004
        val app = ApplicationInfo().apply { this.packageName = packageName; this.uid = uid; enabled = true; flags = ApplicationInfo.FLAG_SYSTEM }
        val providerInfo = ProviderInfo().apply { this.packageName = packageName; name = "$packageName.Provider"; authority = "com.android.contacts"; applicationInfo = app; enabled = true; exported = true; grantUriPermissions = true }
        val info = PackageInfo().apply { this.packageName = packageName; applicationInfo = app; providers = arrayOf(providerInfo); versionCode = 7; lastUpdateTime = 99 }
        val pm = shadowOf(context.packageManager); pm.installPackage(info); pm.setPackagesForUid(uid, packageName)
        val party = Party(context, packageName, uid, providerInfo.name, ResolveInfo()); party.signatures(arrayOf(Signature(CERT)))
        val provider = FactorySyntheticContactProvider(); provider.attachInfo(context, providerInfo)
        ShadowContentResolver.registerProviderInternal(providerInfo.authority, provider)
        return Fixture(picker, party, provider)
    }
}

internal class FactorySyntheticContactProvider : ContentProvider() {
    data class Query(val uri: Uri, val projection: List<String>?, val selection: String?, val args: List<String>?, val sort: String?)
    val queries = mutableListOf<Query>()
    val cursors = mutableListOf<MatrixCursor>()
    var columns = arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1)
    var rows: List<Array<Any?>> = listOf(arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, FactoryContactCoordinatorTest.PHONE))
    var returnNull = false
    var failure: RuntimeException? = null
    var onQuery: (() -> Unit)? = null
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        queries += Query(uri, projection?.toList(), selection, selectionArgs?.toList(), sortOrder)
        failure?.let { throw it }; if (returnNull) return null
        val cursor = MatrixCursor(columns).also { result -> rows.forEach { result.addRow(it) }; cursors += result }
        onQuery?.invoke(); return cursor
    }
    override fun getType(uri: Uri) = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Synthetic contact writes are forbidden")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Synthetic contact writes are forbidden")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Synthetic contact writes are forbidden")
}
