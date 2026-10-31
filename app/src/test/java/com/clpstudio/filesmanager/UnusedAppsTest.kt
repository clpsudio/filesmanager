package com.clpstudio.filesmanager

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.UnusedApps
import com.clpstudio.filesmanager.data.UnusedBucket
import com.clpstudio.filesmanager.data.UsageAccess
import com.clpstudio.filesmanager.ui.screens.UnusedAppsContent
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Item 36: usage access detected live; unused apps by 30 days / 2 / 3 / 6 months / 1 year / older. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w800dp-h1280dp-mdpi")
class UnusedAppsTest {
    @get:Rule val compose = createComposeRule()
    private val day = UnusedApps.DAY
    private val now = 1_800_000_000_000L

    @Test fun bucketsByDaysUnused() {
        assertNull(UnusedApps.bucket(now - 5 * day, now))
        assertNull(UnusedApps.bucket(now - 29 * day, now))
        assertEquals(UnusedBucket.D30, UnusedApps.bucket(now - 30 * day, now))
        assertEquals(UnusedBucket.D30, UnusedApps.bucket(now - 59 * day, now))
        assertEquals(UnusedBucket.M2, UnusedApps.bucket(now - 60 * day, now))
        assertEquals(UnusedBucket.M3, UnusedApps.bucket(now - 100 * day, now))
        assertEquals(UnusedBucket.M6, UnusedApps.bucket(now - 200 * day, now))
        assertEquals(UnusedBucket.Y1, UnusedApps.bucket(now - 400 * day, now))
        assertEquals(UnusedBucket.OLDER, UnusedApps.bucket(now - 800 * day, now))
        assertEquals(UnusedBucket.OLDER, UnusedApps.bucket(0L, now))
    }

    @Test fun noUsageRecordFallsBackToInstallOrUpdate() {
        // Never opened, installed 3 years ago, updated 10 days ago: recent activity, not listed.
        assertNull(UnusedApps.classify("a", "A", 0L, now - 1000 * day, now - 10 * day, now))
        // Never opened, installed 200 days ago: 6 months, marked as "no record".
        val b = UnusedApps.classify("b", "B", 0L, now - 200 * day, now - 200 * day, now)!!
        assertEquals(UnusedBucket.M6, b.bucket)
        assertTrue(b.noRecord)
        // A real last use wins over the install date.
        val c = UnusedApps.classify("c", "C", now - 70 * day, now - 900 * day, now - 900 * day, now)!!
        assertEquals(UnusedBucket.M2, c.bucket)
        assertFalse(c.noRecord)
        // Used last week: not an unused app.
        assertNull(UnusedApps.classify("d", "D", now - 7 * day, now - 900 * day, now - 900 * day, now))
    }

    @Test fun usageAccessIsDetectedWhenGranted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val shadow = Shadows.shadowOf(ops)
        shadow.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_IGNORED)
        UsageAccess.watch(app)
        assertFalse(UsageAccess.check(app))
        var heard = false
        UsageAccess.onChange { heard = it }
        // The user turns it on in Settings: the watcher (or the next resume check) flips it, no restart.
        shadow.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        UsageAccess.check(app)
        assertTrue(UsageAccess.granted.value)
        assertTrue(heard)
    }

    @Test fun screenShowsBucketsAndFilters() {
        val apps = listOf(
            UnusedApps.classify("p.one", "Old Game", now - 800 * day, now - 900 * day, now - 900 * day, now)!!,
            UnusedApps.classify("p.two", "Bank", now - 40 * day, now - 900 * day, now - 900 * day, now)!!,
            UnusedApps.classify("p.three", "Shop", now - 100 * day, now - 900 * day, now - 900 * day, now)!!,
        ).sortedByDescending { it.bucket.ordinal }
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                UnusedAppsContent(access = true, apps = apps, onBack = {}, onAllow = {}, onOpenInfo = {}, onUninstall = {})
            }
        }
        compose.onNodeWithText("All 3").assertIsDisplayed()
        compose.onNodeWithText("30 days 1").assertIsDisplayed()
        compose.onNodeWithText("Bank").assertIsDisplayed()
        compose.onNodeWithText("3 months 1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Shop").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Bank")).fetchSemanticsNodes().size)
    }

    @Test fun noAccessShowsAllowCard() {
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                UnusedAppsContent(access = false, apps = null, onBack = {}, onAllow = {}, onOpenInfo = {}, onUninstall = {})
            }
        }
        compose.onNodeWithText("Allow Usage access").assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertIsDisplayed()
    }
}
