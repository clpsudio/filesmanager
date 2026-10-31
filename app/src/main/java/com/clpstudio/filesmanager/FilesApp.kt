package com.clpstudio.filesmanager

import android.app.Application
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import com.clpstudio.filesmanager.thumbs.AppIconFetcher
import com.clpstudio.filesmanager.thumbs.AudioArtFetcher
import com.clpstudio.filesmanager.thumbs.PdfThumbFetcher
import com.clpstudio.filesmanager.thumbs.ThumbKeyers
import com.clpstudio.filesmanager.work.BinCleanupWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class FilesApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // First: a crash anywhere below is saved to filesDir/crash (Settings → Crash reports).
        com.clpstudio.filesmanager.crash.CrashLog.install(this)
        if (BuildConfig.DEBUG) com.clpstudio.filesmanager.crash.DebugStrictMode.enable()
        Graph.init(this)
        com.clpstudio.filesmanager.data.Favorites.init(this)
        com.clpstudio.filesmanager.data.StorageWatcher.install()
        installSdCard()
        // Phase 2: vault (encrypted, hardware key) and the app lock over every screen.
        com.clpstudio.filesmanager.vault.Vault.init(this)
        com.clpstudio.filesmanager.security.AppLock.init(this)
        // Bin: auto-delete after 30 days, checked daily and at start.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BinCleanupWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BinCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
        Graph.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { Graph.bin.purgeExpired() } }
    }

    /** SD card in / out, or "Show SD card" switched: every list, Home and the picker reload. */
    private fun installSdCard() {
        val sd = com.clpstudio.filesmanager.data.SdCards
        sd.addListener { com.clpstudio.filesmanager.ui.SdCardRefresh.onVolumesChanged(this) }
        sd.install(this)
        Graph.appScope.launch {
            var last: Boolean? = null
            Graph.settings.collect { s ->
                val prev = last
                last = s.showSdCard
                if (prev != null && prev != s.showSdCard) sd.changed()
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
                add(com.clpstudio.filesmanager.thumbs.RawPreviewFetcher.Factory())
                add(AudioArtFetcher.Factory())
                add(com.clpstudio.filesmanager.thumbs.VideoThumbFetcher.Factory(this@FilesApp))
                add(com.clpstudio.filesmanager.thumbs.VideoThumbFetcher.keyer)
                add(PdfThumbFetcher.Factory())
                add(AppIconFetcher.ApkFactory())
                add(AppIconFetcher.PackageFactory())
                add(ThumbKeyers.audio)
                add(ThumbKeyers.pdf)
                add(ThumbKeyers.apk)
                add(ThumbKeyers.app)
                add(com.clpstudio.filesmanager.thumbs.DesignThumbFetcher.Factory())
                add(com.clpstudio.filesmanager.thumbs.EpubCoverFetcher.Factory())
                add(com.clpstudio.filesmanager.thumbs.OfficeThumbFetcher.Factory())
                add(ThumbKeyers.design)
                add(ThumbKeyers.epub)
                add(ThumbKeyers.office)
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.25).build()
            }
            .crossfade(120)
            .build()
}
