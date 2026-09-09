package org.jellyfin.mobile.downloads

import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.model.api.MediaType
import java.util.UUID

/** Shared by normal playback and the web player's local-availability check. Call off the main thread. */
class DownloadedMediaResolver(
    private val preferences: AppPreferences,
    private val downloads: DownloadDao,
    private val storage: StorageManager,
) {
    fun find(itemId: UUID, mediaSourceId: String? = null): DownloadFiles? {
        val serverId = preferences.currentServerId ?: return null
        val userId = preferences.currentUserId ?: return null
        val saved = downloads.getDownloadByItemId(itemId, serverId, userId) ?: return null
        if (saved.download.item.mediaType != MediaType.VIDEO) return null
        val source = saved.download.item.mediaSources?.firstOrNull() ?: return null
        // A saved original must not silently replace a different version explicitly selected by the user.
        if (!mediaSourceId.isNullOrEmpty() && !source.id?.replace("-", "").equals(mediaSourceId.replace("-", ""), ignoreCase = true)) return null
        if (!storage.verify(saved)) return null
        // Account switching can happen while a removable-storage provider verifies the file.
        return saved.takeIf { preferences.currentServerId == serverId && preferences.currentUserId == userId }
    }
}
