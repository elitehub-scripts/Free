package com.example.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import com.example.database.DownloadEntity
import com.example.database.WiFiShareLocalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManagerEngine(
    private val context: Context,
    private val repository: WiFiShareLocalRepository,
    private val scope: CoroutineScope,
    private val tunnelClientProvider: () -> OkHttpClient?
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val directClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getDownloadsDir(subfolder: String = "WiFiShare"): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        return File(base, subfolder).apply { mkdirs() }
    }

    fun enqueueDownload(
        url: String,
        suggestedFilename: String? = null,
        mimeType: String? = null,
        routedViaHost: Boolean,
        subfolder: String = "WiFiShare"
    ): String {
        val id = UUID.randomUUID().toString()
        val guessedName = (suggestedFilename?.takeIf { it.isNotBlank() }
            ?: URLUtil.guessFileName(url, null, mimeType))
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destFile = resolveUniqueFile(getDownloadsDir(subfolder), guessedName)
        val resolvedMime = mimeType ?: guessMimeType(guessedName)

        val initial = DownloadEntity(
            id = id,
            url = url,
            filename = destFile.name,
            mimeType = resolvedMime,
            localPath = destFile.absolutePath,
            bytesDownloaded = 0L,
            totalBytes = -1L,
            status = "QUEUED",
            routedViaHost = routedViaHost
        )
        scope.launch(Dispatchers.IO) {
            repository.upsertDownload(initial)
            startOrResumeInternal(initial)
        }
        return id
    }

    fun pauseDownload(id: String) {
        activeJobs.remove(id)?.cancel()
        scope.launch(Dispatchers.IO) {
            repository.getDownload(id)?.let { current ->
                if (current.status == "DOWNLOADING" || current.status == "QUEUED") {
                    repository.upsertDownload(
                        current.copy(
                            status = "PAUSED",
                            speedBps = 0L,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    fun resumeOrRetry(id: String) {
        scope.launch(Dispatchers.IO) {
            val entity = repository.getDownload(id) ?: return@launch
            if (entity.status == "PAUSED" || entity.status == "FAILED" || entity.status == "CANCELLED") {
                startOrResumeInternal(entity)
            }
        }
    }

    fun cancelDownload(id: String) {
        activeJobs.remove(id)?.cancel()
        scope.launch(Dispatchers.IO) {
            val entity = repository.getDownload(id) ?: return@launch
            runCatching { File(entity.localPath).delete() }
            repository.upsertDownload(
                entity.copy(
                    status = "CANCELLED",
                    speedBps = 0L,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteRecord(id: String) {
        activeJobs.remove(id)?.cancel()
        scope.launch(Dispatchers.IO) {
            repository.getDownload(id)?.let { runCatching { File(it.localPath).delete() } }
            repository.deleteDownload(id)
        }
    }

    private fun startOrResumeInternal(entity: DownloadEntity) {
        activeJobs.remove(entity.id)?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            val file = File(entity.localPath)
            file.parentFile?.mkdirs()
            val existingBytes = if (file.exists()) file.length() else 0L

            repository.upsertDownload(
                entity.copy(
                    status = "DOWNLOADING",
                    bytesDownloaded = existingBytes,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )

            try {
                val client = if (entity.routedViaHost) {
                    tunnelClientProvider() ?: throw IllegalStateException("Host tunnel is not active")
                } else {
                    directClient
                }

                val reqBuilder = Request.Builder().url(entity.url)
                if (existingBytes > 0L) {
                    reqBuilder.addHeader("Range", "bytes=$existingBytes-")
                }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty response body")
                    val supportsResume = resp.code == 206
                    val startOffset = if (supportsResume) existingBytes else 0L
                    val contentLength = body.contentLength()
                    val totalSize = if (contentLength > 0L) startOffset + contentLength else entity.totalBytes

                    RandomAccessFile(file, "rw").use { raf ->
                        if (!supportsResume) {
                            raf.setLength(0L)
                        }
                        raf.seek(startOffset)
                        var downloaded = startOffset
                        val buf = ByteArray(16384)
                        val inp = body.byteStream()
                        var lastUiUpdateMs = System.currentTimeMillis()
                        var windowBytes = 0L

                        while (isActive) {
                            val n = inp.read(buf)
                            if (n == -1) break
                            raf.write(buf, 0, n)
                            downloaded += n
                            windowBytes += n

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastUiUpdateMs
                            if (elapsed >= 500L) {
                                val speed = (windowBytes * 1000L) / elapsed.coerceAtLeast(1L)
                                repository.upsertDownload(
                                    entity.copy(
                                        bytesDownloaded = downloaded,
                                        totalBytes = totalSize,
                                        status = "DOWNLOADING",
                                        speedBps = speed,
                                        updatedAt = now
                                    )
                                )
                                lastUiUpdateMs = now
                                windowBytes = 0L
                            }
                        }

                        if (!isActive) return@use

                        val uri = runCatching {
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            ).toString()
                        }.getOrNull()

                        repository.upsertDownload(
                            entity.copy(
                                bytesDownloaded = downloaded,
                                totalBytes = if (totalSize > 0) totalSize else downloaded,
                                status = "COMPLETED",
                                speedBps = 0L,
                                contentUri = uri,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.upsertDownload(
                    entity.copy(
                        status = "FAILED",
                        speedBps = 0L,
                        errorMessage = e.localizedMessage ?: "Download error",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } finally {
                activeJobs.remove(entity.id)
            }
        }
        activeJobs[entity.id] = job
    }

    fun openCompletedFile(entity: DownloadEntity): Result<Unit> {
        return try {
            val file = File(entity.localPath)
            if (!file.exists()) {
                return Result.failure(IllegalStateException("Downloaded file no longer exists on disk"))
            }
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, entity.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveUniqueFile(dir: File, filename: String): File {
        var candidate = File(dir, filename)
        if (!candidate.exists()) return candidate
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var idx = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$idx$ext")
            idx++
        }
        return candidate
    }

    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
