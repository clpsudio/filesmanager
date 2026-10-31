package com.clpstudio.filesmanager.ui

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
fun AppRoot(nowPlayingTick: Int = 0) {
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
    LaunchedEffect(nowPlayingTick) {
        if (nowPlayingTick > 0) {
            if (player.isVideo) context.startActivity(Intent(context, VideoPlayerActivity::class.java))
            else nav.navigate(Routes.MUSIC) { launchSingleTop = true }
        }
    }
    val back: () -> Unit = { nav.popBackStack() }
    val toPaste: () -> Unit = { nav.navigate(Routes.browse()) }

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
                        HomeTile.VIDEOS -> nav.navigate(Routes.category(Category.VIDEOS))
                        HomeTile.AUDIO -> nav.navigate(Routes.category(Category.AUDIO))
                        HomeTile.DOCUMENTS -> nav.navigate(Routes.category(Category.DOCUMENTS))
                        HomeTile.DOWNLOADS -> nav.navigate(Routes.category(Category.DOWNLOADS))
                        HomeTile.APPS -> nav.navigate(Routes.APPS)
                        HomeTile.BIN -> nav.navigate(Routes.BIN)
                        HomeTile.INTERNAL -> {
                            val ok = runCatching { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }.isSuccess
                            if (!ok) nav.navigate(Routes.browse())
                        }
                        HomeTile.VAULT -> Toast.makeText(context, "The encrypted vault comes in the next update", Toast.LENGTH_SHORT).show()
                    }
                },
                onSearch = { nav.navigate(Routes.search()) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onBrowse = { nav.navigate(Routes.browse()) },
                onOpenRecent = { item, list -> opener.open(item, list) },
                player = player,
                onOpenPlayer = { nav.navigate(Routes.MUSIC) },
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
        composable(Routes.APPS) { AppsScreen(back) }
        composable(Routes.BIN) { BinScreen(back) }
        composable(Routes.SETTINGS) { SettingsScreen(back, onBin = { nav.navigate(Routes.BIN) }, onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) }) }
        composable(Routes.DIAGNOSTICS) { com.clpstudio.filesmanager.ui.screens.DiagnosticsScreen(back) }
        composable(Routes.MUSIC) { MusicPlayerScreen(player, back) }
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
            PdfViewerScreen(Uri.parse(e.arguments?.getString("uri")), e.arguments?.getString("name").orEmpty(), back)
        }
        composable("text?uri={uri}&name={name}", arguments = listOf(navArgument("uri") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType })) { e ->
            TextViewerScreen(Uri.parse(e.arguments?.getString("uri")), e.arguments?.getString("name").orEmpty(), back)
        }
        composable("zip?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { e ->
            ZipViewerScreen(e.arguments?.getString("path").orEmpty(), back) { extracted -> opener.open(FileItem.of(extracted)) }
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

    AppState.progress?.let { ProgressDialog(it) { AppState.cancelOperation() } }
}
