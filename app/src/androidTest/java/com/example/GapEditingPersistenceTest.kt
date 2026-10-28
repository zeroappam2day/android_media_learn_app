package com.example

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Android picker/confirmation tests restricted to synthetic media in the isolated QA package. */
@RunWith(AndroidJUnit4::class)
class GapEditingPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val automation get() = instrumentation.uiAutomation
    private val db get() = VideoDatabase.getDatabase(context)
    private val receipt get() = context.getSharedPreferences("qa71_editing_receipt", 0)
    private val phase get() = requireNotNull(InstrumentationRegistry.getArguments().getString("qa71_phase")) { "Explicit qa71_phase is required" }
    private val token = if (InstrumentationRegistry.getArguments().getString("qa71_phase") == "seed")
        "QA71Editing" + UUID.randomUUID().toString().replace("-", "").take(12)
        else requireNotNull(context.getSharedPreferences("qa71_editing_receipt", 0).getString("token", null))
    private val renamedName get() = token.lowercase().take(15) + "_vid.png"
    private val physicalPath get() = "Pictures/$token/Camera/"
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val parentPath = "Documents/QA/$token"
    private val parentTree get() = treeUri(parentPath)
    private val images = linkedMapOf<Uri, ByteArray>()
    private val mediaIds = mutableSetOf<Long>()
    private val createdDocuments = linkedSetOf<Uri>()
    private val evidence = JSONArray()
    private var originalFolders: Set<String>? = null
    private var permitted = false
    private val folderValidationEvent = AtomicBoolean(false)

    @Before fun isolatedPackageAndRuntimeOnly() {
        check(context.packageName.endsWith(".qa.preview"))
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(Build.VERSION.SDK_INT == 36)
        check(phase in setOf("seed", "verify_long_seed_short", "verify_short", "cleanup"))
        check(context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
        check(context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        if (phase == "seed") {
            check(!receipt.contains("token")) { "An earlier owned editing phase must be verified/cleaned first" }
            check(query { db.videoItemDao().getAllVideosDirect().isEmpty() })
            check(!prefs.getBoolean("auto_ingest_enabled", false))
            originalFolders = prefs.getStringSet("target_folders", null)?.toSet()
            receipt.edit().putString("token", token).putBoolean("hadFolders", prefs.contains("target_folders"))
                .putString("oldFolders", JSONArray(originalFolders.orEmpty().toList()).toString())
                .putBoolean("hadProvider", prefs.contains("llm_provider")).putString("oldProvider", prefs.getString("llm_provider", ""))
                .putString("records", "[]").putString("documents", "[]").commit()
            prefs.edit().putString("llm_provider", "local").putStringSet("target_folders", setOf(treeUri(physicalPath.trimEnd('/')).toString())).commit()
        } else {
            check(receipt.getString("token", null) == token)
            val records = JSONArray(requireNotNull(receipt.getString("records", null)))
            for (i in 0 until records.length()) {
                val r = records.getJSONObject(i); val uri = Uri.parse(r.getString("uri"))
                images[uri] = android.util.Base64.decode(r.getString("bytes"), android.util.Base64.NO_WRAP)
                mediaIds += r.getLong("id")
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { c ->
                    check(c.moveToFirst()); check(c.getString(0) == context.packageName); check(c.getString(1) == physicalPath)
                }
                assertBytes(uri)
            }
            check(query { db.videoItemDao().getAllVideosDirect().all { it.id in mediaIds } })
            val documents = JSONArray(requireNotNull(receipt.getString("documents", null)))
            for (i in 0 until documents.length()) {
                val uri = Uri.parse(documents.getString(i)); check(DocumentsContract.getDocumentId(uri).startsWith("primary:$parentPath/")); createdDocuments += uri
            }
            val old = JSONArray(requireNotNull(receipt.getString("oldFolders", null)))
            originalFolders = (0 until old.length()).map { old.getString(it) }.toSet()
        }
        permitted = true
    }

    private fun cleanupNativeOwned() {
        if (!permitted) return
        automation.setOnAccessibilityEventListener(null)
        // Return from system UI before cleanup; never click a positive confirmation during teardown.
        repeat(4) { if (activePackage() != context.packageName) pressBack() }
        query {
            mediaIds.forEach { id -> db.analysisResultDao().deleteAnalysisForVideo(id); db.extractedFrameDao().deleteFramesForVideo(id); db.binDao().remove(id); db.videoItemDao().getVideoByIdDirect(id)?.let { db.videoItemDao().deleteVideo(it) } }
        }
        images.keys.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        createdDocuments.toList().asReversed().forEach { uri ->
            check(DocumentsContract.getDocumentId(uri).startsWith("primary:$parentPath/"))
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }.onFailure { record("cleanup_document_permission", listOf("owned document cleanup requires retained write grant")) }
        }
        val ownedParentDocument = DocumentsContract.buildDocumentUriUsingTree(parentTree, "primary:$parentPath")
        check(DocumentsContract.getDocumentId(ownedParentDocument) == "primary:$parentPath")
        runCatching { DocumentsContract.deleteDocument(resolver, ownedParentDocument) }
            .onFailure { record("cleanup_owned_parent_permission", listOf("owned directory cleanup needs retained write grant")) }
        context.getSharedPreferences("app_prefs", 0).edit().putStringSet("target_folders", originalFolders.orEmpty()).commit()
        resolver.persistedUriPermissions.filter { it.uri == parentTree || it.uri.toString().contains(Uri.encode(token)) }.forEach { grant ->
            val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(grant.uri, flags) }
        }
        File(context.getExternalFilesDir(null), "storage-$token.json").writeText(JSONObject().put("fixture", token).put("api", Build.VERSION.SDK_INT).put("events", evidence).toString(2))
    }

    fun t089_cancelActualTrashConfirmationPreservesBytesAndRow() {
        val fixture = image("cancelTrash"); openPreview(fixture.second)
        cleanupAction("Move media to trash"); storageDialog("trash", 1); cancelSystemDialog()
        assertBytes(fixture.first); assertEquals(0, trashFlag(fixture.first)); assertTrue(query { db.videoItemDao().getVideoByIdDirect(fixture.second)?.isCurrentlyAvailable == true })
    }
    fun t090_confirmTrashAffectsOnlyChosenOwnedImage() {
        val chosen = image("trash"); val untouched = image("untouched"); openPreview(chosen.second)
        cleanupAction("Move media to trash"); storageDialog("trash", 1); approveSystemDialog("trash")
        await("owned image trashed") { trashFlag(chosen.first) == 1 }
        assertBytes(chosen.first); assertBytes(untouched.first); assertEquals(0, trashFlag(untouched.first))
        await("trashed row reconciled") { query { db.videoItemDao().getVideoByIdDirect(chosen.second)?.isCurrentlyAvailable == false } }
    }
    fun t091_cancelActualDeleteConfirmationPreservesBytesAndRow() {
        val fixture = image("cancelDelete"); openPreview(fixture.second)
        cleanupAction("Delete media forever"); storageDialog("delete", 1); cancelSystemDialog()
        assertBytes(fixture.first); assertTrue(query { db.videoItemDao().getVideoByIdDirect(fixture.second)?.isCurrentlyAvailable == true })
    }
    fun t092_confirmDeleteAffectsOnlyChosenOwnedImage() {
        val chosen = image("delete"); val untouched = image("untouched"); openPreview(chosen.second)
        cleanupAction("Delete media forever"); storageDialog("delete", 1); approveSystemDialog("delete")
        await("owned image deleted") { !exists(chosen.first) }; assertBytes(untouched.first)
        await("deleted row reconciled") { query { db.videoItemDao().getVideoByIdDirect(chosen.second)?.isCurrentlyAvailable == false } }
    }
    fun t157_cancelDeleteAllTwiceKeepsOwnedFilesAndBin() {
        val first = image("binA"); val second = image("binB"); binFixture(first.second, "keep"); binFixture(second.second, "review_later"); openBin()
        repeat(2) { assertBinContainsOnlyOwnedMedia(); compose.onNodeWithTag("delete_bin").performClick(); storageDialog("delete", 2); cancelSystemDialog() }
        assertBytes(first.first); assertBytes(second.first)
        assertNotNull(query { db.binDao().entry(first.second) }); assertNotNull(query { db.binDao().entry(second.second) })
    }
    fun t158_t159_deleteAllIncludesOnlyAvailableOwnedBinFiles() {
        val first = image("binA"); val second = image("binB"); val untouched = image("outsideBin")
        binFixture(first.second, "keep"); binFixture(second.second, "review_later"); openBin(); assertBinContainsOnlyOwnedMedia()
        compose.onNodeWithTag("delete_bin").performClick(); storageDialog("delete", 2); approveSystemDialog("delete")
        await("two owned Bin files deleted") { !exists(first.first) && !exists(second.first) }; assertBytes(untouched.first)
        await("two Bin entries reconciled") { query { db.binDao().entry(first.second) == null && db.binDao().entry(second.second) == null } }
    }
    fun t182_restoreOwnedAndroidTrashAndFindItAfterProductionRefresh() {
        val fixture = image("restore"); openPreview(fixture.second); cleanupAction("Move media to trash")
        storageDialog("trash", 1); approveSystemDialog("trash"); await("trashed") { trashFlag(fixture.first) == 1 }
        await("trashed row unavailable before repair") { query { db.videoItemDao().getVideoByIdDirect(fixture.second)?.isCurrentlyAvailable == false } }
        assertEquals(1, resolver.update(fixture.first, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null))
        // Fixture-only scope prevents a global refresh from admitting unrelated emulator media.
        context.getSharedPreferences("app_prefs", 0).edit().putStringSet("target_folders", setOf(parentTree.toString())).commit()
        assertBytes(fixture.first); closePreviewIfPresent(); navigate("sources")
        compose.onNodeWithTag("inventory_scan_button").performClick()
        await("restored owned file found by production refresh") { query { db.videoItemDao().getVideoByIdDirect(fixture.second)?.isCurrentlyAvailable == true } }
        assertTrue("Scoped refresh must not add unrelated index records", query { db.videoItemDao().getAllVideosDirect().all { it.id in mediaIds } })
        val category = query { requireNotNull(db.videoItemDao().getVideoByIdDirect(fixture.second)).sourceCategory } ?: "Other"
        compose.onNodeWithTag("source_category_$category").performScrollTo().performClick()
        compose.onNodeWithText(fixtureName(fixture.first)).performScrollTo().assertIsDisplayed()
    }
    fun t214_t215_recoverRestoredPhysicalFileAndRepeatedTapKeepsPriorDecision() {
        val fixture = image("recover"); binFixture(fixture.second, "review_later")
        resolver.update(fixture.first, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
        resolver.update(fixture.first, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
        openBin(); val action = hasContentDescription("Recover") and hasAnyAncestor(hasTestTag("bin_${fixture.second}"))
        compose.onNode(action, useUnmergedTree = true).performClick()
        // A second observed click may legitimately disappear with the removed row; never target another item.
        if (compose.onAllNodes(action, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) compose.onNode(action, useUnmergedTree = true).performClick()
        await("prior decision recovered") { query { db.binDao().entry(fixture.second) == null && db.videoItemDao().getVideoByIdDirect(fixture.second)?.reviewDecision == "review_later" } }
        assertBytes(fixture.first); compose.onNodeWithTag("bin_${fixture.second}").assertDoesNotExist()
    }
    fun t216_returnFromOtherAppAndCancelPendingDeleteSafely() {
        val fixture = image("backgroundCancel"); val before = query { db.videoItemDao().getVideoByIdDirect(fixture.second) }
        openPreview(fixture.second); cleanupAction("Delete media forever"); storageDialog("delete", 1)
        injectKey(KeyEvent.KEYCODE_HOME)
        await("actual Home excursion") { activePackage() != context.packageName && activePackage() !in confirmationPackages }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("return to QA or retained Android confirmation") { activePackage() == context.packageName || activePackage() in confirmationPackages }
        if (activePackage() in confirmationPackages) {
            storageDialog("delete", 1); cancelSystemDialog()
            await("retained confirmation canceled") { activePackage() == context.packageName }
        } else record("android_dismissed_confirmation_on_home", listOf("owned bytes and saved row checked before retry"))
        assertBytes(fixture.first); assertEquals(before, query { db.videoItemDao().getVideoByIdDirect(fixture.second) })
        // Android may dismiss its own question on Home. Deliberately reopen the same owned
        // request, observe its real dialog again, and prove an explicit Cancel is safe.
        closePreviewIfPresent(); openPreview(fixture.second); cleanupAction("Delete media forever")
        storageDialog("delete", 1); cancelSystemDialog(); await("retried confirmation canceled") { activePackage() == context.packageName }
        assertBytes(fixture.first); assertEquals(before, query { db.videoItemDao().getVideoByIdDirect(fixture.second) })
    }
    fun t185_olderAndroidActionUnavailableBranchNotApplicableOnApi36() {
        Assume.assumeTrue("Not applicable: API${Build.VERSION.SDK_INT} supports Android11+ storage requests; older API needs its own runtime", Build.VERSION.SDK_INT < 30)
        fail("Run this scenario with a separate legacy-API-compatible test class")
    }

    fun t033_cancelWatchFolderPickerDoesNotAddFolder() {
        image("folderParent"); val before = folders(); navigate("folders"); compose.onNodeWithContentDescription("Add watch folder").performClick()
        documentsUi(); pressBack(); await("picker canceled") { activePackage() == context.packageName }; assertEquals(before, folders())
    }
    fun t032_t034_t035_chooseSameWatchFolderTwiceThenRemoveOnlyChoice() {
        val fixture = image("watch"); val before = folders(); chooseParentAsWatchFolder()
        await("owned watch folder saved") { parentTree.toString() in folders() }; assertBytes(fixture.first)
        openFolderPicker(); navigateOwnedParent(); clickObserved("USE THIS FOLDER"); acceptFolderGrantIfShown()
        await("duplicate picker returned") { activePackage() == context.packageName }
        assertEquals(1, folders().count { it == parentTree.toString() }); assertEquals(before + parentTree.toString(), folders())
        val bounds = compose.onNodeWithText(parentPath).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNode(hasContentDescription("Remove") and SemanticsMatcher("Remove only the owned watch folder") { it.boundsInRoot.center.y in bounds.top..bounds.bottom }, useUnmergedTree = true).performClick()
        await("owned watch choice removed") { folders() == before }; assertBytes(fixture.first)
    }
    fun t036_reselectOwnedFolderAfterItsReadGrantIsRevoked() {
        val fixture = image("regrant"); chooseParentAsWatchFolder()
        val granted = resolver.persistedUriPermissions.first { it.uri == parentTree }
        resolver.releasePersistableUriPermission(parentTree, (if (granted.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (granted.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
        assertTrue(resolver.persistedUriPermissions.none { it.uri == parentTree && it.isReadPermission })
        openFolderPicker(); navigateOwnedParent(); clickObserved("USE THIS FOLDER"); acceptFolderGrantIfShown()
        await("owned folder read grant restored") { resolver.persistedUriPermissions.any { it.uri == parentTree && it.isReadPermission } }
        assertEquals(1, folders().count { it == parentTree.toString() }); assertBytes(fixture.first)
    }
    fun t160_exportActualSaveWritesReadableJsonAndPreservesImage() {
        val fixture = image("export"); chooseParentAsWatchFolder(); val filename = "$token-metadata.json"
        exportPicker(filename); navigateOwnedParent(); saveDocument()
        val uri = ownedDocument(filename); createdDocuments += uri
        await("export document written") { runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()).has("media") } == true }.getOrDefault(false) }
        val json = resolver.openInputStream(uri)!!.bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(1, json.getInt("schema")); val rows = json.getJSONArray("media")
        assertTrue((0 until rows.length()).any { rows.getJSONObject(it).getString("uri") == fixture.first.toString() })
        assertBytes(fixture.first)
    }
    fun t161_cancelActualExportCreatesNoFile() {
        val fixture = image("exportCancel"); chooseParentAsWatchFolder(); val filename = "$token-canceled.json"
        exportPicker(filename); navigateOwnedParent(); cancelDocumentPicker()
        assertFalse(documentExists(ownedDocument(filename))); assertBytes(fixture.first)
    }
    fun t162_emptyInventoryExportContainsEmptyMediaArray() {
        assertTrue("T162 requires an empty QA index", query { db.videoItemDao().getAllVideosDirect().isEmpty() })
        image("directoryAnchor", index = false); chooseParentAsWatchFolder(); val filename = "$token-empty.json"
        assertTrue(query { db.videoItemDao().getAllVideosDirect().isEmpty() })
        exportPicker(filename); navigateOwnedParent(); saveDocument(); val uri = ownedDocument(filename); createdDocuments += uri
        await("empty export written") { runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()).getJSONArray("media").length() == 0 } == true }.getOrDefault(false) }
    }
    fun t163_existingFilenameRequiresObservedReplacementDecision() {
        val fixture = image("existing"); chooseParentAsWatchFolder(); val filename = "$token-existing.json"
        exportPicker(filename); navigateOwnedParent(); saveDocument(); val uri = ownedDocument(filename); createdDocuments += uri
        await("first export complete and parseable") { runCatching { readExport(uri).getJSONArray("media").length() == 1 }.getOrDefault(false) }
        val beforeHash = resolver.openInputStream(uri)!!.use { digest(it.readBytes()) }
        val beforeChildren = ownedJsonChildren().keys
        exportPicker(filename); navigateOwnedParent(); clickObserved("SAVE")
        await("actual replace prompt or returned numbered-file save") { systemNodes().any { it.isVisibleToUser && it.text?.toString()?.contains("replace", true) == true } || activePackage() == context.packageName }
        if (activePackage() != context.packageName) {
            recordControls("existing_filename_prompt"); cancelSystemDialog()
            await("replacement canceled") { activePackage() == context.packageName || activePackage() in documentPackages }
            if (activePackage() in documentPackages) pressBack()
            await("replacement cancellation returned") { activePackage() == context.packageName }
            assertEquals(beforeChildren, ownedJsonChildren().keys)
        } else {
            var newDocuments = emptyMap<String, Uri>()
            await("exactly one new owned numbered export") {
                newDocuments = ownedJsonChildren().filterKeys { it !in beforeChildren }
                newDocuments.size == 1
            }
            val (newName, newUri) = newDocuments.entries.single().let { it.key to it.value }
            val numberedName = Regex(Regex.escape(token + "-existing") + " ?\\([1-9][0-9]*\\)\\.json")
            check(numberedName.matches(newName)) { "Observed new output is not the expected token-owned numbered filename" }
            createdDocuments += newUri
            await("numbered export complete and parseable") { runCatching { readExport(newUri).getJSONArray("media").length() == 1 }.getOrDefault(false) }
            val document = readExport(newUri); assertEquals(1, document.getInt("schema"))
            val row = document.getJSONArray("media").getJSONObject(0)
            assertEquals(fixture.first.toString(), row.getString("uri")); assertEquals(fixtureName(fixture.first), row.getString("name"))
            record("observed_numbered_export_verified", listOf(newName))
        }
        assertEquals(beforeHash, resolver.openInputStream(uri)!!.use { digest(it.readBytes()) }); assertBytes(fixture.first)
    }
    fun t233_t234_t235_t236_createCancelValidateAndDuplicateFolderInActualPicker() {
        image("folderAnchor"); chooseParentAsWatchFolder(); val before = folders()
        openFolderPicker(); navigateOwnedParent(); newFolder(); setSystemEdit(""); recordControls("empty_folder_name")
        val positive = systemNodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
        assertNotNull("Observe Android folder-name positive control", positive)
        if (positive!!.isEnabled) {
            folderValidationEvent.set(false); clickNode(positive)
            await("empty folder name visibly rejected") { folderValidationEvent.get() || systemNodes().any { it.isVisibleToUser && it.error != null } }
            assertTrue(systemNodes().any { it.isVisibleToUser && it.isEditable && (it.text.isNullOrEmpty() || it.isShowingHintText) })
        }
        val child = "$token-child"; setSystemEdit(child); acceptFolderName(); createdDocuments += ownedDocument(child)
        await("new folder appears") { findObserved(child) != null }
        assertEquals("Creating a folder must not automatically register it", before, folders())
        navigateOwnedParent()
        newFolder(); setSystemEdit("$token-canceled"); cancelSystemDialog()
        assertNull(findObserved("$token-canceled")); assertEquals(before, folders())
        val marker = requireNotNull(DocumentsContract.createDocument(resolver, ownedDocument(child), "text/plain", "$token-marker.txt"))
        createdDocuments += marker
        val markerBytes = "Owned existing folder must survive $token".toByteArray()
        resolver.openOutputStream(marker)!!.use { it.write(markerBytes) }
        newFolder(); setSystemEdit(child); recordControls("duplicate_folder_name_before_save")
        val button = systemNodes().first { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
        folderValidationEvent.set(false)
        if (button.isEnabled) clickNode(button)
        await("duplicate resolved without replacing existing folder") { findObserved("$child (1)") != null || !button.isEnabled || folderValidationEvent.get() || systemNodes().any { it.isVisibleToUser && (it.error != null || it.text?.toString()?.contains("exist", true) == true) } }
        if (findObserved("$child (1)") != null) {
            createdDocuments += ownedDocument("$child (1)")
            assertTrue(documentExists(ownedDocument("$child (1)")))
            record("duplicate_folder_auto_named", listOf("$child (1)"))
        }
        recordControls("duplicate_folder_name_after_save"); assertEquals(before, folders())
        if (systemNodes().any { it.isVisibleToUser && it.isEditable }) cancelSystemDialog()
        assertArrayEquals(markerBytes, resolver.openInputStream(marker)!!.use { it.readBytes() })
        cancelDocumentPicker()
        assertTrue(documentExists(ownedDocument(child))); assertFalse(documentExists(ownedDocument("$token-canceled")))
    }
    fun t229_topLevelWatchFolderCannotBeSelected() {
        image("rootAnchor"); openFolderPicker(); navigatePrimaryRoot()
        val use = findObserved("USE THIS FOLDER"); recordControls("top_level_folder_restriction")
        assertTrue("Android must disable or omit root folder selection", use == null || !use.isEnabled)
        pressBack(); await("root picker canceled") { activePackage() == context.packageName }
    }
    fun t230_t237_observeActualPickerSortAndViewWithoutChangingMedia() {
        val fixture = image("sortAnchor"); chooseParentAsWatchFolder(); exportPicker("$token-navigation.json"); navigateOwnedParent()
        recordControls("save_picker_controls")
        // Discover visible, named system controls before clicking; an absent affordance is a real coverage gap.
        var sort = systemNodes().firstOrNull { it.isVisibleToUser && (it.contentDescription?.toString()?.contains("sort", true) == true || it.text?.toString()?.contains("Sort by", true) == true) }
        if (sort == null) {
            clickObserved("More options")
            await("sort menu opened") { findObserved("Sort by...") != null }
            recordControls("save_picker_overflow_controls")
            sort = findObserved("Sort by...")
        }
        assertNotNull("No observed DocumentsUI sort control: T237 remains unexecuted", sort); clickNode(sort!!)
        await("observed filename sorting choice") { findObserved("File name (A to Z)") != null || findObserved("Name") != null }
        recordControls("sort_choices")
        val name = findObserved("File name (A to Z)") ?: findObserved("Name"); assertNotNull("Observe Name sorting choice", name); clickNode(name!!)
        await("sort choices dismissed") { findObserved("File name (A to Z)") == null && findObserved("Name") == null }
        val view = systemNodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.let { text -> text.contains("grid", true) || text.contains("list", true) } == true }
        assertNotNull("No observed DocumentsUI view control: T237 remains unexecuted", view); clickNode(view!!); recordControls("view_changed")
        cancelDocumentPicker(); assertBytes(fixture.first)
    }

    private fun image(suffix: String, index: Boolean = true): Pair<Uri, Long> {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(29, 110, 185)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token-$suffix.png"); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$parentPath/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        images[uri] = bytes; resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val id = if (!index) -1L else query { db.videoItemDao().insertVideo(VideoItem(mediaStoreId = android.content.ContentUris.parseId(uri), uriString = uri.toString(), displayName = "$token-$suffix.png",
            relativePath = "$parentPath/", mimeType = "image/png", sizeBytes = bytes.size.toLong(), durationMs = null, dateAdded = null, dateModified = null,
            reviewDecision = "keep", mediaCollection = "image", volumeName = "external_primary", sourceCategory = token)) }.also { mediaIds += it }
        return uri to id
    }
    private fun fixtureName(uri: Uri) = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { check(it.moveToFirst()); it.getString(0) }
    private fun exists(uri: Uri): Boolean { check(uri in images); return resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }, null)?.use { it.moveToFirst() } == true }
    private fun trashFlag(uri: Uri): Int { check(uri in images); return resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }, null)?.use { if (it.moveToFirst()) it.getInt(0) else -1 } ?: -1 }
    private fun assertBytes(uri: Uri) { assertEquals(digest(requireNotNull(images[uri])), resolver.openInputStream(uri)!!.use { digest(it.readBytes()) }) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun binFixture(id: Long, prior: String) { check(id in mediaIds); query { db.binDao().add(BinEntry(id, prior, null)); db.videoItemDao().updateReviewDecision(id, "ready_to_delete") } }
    private fun assertBinContainsOnlyOwnedMedia() { assertTrue("Delete all may include only owned fixtures", query { db.binDao().observeEntries().first().all { it.mediaId in mediaIds } }) }
    private fun navigate(destination: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_$destination").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
    }
    private fun openPreview(id: Long) {
        check(id in mediaIds); navigate("queue"); val value = query { requireNotNull(db.videoItemDao().getVideoByIdDirect(id)).displayName }
        val y = compose.onNodeWithText(value).performScrollTo().fetchSemanticsNode().boundsInRoot.center.y
        val actions = compose.onAllNodes(hasContentDescription("Preview") and hasClickAction()).fetchSemanticsNodes()
        check(actions.isNotEmpty()) { "Missing Preview action for owned media" }
        val index = actions.indices.minBy { kotlin.math.abs(actions[it].boundsInRoot.center.y - y) }
        check(kotlin.math.abs(actions[index].boundsInRoot.center.y - y) < 70f) { "Preview ownership not proven" }
        compose.onAllNodes(hasContentDescription("Preview") and hasClickAction())[index].performClick()
        compose.onNodeWithText("Media Details Preview: $value").assertIsDisplayed()
    }
    private fun closePreviewIfPresent() { if (compose.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close").performClick() }
    private fun cleanupAction(label: String) { compose.onNode(hasContentDescription(label) and hasClickAction()).performScrollTo().assertIsDisplayed().performClick() }
    private fun openBin() { navigate("sources"); compose.onNodeWithTag("open_bin").performClick(); compose.onNodeWithTag("bin_screen").assertIsDisplayed() }
    private fun folders() = context.getSharedPreferences("app_prefs", 0).getStringSet("target_folders", emptySet())?.toSet().orEmpty()
    private fun openFolderPicker() { navigate("folders"); compose.onNodeWithContentDescription("Add watch folder").performClick(); documentsUi() }
    private fun chooseParentAsWatchFolder() {
        openFolderPicker(); navigateOwnedParent(); clickObserved("USE THIS FOLDER"); acceptFolderGrantIfShown()
        await("watch folder saved") { parentTree.toString() in folders() }
        // Write grant is retained only for deleting this test's own DocumentsUI-created fixtures.
        runCatching { resolver.takePersistableUriPermission(parentTree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }
    private fun treeUri(path: String) = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$path")
    private fun ownedDocument(child: String): Uri {
        check(child.startsWith(token)); return DocumentsContract.buildDocumentUriUsingTree(parentTree, "primary:$parentPath/$child")
    }
    private fun documentExists(uri: Uri) = runCatching { resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true }.getOrDefault(false)
    private val documentPackages = setOf("com.google.android.documentsui", "com.android.documentsui")
    private val confirmationPackages = setOf("android", "com.google.android.providers.media.module", "com.android.providers.media.module", "com.android.providers.media")
    private fun activePackage() = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
    private fun documentsUi() { await("actual DocumentsUI") { activePackage() in documentPackages }; recordControls("documents_ui") }
    private fun primaryRootVisible(): Boolean = systemNodes().filter {
        it.isVisibleToUser && it.viewIdResourceName?.endsWith("/breadcrumb_text") == true
    }.lastOrNull()?.text?.toString() in setOf(Build.MODEL, "Internal storage", "Internal shared storage")
    private fun navigatePrimaryRoot() {
        documentsUi()
        await("loaded observed primary-root controls") {
            (findObserved("Pictures") != null && findObserved("DCIM") != null) ||
                systemNodes().any { it.isVisibleToUser && (it.viewIdResourceName?.endsWith("/breadcrumb_text") == true || it.contentDescription?.toString()?.equals("Show roots", true) == true) }
        }
        if (primaryRootVisible() || (findObserved("Pictures") != null && findObserved("DCIM") != null)) return
        val drawer = systemNodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.equals("Show roots", true) == true }
        val breadcrumb = systemNodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName?.endsWith("/breadcrumb_text") == true }
        if (drawer == null && breadcrumb != null) {
            repeat(8) {
                if (primaryRootVisible() || (findObserved("Pictures") != null && findObserved("DCIM") != null)) return
                check(activePackage() in documentPackages) { "Picker closed before primary root was observed" }
                pressBack()
            }
            error("Primary root not observed after bounded parent navigation")
        }
        check(drawer != null) { "No observed primary-root breadcrumb or Show roots control after load" }; clickNode(drawer)
        await("observed internal-storage root") { systemNodes().any { it.isVisibleToUser && it.text?.toString()?.let { text -> text.equals("Internal storage", true) || text.equals("Internal shared storage", true) } == true } }
        val internal = systemNodes().first { it.isVisibleToUser && it.text?.toString()?.let { text -> text.equals("Internal storage", true) || text.equals("Internal shared storage", true) } == true }
        clickNode(internal); await("Pictures in primary root") { primaryRootVisible() || (findObserved("Pictures") != null && findObserved("DCIM") != null) }
    }
    private fun navigateOwnedParent() {
        documentsUi()
        if (currentDirectory(token)) return
        navigatePrimaryRoot(); findOwnedDirectoryByBoundedScroll("Documents"); clickObserved("Documents")
        await("Documents directory") { currentDirectory("Documents") }
        val mayCreate = phase == "seed" && !receipt.getBoolean("exportTreeCreated", false)
        if (findDirectoryByScroll("QA")) clickObserved("QA") else {
            check(mayCreate) { "Existing Documents/QA parent not observed; refusing a guessed destination" }
            newFolder(); setSystemEdit("QA"); acceptFolderName()
        }
        await("Documents/QA directory") { currentDirectory("QA") }
        if (findDirectoryByScroll(token)) clickObserved(token) else {
            check(mayCreate) { "Owned export folder not observed" }
            newFolder(); setSystemEdit(token); acceptFolderName()
        }
        await("exact owned Documents/QA export directory") { currentDirectory(token) }
        recordControls("owned_documents_qa_export_directory")
    }
    private fun ownedParentVisible(): Boolean {
        val nodes = systemNodes().filter { it.isVisibleToUser }
        val title = nodes.any { it.text?.toString() == token || it.contentDescription?.toString() == token }
        val ownedNames = images.keys.map { fixtureName(it) }.toSet()
        val child = nodes.any { !it.isEditable && it.text?.toString() in ownedNames }
        // Android's current-directory title has no resource ID on this API36 build.
        // A token title AND a known direct child proves location without trusting an absent ID.
        return title && child
    }

    private fun observedDirectoryRecycler(): AccessibilityNodeInfo? {
        val candidates = systemNodes().filter {
            it.isVisibleToUser && (it.className?.toString()?.endsWith("RecyclerView") == true || it.viewIdResourceName?.endsWith("/dir_list") == true)
        }
        // Breadcrumbs can also be a RecyclerView. The directory grid has its own ID
        // or the largest observed viewport; never scroll the horizontal breadcrumb list.
        return candidates.firstOrNull { it.viewIdResourceName?.endsWith("/dir_list") == true }
            ?: candidates.maxByOrNull { node -> Rect().also { node.getBoundsInScreen(it) }.height() }
    }
    private fun directoryViewportSignature(): List<String> = systemNodes().filter {
        it.isVisibleToUser && (it.viewIdResourceName?.endsWith("/title") == true || it.viewIdResourceName?.endsWith("/icon") == true)
    }.map { node ->
        // Transient hashes detect a changed page. Arbitrary filenames never enter retained evidence.
        val rect = Rect().also { node.getBoundsInScreen(it) }
        "${node.viewIdResourceName}:${node.text?.toString()?.hashCode()}:$rect"
    }
    private fun scrollObservedDirectory(action: Int): Boolean {
        check(activePackage() in documentPackages)
        val list = observedDirectoryRecycler() ?: error("No observed DocumentsUI RecyclerView for bounded scrolling")
        record("observed_directory_scroll_target", listOf("${list.viewIdResourceName}:${list.className}:${Rect().also { list.getBoundsInScreen(it) }}"))
        val before = directoryViewportSignature()
        if (!list.performAction(action)) return false
        val deadline = SystemClock.uptimeMillis() + 2000
        var last = before
        var stableSince = SystemClock.uptimeMillis()
        var changed = false
        while (SystemClock.uptimeMillis() < deadline) {
            val current = directoryViewportSignature()
            if (current != last) { changed = true; last = current; stableSince = SystemClock.uptimeMillis() }
            if (changed && SystemClock.uptimeMillis() - stableSince >= 200) return true
            SystemClock.sleep(100)
        }
        return changed
    }
    private fun findOwnedDirectoryByBoundedScroll(label: String = token) {
        if (findObserved(label) != null) return
        // Reset only the observed directory list to its top; never scroll another window.
        var steps = 0
        while (steps++ < 50 && findObserved(label) == null && scrollObservedDirectory(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) { }
        if (findObserved(label) != null) return
        steps = 0
        while (steps++ < 50) {
            if (!scrollObservedDirectory(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            if (findObserved(label) != null) return
        }
        recordControls("owned_directory_not_observed_after_bounded_scroll")
        check(findObserved(label) != null) { "Exact directory $label not observed after bounded RecyclerView traversal" }
    }
    private fun ownedJsonChildren(): Map<String, Uri> {
        val parentId = "primary:$parentPath"
        check(parentId == "primary:$parentPath")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parentTree, parentId)
        val result = linkedMapOf<String, Uri>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0); val name = cursor.getString(1)
                if (name.startsWith(token) && name.endsWith(".json")) {
                    check(id.startsWith("primary:$parentPath/")); result[name] = DocumentsContract.buildDocumentUriUsingTree(parentTree, id)
                }
            }
        } ?: error("Cannot enumerate token-owned export directory")
        return result
    }
    private fun readExport(uri: Uri): JSONObject {
        check(DocumentsContract.getDocumentId(uri).startsWith("primary:$parentPath/"))
        return resolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }
    private fun acceptFolderGrantIfShown() {
        await("folder grant dialog or QA return") { activePackage() == context.packageName || systemNodes().any { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" } }
        if (activePackage() != context.packageName) {
            recordControls("watch_folder_grant"); val positive = systemNodes().first { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
            check(positive.text?.toString()?.equals("ALLOW", true) == true) { "Unobserved folder grant label; refusing positive action" }; clickNode(positive)
        }
        await("watch picker returned") { activePackage() == context.packageName }
    }
    private fun exportPicker(filename: String) {
        closePreviewIfPresent(); compose.onNodeWithContentDescription("Save file details").performClick(); documentsUi()
        setSystemEdit(filename); recordControls("export_filename_set")
    }
    private fun saveDocument() { clickObserved("SAVE"); await("save returned to QA") { activePackage() == context.packageName } }
    private fun newFolder() {
        val node = systemNodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.equals("New folder", true) == true }
        if (node != null) clickNode(node) else {
            val overflow = systemNodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.equals("More options", true) == true }
            check(overflow != null) { "No observed New folder or More options control" }; clickNode(overflow); clickObserved("New folder")
        }
        await("new-folder name field") { systemNodes().any { it.isVisibleToUser && it.isEditable } }; recordControls("new_folder_dialog")
    }
    private fun acceptFolderName() {
        val positive = systemNodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
        check(positive != null && positive.isEnabled) { "No enabled observed folder creation button" }
        check(positive.text?.toString()?.let { it.equals("OK", true) || it.equals("CREATE", true) } == true) { "Unknown folder creation positive label" }
        clickNode(positive)
    }
    private fun setSystemEdit(value: String) {
        await("single visible system text field") { systemNodes().count { it.isVisibleToUser && it.isEditable } == 1 }
        val field = systemNodes().single { it.isVisibleToUser && it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
    }
    private fun storageDialog(kind: String, expectedCount: Int) {
        await("observed Android $kind confirmation") { activePackage() in confirmationPackages && systemNodes().any { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" } }
        recordControls("${kind}_confirmation_$expectedCount")
        val text = systemNodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.joinToString(" ")
        check(text.contains(kind, true) || (kind == "trash" && text.contains("move", true))) { "Observed dialog does not identify requested storage operation" }
        // Scope is established by the production action selecting an owned ID and, for Bin, a full owned-ID gate.
        check(images.isNotEmpty() && expectedCount <= images.size)
        val countPattern = Regex("(?i)\\b$expectedCount\\s+(?:photos?|images?|items?|files?)\\b")
        check(countPattern.containsMatchIn(text) || (expectedCount == 1 && text.contains("this photo", true))) {
            "System dialog does not expose the exact requested item count; do not accept this scenario without review"
        }
    }
    private fun approveSystemDialog(kind: String) {
        check(activePackage() in confirmationPackages)
        val positive = systemNodes().single { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
        val allowed = if (kind == "delete") setOf("delete", "allow") else setOf("move to trash", "trash", "allow", "move")
        check(positive.text?.toString()?.lowercase() in allowed) { "Unknown destructive confirmation label; refusing action" }
        recordControls("approve_$kind"); clickNode(positive); await("storage confirmation closed") { activePackage() == context.packageName }
    }
    private fun cancelSystemDialog() {
        check(activePackage() in confirmationPackages || activePackage() in documentPackages)
        val negative = systemNodes().firstOrNull { it.isVisibleToUser && it.isEnabled && (it.viewIdResourceName == "android:id/button2" || it.text?.toString()?.equals("Cancel", true) == true) }
        check(negative != null) { "No observed Cancel control; refusing inferred action" }; clickNode(negative)
    }
    private fun findObserved(label: String) = systemNodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()?.equals(label, true) == true || it.contentDescription?.toString()?.equals(label, true) == true) }
    private fun clickObserved(label: String) { await("observed $label") { findObserved(label) != null }; val node = requireNotNull(findObserved(label)); check(node.isEnabled); clickNode(node) }
    private fun systemNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        automation.rootInActiveWindow?.let(::visit); return result
    }
    private fun clickNode(node: AccessibilityNodeInfo) {
        check(activePackage() in documentPackages || activePackage() in confirmationPackages)
        var current: AccessibilityNodeInfo? = node
        while (current != null) { if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; current = current.parent }
        val rect = Rect().also { node.getBoundsInScreen(it) }; check(!rect.isEmpty)
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun cancelDocumentPicker() {
        repeat(8) {
            if (activePackage() == context.packageName) return
            check(activePackage() in documentPackages) { "Refusing Back outside the observed document picker" }
            pressBack()
        }
        await("document picker canceled after parent navigation") { activePackage() == context.packageName }
    }

    private fun injectKey(code: Int) {
        check(code in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent $code")).use { it.readBytes() }
        SystemClock.sleep(250)
    }
    private fun pressBack() { automation.executeShellCommand("input keyevent KEYCODE_BACK").close(); SystemClock.sleep(250) }
    private fun recordControls(event: String) {
        val allowed = setOf("save", "cancel", "delete", "allow", "move", "move to trash", "trash", "ok", "create", "use this folder", "new folder", "name", "sort by", "pictures", "internal storage", "more options", "show roots", "navigate up")
        val labels = systemNodes().filter { it.isVisibleToUser }.mapNotNull { node ->
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: return@mapNotNull null
            if (text.lowercase() in allowed || text.startsWith(token) || text.contains("sort", true) || text.contains("view", true)) "${node.viewIdResourceName.orEmpty()}:$text:${node.isEnabled}" else if (node.isEditable) "${node.viewIdResourceName.orEmpty()}:<editable>:${node.isEnabled}" else null
        }.distinct().take(40)
        record(event, labels)
    }
    private fun record(event: String, labels: List<String>) { evidence.put(JSONObject().put("event", event).put("package", activePackage()).put("labels", JSONArray(labels))) }
    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; Thread.sleep(100) }
        recordControls("timeout_$description"); error("Timed out: $description; sanitized evidence storage-$token.json")
    }
    private fun <T> query(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @After fun retainOwnedPhaseAndRecordAttempt() {
        if (permitted && receipt.contains("token")) {
            persistFixture()
            writeStage("attempt_" + phase)
        }
    }

    @Test fun phasedReviewUnicodeAndNativeExportAcrossHostRestart() {
        println("QA71_EDITING_PHASE token=$token phase=$phase")
        when (phase) {
            "seed" -> seedJourney()
            "verify_long_seed_short" -> verifyLongAndSeedShort()
            "verify_short" -> verifyShortJourney()
            "cleanup" -> cleanupJourney()
        }
    }

    private fun values(long: Boolean) = listOf(
        if (long) "مرحبا 😀\n".repeat(1500).take(9998) + "終\n" else "QA saved",
        "QA", "Testing", "Review", "QA title", if (long) "QA latest detail" else "QA detail",
        if (long) "QA step\nخطوة ثانية 😀\n最後" else "QA step"
    )
    private fun roleId(role: String) = receipt.getLong("id_$role", -1).also { check(it in mediaIds) }
    private fun row(role: String) = query { requireNotNull(db.videoItemDao().getVideoByIdDirect(roleId(role))) }
    private fun seedImage(role: String, processed: Boolean): Long {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
        val name = "$token-$role.png"
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, physicalPath); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        images[uri] = bytes; resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val id = query { db.videoItemDao().insertVideo(VideoItem(mediaStoreId = android.content.ContentUris.parseId(uri), uriString = uri.toString(), displayName = name,
            relativePath = physicalPath, mimeType = "image/png", sizeBytes = bytes.size.toLong(), durationMs = null, dateAdded = null, dateModified = null,
            status = if (processed) "processed" else "new", lastCompletedStep = if (processed) "result_saved" else "discovered",
            reviewDecision = "unreviewed", sourceCategory = "Camera", mediaCollection = "image", volumeName = "external_primary")) }
        mediaIds += id; receipt.edit().putLong("id_$role", id).commit(); persistFixture()
        if (processed) query { db.analysisResultDao().insertAnalysis(AnalysisResult(videoId = id, descriptionLine1 = "QA title", descriptionLine2 = "Initial detail", categoryLevel1 = "QA", categoryLevel2 = "Testing", categoryLevel3 = "Review", formattedActionPlan = "QA step", urls = emptyList(), codeOrCommands = emptyList(), evidenceJson = "[]", source = "edited")) }
        return id
    }
    private fun seedJourney() {
        listOf("keep", "later", "remove", "new").forEach { seedImage(it, it == "keep") }
        sourceCategory()
        decide("keep", "Keep", "keep"); decide("later", "Later", "review_later"); decide("remove", "Remove", "ready_to_delete")
        decide("new", "Later", "review_later"); decide("new", "New", "unreviewed")
        assertReviewAcrossScreens()
        openKeepPreview(); editAndSave(values(true).toMutableList().apply { this[5] = "QA previous detail" }); closePreviewIfPresent()
        chooseParentAsWatchFolder(); receipt.edit().putBoolean("exportTreeCreated", true).commit()
        val oldValues = values(true).toMutableList().apply { this[5] = "QA previous detail" }
        val before = nativeExport("before-rename", oldValues, "keep", "$token-keep.png")
        receipt.edit().putString("oldExport", before.first.toString()).putString("oldExportSha", digest(resolver.openInputStream(before.first)!!.use { it.readBytes() })).commit()
        navigate("queue"); action(row("keep"), "Rename").performClick()
        await("actual app-owned rename") { row("keep").displayName == renamedName }
        resolver.query(Uri.parse(row("keep").uriString), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { c -> check(c.moveToFirst()); assertEquals(renamedName, c.getString(0)) }
        openKeepPreview(); editAndSave(values(true)); closePreviewIfPresent()
        sourceCategory(); decide("keep", "Later", "review_later")
        nativeExport("latest-after-rename-and-review", values(true), "review_later", renamedName)
        sourceCategory(); decide("keep", "Keep", "keep")
        val tag = query { db.customTagDao().insert(CustomTag(name = token)) }
        query { db.customTagDao().assign(MediaTagCrossRef(roleId("keep"), tag)) }; receipt.edit().putLong("tagId", tag).commit()
        assertNameInTags()
        sourceOverview(); compose.onNodeWithTag("inventory_scan_button").performClick()
        await("real scoped refresh retains one identity per fixture") { query { db.videoItemDao().getAllVideosDirect().size == 4 && db.videoItemDao().getAllVideosDirect().all { it.lastSeenScanToken != null } } }
        assertReviewAcrossScreens(); assertSaved(values(true)); assertAllBytes()
        nativeExport("seed-long", values(true), "keep", renamedName)
        assertOldExportUnchanged()
        receipt.edit().putInt("seedPid", Process.myPid()).putLong("seedStart", Process.getStartElapsedRealtime()).putBoolean("seedComplete", true).commit()
        writeStage("seed_complete_waiting_for_external_force_stop")
    }
    private fun verifyLongAndSeedShort() {
        requireFreshHostStoppedProcess("seedPid", "seedStart"); check(receipt.getBoolean("seedComplete", false))
        assertReviewAcrossScreens(); assertSaved(values(true)); assertNameInTags(); assertAllBytes(); assertOldExportUnchanged()
        nativeExport("after-long-restart", values(true), "keep", renamedName)
        openKeepPreview(); editAndSave(values(false)); closePreviewIfPresent()
        receipt.edit().putInt("shortSeedPid", Process.myPid()).putLong("shortSeedStart", Process.getStartElapsedRealtime()).putBoolean("shortSeedComplete", true).commit()
        writeStage("long_verified_short_seeded_waiting_for_external_force_stop")
    }
    private fun verifyShortJourney() {
        requireFreshHostStoppedProcess("shortSeedPid", "shortSeedStart"); check(receipt.getBoolean("shortSeedComplete", false))
        assertReviewAcrossScreens(); assertSaved(values(false)); assertNameInTags(); assertAllBytes(); assertOldExportUnchanged()
        nativeExport("QA-restart", values(false), "keep", renamedName)
        val homeResolution = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "cmd package resolve-activity --user current --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        )).bufferedReader().use { it.readText() }
        val home = requireNotNull(homeResolution.lineSequence().map { it.trim() }
            .firstOrNull { it.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) }
            ?.let(android.content.ComponentName::unflattenFromString)).packageName
        record("resolved_actual_launcher", listOf(home))
        val launcherIntent = Intent.makeMainActivity(android.content.ComponentName(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun observedPackage(): String {
            check(automation.clearCache())
            return automation.rootInActiveWindow?.packageName?.toString().orEmpty()
        }
        injectKey(KeyEvent.KEYCODE_HOME)
        await("observed launcher before normal app launch") { observedPackage() == home }
        context.startActivity(launcherIntent)
        await("QA launched from actual Home") { observedPackage() == context.packageName }
        for (destination in listOf("sources", "queue", "folders")) {
            navigate(destination); dismissOpenMenu()
            injectKey(KeyEvent.KEYCODE_BACK)
            await("Android Back reaches actual launcher") { observedPackage() == home }
            context.startActivity(launcherIntent)
            await("QA Activity relaunched") { observedPackage() == context.packageName }
            assertSavedRowsOnly(values(false)); assertAllBytes()
        }
        assertReviewAcrossScreens(); assertSaved(values(false))
        receipt.edit().putBoolean("verified", true).commit(); writeStage("short_restart_and_three_android_back_cycles_verified")
    }
    private fun requireFreshHostStoppedProcess(pid: String, started: String) {
        check(InstrumentationRegistry.getArguments().getString("qa_host_force_stop_confirmed") == "true")
        check(Process.myPid() != receipt.getInt(pid, -1) || Process.getStartElapsedRealtime() != receipt.getLong(started, -1))
    }
    private fun currentDirectory(label: String): Boolean {
        val nodes = systemNodes().filter { it.isVisibleToUser && !it.isEditable }
        val breadcrumbs = nodes.filter { it.viewIdResourceName?.endsWith("/breadcrumb_text") == true }
        return breadcrumbs.lastOrNull()?.text?.toString() == label || nodes.any { it.viewIdResourceName.isNullOrEmpty() && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
    }
    private fun findDirectoryByScroll(label: String): Boolean {
        if (findObserved(label) != null) return true
        var n = 0
        while (n++ < 50 && findObserved(label) == null && scrollObservedDirectory(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) { }
        if (findObserved(label) != null) return true
        n = 0
        while (n++ < 50 && scrollObservedDirectory(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) if (findObserved(label) != null) return true
        return findObserved(label) != null
    }
    private fun sourceOverview() {
        navigate("sources")
        repeat(3) {
            if (compose.onAllNodesWithTag("open_tags").fetchSemanticsNodes().isNotEmpty()) return
            if (compose.onAllNodesWithContentDescription("Show all sources").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Show all sources").performClick()
            else if (compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Back").performClick()
        }
        compose.onNodeWithTag("open_tags").assertExists()
    }
    private fun sourceCategory() {
        sourceOverview()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("source_category_Camera"))
        compose.onNodeWithTag("source_category_Camera").performClick()
        await("four actual Camera fixture rows") { compose.onAllNodesWithTag("source_media_${roleId("keep")}").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun decide(role: String, label: String, code: String) {
        val id = roleId(role)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("source_media_$id"))
        compose.onNodeWithTag("review_decision_$id").onChildren().filter(hasClickAction()).onFirst().performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
        await("actual Sources $label decision") { row(role).reviewDecision == code }
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("review_decision_$id"))).assertIsDisplayed()
    }
    private fun assertReviewAcrossScreens() {
        sourceCategory()
        for ((role, label) in mapOf("keep" to "Keep", "later" to "Later", "remove" to "Remove", "new" to "New")) {
            val id = roleId(role)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("source_media_$id"))
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("review_decision_$id"))).assertIsDisplayed()
        }
        compose.onNodeWithText("1 unreviewed   •   1 kept   •   1 ready to delete", substring = true).assertExists()
        navigate("home"); compose.onNodeWithText("Overview").performClick()
        compose.onNode(hasText("03") and hasAnySibling(hasText("New"))).assertIsDisplayed()
        compose.onNode(hasText("00") and hasAnySibling(hasText("Queued"))).assertIsDisplayed()
        compose.onNode(hasText("01") and hasAnySibling(hasText("Proc."))).assertIsDisplayed()
        compose.onNode(hasText("00") and hasAnySibling(hasText("Review"))).assertIsDisplayed()
        navigate("queue")
        for (role in listOf("keep", "later", "remove", "new")) compose.onNodeWithText(row(role).displayName).performScrollTo().assertExists()
        sourceOverview(); compose.onNodeWithTag("open_bin").performClick(); compose.onNodeWithText("Bin empty").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertSavedRowsReviewOnly()
    }
    private fun assertSavedRowsReviewOnly() {
        val expected = mapOf("keep" to "keep", "later" to "review_later", "remove" to "ready_to_delete", "new" to "unreviewed")
        expected.forEach { (role, value) -> assertEquals(value, row(role).reviewDecision) }
        listOf("keep", "later", "remove", "new").forEach { role ->
            assertEquals("Review choices must preserve independent processing status", if (role == "keep") "processed" else "new", row(role).status)
        }
        assertEquals(4, query { db.videoItemDao().getAllVideosDirect().size })
    }
    private fun openKeepPreview() { navigate("queue"); action(row("keep"), "Preview").performClick() }
    private fun action(row: VideoItem, description: String): SemanticsNodeInteraction {
        val queueTag = "queue_${row.id}_${description.lowercase()}"
        if (compose.onAllNodesWithTag(queueTag).fetchSemanticsNodes().size == 1) {
            return compose.onNodeWithTag(queueTag).performQaQueueScrollTo(compose).assertIsDisplayed()
        }
        val y = compose.onNodeWithText(row.displayName).performScrollTo().fetchSemanticsNode().boundsInRoot.center.y
        val nodes = compose.onAllNodes(hasContentDescription(description) and hasClickAction()).fetchSemanticsNodes()
        check(nodes.isNotEmpty()); val index = nodes.indices.minBy { kotlin.math.abs(nodes[it].boundsInRoot.center.y - y) }
        check(kotlin.math.abs(nodes[index].boundsInRoot.center.y - y) < 70f)
        return compose.onAllNodes(hasContentDescription(description) and hasClickAction())[index]
    }
    private fun editAndSave(v: List<String>) {
        val fields = compose.onAllNodes(hasSetTextAction()); fields.assertCountEquals(7)
        v.forEachIndexed { index, value -> fields[index].performScrollTo().performTextReplacement(value) }
        compose.onNodeWithContentDescription("Save edits").performScrollTo().performClick()
        await("all seven saved editor fields") { stored() == v }
        v.forEachIndexed { index, value -> fields[index].performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value))) }
    }
    private fun stored(): List<String?> = query {
        val item = db.videoItemDao().getVideoByIdDirect(roleId("keep")); val a = db.analysisResultDao().getAnalysisForVideoDirect(roleId("keep"))
        listOf(item?.manualTranscript, a?.categoryLevel1, a?.categoryLevel2, a?.categoryLevel3, a?.descriptionLine1, a?.descriptionLine2, a?.formattedActionPlan)
    }
    private fun assertSavedRowsOnly(v: List<String>) {
        assertEquals(v, stored()); assertSavedRowsReviewOnly(); assertEquals(renamedName, row("keep").displayName)
    }
    private fun assertSaved(v: List<String>) {
        assertSavedRowsOnly(v); openKeepPreview()
        val fields = compose.onAllNodes(hasSetTextAction()); fields.assertCountEquals(7)
        v.forEachIndexed { index, value -> fields[index].performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value))) }
        compose.onNodeWithContentDescription("Close").performClick()
        navigate("queue"); action(row("keep"), "Output").performClick()
        listOf("QA > Testing > Review", v[4], v[5], v[6]).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText(v[0], substring = false).assertDoesNotExist(); compose.onNodeWithContentDescription("Close").performClick()
    }
    private fun assertNameInTags() {
        sourceOverview(); compose.onNodeWithTag("open_tags").performClick()
        compose.onNodeWithText(token, substring = false).performClick()
        compose.onNodeWithText(renamedName, substring = false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("media_sources_screen").assertIsDisplayed()
        compose.onNodeWithTag("open_tags").assertIsDisplayed()
    }
    private fun nativeExport(label: String, v: List<String>, review: String, name: String): Pair<Uri, JSONObject> {
        check(query { db.videoItemDao().getAllVideosDirect().all { it.id in mediaIds } })
        val before = ownedJsonChildren()
        val filename = "$token-$label-${System.nanoTime()}.json"
        exportPicker(filename); navigateOwnedParent(); saveDocument()
        await("actual Documents/QA file created") { (ownedJsonChildren().keys - before.keys).size == 1 }
        val added = ownedJsonChildren().filterKeys { it !in before }; assertEquals(setOf(filename), added.keys)
        val uri = added.values.single(); createdDocuments += uri; persistFixture()
        val raw = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val json = JSONObject(raw.toString(Charsets.UTF_8)); assertEquals(1, json.getInt("schema"))
        val records = json.getJSONArray("media"); assertEquals(4, records.length())
        val expectedUris = images.keys.map { it.toString() }.toSet()
        assertEquals(expectedUris, (0 until records.length()).map { records.getJSONObject(it).getString("uri") }.toSet())
        val saved = (0 until records.length()).map { records.getJSONObject(it) }.single { it.getString("uri") == row("keep").uriString }
        assertEquals(name, saved.getString("name")); assertEquals("processed", saved.getString("status")); assertEquals(review, saved.getString("reviewDecision"))
        assertEquals(v[0], saved.getString("notes")); assertEquals(v[4], saved.getString("summary")); assertEquals(v[5], saved.getString("details")); assertEquals(v[6], saved.getString("roadmap")); assertEquals(v.subList(1, 4).joinToString(" / "), saved.getString("category"))
        assertAllBytes(); record("native_export_read_back", listOf(filename, "records=4 exact_unicode_fields=true"))
        return uri to saved
    }
    private fun assertOldExportUnchanged() {
        val uri = Uri.parse(requireNotNull(receipt.getString("oldExport", null)))
        check(DocumentsContract.getDocumentId(uri).startsWith("primary:$parentPath/"))
        val raw = resolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(receipt.getString("oldExportSha", null), digest(raw))
        val records = JSONObject(raw.toString(Charsets.UTF_8)).getJSONArray("media")
        val old = (0 until records.length()).map { records.getJSONObject(it) }.single { it.getString("uri") == row("keep").uriString }
        assertEquals("$token-keep.png", old.getString("name")); assertEquals("QA previous detail", old.getString("details")); assertEquals("keep", old.getString("reviewDecision"))
    }
    private fun assertAllBytes() = images.keys.forEach(::assertBytes)
    private fun persistFixture() {
        val records = JSONArray()
        images.forEach { (uri, bytes) ->
            val id = query { db.videoItemDao().getAllVideosDirect().singleOrNull { it.uriString == uri.toString() }?.id }
            if (id != null) records.put(JSONObject().put("id", id).put("uri", uri.toString()).put("bytes", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
        }
        receipt.edit().putString("records", records.toString()).putString("documents", JSONArray(createdDocuments.map { it.toString() }).toString()).commit()
    }
    private fun dismissOpenMenu() { if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick() }
    private fun cleanupJourney() {
        val tag = receipt.getLong("tagId", -1)
        if (tag >= 0) query {
            val own = db.customTagDao().observeTags().first().singleOrNull { it.id == tag }
            if (own != null) { check(own.name == token); db.openHelper.writableDatabase.execSQL("DELETE FROM media_tag_cross_ref WHERE mediaId = ? AND tagId = ?", arrayOf(roleId("keep"), tag)); db.openHelper.writableDatabase.execSQL("DELETE FROM custom_tags WHERE id = ? AND name = ?", arrayOf(tag, token)) }
        }
        cleanupNativeOwned()
        prefs.edit().apply {
            if (!receipt.getBoolean("hadFolders", false)) remove("target_folders")
            if (receipt.getBoolean("hadProvider", false)) putString("llm_provider", receipt.getString("oldProvider", "")) else remove("llm_provider")
        }.commit()
        writeStage("cleanup_complete"); receipt.edit().clear().commit(); permitted = false
    }
    private fun writeStage(stage: String) {
        File(context.getExternalFilesDir(null), "$token-$stage.json").writeText(JSONObject().put("stage", stage).put("token", token).put("package", context.packageName).put("api", Build.VERSION.SDK_INT)
            .put("seedPid", receipt.getInt("seedPid", -1)).put("currentPid", Process.myPid()).put("hostForceStopRequired", true).put("verified", receipt.getBoolean("verified", false)).put("events", evidence).toString(2))
    }

}
