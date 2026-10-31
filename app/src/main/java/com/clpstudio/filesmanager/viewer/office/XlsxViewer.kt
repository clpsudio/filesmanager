package com.clpstudio.filesmanager.viewer.office

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.viewer.ZoomState
import com.clpstudio.filesmanager.viewer.zoomable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface XlsxLoad {
    data object Loading : XlsxLoad
    data class Ready(val book: XlsxWorkbook) : XlsxLoad
    data class Failed(val message: String) : XlsxLoad
}

/**
 * Offline .xlsx viewer body (no top bar): sheet tabs, then the sheet as a cell grid on a white
 * page, scrollable both ways. [onPageInfo] reports (current sheet, sheet count).
 */
@Composable
fun XlsxContent(
    file: File,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onPageInfo: (current: Int, total: Int) -> Unit = { _, _ -> },
) {
    val load by produceState<XlsxLoad>(XlsxLoad.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                XlsxLoad.Ready(XlsxParser.parse(file))
            } catch (e: OfficeParseException) {
                XlsxLoad.Failed(e.message ?: "This Excel file couldn't be opened.")
            } catch (e: OutOfMemoryError) {
                XlsxLoad.Failed("This Excel file is too large to show.")
            } catch (e: Exception) {
                XlsxLoad.Failed("This Excel file couldn't be opened. It may be damaged.")
            }
        }
    }
    Box(modifier.fillMaxSize().background(officeBackground())) {
        when (val l = load) {
            XlsxLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is XlsxLoad.Failed -> EmptyState(Icons.Outlined.TableChart, "Can't open", l.message, Modifier.padding(contentPadding))
            is XlsxLoad.Ready -> XlsxBook(l.book, contentPadding, onPageInfo)
        }
    }
}

@Composable
private fun XlsxBook(book: XlsxWorkbook, contentPadding: PaddingValues, onPageInfo: (Int, Int) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, book.sheets.lastIndex)
    val callback by rememberUpdatedState(onPageInfo)
    LaunchedEffect(index, book.sheets.size) { callback(index + 1, book.sheets.size) }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (book.sheets.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                book.sheets.forEachIndexed { i, sheet ->
                    val active = i == index
                    Text(
                        sheet.name + if (sheet.hidden) " (hidden)" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .clickable { selected = i }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
        OfficePage(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = if (book.sheets.size > 1) 0.dp else 10.dp)) {
            SheetGrid(book.sheets[index])
        }
    }
}

private const val MAX_CELL_CHARS = 400

@Composable
private fun SheetGrid(sheet: XlsxSheet) {
    if (sheet.rowCount == 0 || sheet.columnCount == 0) {
        Text("This sheet is empty.", color = OfficePageColors.HeaderInk, modifier = Modifier.padding(24.dp))
        return
    }
    val zoom = remember(sheet) { ZoomState(maxScale = 4f) }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val widths: List<Dp> = remember(sheet) {
        List(sheet.columnCount) { c ->
            val chars = sheet.columnWidths[c] ?: sheet.defaultColumnWidth
            if (chars <= 0f) 0.dp else (chars * 9f + 8f).coerceIn(24f, 560f).dp
        }
    }
    val numberWidth = (sheet.rowCount.toString().length * 8 + 16).dp
    val totalWidth = numberWidth + widths.fold(0.dp) { a, b -> a + b }
    val line = OfficePageColors.GridLine
    BoxWithConstraints(Modifier.fillMaxSize().zoomable(zoom, verticalScrollOwner = true)) {
        val viewWidth = maxWidth
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LazyColumn(state = listState, modifier = Modifier.width(maxOf(totalWidth, viewWidth)).fillMaxSize()) {
                stickyHeader {
                    Row(Modifier.background(OfficePageColors.HeaderFill)) {
                        HeaderCell("", numberWidth, line)
                        widths.forEachIndexed { c, w -> if (w > 0.dp) HeaderCell(XlsxParser.columnName(c), w, line) }
                    }
                }
                items(sheet.rowCount) { r ->
                    val row = sheet.rows[r]
                    val h = (sheet.rowHeights[r]?.let { it * 1.33f } ?: 22f).coerceIn(18f, 160f).dp
                    Row(Modifier.height(h)) {
                        HeaderCell((r + 1).toString(), numberWidth, line, Modifier.height(h))
                        widths.forEachIndexed { c, w ->
                            if (w > 0.dp) GridCell(row[c], w, h, line)
                        }
                    }
                }
                if (sheet.truncated) {
                    item {
                        Text(
                            "Showing the first ${XlsxParser.MAX_ROWS} rows and ${XlsxParser.MAX_COLS} columns.",
                            fontSize = 12.sp, color = OfficePageColors.HeaderInk, modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.gridLines(line: Color) = drawBehind {
    val s = 1f
    drawLine(line, Offset(size.width - s / 2, 0f), Offset(size.width - s / 2, size.height), s)
    drawLine(line, Offset(0f, size.height - s / 2), Offset(size.width, size.height - s / 2), s)
}

@Composable
private fun HeaderCell(text: String, width: Dp, line: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.width(width).height(22.dp).background(OfficePageColors.HeaderFill).gridLines(line),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontSize = 11.sp, color = OfficePageColors.HeaderInk), maxLines = 1)
    }
}

@Composable
private fun GridCell(cell: XlsxCell?, width: Dp, height: Dp, line: Color) {
    Box(
        Modifier.width(width).height(height)
            .then(if (cell?.fillHex != null) Modifier.background(parseColor(cell.fillHex)) else Modifier)
            .gridLines(line)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (cell != null && cell.text.isNotEmpty()) {
            val align = when (cell.align) {
                DocAlign.CENTER -> TextAlign.Center
                DocAlign.END -> TextAlign.End
                DocAlign.START -> TextAlign.Start
                DocAlign.JUSTIFY -> TextAlign.Justify
                null -> if (cell.isNumber) TextAlign.End else TextAlign.Start
            }
            Text(
                cell.text.take(MAX_CELL_CHARS),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = cell.colorHex?.let(::parseColor) ?: OfficePageColors.Ink,
                    fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal,
                    textAlign = align,
                ),
                maxLines = maxOf(1, (height.value / 18f).toInt()),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
