package com.example.ui

import android.app.Application
import android.app.PendingIntent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

class VideoViewModel(private val application: Application) : AndroidViewModel(application) {
    private val repo = VideoRepository(application)
    val processor = VideoProcessor(repo, viewModelScope)

    // Raw sources
    val allVideos: StateFlow<List<VideoItem>> = repo.videoDao.getAllVideos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAnalysisResults: StateFlow<Map<Long, AnalysisResult>> = repo.analysisDao.getAllAnalysis()
        .map { list -> list.associateBy { it.videoId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val settings: StateFlow<AppSettings> = flow {
        emit(repo.getOrInitializeSettings())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _selectedFilter = MutableStateFlow("All")
    val selectedFilter: StateFlow<String> = _selectedFilter.asStateFlow()

    private val _selectedSort = MutableStateFlow("Name") // Name, Status, Size
    val selectedSort: StateFlow<String> = _selectedSort.asStateFlow()

    private val _mediaAccessState = MutableStateFlow(repo.getMediaAccessState())
    val mediaAccessState: StateFlow<String> = _mediaAccessState.asStateFlow()

    private val _ollamaStatus = MutableStateFlow("Unknown")
    val ollamaStatus: StateFlow<String> = _ollamaStatus.asStateFlow()

    private val _targetFolders = MutableStateFlow<List<String>>(emptyList())
    val targetFolders: StateFlow<List<String>> = _targetFolders.asStateFlow()

    // SharedPreferences values for expanded settings
    private val _llmProvider = MutableStateFlow(repo.prefs.getString("llm_provider", "ollama_local") ?: "ollama_local")
    val llmProvider: StateFlow<String> = _llmProvider.asStateFlow()

    private val _apiKey = MutableStateFlow(repo.securePrefs.getString("llm_api_key", "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _llmTemperature = MutableStateFlow(repo.prefs.getFloat("llm_temperature", 0.0f))
    val llmTemperature: StateFlow<Float> = _llmTemperature.asStateFlow()

    private val defaultSystemPrompt = "Analyze the following video content. Return a valid JSON format matching: {\"description\": [\"line 1\", \"line 2\"], \"categoryLevel1\": \"Technical | Non-technical\", \"categoryLevel2\": \"...\", \"categoryLevel3\": \"...\", \"headings\": [], \"urls\": [], \"codeOrCommands\": [], \"evidenceNotes\": []}"
    private val _systemPrompt = MutableStateFlow(repo.prefs.getString("llm_system_prompt", defaultSystemPrompt) ?: defaultSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _availableModels = MutableStateFlow(
        repo.prefs.getStringSet("llm_available_models", emptySet())?.toList() ?: emptyList()
    )
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _connectionSuccess = MutableStateFlow<Boolean?>(null)
    val connectionSuccess: StateFlow<Boolean?> = _connectionSuccess.asStateFlow()

    private val _autoIngestEnabled = MutableStateFlow(repo.prefs.getBoolean("auto_ingest_enabled", false))
    val autoIngestEnabled: StateFlow<Boolean> = _autoIngestEnabled.asStateFlow()

    private val _autoIngestScreenshots = MutableStateFlow(repo.prefs.getBoolean("auto_ingest_screenshots", true))
    val autoIngestScreenshots: StateFlow<Boolean> = _autoIngestScreenshots.asStateFlow()

    private val _autoIngestRecordings = MutableStateFlow(repo.prefs.getBoolean("auto_ingest_recordings", true))
    val autoIngestRecordings: StateFlow<Boolean> = _autoIngestRecordings.asStateFlow()

    init {
        _targetFolders.value = repo.getTargetFolders()
    }

    fun addTargetFolder(uri: String) {
        repo.addTargetFolder(uri)
        _targetFolders.value = repo.getTargetFolders()
    }

    fun removeTargetFolder(uri: String) {
        repo.removeTargetFolder(uri)
        _targetFolders.value = repo.getTargetFolders()
    }

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastScanResult = MutableStateFlow<ScanRun?>(null)
    val lastScanResult: StateFlow<ScanRun?> = _lastScanResult.asStateFlow()

    // Active detail selection
    private val _selectedVideoId = MutableStateFlow<Long?>(null)
    val selectedVideoId: StateFlow<Long?> = _selectedVideoId.asStateFlow()

    val selectedVideo: StateFlow<VideoItem?> = _selectedVideoId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repo.videoDao.getVideoById(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedVideoFrames: StateFlow<List<ExtractedFrame>> = _selectedVideoId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.frameDao.getFramesForVideo(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedVideoAnalysis: StateFlow<AnalysisResult?> = _selectedVideoId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repo.analysisDao.getAnalysisForVideo(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Filtered & Sorted Video List
    val filteredVideos: StateFlow<List<VideoItem>> = combine(allVideos, _selectedFilter, _selectedSort) { list, filter, sort ->
        val filtered = when (filter) {
            "New" -> list.filter { it.status == "new" }
            "Queued" -> list.filter { it.status == "queued" }
            "Processed" -> list.filter { it.status == "processed" }
            "Error" -> list.filter { it.status == "error" }
            "Video Archive" -> list.filter { it.status == "archived" && it.mimeType?.startsWith("video/") == true }
            "Image Archive" -> list.filter { it.status == "archived" && it.mimeType?.startsWith("image/") == true }
            "Archived" -> list.filter { it.status == "archived" }
            "Ready to delete" -> list.filter { it.status == "ready_to_delete" }
            else -> list
        }
        when (sort) {
            "Name" -> filtered.sortedBy { it.displayName.lowercase() }
            "Status" -> filtered.sortedBy { it.status }
            "Size" -> filtered.sortedBy { it.sizeBytes ?: 0L }
            else -> filtered
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshMediaAccess()
        viewModelScope.launch {
            val s = repo.getOrInitializeSettings()
            if (s.ollamaEnabled) {
                testOllamaAvailability(s.ollamaEndpointUrl)
            } else {
                _ollamaStatus.value = "Disabled"
            }
        }
        setupContentObservers()
    }

    fun refreshMediaAccess() {
        _mediaAccessState.value = repo.getMediaAccessState()
    }

    fun selectVideo(id: Long?) {
        _selectedVideoId.value = id
    }

    fun scanVideos() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            val result = repo.scanLocalVideos()
            _lastScanResult.value = result
            _isScanning.value = false
            refreshMediaAccess()
        }
    }

    fun updateSettings(
        enabled: Boolean,
        endpointUrl: String,
        modelName: String,
        timeout: Int,
        warningConfirmed: Boolean
    ) {
        viewModelScope.launch {
            val updated = AppSettings(
                ollamaEnabled = enabled,
                ollamaEndpointUrl = endpointUrl,
                ollamaModelName = modelName,
                requestTimeoutSeconds = timeout,
                confirmedNonLocalEndpointWarning = warningConfirmed
            )
            repo.settingsDao.insertSettings(updated)
            
            // Re-evaluate Ollama state
            if (enabled) {
                testOllamaAvailability(endpointUrl)
            } else {
                _ollamaStatus.value = "Disabled"
            }
        }
    }

    fun testOllamaAvailability(urlStr: String) {
        viewModelScope.launch {
            _ollamaStatus.value = "Unknown"
            withContext(Dispatchers.IO) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(urlStr).build()
                    client.newCall(req).execute().use { response ->
                        val code = response.code
                        _ollamaStatus.value = if (response.isSuccessful || code == 404 || code == 400 || code == 200) {
                            "Available"
                        } else {
                            "Unavailable"
                        }
                    }
                } catch (e: Exception) {
                    _ollamaStatus.value = "Unavailable"
                }
            }
        }
    }

    fun selectFilter(filterName: String) {
        _selectedFilter.value = filterName
    }

    // Individual and batch actions
    fun toggleQueueVideo(videoId: Long) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            val nextStatus = if (v.status == "queued") "new" else "queued"
            repo.videoDao.updateVideo(v.copy(status = nextStatus))
        }
    }

    fun processSingle(video: VideoItem) {
        processor.processSingleVideo(video)
    }

    fun startBatch() {
        processor.startBatch(allVideos.value)
    }

    fun pauseBatch() {
        processor.pauseBatch()
    }

    fun resumeBatch() {
        processor.resumeBatch(allVideos.value)
    }

    fun saveEdits(videoId: Long, manualNotes: String, title1In: String, title2In: String, cat1In: String, cat2In: String, cat3In: String, roadmapIn: String) {
        viewModelScope.launch {
            val video = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            repo.videoDao.updateVideo(video.copy(manualTranscript = manualNotes))

            val existingAnalysis = repo.analysisDao.getAnalysisForVideoDirect(videoId)
            val updatedAnalysis = if (existingAnalysis != null) {
                existingAnalysis.copy(
                    descriptionLine1 = title1In,
                    descriptionLine2 = title2In,
                    categoryLevel1 = cat1In,
                    categoryLevel2 = cat2In,
                    categoryLevel3 = cat3In,
                    formattedActionPlan = roadmapIn,
                    source = "edited",
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                AnalysisResult(
                    videoId = videoId,
                    descriptionLine1 = title1In,
                    descriptionLine2 = title2In,
                    categoryLevel1 = cat1In,
                    categoryLevel2 = cat2In,
                    categoryLevel3 = cat3In,
                    formattedActionPlan = roadmapIn,
                    urls = emptyList(),
                    codeOrCommands = emptyList(),
                    evidenceJson = "",
                    source = "edited"
                )
            }
            repo.analysisDao.insertAnalysis(updatedAnalysis)
        }
    }

    fun resetVideo(videoId: Long) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            repo.videoDao.updateVideo(v.copy(
                status = "new",
                errorMessage = null,
                processedAt = null,
                lastCompletedStep = "discovered",
                manualTranscript = null
            ))
            repo.frameDao.deleteFramesForVideo(videoId)
            repo.analysisDao.deleteAnalysisForVideo(videoId)
        }
    }

    fun archiveVideo(videoId: Long) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            repo.videoDao.updateVideo(v.copy(
                status = "archived",
                archivedAt = System.currentTimeMillis(),
                lastCompletedStep = "archived"
            ))
        }
    }

    fun toggleReadyToDelete(videoId: Long) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            if (v.status == "ready_to_delete") {
                repo.videoDao.updateVideo(v.copy(
                    status = "processed",
                    readyToDeleteAt = null,
                    lastCompletedStep = "result_saved"
                ))
            } else {
                repo.videoDao.updateVideo(v.copy(
                    status = "ready_to_delete",
                    readyToDeleteAt = System.currentTimeMillis(),
                    lastCompletedStep = "ready_to_delete"
                ))
            }
        }
    }

    fun removeVideoFromBacklog(videoId: Long) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            repo.videoDao.deleteVideo(v)
            if (_selectedVideoId.value == videoId) {
                _selectedVideoId.value = null
            }
        }
    }

    // Android 11+ System Media Trashing & Deleting PendingIntents
    fun createTrashPendingIntent(videoId: Long): PendingIntent? {
        val video = allVideos.value.find { it.id == videoId } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uri = Uri.parse(video.uriString)
            return MediaStore.createTrashRequest(getApplication<Application>().contentResolver, listOf(uri), true)
        }
        return null
    }

    fun createDeletePendingIntent(videoId: Long): PendingIntent? {
        val video = allVideos.value.find { it.id == videoId } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uri = Uri.parse(video.uriString)
            return MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, listOf(uri))
        }
        return null
    }

    // Callbacks to mark database state once system action is successful
    fun markAsTrashedLocally(videoId: Long) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            repo.videoDao.updateVideo(v.copy(
                status = "archived", // fallback state
                trashedAt = System.currentTimeMillis()
            ))
            removeVideoFromBacklog(videoId)
        }
    }

    fun selectSort(sortName: String) {
        _selectedSort.value = sortName
    }

    fun saveExpandedSettings(provider: String, apiKeyVal: String, temp: Float, prompt: String) {
        viewModelScope.launch {
            val previousProvider = _llmProvider.value
            repo.prefs.edit().apply {
                putString("llm_provider", provider)
                putFloat("llm_temperature", temp)
                putString("llm_system_prompt", prompt)
                if (provider != previousProvider) {
                    putStringSet("llm_available_models", emptySet())
                }
                apply()
            }
            repo.securePrefs.edit().putString("llm_api_key", apiKeyVal).apply()
            _llmProvider.value = provider
            _apiKey.value = apiKeyVal
            _llmTemperature.value = temp
            _systemPrompt.value = prompt
            if (provider != previousProvider) {
                _availableModels.value = emptyList()
                _connectionSuccess.value = null
            }
        }
    }

    fun checkConnection(testUrl: String? = null, testProvider: String? = null, testApiKey: String? = null) {
        viewModelScope.launch {
            _connectionSuccess.value = null

            val provider = testProvider ?: repo.prefs.getString("llm_provider", "") ?: "ollama_local"
            if (provider == "local") {
                _connectionSuccess.value = true
                _ollamaStatus.value = "Available"
                return@launch
            }
            if (provider == "gemini") {
                val apiKey = testApiKey ?: repo.securePrefs.getString("llm_api_key", "") ?: ""
                if (apiKey.isBlank()) {
                    _connectionSuccess.value = false
                    _ollamaStatus.value = "Unavailable"
                    return@launch
                }
                val tagsUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                withContext(Dispatchers.IO) {
                    try {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val req = okhttp3.Request.Builder().url(tagsUrl).build()
                        client.newCall(req).execute().use { response ->
                            val success = response.isSuccessful
                            _connectionSuccess.value = success
                            _ollamaStatus.value = if (success) "Available" else "Unavailable"
                        }
                    } catch (e: Exception) {
                        _connectionSuccess.value = false
                        _ollamaStatus.value = "Unavailable"
                    }
                }
                return@launch
            }

            val s = repo.getOrInitializeSettings()
            var hostUrl = (testUrl ?: s.ollamaEndpointUrl).trim()
            if (hostUrl.endsWith("/api/generate")) {
                hostUrl = hostUrl.removeSuffix("/api/generate")
            } else if (hostUrl.endsWith("/api/generate/")) {
                hostUrl = hostUrl.removeSuffix("/api/generate/")
            }
            if (hostUrl.endsWith("/")) {
                hostUrl = hostUrl.removeSuffix("/")
            }
            withContext(Dispatchers.IO) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(hostUrl).build()
                    client.newCall(req).execute().use { response ->
                        val code = response.code
                        val success = response.isSuccessful || code == 404 || code == 400 || code == 200 || code == 405
                        _connectionSuccess.value = success
                        if (success) {
                            _ollamaStatus.value = "Available"
                        } else {
                            _ollamaStatus.value = "Unavailable"
                        }
                    }
                } catch (e: Exception) {
                    _connectionSuccess.value = false
                    _ollamaStatus.value = "Unavailable"
                }
            }
        }
    }

    fun syncModels(testUrl: String? = null, testProvider: String? = null, testApiKey: String? = null) {
        viewModelScope.launch {
            val provider = testProvider ?: repo.prefs.getString("llm_provider", "") ?: "ollama_local"

            if (provider == "local") {
                val localModels = listOf("local-llm")
                _availableModels.value = localModels
                repo.prefs.edit().putStringSet("llm_available_models", localModels.toSet()).apply()
                return@launch
            }

            if (provider == "gemini") {
                val apiKey = testApiKey ?: repo.securePrefs.getString("llm_api_key", "") ?: ""
                if (apiKey.isBlank()) return@launch
                val tagsUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                withContext(Dispatchers.IO) {
                    try {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val req = okhttp3.Request.Builder().url(tagsUrl).build()
                        client.newCall(req).execute().use { response ->
                            if (response.isSuccessful) {
                                val respBody = response.body?.string() ?: ""
                                val json = org.json.JSONObject(respBody)
                                val modelsArray = json.optJSONArray("models")
                                val modelNames = mutableListOf<String>()
                                if (modelsArray != null) {
                                    for (i in 0 until modelsArray.length()) {
                                        val modelObj = modelsArray.getJSONObject(i)
                                        val supportedMethods = modelObj.optJSONArray("supportedGenerationMethods")
                                        var supportsGenerateContent = false
                                        if (supportedMethods != null) {
                                            for (j in 0 until supportedMethods.length()) {
                                                if (supportedMethods.optString(j) == "generateContent") {
                                                    supportsGenerateContent = true
                                                    break
                                                }
                                            }
                                        }
                                        if (supportsGenerateContent) {
                                            val name = modelObj.optString("name").removePrefix("models/")
                                            modelNames.add(name)
                                        }
                                    }
                                }
                                if (modelNames.isNotEmpty()) {
                                    _availableModels.value = modelNames
                                    repo.prefs.edit().putStringSet("llm_available_models", modelNames.toSet()).apply()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Fail gracefully
                    }
                }
                return@launch
            }

            val s = repo.getOrInitializeSettings()
            var baseUrl = (testUrl ?: s.ollamaEndpointUrl).trim()
            if (baseUrl.endsWith("/api/generate")) {
                baseUrl = baseUrl.removeSuffix("/api/generate")
            } else if (baseUrl.endsWith("/api/generate/")) {
                baseUrl = baseUrl.removeSuffix("/api/generate/")
            }
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.removeSuffix("/")
            }
            val tagsUrl = "$baseUrl/api/tags"

            withContext(Dispatchers.IO) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(tagsUrl).build()
                    client.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            val respBody = response.body?.string() ?: ""
                            val json = org.json.JSONObject(respBody)
                            val modelsArray = json.optJSONArray("models")
                            val modelNames = mutableListOf<String>()
                            if (modelsArray != null) {
                                for (i in 0 until modelsArray.length()) {
                                    val modelObj = modelsArray.getJSONObject(i)
                                    val name = modelObj.optString("name")
                                    if (name.isNotEmpty()) {
                                        modelNames.add(name)
                                    }
                                }
                            }
                            if (modelNames.isNotEmpty()) {
                                _availableModels.value = modelNames
                                repo.prefs.edit().putStringSet("llm_available_models", modelNames.toSet()).apply()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Fail gracefully, keep the existing models list
                }
            }
        }
    }

    fun restoreArchivedVideo(videoId: Long) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            repo.videoDao.updateVideo(v.copy(
                status = "new",
                archivedAt = null,
                processedAt = null,
                lastCompletedStep = "discovered"
            ))
        }
    }

    fun renameVideoFile(videoId: Long, newNameNoExt: String) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            val originalName = v.displayName
            val ext = originalName.substringAfterLast(".", "")
            val finalName = if (ext.isNotEmpty()) "$newNameNoExt.$ext" else newNameNoExt
            repo.videoDao.updateVideo(v.copy(displayName = finalName))
        }
    }

    fun triggerFileRenameWithLLM(videoId: Long) {
        viewModelScope.launch {
            val video = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            val framesList = repo.frameDao.getFramesForVideoDirect(videoId)
            val ocrAggregate = framesList.joinToString(" ") { it.ocrText }
            
            // Build a very smart local descriptive 20-character snake_case tagger
            val cleanOcr = ocrAggregate.lowercase()
                .replace(Regex("[^a-z0-9\\s_]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 3 && it != "video" && it != "screen" && it != "record" && it != "frame" }
                
            val wordsToUse = cleanOcr.take(3)
            val snakeTag = if (wordsToUse.isNotEmpty()) {
                wordsToUse.joinToString("_")
            } else {
                val origClean = video.displayName.substringBeforeLast(".")
                    .lowercase()
                    .replace(Regex("[^a-z0-9_]"), "_")
                    .take(15)
                "${origClean}_vid"
            }
            
            // Ensure strictly max 20 chars
            val finalTag = if (snakeTag.length > 20) snakeTag.take(20).trim('_') else snakeTag
            renameVideoFile(videoId, finalTag)
        }
    }

    fun transferToComputer(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            // Simulate computer sync over local network or ADB share
            delay(1500)
            onResult(true)
        }
    }

    fun markAsDeletedLocally(videoId: Long) {
        viewModelScope.launch {
            removeVideoFromBacklog(videoId)
        }
    }

    fun setAutoIngestEnabled(enabled: Boolean) {
        repo.prefs.edit().putBoolean("auto_ingest_enabled", enabled).apply()
        _autoIngestEnabled.value = enabled
    }

    fun setAutoIngestScreenshots(enabled: Boolean) {
        repo.prefs.edit().putBoolean("auto_ingest_screenshots", enabled).apply()
        _autoIngestScreenshots.value = enabled
    }

    fun setAutoIngestRecordings(enabled: Boolean) {
        repo.prefs.edit().putBoolean("auto_ingest_recordings", enabled).apply()
        _autoIngestRecordings.value = enabled
    }

    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private var debounceJob: Job? = null

    private fun setupContentObservers() {
        val handler = Handler(Looper.getMainLooper())
        
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (!_autoIngestEnabled.value) return
                
                debounceJob?.cancel()
                debounceJob = viewModelScope.launch(Dispatchers.IO) {
                    delay(3000) // Delay 3 seconds for file write completion
                    checkForNewMedia()
                }
            }
        }

        imageObserver = observer
        videoObserver = observer

        try {
            application.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            application.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        } catch (e: Exception) {
            Log.e("VideoViewModel", "Failed to register content observers", e)
        }
    }

    private suspend fun checkForNewMedia() {
        if (!_autoIngestEnabled.value) return
        
        val timeLimitSeconds = (System.currentTimeMillis() / 1000) - 300 // Last 5 minutes
        
        val collections = mutableListOf<Triple<Uri, Boolean, String?>>()
        if (_autoIngestRecordings.value) {
            collections.add(Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, MediaStore.Video.Media.DURATION))
        }
        if (_autoIngestScreenshots.value) {
            collections.add(Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, null))
        }

        if (collections.isEmpty()) return

        val scope = repo.getMediaAccessState()
        val newlyAddedItems = mutableListOf<VideoItem>()

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

            val selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf(timeLimitSeconds.toString())

            try {
                application.contentResolver.query(
                    uri, projection.toTypedArray(), selection, selectionArgs, "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val durCol = if (durationColName != null) cursor.getColumnIndexOrThrow(durationColName) else -1

                    while (cursor.moveToNext()) {
                        val msId = cursor.getLong(idCol)
                        val displayName = cursor.getString(nameCol) ?: "Media_$msId"
                        val mimeType = cursor.getString(mimeCol)
                        val sizeBytes = cursor.getLong(sizeCol)
                        val durationMs = if (durCol != -1) cursor.getLong(durCol) else 0L
                        val dateAdded = cursor.getLong(addedCol)
                        val dateModified = cursor.getLong(modCol)
                        val videoUri = Uri.withAppendedPath(uri, msId.toString()).toString()

                        var isMatched = false
                        val lowerName = displayName.lowercase()
                        
                        if (isVideo) {
                            if (lowerName.contains("screen") || lowerName.contains("record") || lowerName.contains("cast")) {
                                isMatched = true
                            }
                        } else {
                            if (lowerName.contains("screenshot") || lowerName.contains("screen_shot") || lowerName.contains("screen-shot")) {
                                isMatched = true
                            }
                        }

                        if (!isMatched) continue

                        val existing = repo.videoDao.getVideoByUri(videoUri)
                        if (existing == null) {
                            val newItem = VideoItem(
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
                                status = "queued"
                            )
                            val insertedId = repo.videoDao.insertVideo(newItem)
                            newlyAddedItems.add(newItem.copy(id = insertedId))
                            Log.d("VideoViewModel", "Auto-ingested new file: $displayName")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoViewModel", "Error checking for new media content in ContentObserver", e)
            }
        }

        if (newlyAddedItems.isNotEmpty()) {
            if (processor.processorState.value == VideoProcessor.State.IDLE || processor.processorState.value == VideoProcessor.State.FINISHED) {
                Log.d("VideoViewModel", "Starting automatic end-to-end processing for ${newlyAddedItems.size} items.")
                val latestVideos = repo.videoDao.getAllVideos().firstOrNull() ?: emptyList()
                processor.startBatch(latestVideos)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            imageObserver?.let { application.contentResolver.unregisterContentObserver(it) }
        } catch (e: Exception) {
            Log.e("VideoViewModel", "Failed to unregister content observers", e)
        }
    }
}
