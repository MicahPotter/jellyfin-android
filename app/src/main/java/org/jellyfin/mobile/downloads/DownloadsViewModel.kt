package org.jellyfin.mobile.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.sdk.model.api.MediaType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class DownloadsViewModel : ViewModel(), KoinComponent {

    private val downloadDao: DownloadDao by inject()
    private val downloadManager: DownloadManager by inject()
    private val activityEventHandler: ActivityEventHandler by inject()
    private val storageManager: StorageManager by inject()
    private val appPreferences: AppPreferences by inject()
    private val _actionFailed = MutableStateFlow(false)
    val actionFailed = _actionFailed.asStateFlow()

    private fun runAction(action: suspend () -> Unit) = viewModelScope.launch {
        _actionFailed.value = false
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Download action failed")
            _actionFailed.value = true
        }
    }

    val downloads: StateFlow<List<DownloadFiles>> = downloadDao
        .observeDownloads(appPreferences.currentServerId ?: -1, appPreferences.currentUserId ?: -1)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val _storageLocation = MutableStateFlow(storageManager.getStorageLocation())
    val storageLocation = _storageLocation.asStateFlow()

    private val _storageLocationAccessible = MutableStateFlow(storageManager.isStorageLocationAccessible())
    val storageLocationAccessible = _storageLocationAccessible.asStateFlow()

    private val storageRevision = MutableStateFlow(0)
    val readyIds = combine(downloads, storageRevision) { items, _ ->
        items.filter(storageManager::verify).map { it.download.id }.toSet()
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptySet())

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            _storageLocation.value = storageManager.getStorageLocation()
            _storageLocationAccessible.value = storageManager.isStorageLocationAccessible()
            storageRevision.value++
        }
    }

    fun openDownload(download: DownloadEntity) {
        when (download.item.mediaType) {
            MediaType.VIDEO -> {
                val playOptions = PlayOptions.forDownload(download.itemId)
                activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(playOptions))
            }

            MediaType.AUDIO,
            MediaType.PHOTO,
            MediaType.BOOK,
            MediaType.UNKNOWN -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        downloadDao.getFiles(download.id).firstOrNull { it.type == DownloadFileType.ITEM }?.uri
                    }?.let {
                        activityEventHandler.emit(ActivityEvent.OpenUrl(it.toString(), true))
                    }
                }
            }
        }
    }

    fun download(download: DownloadEntity) {
        runAction {
            downloadManager.resume(download)
        }
    }

    fun cancelDownload(download: DownloadEntity) {
        runAction { downloadManager.cancel(download.id) }
    }

    fun openSettings() = activityEventHandler.emit(ActivityEvent.OpenSettings)

    fun removeDownload(download: DownloadEntity, deleteFiles: Boolean) {
        runAction {
            downloadManager.delete(download.id, deleteFiles)
        }
    }

    fun changeStorageLocation(uri: android.net.Uri) {
        runAction {
            check(withContext(Dispatchers.IO) { storageManager.changeStorageLocation(uri) })
            refreshStorage()
        }
    }
}
