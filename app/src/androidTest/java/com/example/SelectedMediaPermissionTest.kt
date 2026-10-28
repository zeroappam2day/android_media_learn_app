package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Rect
import android.provider.DocumentsContract
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import com.example.data.FolderScopedInventorySource
import com.example.data.VideoDatabase
import com.example.data.CustomTag
import com.example.data.MediaTagCrossRef
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AndroidMediaInventorySource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Original denied/limited/limited_video/full/revoked grants still come from Android UI.
 * All runs require isolated .qa.preview data. Both inventory roots are per-token.
 * Completion phases below explicitly distinguish kernel permission setup seams from App-info UI.
 * New retained sequence: regrant_seed -> HOST revoke outside instrumentation -> revoked_verify
 * (rea_host_revoked_confirmed=true) -> HOST grant -> regranted_verify
 * (rea_host_regranted_confirmed=true) -> completion_cleanup. Revoking this app inside its own
 * instrumentation can kill the test process, so no self-revoke command is issued here.
 * images_then_video requires initially denied grants and completes/cleans in one invocation.
 * None of the new kernel grant/revoke seams claims Android App-info menu coverage.
 */
@RunWith(AndroidJUnit4::class)
class SelectedMediaPermissionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private var token = "rea_permission_${System.nanoTime()}"
    private val createdUris = mutableListOf<String>()
    private var videoPhase = false
    private val expectedByteHashes = linkedMapOf<String, String>()
    private var approvedReferenceUri: String? = null
    private var scopeSnapshot: String? = null
    private val db get() = VideoDatabase.getDatabase(context)
    private val receipt get() = context.getSharedPreferences("qa_permission_completion_receipt", 0)
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private fun paths() = listOf("Pictures/ReaPermission/$token/", "Movies/ReaPermission/$token/")
    private fun trees() = paths().map { DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:${it.trimEnd('/')}").toString() }


    @Test
    fun systemConsentControlsActualSharedMediaVisibility() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        val phase = InstrumentationRegistry.getArguments().getString("rea_permission_phase")
        assumeTrue("Pass -e rea_permission_phase denied|limited|limited_video|full|revoked for this opt-in test", phase != null)
        check(context.packageName.endsWith(".qa.preview")) { "All scoped/mutating permission phases require the isolated QA package" }
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(runBlocking { db.appSettingsDao().getSettingsDirect()?.ollamaEnabled != true }) { "QA provider must be disabled before permission-only UI phases" }
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        if (phase in setOf("regrant_seed", "revoked_verify", "regranted_verify", "images_then_video", "completion_cleanup")) {
            completionPhase(requireNotNull(phase)); return
        }
        check(Build.VERSION.SDK_INT >= 34) { "Selected-media permission requires Android 14 or later" }
        check(phase in setOf("denied", "limited", "limited_video", "full", "revoked"))
        videoPhase = phase == "limited_video"
        val source = AndroidMediaInventorySource(context)
        assertEquals("The host must reset grants before each phase", "Denied", source.currentAccessState())
        check(runBlocking { db.videoItemDao().getAllVideosDirect().isEmpty() }) { "Original selection phases require empty isolated QA index" }
        check(!receipt.contains("token")) { "Complete or cleanup retained permission mission before an original phase" }
        beginScope()
        try {
            if (videoPhase) {
                val bytes = readSyntheticVideoFixture()
                createShellOwnedMedia("a", "video", "mp4", "Movies", "video/mp4", bytes)
                createShellOwnedMedia("b", "video", "mp4", "Movies", "video/mp4", bytes)
            } else {
                createShellOwnedImage("a")
                createShellOwnedImage("b")
            }
            assertScopedFixtureOwnership()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("main activity navigation") { find { it.contentDescription?.toString() in setOf("Open menu", "Close menu") } != null }
                if (find { it.contentDescription?.toString() == "Close menu" } == null) {
                    click { it.contentDescription?.toString() == "Open menu" }
                }
                click { it.text?.toString() == "Home" }
                await("main activity Scan Videos action") { find { it.contentDescription?.toString() == "Scan Videos" } != null }
                if (phase == "revoked") {
                    assertEquals("Denied", source.currentAccessState())
                    assertTrue("Revocation must hide all shell-owned fixtures", visibleFixtureUris().isEmpty())
                } else {
                    click { it.contentDescription?.toString() == "Scan Videos" }
                    await("Android permission sheet") {
                        find { it.viewIdResourceName?.contains("permission_") == true &&
                            (it.text?.contains("Allow", true) == true || it.text?.contains("Select", true) == true ||
                                it.viewIdResourceName?.endsWith("permission_deny_button") == true) } != null
                    }
                    when (phase) {
                        "denied" -> {
                            click { it.viewIdResourceName?.endsWith("permission_deny_button") == true ||
                                it.text?.toString()?.lowercase() in setOf("don't allow", "don’t allow") }
                            await("denied media access") { source.currentAccessState() == "Denied" }
                            assertTrue("Deny must hide every shell-owned fixture", visibleFixtureUris().isEmpty())
                        }
                        "full" -> {
                            click { it.viewIdResourceName?.endsWith("permission_allow_all_button") == true ||
                                it.text?.toString()?.lowercase() in setOf("allow all", "allow all photos and videos") }
                            await("full media access") { source.currentAccessState() == "Full access" }
                            assertEquals(createdUris.toSet(), visibleFixtureUris())
                        }
                        "limited", "limited_video" -> {
                            click { it.viewIdResourceName?.endsWith("permission_allow_selected_button") == true ||
                                it.text?.toString()?.startsWith("Select photos", true) == true }
                            selectNewestFixture()
                            confirmSelection()
                            await("selected-media access") { source.currentAccessState() == "Limited access" }
                            val firstSelection = visibleFixtureUris()
                            assertEquals("Exactly one fixture must be readable after selecting one media item", 1, firstSelection.size)
                            requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(firstSelection.single()))).use {
                                assertTrue("Selected media bytes must be readable by the app", it.read() >= 0)
                            }
                            val withheld = createdUris.toSet() - firstSelection
                            assertEquals(1, withheld.size)
                            assertCannotRead(withheld.single())
                            scenario.recreate()
                            await("limited state after recreation") { source.currentAccessState() == "Limited access" }
                            assertEquals(firstSelection, visibleFixtureUris())
                            click { it.text?.toString() == "Change" }
                            click { it.viewIdResourceName?.endsWith("permission_allow_selected_button") == true }
                            selectBothFixtures()
                            confirmSelection()
                            await("expanded selection") { visibleFixtureUris() == createdUris.toSet() }
                            assertEquals("Reselecting does not grant full-library access", "Limited access", source.currentAccessState())
                        }
                    }
                }
                shell("input keyevent KEYCODE_HOME")
                await("app to leave foreground") { automation.rootInActiveWindow?.packageName?.toString() != context.packageName }
                shell("am start -W --activity-reorder-to-front -n ${context.packageName}/com.example.MainActivity")
                await("app foreground after relaunch") { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
                val expected = when (phase) { "full" -> "Full access"; "limited", "limited_video" -> "Limited access"; else -> "Denied" }
                await("permission state after background and resume") { source.currentAccessState() == expected }
                if (phase in setOf("full", "limited", "limited_video")) assertEquals(createdUris.toSet(), visibleFixtureUris())
                else assertTrue(visibleFixtureUris().isEmpty())
                writeEvidence("PASS phase=$phase sdk=${Build.VERSION.SDK_INT} access=$expected fixtures=${createdUris.size} visible=${visibleFixtureUris().size}")
            }
        } catch (failure: Throwable) {
            writeEvidence("FAIL phase=$phase error=$failure\n${tree()}")
            throw failure
        } finally {
            cleanupOwnedFixtureRowsAndMedia()
            restoreScope()
        }
    }

    private fun createShellOwnedImage(suffix: String) {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
        createShellOwnedMedia(suffix, "images", "png", "Pictures", "image/png", Base64.decode(png, Base64.DEFAULT))
    }

    private fun createShellOwnedMedia(suffix: String, collection: String, extension: String, directory: String, mime: String, bytes: ByteArray) {
        val name = "${token}_$suffix.$extension"
        val baseUri = "content://media/external/$collection/media"
        check(name.matches(Regex("[A-Za-z0-9_.-]+")))
        check(directory in setOf("Pictures", "Movies"))
        assertEquals("Shell fixture creation must run as Android shell UID", "2000", shell("id -u").trim())
        val path = "$directory/ReaPermission/$token/"
        val where = "_display_name='$name' AND relative_path='$path'"
        check(!shell("content query --uri $baseUri --projection _id --where \"$where\"").contains("_id=")) { "Unique fixture name/path already exists" }
        shell("content insert --uri $baseUri --bind _display_name:s:$name --bind relative_path:s:$path --bind mime_type:s:$mime")
        val rows = shell("content query --uri $baseUri --projection _id:_display_name:owner_package_name:relative_path --where \"$where\"")
        val row = rows.lineSequence().firstOrNull { it.contains("_display_name=$name,") }
            ?: error("Shell MediaStore insert did not create its uniquely scoped fixture")
        val id = Regex("_id=(\\d+)").find(row)?.groupValues?.get(1) ?: error("Missing scoped fixture ID")
        val uri = "$baseUri/$id"
        createdUris += uri
        expectedByteHashes[uri] = sha256(bytes)
        if (receipt.getString("token", null) == token) persistCompletionFixture("creating_owned_fixtures")
        val descriptors = automation.executeShellCommandRw("content write --uri $uri")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
        await("fixture to be indexed after binary write") {
            shell("content query --uri $uri --projection _size:width:height").contains("_size=${bytes.size}")
        }
        assertEquals("Exactly one scoped fixture must be created", 1, rows.lineSequence().count { it.contains("_id=") })
        assertFalse("Shell-created fixture must not be owned by the app", row.contains("owner_package_name=${context.packageName}"))
        assertTrue(row.contains("relative_path=$path"))
        expectedByteHashes[uri] = sha256(bytes)
        assertEquals("Shell-written fixture bytes must match exactly", sha256(bytes), sha256(shellRead(uri)))
    }

    private fun readSyntheticVideoFixture(): ByteArray {
        val name = InstrumentationRegistry.getArguments().getString("rea_video_fixture") ?: "codex_emulator_workflow.mp4"
        check(name in setOf("codex_emulator_workflow.mp4", "qa_no_text.mp4")) { "Only the two explicitly approved synthetic video names are permitted" }
        val expectedHash = InstrumentationRegistry.getArguments().getString("rea_video_sha256")
        if (expectedHash != null) check(expectedHash.matches(Regex("[0-9a-fA-F]{64}")))
        val rows = shell("content query --uri content://media/external/video/media --projection _id:_display_name:_size --where \"_display_name='$name'\"")
        val row = rows.lineSequence().firstOrNull { it.contains("_display_name=$name,") }
            ?: error("Host must provision the exact synthetic MP4 before permission phases: $name")
        val id = requireNotNull(Regex("_id=(\\d+)").find(row)?.groupValues?.get(1))
        val size = requireNotNull(Regex("_size=(\\d+)").find(row)?.groupValues?.get(1)).toLong()
        check(size in 1..16_777_216) { "Synthetic video fixture must be at most 16 MiB" }
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("content read --uri content://media/external/video/media/$id")
        ).use { it.readBytes() }
        assertEquals(size, bytes.size.toLong())
        if (expectedHash != null) assertEquals("Approved synthetic source must match supplied provenance", expectedHash.lowercase(), sha256(bytes))
        assertEquals("Exactly one approved source name must exist", 1, rows.lineSequence().count { it.contains("_id=") })
        approvedReferenceUri = "content://media/external/video/media/$id"
        val local = File(context.cacheDir, "$token.mp4")
        try {
            local.writeBytes(bytes)
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(local.absolutePath)
                assertTrue(requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() > 0)
                val frame = retriever.getFrameAtTime(0)
                assertNotNull("Synthetic source must decode a real video frame", frame)
                frame?.recycle()
            }
        } finally {
            local.delete()
        }
        return bytes
    }

    private fun visibleFixtureUris(): Set<String> = runBlocking {
        FolderScopedInventorySource(AndroidMediaInventorySource(context)) { trees() }.readSnapshot().records
            .filter { it.displayName.startsWith(token) }.map { it.uriString }.toSet()
    }

    private fun assertCannotRead(uri: String) {
        try {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.read() }
            fail("An unselected shell-owned image must not be readable")
        } catch (_: SecurityException) {
        } catch (_: java.io.FileNotFoundException) {
        }
    }

    private fun selectNewestFixture() {
        val exactOwnedAlbums = allNodes().filter { it.isVisibleToUser && it.text?.toString() == token }
        if (exactOwnedAlbums.size == 1) performClick(exactOwnedAlbums.single())
        // Preserve the existing proven newest-owned-thumbnail route when the picker has no
        // readily observed token album. Fresh fixtures were inserted immediately before launch.
        await("media-picker thumbnails") { fixturePickerNodes().isNotEmpty() }
        File(context.getExternalFilesDir(null), "rea-permission-picker.txt").writeText(tree())
        automation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "rea-permission-picker.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        performClick(fixturePickerNodes().first())
    }

    private fun selectBothFixtures() {
        await("media-picker thumbnails for reselection") { fixturePickerNodes().size >= 2 }
        performClick(fixturePickerNodes()[1])
    }

    private fun fixturePickerNodes(): List<AccessibilityNodeInfo> = allNodes().filter {
        it.isVisibleToUser && (it.viewIdResourceName?.endsWith("/icon_thumbnail") == true ||
            it.contentDescription?.toString()?.startsWith(if (videoPhase) "Video taken on" else "Photo taken on", true) == true)
    }.distinctBy { node -> Rect().also { node.getBoundsInScreen(it) }.toString() }

    private fun confirmSelection() {
        click { node ->
            val text = node.text?.toString().orEmpty()
            node.isEnabled && (text.matches(Regex("(?i)(add|allow|done)( \\(\\d+\\))?")) ||
                node.viewIdResourceName?.endsWith("/button_add") == true ||
                node.viewIdResourceName?.endsWith("/button_allow") == true)
        }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? = allNodes().firstOrNull { it.isVisibleToUser && predicate(it) }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        automation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        await("UI control") { find(predicate) != null }
        performClick(requireNotNull(find(predicate)))
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = current.parent
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val now = android.os.SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 100 else 0, action,
                rect.exactCenterX(), rect.exactCenterY(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(this, true))
                recycle()
            }
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 20_000
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(150)
        }
        error("Timed out waiting for $description\n${tree()}")
    }

    private fun shell(command: String): String {
        val pipes = automation.executeShellCommandRwe("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((command + "\n").toByteArray()) }
        val output = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
        val error = ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).bufferedReader().use { it.readText() }
        return output + error
    }

    private fun tree(): String = allNodes().filter { it.isVisibleToUser }.joinToString("\n") {
        val value = it.text?.toString().orEmpty()
        val allowed = setOf("Home", "Sources", "New", "Keep", "Later", "Remove", "Change", "Dismiss", "Add", "Allow", "Done", "Don't allow", "Don’t allow", "Allow all", "Select photos", "Select photos and videos")
        val label = if (value.startsWith(token) || value in allowed || value.matches(Regex("[0-9]+ available items.*"))) value else "<redacted>"
        "${it.packageName} ${it.viewIdResourceName} label=$label checked=${it.isChecked} selected=${it.isSelected}"
    }

    private fun beginScope() {
        check(context.packageName.endsWith(".qa.preview"))
        check(token.matches(Regex("rea_permission_[0-9]+")))
        val old = JSONObject().put("auto", prefs.getBoolean("auto_ingest_enabled", false)).put("hadAuto", prefs.contains("auto_ingest_enabled"))
        prefs.getStringSet("target_folders", null)?.let { old.put("folders", JSONArray(it.toList())) }
        scopeSnapshot = old.toString()
        prefs.edit().putBoolean("auto_ingest_enabled", false).putStringSet("target_folders", trees().toSet()).commit()
    }
    private fun restoreScope() {
        val value = scopeSnapshot ?: return
        val old = JSONObject(value)
        prefs.edit().apply {
            if (old.getBoolean("hadAuto")) putBoolean("auto_ingest_enabled", old.getBoolean("auto")) else remove("auto_ingest_enabled")
            if (old.has("folders")) putStringSet("target_folders", (0 until old.getJSONArray("folders").length()).map { old.getJSONArray("folders").getString(it) }.toSet()) else remove("target_folders")
        }.commit()
    }
    private fun cleanupOwnedFixtureRowsAndMedia() {
        check(context.packageName.endsWith(".qa.preview"))
        runBlocking {
            db.videoItemDao().getAllVideosDirect().filter { it.displayName.startsWith(token + "_") && it.relativePath in paths() }.forEach { row ->
                db.binDao().remove(row.id); db.extractedFrameDao().deleteFramesForVideo(row.id); db.analysisResultDao().deleteAnalysisForVideo(row.id); db.videoItemDao().deleteVideo(row)
            }
            val tagId = receipt.getLong("tagId", -1)
            if (tagId >= 0) db.customTagDao().observeTags().first().firstOrNull { it.id == tagId && it.name == token + "_retained" }?.let { db.customTagDao().delete(it) }
        }
        createdUris.forEach { uri ->
            check(uri in expectedByteHashes)
            val metadata = shell("content query --uri $uri --projection _display_name:relative_path")
            if (metadata.contains("_display_name=")) {
                check(metadata.contains("_display_name=${token}_") && paths().any { metadata.contains("relative_path=$it") })
                shell("content delete --uri $uri")
            }
        }
    }
    private fun shellRead(uri: String): ByteArray {
        check(uri in createdUris || uri == approvedReferenceUri)
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("content read --uri $uri")).use { it.readBytes() }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assertOwnedPhysicalBytes() { expectedByteHashes.forEach { (uri, hash) -> assertEquals(hash, sha256(shellRead(uri))) } }
    private fun assertScopedFixtureOwnership() {
        // The user authorized this emulator's visible gallery. Do not inspect or restrict its
        // other media; only this run's freshly inserted shell-owned files may be selected/mutated.
        assertEquals("Shell operations must use UID2000", "2000", shell("id -u").trim())
        createdUris.forEach { uri ->
            val row = shell("content query --uri $uri --projection _display_name:relative_path:owner_package_name")
            assertTrue("Fixture must keep its exact unique prefix", row.contains("_display_name=${token}_"))
            assertTrue("Fixture must remain in one of its two exact scoped folders", paths().any { row.contains("relative_path=$it") })
            assertFalse("Fixture must be shell-owned rather than QA-app-owned", row.contains("owner_package_name=${context.packageName}"))
        }
        assertOwnedPhysicalBytes()
    }
    private fun kernelGranted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun kernelGrantForQaOnly(permission: String) {
        check(context.packageName.endsWith(".qa.preview"))
        check(permission in setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        automation.grantRuntimePermission(context.packageName, permission)
        await("explicit QA kernel grant") { kernelGranted(permission) }
    }
    private fun completionPhase(phase: String) {
        check(context.packageName.endsWith(".qa.preview"))
        check(Build.VERSION.SDK_INT >= 34)
        when (phase) {
            "regrant_seed" -> {
                check(!receipt.contains("token")) { "Retained permission mission already exists" }
                check(runBlocking { db.videoItemDao().getAllVideosDirect().isEmpty() })
                beginScope(); persistCompletionFixture("seed_prepared")
                createShellOwnedImage("a"); createShellOwnedMedia("v", "video", "mp4", "Movies", "video/mp4", readSyntheticVideoFixture())
                assertScopedFixtureOwnership()
                persistCompletionFixture("seed_started")
                // EXPLICIT SETUP SEAM: grants establish initial real kernel access; not App-info UI.
                kernelGrantForQaOnly(Manifest.permission.READ_MEDIA_IMAGES); kernelGrantForQaOnly(Manifest.permission.READ_MEDIA_VIDEO)
                ActivityScenario.launch(MainActivity::class.java).use {
                    refreshActualSources(2, denyRemaining = false)
                    val image = runBlocking { db.videoItemDao().getAllVideosDirect().single { it.displayName == token + "_a.png" } }
                    runBlocking {
                        db.videoItemDao().updateVideo(image.copy(reviewDecision = "keep", manualTranscript = "QA permission retained notes"))
                        val tag = db.customTagDao().insert(CustomTag(name = token + "_retained"))
                        db.customTagDao().assign(MediaTagCrossRef(image.id, tag))
                        receipt.edit().putLong("imageId", image.id).putLong("tagId", tag).commit()
                    }
                    // State seed is explicit Room setup; post-change Sources is real production UI.
                    val category = runBlocking { db.videoItemDao().getVideoByIdDirect(image.id)?.sourceCategory } ?: "Other"
                    click { it.text?.toString() == category }; await("owned image visible with Keep") { find { it.text?.toString() == image.displayName } != null && find { it.text?.toString() == "Keep" } != null }
                    assertRetainedState(); assertOwnedPhysicalBytes()
                }
                receipt.edit().putString("phase", "seed_complete_host_revoke_required").commit()
                writeEvidence("SEED_READY partial=kernel_grant_and_Room_setup host_must_revoke_outside_instrumentation=true fixtures=2")
            }
            "revoked_verify", "regranted_verify" -> {
                loadCompletionFixture()
                check(InstrumentationRegistry.getArguments().getString(if (phase == "revoked_verify") "rea_host_revoked_confirmed" else "rea_host_regranted_confirmed") == "true") { "Host must record the external QA-only grant/revoke command between completed invocations" }
                val granted = phase == "regranted_verify"
                assertEquals(granted, kernelGranted(Manifest.permission.READ_MEDIA_IMAGES)); assertEquals(granted, kernelGranted(Manifest.permission.READ_MEDIA_VIDEO))
                if (!granted) assertFalse(kernelGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
                ActivityScenario.launch(MainActivity::class.java).use {
                    refreshActualSources(if (granted) 2 else 0, denyRemaining = !granted)
                    if (!granted) {
                        createdUris.forEach(::assertCannotRead)
                        assertTrue("Revoke refresh must hide both indexed fixtures", runBlocking { db.videoItemDao().getAllVideosDirect().filter { row -> row.displayName.startsWith(token + "_") }.none { row -> row.isCurrentlyAvailable } })
                    } else assertEquals(createdUris.toSet(), visibleFixtureUris())
                    assertRetainedState(); assertOwnedPhysicalBytes()
                }
                receipt.edit().putString("phase", phase + "_complete").commit()
                writeEvidence("VERIFY phase=$phase partial=external_kernel_permission_setup visible_count=${if (granted) 2 else 0} saved_keep_notes_tag=true bytes_equal=true OS_AppInfo_UI_NOT_PROVED=true")
            }
            "images_then_video" -> {
                check(!receipt.contains("token")); check(runBlocking { db.videoItemDao().getAllVideosDirect().isEmpty() })
                check(!kernelGranted(Manifest.permission.READ_MEDIA_IMAGES) && !kernelGranted(Manifest.permission.READ_MEDIA_VIDEO) && !kernelGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) { "Host must reset QA grants before split-permission seam" }
                beginScope()
                try {
                    createShellOwnedImage("a"); createShellOwnedMedia("v", "video", "mp4", "Movies", "video/mp4", readSyntheticVideoFixture())
                    // Keep the video permission denied during the photos-only setup; Android
                    // otherwise auto-grants another requested permission in the same group.
                    shell("pm set-permission-flags ${context.packageName} android.permission.READ_MEDIA_VIDEO user-set user-fixed")
                    assertScopedFixtureOwnership(); kernelGrantForQaOnly(Manifest.permission.READ_MEDIA_IMAGES)
                    assertFalse(kernelGranted(Manifest.permission.READ_MEDIA_VIDEO))
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        refreshActualSources(1, denyRemaining = true)
                        assertTrue(kernelGranted(Manifest.permission.READ_MEDIA_IMAGES)); assertFalse(kernelGranted(Manifest.permission.READ_MEDIA_VIDEO))
                        assertEquals(setOf(createdUris.single { uri -> "/images/" in uri }), visibleFixtureUris())
                        assertCannotRead(createdUris.single { uri -> "/video/" in uri })
                        navigateHome()
                        shell("pm clear-permission-flags ${context.packageName} android.permission.READ_MEDIA_VIDEO user-fixed")
                        kernelGrantForQaOnly(Manifest.permission.READ_MEDIA_VIDEO)
                        automation.waitForIdle(500, 5000)
                        scenario.recreate()
                        refreshActualSources(2, denyRemaining = false)
                        assertEquals(createdUris.toSet(), visibleFixtureUris()); assertOwnedPhysicalBytes()
                    }
                    writeEvidence("VERIFY phase=images_then_video partial=real_kernel_grants_not_OS_AppInfo_UI counts=1_then_2 bytes_equal=true")
                } finally {
                    shell("pm clear-permission-flags ${context.packageName} android.permission.READ_MEDIA_VIDEO user-fixed")
                    cleanupOwnedFixtureRowsAndMedia(); restoreScope()
                }
            }
            "completion_cleanup" -> { loadCompletionFixture(verifyBytes = false); cleanupOwnedFixtureRowsAndMedia(); restoreScope(); receipt.edit().clear().commit(); writeEvidence("CLEANUP only_token_owned_media_rows_tag_and_nonsecret_scope_restored=true") }
        }
    }
    private fun persistCompletionFixture(phase: String) {
        val hashes = JSONObject(); expectedByteHashes.forEach { (uri, hash) -> hashes.put(uri, hash) }
        check(receipt.edit().putString("token", token).putString("uris", JSONArray(createdUris).toString()).putString("hashes", hashes.toString())
            .putString("oldScope", scopeSnapshot).putString("referenceUri", approvedReferenceUri).putString("phase", phase).commit())
    }
    private fun loadCompletionFixture(verifyBytes: Boolean = true) {
        token = requireNotNull(receipt.getString("token", null)); check(token.matches(Regex("rea_permission_[0-9]+")))
        val uris = JSONArray(requireNotNull(receipt.getString("uris", null))); createdUris.clear(); repeat(uris.length()) { createdUris += uris.getString(it) }
        val hashes = JSONObject(requireNotNull(receipt.getString("hashes", null))); hashes.keys().forEach { key -> expectedByteHashes[key] = hashes.getString(key) }
        approvedReferenceUri = receipt.getString("referenceUri", null); scopeSnapshot = receipt.getString("oldScope", null)
        check(prefs.getStringSet("target_folders", emptySet()) == trees().toSet()) { "Retained token scope must remain exact" }
        if (verifyBytes) assertOwnedPhysicalBytes()
    }
    private fun assertRetainedState() = runBlocking {
        val id = receipt.getLong("imageId", -1); val tag = receipt.getLong("tagId", -1)
        val row = requireNotNull(db.videoItemDao().getVideoByIdDirect(id))
        check(row.displayName == token + "_a.png" && row.relativePath in paths())
        assertEquals("keep", row.reviewDecision); assertEquals("QA permission retained notes", row.manualTranscript)
        assertEquals(token + "_retained", db.customTagDao().observeTags().first().single { it.id == tag }.name)
        assertTrue(db.customTagDao().observeAssignments().first().contains(MediaTagCrossRef(id, tag)))
    }
    private fun navigateHome() { navigateDestination("Home") }
    private fun navigateDestination(label: String) {
        await("QA navigation") { find { it.contentDescription?.toString() in setOf("Open menu", "Close menu") } != null }
        if (find { it.contentDescription?.toString() == "Close menu" } == null) click { it.contentDescription?.toString() == "Open menu" }
        click { it.text?.toString() == label }
        await("$label destination with navigation closed") {
            find { it.contentDescription?.toString() == "Close menu" } == null &&
                find { it.contentDescription?.toString() == "Open menu" } != null &&
                find { it.text?.toString() == label } != null
        }
    }
    private fun refreshActualSources(expected: Int, denyRemaining: Boolean) {
        navigateDestination("Sources")
        find { it.contentDescription?.toString() == "Show all sources" }?.let(::performClick)
        click { it.contentDescription?.toString() == "Refresh inventory" }
        await("permission response or exact source count") { permissionDenyNode() != null || visibleCount(expected) }
        permissionDenyNode()?.let { node ->
            check(denyRemaining) { "Unexpected remaining-permission sheet in full-grant fixture" }; performClick(node)
            await("return after permission response") { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
            if (!kernelGranted(Manifest.permission.READ_MEDIA_IMAGES) && !kernelGranted(Manifest.permission.READ_MEDIA_VIDEO)) {
                // T019 explicitly requests manual Refresh after revocation. An initial Deny
                // callback is not assumed to scan. Exercise the visible Refresh action again.
                click { it.contentDescription?.toString() == "Refresh inventory" }
                await("manual revoked refresh result or second permission question") { visibleCount(expected) || permissionDenyNode() != null }
                permissionDenyNode()?.let(::performClick)
            }
        }
        await("actual Sources count $expected after visible Refresh") { visibleCount(expected) }
        assertTrue("Room index must contain only this run", runBlocking { db.videoItemDao().getAllVideosDirect().all { row -> row.displayName.startsWith(token + "_") && row.relativePath in paths() } })
        // Prove Home's label after the actual permission/Refresh callback updates app state.
        navigateHome(); click { it.text?.toString() == "Overview" }
        val access = AndroidMediaInventorySource(context).currentAccessState()
        await("actual Home access label after Refresh") { find { it.text?.toString() == "Media: $access" } != null }
        navigateDestination("Sources")
        find { it.contentDescription?.toString() == "Show all sources" }?.let(::performClick)
        await("Sources count retained after Home") { visibleCount(expected) }
    }
    private fun permissionDenyNode() = find { it.isEnabled && (it.viewIdResourceName?.endsWith("permission_deny_button") == true || it.text?.toString()?.lowercase() in setOf("don't allow", "don’t allow")) }
    private fun visibleCount(expected: Int) = find { it.text?.toString()?.startsWith("$expected available items • ") == true } != null

    private fun writeEvidence(text: String) {
        val phase = InstrumentationRegistry.getArguments().getString("rea_permission_phase")
        File(context.getExternalFilesDir(null), "rea-permission-$phase.txt").writeText(text)
        println(text)
    }
}
