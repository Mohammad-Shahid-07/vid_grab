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
        ORDER BY 
            CASE status 
                WHEN 'DOWNLOADING' THEN 1 
                WHEN 'MERGING' THEN 2 
                WHEN 'EXPANDING' THEN 3 
                WHEN 'PENDING' THEN 4 
                WHEN 'PAUSED' THEN 5 
                WHEN 'FAILED' THEN 6 
                WHEN 'COMPLETED' THEN 7 
                ELSE 8 
            END ASC, 
            createdAt ASC
    """)
    fun getAllTasksFlow(): Flow<List<DownloadTaskEntity>>

    @Query("""
        SELECT * FROM download_tasks 
        WHERE status IN ('DOWNLOADING', 'MERGING', 'EXPANDING', 'PENDING') 
        ORDER BY 
            CASE status 
                WHEN 'DOWNLOADING' THEN 1 
                WHEN 'MERGING' THEN 2 
                WHEN 'EXPANDING' THEN 3 
                ELSE 4 
            END ASC, 
            createdAt ASC
    """)
    fun getActiveTasksFlow(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED' ORDER BY updatedAt DESC")
    fun getCompletedTasksFlow(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = 'PENDING' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextPendingTask(): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = 'PENDING' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getNextPendingTasks(limit: Int): List<DownloadTaskEntity>

    @Query("UPDATE download_tasks SET status = 'PAUSED', updatedAt = :updatedAt WHERE status IN ('PENDING', 'DOWNLOADING', 'MERGING')")
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

    @Query("""
        UPDATE download_tasks 
        SET progressPercent = :progress, 
            downloadedBytes = :downloadedBytes, 
            totalBytes = :totalBytes, 
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
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE download_tasks 
        SET status = 'COMPLETED', 
            progressPercent = 100.0, 
            totalBytes = :realSizeBytes, 
            downloadedBytes = :realSizeBytes, 
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

    @Query("UPDATE download_tasks SET status = 'FAILED', errorMessage = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markFailed(id: String, error: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = 'PENDING', progressPercent = 0.0, errorMessage = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun resetForRetry(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()
}
