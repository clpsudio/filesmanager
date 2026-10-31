package com.clpstudio.filesmanager.picker

import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileTypes
import java.util.Locale

/** The places the picker can show: categories, then a folder browser. */
enum class PickerSection(val title: String, val category: Category?, val probeMime: String?) {
    RECENT("Recent", Category.RECENT, null),
    PHOTOS("Photos", Category.PHOTOS, "image/jpeg"),
    VIDEOS("Videos", Category.VIDEOS, "video/mp4"),
    AUDIO("Audio", Category.AUDIO, "audio/mpeg"),
    DOCUMENTS("Documents", Category.DOCUMENTS, "application/pdf"),
    DOWNLOADS("Downloads", Category.DOWNLOADS, null),
    BROWSE("Browse", null, null),
}

/**
 * What another app asked for with ACTION_GET_CONTENT / ACTION_PICK: which types, one or many.
 * Types are MIME patterns: any type, a family like image, or one exact type.
 */
data class PickerRequest(val mimeTypes: List<String>, val multiple: Boolean) {

    val acceptsAll: Boolean get() = mimeTypes.any { it == "*/*" }

    fun accepts(mime: String?): Boolean {
        if (acceptsAll) return true
        val m = mime?.lowercase(Locale.ROOT)?.takeIf { it.contains('/') } ?: return false
        return mimeTypes.any { p -> matches(p, m) }
    }

    /** Files only (folders are for browsing), of an accepted type. */
    fun accepts(item: FileItem): Boolean = !item.isDir && accepts(FileTypes.mimeOf(item.name))

    /** Sections worth showing for this request (an images-only pick has no Audio tab). */
    val sections: List<PickerSection>
        get() = PickerSection.entries.filter { s -> s.probeMime == null || accepts(s.probeMime) }

    /** Where the picker opens: Photos for images, Videos for videos, Audio for audio, else Recent. */
    val startSection: PickerSection
        get() {
            if (acceptsAll || mimeTypes.isEmpty()) return PickerSection.RECENT
            val families = mimeTypes.map { it.substringBefore('/') }.toSet()
            return when (families) {
                setOf("image") -> PickerSection.PHOTOS
                setOf("video") -> PickerSection.VIDEOS
                setOf("audio") -> PickerSection.AUDIO
                setOf("image", "video") -> PickerSection.PHOTOS
                else -> PickerSection.RECENT
            }
        }

    /** "Choose a photo" / "Choose files"… for the top bar. */
    val title: String
        get() {
            val families = mimeTypes.map { it.substringBefore('/') }.toSet()
            val noun = when {
                acceptsAll || mimeTypes.isEmpty() -> if (multiple) "files" else "a file"
                families == setOf("image") -> if (multiple) "photos" else "a photo"
                families == setOf("video") -> if (multiple) "videos" else "a video"
                families == setOf("audio") -> if (multiple) "audio files" else "an audio file"
                else -> if (multiple) "files" else "a file"
            }
            return "Choose $noun"
        }

    companion object {
        /** From the intent's type, EXTRA_MIME_TYPES and EXTRA_ALLOW_MULTIPLE. */
        fun from(type: String?, extraMimeTypes: Array<String>?, allowMultiple: Boolean): PickerRequest {
            val extras = extraMimeTypes.orEmpty().map(::normalize).filter { it.isNotEmpty() }
            val main = normalize(type)
            val list = when {
                extras.isNotEmpty() && (main == "*/*" || main.isEmpty()) -> extras
                extras.isNotEmpty() -> (extras + main).distinct()
                main.isEmpty() -> listOf("*/*")
                else -> listOf(main)
            }
            return PickerRequest(list.distinct(), allowMultiple)
        }

        /** ACTION_PICK on a MediaStore table arrives as "vnd.android.cursor.dir/image" and the like. */
        fun normalize(type: String?): String {
            val t = type?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (t.isEmpty()) return ""
            if (t.startsWith("vnd.android.cursor.dir/") || t.startsWith("vnd.android.cursor.item/")) {
                val what = t.substringAfter('/')
                return when {
                    what.startsWith("image") -> "image/*"
                    what.startsWith("video") -> "video/*"
                    what.startsWith("audio") -> "audio/*"
                    else -> "*/*"
                }
            }
            if (t == "*" || !t.contains('/')) return "*/*"
            return t
        }

        fun matches(pattern: String, mime: String): Boolean {
            val p = pattern.lowercase(Locale.ROOT)
            if (p == "*/*") return true
            if (p.endsWith("/*")) return mime.startsWith(p.dropLast(1))
            return p == mime
        }
    }
}
