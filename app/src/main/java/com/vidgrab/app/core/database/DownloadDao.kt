package com.vidgrab.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("""
        SELECT * FROM download_tasks 
        WHERE status != 'CANCELLED'
        ORDER BY 
            CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END ASC, 
            queueGroup DESC,
            queueIndex ASC,
            createdAt ASC
    """)
    fun getAllTasksFlow(): Flow<List<DownloadTaskEntity>>

    @Query("""
        SELECT * FROM download_tasks 
        WHERE status IN ('DOWNLOADING', 'MERGING', 'EXPANDING', 'PENDING') 
        ORDER BY queueGroup ASC, queueIndex ASC, createdAt ASC
    """)
    fun getActiveTasksFlow(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status IN ('DOWNLOADING', 'MERGING')")
    suspend fun getActiveDownloadingCount(): Int

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED' ORDER BY updatedAt DESC")
    fun getCompletedTasksFlow(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = 'PENDING' ORDER BY queueGroup ASC, queueIndex ASC, createdAt ASC LIMIT 1")
    suspend fun getNextPendingTask(): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = 'PENDING' ORDER BY queueGroup ASC, queueIndex ASC, createdAt ASC LIMIT :limit")
    suspend fun getNextPendingTasks(limit: Int): List<DownloadTaskEntity>

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status IN ('DOWNLOADING', 'MERGING', 'EXPANDING')")
    suspend fun countInterruptedTasks(): Int

    @Query("UPDATE download_tasks SET status = 'PENDING', speedText = '', updatedAt = :updatedAt WHERE status IN ('DOWNLOADING', 'MERGING', 'EXPANDING')")
    suspend fun resetInterruptedTasks(updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = 'PAUSED', updatedAt = :updatedAt WHERE status IN ('PENDING', 'DOWNLOADING', 'MERGING', 'EXPANDING')")
    suspend fun pauseAllTasks(updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = 'PENDING', updatedAt = :updatedAt WHERE status = 'PAUSED'")
    suspend fun resumeAllTasks(updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    fun getTaskByIdFlow(id: String): Flow<DownloadTaskEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<DownloadTaskEntity>)

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query("UPDATE download_tasks SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: TaskStatus, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = :status, updatedAt = :updatedAt WHERE id = :id AND status NOT IN ('COMPLETED', 'CANCELLED')")
    suspend fun updateStatusIfNotTerminal(id: String, status: TaskStatus, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE download_tasks 
        SET progressPercent = :progress, 
            downloadedBytes = :downloadedBytes, 
            totalBytes = :totalBytes, 
            videoBytes = :videoBytes,
            audioBytes = :audioBytes,
            activeConnections = :activeConnections,
            segmentProgressCsv = :segmentProgressCsv,
            speedText = :speed, 
            etaSeconds = :eta, 
            status = :status, 
            updatedAt = :updatedAt 
        WHERE id = :id
    """)
    suspend fun updateProgress(
        id: String,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        speed: String,
        eta: Long,
        status: TaskStatus,
        videoBytes: Long = 0L,
        audioBytes: Long = 0L,
        activeConnections: Int = 8,
        segmentProgressCsv: String = "",
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE download_tasks 
        SET status = 'COMPLETED', 
            progressPercent = 100.0, 
            totalBytes = CASE WHEN :realSizeBytes > 0 THEN :realSizeBytes ELSE totalBytes END,
            downloadedBytes = CASE WHEN :realSizeBytes > 0 THEN :realSizeBytes ELSE downloadedBytes END,
            speedText = '',
            activeConnections = 0,
            segmentProgressCsv = '',
            outputFileUri = :fileUri, 
            outputFilePath = :filePath, 
            updatedAt = :updatedAt 
        WHERE id = :id
    """)
    suspend fun markCompleted(
        id: String, 
        fileUri: String, 
        filePath: String, 
        realSizeBytes: Long, 
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun getCompletedTasks(): List<DownloadTaskEntity>

    @Query("""
        UPDATE download_tasks
        SET totalBytes = :realSizeBytes,
            downloadedBytes = :realSizeBytes
        WHERE id = :id AND status = 'COMPLETED' AND :realSizeBytes > 0
    """)
    suspend fun correctCompletedSize(id: String, realSizeBytes: Long)

    @Query("UPDATE download_tasks SET status = 'FAILED', errorMessage = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markFailed(id: String, error: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = 'PENDING', progressPercent = 0.0, downloadedBytes = 0, speedText = '', activeConnections = 0, segmentProgressCsv = '', errorMessage = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun resetForRetry(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()
}
