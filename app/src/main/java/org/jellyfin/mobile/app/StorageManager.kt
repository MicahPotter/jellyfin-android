package org.jellyfin.mobile.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import timber.log.Timber

class StorageManager(
    private val context: Context,
    private val appPreferences: AppPreferences
) {
    val defaultStorageLocation
        get() = Environment.getExternalStorageDirectory().resolve(context.getString(R.string.app_name_short)).toUri()

    fun getStorageLocation() = appPreferences.storageLocation?.toUri()?.let {
        DocumentFile.fromTreeUri(context, it)
    }

    fun isStorageLocationAccessible(): Boolean {
        val documentFile = getStorageLocation()
        return documentFile != null && documentFile.exists() && documentFile.canWrite()
    }

    fun changeStorageLocation(location: Uri): Boolean {
        if (appPreferences.storageLocation?.toUri() == location) return true

        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                location,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )

            appPreferences.storageLocation = location.toString()
            getStorageLocation()?.let(::ensureNoMedia)
        }.onFailure { err ->
            Timber.e(err, "Failed to change storage location to $location")
        }.isSuccess
    }

    fun verify(download: DownloadFiles): Boolean {
        if (download.download.status != DownloadStatus.DOWNLOADED) return false
        val media = download.files.firstOrNull { it.type == DownloadFileType.ITEM } ?: return false
        if (media.status != DownloadStatus.DOWNLOADED || media.size <= 0) return false
        // Artwork is optional. Check the recorded URI, which may be in an older download folder.
        return runCatching {
            val file = DocumentFile.fromSingleUri(context, media.uri)
            file != null && file.exists() && file.canRead() && file.length() == media.size
        }.getOrDefault(false)
    }

    private fun ensureNoMedia(documentFile: DocumentFile) {
        if (documentFile.findFile(NOMEDIA_FILE) == null) {
            documentFile.createFile("", NOMEDIA_FILE)
        }
    }

    companion object {
        const val NOMEDIA_FILE = ".nomedia"
    }
}
