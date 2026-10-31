package com.clpstudio.filesmanager.viewer.office

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.viewer.ZoomState
import com.clpstudio.filesmanager.viewer.zoomable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface PptxLoad {
    data object Loading : PptxLoad
    data class Ready(val deck: PptxPresentation) : PptxLoad
    data class Failed(val message: String) : PptxLoad
}

/**
 * Offline .pptx viewer body (no top bar): each slide as a white card with its text, pictures and
 * tables in reading order. [onPageInfo] reports (current slide, slide count).
 */
@Composable
fun PptxContent(
    file: File,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onPageInfo: (current: Int, total: Int) -> Unit = { _, _ -> },
) {
    val load by produceState<PptxLoad>(PptxLoad.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                PptxLoad.Ready(PptxParser.parse(file))
            } catch (e: OfficeParseException) {
                PptxLoad.Failed(e.message ?: "This PowerPoint file couldn't be opened.")
            } catch (e: OutOfMemoryError) {
                PptxLoad.Failed("This PowerPoint file is too large to show.")
            } catch (e: Throwable) { // also StackOverflowError on deeply nested XML: show the info screen, never crash
                PptxLoad.Failed("This PowerPoint file couldn't be opened. It may be damaged.")
            }
        }
    }
    Box(modifier.fillMaxSize().background(officeBackground())) {
        when (val l = load) {
            PptxLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is PptxLoad.Failed -> EmptyState(Icons.Outlined.Slideshow, "Can't open", l.message, Modifier.padding(contentPadding))
            is PptxLoad.Ready -> Slides(l.deck, contentPadding, onPageInfo)
        }
    }
}

@Composable
private fun Slides(deck: PptxPresentation, contentPadding: PaddingValues, onPageInfo: (Int, Int) -> Unit) {
    val listState = rememberLazyListState()
    val zoom = remember { ZoomState(maxScale = 5f) }
    val cache = remember(deck) { OfficeImageCache() }
    ReportVisiblePage(listState, deck.slides.size, onPageInfo)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardWidth = (maxWidth - 20.dp).coerceIn(200.dp, 960.dp)
        val ratio = (deck.slideWidthPt / deck.slideHeightPt).coerceIn(0.3f, 4f)
        val textScale = (cardWidth.value / deck.slideWidthPt * 1.5f).coerceIn(0.5f, 1.3f)
        val maxPx = with(LocalDensity.current) { (cardWidth.toPx() * 1.5f).toInt().coerceIn(256, 2048) }
        com.clpstudio.filesmanager.ui.components.DocumentSelection(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().zoomable(zoom, verticalScrollOwner = true),
        ) {
            items(deck.slides, key = { it.number }) { slide ->
                OfficePage(Modifier.width(cardWidth).heightIn(min = cardWidth / ratio)) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (item in slide.items) SlideItemView(item, deck, cache, textScale, maxPx, cardWidth - 40.dp)
                    }
                    Text(
                        "${slide.number}",
                        style = TextStyle(fontSize = 10.sp, color = OfficePageColors.HeaderInk),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun SlideItemView(item: SlideItem, deck: PptxPresentation, cache: OfficeImageCache, textScale: Float, maxPx: Int, width: Dp) {
    when (item) {
        is SlideItem.Paragraph -> {
            if (item.runs.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                return
            }
            val align = when (item.align) {
                DocAlign.CENTER -> TextAlign.Center
                DocAlign.END -> TextAlign.End
                DocAlign.JUSTIFY -> TextAlign.Justify
                DocAlign.START -> TextAlign.Start
            }
            val text = buildAnnotatedString {
                for (r in item.runs) {
                    val deco = listOfNotNull(if (r.underline) TextDecoration.Underline else null, if (r.strike) TextDecoration.LineThrough else null)
                    withStyle(
                        SpanStyle(
                            color = r.colorHex?.let(::parseColor) ?: if (r.hyperlink != null) OfficePageColors.Link else OfficePageColors.Ink,
                            fontSize = (r.sizePt * textScale * if (r.script != 0) 0.7f else 1f).sp,
                            fontWeight = if (r.bold) FontWeight.Bold else null,
                            fontStyle = if (r.italic) FontStyle.Italic else null,
                            textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
                            baselineShift = when (r.script) { 1 -> BaselineShift.Superscript; -1 -> BaselineShift.Subscript; else -> null },
                        ),
                    ) { append(r.text) }
                }
            }
            val size = item.runs.maxOf { it.sizePt } * textScale
            Row(Modifier.fillMaxWidth().padding(start = (item.level * 18).dp, bottom = if (item.isTitle) 6.dp else 0.dp)) {
                if (item.bullet != null) {
                    Text(
                        item.bullet,
                        style = TextStyle(fontSize = size.sp, color = item.runs.first().colorHex?.let(::parseColor) ?: OfficePageColors.Ink),
                        modifier = Modifier.width((size * 1.6f).coerceAtLeast(14f).dp),
                    )
                }
                Text(text, style = TextStyle(color = OfficePageColors.Ink, textAlign = align, lineHeight = (size * 1.25f).sp), modifier = Modifier.weight(1f))
            }
        }
        is SlideItem.Picture -> {
            val bytes = deck.images[item.path]
            val bmp by produceState<Bitmap?>(cache.get(item.path), item.path) {
                if (value == null && bytes != null) value = withContext(Dispatchers.Default) { cache.getOrDecode(item.path, bytes, maxPx) }
            }
            val ratio = when {
                item.widthPt > 0f && item.heightPt > 0f -> item.widthPt / item.heightPt
                bmp != null -> bmp!!.width.toFloat() / bmp!!.height.coerceAtLeast(1)
                else -> 16f / 9f
            }.coerceIn(0.1f, 10f)
            val w = if (item.widthPt > 0f) (width * (item.widthPt / deck.slideWidthPt).coerceIn(0.2f, 1f)) else width
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) {
                    Image(b.asImageBitmap(), item.description, contentScale = ContentScale.Fit, modifier = Modifier.width(w).aspectRatio(ratio))
                } else {
                    Box(Modifier.width(w).aspectRatio(ratio).background(OfficePageColors.HeaderFill), contentAlignment = Alignment.Center) {
                        Text(item.description ?: "Picture", fontSize = 11.sp, color = OfficePageColors.HeaderInk)
                    }
                }
            }
        }
        is SlideItem.Table -> {
            val cols = item.rows.maxOf { it.size }.coerceAtLeast(1)
            val line = OfficePageColors.GridLine
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                item.rows.forEachIndexed { ri, row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        for (c in 0 until cols) {
                            Box(
                                Modifier.weight(1f).fillMaxHeight()
                                    .then(if (ri == 0) Modifier.background(OfficePageColors.HeaderFill) else Modifier)
                                    .drawBehind {
                                        drawLine(line, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
                                        drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                                        if (c == 0) drawLine(line, Offset(0f, 0f), Offset(0f, size.height), 1f)
                                        if (ri == 0) drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1f)
                                    }
                                    .padding(4.dp),
                            ) {
                                Text(
                                    row.getOrNull(c) ?: "",
                                    style = TextStyle(
                                        fontSize = (14f * textScale).sp,
                                        color = OfficePageColors.Ink,
                                        fontWeight = if (ri == 0) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
