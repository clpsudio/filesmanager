package com.clpstudio.filesmanager.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException

/**
 * Lets the app's own PDF and text viewers read a vault file without a plaintext copy:
 * openFile hands out a seekable proxy descriptor that decrypts on demand (Android 8+).
 * Not exported, no URI grants: no other app can read through it. Works only while the vault is open.
 */
class VaultProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun item(uri: Uri): VaultItem {
        if (!Vault.isReady || !Vault.store.isUnlocked) throw FileNotFoundException("The vault is locked")
        val id = uri.lastPathSegment ?: throw FileNotFoundException()
        return Vault.store.find(id) ?: throw FileNotFoundException("Not in the vault")
    }

    override fun getType(uri: Uri): String? = runCatching { item(uri).mime }.getOrNull()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val it = item(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> it.name
                OpenableColumns.SIZE -> it.size
                else -> null
            }
        })
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val item = item(uri)
        val reader = Vault.store.openReader(item)
        val sm = context!!.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = reader.size
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                reader.read(offset, data, 0, size).coerceAtLeast(0)
            } catch (e: Exception) {
                throw ErrnoException("read", OsConstants.EIO)
            }
            override fun onRelease() { runCatching { reader.close() } }
        }, handler)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.clpstudio.filesmanager.vault"
        fun uri(item: VaultItem): Uri = Uri.parse("content://$AUTHORITY/item/${item.id}")
        fun isVault(uri: Uri): Boolean = uri.authority == AUTHORITY
        private val handler: Handler by lazy { Handler(HandlerThread("vault-io").apply { start() }.looper) }
    }
}
