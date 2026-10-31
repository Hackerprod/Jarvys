package com.jarvys.agent.connectors

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafCreateResultTest {
    @Test fun cancelledResultNeverReturnsEvenSuppliedUri() {
        val uri = Uri.parse("content://test.provider/document/chosen")
        assertNull(acceptedSafCreateResult(ActivityResult(Activity.RESULT_CANCELED, Intent().setData(uri))))
        assertNull(acceptedSafCreateResult(ActivityResult(7, Intent().setData(uri))))
    }
    @Test fun successfulResultRetainsExactSelectedUriAndNullCancellation() {
        val uri = Uri.parse("content://test.provider/document/chosen")
        assertEquals(uri, acceptedSafCreateResult(ActivityResult(Activity.RESULT_OK, Intent().setData(uri))))
        assertNull(acceptedSafCreateResult(ActivityResult(Activity.RESULT_OK, null)))
    }
}
