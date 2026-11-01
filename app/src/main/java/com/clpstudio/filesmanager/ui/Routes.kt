package com.clpstudio.filesmanager.ui

import android.net.Uri
import com.clpstudio.filesmanager.data.Category

object Routes {
    const val HOME = "home"
    const val APPS = "apps"
    const val UNUSED_APPS = "unused_apps"
    const val LOGS = "logs"
    const val BIN = "bin"
    const val SETTINGS = "settings"
    const val MUSIC = "music"
    const val VAULT = "vault"
    const val VAULT_SETTINGS = "vault_settings"
    const val APP_LOCK = "app_lock"
    fun vaultView(id: String) = "vault_view?id=${Uri.encode(id)}"
    const val DIAGNOSTICS = "diagnostics"
    const val CRASH_REPORTS = "crash_reports"
    const val LICENCES = "licences"
    const val SLIDESHOW = "slideshow"
    const val FAVORITES = "favorites"
    const val STORAGE = "storage"
    const val HIDDEN = "hidden"
    const val FOLDER_FILTERS = "folder_filters"

    fun storageKind(kind: com.clpstudio.filesmanager.data.StorageKind, volume: String) = "storage_kind?kind=${kind.name}&vol=${Uri.encode(volume)}"
    fun storageCleanup(mode: String, volume: String) = "storage_clean?mode=${Uri.encode(mode)}&vol=${Uri.encode(volume)}"

    fun category(c: Category) = "category/${c.name}"
    fun categoryFolder(c: Category, folder: String) = "catfolder/${c.name}?folder=${Uri.encode(folder)}"
    fun browse(path: String? = null) = "browse" + (if (path != null) "?path=${Uri.encode(path)}" else "")
    fun search(root: String? = null, type: String? = null): String {
        val q = listOfNotNull(root?.let { "root=${Uri.encode(it)}" }, type?.let { "type=$it" })
        return "search" + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }
    fun image(path: String) = view(path)
    fun view(path: String) = "view?path=${Uri.encode(path)}"
    fun pdf(uri: Uri, name: String) = "pdf?uri=${Uri.encode(uri.toString())}&name=${Uri.encode(name)}"
    fun text(uri: Uri, name: String) = "text?uri=${Uri.encode(uri.toString())}&name=${Uri.encode(name)}"
    fun font(uri: Uri, name: String) = "font?uri=${Uri.encode(uri.toString())}&name=${Uri.encode(name)}"
    fun hex(uri: Uri, name: String) = "hex?uri=${Uri.encode(uri.toString())}&name=${Uri.encode(name)}"
    fun zip(path: String) = "zip?path=${Uri.encode(path)}"
    fun epub(path: String) = "epub?path=${Uri.encode(path)}"
    fun apk(path: String) = "apk?path=${Uri.encode(path)}"
    fun info(path: String) = "info?path=${Uri.encode(path)}"
}
