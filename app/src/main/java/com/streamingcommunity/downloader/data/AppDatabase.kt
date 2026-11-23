package com.streamingcommunity.downloader.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadJobDao {
    @Query("SELECT * FROM download_jobs ORDER BY createdAtMs DESC")
    fun observeAllJobs(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE status IN ('QUEUED', 'RESOLVING', 'DOWNLOADING_VIDEO', 'DOWNLOADING_AUDIO', 'DOWNLOADING_SUBTITLES', 'JOINING', 'REMUXING') ORDER BY createdAtMs ASC")
    suspend fun getActiveAndQueuedJobs(): List<DownloadJobEntity>

    @Query("SELECT * FROM download_jobs WHERE id = :id")
    suspend fun getById(id: Long): DownloadJobEntity?

    @Query("SELECT * FROM download_jobs WHERE source = :source AND externalId = :externalId")
    suspend fun getJobsForTitle(source: String, externalId: String): List<DownloadJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: DownloadJobEntity): Long

    @Update
    suspend fun update(job: DownloadJobEntity)

    @Query(
        """
        UPDATE download_jobs 
        SET status = :status,
            progressPercent = :progressPercent,
            completedSegments = :completedSegments,
            totalSegments = :totalSegments,
            downloadedBytes = :downloadedBytes,
            speedBytesPerSec = :speedBytesPerSec,
            etaSeconds = :etaSeconds,
            updatedAtMs = :updatedAtMs
        WHERE id = :id AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
        """
    )
    suspend fun updateProgress(
        id: Long,
        status: String,
        progressPercent: Int,
        completedSegments: Int,
        totalSegments: Int,
        downloadedBytes: Long,
        speedBytesPerSec: Long,
        etaSeconds: Long,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE download_jobs 
        SET status = :status,
            outputPath = :outputPath,
            errorMessage = :errorMessage,
            progressPercent = :progressPercent,
            speedBytesPerSec = 0,
            etaSeconds = 0,
            updatedAtMs = :updatedAtMs
        WHERE id = :id
        """
    )
    suspend fun updateFinalState(
        id: Long,
        status: String,
        outputPath: String?,
        errorMessage: String?,
        progressPercent: Int,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM download_jobs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_jobs WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinishedJobs()
}

@Dao
interface WatchDao {
    @Query("SELECT * FROM watches ORDER BY createdAtMs DESC")
    fun observeAllWatches(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches ORDER BY createdAtMs DESC")
    suspend fun getAllWatches(): List<WatchEntity>

    @Query("SELECT * FROM watches WHERE source = :source AND externalId = :externalId LIMIT 1")
    fun observeWatch(source: String, externalId: String): Flow<WatchEntity?>

    @Query("SELECT * FROM watches WHERE source = :source AND externalId = :externalId LIMIT 1")
    suspend fun getWatch(source: String, externalId: String): WatchEntity?

    @Query("SELECT * FROM watches WHERE id = :id")
    suspend fun getWatchById(id: Long): WatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatch(watch: WatchEntity): Long

    @Update
    suspend fun updateWatch(watch: WatchEntity)

    @Query("DELETE FROM watches WHERE id = :id")
    suspend fun deleteWatchById(id: Long)

    @Query("SELECT episodeKey FROM seen_episodes WHERE watchId = :watchId")
    suspend fun getSeenEpisodeKeys(watchId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeenEpisodes(episodes: List<SeenEpisodeEntity>)
}

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress")
    fun observeAllProgress(): Flow<List<PlaybackProgressEntity>>

    @Query("SELECT * FROM playback_progress WHERE mediaKey = :mediaKey LIMIT 1")
    suspend fun getProgress(mediaKey: String): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE mediaKey = :mediaKey")
    suspend fun deleteProgress(mediaKey: String)
}

@Database(
    entities = [
        DownloadJobEntity::class,
        WatchEntity::class,
        SeenEpisodeEntity::class,
        PlaybackProgressEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadJobDao(): DownloadJobDao
    abstract fun watchDao(): WatchDao
    abstract fun playbackProgressDao(): PlaybackProgressDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_progress` (
                        `mediaKey` TEXT NOT NULL,
                        `positionMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `playback_progress` ADD COLUMN `completed` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sc_downloader.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
