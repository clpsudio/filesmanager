package com.clpstudio.filesmanager

import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.DefaultView
import com.clpstudio.filesmanager.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DefaultViewSettingTest {
    @Test fun defaultsToAllFiles() {
        assertEquals(DefaultView.ALL_FILES, AppSettings().defaultView)
        assertEquals(DefaultView.ALL_FILES, DefaultView.parse(null))
        assertEquals(DefaultView.ALL_FILES, DefaultView.parse("something-else"))
        assertEquals(DefaultView.FOLDERS, DefaultView.parse("FOLDERS"))
    }

    @Test fun savedChoiceIsReadBack() = runBlocking {
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext())
        assertEquals(DefaultView.ALL_FILES, repo.settings.first().defaultView)
        repo.setDefaultView(DefaultView.FOLDERS)
        assertEquals(DefaultView.FOLDERS, repo.settings.first().defaultView)
        repo.setDefaultView(DefaultView.ALL_FILES)
        assertEquals(DefaultView.ALL_FILES, repo.settings.first().defaultView)
    }
}
