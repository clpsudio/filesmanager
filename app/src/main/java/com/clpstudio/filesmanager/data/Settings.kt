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
    /** Settings → Video player → Loop videos (default: loop forever). */
    val loopMode: LoopMode = LoopMode.FOREVER,
    /** Extra plays for [LoopMode.TIMES] (1–10). */
    val loopTimes: Int = LoopPolicy.DEFAULT_TIMES,
    /** Seconds a double-tap on the left / right side skips in the video player. */
    val doubleTapSkipSeconds: Int = DoubleTapSkip.DEFAULT,
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
        val doubleTapSkip = androidx.datastore.preferences.core.intPreferencesKey("double_tap_skip")
        val loopMode = stringPreferencesKey("loop_mode")
        val loopTimes = androidx.datastore.preferences.core.intPreferencesKey("loop_times")
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
            doubleTapSkipSeconds = DoubleTapSkip.clamp(p[Keys.doubleTapSkip] ?: DoubleTapSkip.DEFAULT),
            loopMode = LoopMode.parse(p[Keys.loopMode]),
            loopTimes = LoopPolicy.clampTimes(p[Keys.loopTimes] ?: LoopPolicy.DEFAULT_TIMES),
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
    suspend fun setLoop(mode: LoopMode, times: Int? = null) = context.dataStore.edit {
        it[Keys.loopMode] = mode.name
        if (times != null) it[Keys.loopTimes] = LoopPolicy.clampTimes(times)
    }
    suspend fun setDoubleTapSkip(seconds: Int) = context.dataStore.edit { it[Keys.doubleTapSkip] = DoubleTapSkip.clamp(seconds) }
    suspend fun setDefaultView(view: DefaultView) = context.dataStore.edit { it[Keys.defaultView] = view.name }
}

internal fun encodeFilters(m: Map<String, String>): String = m.entries.joinToString(";") { "${it.key}=${it.value}" }
internal fun decodeFilters(s: String?): Map<String, String> = s.orEmpty().split(';').mapNotNull {
    val i = it.indexOf('=')
    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
}.toMap()

/** Settings → Video player → Double-tap skip: 5 / 10 / 15 / 20 / 30 s or a custom 1–60 s. Default 10 s. */
object DoubleTapSkip {
    const val DEFAULT = 10
    const val MIN = 1
    const val MAX = 60
    val PRESETS = listOf(5, 10, 15, 20, 30)

    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN, MAX)
    fun isPreset(seconds: Int): Boolean = seconds in PRESETS
    fun ms(seconds: Int): Long = clamp(seconds) * 1000L
    /** "10 s" */
    fun label(seconds: Int): String = "${clamp(seconds)} s"
    /** Parses what the user typed in "Custom"; null when it isn't a whole number in range. */
    fun parse(text: String): Int? = text.trim().removeSuffix("s").trim().toIntOrNull()?.takeIf { it in MIN..MAX }
}

/** Settings → Video player → Loop videos. */
enum class LoopMode(val label: String) {
    OFF("Off"), FOREVER("Loop forever"), TIMES("Loop a number of times");

    companion object {
        fun parse(value: String?): LoopMode = entries.firstOrNull { it.name == value } ?: FOREVER
    }
}

/**
 * How the player loops the current video. Forever = repeat with no limit. Times = play it N more
 * times after the first, then go on like Loop off (next video in the list, or stop at the last).
 */
object LoopPolicy {
    const val DEFAULT_TIMES = 3
    const val MIN_TIMES = 1
    const val MAX_TIMES = 10

    fun clampTimes(n: Int): Int = n.coerceIn(MIN_TIMES, MAX_TIMES)

    /** Repeat the current item at the start of a video? (Off: no.) */
    fun repeatsAtStart(mode: LoopMode, times: Int): Boolean = when (mode) {
        LoopMode.OFF -> false
        LoopMode.FOREVER -> true
        LoopMode.TIMES -> clampTimes(times) > 0
    }

    /**
     * Called after the video repeated once more ([repeatsDone] counts repeats so far, this one included).
     * Returns whether it should keep repeating after this play.
     */
    fun keepRepeating(mode: LoopMode, times: Int, repeatsDone: Int): Boolean = when (mode) {
        LoopMode.OFF -> false
        LoopMode.FOREVER -> true
        LoopMode.TIMES -> repeatsDone < clampTimes(times)
    }

    /** The in-player Loop button: off ↔ the last chosen looping mode (forever when it was off). */
    fun toggled(current: LoopMode, lastOn: LoopMode): LoopMode =
        if (current != LoopMode.OFF) LoopMode.OFF else if (lastOn == LoopMode.OFF) LoopMode.FOREVER else lastOn

    fun label(mode: LoopMode, times: Int): String = when (mode) {
        LoopMode.OFF -> "Off"
        LoopMode.FOREVER -> "Forever"
        LoopMode.TIMES -> if (clampTimes(times) == 1) "1 more time" else "${clampTimes(times)} more times"
    }
}
