package com.example.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_accounts",
    indices = [Index(value = ["email"], unique = true)]
)
data class LocalAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    val displayName: String,
    val passwordSaltHex: String,
    val passwordHashHex: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_host_sessions")
data class SavedHostSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sharingCode: String,
    val listenPort: Int,
    val localIp: String,
    val stunEndpoint: String,
    val permittedModesCsv: String,
    val maxDevices: Int,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null
)

@Entity(
    tableName = "saved_peers",
    indices = [Index(value = ["deviceUuid", "role"], unique = true)]
)
data class SavedPeerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "HOST" (saved by client) or "CLIENT" (saved by host)
    val deviceUuid: String,
    val peerName: String,
    val hostIp: String,
    val hostPort: Int,
    val sharingCode: String,
    val hostPublicKeyB64: String = "",
    val modeCode: String,
    val approvalStatus: String, // APPROVED, REJECTED, DISCONNECTED
    val bytesUploaded: Long = 0L,
    val bytesDownloaded: Long = 0L,
    val lastConnectedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "browsing_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val filename: String,
    val mimeType: String,
    val localPath: String,
    val contentUri: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val status: String, // QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    val speedBps: Long = 0L,
    val errorMessage: String? = null,
    val routedViaHost: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "traffic_records")
data class TrafficRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,   // yyyy-MM-dd
    val monthKey: String,  // yyyy-MM
    val role: String,      // HOST or CLIENT
    val peerLabel: String,
    val bytesUploaded: Long,
    val bytesDownloaded: Long,
    val durationSeconds: Long,
    val recordedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tunnel_events")
data class TunnelEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val level: String, // INFO, WARN, ERROR
    val component: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
