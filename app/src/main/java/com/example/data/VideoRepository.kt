package com.example.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class VideoRepository(private val context: Context) {
    private val db = VideoDatabase.getDatabase(context)
    val videoDao = db.videoItemDao()
    val frameDao = db.extractedFrameDao()
    val analysisDao = db.analysisResultDao()
    val scanDao = db.scanRunDao()
    val processingDao = db.processingRunDao()
    val settingsDao = db.appSettingsDao()

    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    val securePrefs = EncryptedSharedPreferences.create(
        context,
        "secure_app_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getTargetFolders(): List<String> {
        return prefs.getStringSet("target_folders", emptySet())?.toList() ?: emptyList()
    }

    fun addTargetFolder(uriStr: String) {
        val set = prefs.getStringSet("target_folders", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(uriStr)
        prefs.edit().putStringSet("target_folders", set).apply()
    }

    fun removeTargetFolder(uriStr: String) {
        val set = prefs.getStringSet("target_folders", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(uriStr)
        prefs.edit().putStringSet("target_folders", set).apply()
    }

    private fun getRelativePathPrefix(treeUriStr: String): String? {
        val path = Uri.parse(treeUriStr).path ?: return null
        val split = path.split(":")
        if (split.size >= 2) {
            return split[1] // Return the path portion regardless of primary tag to support SD card / external storage
        }
        return null
    }

    // Setup default app settings if not exists
    suspend fun getOrInitializeSettings(): AppSettings {
        val current = settingsDao.getSettingsDirect()
        if (current == null) {
            val defaultSettings = AppSettings()
            settingsDao.insertSettings(defaultSettings)
            return defaultSettings
        }
        return current
    }

    // Media Access State Detector
    fun getMediaAccessState(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val fullGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED

            if (fullGranted) {
                "Full access"
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val partialGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
                if (partialGranted) "Limited access" else "Denied"
            } else {
                "Denied"
            }
        } else {
            val legacyGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (legacyGranted) "Full access" else "Denied"
        }
    }

    // Endpoint URL safety validator
    fun validateEndpoint(urlStr: String): Map<String, Any> {
        val host = try {
            URL(urlStr).host
        } catch (e: Exception) {
            return mapOf("valid" to false, "isPrivate" to false, "reason" to "Invalid URL format")
        }

        if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") {
            return mapOf("valid" to true, "isPrivate" to true)
        }

        // Validate private IPv4 ranges (RFC 1918)
        val ipParts = host.split(".").mapNotNull { it.toIntOrNull() }
        if (ipParts.size == 4) {
            val p1 = ipParts[0]
            val p2 = ipParts[1]
            val isPrivate = (p1 == 10) ||
                    (p1 == 172 && p2 in 16..31) ||
                    (p1 == 192 && p2 == 168)
            return mapOf("valid" to true, "isPrivate" to isPrivate)
        }

        // Hostnames or unknown IP scopes are flagged as public/unconfirmed
        return mapOf("valid" to true, "isPrivate" to false)
    }

    // Scan videos and images from MediaStore, applying target folder filters if present
    suspend fun scanLocalVideos(): ScanRun = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val scope = getMediaAccessState()
        
        val scanRunId = scanDao.insertScanRun(ScanRun(
            startedAt = startedAt,
            accessScope = scope,
            status = "running"
        ))

        var found = 0
        var added = 0
        var updated = 0

        try {
            val folders = getTargetFolders().mapNotNull { getRelativePathPrefix(it) }
            val selection = if (folders.isNotEmpty()) {
                folders.joinToString(" OR ") { "${MediaStore.MediaColumns.DATA} LIKE '%${it}%'" }
            } else null
            
            val collections = listOf(
                Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, MediaStore.Video.Media.DURATION),
                Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, null)
            )

            for ((uri, isVideo, durationColName) in collections) {
                val projection = mutableListOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )
                if (durationColName != null) {
                    projection.add(durationColName)
                }

                context.contentResolver.query(
                    uri, projection.toTypedArray(), selection, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val durCol = if (durationColName != null) cursor.getColumnIndexOrThrow(durationColName) else -1

                    while (cursor.moveToNext()) {
                        found++
                        val msId = cursor.getLong(idCol)
                        val displayName = cursor.getString(nameCol) ?: "Media_$msId"
                        val mimeType = cursor.getString(mimeCol)
                        val sizeBytes = cursor.getLong(sizeCol)
                        val durationMs = if (durCol != -1) cursor.getLong(durCol) else 0L
                        val dateAdded = cursor.getLong(addedCol)
                        val dateModified = cursor.getLong(modCol)
                        val videoUri = Uri.withAppendedPath(uri, msId.toString()).toString()

                        val existing = videoDao.getVideoByUri(videoUri)
                        if (existing == null) {
                            videoDao.insertVideo(VideoItem(
                                mediaStoreId = msId,
                                uriString = videoUri,
                                displayName = displayName,
                                relativePath = null,
                                mimeType = mimeType,
                                sizeBytes = sizeBytes,
                                durationMs = durationMs,
                                dateAdded = dateAdded,
                                dateModified = dateModified,
                                mediaAccessScope = scope,
                                status = "new"
                            ))
                            added++
                        } else {
                            if (existing.dateModified != dateModified) {
                                videoDao.updateVideo(existing.copy(
                                    displayName = displayName,
                                    mimeType = mimeType,
                                    sizeBytes = sizeBytes,
                                    durationMs = durationMs,
                                    dateAdded = dateAdded,
                                    dateModified = dateModified,
                                    lastScannedAt = System.currentTimeMillis()
                                ))
                                updated++
                            }
                        }
                    }
                }
            }

            val finalRun = ScanRun(
                id = scanRunId,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                videosFound = found,
                videosAdded = added,
                videosUpdated = updated,
                accessScope = scope,
                status = "finished"
            )
            scanDao.updateScanRun(finalRun)
            finalRun
        } catch (e: Exception) {
            val errorRun = ScanRun(
                id = scanRunId,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                videosFound = found,
                videosAdded = added,
                videosUpdated = updated,
                accessScope = scope,
                status = "error",
                errorMessage = e.localizedMessage ?: "Unknown scanning error"
            )
            scanDao.updateScanRun(errorRun)
            errorRun
        }
    }

    // Sample video frames or decode image and perform ML Kit Latin English OCR
    suspend fun sampleFramesAndOCR(video: VideoItem, onStepPercent: (Int) -> Unit): List<ExtractedFrame> = withContext(Dispatchers.IO) {
        val framesList = mutableListOf<ExtractedFrame>()
        
        try {
            frameDao.deleteFramesForVideo(video.id)

            if (video.mimeType?.startsWith("image/") == true) {
                onStepPercent(50)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val src = android.graphics.ImageDecoder.createSource(context.contentResolver, Uri.parse(video.uriString))
                    android.graphics.ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, Uri.parse(video.uriString))
                }
                
                val ocrResult = runOcr(bitmap)
                val cleanedText = ocrResult.trim()
                if (cleanedText.isNotEmpty()) {
                    val frameItem = ExtractedFrame(
                        videoId = video.id,
                        timestampMs = 0,
                        ocrText = cleanedText
                    )
                    frameDao.insertFrame(frameItem)
                    framesList.add(frameItem)
                }
                onStepPercent(100)
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(video.uriString))
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: video.durationMs ?: 15_000L
                    
                    val sampleCount = 6 // sample 6 frames
                    val intervalUs = (durationMs * 1000) / (sampleCount + 1)

                    for (i in 1..sampleCount) {
                        val timeUs = i * intervalUs
                        val timeMs = timeUs / 1000
                        onStepPercent((i * 100) / sampleCount)

                        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (bitmap != null) {
                            val ocrResult = runOcr(bitmap)
                            val cleanedText = ocrResult.trim()
                            if (cleanedText.isNotEmpty()) {
                                val frameItem = ExtractedFrame(
                                    videoId = video.id,
                                    timestampMs = timeMs,
                                    ocrText = cleanedText
                                )
                                frameDao.insertFrame(frameItem)
                                framesList.add(frameItem)
                            }
                        }
                    }
                } finally {
                    try {
                        retriever.release()
                    } catch (ex: Exception) {
                        Log.e("VideoRepository", "Retriever release error", ex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoRepository", "Frame extraction or OCR failed for ${video.displayName}", e)
            throw e
        }
        framesList
    }

    // Perform OCR asynchronously with callback mapping to suspend
    private suspend fun runOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        try {
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
            )
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    continuation.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.e("VideoRepository", "ML Kit OCR processing error", e)
                    continuation.resume("") // fallback to empty
                }
        } catch (e: Exception) {
            continuation.resume("")
        }
    }

    // Execute Local LLM Ollama endpoint or Fallback to keyword tagging
    suspend fun analyzeVideoKnowledge(
        video: VideoItem,
        frames: List<ExtractedFrame>,
        userNotes: String?,
        settings: AppSettings
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val ocrAggregate = frames.joinToString("\n") { "[Frame @ ${formatTimestamp(it.timestampMs)}] ${it.ocrText}" }
        val promptText = buildString {
            append("Analyze the following video content. ")
            append("Filename: ${video.displayName}\n")
            append("Duration: ${formatDuration(video.durationMs ?: 0)} seconds\n")
            if (ocrAggregate.isNotEmpty()) {
                append("Extracted Text by Timestamps:\n")
                append(ocrAggregate)
                append("\n")
            }
            if (!userNotes.isNullOrEmpty()) {
                append("User notes & transcript details:\n")
                append(userNotes)
                append("\n")
            }
            append("\nReturn JSON format matching:")
            append("\n{\n  \"description\": [\"line 1\", \"line 2\"],\n  \"categoryLevel1\": \"Technical | Non-technical\",\n  \"categoryLevel2\": \"...\",\n  \"categoryLevel3\": \"...\",\n  \"headings\": [\n    {\n      \"heading\": \"...\",\n      \"subheadings\": [\n        {\n          \"subheading\": \"...\",\n          \"numberedSteps\": [\"1. ...\", \"2. ...\"],\n          \"bulletPoints\": [\"...\", \"...\"]\n        }\n      ]\n    }\n  ],\n  \"urls\": [\"...\"],\n  \"codeOrCommands\": [\"...\"],\n  \"evidenceNotes\": [\"...\"]\n}")
        }

        if (settings.ollamaEnabled) {
            val validation = validateEndpoint(settings.ollamaEndpointUrl)
            val isValid = validation["valid"] as? Boolean ?: false
            val isPrivate = validation["isPrivate"] as? Boolean ?: false

            // Security Policy: permit cleartext or non-local endpoints if user confirmed warning
            val isUrlHttps = settings.ollamaEndpointUrl.lowercase(java.util.Locale.getDefault()).startsWith("https://")
            val securityCleared = if (isUrlHttps) {
                isPrivate || settings.confirmedNonLocalEndpointWarning
            } else {
                // HTTP: allow if private OR if they confirmed the warning
                isPrivate || settings.confirmedNonLocalEndpointWarning
            }

            if (isValid && securityCleared) {
                try {
                    val provider = prefs.getString("llm_provider", "") ?: "ollama_local"
                    val apiKey = securePrefs.getString("llm_api_key", "") ?: ""
                    val timeout = if (provider == "ollama_cloud") 15L else settings.requestTimeoutSeconds.toLong()

                    val client = OkHttpClient.Builder()
                        .connectTimeout(timeout, TimeUnit.SECONDS)
                        .readTimeout(timeout, TimeUnit.SECONDS)
                        .writeTimeout(timeout, TimeUnit.SECONDS)
                        .build()

                    var targetUrl = settings.ollamaEndpointUrl.trim()
                    if (targetUrl.endsWith("/api/generate")) targetUrl = targetUrl.removeSuffix("/api/generate")
                    if (targetUrl.endsWith("/api/generate/")) targetUrl = targetUrl.removeSuffix("/api/generate/")
                    if (targetUrl.endsWith("/v1/chat/completions")) targetUrl = targetUrl.removeSuffix("/v1/chat/completions")
                    if (targetUrl.endsWith("/v1/chat/completions/")) targetUrl = targetUrl.removeSuffix("/v1/chat/completions/")
                    if (targetUrl.endsWith("/")) targetUrl = targetUrl.removeSuffix("/")
                    
                    val isV1 = targetUrl.endsWith("/v1")
                    targetUrl = if (isV1) "$targetUrl/chat/completions" else "$targetUrl/api/generate"

                    val jsonReq = JSONObject()
                    if (isV1) {
                        jsonReq.put("model", settings.ollamaModelName)
                        val messagesArray = JSONArray()
                        val msgObj = JSONObject()
                        msgObj.put("role", "user")
                        msgObj.put("content", promptText)
                        messagesArray.put(msgObj)
                        jsonReq.put("messages", messagesArray)
                        val formatObj = JSONObject()
                        formatObj.put("type", "json_object")
                        jsonReq.put("response_format", formatObj)
                        jsonReq.put("stream", false)
                    } else {
                        jsonReq.put("model", settings.ollamaModelName)
                        jsonReq.put("prompt", promptText)
                        jsonReq.put("stream", false)
                        jsonReq.put("format", "json")
                    }

                    val requestBody = jsonReq.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val reqBuilder = Request.Builder().url(targetUrl).post(requestBody)
                    if (provider == "ollama_cloud" && apiKey.isNotBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }
                    val request = reqBuilder.build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val respStr = response.body?.string()
                            if (!respStr.isNullOrEmpty()) {
                                try {
                                    return@withContext parseOllamaResponse(video.id, respStr)
                                } catch (je: Exception) {
                                    // Handle hallucinated/malformed JSON without throwing a hard exception or crashing
                                    Log.e("VideoRepository", "Ollama response JSON parsing failed or non-JSON hallucination returned. Using deterministic fallback.", je)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VideoRepository", "Ollama LLM call failed. Falling back to deterministic category.", e)
                }
            }
        }

        // Deterministic keyword classifier fallback
        generateDeterministicFallback(video, frames, userNotes)
    }

    private fun parseOllamaResponse(videoId: Long, jsonResponse: String): AnalysisResult {
        val root = JSONObject(jsonResponse)
        var rawResponseString = if (root.has("response")) root.getString("response") else jsonResponse

        // Handle OpenAI format: {"choices": [{"message": {"content": "{...}"}}]}
        if (root.has("choices")) {
            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val msg = choices.getJSONObject(0).optJSONObject("message")
                if (msg != null && msg.has("content")) {
                    rawResponseString = msg.getString("content")
                }
            }
        }

        val responseJson = JSONObject(rawResponseString)
        val descArr = responseJson.optJSONArray("description")
        val desc1 = descArr?.optString(0) ?: "Analyzed video documentation."
        val desc2 = descArr?.optString(1) ?: "Contains extracted timeline."

        val cat1 = responseJson.optString("categoryLevel1", "Technical")
        val cat2 = responseJson.optString("categoryLevel2", "AI workflow")
        val cat3 = responseJson.optString("categoryLevel3", "Llama")

        // Parse formatted steps & headings into scannable text
        val plan = StringBuilder()
        val headingsArr = responseJson.optJSONArray("headings")
        if (headingsArr != null) {
            for (h in 0 until headingsArr.length()) {
                val headObj = headingsArr.optJSONObject(h) ?: continue
                val title = headObj.optString("heading")
                plan.append("## $title\n")
                val subheadings = headObj.optJSONArray("subheadings")
                if (subheadings != null) {
                    for (s in 0 until subheadings.length()) {
                        val subObj = subheadings.optJSONObject(s) ?: continue
                        val subTitle = subObj.optString("subheading")
                        plan.append("### $subTitle\n")
                        
                        val numbered = subObj.optJSONArray("numberedSteps")
                        if (numbered != null) {
                            for (n in 0 until numbered.length()) {
                                plan.append("${numbered.getString(n)}\n")
                            }
                        }
                        
                        val bullets = subObj.optJSONArray("bulletPoints")
                        if (bullets != null) {
                            for (b in 0 until bullets.length()) {
                                plan.append("- ${bullets.getString(b)}\n")
                            }
                        }
                    }
                }
                plan.append("\n")
            }
        }

        val urlsList = mutableListOf<String>()
        responseJson.optJSONArray("urls")?.let { arr ->
            for (i in 0 until arr.length()) urlsList.add(arr.getString(i))
        }

        val codeList = mutableListOf<String>()
        responseJson.optJSONArray("codeOrCommands")?.let { arr ->
            for (i in 0 until arr.length()) codeList.add(arr.getString(i))
        }

        val evidenceNotesList = mutableListOf<String>()
        responseJson.optJSONArray("evidenceNotes")?.let { arr ->
            for (i in 0 until arr.length()) evidenceNotesList.add(arr.getString(i))
        }

        return AnalysisResult(
            videoId = videoId,
            descriptionLine1 = desc1,
            descriptionLine2 = desc2,
            categoryLevel1 = cat1,
            categoryLevel2 = cat2,
            categoryLevel3 = cat3,
            formattedActionPlan = plan.toString().trim(),
            urls = urlsList,
            codeOrCommands = codeList,
            evidenceJson = evidenceNotesList.joinToString("\n"),
            source = "ollama"
        )
    }

    fun generateDeterministicFallback(
        video: VideoItem,
        frames: List<ExtractedFrame>,
        userNotes: String?
    ): AnalysisResult {
        val fullContent = buildString {
            append(video.displayName.lowercase())
            append(" ")
            append(userNotes?.lowercase() ?: "")
            append(" ")
            frames.forEach { append(it.ocrText.lowercase()).append(" ") }
        }

        // Tech keywords
        val techKeywords = listOf(
            "github", "code", "programming", "developer", "ide", "bug", "python", "kotlin",
            "javascript", "react", "setup", "linux", "terminal", "install", "api", "compiler",
            "ai", "ollama", "local", "prompt", "model", "git", "database", "query", "docker"
        )
        
        val isTechnical = techKeywords.any { fullContent.contains(it) }
        val cat1 = if (isTechnical) "Technical" else "Non-technical"

        var cat2 = "Product idea"
        var cat3 = "General"

        if (isTechnical) {
            if (fullContent.contains("git") || fullContent.contains("github") || fullContent.contains("repo")) {
                cat2 = "New GitHub project"
                cat3 = "git-repository"
            } else if (fullContent.contains("bug") || fullContent.contains("crash") || fullContent.contains("fix") || fullContent.contains("error")) {
                cat2 = "Bug fix"
                cat3 = "debugging"
            } else if (fullContent.contains("ai") || fullContent.contains("ollama") || fullContent.contains("prompt") || fullContent.contains("llm")) {
                cat2 = "AI workflow"
                cat3 = "artificial-intelligence"
            } else if (fullContent.contains("tutorial") || fullContent.contains("learn") || fullContent.contains("class") || fullContent.contains("how")) {
                cat2 = "Coding tutorial"
                cat3 = "education"
            } else if (fullContent.contains("terminal") || fullContent.contains("bash") || fullContent.contains("cmd") || fullContent.contains("gradle")) {
                cat2 = "Developer tool"
                cat3 = "command-line"
            } else if (fullContent.contains("setup") || fullContent.contains("install") || fullContent.contains("config")) {
                cat2 = "Computer setup"
                cat3 = "system-config"
            } else {
                cat2 = "Automation idea"
                cat3 = "automation"
            }
        } else {
            if (fullContent.contains("travel") || fullContent.contains("trip") || fullContent.contains("hotel") || fullContent.contains("beach")) {
                cat2 = "Holiday destination"
                cat3 = "leisure"
            } else if (fullContent.contains("life") || fullContent.contains("routine") || fullContent.contains("hack")) {
                cat2 = "Life hack"
                cat3 = "productivity"
            } else if (fullContent.contains("diy") || fullContent.contains("wood") || fullContent.contains("build")) {
                cat2 = "DIY"
                cat3 = "crafting"
            } else if (fullContent.contains("art") || fullContent.contains("paint") || fullContent.contains("draw")) {
                cat2 = "Art idea"
                cat3 = "creative"
            } else if (fullContent.contains("home") || fullContent.contains("kitchen") || fullContent.contains("room")) {
                cat2 = "Home improvement"
                cat3 = "renovation"
            } else if (fullContent.contains("workout") || fullContent.contains("fit") || fullContent.contains("health") || fullContent.contains("diet")) {
                cat2 = "Health or routine"
                cat3 = "fitness"
            }
        }

        // Extracted items logic
        val urls = mutableListOf<String>()
        val codeList = mutableListOf<String>()
        val evidenceNotes = mutableListOf<String>()

        // Look for URLs
        val urlRegex = "(https?://[a-zA-Z0-9./-]+)".toRegex()
        urlRegex.findAll(fullContent).forEach { match ->
            val u = match.value
            if (!urls.contains(u)) urls.add(u)
        }

        // Look for commands/code matching basic terminal actions
        val commandRegex = "(npm install|pip install|git clone|gradle|sudo apt|docker run|./gradlew|run|build)[a-zA-Z0-9\\s.-]*".toRegex()
        commandRegex.findAll(fullContent).forEach { match ->
            val c = match.value.trim()
            if (!codeList.contains(c)) codeList.add(c)
        }

        // OCR timeline logs
        frames.forEach {
            val shortText = if (it.ocrText.length > 50) it.ocrText.take(50) + "..." else it.ocrText
            evidenceNotes.add("Scene evidence at ${formatTimestamp(it.timestampMs)}: $shortText")
        }

        val plan = StringBuilder()
        plan.append("## Fallback Summary & Structured Roadmap\n")
        plan.append("### Step 1: Initialize Workspace\n")
        plan.append("1. Locate your saved source video: **${video.displayName}**.\n")
        plan.append("2. Setup target environment categorized under `$cat1 > $cat2 > $cat3`.\n\n")
        plan.append("### Step 2: Extract Relevant Highlights\n")
        if (evidenceNotes.isNotEmpty()) {
            evidenceNotes.forEach {
                plan.append("- $it\n")
            }
        } else {
            plan.append("- No substantial textual overlay features were detected in sampled frames.\n")
        }
        plan.append("\n### Step 3: Actionable Execution Plan\n")
        plan.append("1. Review matching frames and transcript updates if populated.\n")
        plan.append("2. Keep references up-to-date and complete archived state within the app dashboard.\n")

        return AnalysisResult(
            videoId = video.id,
            descriptionLine1 = "Deterministic fallback generated locally.",
            descriptionLine2 = "Categorized video frame characteristics dynamically.",
            categoryLevel1 = cat1,
            categoryLevel2 = cat2,
            categoryLevel3 = cat3,
            formattedActionPlan = plan.toString(),
            urls = urls,
            codeOrCommands = codeList,
            evidenceJson = evidenceNotes.joinToString("\n"),
            source = "fallback"
        )
    }

    // String utility helpers
    private fun formatTimestamp(ms: Long): String {
        val totalSecs = ms / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun formatDuration(ms: Long): String {
        val secs = ms / 1000
        return if (secs == 0L) "0" else secs.toString()
    }
}
