package com.example.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val manualTranscript: String? = null, // For user to manually edit transcript / notes
    @ColumnInfo(defaultValue = "'unreviewed'") val reviewDecision: String = "unreviewed",
    @ColumnInfo(defaultValue = "'unknown'") val mediaCollection: String = "unknown",
    val bucketDisplayName: String? = null,
    val volumeName: String? = null,
    val sourceCategory: String? = null,
    val sourceSubcategory: String? = null,
    val categoryEvidenceKind: String? = null,
    val subcategoryEvidenceKind: String? = null,
    val categoryConfidence: String? = null,
    val subcategoryConfidence: String? = null,
    @ColumnInfo(defaultValue = "0") val classificationRuleVersion: Int = 0,
    val classifiedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val isPending: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isTrashed: Boolean = false,
    @ColumnInfo(defaultValue = "1") val isCurrentlyAvailable: Boolean = true,
    val lastSeenScanToken: Long? = null
)

data class MediaInventoryTotals(
    val itemCount: Long,
    val totalBytes: Long
)

data class SourceAggregate(
    val category: String,
    val itemCount: Long,
    val totalBytes: Long
)

data class SubcategoryAggregate(
    val subcategory: String,
    val itemCount: Long,
    val totalBytes: Long
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
    fun fromList(list: List<String>?): String? = list?.let { org.json.JSONArray(it).toString() }

    @TypeConverter
    fun toList(str: String?): List<String>? = str?.let {
        try {
            val array = org.json.JSONArray(it)
            (0 until array.length()).map(array::getString)
        } catch (_: org.json.JSONException) {
            it.split("\n").filter(String::isNotEmpty)
        }
    }
}

@Dao
interface VideoItemDao {
    @Query("SELECT * FROM video_items ORDER BY lastScannedAt DESC")
    fun getAllVideos(): Flow<List<VideoItem>>

    @Query("SELECT * FROM video_items")
    suspend fun getAllVideosDirect(): List<VideoItem>

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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVideos(videos: List<VideoItem>): List<Long>

    @Update
    suspend fun updateVideo(video: VideoItem)

    @Query("UPDATE video_items SET status = :status, errorMessage = :errorMessage, processedAt = :processedAt, lastCompletedStep = :lastCompletedStep WHERE id = :id")
    suspend fun updateProcessingState(id: Long, status: String, errorMessage: String?, processedAt: Long?, lastCompletedStep: String)

    @Update
    suspend fun updateVideos(videos: List<VideoItem>)

    @Delete
    suspend fun deleteVideo(video: VideoItem)

    @Query("DELETE FROM video_items")
    suspend fun deleteAllVideos()

    @Query("UPDATE video_items SET status = CASE WHEN reviewDecision = 'keep' THEN 'queued' ELSE 'new' END, errorMessage = NULL WHERE status = 'processing'")
    suspend fun requeueInterruptedProcessing(): Int

    @Query("UPDATE video_items SET manualTranscript = :notes WHERE id = :id")
    suspend fun updateManualNotes(id: Long, notes: String)

    @Query("UPDATE video_items SET status = 'new' WHERE id = :id AND status = 'queued' AND reviewDecision != 'keep'")
    suspend fun dequeueIfNotKept(id: Long)

    @Query("UPDATE video_items SET reviewDecision = :decision WHERE id = :id")
    suspend fun updateReviewDecision(id: Long, decision: String)

    @Query("UPDATE video_items SET status = 'queued' WHERE id IN (:ids) AND status IN ('new', 'error') AND reviewDecision = 'keep' AND isCurrentlyAvailable = 1 AND isPending = 0 AND isTrashed = 0")
    suspend fun enqueueEligibleVideos(ids: List<Long>): Int

    @Query("UPDATE video_items SET isCurrentlyAvailable = 0 WHERE id = :id")
    suspend fun markUnavailable(id: Long)

    @Query("UPDATE video_items SET isCurrentlyAvailable = 0 WHERE lastSeenScanToken IS NULL OR lastSeenScanToken != :scanToken")
    suspend fun markMissingMediaUnavailable(scanToken: Long)

    @Query("SELECT COUNT(*) AS itemCount, COALESCE(SUM(sizeBytes), 0) AS totalBytes FROM video_items WHERE isPending = 0 AND isTrashed = 0 AND isCurrentlyAvailable = 1")
    fun getEligibleInventoryTotals(): Flow<MediaInventoryTotals>

    @Query("SELECT COALESCE(sourceCategory, 'Other') AS category, COUNT(*) AS itemCount, COALESCE(SUM(sizeBytes), 0) AS totalBytes FROM video_items WHERE isPending = 0 AND isTrashed = 0 AND isCurrentlyAvailable = 1 GROUP BY COALESCE(sourceCategory, 'Other') ORDER BY totalBytes DESC, category COLLATE NOCASE ASC")
    fun getSourceAggregates(): Flow<List<SourceAggregate>>

    @Query("SELECT sourceSubcategory AS subcategory, COUNT(*) AS itemCount, COALESCE(SUM(sizeBytes), 0) AS totalBytes FROM video_items WHERE isPending = 0 AND isTrashed = 0 AND isCurrentlyAvailable = 1 AND COALESCE(sourceCategory, 'Other') = :category AND sourceSubcategory IS NOT NULL GROUP BY sourceSubcategory ORDER BY totalBytes DESC, subcategory COLLATE NOCASE ASC")
    fun getSubcategoryAggregates(category: String): Flow<List<SubcategoryAggregate>>
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
    @Query("SELECT * FROM analysis_results ORDER BY updatedAt ASC, id ASC")
    fun getAllAnalysis(): Flow<List<AnalysisResult>>

    @Query("SELECT * FROM analysis_results WHERE videoId = :videoId ORDER BY updatedAt DESC, id DESC LIMIT 1")
    fun getAnalysisForVideo(videoId: Long): Flow<AnalysisResult?>

    @Query("SELECT * FROM analysis_results WHERE videoId = :videoId ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun getAnalysisForVideoDirect(videoId: Long): AnalysisResult?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysisRow(analysis: AnalysisResult): Long

    @Transaction
    suspend fun insertAnalysis(analysis: AnalysisResult): Long {
        val previous = getAnalysisForVideoDirect(analysis.videoId)
        deleteAnalysisForVideo(analysis.videoId)
        return insertAnalysisRow(analysis.copy(id = previous?.id ?: analysis.id))
    }

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
        AppSettings::class,
        MediaRule::class,
        MediaRuleRevision::class,
        CustomTag::class,
        MediaTagCrossRef::class,
        RuleRun::class,
        RuleMatch::class,
        BinEntry::class
    ],
    version = 3,
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
    abstract fun mediaRuleDao(): MediaRuleDao
    abstract fun customTagDao(): CustomTagDao
    abstract fun ruleRunDao(): RuleRunDao
    abstract fun binDao(): BinDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE video_items ADD COLUMN reviewDecision TEXT NOT NULL DEFAULT 'unreviewed'")
                db.execSQL("ALTER TABLE video_items ADD COLUMN mediaCollection TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("ALTER TABLE video_items ADD COLUMN bucketDisplayName TEXT")
                db.execSQL("ALTER TABLE video_items ADD COLUMN volumeName TEXT")
                db.execSQL("ALTER TABLE video_items ADD COLUMN sourceCategory TEXT")
                db.execSQL("ALTER TABLE video_items ADD COLUMN sourceSubcategory TEXT")
                db.execSQL("ALTER TABLE video_items ADD COLUMN categoryEvidenceKind TEXT")
                db.execSQL("ALTER TABLE video_items ADD COLUMN subcategoryEvidenceKind TEXT")
                db.execSQL("ALTER TABLE video_items ADD COLUMN categoryConfidence TEXT")
                db.execSQL("ALTER TABLE video_items ADD COLUMN subcategoryConfidence TEXT")
                db.execSQL("ALTER TABLE video_items ADD COLUMN classificationRuleVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE video_items ADD COLUMN classifiedAt INTEGER")
                db.execSQL("ALTER TABLE video_items ADD COLUMN isPending INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE video_items ADD COLUMN isTrashed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE video_items ADD COLUMN isCurrentlyAvailable INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE video_items ADD COLUMN lastSeenScanToken INTEGER")
                db.execSQL("UPDATE video_items SET mediaCollection = CASE WHEN mimeType LIKE 'image/%' THEN 'image' WHEN mimeType LIKE 'video/%' THEN 'video' ELSE 'unknown' END")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `currentRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_rule_revisions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ruleId` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `definitionJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`ruleId`) REFERENCES `media_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_rule_revisions_ruleId` ON `media_rule_revisions` (`ruleId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_media_rule_revisions_ruleId_revision` ON `media_rule_revisions` (`ruleId`, `revision`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `custom_tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_tags_name` ON `custom_tags` (`name`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_tag_cross_ref` (`mediaId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, PRIMARY KEY(`mediaId`, `tagId`), FOREIGN KEY(`mediaId`) REFERENCES `video_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`tagId`) REFERENCES `custom_tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_tag_cross_ref_tagId` ON `media_tag_cross_ref` (`tagId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `rule_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ruleId` INTEGER, `ruleName` TEXT NOT NULL, `ruleRevision` INTEGER NOT NULL, `mode` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `status` TEXT NOT NULL, `matchCount` INTEGER NOT NULL, `matchBytes` INTEGER NOT NULL, `errorMessage` TEXT, FOREIGN KEY(`ruleId`) REFERENCES `media_rules`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rule_runs_ruleId` ON `rule_runs` (`ruleId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `rule_matches` (`runId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, `score` REAL, `evaluator` TEXT, `evidence` TEXT NOT NULL, PRIMARY KEY(`runId`, `mediaId`), FOREIGN KEY(`runId`) REFERENCES `rule_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`mediaId`) REFERENCES `video_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rule_matches_mediaId` ON `rule_matches` (`mediaId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `bin_entries` (`mediaId` INTEGER NOT NULL, `priorReviewDecision` TEXT NOT NULL, `sourceRunId` INTEGER, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`), FOREIGN KEY(`mediaId`) REFERENCES `video_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bin_entries_addedAt` ON `bin_entries` (`addedAt`)")
            }
        }

        @Volatile
        private var INSTANCE: VideoDatabase? = null

        fun getDatabase(context: android.content.Context): VideoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VideoDatabase::class.java,
                    "video_knowledge_sorter_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
