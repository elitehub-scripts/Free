package com.example.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalAccountDao {
    @Query("SELECT * FROM local_accounts WHERE email = :email LIMIT 1")
    suspend fun getByEmail(email: String): LocalAccountEntity?

    @Query("SELECT * FROM local_accounts ORDER BY lastLoginAt DESC")
    fun observeAccounts(): Flow<List<LocalAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: LocalAccountEntity): Long

    @Query("UPDATE local_accounts SET lastLoginAt = :timestamp WHERE id = :id")
    suspend fun updateLastLogin(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM local_accounts WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface HostSessionDao {
    @Query("SELECT * FROM saved_host_sessions ORDER BY startedAt DESC LIMIT 50")
    fun observeSessions(): Flow<List<SavedHostSessionEntity>>

    @Insert
    suspend fun insert(session: SavedHostSessionEntity): Long

    @Query("UPDATE saved_host_sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun markEnded(id: Long, endedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM saved_host_sessions")
    suspend fun clearAll()
}

@Dao
interface SavedPeerDao {
    @Query("SELECT * FROM saved_peers WHERE role = :role ORDER BY lastConnectedAt DESC LIMIT 50")
    fun observeByRole(role: String): Flow<List<SavedPeerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: SavedPeerEntity): Long

    @Query("DELETE FROM saved_peers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM saved_peers")
    suspend fun clearAll()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun isBookmarked(url: String): Flow<Boolean>
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM browsing_history ORDER BY visitedAt DESC LIMIT 200")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntity)

    @Query("DELETE FROM browsing_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM browsing_history")
    suspend fun clearAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface TrafficDao {
    @Query("SELECT * FROM traffic_records ORDER BY recordedAt DESC LIMIT 100")
    fun observeRecentRecords(): Flow<List<TrafficRecordEntity>>

    @Query("SELECT COALESCE(SUM(bytesUploaded), 0) FROM traffic_records WHERE dateKey = :dateKey")
    fun observeDailyUpload(dateKey: String): Flow<Long>

    @Query("SELECT COALESCE(SUM(bytesDownloaded), 0) FROM traffic_records WHERE dateKey = :dateKey")
    fun observeDailyDownload(dateKey: String): Flow<Long>

    @Query("SELECT COALESCE(SUM(bytesUploaded), 0) FROM traffic_records WHERE monthKey = :monthKey")
    fun observeMonthlyUpload(monthKey: String): Flow<Long>

    @Query("SELECT COALESCE(SUM(bytesDownloaded), 0) FROM traffic_records WHERE monthKey = :monthKey")
    fun observeMonthlyDownload(monthKey: String): Flow<Long>

    @Insert
    suspend fun insert(record: TrafficRecordEntity)

    @Query("DELETE FROM traffic_records")
    suspend fun clearAll()
}

@Dao
interface TunnelEventDao {
    @Query("SELECT * FROM tunnel_events ORDER BY timestamp DESC LIMIT 150")
    fun observeEvents(): Flow<List<TunnelEventEntity>>

    @Insert
    suspend fun insert(event: TunnelEventEntity)

    @Query("DELETE FROM tunnel_events")
    suspend fun clearAll()
}
