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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
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

private sealed interface DocxLoad {
    data object Loading : DocxLoad
    data class Ready(val doc: DocxDocument, val pages: List<List<DocBlock>>) : DocxLoad
    data class Failed(val message: String) : DocxLoad
}

/**
 * Offline .docx viewer body (no top bar): pages on white paper cards, pinch to zoom.
 * [onPageInfo] is called with (current page, total pages) as the visible page changes.
 */
@Composable
fun DocxContent(
    file: File,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onPageInfo: (current: Int, total: Int) -> Unit = { _, _ -> },
) {
    val load by produceState<DocxLoad>(DocxLoad.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                val doc = DocxParser.parse(file)
                DocxLoad.Ready(doc, doc.pages())
            } catch (e: OfficeParseException) {
                DocxLoad.Failed(e.message ?: "This Word document couldn't be opened.")
            } catch (e: OutOfMemoryError) {
                DocxLoad.Failed("This Word document is too large to show.")
            } catch (e: Throwable) { // also StackOverflowError on deeply nested XML: show the info screen, never crash
                DocxLoad.Failed("This Word document couldn't be opened. It may be damaged.")
            }
        }
    }
    Box(modifier.fillMaxSize().background(officeBackground())) {
        when (val l = load) {
            DocxLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is DocxLoad.Failed -> EmptyState(Icons.Outlined.Description, "Can't open", l.message, Modifier.padding(contentPadding))
            is DocxLoad.Ready -> DocxPages(l.doc, l.pages, contentPadding, onPageInfo)
        }
    }
}

@Composable
private fun DocxPages(doc: DocxDocument, pages: List<List<DocBlock>>, contentPadding: PaddingValues, onPageInfo: (Int, Int) -> Unit) {
    val listState = rememberLazyListState()
    val zoom = remember { ZoomState(maxScale = 5f) }
    val images = remember(doc) { OfficeImageCache() }
    ReportVisiblePage(listState, pages.size, onPageInfo)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = (maxWidth - 20.dp).coerceIn(200.dp, 900.dp)
        val pointScale = (pageWidth.value / doc.page.widthPt).coerceIn(0.4f, 2f)
        val r = DocRender(
            scale = pointScale,
            textScale = pointScale.coerceIn(0.85f, 1.6f),
            images = images,
            doc = doc,
            maxImagePx = with(LocalDensity.current) { (pageWidth.toPx() * 1.5f).toInt().coerceIn(256, 2048) },
        )
        val margin = (doc.page.marginLeftPt * pointScale * 0.5f).dp.coerceIn(20.dp, 48.dp)
        val ratio = (doc.page.heightPt / doc.page.widthPt).coerceIn(0.3f, 3f)
        com.clpstudio.filesmanager.ui.components.DocumentSelection(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding.plus(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().zoomable(zoom, verticalScrollOwner = true),
        ) {
            itemsIndexed(pages) { _, blocks ->
                OfficePage(Modifier.width(pageWidth).heightIn(min = pageWidth * ratio)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = margin)) {
                        for (b in blocks) r.Block(b, pageWidth - margin * 2)
                    }
                }
            }
            if (doc.truncated) {
                item {
                    Text(
                        "This document is very large; showing the first part.",
                        style = TextStyle(fontSize = 12.sp, color = OfficePageColors.HeaderInk),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        }
    }
}

private fun PaddingValues.plus(top: Dp, bottom: Dp): PaddingValues = object : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = this@plus.calculateLeftPadding(layoutDirection)
    override fun calculateRightPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = this@plus.calculateRightPadding(layoutDirection)
    override fun calculateTopPadding() = this@plus.calculateTopPadding() + top
    override fun calculateBottomPadding() = this@plus.calculateBottomPadding() + bottom
}

/** Rendering of the document model; [scale] = dp per point, [textScale] = sp per point. */
private class DocRender(
    val scale: Float,
    val textScale: Float,
    val images: OfficeImageCache,
    val doc: DocxDocument,
    val maxImagePx: Int,
) {
    @Composable
    fun Block(b: DocBlock, width: Dp) {
        when (b) {
            is DocParagraph -> Paragraph(b, width)
            is DocTable -> Table(b, width)
        }
    }

    @Composable
    fun Paragraph(p: DocParagraph, width: Dp) {
        val indent = (p.indentStartPt * scale).dp.coerceAtMost(width * 0.45f)
        val top = (p.spaceBeforePt * scale * 0.8f).dp
        val bottom = (p.spaceAfterPt * scale * 0.8f).dp
        val textAlign = when (p.align) {
            DocAlign.START -> TextAlign.Start
            DocAlign.CENTER -> TextAlign.Center
            DocAlign.END -> TextAlign.End
            DocAlign.JUSTIFY -> TextAlign.Justify
        }
        val inner = width - indent
        Column(Modifier.fillMaxWidth().padding(start = indent, top = top, bottom = bottom)) {
            if (p.runs.isEmpty() || p.runs.all { it is DocRun.Text && it.text.isEmpty() }) {
                Spacer(Modifier.height((p.maxSizePt * textScale * 1.15f).dp))
                return@Column
            }
            val marker = p.listMarker
            if (marker != null) {
                val markerWidth = maxOf((-p.firstLinePt * scale).dp, 18.dp).coerceAtMost(inner * 0.3f)
                val firstText = p.runs.firstOrNull { it is DocRun.Text } as? DocRun.Text
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(markerWidth)) {
                        Text(
                            marker,
                            style = TextStyle(
                                color = firstText?.colorHex?.let(::parseColor) ?: OfficePageColors.Ink,
                                fontSize = ((firstText?.sizePt ?: doc.defaultSizePt) * textScale * if (marker.length == 1) 1.15f else 1f).sp,
                                fontWeight = if (firstText?.bold == true) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    }
                    Column(Modifier.weight(1f)) { Content(p, textAlign, inner - markerWidth, firstLine = 0f) }
                }
            } else {
                Content(p, textAlign, inner, firstLine = p.firstLinePt)
            }
        }
    }

    /** Text and pictures of one paragraph, in order. */
    @Composable
    private fun Content(p: DocParagraph, align: TextAlign, width: Dp, firstLine: Float) {
        val groups = ArrayList<Any>()
        val texts = ArrayList<DocRun.Text>()
        for (r in p.runs) {
            when (r) {
                is DocRun.Text -> texts.add(r)
                is DocRun.Image -> {
                    if (texts.isNotEmpty()) { groups.add(ArrayList(texts)); texts.clear() }
                    groups.add(r)
                }
            }
        }
        if (texts.isNotEmpty()) groups.add(ArrayList(texts))
        val horizontal = when (p.align) {
            DocAlign.CENTER -> Alignment.CenterHorizontally
            DocAlign.END -> Alignment.End
            else -> Alignment.Start
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = horizontal) {
            var first = true
            for (g in groups) {
                if (g is DocRun.Image) {
                    Picture(g, width)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val runs = g as List<DocRun.Text>
                    val text = annotated(runs)
                    if (text.isNotBlank() || text.isNotEmpty()) {
                        val lineSize = runs.maxOf { it.sizePt }
                        Text(
                            text,
                            modifier = Modifier.fillMaxWidth(),
                            style = TextStyle(
                                color = OfficePageColors.Ink,
                                fontSize = (doc.defaultSizePt * textScale).sp,
                                lineHeight = (lineSize * textScale * 1.3f).sp,
                                textAlign = align,
                                textIndent = if (first && firstLine > 0f) TextIndent(firstLine = (firstLine * textScale).sp) else null,
                            ),
                        )
                    }
                }
                first = false
            }
        }
    }

    private fun annotated(runs: List<DocRun.Text>): AnnotatedString = buildAnnotatedString {
        for (r in runs) {
            val deco = listOfNotNull(
                if (r.underline) TextDecoration.Underline else null,
                if (r.strike) TextDecoration.LineThrough else null,
            )
            val style = SpanStyle(
                color = r.colorHex?.let(::parseColor) ?: if (r.hyperlink != null) OfficePageColors.Link else OfficePageColors.Ink,
                fontSize = (r.sizePt * textScale * if (r.script != 0) 0.7f else 1f).sp,
                fontWeight = if (r.bold) FontWeight.Bold else null,
                fontStyle = if (r.italic) FontStyle.Italic else null,
                textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
                background = r.highlightHex?.let(::parseColor) ?: Color.Unspecified,
                baselineShift = when (r.script) { 1 -> BaselineShift.Superscript; -1 -> BaselineShift.Subscript; else -> null },
            )
            withStyle(style) { append(r.text.replace("\t", "    ")) }
        }
    }

    @Composable
    private fun Picture(img: DocRun.Image, maxWidth: Dp) {
        val bytes = doc.images[img.path]
        val bmp by produceState<Bitmap?>(images.get(img.path), img.path) {
            if (value == null && bytes != null) value = withContext(Dispatchers.Default) { images.getOrDecode(img.path, bytes, maxImagePx) }
        }
        val ratio = when {
            img.widthPt > 0f && img.heightPt > 0f -> img.widthPt / img.heightPt
            bmp != null -> bmp!!.width.toFloat() / bmp!!.height.coerceAtLeast(1)
            else -> 4f / 3f
        }.coerceIn(0.05f, 20f)
        val w = if (img.widthPt > 0f) (img.widthPt * scale).dp.coerceAtMost(maxWidth) else maxWidth
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(),
                contentDescription = img.description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(w).aspectRatio(ratio).padding(vertical = 2.dp),
            )
        } else {
            Box(Modifier.width(w).aspectRatio(ratio).background(OfficePageColors.HeaderFill), contentAlignment = Alignment.Center) {
                if (bytes == null || bmp == null) {
                    Text(img.description ?: "Picture", style = TextStyle(fontSize = 11.sp, color = OfficePageColors.HeaderInk), modifier = Modifier.padding(4.dp))
                }
            }
        }
    }

    @Composable
    fun Table(t: DocTable, width: Dp) {
        val cols = maxOf(1, t.columnCount)
        val widths = if (t.columnWidthsPt.size == cols) t.columnWidthsPt else List(cols) { 1f }
        val total = widths.sum().coerceAtLeast(1f)
        val line = OfficePageColors.GridLine
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            t.rows.forEachIndexed { ri, row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    var col = 0
                    row.cells.forEachIndexed { ci, cell ->
                        val span = cell.colSpan.coerceAtLeast(1)
                        val w = (col until minOf(cols, col + span)).sumOf { widths.getOrElse(it) { 1f }.toDouble() }.toFloat()
                            .takeIf { it > 0f } ?: (total / cols)
                        col += span
                        val cellWidth = width * (w / total)
                        Box(
                            Modifier
                                .weight(w)
                                .fillMaxHeight()
                                .then(if (cell.fillHex != null) Modifier.background(parseColor(cell.fillHex)) else Modifier)
                                .then(
                                    if (t.borders) {
                                        Modifier.drawBehind {
                                            val s = 1.dp.toPx()
                                            drawLine(line, Offset(size.width - s / 2, 0f), Offset(size.width - s / 2, size.height), s)
                                            drawLine(line, Offset(0f, size.height - s / 2), Offset(size.width, size.height - s / 2), s)
                                            if (ci == 0) drawLine(line, Offset(s / 2, 0f), Offset(s / 2, size.height), s)
                                            if (ri == 0) drawLine(line, Offset(0f, s / 2), Offset(size.width, s / 2), s)
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                for (b in cell.blocks) Block(b, cellWidth - 8.dp)
                            }
                        }
                    }
                    if (col < cols) Spacer(Modifier.weight((col until cols).sumOf { widths.getOrElse(it) { 1f }.toDouble() }.toFloat().coerceAtLeast(0.01f)))
                }
            }
        }
    }
}

/** "#RRGGBB" -> Color (opaque); bad input -> ink. */
internal fun parseColor(hex: String): Color {
    val s = hex.removePrefix("#")
    val v = s.toLongOrNull(16) ?: return OfficePageColors.Ink
    return Color(0xFF000000 or (v and 0xFFFFFF))
}
