package com.example

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * DRAFT: production MainScreen/VideoViewModel with real populated MediaStore snapshots.
 * Holding a real snapshot controls timing only; no invented inventory records are returned.
 * No real credential store is constructed. Restart phases retain only uniquely owned fixture
 * bytes and a nonsecret receipt until the host actually force-stops the QA app.
 */
class InventoryUiGapJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val receipt get() = context.getSharedPreferences("qa71_inventory_receipt", 0)
    private val args get() = InstrumentationRegistry.getArguments()
    private var token = "QA71Inventory${System.nanoTime()}"
    private val owned = linkedMapOf<Uri, ByteArray>()
    private val tags = mutableListOf<Long>()
    private var oldFolders: Set<String>? = null
    private val oldFlags = linkedMapOf<String, Boolean?>()
    private var oldProvider: String? = null
    private var oldSettings: AppSettings? = null
    private var originalSettingsRow: JSONObject? = null
    private var entered = false
    private var retain = false
    private var vm: VideoViewModel? = null
    private var repo: VideoRepository? = null
    private val store = ViewModelStore()
    private val credentialName get() = "${token}_empty_credentials"
    private fun imageRoot() = "Pictures/$token/"
    private fun videoRoot() = "Movies/$token/"
    private fun tree(path: String) = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:${path.trimEnd('/')}").toString()

    @Before fun isolate() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(args.getString("rea_runtime") == "emulator")
        check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
        check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
        val phase = args.getString("qa71_inventory_phase")
        if (phase == "observer_verify" || phase == "observer_cleanup") {
            val value = JSONObject(requireNotNull(receipt.getString("payload", null)))
            token = value.getString("token"); check(token.matches(Regex("QA71Inventory[0-9]+")))
            val files = value.getJSONArray("owned")
            repeat(files.length()) { i -> val o = files.getJSONObject(i); owned[Uri.parse(o.getString("uri"))] = Base64.decode(o.getString("bytes"), Base64.NO_WRAP) }
            val old = value.getJSONObject("old")
            oldFolders = if (old.has("folders")) old.getJSONArray("folders").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } else null
            listOf("auto_ingest_enabled", "auto_ingest_screenshots", "auto_ingest_recordings").forEach { key -> oldFlags[key] = if (old.has(key)) old.getBoolean(key) else null }
            oldProvider = old.optString("provider").takeIf { old.has("provider") }
            oldSettings = io { db.appSettingsDao().getSettingsDirect() }
            originalSettingsRow = old.getJSONObject("settingsRow")
            check(io { dao.getAllVideosDirect().all { Uri.parse(it.uriString) in owned } })
        } else {
            check(!receipt.contains("payload")) { "Verify or clean the retained observer mission first" }
            check(io { dao.getAllVideosDirect().isEmpty() }) { "Empty isolated QA index required; preserve existing records" }
            oldFolders = prefs.getStringSet("target_folders", null)?.toSet()
            listOf("auto_ingest_enabled", "auto_ingest_screenshots", "auto_ingest_recordings").forEach { key -> oldFlags[key] = if (prefs.contains(key)) prefs.getBoolean(key, false) else null }
            oldProvider = prefs.getString("llm_provider", null)
            oldSettings = io { db.appSettingsDao().getSettingsDirect() }
            originalSettingsRow = snapshotSettings()
        }
        entered = true
        prefs.edit().putStringSet("target_folders", setOf(tree(imageRoot()), tree(videoRoot())))
            .putBoolean("auto_ingest_enabled", false).putBoolean("auto_ingest_screenshots", true)
            .putBoolean("auto_ingest_recordings", true).putString("llm_provider", "local").commit()
        io { db.appSettingsDao().insertSettings((oldSettings ?: AppSettings()).copy(ollamaEnabled = false)) }
    }

    @After fun cleanOwnedOnly() {
        if (!entered) return
        instrumentation.runOnMainSync { store.clear() }
        if (retain) return
        prefs.edit().putBoolean("auto_ingest_enabled", false).commit()
        io {
            dao.getAllVideosDirect().filter { Uri.parse(it.uriString) in owned }.forEach { row ->
                db.binDao().remove(row.id); db.extractedFrameDao().deleteFramesForVideo(row.id)
                db.analysisResultDao().deleteAnalysisForVideo(row.id); dao.deleteVideo(row)
            }
            tags.forEach { id -> db.customTagDao().observeTags().first().firstOrNull { it.id == id && it.name.startsWith(token) }?.let { db.customTagDao().delete(it) } }
            restoreSettings(requireNotNull(originalSettingsRow))
        }
        owned.forEach { (uri, _) -> assertOwnedUri(uri); context.contentResolver.delete(uri, null, null) }
        prefs.edit().apply {
            if (oldFolders == null) remove("target_folders") else putStringSet("target_folders", oldFolders)
            oldFlags.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
            if (oldProvider == null) remove("llm_provider") else putString("llm_provider", oldProvider)
        }.commit()
        context.deleteSharedPreferences(credentialName)
        receipt.edit().clear().commit()
    }

    @Test fun t014_t022_t023_populatedSnapshotRapidRefreshAndActualHomeResumeRemainDuplicateFree() {
        val image = create("image.png", png(), false)
        val video = create("video.mp4", instrumentation.context.assets.open("qa_no_text.mp4").use { it.readBytes() }, true)
        val ready = CountDownLatch(1); val release = CountDownLatch(1); val held = AtomicBoolean(true); val reads = AtomicInteger()
        val real = AndroidMediaInventorySource(context)
        mount(object : MediaInventorySource {
            override fun currentAccessState() = real.currentAccessState()
            override suspend fun readSnapshot(): MediaInventorySnapshot {
                val actual = real.readSnapshot(); reads.incrementAndGet()
                if (held.get()) { ready.countDown(); withContext(Dispatchers.IO) { check(release.await(15, TimeUnit.SECONDS)) { "Host UI test failed to release real snapshot" } } }
                return actual
            }
        })
        navigate("Sources")
        compose.onNodeWithTag("inventory_scan_button").performClick()
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        assertTrue(requireNotNull(vm).isScanning.value)
        compose.onNodeWithTag("inventory_scan_button").assertIsNotEnabled()
        compose.onNodeWithTag("inventory_scan_button").performTouchInput { repeat(5) { click(); advanceEventTime(20) } }
        assertEquals("Disabled rapid taps cannot start another populated read", 1, reads.get())
        val activity = compose.activity
        compose.activityRule.scenario.onActivity { it.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        await { activity.lifecycle.currentState == Lifecycle.State.CREATED }
        assertTrue("Search must still be running while Android Home is foreground", requireNotNull(vm).isScanning.value)
        context.startActivity(Intent(context, InventoryRecreationTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        await { activity.lifecycle.currentState == Lifecycle.State.RESUMED }
        held.set(false); release.countDown()
        await { !requireNotNull(vm).isScanning.value && ownedRows().size == 2 }
        assertTotals()
        val before = ownedRows().associateBy { it.uriString }
        val chosen = requireNotNull(before[image.toString()])
        val note = "QA71 preserved notes"; val tagName = "$token-tag"
        io {
            dao.updateVideo(chosen.copy(reviewDecision = "keep", manualTranscript = note))
            val tag = db.customTagDao().insert(CustomTag(name = tagName)); tags += tag
            db.customTagDao().assign(MediaTagCrossRef(chosen.id, tag))
        }
        repeat(2) {
            val count = reads.get(); compose.onNodeWithTag("inventory_scan_button").performClick()
            await { reads.get() > count && !requireNotNull(vm).isScanning.value }
            assertTotals()
            val current = ownedRows().associateBy { it.uriString }
            assertEquals(before.keys, current.keys)
            before.forEach { (uri, old) -> assertEquals(old.id, current.getValue(uri).id); assertEquals(old.mediaStoreId, current.getValue(uri).mediaStoreId) }
            assertEquals("keep", current.getValue(image.toString()).reviewDecision); assertEquals(note, current.getValue(image.toString()).manualTranscript)
            assertEquals(1, io { db.customTagDao().observeAssignments().first().count { it.mediaId == chosen.id && it.tagId in tags } })
            assertEquals(1, current.values.count { it.uriString == video.toString() })
        }
        assertBytes(); evidence("T014 T022 T023: real populated source, active Home boundary, single-flight, two refreshes and notes/tag/identity/bytes preserved")
    }

    @Test fun t186_visibleSourceFailureThenRealPopulatedRetryRestoresExactCategory() {
        val image = create("repair.png", png(), false)
        val fail = AtomicBoolean(false); val real = AndroidMediaInventorySource(context)
        mount(object : MediaInventorySource {
            override fun currentAccessState() = real.currentAccessState()
            override suspend fun readSnapshot(): MediaInventorySnapshot {
                if (fail.get()) throw java.io.IOException("QA71 controlled source failure")
                return real.readSnapshot()
            }
        })
        navigate("Sources")
        compose.onNodeWithTag("inventory_scan_button").performClick()
        await { ownedRows().size == 1 && !requireNotNull(vm).isScanning.value }
        val original = ownedRows().single()
        val retainedNote = "QA71 retry preserves original notes"
        io { dao.updateVideo(original.copy(reviewDecision = "keep", manualTranscript = retainedNote)) }
        fail.set(true)
        expectToast("Media scan failed. Check photo and video permissions and retry.") { compose.onNodeWithTag("inventory_scan_button").performClick() }
        assertEquals(original.id, ownedRows().single().id)
        assertEquals("keep", ownedRows().single().reviewDecision)
        assertEquals(retainedNote, ownedRows().single().manualTranscript); assertBytes()
        fail.set(false); compose.onNodeWithTag("inventory_scan_button").performClick()
        await { ownedRows().size == 1 && !requireNotNull(vm).isScanning.value }
        val row = ownedRows().single(); assertEquals(image.toString(), row.uriString); assertEquals("Camera", row.sourceCategory)
        assertEquals(original.id, row.id); assertEquals("new", row.status)
        assertEquals("keep", row.reviewDecision); assertEquals(retainedNote, row.manualTranscript)
        assertTotals(); compose.onNodeWithTag("source_category_Camera").performClick(); compose.onNodeWithTag("source_media_${row.id}").assertExists()
        assertBytes(); evidence("T186: actual visible failure toast, explicit retry, one real restored item/Camera category/count/bytes")
    }

    @Test fun t189_screenshotWhileBackgroundAndAfterActualHostRestartIsFoundOnce() {
        when (args.getString("qa71_inventory_phase")) {
            "observer_seed" -> {
                prefs.edit().putBoolean("auto_ingest_enabled", true).putBoolean("auto_ingest_screenshots", true).commit()
                mount(AndroidMediaInventorySource(context)); navigate("Home")
                val activity = compose.activity
                compose.activityRule.scenario.onActivity { it.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                await { activity.lifecycle.currentState == Lifecycle.State.CREATED }
                val first = create("Screenshot_${token}_background.png", png(), false, screenshots = true)
                SystemClock.sleep(4500)
                context.startActivity(Intent(context, InventoryRecreationTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                await { activity.lifecycle.currentState == Lifecycle.State.RESUMED }
                await { ownedRows().singleOrNull()?.uriString == first.toString() }
                assertDiscoveredOnly(); assertTotals(); assertBytes(); saveReceipt(); retain = true
                evidence("T189 seed: screenshot created after actual Android Home/onStop, discovered once after return; external QA force-stop now required")
            }
            "observer_verify" -> {
                check(args.getString("qa71_host_force_stop_confirmed") == "true")
                prefs.edit().putBoolean("auto_ingest_enabled", true).putBoolean("auto_ingest_screenshots", true).commit()
                mount(AndroidMediaInventorySource(context)); await { ownedRows().size == 1 }
                create("Screenshot_${token}_restart.png", png(), false, screenshots = true)
                await { ownedRows().size == 2 }
                assertDiscoveredOnly(); assertTotals(); assertBytes()
                evidence("T189 verify: new process observer sees new screenshot once, both remain New/unreviewed with no analysis/frames")
            }
            "observer_cleanup" -> evidence("Explicit retained observer cleanup")
            else -> org.junit.Assume.assumeTrue("Opt in with qa71_inventory_phase=observer_seed|observer_verify|observer_cleanup", false)
        }
    }

    private fun mount(source: MediaInventorySource) {
        check(vm == null)
        val credentials = context.getSharedPreferences(credentialName, 0); check(credentials.edit().clear().commit())
        repo = VideoRepository(context, mediaInventorySource = source, credentialPreferences = credentials)
        compose.runOnUiThread {
            vm = VideoViewModel(context.applicationContext as Application, requireNotNull(repo)); store.put("qa71-inventory", requireNotNull(vm))
            compose.activity.setContent { MaterialTheme { MainScreen(requireNotNull(vm), false, {}) } }
        }
        await { requireNotNull(vm).startupReady.value }
    }
    private fun create(name: String, bytes: ByteArray, video: Boolean, screenshots: Boolean = false): Uri {
        val path = (if (video) videoRoot() else imageRoot()) + if (screenshots) "Screenshots/" else "Camera/"
        val uri = requireNotNull(context.contentResolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token-$name"); put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        owned[uri] = bytes; context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
    private fun assertOwnedUri(uri: Uri) {
        check(uri in owned)
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { c ->
            check(c.moveToFirst()); check(c.getString(0).startsWith(token + "-")); check(c.getString(1).startsWith(imageRoot()) || c.getString(1).startsWith(videoRoot()))
        }
    }
    private fun png(): ByteArray = ByteArrayOutputStream().use { out -> Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); check(compress(Bitmap.CompressFormat.PNG,100,out)); recycle() }; out.toByteArray() }
    private fun ownedRows() = io { dao.getAllVideosDirect().filter { Uri.parse(it.uriString) in owned } }
    private fun assertBytes() = owned.forEach { (uri, bytes) -> assertOwnedUri(uri); assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
    private fun assertTotals() {
        navigate("Sources"); await { requireNotNull(vm).inventoryTotals.value.itemCount == owned.size.toLong() }
        assertEquals(owned.values.sumOf { it.size }.toLong(), requireNotNull(vm).inventoryTotals.value.totalBytes)
        compose.onNodeWithTag("inventory_total").assertTextEquals("${owned.size} available items • ${size(owned.values.sumOf { it.size }.toLong())}")
        assertEquals(owned.keys.map { it.toString() }.toSet(), ownedRows().map { it.uriString }.toSet())
    }
    private fun assertDiscoveredOnly() = ownedRows().forEach { row -> assertEquals("new",row.status); assertEquals("unreviewed",row.reviewDecision); assertNull(row.manualTranscript); assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(row.id) }); assertTrue(io { db.extractedFrameDao().getFramesForVideoDirect(row.id).isEmpty() }) }
    private fun navigate(label: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_${label.lowercase()}").performClick(); compose.waitForIdle()
    }
    private fun size(bytes: Long): String { if(bytes<1024L)return "$bytes B";var n=bytes.toDouble();val u=arrayOf("KB","MB","GB","TB");var i=-1;while(n>=1024&&i<u.lastIndex){n/=1024;i++};return String.format(Locale.getDefault(),if(n>=100)"%.0f %s" else "%.1f %s",n,u[i]) }
    private fun saveReceipt() {
        val old=JSONObject();oldFolders?.let{old.put("folders",JSONArray(it.toList()))};oldFlags.forEach{(k,v)->if(v!=null)old.put(k,v)};oldProvider?.let{old.put("provider",it)}
        old.put("settingsRow", requireNotNull(originalSettingsRow))
        val files=JSONArray();owned.forEach{(uri,bytes)->files.put(JSONObject().put("uri",uri.toString()).put("bytes",Base64.encodeToString(bytes,Base64.NO_WRAP)))}
        receipt.edit().putString("payload",JSONObject().put("token",token).put("owned",files).put("old",old).toString()).commit()
    }
    private fun snapshotSettings(): JSONObject {
        val result = JSONObject().put("exists", false)
        db.openHelper.readableDatabase.query("SELECT * FROM app_settings WHERE id=1").use { c ->
            if (c.moveToFirst()) {
                result.put("exists", true)
                c.columnNames.forEachIndexed { i, name ->
                    check(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                    val cell = JSONObject().put("type", c.getType(i))
                    when(c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cell.put("value", c.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> cell.put("value", c.getDouble(i))
                        android.database.Cursor.FIELD_TYPE_STRING -> cell.put("value", c.getString(i))
                        android.database.Cursor.FIELD_TYPE_BLOB -> cell.put("value", Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
                    }
                    result.put(name, cell)
                }
            }
        }
        return result
    }
    private fun restoreSettings(row: JSONObject) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM app_settings WHERE id=1")
        if (!row.getBoolean("exists")) return
        val names = row.keys().asSequence().filter { it != "exists" }.toList()
        check(names.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
        val values = names.map { name ->
            val cell = row.getJSONObject(name)
            when(cell.getInt("type")) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> cell.getLong("value")
                android.database.Cursor.FIELD_TYPE_FLOAT -> cell.getDouble("value")
                android.database.Cursor.FIELD_TYPE_STRING -> cell.getString("value")
                android.database.Cursor.FIELD_TYPE_BLOB -> Base64.decode(cell.getString("value"), Base64.NO_WRAP)
                else -> null
            }
        }.toTypedArray()
        sql.execSQL("INSERT INTO app_settings (${names.joinToString(",")}) VALUES (${names.joinToString(",") { "?" }})", values)
    }
    private fun expectToast(message: String, action: () -> Unit) {
        val e=instrumentation.uiAutomation.executeAndWaitForEvent(Runnable { action();compose.waitForIdle() },{ event->event.eventType==AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.packageName?.toString()==context.packageName && event.text.any{it.toString()==message}},10000)
        try { assertTrue(e.text.any{it.toString()==message}) } finally {e.recycle()}
    }
    private fun evidence(message:String){File(context.getExternalFilesDir(null),"qa71-inventory-$token-${args.getString("qa71_inventory_phase") ?: "runtime"}.txt").writeText(message)}
    private fun await(predicate:()->Boolean)=compose.waitUntil(15000,predicate)
    private fun <T> io(block:suspend()->T):T=runBlocking(Dispatchers.IO){block()}
}
