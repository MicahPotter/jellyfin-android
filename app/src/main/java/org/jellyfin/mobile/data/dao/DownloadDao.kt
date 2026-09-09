package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.UUID

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download ORDER BY created_at DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Transaction
    @Query("SELECT * FROM download ORDER BY created_at DESC")
    fun getAllDownloadsWithFiles(): Flow<List<DownloadFiles>>

    @Transaction
    @Query("SELECT * FROM download WHERE server_id = :serverId AND user_id = :userId ORDER BY created_at DESC")
    fun observeDownloads(serverId: Long, userId: Long): Flow<List<DownloadFiles>>

    @Transaction
    @Query("SELECT * FROM download WHERE server_id = :serverId AND user_id = :userId ORDER BY created_at DESC")
    fun getDownloadSnapshot(serverId: Long, userId: Long): List<DownloadFiles>

    @Transaction
    @Query("SELECT * FROM download WHERE status = 'QUEUED' OR status = 'DOWNLOADING' ORDER BY created_at ASC")
    fun getQueuedDownloads(): List<DownloadFiles>

    @Query("SELECT * FROM download WHERE item_id IN (:itemIds)")
    fun getDownloadsByItemIds(itemIds: Collection<UUID>): List<DownloadEntity>

    @Transaction
    @Query("SELECT * FROM download WHERE item_id = :itemId AND server_id = :serverId AND user_id = :userId LIMIT 1")
    fun getDownloadByItemId(itemId: UUID, serverId: Long, userId: Long): DownloadFiles?

    @Query("SELECT * FROM download WHERE id = :id")
    suspend fun getDownload(id: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(entity: DownloadEntity): Int

    @Query("UPDATE download SET status = 'DOWNLOADING' WHERE id = :id AND status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun claim(id: Long): Int

    @Query("UPDATE download SET status = :status WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun finishTransfer(id: Long, status: DownloadStatus): Int

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM download_file WHERE download_id = :downloadId")
    suspend fun getFiles(downloadId: Long): List<DownloadFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(entity: DownloadFileEntity): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateFile(entity: DownloadFileEntity): Int
}
