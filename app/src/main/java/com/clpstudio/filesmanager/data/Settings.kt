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

/** How files are shown everywhere: square tiles, rows, or a staggered grid keeping each photo's real shape. */
enum class FileView(val label: String) {
    GRID("Grid"), LIST("List"), LARGE("Real ratio");

    companion object {
        fun parse(value: String?): FileView = entries.firstOrNull { it.name == value } ?: GRID
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
    /** Hide the app's preview in Recents / the app switcher (default on). */
    val hideInRecents: Boolean = true,
    /** App-wide file view (Settings → File view). Screens can change it temporarily. */
    val fileView: FileView = FileView.GRID,
    /** Video keeps playing (sound + media notification) after leaving the player or turning the screen off. */
    val backgroundPlay: Boolean = true,
    /** Home / app switch while a video plays opens picture-in-picture. */
    val autoPip: Boolean = true,
    /** Last type-filter chip per category name (e.g. "PHOTOS" -> "PNG"). */
    val typeFilters: Map<String, String> = emptyMap(),
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
        val hideInRecents = booleanPreferencesKey("hide_in_recents")
        val fileView = stringPreferencesKey("file_view")
        val typeFilters = stringPreferencesKey("type_filters")
        val backgroundPlay = booleanPreferencesKey("background_play")
        val autoPip = booleanPreferencesKey("auto_pip")
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
            hideInRecents = p[Keys.hideInRecents] ?: true,
            fileView = FileView.parse(p[Keys.fileView]),
            typeFilters = decodeFilters(p[Keys.typeFilters]),
            backgroundPlay = p[Keys.backgroundPlay] ?: true,
            autoPip = p[Keys.autoPip] ?: true,
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
    suspend fun setFileView(view: FileView) = context.dataStore.edit { it[Keys.fileView] = view.name }
    suspend fun setTypeFilter(category: String, filter: String) = context.dataStore.edit {
        val m = decodeFilters(it[Keys.typeFilters]).toMutableMap()
        m[category] = filter
        it[Keys.typeFilters] = encodeFilters(m)
    }
    suspend fun setHideInRecents(on: Boolean) = context.dataStore.edit { it[Keys.hideInRecents] = on }
    suspend fun setBackgroundPlay(on: Boolean) = context.dataStore.edit { it[Keys.backgroundPlay] = on }
    suspend fun setAutoPip(on: Boolean) = context.dataStore.edit { it[Keys.autoPip] = on }
    suspend fun setDefaultView(view: DefaultView) = context.dataStore.edit { it[Keys.defaultView] = view.name }
}

internal fun encodeFilters(m: Map<String, String>): String = m.entries.joinToString(";") { "${it.key}=${it.value}" }
internal fun decodeFilters(s: String?): Map<String, String> = s.orEmpty().split(';').mapNotNull {
    val i = it.indexOf('=')
    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
}.toMap()
