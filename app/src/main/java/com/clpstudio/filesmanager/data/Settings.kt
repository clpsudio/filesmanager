package com.clpstudio.filesmanager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How every category (Recent, Photos, Videos, ...) opens. Default: one list of all files. */
enum class DefaultView {
    ALL_FILES, FOLDERS;

    companion object {
        /** Unknown or missing values fall back to [ALL_FILES]. */
        fun parse(value: String?): DefaultView = entries.firstOrNull { it.name == value } ?: ALL_FILES
    }
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Liquid glass look (default on). Off = flat Material 3. */
    val liquidGlass: Boolean = true,
    /** Show dot-files, dot-folders and folders with .nomedia (default off). Never shows vault items. */
    val showHidden: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    val gridView: Boolean = false,
    /** Categories open on "All files" unless the user picks "Folders" in Settings. */
    val defaultView: DefaultView = DefaultView.ALL_FILES,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val glass = booleanPreferencesKey("liquid_glass")
        val hidden = booleanPreferencesKey("show_hidden")
        val sortBy = stringPreferencesKey("sort_by")
        val sortAsc = booleanPreferencesKey("sort_asc")
        val grid = booleanPreferencesKey("grid_view")
        val defaultView = stringPreferencesKey("default_view")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            liquidGlass = p[Keys.glass] ?: true,
            showHidden = p[Keys.hidden] ?: false,
            sortBy = p[Keys.sortBy]?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME,
            sortAscending = p[Keys.sortAsc] ?: true,
            gridView = p[Keys.grid] ?: false,
            defaultView = DefaultView.parse(p[Keys.defaultView]),
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setLiquidGlass(on: Boolean) = context.dataStore.edit { it[Keys.glass] = on }
    suspend fun setShowHidden(on: Boolean) = context.dataStore.edit { it[Keys.hidden] = on }
    suspend fun setSort(by: SortBy, ascending: Boolean) = context.dataStore.edit {
        it[Keys.sortBy] = by.name
        it[Keys.sortAsc] = ascending
    }
    suspend fun setGrid(on: Boolean) = context.dataStore.edit { it[Keys.grid] = on }
    suspend fun setDefaultView(view: DefaultView) = context.dataStore.edit { it[Keys.defaultView] = view.name }
}
