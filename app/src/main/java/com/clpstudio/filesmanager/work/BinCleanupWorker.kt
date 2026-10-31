package com.clpstudio.filesmanager.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clpstudio.filesmanager.Graph

/** Daily: removes Bin items older than 30 days. */
class BinCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        Graph.bin.purgeExpired()
        return Result.success()
    }

    companion object {
        const val NAME = "bin-cleanup"
    }
}
