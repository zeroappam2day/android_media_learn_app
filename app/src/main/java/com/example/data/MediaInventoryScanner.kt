package com.example.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class MediaPermissionSnapshot(
    val sdkInt: Int,
    val imagesGranted: Boolean = false,
    val videosGranted: Boolean = false,
    val selectedGranted: Boolean = false,
    val legacyGranted: Boolean = false
)

object MediaAccessStateResolver {
    fun resolve(snapshot: MediaPermissionSnapshot): String = when {
        snapshot.sdkInt >= Build.VERSION_CODES.TIRAMISU && snapshot.imagesGranted && snapshot.videosGranted -> "Full access"
        snapshot.sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (snapshot.selectedGranted || snapshot.imagesGranted || snapshot.videosGranted) -> "Limited access"
        snapshot.sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            (snapshot.imagesGranted || snapshot.videosGranted) -> "Limited access"
        snapshot.sdkInt >= Build.VERSION_CODES.TIRAMISU -> "Denied"
        snapshot.legacyGranted -> "Full access"
        else -> "Denied"
    }
}

data class MediaInventoryRecord(
    val mediaStoreId: Long,
    val uriString: String,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val dateAdded: Long?,
    val dateModified: Long?,
    val mediaCollection: String,
    val bucketDisplayName: String?,
    val volumeName: String?,
    val isPending: Boolean,
    val isTrashed: Boolean
)

data class MediaInventorySnapshot(
    val accessState: String,
    val records: List<MediaInventoryRecord>,
    val complete: Boolean
)

interface MediaInventorySource {
    fun currentAccessState(): String
    suspend fun readSnapshot(): MediaInventorySnapshot
}

class FolderScopeException : IllegalArgumentException(
    "This folder cannot be searched here. Choose a folder in internal storage or on an SD card."
)

data class MediaFolderScope(val volume: String, val relativePath: String) {
    fun contains(record: MediaInventoryRecord): Boolean {
        val recordVolume = record.volumeName?.lowercase() ?: return false
        val sameVolume = if (volume == "primary") recordVolume in setOf("external_primary", "external") else recordVolume == volume
        val path = record.relativePath?.trim('/')?.let { if (it.isEmpty()) "" else "$it/" } ?: return false
        return sameVolume && (relativePath.isEmpty() || path.startsWith(relativePath))
    }

    companion object {
        fun fromTreeUri(value: String): MediaFolderScope {
            val uri = Uri.parse(value)
            if (uri.scheme != "content" || uri.authority != "com.android.externalstorage.documents") throw FolderScopeException()
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: throw FolderScopeException()
            if (!documentId.contains(':')) throw FolderScopeException()
            val volume = documentId.substringBefore(':').lowercase()
            if (volume != "primary" && !volume.matches(Regex("[0-9a-f]{4}-[0-9a-f]{4}"))) throw FolderScopeException()
            val path = documentId.substringAfter(':').trim('/')
            if (path.split('/').any { it == "." || it == ".." }) throw FolderScopeException()
            return MediaFolderScope(volume, if (path.isEmpty()) "" else "$path/")
        }
    }
}

class FolderScopedInventorySource(
    private val source: MediaInventorySource,
    private val folders: () -> List<String>
) : MediaInventorySource {
    override fun currentAccessState(): String = source.currentAccessState()

    override suspend fun readSnapshot(): MediaInventorySnapshot {
        val scopes = folders().map(MediaFolderScope::fromTreeUri)
        val snapshot = source.readSnapshot()
        return if (scopes.isEmpty()) snapshot else snapshot.copy(records = snapshot.records.filter { record -> scopes.any { it.contains(record) } })
    }
}

class AndroidMediaInventorySource(private val context: Context) : MediaInventorySource {
    override fun currentAccessState(): String = MediaAccessStateResolver.resolve(
        MediaPermissionSnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            imagesGranted = granted(Manifest.permission.READ_MEDIA_IMAGES),
            videosGranted = granted(Manifest.permission.READ_MEDIA_VIDEO),
            selectedGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            legacyGranted = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
    )

    override suspend fun readSnapshot(): MediaInventorySnapshot = withContext(Dispatchers.IO) {
        val records = mutableListOf<MediaInventoryRecord>()
        var complete = true
        val collections = listOf(
            Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", MediaStore.Video.Media.DURATION),
            Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", null)
        )

        for ((collectionUri, collectionName, durationColumnName) in collections) {
            val projection = mutableListOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection += MediaStore.MediaColumns.RELATIVE_PATH
                projection += MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
                projection += MediaStore.MediaColumns.VOLUME_NAME
                projection += MediaStore.MediaColumns.IS_PENDING
            } else {
                projection += MediaStore.MediaColumns.DATA
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) projection += MediaStore.MediaColumns.IS_TRASHED
            if (durationColumnName != null) projection += durationColumnName

            val cursor = context.contentResolver.query(
                collectionUri,
                projection.toTypedArray(),
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )
            if (cursor == null) {
                complete = false
                continue
            }
            cursor.use {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val durationColumn = durationColumnName?.let(cursor::getColumnIndexOrThrow) ?: -1
                val pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val legacyPathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val bucketColumn = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val volumeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                val pendingColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                val trashedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val legacyFile = legacyPathColumn.takeIf { it >= 0 }?.let(cursor::getString)
                    val primaryRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
                    val legacyPrimaryPath = legacyFile?.takeIf { it.startsWith(primaryRoot) }?.removePrefix(primaryRoot)?.substringBeforeLast('/', "")
                    records += MediaInventoryRecord(
                        mediaStoreId = id,
                        uriString = Uri.withAppendedPath(collectionUri, id.toString()).toString(),
                        displayName = cursor.getString(nameColumn) ?: "Media_$id",
                        relativePath = pathColumn.takeIf { it >= 0 }?.let(cursor::getString) ?: legacyPrimaryPath?.let { "$it/" },
                        mimeType = cursor.getString(mimeColumn),
                        sizeBytes = cursor.getLong(sizeColumn),
                        durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L,
                        dateAdded = cursor.getLong(addedColumn),
                        dateModified = cursor.getLong(modifiedColumn),
                        mediaCollection = collectionName,
                        bucketDisplayName = bucketColumn.takeIf { it >= 0 }?.let(cursor::getString),
                        volumeName = volumeColumn.takeIf { it >= 0 }?.let(cursor::getString) ?: legacyPrimaryPath?.let { "external_primary" },
                        isPending = pendingColumn >= 0 && cursor.getInt(pendingColumn) != 0,
                        isTrashed = trashedColumn >= 0 && cursor.getInt(trashedColumn) != 0
                    )
                }
            }
        }
        MediaInventorySnapshot(currentAccessState(), records, complete)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

class MediaOperationCounters {
    private val frameExtractionsValue = AtomicInteger()
    private val ocrRequestsValue = AtomicInteger()
    private val hashRequestsValue = AtomicInteger()
    private val llmRequestsValue = AtomicInteger()

    val frameExtractions: Int get() = frameExtractionsValue.get()
    val ocrRequests: Int get() = ocrRequestsValue.get()
    val hashRequests: Int get() = hashRequestsValue.get()
    val llmRequests: Int get() = llmRequestsValue.get()

    fun recordFrameExtraction() { frameExtractionsValue.incrementAndGet() }
    fun recordOcrRequest() { ocrRequestsValue.incrementAndGet() }
    fun recordHashRequest() { hashRequestsValue.incrementAndGet() }
    fun recordLlmRequest() { llmRequestsValue.incrementAndGet() }
}

class MediaInventoryScanner(
    private val videoDao: VideoItemDao,
    private val scanDao: ScanRunDao,
    private val source: MediaInventorySource,
    val operationCounters: MediaOperationCounters = MediaOperationCounters(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var lastScanToken = 0L

    fun currentAccessState(): String = source.currentAccessState()

    suspend fun scan(admitNew: (MediaInventoryRecord) -> Boolean = { true }): ScanRun = withContext(Dispatchers.IO) {
        val startedAt = clock()
        val scanToken = nextScanToken(startedAt)
        val snapshot = source.readSnapshot()
        val scanRunId = scanDao.insertScanRun(
            ScanRun(startedAt = startedAt, accessScope = snapshot.accessState, status = "running")
        )
        var added = 0
        var updated = 0

        try {
            val existingByUri = videoDao.getAllVideosDirect().associateBy { it.uriString }
            val newItems = ArrayList<VideoItem>(snapshot.records.size)
            val existingUpdates = ArrayList<VideoItem>(snapshot.records.size)

            for (record in snapshot.records) {
                val classification = MediaSourceClassifier.classify(
                    record.relativePath,
                    record.bucketDisplayName,
                    record.displayName
                )
                val now = clock()
                val existing = existingByUri[record.uriString]
                if ((existing == null || existing.isPending) && !admitNew(record)) {
                    if (existing != null) existingUpdates += existing.updatedFrom(record, snapshot.accessState, classification, now, scanToken)
                    continue
                }
                if (existing == null) {
                    newItems += record.toVideoItem(snapshot.accessState, classification, now, scanToken)
                    added++
                } else {
                    val changed = existing.dateModified != record.dateModified ||
                        existing.relativePath != record.relativePath ||
                        existing.sourceCategory != classification.category ||
                        existing.sourceSubcategory != classification.subcategory ||
                        !existing.isCurrentlyAvailable ||
                        existing.isPending != record.isPending ||
                        existing.isTrashed != record.isTrashed
                    existingUpdates += existing.updatedFrom(record, snapshot.accessState, classification, now, scanToken)
                    if (changed) updated++
                }
            }

            if (newItems.isNotEmpty()) videoDao.insertVideos(newItems)
            if (existingUpdates.isNotEmpty()) videoDao.updateVideos(existingUpdates)
            if (snapshot.complete) videoDao.markMissingMediaUnavailable(scanToken)

            val completedRun = ScanRun(
                id = scanRunId,
                startedAt = startedAt,
                finishedAt = clock(),
                videosFound = snapshot.records.size,
                videosAdded = added,
                videosUpdated = updated,
                accessScope = snapshot.accessState,
                status = "finished"
            )
            scanDao.updateScanRun(completedRun)
            completedRun
        } catch (_: Exception) {
            val failedRun = ScanRun(
                id = scanRunId,
                startedAt = startedAt,
                finishedAt = clock(),
                videosFound = snapshot.records.size,
                videosAdded = added,
                videosUpdated = updated,
                accessScope = snapshot.accessState,
                status = "error",
                errorMessage = "Media inventory scan failed"
            )
            scanDao.updateScanRun(failedRun)
            failedRun
        }
    }

    @Synchronized
    private fun nextScanToken(candidate: Long): Long {
        lastScanToken = maxOf(candidate, lastScanToken + 1L)
        return lastScanToken
    }
}

private fun MediaInventoryRecord.toVideoItem(
    accessState: String,
    classification: MediaSourceClassification,
    now: Long,
    scanToken: Long
) = VideoItem(
    mediaStoreId = mediaStoreId,
    uriString = uriString,
    displayName = displayName,
    relativePath = relativePath,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    dateAdded = dateAdded,
    dateModified = dateModified,
    mediaAccessScope = accessState,
    status = "new",
    mediaCollection = mediaCollection,
    bucketDisplayName = bucketDisplayName,
    volumeName = volumeName,
    sourceCategory = classification.category,
    sourceSubcategory = classification.subcategory,
    categoryEvidenceKind = classification.categoryEvidenceKind,
    subcategoryEvidenceKind = classification.subcategoryEvidenceKind,
    categoryConfidence = classification.categoryConfidence,
    subcategoryConfidence = classification.subcategoryConfidence,
    classificationRuleVersion = classification.ruleVersion,
    classifiedAt = now,
    isPending = isPending,
    isTrashed = isTrashed,
    isCurrentlyAvailable = true,
    lastSeenScanToken = scanToken
)

private fun VideoItem.updatedFrom(
    record: MediaInventoryRecord,
    accessState: String,
    classification: MediaSourceClassification,
    now: Long,
    scanToken: Long
) = copy(
    displayName = record.displayName,
    relativePath = record.relativePath,
    mimeType = record.mimeType,
    sizeBytes = record.sizeBytes,
    durationMs = record.durationMs,
    dateAdded = record.dateAdded,
    dateModified = record.dateModified,
    lastScannedAt = now,
    mediaAccessScope = accessState,
    mediaCollection = record.mediaCollection,
    bucketDisplayName = record.bucketDisplayName,
    volumeName = record.volumeName,
    sourceCategory = classification.category,
    sourceSubcategory = classification.subcategory,
    categoryEvidenceKind = classification.categoryEvidenceKind,
    subcategoryEvidenceKind = classification.subcategoryEvidenceKind,
    categoryConfidence = classification.categoryConfidence,
    subcategoryConfidence = classification.subcategoryConfidence,
    classificationRuleVersion = classification.ruleVersion,
    classifiedAt = now,
    isPending = record.isPending,
    isTrashed = record.isTrashed,
    isCurrentlyAvailable = true,
    lastSeenScanToken = scanToken
)
