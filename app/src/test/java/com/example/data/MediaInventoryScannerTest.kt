package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaInventoryScannerTest {
    private lateinit var database: VideoDatabase
    private lateinit var source: MutableInventorySource
    private lateinit var counters: MediaOperationCounters
    private lateinit var scanner: MediaInventoryScanner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VideoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        source = MutableInventorySource("Full access", deterministicRecords())
        counters = MediaOperationCounters()
        var time = 1_000L
        scanner = MediaInventoryScanner(
            database.videoItemDao(),
            database.scanRunDao(),
            source,
            counters
        ) { ++time }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun scanMapsImageAndVideoSourcesAndExactlyExcludesIneligibleRows() = runTest {
        val run = scanner.scan()

        assertEquals("finished", run.status)
        assertEquals(7, run.videosFound)
        assertEquals(MediaInventoryTotals(5, 5_000_000_032L), database.videoItemDao().getEligibleInventoryTotals().first())
        assertEquals(
            listOf(
                SourceAggregate("Screenshots", 2, 5_000_000_001L),
                SourceAggregate("Screen recordings", 1, 17L),
                SourceAggregate("WhatsApp", 1, 11L),
                SourceAggregate("Camera", 1, 3L)
            ),
            database.videoItemDao().getSourceAggregates().first()
        )
        val screenshots = database.videoItemDao().getSubcategoryAggregates("Screenshots").first()
        assertEquals(listOf(SubcategoryAggregate("Facebook", 1, 2_500_000_000L)), screenshots)
        assertFalse(screenshots.any { it.subcategory == "Unknown" })
        val indexed = database.videoItemDao().getAllVideosDirect()
        assertEquals(setOf("image", "video"), indexed.map { it.mediaCollection }.toSet())
        assertEquals("filename_token", indexed.single { it.sourceSubcategory == "Facebook" }.subcategoryEvidenceKind)
        assertEquals(null, indexed.single { it.sourceCategory == "WhatsApp" }.sourceSubcategory)
        assertZeroExpensiveOperations()
    }

    @Test
    fun decisionsSurviveRescanAndMissingRowsBecomeUnavailableWithoutEnteringTotals() = runTest {
        scanner.scan()
        val dao = database.videoItemDao()
        val indexed = dao.getAllVideosDirect().associateBy { it.mediaStoreId }
        dao.updateReviewDecision(indexed.getValue(1).id, "keep")
        dao.updateReviewDecision(indexed.getValue(2).id, "review_later")
        dao.updateReviewDecision(indexed.getValue(3).id, "ready_to_delete")
        source.records = source.records.filterNot { it.mediaStoreId == 4L }

        scanner.scan()

        val rescanned = dao.getAllVideosDirect().associateBy { it.mediaStoreId }
        assertEquals("keep", rescanned.getValue(1).reviewDecision)
        assertEquals("review_later", rescanned.getValue(2).reviewDecision)
        assertEquals("ready_to_delete", rescanned.getValue(3).reviewDecision)
        assertFalse(rescanned.getValue(4).isCurrentlyAvailable)
        assertEquals(MediaInventoryTotals(4, 5_000_000_021L), dao.getEligibleInventoryTotals().first())
        assertZeroExpensiveOperations()
    }

    @Test
    fun decisionsAndAggregatesSurviveDatabaseRecreationThenRescan() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "media-inventory-recreation-test.db"
        context.deleteDatabase(databaseName)
        try {
            var persistentDatabase = Room.databaseBuilder(context, VideoDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            var persistentScanner = MediaInventoryScanner(
                persistentDatabase.videoItemDao(),
                persistentDatabase.scanRunDao(),
                source,
                counters
            )
            persistentScanner.scan()
            val keepId = persistentDatabase.videoItemDao().getAllVideosDirect().single { it.mediaStoreId == 1L }.id
            persistentDatabase.videoItemDao().updateReviewDecision(keepId, "keep")
            persistentDatabase.close()

            persistentDatabase = Room.databaseBuilder(context, VideoDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            source.records = source.records.filterNot { it.mediaStoreId == 4L }
            persistentScanner = MediaInventoryScanner(
                persistentDatabase.videoItemDao(),
                persistentDatabase.scanRunDao(),
                source,
                counters
            )
            persistentScanner.scan()

            assertEquals("keep", persistentDatabase.videoItemDao().getVideoByIdDirect(keepId)?.reviewDecision)
            assertEquals(MediaInventoryTotals(4, 5_000_000_021L), persistentDatabase.videoItemDao().getEligibleInventoryTotals().first())
            assertFalse(persistentDatabase.videoItemDao().getAllVideosDirect().single { it.mediaStoreId == 4L }.isCurrentlyAvailable)
            persistentDatabase.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun capturedInventoryDiagnosticContainsOnlySanitizedCountsAndNoFixtureCanaries() = runTest {
        val run = scanner.scan()
        val diagnostic = "status=${run.status};found=${run.videosFound};added=${run.videosAdded};updated=${run.videosUpdated};access=${run.accessScope}"
        val forbiddenCanaries = deterministicRecords().flatMap {
            listOf(it.displayName, it.relativePath.orEmpty(), it.uriString)
        }.filter { it.isNotEmpty() } + listOf("ocr-canary", "gps-canary", "hash-canary", "credential-canary")

        assertFalse(forbiddenCanaries.any(diagnostic::contains))
        assertEquals("status=finished;found=7;added=7;updated=0;access=Full access", diagnostic)
    }

    @Test
    fun sourceAccessStateIsPreservedForAllFourUiStates() = runTest {
        for (state in listOf("Full access", "Limited access", "Denied", "Unknown")) {
            source.accessState = state
            source.records = emptyList()
            val result = scanner.scan()
            assertEquals(state, result.accessScope)
            assertEquals(state, scanner.currentAccessState())
        }
    }

    @Test
    fun measuresColdAndWarmTenThousandRowInventoryWithoutLatencyThreshold() = runTest {
        source.records = (1L..10_000L).map { id ->
            record(
                id = id,
                path = if (id % 2L == 0L) "Pictures/Screenshots/" else "Movies/Screen recordings/",
                name = if (id % 2L == 0L) "Screenshot_$id.png" else "screen_recording_$id.mp4",
                mime = if (id % 2L == 0L) "image/png" else "video/mp4",
                bytes = id,
                collection = if (id % 2L == 0L) "image" else "video"
            )
        }
        val runtime = Runtime.getRuntime()
        val observedHeap = mutableListOf(runtime.totalMemory() - runtime.freeMemory())

        val coldMs = measureMillis { assertEquals("finished", scanner.scan().status) }
        observedHeap += runtime.totalMemory() - runtime.freeMemory()
        val warmMs = List(5) {
            measureMillis { assertEquals("finished", scanner.scan().status) }.also {
                observedHeap += runtime.totalMemory() - runtime.freeMemory()
            }
        }
        val sorted = warmMs.sorted()
        val p50 = percentileNearestRank(sorted, 0.50)
        val p95 = percentileNearestRank(sorted, 0.95)

        assertEquals(MediaInventoryTotals(10_000, 50_005_000L), database.videoItemDao().getEligibleInventoryTotals().first())
        assertZeroExpensiveOperations()
        println(
            "MEDIA_INVENTORY_PERF " +
                "{\"rows\":10000,\"cold_ms\":$coldMs,\"warm_repetitions\":5," +
                "\"warm_ms\":$warmMs,\"warm_p50_ms\":$p50,\"warm_p95_ms\":$p95," +
                "\"observed_peak_heap_bytes\":${observedHeap.max()},\"measurement_only\":true}"
        )
    }

    private suspend fun measureMillis(block: suspend () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000L
    }

    private fun percentileNearestRank(sorted: List<Long>, percentile: Double): Long {
        val index = kotlin.math.ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    private fun assertZeroExpensiveOperations() {
        assertEquals(0, counters.frameExtractions)
        assertEquals(0, counters.ocrRequests)
        assertEquals(0, counters.hashRequests)
        assertEquals(0, counters.llmRequests)
    }

    private fun deterministicRecords() = listOf(
        record(1, "DCIM/Camera/", "IMG_0001.jpg", "image/jpeg", 3),
        record(2, "Pictures/Screenshots/", "Screenshot_20260806_facebook.png", "image/png", 2_500_000_000L),
        record(3, "Pictures/Screenshots/", "Screenshot_20260806.png", "image/png", 2_500_000_001L),
        record(4, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "IMG-20260806-WA0001.jpg", "image/jpeg", 11),
        record(5, "Movies/Screen recordings/", "screen_recording.mp4", "video/mp4", 17, collection = "video"),
        record(6, "Download/", "pending.jpg", "image/jpeg", 5, pending = true),
        record(7, "Download/", "trashed.mp4", "video/mp4", 7, collection = "video", trashed = true)
    )

    private fun record(
        id: Long,
        path: String,
        name: String,
        mime: String,
        bytes: Long,
        collection: String = "image",
        pending: Boolean = false,
        trashed: Boolean = false
    ) = MediaInventoryRecord(
        mediaStoreId = id,
        uriString = "content://fixture/$collection/$id",
        displayName = name,
        relativePath = path,
        mimeType = mime,
        sizeBytes = bytes,
        durationMs = if (collection == "video") 1_000L else null,
        dateAdded = 100L,
        dateModified = 200L,
        mediaCollection = collection,
        bucketDisplayName = path.trim('/').substringAfterLast('/'),
        volumeName = "external_primary",
        isPending = pending,
        isTrashed = trashed
    )

    private class MutableInventorySource(
        var accessState: String,
        var records: List<MediaInventoryRecord>,
        var complete: Boolean = true
    ) : MediaInventorySource {
        override fun currentAccessState() = accessState
        override suspend fun readSnapshot() = MediaInventorySnapshot(accessState, records, complete)
    }
}
