package com.jarvys.agent

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentDraftViewModelTest {
    @Test fun emptyAndStaleSessionSubmissionsDoNotStartWork() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val model = AttachmentDraftViewModel(application)
        val store = ViewModelStore().apply { put("attachments", model) }
        try {
            val session = UUID.randomUUID().toString()
            model.switchSession(session)
            assertFalse(model.submit(session, "  ", arrayListOf(), false))
            assertFalse(model.submit("old-session", "hello", arrayListOf(), false))
            assertFalse(model.sending.value)
            assertTrue(model.drafts.value.isEmpty())
        } finally { store.clear() }
    }

    @Test fun cancelledCameraCaptureIsRemovedAndClearedOnLifecycleEnd() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val model = AttachmentDraftViewModel(application)
        val store = ViewModelStore().apply { put("attachments", model) }
        val session = UUID.randomUUID().toString()
        model.switchSession(session)
        val first = model.createCameraFile(session)
        first.writeText("temporary capture")
        val second = model.createCameraFile(session)
        assertFalse("replacing a capture removes its old temporary file", first.exists())
        assertEquals(session, model.cameraSession)
        assertEquals(second, model.cameraFile)
        store.clear()
        assertFalse(second.exists())
        assertNull(model.cameraFile)
        assertNull(model.cameraSession)
        assertTrue(model.drafts.value.isEmpty())
    }
}
