package com.clpstudio.filesmanager.data

import android.content.Context
import java.io.File

/** PDF text for "Select text" (item 51): the words on a page, read offline with PDFBox. */
object PdfText {
    /** The text of page [index] (0-based); "" when the page has no text layer (a scanned image). */
    fun page(context: Context?, file: File, index: Int): String {
        if (context != null) PdfSecurity.init(context)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { d ->
            if (index !in 0 until d.numberOfPages) return ""
            val s = com.tom_roush.pdfbox.text.PDFTextStripper()
            s.startPage = index + 1
            s.endPage = index + 1
            return s.getText(d).trim()
        }
    }
}
