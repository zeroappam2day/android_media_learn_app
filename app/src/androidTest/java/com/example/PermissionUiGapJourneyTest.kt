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
class PermissionUiGapJourneyTest {
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
    private val receipt get() = context.getSharedPreferences("qa71_permission_completion_receipt", 0)
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private fun paths() = listOf("Pictures/ReaPermission/$token/", "Movies/ReaPermission/$token/")
    private fun trees() = paths().map { DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:${it.trimEnd('/')}").toString() }


    /** Opt-in UI journeys. Retained phases require host native_permission_driver.py between invocations. */
    @Test fun qa71_nativePermissionJourneys() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(Build.VERSION.SDK_INT == 36)
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        when (val phase = InstrumentationRegistry.getArguments().getString("qa71_permission_phase")) {
            "full_mixed", "deny_then_allow", "selected_mixed" -> nativeJourney(phase)
            "limited_ui" -> originalConsentJourney("limited")
            "retained_seed" -> completionPhase("regrant_seed")
            "retained_revoked" -> completionPhase("revoked_verify")
            "retained_regranted" -> completionPhase("regranted_verify")
            "retained_cleanup" -> completionPhase("completion_cleanup")
            else -> org.junit.Assume.assumeTrue("Opt in with qa71_permission_phase", false)
        }
        println("QA71_NATIVE_PERMISSION token=$token phase=${InstrumentationRegistry.getArguments().getString("qa71_permission_phase")}")
    }

    private fun nativeJourney(phase: String) {
        check(runBlocking { db.videoItemDao().getAllVideosDirect().isEmpty() })
        check(!receipt.contains("token"))
        check(AndroidMediaInventorySource(context).currentAccessState() == "Denied") { "Reset QA grants before starting the native journey" }
        check(runBlocking { db.appSettingsDao().getSettingsDirect()?.ollamaEnabled != true })
        beginScope()
        val denial = java.util.concurrent.atomic.AtomicBoolean(false)
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.packageName?.toString() == context.packageName && event.text.any { it.toString() == "Permissions denied. App needs photo or video access to build the inventory." }) denial.set(true)
        }
        try {
            // The photo is newest, binding the first thumbnail to this exact owned image.
            createShellOwnedMedia("v", "video", "mp4", "Movies", "video/mp4", readSyntheticVideoFixture())
            createShellOwnedImage("a")
            assertScopedFixtureOwnership()
            ActivityScenario.launch(MainActivity::class.java).use {
                navigateHome(); click { it.contentDescription?.toString() == "Scan Videos" }
                await("observed permission question") { permissionDenyNode() != null || find { it.viewIdResourceName?.endsWith("permission_allow_selected_button") == true } != null }
                if (phase == "deny_then_allow") {
                    performClick(requireNotNull(permissionDenyNode()))
                    await("denial notification") { denial.get() }
                    assertEquals("Denied", AndroidMediaInventorySource(context).currentAccessState())
                    assertPresentation(emptySet(), "Denied")
                    click { it.contentDescription?.toString() == "Scan Videos" }
                    await("retry permission question") { find { it.viewIdResourceName?.endsWith("permission_allow_all_button") == true } != null }
                }
                if (phase == "selected_mixed") {
                    videoPhase = false
                    click { it.viewIdResourceName?.endsWith("permission_allow_selected_button") == true }
                    selectNewestFixture(); confirmSelection()
                    val image = createdUris.single { "/images/" in it }; val video = createdUris.single { "/video/" in it }
                    await("photo-only selected access") { visibleFixtureUris() == setOf(image) }
                    assertCannotRead(video)
                    assertPresentation(setOf(image), "Limited access", expectLimitedBanner = true)
                    click { it.text?.toString() == "Change" }
                    click { it.viewIdResourceName?.endsWith("permission_allow_selected_button") == true }
                    captureMixedSelection("before_expansion_click")
                    selectBothFixtures()
                    captureMixedSelection("after_expansion_click")
                    confirmSelection()
                    try {
                        await("video explicitly added") { visibleFixtureUris() == createdUris.toSet() }
                    } finally {
                        captureMixedSelection("after_expansion_confirm")
                    }
                    assertPresentation(createdUris.toSet(), "Limited access", expectLimitedBanner = true)
                } else {
                    click { it.viewIdResourceName?.endsWith("permission_allow_all_button") == true || it.text?.toString()?.lowercase() in setOf("allow all", "allow all photos and videos") }
                    await("all access granted") { AndroidMediaInventorySource(context).currentAccessState() == "Full access" }
                    assertPresentation(createdUris.toSet(), "Full access")
                    assertEquals(setOf("image", "video"), runBlocking { db.videoItemDao().getAllVideosDirect().map { it.mediaCollection }.toSet() })
                }
                assertOwnedPhysicalBytes()
                writeEvidence("PASS QA71 phase=$phase exact_visible_uris=${createdUris.size} bytes_equal=true native_permission_UI=true")
            }
        } finally {
            automation.setOnAccessibilityEventListener(null)
            cleanupOwnedFixtureRowsAndMedia(); restoreScope()
        }
    }
    private fun assertPresentation(expected: Set<String>, access: String, expectLimitedBanner: Boolean = false) {
        navigateDestination("Sources")
        find { it.contentDescription?.toString() == "Show all sources" }?.let(::performClick)
        val bytes = expected.sumOf { shellRead(it).size.toLong() }
        await("exact Sources count, bytes and identities") {
            find { it.text?.toString() == "${expected.size} available items • ${sizeText(bytes)}" } != null &&
                runBlocking { db.videoItemDao().getAllVideosDirect().filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }.map { it.uriString }.toSet() } == expected
        }
        assertEquals(expected.size, runBlocking { db.videoItemDao().getAllVideosDirect().count { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed } })
        navigateHome()
        if (find { it.text?.toString() == "Media: $access" } == null) click { it.text?.toString() == "Overview" }
        await("Home exact access state") { find { it.text?.toString() == "Media: $access" } != null }
        if (expectLimitedBanner) await("visible limited access banner") { find { it.text?.toString() == "Limited Access Enabled" } != null }
    }
    private fun sizeText(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB"); var value = bytes.toDouble(); var unit = -1
        while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
        return String.format(java.util.Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }
    private fun assertParityAfterPermission(granted: Boolean) {
        val image = token + "_a.png"; val video = token + "_v.mp4"
        navigateDestination("Queue")
        await("Queue availability parity") { (find { it.text?.toString() == image } != null) == granted && (find { it.text?.toString() == video } != null) == granted }
        navigateDestination("Sources"); openNativeTool("Tags")
        click { it.text?.toString() == token + "_retained" }
        await("Tag assignable-media availability parity") { (find { it.text?.toString() == image } != null) == granted }
        click { it.contentDescription?.toString() == "Back" }
        if (receipt.getBoolean("binFixture", false)) {
            openNativeTool("Bin")
            fun owningButton(icon: AccessibilityNodeInfo): AccessibilityNodeInfo {
                var node: AccessibilityNodeInfo? = icon
                while (node != null && !node.isClickable) node = node.parent
                return requireNotNull(node) { "Bin icon must belong to an observed clickable button" }
            }
            val delete = owningButton(requireNotNull(find { it.contentDescription?.toString() == "Delete all" }))
            assertEquals("Only available Bin media can be offered for deletion", granted, delete.isEnabled)
            val recover = owningButton(requireNotNull(find { it.contentDescription?.toString() == "Recover" }))
            assertEquals(granted, recover.isEnabled)
            if (granted) {
                performClick(recover)
                await("actual Bin recovery after permission restoration") { runBlocking { db.binDao().entry(receipt.getLong("imageId", -1)) == null } }
                receipt.edit().putBoolean("binFixture", false).commit()
                assertRetainedState()
            }
            click { it.contentDescription?.toString() == "Back" }
        }
        if (granted) {
            navigateDestination("Queue"); rowAction(image, "Output")
            await("restored saved summary in real Output") { find { it.text?.toString() == "QA71 retained summary" } != null }
            click { it.contentDescription?.toString() == "Close" }
            rowAction(image, "Preview")
            await("restored saved notes in real Preview") { find { it.isEditable && it.text?.toString() == "QA permission retained notes" } != null }
            click { it.contentDescription?.toString() == "Close" }
        }
        assertRetainedState(); assertOwnedPhysicalBytes()
    }
    private fun openNativeTool(label: String) {
        find { it.contentDescription?.toString() == "Back" }?.let(::performClick)
        writeEvidence("TOOL_ENTRY requested=$label phone_inventory=${find { it.text?.toString() == "Phone media inventory" } != null} show_all_sources=${find { it.contentDescription?.toString() == "Show all sources" } != null} tags=${find { it.contentDescription?.toString() == "Tags" } != null} bin=${find { it.contentDescription?.toString() == "Bin" } != null} open_menu=${find { it.contentDescription?.toString() == "Open menu" } != null} close_menu=${find { it.contentDescription?.toString() == "Close menu" } != null}")
        capturePublicToolState(label)
        await("Sources tool or observed category exit") {
            find { it.contentDescription?.toString() == label } != null ||
                find { it.contentDescription?.toString() == "Show all sources" } != null
        }
        find { it.contentDescription?.toString() == "Show all sources" }?.let(::performClick)
        click { it.contentDescription?.toString() == label }
        await("native $label tool") { find { it.text?.toString() == label } != null && find { it.contentDescription?.toString() == "Back" } != null }
    }

    private fun capturePublicToolState(requested: String) {
        val args = InstrumentationRegistry.getArguments()
        val phase = (args.getString("qa71_permission_phase") ?: args.getString("rea_permission_phase") ?: "runtime")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        val publicLabels = setOf("Tags", "Bin", "Refresh inventory", "Show all sources", "Open menu", "Close menu", "Back")
        fun publicSnapshot(): org.json.JSONObject {
            val root = automation.rootInActiveWindow
            val rootBounds = Rect().also { root?.getBoundsInScreen(it) }
            val icons = org.json.JSONArray()
            allNodes().filter { it.contentDescription?.toString() in publicLabels }.forEach { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                icons.put(org.json.JSONObject().put("description", node.contentDescription?.toString())
                    .put("bounds", bounds.toShortString()).put("visible", node.isVisibleToUser)
                    .put("enabled", node.isEnabled).put("clickable", node.isClickable))
            }
            return org.json.JSONObject().put("root_package", root?.packageName?.toString())
                .put("window_bounds", rootBounds.toShortString()).put("public_icons", icons)
                .put("phone_inventory_visible", find { it.text?.toString() == "Phone media inventory" } != null)
        }
        fun captureScreen(stage: String, snapshot: org.json.JSONObject) {
            if (snapshot.optString("root_package") == context.packageName && snapshot.optBoolean("phone_inventory_visible")) {
                automation.takeScreenshot()?.let { screenshot ->
                    snapshot.put("screen_width", screenshot.width).put("screen_height", screenshot.height)
                    File(context.getExternalFilesDir(null), "qa71-tool-$phase-$requested-$stage.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
            }
        }
        val before = publicSnapshot()
        captureScreen("before-cache-clear", before)
        val cleared = automation.clearCache()
        val after = publicSnapshot()
        captureScreen("after-cache-clear", after)
        val metadata = org.json.JSONObject().put("phase", phase).put("requested", requested)
            .put("orientation", context.resources.configuration.orientation)
            .put("before_cache_clear", before).put("clear_cache_succeeded", cleared).put("after_cache_clear", after)
        File(context.getExternalFilesDir(null), "qa71-tool-$phase-$requested.json").writeText(metadata.toString(2))
        println("PUBLIC_TOOL_STATE $metadata")
    }

    private fun rowAction(name: String, action: String) {
        fun scrollHorizontally(direction: android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction): Boolean {
            val scrollable = allNodes().firstOrNull { node ->
                node.isVisibleToUser && node.actionList.any { it.id == direction.id }
            } ?: return false
            val moved = scrollable.performAction(direction.id)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (moved) android.os.SystemClock.sleep(350)
            return moved
        }
        repeat(6) {
            val visibleName = find { it.isVisibleToUser && it.text?.toString() == name }
            if (visibleName == null) scrollHorizontally(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
        }
        await("owned Queue row") { find { it.isVisibleToUser && it.text?.toString() == name } != null }
        val target = Rect().also { requireNotNull(find { it.isVisibleToUser && it.text?.toString() == name }).getBoundsInScreen(it) }
        val filenameCenters = allNodes().filter { it.isVisibleToUser && it.text?.toString()?.startsWith(token + "_") == true }
            .map { node -> Rect().also { node.getBoundsInScreen(it) }.centerY() }.distinct()
        fun belongsToOwnedRow(node: AccessibilityNodeInfo): Boolean {
            val center = Rect().also { node.getBoundsInScreen(it) }.centerY()
            return filenameCenters.minByOrNull { kotlin.math.abs(it - center) } == target.centerY()
        }
        fun ownedVisibleActions() = allNodes().filter { node ->
            node.isVisibleToUser && (node.contentDescription?.toString() == action || (action == "Rename" && node.text?.toString() == action)) &&
                belongsToOwnedRow(node)
        }
        repeat(6) {
            if (ownedVisibleActions().isEmpty()) scrollHorizontally(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
        }
        val actions = ownedVisibleActions()
        val chosen = actions.minByOrNull { node -> kotlin.math.abs(Rect().also { node.getBoundsInScreen(it) }.centerY() - target.centerY()) }
        check(chosen != null) { "Owned action $action missing for $name; filename centers=$filenameCenters target=$target\n${tree()}" }
        val bounds = Rect().also { chosen.getBoundsInScreen(it) }
        check(belongsToOwnedRow(chosen)) { "Row action ownership not established" }
        performClick(chosen)
    }

    private fun originalConsentJourney(phaseOverride: String? = null) {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        val phase = phaseOverride ?: InstrumentationRegistry.getArguments().getString("rea_permission_phase")
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
                            assertPresentation(firstSelection, "Limited access", expectLimitedBanner = true)
                            scenario.recreate()
                            await("limited state after recreation") { source.currentAccessState() == "Limited access" }
                            assertEquals(firstSelection, visibleFixtureUris())
                            click { it.text?.toString() == "Change" }
                            click { it.viewIdResourceName?.endsWith("permission_allow_selected_button") == true }
                            selectBothFixtures()
                            confirmSelection()
                            await("expanded selection") { visibleFixtureUris() == createdUris.toSet() }
                            assertEquals("Reselecting does not grant full-library access", "Limited access", source.currentAccessState())
                            assertPresentation(createdUris.toSet(), "Limited access", expectLimitedBanner = true)
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

    private fun captureMixedSelection(stage: String) {
        check(stage in setOf("before_expansion_click", "after_expansion_click", "after_expansion_confirm"))
        val rows = allNodes().filter { it.isVisibleToUser }.map { node ->
            fun kind(value: String?) = when {
                value?.startsWith("Video taken on", true)==true -> "video"
                value?.startsWith("Photo taken on", true)==true -> "photo"
                value in setOf("Photos", "Videos", "Albums", "Selected", "Select photos", "Select photos and videos") -> value
                value?.matches(Regex("""(?i)(add|allow|done)( \(\d+\))?"""))==true -> value
                else -> null
            }
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            JSONObject().put("package", node.packageName?.toString()).put("id", node.viewIdResourceName)
                .put("kind", kind(node.contentDescription?.toString()) ?: kind(node.text?.toString()))
                .put("bounds", bounds.toShortString()).put("checked", node.isChecked).put("selected", node.isSelected)
                .put("checkable", node.isCheckable).put("enabled", node.isEnabled).put("clickable", node.isClickable)
                .put("parent_id", node.parent?.viewIdResourceName)
        }
        val proof = JSONObject().put("stage",stage).put("fixture",token)
            .put("access", AndroidMediaInventorySource(context).currentAccessState())
            .put("visible_fixture_uris",JSONArray(visibleFixtureUris().toList()))
            .put("expected_fixture_uris",JSONArray(createdUris.toList())).put("nodes",JSONArray(rows))
        File(context.getExternalFilesDir(null),"qa71-selected-mixed-$stage.json").writeText(proof.toString(2))
        automation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null),"qa71-selected-mixed-$stage.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle()
        }
    }

    private fun selectBothFixtures() {
        if (InstrumentationRegistry.getArguments().getString("qa71_permission_phase") == "selected_mixed") {
            await("newest owned video tile in mixed picker") {
                find { it.contentDescription?.toString()?.startsWith("Video taken on", true) == true } != null
            }
            val video = requireNotNull(find { it.contentDescription?.toString()?.startsWith("Video taken on", true) == true })
            val bounds = Rect().also { video.getBoundsInScreen(it) }
            performClick(video)
            await("selected marker on the observed newest video tile") {
                find { node ->
                    val marker = Rect().also { node.getBoundsInScreen(it) }
                    node.contentDescription?.toString() == "Selected" && bounds.contains(marker.centerX(), marker.centerY())
                } != null
            }
            return
        }
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
        // API36 can retain stale child visibility after navigation; query the actual current tree.
        check(automation.clearCache()) { "Could not refresh the native accessibility snapshot" }
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
                        db.videoItemDao().updateVideo(image.copy(status = "processed", reviewDecision = "keep", manualTranscript = "QA permission retained notes", lastCompletedStep = "result_saved"))
                        db.analysisResultDao().insertAnalysis(com.example.data.AnalysisResult(videoId = image.id,
                            descriptionLine1 = "QA71 retained summary", descriptionLine2 = "QA71 retained details",
                            categoryLevel1 = "QA", categoryLevel2 = "Permission", categoryLevel3 = "Retained",
                            formattedActionPlan = "QA71 retained step", urls = emptyList(), codeOrCommands = emptyList(), evidenceJson = "[]", source = "edited"))
                        val binFixture = InstrumentationRegistry.getArguments().getString("qa71_bin_fixture") == "true"
                        receipt.edit().putBoolean("binFixture", binFixture).commit()
                        if (binFixture) {
                            db.binDao().add(com.example.data.BinEntry(image.id, "keep", null))
                            db.videoItemDao().updateReviewDecision(image.id, "ready_to_delete")
                        }
                        val tag = db.customTagDao().insert(CustomTag(name = token + "_retained"))
                        db.customTagDao().assign(MediaTagCrossRef(image.id, tag))
                        receipt.edit().putLong("imageId", image.id).putLong("tagId", tag).commit()
                    }
                    // State seed is explicit Room setup; post-change Sources is real production UI.
                    val category = runBlocking { db.videoItemDao().getVideoByIdDirect(image.id)?.sourceCategory } ?: "Other"
                    click { it.text?.toString() == category }; await("owned image visible with Keep") { find { it.text?.toString() == image.displayName } != null && (receipt.getBoolean("binFixture", false) || find { it.text?.toString() == "Keep" } != null) }
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
                    assertParityAfterPermission(granted)
                    if (InstrumentationRegistry.getArguments().getString("qa71_t057_rename_retry") == "true") t057UnavailableThenRestoredNativeRename(granted)
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
        assertEquals(if (receipt.getBoolean("binFixture", false)) "ready_to_delete" else "keep", row.reviewDecision)
        assertEquals("QA permission retained notes", row.manualTranscript)
        val analysis = requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(id))
        assertEquals("QA71 retained summary", analysis.descriptionLine1)
        assertEquals("QA71 retained details", analysis.descriptionLine2)
        assertEquals("QA71 retained step", analysis.formattedActionPlan)
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
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("qa71_permission_phase") ?: args.getString("rea_permission_phase") ?: "runtime"
        File(context.getExternalFilesDir(null), "rea-permission-$phase.txt").writeText(text)
        println(text)
    }
    /** T057: original Rename action is conditional; revoked media is hidden after real Refresh. */
    private fun t057UnavailableThenRestoredNativeRename(granted: Boolean) {
        check(!receipt.getBoolean("binFixture", false)) { "T057 requires the non-Bin retained fixture" }
        val id = receipt.getLong("imageId", -1)
        val original = runBlocking { requireNotNull(db.videoItemDao().getVideoByIdDirect(id)) }
        check(original.uriString in createdUris && original.relativePath in paths())
        val oldName = token + "_a.png"
        assertEquals(oldName, original.displayName)
        val summary = runBlocking { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(id)) }
        assertEquals("QA permission retained notes", original.manualTranscript)
        assertOwnedPhysicalBytes()
        if (!granted) navigateDestination("Queue")
        else {
            assertNotNull("Parity route must end on the actual Queue heading", find { it.text?.toString() == "Queue" })
            assertNotNull("Queue navigation must be closed before restored Rename", find { it.contentDescription?.toString() == "Open menu" })
            assertNull("Do not reopen an already selected Queue menu", find { it.contentDescription?.toString() == "Close menu" })
        }
        if (!granted) {
            assertCannotRead(original.uriString)
            assertFalse(original.isCurrentlyAvailable)
            assertNull("Original step says Rename only IF still shown; unavailable Queue row must be absent", find { it.text?.toString() == oldName })
            // No hidden action is clicked and no failure toast is claimed for this conditional branch.
            assertEquals(oldName, runBlocking { requireNotNull(db.videoItemDao().getVideoByIdDirect(id)).displayName })
            val physical = shell("content query --uri ${original.uriString} --projection _display_name:relative_path")
            check(physical.contains("_display_name=$oldName") && physical.contains("relative_path=${original.relativePath}"))
            receipt.edit().putString("t057Uri", original.uriString).putLong("t057Id", id).putString("t057OriginalName", oldName).putBoolean("t057UnavailableVerified", true).commit()
            writeEvidence("T057 unavailable actual_native_AppInfo_denial requires_host_receipt=true actual_Refresh=true Queue_row_and_Rename_hidden=true original_name_kept=true conditional_failure_toast=not_applicable")
            return
        }
        check(receipt.getBoolean("t057UnavailableVerified", false))
        assertEquals(receipt.getLong("t057Id", -1), id)
        assertEquals(receipt.getString("t057Uri", null), original.uriString)
        assertTrue(original.isCurrentlyAvailable)
        assertEquals(1, runBlocking { db.videoItemDao().getAllVideosDirect().count { it.uriString == original.uriString } })
        resolverReadForT057(original.uriString)
        // rowAction first reveals the exact owned filename before choosing its Rename control.
        // Fixture basename is lowercase ASCII with underscores; no OCR is seeded.
        val expectedName = oldName.substringBeforeLast('.').take(15) + "_vid.png"
        var renamed = false
        try {
            rowAction(oldName, "Rename")
            await("native rename result or Android write authorization") {
                runBlocking { db.videoItemDao().getVideoByIdDirect(id)?.displayName == expectedName } || t057WritePositive() != null
            }
            t057WritePositive()?.let { positive ->
                val text = allNodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.joinToString(" ")
                check(text.contains("modify", true) || text.contains("change", true) || text.contains("edit", true)) { "Android dialog does not explain write authorization" }
                performClick(positive)
            }
            await("actual Queue Rename updates same identity") { runBlocking { db.videoItemDao().getVideoByIdDirect(id)?.displayName == expectedName } }
            renamed = true
            val current = runBlocking { requireNotNull(db.videoItemDao().getVideoByIdDirect(id)) }
            assertEquals(id, current.id); assertEquals(original.uriString, current.uriString)
            assertEquals(original.manualTranscript, current.manualTranscript); assertEquals(original.reviewDecision, current.reviewDecision)
            assertEquals(summary, runBlocking { db.analysisResultDao().getAnalysisForVideoDirect(id) })
            val physical = shell("content query --uri ${original.uriString} --projection _display_name:relative_path")
            check(physical.contains("_display_name=$expectedName") && physical.contains("relative_path=${original.relativePath}"))
            assertEquals(1, runBlocking { db.videoItemDao().getAllVideosDirect().count { it.uriString == original.uriString } })
            assertEquals(2, runBlocking { db.videoItemDao().getAllVideosDirect().size })
            assertOwnedPhysicalBytes()
            await("new filename visible in Queue") { find { it.text?.toString() == expectedName } != null }
            assertNull(find { it.text?.toString() == oldName })
            writeEvidence("T057 restored actual_native_AppInfo_grant requires_host_receipt=true actual_Refresh_and_Queue_Rename=true same_ID_URI=true no_duplicates=true unchanged_notes_summary_bytes=true new_name=$expectedName")
            receipt.edit().putBoolean("t057RenameVerified", true).commit()
        } finally {
            // Reset only this exact shell-owned fixture so the shared retained-phase cleanup remains valid.
            val metadata = shell("content query --uri ${original.uriString} --projection _display_name:relative_path")
            if (renamed || metadata.contains("_display_name=$expectedName")) {
                check(metadata.contains("_display_name=$expectedName") && metadata.contains("relative_path=${original.relativePath}"))
                check(original.uriString in createdUris && original.uriString in expectedByteHashes)
                shell("content update --uri ${original.uriString} --bind _display_name:s:$oldName")
                refreshActualSources(2, denyRemaining = false)
                assertRetainedState(); assertOwnedPhysicalBytes()
            }
        }
    }
    private fun resolverReadForT057(uri: String) {
        check(uri in createdUris)
        val raw = context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
        assertEquals(expectedByteHashes[uri], sha256(raw))
    }
    private fun t057WritePositive(): AccessibilityNodeInfo? {
        if (automation.rootInActiveWindow?.packageName?.toString() !in setOf("com.google.android.providers.media.module", "com.android.providers.media.module", "com.android.providers.media", "android")) return null
        return find { it.isVisibleToUser && it.isEnabled && it.viewIdResourceName == "android:id/button1" && it.text?.toString()?.lowercase() in setOf("allow", "modify", "change", "edit") }
    }

}
