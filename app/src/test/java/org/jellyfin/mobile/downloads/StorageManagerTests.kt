package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class StorageManagerTests {
    private val context = mockk<Context>()
    private val preferences = mockk<AppPreferences>()
    private val uri = mockk<Uri>()
    private val file = mockk<DocumentFile>()
    private val manager = StorageManager(context, preferences)
    private val itemId = UUID.randomUUID()
    private val download = DownloadFiles(
        DownloadEntity(
            serverId = 1,
            userId = 2,
            itemId = itemId,
            item = BaseItemDto(id = itemId, type = BaseItemKind.VIDEO),
            path = "old-folder",
            status = DownloadStatus.DOWNLOADED
        ),
        listOf(
            DownloadFileEntity(
                downloadId = 1,
                type = DownloadFileType.ITEM,
                size = 1024,
                fileName = "movie.mp4",
                uri = uri,
                status = DownloadStatus.DOWNLOADED
            )
        ),
    )

    @BeforeEach
    fun setup() {
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(context, uri) } returns file
        every { file.exists() } returns true
        every { file.canRead() } returns true
        every { file.length() } returns 1024
    }

    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `a valid file remains playable without artwork or the current storage folder`() {
        assertTrue(manager.verify(download))
    }

    @Test
    fun `queued and incomplete transfers cannot be played`() {
        assertFalse(manager.verify(download.copy(download = download.download.copy(status = DownloadStatus.QUEUED))))
        assertFalse(
            manager.verify(download.copy(files = download.files.map { it.copy(status = DownloadStatus.DOWNLOADING) }))
        )
    }

    @Test
    fun `a missing or truncated file is not ready`() {
        every { file.length() } returns 100
        assertFalse(manager.verify(download))
        every { file.exists() } returns false
        assertFalse(manager.verify(download))
    }

    @Test
    fun `revoked storage permission is handled as unavailable`() {
        every { DocumentFile.fromSingleUri(context, uri) } throws SecurityException("Grant revoked")
        assertFalse(manager.verify(download))
    }

    @Test
    fun `empty media and image-only records are not ready`() {
        assertFalse(manager.verify(download.copy(files = emptyList())))
        assertFalse(manager.verify(download.copy(files = download.files.map { it.copy(size = 0) })))
        assertFalse(
            manager.verify(download.copy(files = download.files.map { it.copy(type = DownloadFileType.IMAGE_PRIMARY) }))
        )
    }
}
