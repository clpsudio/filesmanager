package com.clpstudio.filesmanager.data

/**
 * Item 45: a file name split into the part you rename and its type (extension).
 * "photo.jpg" -> ("photo", ".jpg"); "backup.tar.gz" -> ("backup", ".tar.gz"); "README" -> ("README", "");
 * ".nomedia" / ".bashrc" (hidden, no type) -> (".nomedia", ""); ".hidden.txt" -> (".hidden", ".txt");
 * "notes." -> ("notes.", "").
 */
object FileNames {
    /** Two-part types that belong together. */
    private val DOUBLE = setOf("tar.gz", "tar.bz2", "tar.xz", "tar.zst", "tar.lz", "tar.lzma", "tar.z")

    data class Parts(val base: String, val ext: String) {
        val full get() = base + ext
    }

    fun split(name: String): Parts {
        val lower = name.lowercase()
        for (d in DOUBLE) {
            val i = lower.length - d.length - 1
            if (i > 0 && lower.endsWith(".$d") && name[i - 1] != '/') return Parts(name.substring(0, i), name.substring(i))
        }
        val dot = name.lastIndexOf('.')
        // dot == 0: a hidden name like ".nomedia" has no type; a trailing dot is part of the name.
        if (dot <= 0 || dot == name.length - 1) return Parts(name, "")
        // Something like ".hidden" + ".txt" still splits on the last dot (dot > 0).
        return Parts(name.substring(0, dot), name.substring(dot))
    }

    /**
     * The new full name when the extension is locked: what was typed is only the name,
     * so a typed ".txt" at the end stays part of the name and the real type is kept.
     */
    fun lockedName(typedBase: String, ext: String): String = typedBase.trim() + ext
}
