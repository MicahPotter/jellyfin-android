package org.jellyfin.mobile.downloads

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaType
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class DownloadedMediaResolverTests {
    private val preferences = mockk<AppPreferences>()
    private val dao = mockk<DownloadDao>()
    private val storage = mockk<StorageManager>()
    private val resolver = DownloadedMediaResolver(preferences, dao, storage)
    private val itemId = UUID.randomUUID()
    private val sourceId = UUID.randomUUID().toString()
    private val source = mockk<MediaSourceInfo>()
    private val saved = DownloadFiles(
        DownloadEntity(
            serverId = 1, userId = 2, itemId = itemId, path = "saved",
            status = DownloadStatus.DOWNLOADED,
            item = BaseItemDto(id = itemId, type = BaseItemKind.MOVIE, mediaType = MediaType.VIDEO, mediaSources = listOf(source)),
        ), emptyList(),
    )

    @BeforeEach
    fun setup() {
        every { source.id } returns sourceId
        every { preferences.currentServerId } returns 1
        every { preferences.currentUserId } returns 2
        every { dao.getDownloadByItemId(itemId, 1, 2) } returns saved
        every { storage.verify(any()) } returns true
    }

    @Test
    fun `normal playback finds the active account's verified copy without an offline flag`() {
        assertSame(saved, resolver.find(itemId))
        verify(exactly = 1) { dao.getDownloadByItemId(itemId, 1, 2) }
    }

    @Test
    fun `an explicitly chosen version must match the saved original`() {
        assertSame(saved, resolver.find(itemId, sourceId.replace("-", "")))
        assertNull(resolver.find(itemId, UUID.randomUUID().toString()))
    }

    @Test
    fun `incomplete missing or unreadable copies fall back to ordinary playback`() {
        every { storage.verify(saved) } returns false
        assertNull(resolver.find(itemId))
    }

    @Test
    fun `signed out accounts and other accounts cannot use the saved copy`() {
        every { preferences.currentUserId } returns null
        assertNull(resolver.find(itemId))
        every { preferences.currentUserId } returns 3
        every { dao.getDownloadByItemId(itemId, 1, 3) } returns null
        assertNull(resolver.find(itemId))
    }

    @Test
    fun `a saved record without video source metadata is not selected`() {
        every { dao.getDownloadByItemId(itemId, 1, 2) } returns saved.copy(
            download = saved.download.copy(item = saved.download.item.copy(mediaSources = emptyList())),
        )
        assertNull(resolver.find(itemId))
    }
}
