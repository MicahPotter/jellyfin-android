package org.jellyfin.mobile.downloads

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class DownloadDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        JellyfinDatabase::class.java,
    ).build()

    @After
    fun cleanup() = db.close()

    @Test
    fun sameTitleOnDifferentServersAndAccountsStaysIsolated() = runBlocking {
        val itemId = UUID.randomUUID()
        val serverA = db.serverDao.insert("https://server-a.invalid")
        val serverB = db.serverDao.insert("https://server-b.invalid")
        val userA = db.userDao.insert(serverA, UUID.randomUUID(), "test")
        val userB = db.userDao.insert(serverA, UUID.randomUUID(), "test")
        val userC = db.userDao.insert(serverB, UUID.randomUUID(), "test")
        for ((server, user) in listOf(serverA to userA, serverA to userB, serverB to userC)) {
            db.downloadDao.insert(
                DownloadEntity(
                    serverId = server,
                    userId = user,
                    itemId = itemId,
                    item = BaseItemDto(id = itemId, type = BaseItemKind.MOVIE, name = "Same title"),
                    path = "$server-$user",
                )
            )
        }
        assertEquals("$serverA-$userB", db.downloadDao.getDownloadByItemId(itemId, serverA, userB)?.download?.path)
        assertEquals(1, db.downloadDao.getDownloadSnapshot(serverB, userC).size)
        assertNull(db.downloadDao.getDownloadByItemId(itemId, serverB, userA))
    }

    @Test
    fun cancelledAndDeletedDownloadsCannotBeResurrectedByACompletingWorker() = runBlocking {
        val itemId = UUID.randomUUID()
        val server = db.serverDao.insert("https://server.invalid")
        val user = db.userDao.insert(server, UUID.randomUUID(), "test")
        val id = db.downloadDao.insert(
            DownloadEntity(
                serverId = server,
                userId = user,
                itemId = itemId,
                item = BaseItemDto(id = itemId, type = BaseItemKind.MOVIE),
                path = "movie",
            )
        )
        assertEquals(1, db.downloadDao.claim(id))
        val active = requireNotNull(db.downloadDao.getDownload(id))
        db.downloadDao.update(active.copy(status = DownloadStatus.CANCELLED))
        assertEquals(0, db.downloadDao.finishTransfer(id, DownloadStatus.DOWNLOADED))
        assertEquals(0, db.downloadDao.claim(id))
        assertEquals(DownloadStatus.CANCELLED, db.downloadDao.getDownload(id)?.status)
        db.downloadDao.delete(id)
        assertEquals(0, db.downloadDao.finishTransfer(id, DownloadStatus.QUEUED))
        assertNull(db.downloadDao.getDownload(id))
    }
}
