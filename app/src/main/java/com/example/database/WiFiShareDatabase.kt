package com.example.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Database(
    entities = [
        LocalAccountEntity::class,
        SavedHostSessionEntity::class,
        SavedPeerEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        TrafficRecordEntity::class,
        TunnelEventEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class WiFiShareDatabase : RoomDatabase() {
    abstract fun localAccountDao(): LocalAccountDao
    abstract fun hostSessionDao(): HostSessionDao
    abstract fun savedPeerDao(): SavedPeerDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun trafficDao(): TrafficDao
    abstract fun tunnelEventDao(): TunnelEventDao

    companion object {
        @Volatile
        private var INSTANCE: WiFiShareDatabase? = null

        fun getInstance(context: Context): WiFiShareDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WiFiShareDatabase::class.java,
                    "wifishare_local.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

class WiFiShareLocalRepository(private val db: WiFiShareDatabase) {
    val localAccounts: Flow<List<LocalAccountEntity>> = db.localAccountDao().observeAccounts()
    val hostSessions: Flow<List<SavedHostSessionEntity>> = db.hostSessionDao().observeSessions()
    val savedHosts: Flow<List<SavedPeerEntity>> = db.savedPeerDao().observeByRole("HOST")
    val savedClients: Flow<List<SavedPeerEntity>> = db.savedPeerDao().observeByRole("CLIENT")
    val bookmarks: Flow<List<BookmarkEntity>> = db.bookmarkDao().observeBookmarks()
    val history: Flow<List<HistoryEntity>> = db.historyDao().observeHistory()
    val downloads: Flow<List<DownloadEntity>> = db.downloadDao().observeDownloads()
    val trafficRecords: Flow<List<TrafficRecordEntity>> = db.trafficDao().observeRecentRecords()
    val tunnelEvents: Flow<List<TunnelEventEntity>> = db.tunnelEventDao().observeEvents()

    suspend fun findAccountByEmail(email: String): LocalAccountEntity? =
        db.localAccountDao().getByEmail(email.trim().lowercase(Locale.US))

    suspend fun createLocalAccount(account: LocalAccountEntity): Long =
        db.localAccountDao().insert(account)

    suspend fun touchAccountLogin(accountId: Long) =
        db.localAccountDao().updateLastLogin(accountId)

    suspend fun recordHostSessionStart(session: SavedHostSessionEntity): Long =
        db.hostSessionDao().insert(session)

    suspend fun markHostSessionEnded(sessionId: Long) {
        if (sessionId > 0L) db.hostSessionDao().markEnded(sessionId)
    }

    suspend fun savePeerRecord(peer: SavedPeerEntity): Long =
        db.savedPeerDao().upsert(peer)

    suspend fun getTrustedClientUuids(): Set<String> =
        db.savedPeerDao().getTrustedClientUuids().toSet()

    suspend fun getTrustedOrRecentHostByCode(code: String): SavedPeerEntity? =
        db.savedPeerDao().getHostBySharingCode(code)

    suspend fun updatePeerApprovalStatus(id: Long, status: String) =
        db.savedPeerDao().updateApprovalStatus(id, status)

    suspend fun deletePeerRecord(id: Long) =
        db.savedPeerDao().deleteById(id)

    fun isBookmarked(url: String): Flow<Boolean> = db.bookmarkDao().isBookmarked(url)

    suspend fun toggleBookmark(title: String, url: String, currentlyBookmarked: Boolean) {
        if (currentlyBookmarked) {
            db.bookmarkDao().deleteByUrl(url)
        } else {
            db.bookmarkDao().insert(BookmarkEntity(title = title.ifBlank { url }, url = url))
        }
    }

    suspend fun removeBookmark(url: String) = db.bookmarkDao().deleteByUrl(url)

    suspend fun addHistory(title: String, url: String) {
        if (url.isBlank() || url == "about:blank") return
        db.historyDao().insert(HistoryEntity(title = title.ifBlank { url }, url = url))
    }

    suspend fun clearHistory() = db.historyDao().clearAll()

    suspend fun upsertDownload(entity: DownloadEntity) = db.downloadDao().upsert(entity)
    suspend fun getDownload(id: String): DownloadEntity? = db.downloadDao().getById(id)
    suspend fun deleteDownload(id: String) = db.downloadDao().deleteById(id)

    fun observeDailyUpload(dateKey: String = todayKey()): Flow<Long> =
        db.trafficDao().observeDailyUpload(dateKey)

    fun observeDailyDownload(dateKey: String = todayKey()): Flow<Long> =
        db.trafficDao().observeDailyDownload(dateKey)

    fun observeMonthlyUpload(monthKey: String = monthKey()): Flow<Long> =
        db.trafficDao().observeMonthlyUpload(monthKey)

    fun observeMonthlyDownload(monthKey: String = monthKey()): Flow<Long> =
        db.trafficDao().observeMonthlyDownload(monthKey)

    suspend fun recordSessionTraffic(
        role: String,
        peerLabel: String,
        uploaded: Long,
        downloaded: Long,
        durationSeconds: Long
    ) {
        if (uploaded <= 0L && downloaded <= 0L) return
        db.trafficDao().insert(
            TrafficRecordEntity(
                dateKey = todayKey(),
                monthKey = monthKey(),
                role = role,
                peerLabel = peerLabel,
                bytesUploaded = uploaded,
                bytesDownloaded = downloaded,
                durationSeconds = durationSeconds
            )
        )
    }

    suspend fun logEvent(level: String, component: String, message: String) {
        db.tunnelEventDao().insert(
            TunnelEventEntity(level = level, component = component, message = message)
        )
    }

    suspend fun clearEvents() = db.tunnelEventDao().clearAll()

    companion object {
        fun todayKey(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        fun monthKey(): String =
            SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    }
}
