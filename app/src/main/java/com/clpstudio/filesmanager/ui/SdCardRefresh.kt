package com.clpstudio.filesmanager.ui

import android.content.Context
import android.provider.DocumentsContract
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.CategoryRepository

/** What a mounted / removed card or the "Show SD card" switch refreshes. */
object SdCardRefresh {
    fun onVolumesChanged(context: Context) {
        runCatching { Graph.index.invalidate() }
        // Re-run every cached category list with the new volumes / filter.
        runCatching { CategoryRepository.refreshAll() }
        AppState.refresh()
        // The system picker re-reads our roots (the SD card root comes and goes).
        runCatching {
            context.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(context.packageName + ".documents"), null,
            )
        }
    }
}
