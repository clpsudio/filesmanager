package com.clpstudio.filesmanager

import android.app.Application
import android.content.ClipData
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.SafeStart
import com.clpstudio.filesmanager.viewer.VideoPlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Item 27: Samsung Camera's preview hands us content://secmedia/... which may not be passed on with a grant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecMediaCrashTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val secmedia = Uri.parse("content://secmedia/media/2147494520")

    /** Acts like Samsung's provider: any start that tries to pass a grant on throws. */
    private class StrictContext(base: android.content.Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            if (SafeStart.hasGrant(intent)) throw SecurityException("Provider does not allow granting of Uri permissions")
            started += intent
        }
    }

    @Test fun videoFromAnotherAppIsForwardedWithoutAGrant() {
        val i = VideoPlayerActivity.uriIntent(app, secmedia, "Camera video")
        assertEquals(secmedia, i.data)
        assertFalse(SafeStart.hasGrant(i))
        assertNull(i.clipData)
        val ctx = StrictContext(app)
        assertTrue(SafeStart.start(ctx, i))
        assertEquals(1, ctx.started.size)
    }

    @Test fun aRefusedGrantIsRetriedWithoutItAndNeverCrashes() {
        val ctx = StrictContext(app)
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, secmedia).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share",
        ).apply { clipData = ClipData.newRawUri("", secmedia); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        assertTrue(SafeStart.start(ctx, share)) // first try throws, the retry without grants goes through
        val sent = ctx.started.single()
        assertFalse(SafeStart.hasGrant(sent))
        @Suppress("DEPRECATION")
        val inner = sent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(0, inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun nothingCanStartMeansAMessageNotACrash() {
        val ctx = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) = throw SecurityException("no")
        }
        assertFalse(SafeStart.start(ctx, Intent(Intent.ACTION_VIEW, secmedia).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
    }
}
