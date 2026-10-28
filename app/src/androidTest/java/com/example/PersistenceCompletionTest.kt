package com.example

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Opt-in phases: qa_phase=seed|verify|cleanup, rea_runtime=emulator.
 * Seed intentionally retains only its owned fixture and a nonsecret receipt. The HOST must
 * force-stop ONLY the .qa.preview target between completed seed and verify invocations.
 * Verify also requires qa_host_force_stop_confirmed=true. That argument and changed process
 * identity do not replace the host's actual command receipt: instrumentation can restart a
 * target itself. No code here force-stops its own instrumentation process or accesses keys.
 * Export intercepts CreateDocument's result to an owned URI: data bytes, not SAF route proof.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceCompletionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val resolver get() = context.contentResolver
    private val receipt get() = context.getSharedPreferences("qa_persistence_receipt", 0)
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val title = "QA title"
    private val detail = "QA detail"
    private val roadmap get() = if (receipt.getBoolean("longNote", false)) "QA step\nخطوة ثانية 😀" else "QA step"
    private fun note() = if (receipt.getBoolean("longNote", false)) "مرحبا 😀\n".repeat(1500).take(9998) + "終\n" else "QA saved"

    @Test fun phasedSavedFieldsFlagsAndSystemThemeSurviveHostForceStop() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        when (InstrumentationRegistry.getArguments().getString("qa_phase")) {
            "seed" -> seed()
            "verify" -> verify()
            "cleanup" -> cleanup()
            else -> error("Pass qa_phase=seed|verify|cleanup; no implicit phase or automatic cleanup")
        }
    }
    private fun seed() {
        check(!receipt.contains("token")) { "A retained persistence fixture already exists; verify or explicitly cleanup it first" }
        check(io { db.videoItemDao().getAllVideosDirect().isEmpty() }) { "Phased persistence requires an isolated empty QA index" }
        val token = "QAPersist" + UUID.randomUUID().toString().replace("-", "").take(12)
        val settings = io { db.appSettingsDao().getSettingsDirect() }
        val old = JSONObject().put("provider", prefs.getString("llm_provider", JSONObject.NULL.toString()))
            .put("auto", prefs.getBoolean("auto_ingest_enabled", false)).put("record", prefs.getBoolean("auto_ingest_recordings", true))
            .put("shots", prefs.getBoolean("auto_ingest_screenshots", true))
        prefs.getStringSet("target_folders", null)?.let { old.put("folders", org.json.JSONArray(it.toList())) }
        settings?.let { old.put("settings", JSONObject().put("enabled", it.ollamaEnabled).put("url", it.ollamaEndpointUrl).put("model", it.ollamaModelName).put("timeout", it.requestTimeoutSeconds).put("consent", it.confirmedNonLocalEndpointWarning)) }
        receipt.edit().putString("token", token).putString("old", old.toString()).putInt("seedPid", Process.myPid()).putLong("seedProcessStart", Process.getStartElapsedRealtime())
            .putBoolean("longNote", InstrumentationRegistry.getArguments().getString("qa_long_note") == "true").commit()
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.png"); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$token/Screenshots/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        receipt.edit().putString("uri", uri.toString()).putString("bytesSha256", digest(bytes)).commit()
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }; resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val id = io { db.videoItemDao().insertVideo(VideoItem(mediaStoreId = android.content.ContentUris.parseId(uri), uriString = uri.toString(), displayName = "$token.png",
            relativePath = "Pictures/$token/Screenshots/", mimeType = "image/png", sizeBytes = bytes.size.toLong(), durationMs = null, dateAdded = null, dateModified = null,
            status = "processed", reviewDecision = "keep", mediaCollection = "image", sourceCategory = "Screenshots", lastCompletedStep = "result_saved")) }
        receipt.edit().putLong("id", id).commit()
        io { db.analysisResultDao().insertAnalysis(AnalysisResult(videoId = id, descriptionLine1 = "Fixture initial", descriptionLine2 = "Fixture initial",
            categoryLevel1 = "QA", categoryLevel2 = "Testing", categoryLevel3 = "Review", formattedActionPlan = "Fixture initial", urls = emptyList(), codeOrCommands = emptyList(), evidenceJson = "[]", source = "edited")) }
        prefs.edit().putString("llm_provider", "local").putBoolean("auto_ingest_enabled", true).putBoolean("auto_ingest_recordings", true).putBoolean("auto_ingest_screenshots", false)
            .putStringSet("target_folders", setOf("content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:Pictures/$token"))).commit()
        io { db.appSettingsDao().insertSettings(AppSettings(ollamaEnabled = false, ollamaModelName = "qa-persistence-model", requestTimeoutSeconds = 17)) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            navigate("queue"); openAction("Preview"); val values = listOf(note(), "QA", "Testing", "Review", title, detail, roadmap)
            if (receipt.getBoolean("longNote", false)) assertEquals(10000, values[0].length) else assertEquals("QA saved", values[0])
            values.forEachIndexed { index, value -> fields()[index].performScrollTo().performTextReplacement(value) }
            compose.onNodeWithContentDescription("Save edits").performScrollTo().performClick()
            await { io { db.videoItemDao().getVideoByIdDirect(id)?.manualTranscript == note() && db.analysisResultDao().getAnalysisForVideoDirect(id)?.descriptionLine1 == title } }
            compose.onNodeWithContentDescription("Close").performClick()
            // Manual override is intentionally temporary. Verify phase expects current Android theme.
            compose.onNodeWithContentDescription("Toggle Theme").performClick()
            await {
                var applied = false
                scenario.onActivity { activity ->
                    val systemDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    applied = systemDark == WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars
                }
                applied
            }
            scenario.onActivity { activity ->
                val systemDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                assertEquals(systemDark, WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
            assertOwnedBytes()
        }
        receipt.edit().putBoolean("seedComplete", true).commit()
        writeEvidence("seed_complete_waiting_for_external_force_stop")
        // Deliberately no fixture cleanup: host process-stop verification is a separate invocation.
    }
    private fun verify() {
        check(receipt.getBoolean("seedComplete", false)) { "Complete seed phase before verification" }
        check(InstrumentationRegistry.getArguments().getString("qa_host_force_stop_confirmed") == "true") { "Host must record actual QA-only force-stop and pass qa_host_force_stop_confirmed=true" }
        check(Process.myPid() != receipt.getInt("seedPid", -1) || Process.getStartElapsedRealtime() != receipt.getLong("seedProcessStart", -1)) { "Verification must run in a different process instance" }
        val id = receipt.getLong("id", -1); val token = requireNotNull(receipt.getString("token", null))
        check(io { db.videoItemDao().getAllVideosDirect().all { it.id == id } }) { "Export is allowed only while the QA index contains this owned fixture" }
        assertEquals("keep", io { db.videoItemDao().getVideoByIdDirect(id)?.reviewDecision })
        assertEquals(note(), io { db.videoItemDao().getVideoByIdDirect(id)?.manualTranscript })
        assertEquals(17, io { db.appSettingsDao().getSettingsDirect()?.requestTimeoutSeconds })
        assertEquals("qa-persistence-model", io { db.appSettingsDao().getSettingsDirect()?.ollamaModelName })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val systemDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                assertEquals("Reopened production Activity follows Android theme", !systemDark, WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                assertEquals(!systemDark, WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightNavigationBars)
            }
            navigate("folders"); compose.onNodeWithTag("auto_add").assertIsOn(); compose.onNodeWithTag("auto_record").assertIsOn(); compose.onNodeWithTag("auto_shots").assertIsOff()
            navigate("queue"); openAction("Preview")
            val values = listOf(note(), "QA", "Testing", "Review", title, detail, roadmap)
            values.forEachIndexed { index, value ->
                fields()[index].performScrollTo().assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString(value)
                ))
            }
            compose.onNodeWithContentDescription("Close").performClick(); openAction("Output")
            listOf("QA > Testing > Review", title, detail, roadmap).forEach { compose.onNodeWithText(it).assertExists() }
            compose.onNodeWithText(note(), substring = false).assertDoesNotExist(); compose.onNodeWithContentDescription("Close").performClick()
            val output = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$token-restart.json"); put(MediaStore.MediaColumns.MIME_TYPE, "application/json"); put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$token/")
            }))
            receipt.edit().putString("exportUri", output.toString()).commit()
            val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("application/json") }
            val monitor = instrumentation.addMonitor(filter, android.app.Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(output)), true)
            try {
                compose.onNodeWithContentDescription("Export metadata").performClick()
                await { runCatching { resolver.openInputStream(output)!!.bufferedReader().use { JSONObject(it.readText()).getJSONArray("media").length() == 1 } }.getOrDefault(false) }
                assertEquals(1, monitor.hits)
                val exported = resolver.openInputStream(output)!!.bufferedReader().use { JSONObject(it.readText()).getJSONArray("media").getJSONObject(0) }
                assertEquals(note(), exported.getString("notes")); assertEquals(title, exported.getString("summary")); assertEquals(detail, exported.getString("details"))
                assertEquals(roadmap, exported.getString("roadmap")); assertEquals("QA / Testing / Review", exported.getString("category")); assertEquals("keep", exported.getString("reviewDecision"))
                assertEquals(receipt.getString("uri", null), exported.getString("uri")); assertOwnedBytes()
            } finally { instrumentation.removeMonitor(monitor) }
        }
        receipt.edit().putBoolean("verified", true).commit(); writeEvidence("verify_complete_host_receipt_required")
        // Retain until explicit cleanup so a failed verification remains reviewable.
    }
    private fun cleanup() {
        val token = requireNotNull(receipt.getString("token", null)) { "No persistence receipt to cleanup" }; check(token.startsWith("QAPersist"))
        val id = receipt.getLong("id", -1)
        io {
            db.videoItemDao().getVideoByIdDirect(id)?.let { row ->
                check(row.displayName == "$token.png" && row.uriString == receipt.getString("uri", null))
                db.analysisResultDao().deleteAnalysisForVideo(id); db.extractedFrameDao().deleteFramesForVideo(id); db.videoItemDao().deleteVideo(row)
            }
        }
        listOf("uri", "exportUri").mapNotNull { receipt.getString(it, null) }.forEach { value ->
            val uri = Uri.parse(value)
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) { check(cursor.getString(0).startsWith(token)); resolver.delete(uri, null, null) }
            }
        }
        val old = JSONObject(requireNotNull(receipt.getString("old", null)))
        prefs.edit().apply {
            if (old.getString("provider") == "null") remove("llm_provider") else putString("llm_provider", old.getString("provider"))
            putBoolean("auto_ingest_enabled", old.getBoolean("auto")); putBoolean("auto_ingest_recordings", old.getBoolean("record")); putBoolean("auto_ingest_screenshots", old.getBoolean("shots"))
            if (old.has("folders")) putStringSet("target_folders", (0 until old.getJSONArray("folders").length()).map { old.getJSONArray("folders").getString(it) }.toSet()) else remove("target_folders")
        }.commit()
        if (old.has("settings")) {
            val saved = old.getJSONObject("settings")
            io { db.appSettingsDao().insertSettings(AppSettings(ollamaEnabled = saved.getBoolean("enabled"), ollamaEndpointUrl = saved.getString("url"), ollamaModelName = saved.getString("model"), requestTimeoutSeconds = saved.getInt("timeout"), confirmedNonLocalEndpointWarning = saved.getBoolean("consent"))) }
        } else {
            io {
                check(db.appSettingsDao().getSettingsDirect()?.ollamaModelName == "qa-persistence-model")
                db.openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id = 1")
            }
        }
        writeEvidence("cleanup_complete"); receipt.edit().clear().commit()
    }
    private fun assertOwnedBytes() {
        val uri = Uri.parse(requireNotNull(receipt.getString("uri", null)))
        assertEquals(receipt.getString("bytesSha256", null), resolver.openInputStream(uri)!!.use { digest(it.readBytes()) })
    }
    private fun fields() = compose.onAllNodes(hasSetTextAction())
    private fun navigate(destination: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_$destination").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
    }
    private fun openAction(label: String) {
        val name = requireNotNull(receipt.getString("token", null)) + ".png"
        await { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        val ownedId = receipt.getLong("id", -1L)
        val owned = io { requireNotNull(db.videoItemDao().getVideoByIdDirect(ownedId)) }
        check(owned.displayName == name)
        compose.onNodeWithText(name).performQaQueueScrollTo(compose).assertIsDisplayed()
        val queueTag = "queue_${ownedId}_${label.lowercase()}"
        if (compose.onAllNodesWithTag(queueTag).fetchSemanticsNodes().size == 1) {
            compose.onNodeWithTag(queueTag).performQaQueueScrollTo(compose).assertIsDisplayed().performClick()
            return
        }
        val y = compose.onNodeWithText(name).performScrollTo().fetchSemanticsNode().boundsInRoot.center.y
        val actions = compose.onAllNodes(hasContentDescription(label) and hasClickAction()).fetchSemanticsNodes()
        check(actions.isNotEmpty()) { "Missing retained owned fixture action" }
        val index = actions.indices.minBy { kotlin.math.abs(actions[it].boundsInRoot.center.y - y) }
        check(kotlin.math.abs(actions[index].boundsInRoot.center.y - y) < 70f) { "Retained fixture action ownership not proven" }
        compose.onAllNodes(hasContentDescription(label) and hasClickAction())[index].performClick()
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun writeEvidence(stage: String) {
        val token = requireNotNull(receipt.getString("token", null))
        File(context.getExternalFilesDir(null), "$token-$stage.json").writeText(JSONObject().put("stage", stage).put("package", context.packageName)
            .put("seedPid", receipt.getInt("seedPid", -1)).put("currentPid", Process.myPid()).put("hostForceStopReceiptRequired", true)
            .put("verified", receipt.getBoolean("verified", false)).toString(2))
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
