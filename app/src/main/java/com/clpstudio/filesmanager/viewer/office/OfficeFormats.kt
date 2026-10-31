package com.clpstudio.filesmanager.viewer.office

import com.clpstudio.filesmanager.data.FileTypes
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** The Office Open XML formats Files can show offline. */
enum class OfficeFormat { DOCX, XLSX, PPTX }

/**
 * Works out which in-app viewer an Office file needs: from its extension, else its MIME type
 * (files shared from other apps often have no extension), else from the parts inside the zip.
 */
object OfficeFormats {
    // Item 11 (F11): Word / Excel / PowerPoint 97–2003 open in the same viewers (own binary readers).
    private val docx = setOf("docx", "dotx", "docm", "dotm", "doc", "dot")
    private val xlsx = setOf("xlsx", "xlsm", "xls", "xlt")
    private val pptx = setOf("pptx", "ppt", "pps", "pot")

    fun byExtension(name: String): OfficeFormat? = when (FileTypes.extension(name)) {
        in docx -> OfficeFormat.DOCX
        in xlsx -> OfficeFormat.XLSX
        in pptx -> OfficeFormat.PPTX
        else -> null
    }

    fun byMime(mime: String?): OfficeFormat? {
        val m = mime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim() ?: return null
        return when {
            m.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml.") ||
                m == "application/vnd.ms-word.document.macroenabled.12" ||
                m == "application/vnd.ms-word.template.macroenabled.12" || m == "application/msword" -> OfficeFormat.DOCX
            m.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.") ||
                m == "application/vnd.ms-excel.sheet.macroenabled.12" || m == "application/vnd.ms-excel" -> OfficeFormat.XLSX
            m.startsWith("application/vnd.openxmlformats-officedocument.presentationml.presentation") || m == "application/vnd.ms-powerpoint" -> OfficeFormat.PPTX
            else -> null
        }
    }

    /** Looks inside the zip (slow-ish: call off the main thread). */
    fun sniff(file: File): OfficeFormat? = if (Cfb.isCfb(file)) sniffLegacy(file) else try {
        ZipFile(file).use { z ->
            when {
                z.getEntry("word/document.xml") != null -> OfficeFormat.DOCX
                z.getEntry("xl/workbook.xml") != null -> OfficeFormat.XLSX
                z.getEntry("ppt/presentation.xml") != null -> OfficeFormat.PPTX
                else -> {
                    val ct = z.getEntry("[Content_Types].xml")?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }.orEmpty()
                    when {
                        ct.contains("wordprocessingml.document.main") || ct.contains("ms-word.document.macroEnabled.main") -> OfficeFormat.DOCX
                        ct.contains("spreadsheetml.sheet.main") -> OfficeFormat.XLSX
                        ct.contains("presentationml.presentation.main") -> OfficeFormat.PPTX
                        else -> null
                    }
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun sniffLegacy(file: File): OfficeFormat? = runCatching {
        Cfb.open(file).use { c ->
            when {
                c.has("WordDocument") -> OfficeFormat.DOCX
                c.has("Workbook") || c.has("Book") -> OfficeFormat.XLSX
                c.has("PowerPoint Document") -> OfficeFormat.PPTX
                else -> null
            }
        }
    }.getOrNull()

    /** Extension / MIME only (no file access): safe on the main thread. */
    fun quick(name: String, mime: String? = null): OfficeFormat? = byExtension(name) ?: byMime(mime)

    fun detect(name: String, mime: String?, file: File?): OfficeFormat? = quick(name, mime) ?: file?.let { sniff(it) }

    /**
     * For document types Files can't draw (Word 97–2003 .doc, .xls, .ppt, OpenDocument, RTF, Pages):
     * a short name for the "no preview" card, or null.
     */
    fun noPreviewLabel(name: String): String? = when (FileTypes.extension(name)) {
        "odt" -> "OpenDocument text (.odt)"
        "ods" -> "OpenDocument sheets (.ods)"
        "odp" -> "OpenDocument slides (.odp)"
        "rtf" -> "Rich text (.rtf)"
        "pages" -> "Apple Pages documents"
        else -> null
    }

    fun noPreviewMessage(name: String): String =
        noPreviewLabel(name)?.let { "Files can't preview $it. Open it with another app." }
            ?: "Files can't preview this document. Open it with another app."
}
