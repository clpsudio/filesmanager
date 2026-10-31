package com.clpstudio.filesmanager.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.playback.rememberPlayerUi
import com.clpstudio.filesmanager.ui.components.ProgressDialog
import com.clpstudio.filesmanager.ui.screens.AppsScreen
import com.clpstudio.filesmanager.ui.screens.BinScreen
import com.clpstudio.filesmanager.ui.screens.BrowserScreen
import com.clpstudio.filesmanager.ui.screens.CategoryFolderScreen
import com.clpstudio.filesmanager.ui.screens.CategoryScreen
import com.clpstudio.filesmanager.ui.screens.HomeScreen
import com.clpstudio.filesmanager.ui.screens.HomeTile
import com.clpstudio.filesmanager.ui.screens.PermissionScreen
import com.clpstudio.filesmanager.ui.screens.SearchScreen
import com.clpstudio.filesmanager.ui.screens.SettingsScreen
import com.clpstudio.filesmanager.ui.screens.StoragePermission
import com.clpstudio.filesmanager.ui.screens.typeFilter
import com.clpstudio.filesmanager.viewer.FileInfoScreen
import com.clpstudio.filesmanager.viewer.FontPreviewScreen
import com.clpstudio.filesmanager.viewer.HexViewerScreen
import com.clpstudio.filesmanager.data.ExternalFiles
import com.clpstudio.filesmanager.viewer.ImageViewerScreen
import com.clpstudio.filesmanager.viewer.MusicPlayerScreen
import com.clpstudio.filesmanager.viewer.PdfViewerScreen
import com.clpstudio.filesmanager.viewer.TextViewerScreen
import com.clpstudio.filesmanager.viewer.VideoPlayerActivity
import com.clpstudio.filesmanager.viewer.ZipViewerScreen
import java.io.File

private val springOffset = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private val springFloat = spring<Float>(stiffness = Spring.StiffnessMediumLow)

@Composable
fun AppRoot(nowPlayingTick: Int = 0, browsePath: String? = null, onBrowseHandled: () -> Unit = {}) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StoragePermission.granted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = StoragePermission.granted(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(granted) { if (granted) AppState.refresh() }
    if (!granted) {
        PermissionScreen(onChecked = { granted = StoragePermission.granted(context) })
        return
    }

    val nav = rememberNavController()
    val opener = remember(nav) { Opener(context, nav) }
    val player = rememberPlayerUi()
    LaunchedEffect(browsePath) {
        if (browsePath != null) {
            nav.navigate(Routes.browse(browsePath))
            onBrowseHandled()
        }
    }
    LaunchedEffect(nowPlayingTick) {
        if (nowPlayingTick > 0) {
            if (player.isVideo) context.startActivity(Intent(context, VideoPlayerActivity::class.java))
            else nav.navigate(Routes.MUSIC) { launchSingleTop = true }
        }
    }
    val back: () -> Unit = { nav.popBackStack() }
    val toPaste: () -> Unit = { nav.navigate(Routes.browse()) }

    // Audio mini player above every list screen (Home draws its own; hidden in viewers and the full player).
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val showMini = player.hasMedia && !player.isVideo && route.isNotEmpty() && miniPlayerRoute(route)
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMiniPlayerInset provides if (showMini) com.clpstudio.filesmanager.ui.screens.MiniPlayerHeight else 0.dp,
        com.clpstudio.filesmanager.ui.components.LocalDateGrouping provides com.clpstudio.filesmanager.Graph.settings.collectAsState().value.dateGrouping,
    ) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally(springOffset) { it / 5 } + fadeIn(springFloat) },
        exitTransition = { scaleOut(springFloat, targetScale = 0.96f) + fadeOut(springFloat) },
        popEnterTransition = { scaleIn(springFloat, initialScale = 0.96f) + fadeIn(springFloat) },
        popExitTransition = { slideOutHorizontally(springOffset) { it / 5 } + fadeOut(springFloat) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onTile = { tile ->
                    when (tile) {
                        HomeTile.RECENT -> nav.navigate(Routes.category(Category.RECENT))
                        HomeTile.PHOTOS -> nav.navigate(Routes.category(Category.PHOTOS))
                        HomeTile.MEDIA -> nav.navigate(Routes.category(Category.MEDIA))
                        HomeTile.FAVORITES -> nav.navigate(Routes.FAVORITES)
                        HomeTile.VIDEOS -> nav.navigate(Routes.category(Category.VIDEOS))
                        HomeTile.AUDIO -> nav.navigate(Routes.category(Category.AUDIO))
                        HomeTile.DOCUMENTS -> nav.navigate(Routes.category(Category.DOCUMENTS))
                        HomeTile.DOWNLOADS -> nav.navigate(Routes.category(Category.DOWNLOADS))
                        HomeTile.APPS -> nav.navigate(Routes.APPS)
                        HomeTile.BIN -> nav.navigate(Routes.BIN)
                        HomeTile.HIDDEN -> nav.navigate(Routes.HIDDEN)
                        // Our own storage screen: never the system Files / Settings app (it may be removed or disabled).
                        HomeTile.INTERNAL -> nav.navigate(Routes.STORAGE)
                        HomeTile.VAULT -> nav.navigate(Routes.VAULT)
                    }
                },
                onSearch = { nav.navigate(Routes.search()) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onBrowse = { nav.navigate(Routes.browse()) },
                onBrowseRoot = { nav.navigate(Routes.browse(it)) },
                onOpenRecent = { item, list -> opener.open(item, list) },
                player = player,
                onOpenPlayer = { nav.navigate(Routes.MUSIC) },
                onPlaylists = { nav.navigate(PlaylistRoutes.list(it)) },
            )
        }
        composable("category/{cat}", arguments = listOf(navArgument("cat") { type = NavType.StringType })) { e ->
            val cat = Category.valueOf(e.arguments?.getString("cat") ?: Category.RECENT.name)
            CategoryScreen(
                cat = cat, opener = opener, onBack = back,
                onFolder = { nav.navigate(Routes.categoryFolder(cat, it)) },
                onSearch = { nav.navigate(Routes.search(type = cat.typeFilter)) },
                onPaste = toPaste,
            )
        }
        composable(
            "catfolder/{cat}?folder={folder}",
            arguments = listOf(navArgument("cat") { type = NavType.StringType }, navArgument("folder") { type = NavType.StringType; defaultValue = "" }),
        ) { e ->
            val cat = Category.valueOf(e.arguments?.getString("cat") ?: Category.RECENT.name)
            val folder = e.arguments?.getString("folder").orEmpty()
            CategoryFolderScreen(cat, folder, opener, back, onSearch = { nav.navigate(Routes.search(root = folder)) }, onPaste = toPaste)
        }
        composable("browse?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
            BrowserScreen(
                startPath = e.arguments?.getString("path"),
                opener = opener, onBack = back,
                onSearchAll = { nav.navigate(Routes.search(root = it)) },
            )
        }
        composable(
            "search?root={root}&type={type}",
            arguments = listOf(
                navArgument("root") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { e ->
            SearchScreen(e.arguments?.getString("root"), e.arguments?.getString("type"), opener, back)
        }
        composable(Routes.APPS) { AppsScreen(back, onPaste = toPaste, onOpenApk = { nav.navigate(Routes.apk(it)) }) }
        composable(Routes.BIN) { BinScreen(back) }
        composable(Routes.SETTINGS) { SettingsScreen(back, onBin = { nav.navigate(Routes.BIN) }, onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) }, onLogs = { nav.navigate(Routes.LOGS) }, onCrashReports = { nav.navigate(Routes.CRASH_REPORTS) }, onFolderFilters = { nav.navigate(Routes.FOLDER_FILTERS) }, onAppLock = { nav.navigate(Routes.APP_LOCK) }, onVaultSettings = { nav.navigate(Routes.VAULT_SETTINGS) }) }
        vaultRoutes(nav, back)
        playlistRoutes(nav, back, opener, context)
        composable(Routes.FOLDER_FILTERS) { com.clpstudio.filesmanager.ui.screens.FolderFiltersScreen(back) }
        composable(Routes.CRASH_REPORTS) { com.clpstudio.filesmanager.crash.CrashReportsScreen(back) }
        composable(Routes.FAVORITES) { com.clpstudio.filesmanager.ui.screens.FavoritesScreen(opener, back, onPaste = toPaste) }
        composable(Routes.SLIDESHOW) { com.clpstudio.filesmanager.viewer.SlideshowScreen(ViewerSession.playItems, onBack = back) }
        composable(Routes.DIAGNOSTICS) { com.clpstudio.filesmanager.ui.screens.DiagnosticsScreen(back) }
        composable(Routes.MUSIC) { MusicPlayerScreen(player, back) }
        composable(Routes.HIDDEN) {
            com.clpstudio.filesmanager.ui.screens.HiddenFilesScreen(opener, back, onFolder = { nav.navigate(Routes.browse(it)) })
        }
        composable(Routes.STORAGE) {
            // Our own Manage storage screen: never the system Files app or Settings storage page.
            com.clpstudio.filesmanager.ui.screens.ManageStorageScreen(
                onBack = back,
                onBrowse = { nav.navigate(Routes.browse(it)) },
                onKind = { kind, vol -> nav.navigate(Routes.storageKind(kind, vol)) },
                onCleanup = { mode, vol -> nav.navigate(Routes.storageCleanup(mode, vol)) },
                onApps = { nav.navigate(Routes.APPS) },
                onBin = { nav.navigate(Routes.BIN) },
                onUnused = { nav.navigate(Routes.UNUSED_APPS) },
            )
        }
        composable(Routes.LOGS) { com.clpstudio.filesmanager.ui.screens.LogsScreen(back) }
        composable(Routes.UNUSED_APPS) { com.clpstudio.filesmanager.ui.screens.UnusedAppsScreen(back) }
        composable(
            "storage_kind?kind={kind}&vol={vol}",
            arguments = listOf(navArgument("kind") { type = NavType.StringType; defaultValue = "VIDEOS" }, navArgument("vol") { type = NavType.StringType; defaultValue = "" }),
        ) { e ->
            val kind = runCatching { com.clpstudio.filesmanager.data.StorageKind.valueOf(e.arguments?.getString("kind").orEmpty()) }
                .getOrDefault(com.clpstudio.filesmanager.data.StorageKind.VIDEOS)
            com.clpstudio.filesmanager.ui.screens.StorageKindScreen(kind, e.arguments?.getString("vol").orEmpty(), opener, back, onPaste = toPaste)
        }
        composable(
            "storage_clean?mode={mode}&vol={vol}",
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "large" }, navArgument("vol") { type = NavType.StringType; defaultValue = "" }),
        ) { e ->
            com.clpstudio.filesmanager.ui.screens.StorageCleanupScreen(e.arguments?.getString("mode").orEmpty(), e.arguments?.getString("vol").orEmpty(), back)
        }
        composable("view?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { e ->
            val path = e.arguments?.getString("path").orEmpty()
            com.clpstudio.filesmanager.viewer.FilePagerScreen(
                paths = ViewerSession.files,
                start = path,
                onBack = back,
                openFull = { item, list -> opener.openFull(item, list) },
                showInFolder = { folder -> nav.navigate(Routes.browse(folder)) },
            )
        }
        composable("pdf?uri={uri}&name={name}", arguments = listOf(navArgument("uri") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType })) { e ->
            VaultDocGuard(Uri.parse(e.arguments?.getString("uri")), back)
            PdfViewerScreen(Uri.parse(e.arguments?.getString("uri")), e.arguments?.getString("name").orEmpty(), back)
        }
        composable("text?uri={uri}&name={name}", arguments = listOf(navArgument("uri") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType })) { e ->
            VaultDocGuard(Uri.parse(e.arguments?.getString("uri")), back)
            TextViewerScreen(Uri.parse(e.arguments?.getString("uri")), e.arguments?.getString("name").orEmpty(), back)
        }
        composable("zip?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { e ->
            ZipViewerScreen(e.arguments?.getString("path").orEmpty(), back) { extracted -> opener.open(FileItem.of(extracted)) }
        }
        composable("apk?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { e ->
            com.clpstudio.filesmanager.viewer.ApkViewerScreen(
                e.arguments?.getString("path").orEmpty(), back,
                onBrowseFiles = { f -> nav.navigate(Routes.zip(f.absolutePath)) },
                onOpenText = { f -> nav.navigate(Routes.text(Uri.fromFile(f), f.name)) },
            )
        }
        composable("epub?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { e ->
            com.clpstudio.filesmanager.viewer.EpubReaderScreen(File(e.arguments?.getString("path").orEmpty()), back)
        }
        composable("info?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { e ->
            val path = e.arguments?.getString("path").orEmpty()
            val f = File(path)
            FileInfoScreen(
                ExternalFiles.fromFile(f), back,
                onViewText = { nav.navigate(Routes.text(Uri.fromFile(f), f.name)) },
                onViewHex = { nav.navigate(Routes.hex(Uri.fromFile(f), f.name)) },
            )
        }
        composable("font?uri={uri}&name={name}", arguments = listOf(navArgument("uri") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType })) { e ->
            FontPreviewScreen(Uri.parse(e.arguments?.getString("uri")), e.arguments?.getString("name").orEmpty(), back)
        }
        composable("hex?uri={uri}&name={name}", arguments = listOf(navArgument("uri") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType })) { e ->
            HexViewerScreen(Uri.parse(e.arguments?.getString("uri")), e.arguments?.getString("name").orEmpty(), back)
        }
    }
    }
    androidx.compose.animation.AnimatedVisibility(
        showMini,
        enter = androidx.compose.animation.slideInVertically { it } + fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + fadeOut(),
        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
    ) {
        com.clpstudio.filesmanager.ui.screens.MiniPlayer(player, onOpen = { nav.navigate(Routes.MUSIC) { launchSingleTop = true } })
    }
    }

    AppState.progress?.let { ProgressDialog(it) { AppState.cancelOperation() } }
    com.clpstudio.filesmanager.ui.components.DoneActionHost { target ->
        if (target.isDirectory) nav.navigate(Routes.browse(target.absolutePath))
        else opener.open(FileItem.of(target))
    }
    // One-time "The app closed unexpectedly…" snackbar on the first launch after a crash.
    com.clpstudio.filesmanager.crash.CrashNoticeHost { nav.navigate(Routes.CRASH_REPORTS) { launchSingleTop = true } }
}

/** Bottom space lists leave for the floating audio mini player (0 when it isn't showing). */
val LocalMiniPlayerInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** Screens that show the floating mini player: lists and pages, not Home (own bar), viewers or the full player. */
internal fun miniPlayerRoute(route: String): Boolean {
    if (route == Routes.HOME || route == Routes.MUSIC || route == Routes.SLIDESHOW) return false
    val viewers = listOf("view?", "pdf?", "text?", "zip?", "info?", "font?", "hex?", "epub?", "apk?")
    return viewers.none { route.startsWith(it) }
}
