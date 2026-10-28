package com.example.ui

import android.app.Application
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.IntentSender
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VideoViewModel @JvmOverloads constructor(
    private val application: Application,
    private val repo: VideoRepository = VideoRepository(application),
    private val connectionClient: okhttp3.Call.Factory? = null,
    private val storageOperations: StorageOperations = StorageOperations(application.contentResolver)
) : AndroidViewModel(application) {
    val processor = VideoProcessor(repo, viewModelScope)

    // Raw sources
    val allVideos: StateFlow<List<VideoItem>> = repo.videoDao.getAllVideos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAnalysisResults: StateFlow<Map<Long, AnalysisResult>> = repo.analysisDao.getAllAnalysis()
        .map { list -> list.associateBy { it.videoId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val inventoryTotals: StateFlow<MediaInventoryTotals> = repo.videoDao.getEligibleInventoryTotals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MediaInventoryTotals(0, 0))

    val sourceAggregates: StateFlow<List<SourceAggregate>> = repo.videoDao.getSourceAggregates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mediaRules: StateFlow<List<MediaRuleCard>> = repo.mediaRuleDao.observeRuleCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customTags: StateFlow<List<CustomTag>> = repo.customTagDao.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tagAssignments: StateFlow<List<MediaTagCrossRef>> = repo.customTagDao.observeAssignments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val binMedia: StateFlow<List<VideoItem>> = repo.binDao.observeMedia()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val binCoordinator = BinCoordinator(application.contentResolver, repo.videoDao, repo.binDao)
    private val _ruleMessage = MutableStateFlow<String?>(null)
    val ruleMessage: StateFlow<String?> = _ruleMessage.asStateFlow()
    private var pendingBinDeleteIds: List<Long> = emptyList()

    private val _selectedSourceCategory = MutableStateFlow<String?>(null)
    val selectedSourceCategory: StateFlow<String?> = _selectedSourceCategory.asStateFlow()

    val selectedSourceSubcategories: StateFlow<List<SubcategoryAggregate>> = _selectedSourceCategory
        .flatMapLatest { category ->
            if (category == null) flowOf(emptyList()) else repo.videoDao.getSubcategoryAggregates(category)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = repo.settingsDao.getSettings()
        .map { it ?: repo.getOrInitializeSettings() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

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
    private val _llmProvider = MutableStateFlow((repo.prefs.getString("llm_provider", "ollama_local") ?: "ollama_local").takeIf { it in setOf("ollama_local", "ollama_cloud", "local") } ?: "local")
    val llmProvider: StateFlow<String> = _llmProvider.asStateFlow()

    private val _hasApiKey = MutableStateFlow(repo.securePrefs.contains("llm_api_key"))
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    private val _llmTemperature = MutableStateFlow(repo.prefs.getFloat("llm_temperature", 0.0f))
    val llmTemperature: StateFlow<Float> = _llmTemperature.asStateFlow()

    private val defaultSystemPrompt = "Analyze the following video content. Return a valid JSON format matching: {\"description\": [\"line 1\", \"line 2\"], \"categoryLevel1\": \"Technical | Non-technical\", \"categoryLevel2\": \"...\", \"categoryLevel3\": \"...\", \"headings\": [], \"urls\": [], \"codeOrCommands\": [], \"evidenceNotes\": []}"
    private val _systemPrompt = MutableStateFlow(repo.prefs.getString("llm_system_prompt", defaultSystemPrompt) ?: defaultSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val connectionRequestSequence = java.util.concurrent.atomic.AtomicLong()
    private val modelRequestSequence = java.util.concurrent.atomic.AtomicLong()
    private val _modelDiscoveryMessage = MutableStateFlow<String?>(null)
    val modelDiscoveryMessage: StateFlow<String?> = _modelDiscoveryMessage.asStateFlow()
    fun modelEndpointKey(url: String): String = url.trim().trimEnd('/')
        .removeSuffix("/api/generate").removeSuffix("/chat/completions")

    private val _availableModels = MutableStateFlow(emptyList<String>())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()
    private val _availableModelEndpoint = MutableStateFlow("")
    val availableModelEndpoint: StateFlow<String> = _availableModelEndpoint.asStateFlow()
    private val _availableModelProvider = MutableStateFlow("")
    val availableModelProvider: StateFlow<String> = _availableModelProvider.asStateFlow()

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
        try {
            MediaFolderScope.fromTreeUri(uri)
        } catch (error: FolderScopeException) {
            _operationMessage.value = error.message
            return
        }
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
        val available = list.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }
        val filtered = when (filter) {
            "New" -> available.filter { it.status == "new" }
            "Queued" -> available.filter { it.status == "queued" }
            "Processed" -> available.filter { it.status == "processed" }
            "Error" -> available.filter { it.status == "error" }
            "Video Archive" -> available.filter { it.status == "archived" && it.mimeType?.startsWith("video/") == true }
            "Image Archive" -> available.filter { it.status == "archived" && it.mimeType?.startsWith("image/") == true }
            "Archived" -> available.filter { it.status == "archived" }
            "Ready to delete" -> available.filter { it.status == "ready_to_delete" }
            else -> available
        }
        when (sort) {
            "Name" -> filtered.sortedBy { it.displayName.lowercase() }
            "Status" -> filtered.sortedBy { it.status }
            "Size" -> filtered.sortedBy { it.sizeBytes ?: 0L }
            else -> filtered
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _startupReady = MutableStateFlow(false)
    val startupReady: StateFlow<Boolean> = _startupReady.asStateFlow()

    init {
        refreshMediaAccess()
        viewModelScope.launch {
            val interrupted = repo.videoDao.requeueInterruptedProcessing()
            _startupReady.value = true
            if (interrupted > 0) _operationMessage.value = "Interrupted work is ready to retry. Nothing was deleted."
            val s = repo.getOrInitializeSettings()
            if (s.ollamaEnabled) {
                testOllamaAvailability(s.ollamaEndpointUrl)
            } else {
                _ollamaStatus.value = "Disabled"
            }
        }
        setupContentObservers()
        if (_autoIngestEnabled.value) viewModelScope.launch(Dispatchers.IO) { checkForNewMedia() }
    }

    fun refreshMediaAccess() {
        _mediaAccessState.value = repo.getMediaAccessState()
    }

    fun selectVideo(id: Long?) {
        _selectedVideoId.value = id
    }

    fun framesForVideo(id: Long) = repo.frameDao.getFramesForVideo(id)

    private val _renameAuthorization = MutableStateFlow<IntentSender?>(null)
    val renameAuthorization = _renameAuthorization.asStateFlow()
    private var pendingRename: Pair<Long, String>? = null
    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage = _operationMessage.asStateFlow()

    fun clearOperationMessage() { _operationMessage.value = null }

    fun consumeRenameAuthorization(): IntentSender? {
        val sender = _renameAuthorization.value
        _renameAuthorization.value = null
        return sender
    }

    fun finishRename(confirmed: Boolean) {
        val pending = pendingRename
        pendingRename = null
        _renameAuthorization.value = null
        if (confirmed && pending != null) renameVideoFile(pending.first, pending.second, false)
    }

    fun reconcileStorageAction(videoId: Long, trash: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val video = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
        try {
            val uri = Uri.parse(video.uriString)
            val columns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED)
            } else arrayOf(MediaStore.MediaColumns._ID)
            val state = storageOperations.query(uri, columns)?.use {
                if (!it.moveToFirst()) "absent"
                else if (trash && it.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) >= 0 &&
                    it.getInt(it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)) == 1) "trashed"
                else "present"
            } ?: "unknown"
            if (state == "absent" || state == "trashed") {
                repo.videoDao.updateVideo(video.copy(isCurrentlyAvailable = false,
                    isTrashed = state == "trashed", trashedAt = if (trash) System.currentTimeMillis() else video.trashedAt))
                _operationMessage.value = if (trash) "Media moved to Android trash." else "Media deleted from device storage."
            } else _operationMessage.value = "Storage action could not be verified. Scan to refresh."
        } catch (_: Exception) {
            _operationMessage.value = "Storage action could not be verified. Scan to refresh."
        }
    }

    fun scanVideos() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            try {
                _lastScanResult.value = repo.scanLocalVideos()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: FolderScopeException) {
                _lastScanResult.value = ScanRun(status = "error", finishedAt = System.currentTimeMillis(), errorMessage = error.message)
                _operationMessage.value = error.message
            } catch (_: Exception) {
                _lastScanResult.value = ScanRun(status = "error", finishedAt = System.currentTimeMillis(),
                    errorMessage = "Media scan failed. Check photo and video permissions and retry.")
                _operationMessage.value = "Media scan failed. Check photo and video permissions and retry."
            } finally {
                _isScanning.value = false
                refreshMediaAccess()
            }
        }
    }

    fun endpointNeedsConsent(url: String): Boolean = repo.validateEndpoint(url)["isPrivate"] != true

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
                requestTimeoutSeconds = timeout.coerceIn(5, 300),
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
        checkConnection(testUrl = urlStr)
    }

    fun selectFilter(filterName: String) {
        _selectedFilter.value = filterName
    }

    // Individual and batch actions
    fun enqueueVideos(ids: List<Long>) {
        viewModelScope.launch {
            try {
                var added = 0
                for (chunk in ids.distinct().chunked(500)) added += repo.videoDao.enqueueEligibleVideos(chunk)
                _operationMessage.value = if (added == 0) "No new kept files to add from these folders." else "Added $added files to Backlog queue!"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _operationMessage.value = "Could not add files to the queue. Try again."
            }
        }
    }

    fun toggleQueueVideo(videoId: Long) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            if (!MediaAnalysisPolicy.canAnalyze(v)) return@launch
            val nextStatus = if (v.status == "queued") "new" else "queued"
            repo.videoDao.updateVideo(v.copy(status = nextStatus))
        }
    }

    fun processSingle(video: VideoItem): Boolean {
        if (!MediaAnalysisPolicy.canAnalyze(video)) return false
        processor.processSingleVideo(video)
        return true
    }

    fun startBatch() {
        processor.startBatch(MediaAnalysisPolicy.eligible(allVideos.value))
    }

    fun pauseBatch() {
        processor.pauseBatch()
    }

    fun resumeBatch() {
        processor.resumeBatch(MediaAnalysisPolicy.eligible(allVideos.value))
    }

    fun selectSourceCategory(category: String?) {
        _selectedSourceCategory.value = category
    }

    fun setReviewDecision(videoId: Long, decision: String) {
        require(decision in setOf("unreviewed", "keep", "review_later", "ready_to_delete"))
        viewModelScope.launch {
            repo.videoDao.updateReviewDecision(videoId, decision)
            if (decision != "keep") {
                repo.videoDao.dequeueIfNotKept(videoId)
            }
        }
    }

    fun saveRule(existing: MediaRule?, name: String, kind: String, threshold: Double?) {
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > 40) {
            _ruleMessage.value = "Name invalid"
            return
        }
        val json = when (kind) {
            "exact_duplicate" -> MediaRuleSchema.duplicateJson()
            "obscured" -> threshold?.let(MediaRuleSchema::obscuredJson)
            else -> null
        }
        val definition = json?.let { MediaRuleSchema.parse(it).getOrNull() }
        if (json == null || definition == null) {
            _ruleMessage.value = "Rule invalid"
            return
        }
        viewModelScope.launch {
            if (existing == null) repo.mediaRuleDao.create(cleanName, kind, json)
            else repo.mediaRuleDao.revise(existing, cleanName, json)
            _ruleMessage.value = "Rule saved"
        }
    }

    fun setRuleEnabled(rule: MediaRule, enabled: Boolean) = viewModelScope.launch {
        repo.mediaRuleDao.updateRule(rule.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    fun deleteRule(rule: MediaRule) = viewModelScope.launch { repo.mediaRuleDao.deleteRule(rule.id) }

    fun previewRule(rule: MediaRule) = viewModelScope.launch(Dispatchers.Default) {
        val revision = repo.mediaRuleDao.revision(rule.id, rule.currentRevision)
        val definition = revision?.let { MediaRuleSchema.parse(it.definitionJson).getOrNull() }
        if (definition == null) {
            _ruleMessage.value = "Rule invalid"
            return@launch
        }
        val preview = when (val predicate = definition.predicate) {
            MediaPredicate.ExactDuplicate -> MediaRuleEngine().preview(definition, allVideos.value)
            is MediaPredicate.Obscured -> {
                val matches = mutableListOf<VideoItem>()
                for (media in allVideos.value) {
                    if (media.mimeType?.startsWith("image/") == true && media.isCurrentlyAvailable && !media.isPending && !media.isTrashed) {
                        if (repo.evaluateObscuredImage(media, predicate.threshold)?.matched == true) matches += media
                    }
                }
                RulePreview(matches.map { it.id }, matches.size, matches.sumOf { it.sizeBytes ?: 0L })
            }
        }
        _ruleMessage.value = "${preview.itemCount} • ${formatBytes(preview.totalBytes)}"
    }

    fun runRule(rule: MediaRule) = viewModelScope.launch(Dispatchers.Default) {
        if (!rule.enabled) {
            _ruleMessage.value = "Rule off"
            return@launch
        }
        val revision = repo.mediaRuleDao.revision(rule.id, rule.currentRevision)
        val definition = revision?.let { MediaRuleSchema.parse(it.definitionJson).getOrNull() }
        if (definition == null) {
            _ruleMessage.value = "Rule invalid"
            return@launch
        }
        val runId = repo.ruleRunDao.insert(RuleRun(ruleId = rule.id, ruleName = rule.name, ruleRevision = rule.currentRevision, mode = "execute"))
        val evaluated: List<Triple<VideoItem, Double?, String>> = when (val predicate = definition.predicate) {
            MediaPredicate.ExactDuplicate -> {
                val byId = allVideos.value.associateBy { it.id }
                DuplicateEvaluator.groups(allVideos.value).flatMap { it.duplicateIds }.mapNotNull { byId[it] }.map { Triple(it, null, "exact_name_and_bytes") }
            }
            is MediaPredicate.Obscured -> buildList {
                for (media in allVideos.value) {
                    if (media.mimeType?.startsWith("image/") == true && media.isCurrentlyAvailable && !media.isPending && !media.isTrashed) {
                        val evidence = repo.evaluateObscuredImage(media, predicate.threshold)
                        if (evidence?.matched == true) add(Triple(media, evidence.score, evidence.evaluator))
                    }
                }
            }
        }
        val tagActions = definition.actions.filterIsInstance<MediaAction.AddTag>()
        for (tagAction in tagActions) {
            val tagId = repo.customTagDao.byName(tagAction.name)?.id ?: repo.customTagDao.insert(CustomTag(name = tagAction.name))
            evaluated.forEach { repo.customTagDao.assign(MediaTagCrossRef(it.first.id, tagId)) }
        }
        if (definition.actions.any { it is MediaAction.MoveToBin }) {
            evaluated.forEach { (media, _, _) ->
                repo.binDao.add(BinEntry(media.id, media.reviewDecision, runId))
                repo.videoDao.updateReviewDecision(media.id, "ready_to_delete")
            }
        }
        if (evaluated.isNotEmpty()) repo.ruleRunDao.insertMatches(evaluated.map { (media, score, evidence) -> RuleMatch(runId, media.id, score, evidence, evidence) })
        repo.ruleRunDao.update(RuleRun(id = runId, ruleId = rule.id, ruleName = rule.name, ruleRevision = rule.currentRevision, mode = "execute", finishedAt = System.currentTimeMillis(), status = "finished", matchCount = evaluated.size, matchBytes = evaluated.sumOf { it.first.sizeBytes ?: 0L }))
        _ruleMessage.value = "${evaluated.size} in Bin"
    }

    fun addTag(name: String) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 40) _ruleMessage.value = "Name invalid"
        else if (repo.customTagDao.byName(clean) == null) repo.customTagDao.insert(CustomTag(name = clean))
        else _ruleMessage.value = "Name already exists"
    }

    fun renameTag(tag: CustomTag, name: String) = viewModelScope.launch {
        val clean = name.trim()
        val existing = repo.customTagDao.byName(clean)
        when {
            clean.length !in 1..40 -> _ruleMessage.value = "Name must contain 1–40 characters"
            existing != null && existing.id != tag.id -> _ruleMessage.value = "Name already exists"
            else -> repo.customTagDao.update(tag.copy(name = clean))
        }
    }

    fun deleteTag(tag: CustomTag) = viewModelScope.launch { repo.customTagDao.delete(tag) }
    fun assignTag(mediaId: Long, tagId: Long) = viewModelScope.launch { repo.customTagDao.assign(MediaTagCrossRef(mediaId, tagId)) }
    fun unassignTag(mediaId: Long, tagId: Long) = viewModelScope.launch { repo.customTagDao.unassign(MediaTagCrossRef(mediaId, tagId)) }

    fun recoverBin(mediaId: Long) = viewModelScope.launch { binCoordinator.recover(mediaId) }

    fun createBinDeletePendingIntent(): PendingIntent? {
        val candidates = binMedia.value.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }
        pendingBinDeleteIds = candidates.map { it.id }
        return runCatching { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || candidates.isEmpty()) null else storageOperations.request(false, candidates.map { Uri.parse(it.uriString) }.distinct()) }
            .onFailure {
                pendingBinDeleteIds = emptyList()
                _ruleMessage.value = "Delete failed"
            }
            .getOrNull()
    }

    fun finishBinDelete(confirmed: Boolean) {
        val ids = pendingBinDeleteIds
        pendingBinDeleteIds = emptyList()
        if (!DeletionResultPolicy.shouldReconcile(confirmed, ids)) return
        viewModelScope.launch(Dispatchers.IO) {
            val removed = binCoordinator.reconcile(ids)
            _ruleMessage.value = "${removed.size} deleted"
        }
    }

    suspend fun loadThumbnail(uriString: String) = repo.loadMediaThumbnail(uriString)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun saveEdits(videoId: Long, manualNotes: String, title1In: String, title2In: String, cat1In: String, cat2In: String, cat3In: String, roadmapIn: String) {
        viewModelScope.launch {
            val video = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            repo.videoDao.updateManualNotes(videoId, manualNotes)

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
            return storageOperations.request(true, listOf(uri))
        }
        return null
    }

    fun createDeletePendingIntent(videoId: Long): PendingIntent? {
        val video = allVideos.value.find { it.id == videoId } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uri = Uri.parse(video.uriString)
            return storageOperations.request(false, listOf(uri))
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
        require(provider in setOf("ollama_local", "ollama_cloud", "local")) { "Unsupported provider" }
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
            if (apiKeyVal.isNotBlank()) {
                repo.securePrefs.edit().putString("llm_api_key", apiKeyVal).apply()
                _hasApiKey.value = true
            }
            _llmProvider.value = provider
            _llmTemperature.value = temp
            _systemPrompt.value = prompt
            if (provider != previousProvider) {
                connectionRequestSequence.incrementAndGet()
                modelRequestSequence.incrementAndGet()
                _availableModels.value = emptyList()
                _connectionSuccess.value = null
            }
        }
    }

    fun checkConnection(testUrl: String? = null, testProvider: String? = null, testApiKey: String? = null) {
        val sequence = connectionRequestSequence.incrementAndGet()
        viewModelScope.launch {
            _connectionSuccess.value = null

            val provider = testProvider ?: repo.prefs.getString("llm_provider", "") ?: "ollama_local"
            if (provider == "local") {
                _connectionSuccess.value = true
                _ollamaStatus.value = "Local rules"
                return@launch
            }
            val s = repo.getOrInitializeSettings()
            if (provider !in setOf("ollama_local", "ollama_cloud") ||
                provider == "ollama_cloud" && !CloudCredentialPolicy.isTrustedEndpoint(testUrl ?: s.ollamaEndpointUrl)) {
                _connectionSuccess.value = false
                _ollamaStatus.value = "Unavailable"
                _operationMessage.value = "Use the official HTTPS Cloud address."
                return@launch
            }
            var hostUrl = (testUrl ?: s.ollamaEndpointUrl).trim()
            if (hostUrl.endsWith("/api/generate")) hostUrl = hostUrl.removeSuffix("/api/generate")
            if (hostUrl.endsWith("/api/generate/")) hostUrl = hostUrl.removeSuffix("/api/generate/")
            if (hostUrl.endsWith("/v1/chat/completions")) hostUrl = hostUrl.removeSuffix("/chat/completions")
            if (hostUrl.endsWith("/v1/chat/completions/")) hostUrl = hostUrl.removeSuffix("/chat/completions/")
            if (hostUrl.endsWith("/")) hostUrl = hostUrl.removeSuffix("/")
            
            val testEndpoint = if (hostUrl.endsWith("/v1")) "$hostUrl/models" else if (provider == "ollama_cloud") "$hostUrl/api/tags" else hostUrl
            
            val apiKey = if (provider == "ollama_cloud") testApiKey?.takeIf { it.isNotBlank() } ?: repo.securePrefs.getString("llm_api_key", "").orEmpty() else ""
            if (provider == "ollama_cloud" && apiKey.isBlank()) {
                _connectionSuccess.value = false
                _ollamaStatus.value = "Unavailable"
                _operationMessage.value = "Enter your Cloud key in the hidden field."
                return@launch
            }

            withContext(Dispatchers.IO) {
                try {
                    val timeout = if (provider == "ollama_cloud") 15L else 4L
                    val client = connectionClient ?: okhttp3.OkHttpClient.Builder()
                        .followRedirects(provider != "ollama_cloud")
                        .followSslRedirects(provider != "ollama_cloud")
                        .connectTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    
                    val reqBuilder = okhttp3.Request.Builder().url(testEndpoint)
                    if (provider == "ollama_cloud" && apiKey.isNotBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }
                    val req = reqBuilder.build()
                    
                    client.newCall(req).execute().use { response ->
                        if (sequence != connectionRequestSequence.get()) return@withContext
                        val code = response.code
                        val success = response.isSuccessful || provider == "ollama_local" && code in setOf(404, 400, 405)
                        _connectionSuccess.value = success
                        if (success) {
                            _ollamaStatus.value = "Available"
                            syncModels(testUrl = hostUrl, testProvider = provider, testApiKey = apiKey)
                        } else {
                            _ollamaStatus.value = "Unavailable"
                        }
                    }
                } catch (e: Exception) {
                    if (sequence != connectionRequestSequence.get()) return@withContext
                    _connectionSuccess.value = false
                    _ollamaStatus.value = "Unavailable"
                }
            }
        }
    }

    fun syncModels(testUrl: String? = null, testProvider: String? = null, testApiKey: String? = null) {
        val sequence = modelRequestSequence.incrementAndGet()
        viewModelScope.launch {
            val provider = testProvider ?: repo.prefs.getString("llm_provider", "") ?: "ollama_local"
            _modelDiscoveryMessage.value = null

            if (provider == "local") {
                val localModels = listOf("keyword-fallback")
                _availableModelEndpoint.value = ""
                _availableModelProvider.value = provider
                _availableModels.value = localModels
                repo.prefs.edit().putStringSet("llm_available_models", localModels.toSet()).apply()
                return@launch
            }

            val s = repo.getOrInitializeSettings()
            if (sequence != modelRequestSequence.get()) return@launch
            val endpointKey = modelEndpointKey(testUrl ?: s.ollamaEndpointUrl)
            if (_availableModelProvider.value != provider || _availableModelEndpoint.value != endpointKey) _availableModels.value = emptyList()
            if (provider !in setOf("ollama_local", "ollama_cloud") ||
                provider == "ollama_cloud" && !CloudCredentialPolicy.isTrustedEndpoint(testUrl ?: s.ollamaEndpointUrl)) {
                _availableModels.value = emptyList()
                _operationMessage.value = "Use the official HTTPS Cloud address."
                return@launch
            }
            var baseUrl = (testUrl ?: s.ollamaEndpointUrl).trim()
            if (baseUrl.endsWith("/api/generate")) baseUrl = baseUrl.removeSuffix("/api/generate")
            if (baseUrl.endsWith("/api/generate/")) baseUrl = baseUrl.removeSuffix("/api/generate/")
            if (baseUrl.endsWith("/v1/chat/completions")) baseUrl = baseUrl.removeSuffix("/chat/completions")
            if (baseUrl.endsWith("/v1/chat/completions/")) baseUrl = baseUrl.removeSuffix("/chat/completions/")
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.removeSuffix("/")
            
            val isV1 = baseUrl.endsWith("/v1")
            val tagsUrl = if (isV1) "$baseUrl/models" else "$baseUrl/api/tags"
            val apiKey = if (provider == "ollama_cloud") testApiKey?.takeIf { it.isNotBlank() } ?: repo.securePrefs.getString("llm_api_key", "").orEmpty() else ""
            if (provider == "ollama_cloud" && apiKey.isBlank()) {
                _operationMessage.value = "Enter your Cloud key in the hidden field."
                return@launch
            }

            withContext(Dispatchers.IO) {
                try {
                    val timeout = if (provider == "ollama_cloud") 15L else 4L
                    val client = connectionClient ?: okhttp3.OkHttpClient.Builder()
                        .followRedirects(provider != "ollama_cloud")
                        .followSslRedirects(provider != "ollama_cloud")
                        .connectTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                        
                    val reqBuilder = okhttp3.Request.Builder().url(tagsUrl)
                    if (provider == "ollama_cloud" && apiKey.isNotBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }
                    val req = reqBuilder.build()
                    
                    client.newCall(req).execute().use { response ->
                        if (sequence != modelRequestSequence.get()) return@withContext
                        if (response.isSuccessful) {
                            val respBody = response.body?.string() ?: ""
                            val json = org.json.JSONObject(respBody)
                            val modelNames = mutableListOf<String>()
                            
                            if (isV1) {
                                val dataArray = json.optJSONArray("data")
                                if (dataArray != null) {
                                    for (i in 0 until dataArray.length()) {
                                        val modelObj = dataArray.getJSONObject(i)
                                        val name = modelObj.optString("id")
                                        if (name.isNotEmpty()) modelNames.add(name)
                                    }
                                }
                            } else {
                                val modelsArray = json.optJSONArray("models")
                                if (modelsArray != null) {
                                    for (i in 0 until modelsArray.length()) {
                                        val modelObj = modelsArray.getJSONObject(i)
                                        val name = modelObj.optString("name")
                                        if (name.isNotEmpty()) modelNames.add(name)
                                    }
                                }
                            }
                            
                            if (sequence != modelRequestSequence.get()) return@withContext
                            _availableModelEndpoint.value = endpointKey
                            _availableModelProvider.value = provider
                            _availableModels.value = modelNames.distinct()
                            _modelDiscoveryMessage.value = if (modelNames.isEmpty()) "No models returned by this service." else null
                            repo.prefs.edit().putStringSet("llm_available_models", modelNames.toSet()).apply()
                        } else {
                            _modelDiscoveryMessage.value = "Model list could not be refreshed. Previous choices were kept."
                        }
                    }
                } catch (e: Exception) {
                    // Fail gracefully, keep the existing models list
                    if (sequence == modelRequestSequence.get()) _modelDiscoveryMessage.value = "Model list could not be refreshed. Previous choices were kept."
                }
            }
        }
    }

    fun restoreArchivedVideo(videoId: Long) {
        viewModelScope.launch {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            val hasResult = repo.analysisDao.getAnalysisForVideoDirect(videoId) != null
            repo.videoDao.updateVideo(v.copy(
                status = if (hasResult) "processed" else "new",
                archivedAt = null,
                processedAt = if (hasResult) v.processedAt ?: System.currentTimeMillis() else null,
                lastCompletedStep = if (hasResult) "result_saved" else "discovered"
            ))
        }
    }

    fun renameVideoFile(videoId: Long, newNameNoExt: String, allowAuthorization: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val v = repo.videoDao.getVideoByIdDirect(videoId) ?: return@launch
            if (newNameNoExt.isBlank() || newNameNoExt.any { it == '/' || it == '\\' || it.code < 32 }) {
                _operationMessage.value = "Enter a valid file name."
                return@launch
            }
            val originalName = v.displayName
            val ext = originalName.substringAfterLast(".", "")
            val finalName = if (ext.isNotEmpty()) "$newNameNoExt.$ext" else newNameNoExt
            val uri = Uri.parse(v.uriString)
            try {
                val changed = application.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                }, null, null)
                if (changed > 0) {
                    repo.videoDao.updateVideo(v.copy(displayName = finalName))
                    _operationMessage.value = "File renamed to $finalName"
                } else _operationMessage.value = "File rename failed; the original name was kept."
            } catch (e: SecurityException) {
                if (allowAuthorization) {
                    val sender = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
                            MediaStore.createWriteRequest(application.contentResolver, listOf(uri)).intentSender
                        }.getOrNull()
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                        else -> null
                    }
                    if (sender != null) {
                        pendingRename = videoId to newNameNoExt
                        _renameAuthorization.value = sender
                    } else _operationMessage.value = "Android did not allow this file to be renamed."
                } else _operationMessage.value = "Android did not allow this file to be renamed."
            } catch (_: Exception) {
                _operationMessage.value = "File rename failed; the original name was kept."
            }
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
            onResult(false)
        }
    }

    fun exportMetadata(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val success = withContext(Dispatchers.IO) {
            runCatching {
                val items = org.json.JSONArray()
                for (video in repo.videoDao.getAllVideosDirect()) {
                    val analysis = repo.analysisDao.getAnalysisForVideoDirect(video.id)
                    items.put(org.json.JSONObject().apply {
                        put("name", video.displayName)
                        put("uri", video.uriString)
                        put("status", video.status)
                        put("reviewDecision", video.reviewDecision)
                        put("notes", video.manualTranscript ?: "")
                        put("summary", analysis?.descriptionLine1 ?: "")
                        put("details", analysis?.descriptionLine2 ?: "")
                        put("roadmap", analysis?.formattedActionPlan ?: "")
                        put("category", listOfNotNull(analysis?.categoryLevel1, analysis?.categoryLevel2, analysis?.categoryLevel3).joinToString(" / "))
                    })
                }
                val document = org.json.JSONObject().put("schema", 1).put("media", items)
                val stream = requireNotNull(application.contentResolver.openOutputStream(uri, "wt"))
                stream.bufferedWriter().use { it.write(document.toString(2)) }
            }.isSuccess
        }
        onResult(success)
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
        repo.scanLocalVideos(automatic = true, screenshots = _autoIngestScreenshots.value, recordings = _autoIngestRecordings.value)
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
