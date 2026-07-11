package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "video_items")
data class VideoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaStoreId: Long,
    val uriString: String,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val dateAdded: Long?,
    val dateModified: Long?,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = System.currentTimeMillis(),
    val processedAt: Long? = null,
    val archivedAt: Long? = null,
    val readyToDeleteAt: Long? = null,
    val trashedAt: Long? = null,
    val deleteRequestedAt: Long? = null,
    val mediaAccessScope: String = "unknown", // full, limited, unknown
    val status: String = "new", // new, queued, processing, processed, error, archived, ready_to_delete
    val lastCompletedStep: String = "discovered", // discovered, metadata_indexed, frames_sampled, ocr_complete, transcript_notes_available, llm_attempted, fallback_generated, result_saved
    val errorMessage: String? = null,
    val manualTranscript: String? = null // For user to manually edit transcript / notes
)

@Entity(
    tableName = "extracted_frames",
    foreignKeys = [
        ForeignKey(
            entity = VideoItem::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["videoId"])]
)
data class ExtractedFrame(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: Long,
    val timestampMs: Long,
    val ocrText: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "analysis_results",
    foreignKeys = [
        ForeignKey(
            entity = VideoItem::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["videoId"])]
)
data class AnalysisResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: Long,
    val descriptionLine1: String,
    val descriptionLine2: String,
    val categoryLevel1: String, // Technical / Non-technical
    val categoryLevel2: String,
    val categoryLevel3: String,
    val formattedActionPlan: String,
    val urls: List<String>,
    val codeOrCommands: List<String>,
    val evidenceJson: String, // OCR evidence timestamps/notes stringified
    val source: String, // ollama, fallback, edited
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "scan_runs")
data class ScanRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val videosFound: Int = 0,
    val videosAdded: Int = 0,
    val videosUpdated: Int = 0,
    val accessScope: String = "unknown", // full, limited, denied, unknown
    val status: String = "running", // running, finished, error
    val errorMessage: String? = null
)

@Entity(tableName = "processing_runs")
data class ProcessingRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val status: String = "running", // running, paused, finished, error
    val currentStep: String = "",
    val errorMessage: String? = null
)

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val ollamaEnabled: Boolean = false,
    val ollamaEndpointUrl: String = "http://10.0.2.2:11434/api/generate",
    val ollamaModelName: String = "gemma3",
    val requestTimeoutSeconds: Int = 30,
    val confirmedNonLocalEndpointWarning: Boolean = false
)

class Converters {
    @TypeConverter
    fun fromList(list: List<String>?): String? = list?.joinToString("\n")

    @TypeConverter
    fun toList(str: String?): List<String>? = str?.split("\n")?.filter { it.isNotEmpty() }
}

@Dao
interface VideoItemDao {
    @Query("SELECT * FROM video_items ORDER BY lastScannedAt DESC")
    fun getAllVideos(): Flow<List<VideoItem>>

    @Query("SELECT * FROM video_items WHERE id = :id LIMIT 1")
    fun getVideoById(id: Long): Flow<VideoItem?>

    @Query("SELECT * FROM video_items WHERE id = :id LIMIT 1")
    suspend fun getVideoByIdDirect(id: Long): VideoItem?

    @Query("SELECT * FROM video_items WHERE mediaStoreId = :mediaStoreId LIMIT 1")
    suspend fun getVideoByMediaStoreId(mediaStoreId: Long): VideoItem?

    @Query("SELECT * FROM video_items WHERE uriString = :uriString LIMIT 1")
    suspend fun getVideoByUri(uriString: String): VideoItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoItem): Long

    @Update
    suspend fun updateVideo(video: VideoItem)

    @Delete
    suspend fun deleteVideo(video: VideoItem)

    @Query("DELETE FROM video_items")
    suspend fun deleteAllVideos()
}

@Dao
interface ExtractedFrameDao {
    @Query("SELECT * FROM extracted_frames WHERE videoId = :videoId ORDER BY timestampMs ASC")
    fun getFramesForVideo(videoId: Long): Flow<List<ExtractedFrame>>

    @Query("SELECT * FROM extracted_frames WHERE videoId = :videoId ORDER BY timestampMs ASC")
    suspend fun getFramesForVideoDirect(videoId: Long): List<ExtractedFrame>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFrame(frame: ExtractedFrame): Long

    @Query("DELETE FROM extracted_frames WHERE videoId = :videoId")
    suspend fun deleteFramesForVideo(videoId: Long)
}

@Dao
interface AnalysisResultDao {
    @Query("SELECT * FROM analysis_results")
    fun getAllAnalysis(): Flow<List<AnalysisResult>>

    @Query("SELECT * FROM analysis_results WHERE videoId = :videoId LIMIT 1")
    fun getAnalysisForVideo(videoId: Long): Flow<AnalysisResult?>

    @Query("SELECT * FROM analysis_results WHERE videoId = :videoId LIMIT 1")
    suspend fun getAnalysisForVideoDirect(videoId: Long): AnalysisResult?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysis(analysis: AnalysisResult): Long

    @Query("DELETE FROM analysis_results WHERE videoId = :videoId")
    suspend fun deleteAnalysisForVideo(videoId: Long)
}

@Dao
interface ScanRunDao {
    @Query("SELECT * FROM scan_runs ORDER BY startedAt DESC")
    fun getScanRuns(): Flow<List<ScanRun>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScanRun(run: ScanRun): Long

    @Update
    suspend fun updateScanRun(run: ScanRun)
}

@Dao
interface ProcessingRunDao {
    @Query("SELECT * FROM processing_runs ORDER BY startedAt DESC")
    fun getProcessingRuns(): Flow<List<ProcessingRun>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProcessingRun(run: ProcessingRun): Long

    @Update
    suspend fun updateProcessingRun(run: ProcessingRun)
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun getSettings(): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsDirect(): AppSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: AppSettings)
}

@Database(
    entities = [
        VideoItem::class,
        ExtractedFrame::class,
        AnalysisResult::class,
        ScanRun::class,
        ProcessingRun::class,
        AppSettings::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class VideoDatabase : RoomDatabase() {
    abstract fun videoItemDao(): VideoItemDao
    abstract fun extractedFrameDao(): ExtractedFrameDao
    abstract fun analysisResultDao(): AnalysisResultDao
    abstract fun scanRunDao(): ScanRunDao
    abstract fun processingRunDao(): ProcessingRunDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: VideoDatabase? = null

        fun getDatabase(context: android.content.Context): VideoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VideoDatabase::class.java,
                    "video_knowledge_sorter_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
