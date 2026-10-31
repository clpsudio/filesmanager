package com.clpstudio.filesmanager.viewer.office

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/** Colours of the "paper page" look shared by the DOCX, XLSX and PPTX viewers. */
object OfficePageColors {
    val Paper = Color.White
    val Outline = Color(0xFFD9DCE1)
    val Ink = Color(0xFF111111)
    val LightBackground = Color(0xFFEDEFF2)
    val DarkBackground = Color(0xFF1B1D22)
    val Link = Color(0xFF0563C1)
    val GridLine = Color(0xFFBFC4CC)
    val HeaderFill = Color(0xFFF1F3F6)
    val HeaderInk = Color(0xFF5F6670)
}

/** Grey background behind the pages, darker in a dark theme. */
@Composable
fun officeBackground(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) OfficePageColors.DarkBackground else OfficePageColors.LightBackground

private val PageShape = RoundedCornerShape(2.dp)

/** A white page with a thin light-grey outline and a soft shadow. */
@Composable
fun OfficePage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .shadow(3.dp, PageShape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
            .clip(PageShape)
            .background(OfficePageColors.Paper)
            .border(1.dp, OfficePageColors.Outline, PageShape),
        content = content,
    )
}

/**
 * Reports (current page, total) as the list scrolls: the current page is the item that covers
 * the middle of the viewport. [firstPageItem] is the index of page 1 in the list.
 */
@Composable
fun ReportVisiblePage(state: LazyListState, total: Int, onPageInfo: (Int, Int) -> Unit, firstPageItem: Int = 0) {
    val callback by rememberUpdatedState(onPageInfo)
    LaunchedEffect(state, total, firstPageItem) {
        snapshotFlow {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val item = info.visibleItemsInfo.firstOrNull { it.offset <= center && it.offset + it.size > center }
                ?: info.visibleItemsInfo.firstOrNull()
            ((item?.index ?: firstPageItem) - firstPageItem).coerceIn(0, maxOf(0, total - 1))
        }.distinctUntilChanged().collect { callback(it + 1, total) }
    }
}

/** Decoded-image cache for one open document (bounded by bytes). */
class OfficeImageCache(maxBytes: Int = 48 * 1024 * 1024) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun getOrDecode(key: String, bytes: ByteArray, maxPx: Int): Bitmap? {
        cache.get(key)?.let { return it }
        val bmp = decodeOfficeImage(bytes, maxPx) ?: return null
        cache.put(key, bmp)
        return bmp
    }
}
