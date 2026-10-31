package com.clpstudio.filesmanager.data

/**
 * Item 35: whether search results may include hidden files (dot-files, dot-folders, .nomedia folders).
 * Only when BOTH "Show hidden files" and "Show hidden files in search" are on; turning "Show hidden files"
 * off hides them from search too, whatever the second switch says. Vault/Bin are never searched (HiddenFilter).
 */
object SearchHidden {
    fun include(showHidden: Boolean, hiddenInSearch: Boolean): Boolean = showHidden && hiddenInSearch

    fun include(s: AppSettings): Boolean = include(s.showHidden, s.hiddenInSearch)
}
