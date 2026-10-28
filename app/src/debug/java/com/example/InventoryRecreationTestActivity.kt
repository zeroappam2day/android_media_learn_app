package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.room.Room
import com.example.data.MediaInventoryTotals
import com.example.data.VideoDatabase
import com.example.data.VideoItem
import kotlinx.coroutines.runBlocking

/** Debug-only host used to prove production inventory UI state across real Activity recreation. */
class InventoryRecreationTestActivity : ComponentActivity() {
    private lateinit var database: VideoDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resetDatabaseOnNextCreate) {
            applicationContext.deleteDatabase(DATABASE_NAME)
            resetDatabaseOnNextCreate = false
        }
        database = Room.databaseBuilder(applicationContext, VideoDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            if (database.videoItemDao().getAllVideosDirect().isEmpty()) {
                database.videoItemDao().insertVideos(listOf(fixture(1, 8, "keep"), fixture(2, 13, "unreviewed")))
            }
        }
        setContent {
            val totals by database.videoItemDao().getEligibleInventoryTotals()
                .collectAsState(initial = MediaInventoryTotals(0, 0))
            val aggregates by database.videoItemDao().getSourceAggregates().collectAsState(initial = emptyList())
            val media by database.videoItemDao().getAllVideos().collectAsState(initial = emptyList())
            MaterialTheme {
                MediaSourcesScreen(
                    totals = totals,
                    aggregates = aggregates,
                    selectedCategory = null,
                    subcategories = emptyList(),
                    allMedia = media,
                    mediaAccess = "Full access",
                    isScanning = false,
                    onScan = {},
                    onSelectCategory = {},
                    onDecision = { _, _ -> }
                )
            }
        }
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }

    suspend fun databaseRowsForTest(): List<VideoItem> = database.videoItemDao().getAllVideosDirect()

    companion object {
        const val DATABASE_NAME = "media-inventory-ui-recreation-test.db"
        var resetDatabaseOnNextCreate = true

        private fun fixture(id: Long, bytes: Long, decision: String) = VideoItem(
            id = id,
            mediaStoreId = id,
            uriString = "content://synthetic/$id",
            displayName = "synthetic-$id",
            relativePath = "DCIM/Camera/",
            mimeType = if (id == 1L) "image/jpeg" else "video/mp4",
            sizeBytes = bytes,
            durationMs = if (id == 1L) null else 1_000,
            dateAdded = 1,
            dateModified = 1,
            reviewDecision = decision,
            mediaCollection = if (id == 1L) "image" else "video",
            sourceCategory = "Camera"
        )
    }
}
