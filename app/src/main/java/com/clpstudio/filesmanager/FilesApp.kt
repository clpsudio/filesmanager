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
        Graph.init(this)
        // Bin: auto-delete after 30 days, checked daily and at start.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BinCleanupWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BinCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
        Graph.appScope.launch { runCatching { Graph.bin.purgeExpired() } }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
                add(com.clpstudio.filesmanager.thumbs.RawPreviewFetcher.Factory())
                add(AudioArtFetcher.Factory())
                add(PdfThumbFetcher.Factory())
                add(AppIconFetcher.ApkFactory())
                add(AppIconFetcher.PackageFactory())
                add(ThumbKeyers.audio)
                add(ThumbKeyers.pdf)
                add(ThumbKeyers.apk)
                add(ThumbKeyers.app)
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.25).build()
            }
            .crossfade(120)
            .build()
}
