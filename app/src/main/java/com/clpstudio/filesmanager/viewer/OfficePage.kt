package com.clpstudio.filesmanager.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.viewer.office.DocxContent
import com.clpstudio.filesmanager.viewer.office.PptxContent
import com.clpstudio.filesmanager.viewer.office.XlsxContent
import java.io.File

/** Formats with an in-app document viewer (paper pages). Legacy .doc/.xls/.ppt are not among them. */
fun hasOfficeViewer(name: String): Boolean = FileTypes.extension(name) in setOf("docx", "dotx", "xlsx", "xlsm", "pptx")

/** A document page body: the paper-page viewer when we have one, else a card with "Open with". */
@Composable
fun OfficeBody(file: File, modifier: Modifier, topPadding: Dp, fallback: @Composable () -> Unit) {
    var page by remember(file) { mutableStateOf(1 to 0) }
    when (FileTypes.extension(file.name)) {
        "docx", "dotx" -> Box(modifier.fillMaxSize()) {
            DocxContent(file, Modifier.fillMaxSize(), PaddingValues(top = topPadding, bottom = 64.dp), onPageInfo = { c, t -> page = c to t })
            if (page.second > 0) PageCounter(page.first, page.second, Modifier.align(Alignment.BottomEnd))
        }
        "xlsx", "xlsm" -> Box(modifier.fillMaxSize()) {
            XlsxContent(file, Modifier.fillMaxSize(), PaddingValues(top = topPadding, bottom = 64.dp), onPageInfo = { c, t -> page = c to t })
        }
        "pptx" -> Box(modifier.fillMaxSize()) {
            PptxContent(file, Modifier.fillMaxSize(), PaddingValues(top = topPadding, bottom = 64.dp), onPageInfo = { c, t -> page = c to t })
            if (page.second > 0) PageCounter(page.first, page.second, Modifier.align(Alignment.BottomEnd))
        }
        else -> fallback()
    }
}

/** Word / Excel / PowerPoint inside the pager. */
@Composable
fun OfficePage(item: FileItem, modifier: Modifier, topPadding: Dp, onOpen: () -> Unit) {
    OfficeBody(File(item.path), modifier, topPadding) { PreviewCard(item, modifier, onOpen) }
}
