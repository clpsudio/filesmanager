package com.clpstudio.filesmanager.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.io.IOException

/**
 * PDF passwords, fully offline (PdfBox-Android):
 *  - open a password-protected PDF (decrypted to a private temp copy only for viewing),
 *  - add a password (AES-256, user + owner password),
 *  - remove a password the user knows.
 */
object PdfSecurity {
    enum class Lock { NONE, OWNER_ONLY, USER_PASSWORD }

    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        PDFBoxResourceLoader.init(context.applicationContext)
        ready = true
    }

    private fun memory() = MemoryUsageSetting.setupMixed(32L * 1024 * 1024)

    private fun load(file: File, password: String?): PDDocument =
        if (password == null) PDDocument.load(file, memory()) else PDDocument.load(file, password, memory())

    /** NONE = opens freely; OWNER_ONLY = opens, but has edit restrictions; USER_PASSWORD = needs a password to open. */
    fun lockState(file: File): Lock = try {
        load(file, null).use { if (it.isEncrypted) Lock.OWNER_ONLY else Lock.NONE }
    } catch (e: InvalidPasswordException) {
        Lock.USER_PASSWORD
    }

    fun checkPassword(file: File, password: String): Boolean = try {
        load(file, password).use { true }
    } catch (e: InvalidPasswordException) {
        false
    }

    /** Writes an unprotected copy of [input] to [out]. Throws [InvalidPasswordException] for a wrong password. */
    fun decrypt(input: File, password: String, out: File) = transform(input, password, out) { doc ->
        doc.isAllSecurityToBeRemoved = true
    }

    /**
     * Writes a copy of [input] protected with AES-256: [userPassword] is needed to open it,
     * [ownerPassword] (defaults to the user password) to change permissions.
     * [currentPassword] opens an input that is already protected. [out] may be [input] (replace).
     */
    fun encrypt(input: File, userPassword: String, out: File, ownerPassword: String? = null, currentPassword: String? = null) {
        require(userPassword.length >= 4) { "The password needs at least 4 characters" }
        transform(input, currentPassword, out) { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val owner = ownerPassword?.takeIf { it.isNotEmpty() } ?: userPassword
            val policy = StandardProtectionPolicy(owner, userPassword, AccessPermission()).apply {
                encryptionKeyLength = 256
                isPreferAES = true
            }
            doc.protect(policy)
        }
    }

    /** Load, change, save to a temp file, close, then move into place ([out] may be the input). */
    private fun transform(input: File, password: String?, out: File, change: (PDDocument) -> Unit) {
        val tmp = File(out.absoluteFile.parentFile, ".${out.name}.${System.nanoTime()}.part")
        try {
            val doc = load(input, password)
            try {
                change(doc)
                doc.save(tmp)
            } finally {
                doc.close()
            }
            if (out.exists() && !out.delete()) throw IOException("Couldn't replace ${out.name}")
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        } finally {
            tmp.delete()
        }
    }

    /** "<name>_locked.pdf" / "<name>_unlocked.pdf" next to the original, not overwriting anything. */
    fun copyName(original: File, suffix: String): File {
        val dir = original.parentFile ?: File(".")
        val base = original.nameWithoutExtension.removeSuffix("_locked").removeSuffix("_unlocked") + suffix
        var f = File(dir, "$base.pdf")
        var i = 2
        while (f.exists()) f = File(dir, "$base ($i).pdf").also { i++ }
        return f
    }
}
