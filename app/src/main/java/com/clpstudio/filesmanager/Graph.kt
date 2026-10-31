package com.clpstudio.filesmanager

import android.content.Context
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.AppsRepository
import com.clpstudio.filesmanager.data.BinRepository
import com.clpstudio.filesmanager.data.HiddenFilter
import com.clpstudio.filesmanager.data.MediaQueries
import com.clpstudio.filesmanager.data.SearchEngine
import com.clpstudio.filesmanager.data.SettingsRepository
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.data.Diagnostics
import com.clpstudio.filesmanager.data.StorageIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The app's single place for shared services. Phase 2 adds the vault (Keystore/StrongBox),
 * the app lock and the archive tools here, next to these.
 */
object Graph {
    lateinit var app: Context
        private set
    // The handler logs an escaped background exception instead of killing the app.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.clpstudio.filesmanager.crash.CrashLog.coroutineHandler)

    lateinit var settingsRepo: SettingsRepository
        private set
    lateinit var settings: StateFlow<AppSettings>
        private set
    val bin by lazy { BinRepository(app) }
    val media by lazy { MediaQueries(app) }
    val search by lazy { SearchEngine(app) }
    val apps by lazy { AppsRepository(app) }
    /** Shared direct walk of storage for documents / APKs / archives. */
    val index by lazy { StorageIndex(app) }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Diagnostics.init(app)
        settingsRepo = SettingsRepository(app)
        settings = settingsRepo.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    }

    fun hiddenFilter(showHidden: Boolean = settings.value.showHidden): HiddenFilter = HiddenFilter(
        showHidden = showHidden,
        privateRoots = StoragePaths.privateRoots(app),
        storageRoots = StoragePaths.volumes(app).map { it.path },
        hiddenRoots = StoragePaths.hiddenRoots(app),
    )
}
