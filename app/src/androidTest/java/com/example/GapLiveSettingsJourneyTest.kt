package com.example

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Opt-in REAL DeepSeek via local Ollama, actual MainActivity and actual Settings actions.
 * seed: two generated images, Sources Keep, Queue Process / Preview close+Process, Output.
 * HOST must force-stop only QA after a successful seed, then verify in a new process.
 * verify: unchanged result snapshots and original bytes, Preview/Output and Source Keep.
 * cleanup is explicit after verify; failed phases retain owned receipts for diagnosis.
 * No credential store is opened by this test. The provisioner must confirm an empty
 * dedicated QA credential store; PC mode never needs a direct Cloud account key.
 */
class GapLiveSettingsJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val resolver get() = context.contentResolver
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val receiptFile get() = File(context.filesDir, "qa71-live-settings-receipt.json")
    private var scenario: ActivityScenario<MainActivity>? = null
    private var activity: MainActivity? = null
    private lateinit var receipt: JSONObject
    private lateinit var endpoint: String
    private lateinit var model: String

    @Test fun t099_t180_t181_realSettingsKeepBothProcessingEntrypointsAndHostRestart() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(args.getString("rea_runtime") == "emulator")
        check(args.getString("qa_empty_credentials_confirmed") == "true")
        check(args.getString("rea_live_ollama") == "true" && args.getString("rea_allow_cloud_alias") == "true")
        endpoint = args.getString("rea_ollama_endpoint") ?: "http://10.0.2.2:11434/api/generate"
        val url = Uri.parse(endpoint)
        check(url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "10.0.2.2") && url.userInfo == null)
        model = requireNotNull(args.getString("rea_ollama_model"))
        check(model == "deepseek-v4.1-flash:cloud")
        when (args.getString("qa71_live_phase")) {
            "seed" -> seed()
            "verify" -> verify()
            "cleanup" -> cleanup()
            else -> error("Explicit qa71_live_phase=seed|verify|cleanup required")
        }
    }
    @After fun closeOnlyActivity() { scenario?.close() }

    private fun seed() {
        check(!receiptFile.exists()) { "Outstanding live receipt must be verified/cleaned first" }
        check(io { dao.getAllVideosDirect().isEmpty() }) { "Empty isolated QA index required" }
        val token = "QA71Live${System.nanoTime()}"
        receipt = JSONObject().put("token", token).put("package", context.packageName).put("phase", "creating")
            .put("pid", Process.myPid()).put("processStart", Process.getStartElapsedRealtime()).put("endpoint", endpoint).put("model", model)
            .put("oldPrefs", ordinaryPrefs()).put("items", JSONArray())
        io { db.appSettingsDao().getSettingsDirect() }?.let { receipt.put("oldSettings", settingsJson(it)) }
        persist()
        prefs.edit().putString("llm_provider", "ollama_local").putString("llm_system_prompt", "").putFloat("llm_temperature", 0f)
            .putBoolean("auto_ingest_enabled", false).putStringSet("target_folders", setOf("content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:Pictures/$token"))).commit()
        io { db.appSettingsDao().insertSettings(AppSettings(ollamaEnabled = false, ollamaEndpointUrl = endpoint, ollamaModelName = "", requestTimeoutSeconds = 180)) }
        val queue = createPhoto("queue"); val preview = createPhoto("preview")
        launch(); configureRealPcThroughSettings()
        chooseKeep(queue); navigate("Queue"); action(queue, "Process").assertIsEnabled().performClick()
        completed(queue); checkLiveOutput(queue); checkPreview(queue)
        chooseKeep(preview); navigate("Queue"); action(preview, "Preview").performClick()
        val before = io { requireNotNull(dao.getVideoByIdDirect(preview)) }
        click("Close"); assertEquals(before, io { dao.getVideoByIdDirect(preview) })
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(preview) })
        assertTrue(io { db.extractedFrameDao().getFramesForVideoDirect(preview).isEmpty() })
        action(preview, "Preview").performClick(); click("Process file", scroll = true); click("Close")
        completed(preview); checkLiveOutput(preview); checkPreview(preview)
        for (entry in items()) {
            val id = entry.getLong("id"); val result = io { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(id)) }
            entry.put("analysis", resultJson(result)); entry.put("notes", io { dao.getVideoByIdDirect(id)?.manualTranscript ?: "" })
            assertEquals(1, io { db.analysisResultDao().getAllAnalysis().first().count { it.videoId == id } })
            assertOwnedBytes(entry)
        }
        receipt.put("phase", "seed_complete_host_stop_required"); persist()
    }
    private fun verify() {
        load(); check(receipt.getString("phase") == "seed_complete_host_stop_required")
        check(args.getString("qa_host_force_stop_confirmed") == "true")
        check(Process.myPid() != receipt.getInt("pid") || Process.getStartElapsedRealtime() != receipt.getLong("processStart"))
        val ownedIds = items().map { it.getLong("id") }.toSet()
        check(io { dao.getAllVideosDirect().map { it.id }.toSet() } == ownedIds)
        val saved = io { requireNotNull(db.appSettingsDao().getSettingsDirect()) }
        assertTrue(saved.ollamaEnabled); assertEquals(endpoint, saved.ollamaEndpointUrl); assertEquals(model, saved.ollamaModelName); assertEquals(180, saved.requestTimeoutSeconds)
        assertEquals("ollama_local", prefs.getString("llm_provider", null))
        launch()
        for (entry in items()) {
            val id = entry.getLong("id"); assertOwnedBytes(entry)
            val current = io { requireNotNull(dao.getVideoByIdDirect(id)) }
            assertEquals("keep", current.reviewDecision); assertEquals("processed", current.status)
            assertEquals(entry.getString("notes"), current.manualTranscript ?: "")
            assertEquals(entry.getJSONObject("analysis").toString(), resultJson(io { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(id)) }).toString())
            assertEquals(1, io { db.analysisResultDao().getAllAnalysis().first().count { it.videoId == id } })
            navigate("Sources"); showCamera()
            compose.onNodeWithTag("review_decision_$id").onChildren().filter(hasClickAction()).onFirst().assert(hasText("Keep", substring = false))
            checkLiveOutput(id); checkPreview(id)
        }
        receipt.put("phase", "verified"); persist()
    }
    private fun cleanup() {
        load(); scenario?.close(); scenario = null
        for (entry in items()) {
            val id = entry.getLong("id"); val uri = Uri.parse(entry.getString("uri")); validateEntry(entry)
            io {
                dao.getVideoByIdDirect(id)?.let { check(it.uriString == uri.toString() && it.displayName == entry.getString("name")) }
                db.analysisResultDao().deleteAnalysisForVideo(id); db.extractedFrameDao().deleteFramesForVideo(id)
                dao.getVideoByIdDirect(id)?.let { dao.deleteVideo(it) }
            }
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { check(c.getString(0) == entry.getString("name")); resolver.delete(uri, null, null) }
            }
        }
        if (receipt.has("oldSettings")) {
            val s = receipt.getJSONObject("oldSettings")
            io { db.appSettingsDao().insertSettings(AppSettings(id=s.getInt("id"),ollamaEnabled=s.getBoolean("enabled"),ollamaEndpointUrl=s.getString("url"),ollamaModelName=s.getString("model"),requestTimeoutSeconds=s.getInt("timeout"),confirmedNonLocalEndpointWarning=s.getBoolean("consent"))) }
        } else io { db.openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id=1") }
        val old = receipt.getJSONObject("oldPrefs")
        prefs.edit().apply {
            for (key in listOf("llm_provider", "llm_system_prompt")) { if (old.isNull(key)) remove(key) else putString(key, old.getString(key)) }
            if (old.isNull("llm_temperature")) remove("llm_temperature") else putFloat("llm_temperature", old.getDouble("llm_temperature").toFloat())
            if (old.isNull("auto_ingest_enabled")) remove("auto_ingest_enabled") else putBoolean("auto_ingest_enabled", old.getBoolean("auto_ingest_enabled"))
            for (key in listOf("target_folders", "llm_available_models")) { if (old.isNull(key)) remove(key) else putStringSet(key, old.getJSONArray(key).let { a -> (0 until a.length()).map(a::getString).toSet() }) }
        }.commit()
        check(receiptFile.delete())
    }

    private fun configureRealPcThroughSettings() {
        val existingSelection = io { db.appSettingsDao().getSettingsDirect()?.ollamaModelName } ?: AppSettings().ollamaModelName
        click("Settings"); compose.onNodeWithText("PC", substring=false).assertExists()
        field("AI address").performScrollTo().performTextReplacement(endpoint); dismissQaKeyboard(requireNotNull(activity))
        click("Test connection", scroll=true)
        await { compose.onAllNodesWithText("Connected", substring=false).fetchSemanticsNodes().isNotEmpty() }
        click("Sync models tag list", scroll=true)
        val modelSelector = hasClickAction() and (hasText(model,substring=false) or hasText(existingSelection.ifBlank { "Tap sync to fetch models" },substring=false) or hasText("Select model",substring=false) or hasText("Tap sync to fetch models",substring=false))
        await { compose.onAllNodes(modelSelector).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(modelSelector).performScrollTo().performClick()
        await { compose.onAllNodesWithText(model,substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(model,substring=false).onLast().performClick()
        field("Wait limit").performScrollTo().performTextReplacement("180"); dismissQaKeyboard(requireNotNull(activity)); click("Save settings",scroll=true)
        await { io { db.appSettingsDao().getSettingsDirect()?.let { it.ollamaEnabled && it.ollamaEndpointUrl==endpoint && it.ollamaModelName==model && it.requestTimeoutSeconds==180 } == true } }
        click("Settings"); field("AI address").assertTextContains(endpoint); field("Wait limit").performScrollTo().assertTextContains("180")
        compose.onNodeWithText(model,substring=false).assertExists(); click("Close")
    }
    private fun createPhoto(label:String):Long {
        val token=receipt.getString("token"); val name="${token}_$label.png"; val path="Pictures/$token/Camera/"
        val bitmap=Bitmap.createBitmap(1200,600,Bitmap.Config.ARGB_8888)
        val bytes=try {
            bitmap.eraseColor(Color.WHITE); val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=54f }; val c=Canvas(bitmap)
            c.drawText("PYTHON VERSION CHECK",30f,110f,p);c.drawText("Run python --version",30f,250f,p);c.drawText("Print installed Python version",30f,390f,p)
            ByteArrayOutputStream().use { o -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,o));o.toByteArray() }
        } finally { bitmap.recycle() }
        val uri=requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"image/png");put(MediaStore.MediaColumns.RELATIVE_PATH,path);put(MediaStore.MediaColumns.IS_PENDING,1)}))
        val entry=JSONObject().put("name",name).put("path",path).put("uri",uri.toString()).put("sha256",digest(bytes));receipt.getJSONArray("items").put(entry);persist()
        resolver.openOutputStream(uri)!!.use{it.write(bytes)};resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
        val id=io {dao.insertVideo(VideoItem(mediaStoreId=ContentUris.parseId(uri),uriString=uri.toString(),displayName=name,relativePath=path,mimeType="image/png",sizeBytes=bytes.size.toLong(),durationMs=null,dateAdded=null,dateModified=null,status="new",reviewDecision="unreviewed",sourceCategory="Camera",mediaCollection="image"))}
        entry.put("id",id);persist();return id
    }
    private fun chooseKeep(id:Long) {
        navigate("Sources");showCamera()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("source_media_$id"))
        compose.onNodeWithTag("review_decision_$id").onChildren().filter(hasClickAction()).onFirst().performClick()
        compose.onAllNodesWithText("Keep",substring=false).onLast().performClick();await {io {dao.getVideoByIdDirect(id)?.reviewDecision=="keep"}}
    }
    private fun showCamera() {
        if(compose.onAllNodesWithContentDescription("Show all sources").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Show all sources").performClick()
        compose.onNodeWithTag("source_category_Camera").performClick()
    }
    private fun completed(id:Long) {
        await(240000){io{dao.getVideoByIdDirect(id)?.status in setOf("processed","error")}}
        assertEquals("processed",io{dao.getVideoByIdDirect(id)?.status})
        await{compose.onAllNodesWithText("Batch: FINISHED",substring=false).fetchSemanticsNodes().isNotEmpty()}
        val a=io{requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(id))};assertEquals("ollama",a.source)
        assertTrue(listOf(a.descriptionLine1,a.descriptionLine2,a.formattedActionPlan,a.evidenceJson,a.codeOrCommands.joinToString(" ")).joinToString(" ").contains("PYTHON",true))
        assertTrue(io{db.extractedFrameDao().getFramesForVideoDirect(id).any{it.ocrText.contains("PYTHON",true)}})
    }
    private fun checkLiveOutput(id:Long) {
        val a=io{requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(id))};assertEquals("ollama",a.source)
        navigate("Queue");action(id,"Output").assertIsEnabled().performClick()
        listOf(a.descriptionLine1,a.descriptionLine2,a.formattedActionPlan).forEach{compose.onNodeWithText(it,substring=false).assertExists()};click("Close")
    }
    private fun checkPreview(id:Long) {
        navigate("Queue");action(id,"Preview").performClick()
        assertTrue(compose.onAllNodesWithText("PYTHON",substring=true,ignoreCase=true).fetchSemanticsNodes().isNotEmpty());click("Close")
    }
    private fun action(id:Long,label:String):SemanticsNodeInteraction {
        val item=io{requireNotNull(dao.getVideoByIdDirect(id))};val rows=io{dao.getAllVideosDirect()}
        check(rows.all{r->items().any{it.getLong("id")==r.id}})
        val queueTag = "queue_${id}_${label.lowercase()}"
        if (compose.onAllNodesWithTag(queueTag).fetchSemanticsNodes().size == 1) {
            return compose.onNodeWithTag(queueTag).performQaQueueScrollTo(compose).assertIsDisplayed()
        }
        val name=compose.onNodeWithText(item.displayName,substring=false).assertIsDisplayed().fetchSemanticsNode()
        val matcher=hasContentDescription(label) and hasClickAction();val nodes=compose.onAllNodes(matcher).fetchSemanticsNodes()
        val nextTop=rows.filter{it.id!=id}.mapNotNull{other->compose.onAllNodesWithText(other.displayName,substring=false).fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.top}.filter{it>name.boundsInRoot.top}.minOrNull()?:Float.POSITIVE_INFINITY
        val indexes=nodes.indices.filter{nodes[it].boundsInRoot.center.y>=name.boundsInRoot.top&&nodes[it].boundsInRoot.center.y<nextTop};check(indexes.size==1)
        return compose.onAllNodes(matcher)[indexes.single()].assertIsDisplayed()
    }
    private fun launch(){scenario=ActivityScenario.launch(MainActivity::class.java);scenario!!.onActivity{activity=it};compose.waitForIdle()}
    private fun navigate(name:String){if(compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Open menu").performClick();compose.onNodeWithTag("nav_${name.lowercase()}").performClick();if(compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Close menu").performClick();compose.waitForIdle()}
    private fun click(name:String,scroll:Boolean=false){val n=compose.onNode(hasContentDescription(name) and hasClickAction());if(scroll)n.performScrollTo();n.assertIsDisplayed().performClick()}
    private fun field(label:String)=compose.onNode(hasText(label) and hasSetTextAction())
    private fun items()=(0 until receipt.getJSONArray("items").length()).map{receipt.getJSONArray("items").getJSONObject(it)}
    private fun validateEntry(e:JSONObject){val t=receipt.getString("token");check(t.matches(Regex("QA71Live[0-9]+")));check(e.getString("name").startsWith(t+"_")&&e.getString("path")=="Pictures/$t/Camera/");check(e.getString("uri").matches(Regex("content://media/external/images/media/[0-9]+")))}
    private fun assertOwnedBytes(e:JSONObject){validateEntry(e);assertEquals(e.getString("sha256"),resolver.openInputStream(Uri.parse(e.getString("uri")))!!.use{digest(it.readBytes())})}
    private fun ordinaryPrefs()=JSONObject().apply{for(k in listOf("llm_provider","llm_system_prompt"))put(k,if(prefs.contains(k))prefs.getString(k,null)else JSONObject.NULL);put("llm_temperature",if(prefs.contains("llm_temperature"))prefs.getFloat("llm_temperature",0f)else JSONObject.NULL);put("auto_ingest_enabled",if(prefs.contains("auto_ingest_enabled"))prefs.getBoolean("auto_ingest_enabled",false)else JSONObject.NULL);for(k in listOf("target_folders","llm_available_models"))put(k,prefs.getStringSet(k,null)?.let{JSONArray(it.toList())}?:JSONObject.NULL)}
    private fun settingsJson(s:AppSettings)=JSONObject().put("id",s.id).put("enabled",s.ollamaEnabled).put("url",s.ollamaEndpointUrl).put("model",s.ollamaModelName).put("timeout",s.requestTimeoutSeconds).put("consent",s.confirmedNonLocalEndpointWarning)
    private fun resultJson(a:AnalysisResult)=JSONObject().put("id",a.id).put("videoId",a.videoId).put("summary",a.descriptionLine1).put("details",a.descriptionLine2).put("category1",a.categoryLevel1).put("category2",a.categoryLevel2).put("category3",a.categoryLevel3).put("plan",a.formattedActionPlan).put("urls",JSONArray(a.urls)).put("code",JSONArray(a.codeOrCommands)).put("evidence",a.evidenceJson).put("source",a.source).put("createdAt",a.createdAt).put("updatedAt",a.updatedAt)
    private fun load(){check(receiptFile.exists());receipt=JSONObject(receiptFile.readText());check(receipt.getString("package")==context.packageName);check(receipt.getString("endpoint")==endpoint&&receipt.getString("model")==model);items().forEach(::validateEntry)}
    private fun persist(){receiptFile.writeText(receipt.toString(2))}
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun await(timeout:Long=15000,p:()->Boolean)=compose.waitUntil(timeout,p)
    private fun <T> io(block:suspend()->T):T=runBlocking(Dispatchers.IO){block()}
}
