package com.example.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoProcessor(private val repository: VideoRepository, private val externalScope: CoroutineScope) {
    
    enum class State { IDLE, PROCESSING, PAUSED, FINISHED }

    private val _processorState = MutableStateFlow(State.IDLE)
    val processorState: StateFlow<State> = _processorState.asStateFlow()

    private val _activeVideoId = MutableStateFlow<Long?>(null)
    val activeVideoId: StateFlow<Long?> = _activeVideoId.asStateFlow()

    private val _currentVideoProgress = MutableStateFlow(0)
    val currentVideoProgress: StateFlow<Int> = _currentVideoProgress.asStateFlow()

    private val _currentStepName = MutableStateFlow("")
    val currentStepName: StateFlow<String> = _currentStepName.asStateFlow()

    private var activeJob: Job? = null

    fun startBatch(videosToProcess: List<VideoItem>) {
        if (_processorState.value == State.PROCESSING || activeJob?.isCompleted == false) return
        _processorState.value = State.PROCESSING
        
        activeJob = externalScope.launch {
            try {
                val dbSettings = repository.getOrInitializeSettings()
                
                val targets = MediaAnalysisPolicy.eligible(videosToProcess).filter {
                    it.status == "new" || it.status == "queued" || it.status == "error"
                }

                if (targets.isEmpty()) {
                    _processorState.value = State.FINISHED
                    return@launch
                }

                for ((index, queuedVideo) in targets.withIndex()) {
                    if (_processorState.value != State.PROCESSING) break
                    val video = repository.videoDao.getVideoByIdDirect(queuedVideo.id) ?: continue
                    if (!MediaAnalysisPolicy.canAnalyze(video) || video.status !in setOf("new", "queued", "error")) continue
                    
                    _activeVideoId.value = video.id
                    _currentStepName.value = "Processing (${index + 1}/${targets.size}): ${video.displayName}"
                    _currentVideoProgress.value = 10

                    val itemToUpdate = video.copy(status = "processing", errorMessage = null)

                    try {
                        persistProcessingState(itemToUpdate)
                        _currentStepName.value = "Sampling frames..."
                        val stepFramesSampled = itemToUpdate.copy(lastCompletedStep = "frames_sampled")
                        persistProcessingState(stepFramesSampled)

                        val frames = repository.sampleFramesAndOCR(stepFramesSampled) { pct ->
                            _currentVideoProgress.value = 10 + (pct * 40 / 100) // maps to 10% - 50%
                        }

                        _currentStepName.value = "OCR analysis completed."
                        val stepOcrComplete = stepFramesSampled.copy(lastCompletedStep = "ocr_complete")
                        persistProcessingState(stepOcrComplete)

                        _currentStepName.value = "Synthesizing text with LLM/Fallback..."
                        _currentVideoProgress.value = 60
                        
                        // Mark attempting LLM
                        val stepLlmAttempted = stepOcrComplete.copy(lastCompletedStep = "llm_attempted")
                        persistProcessingState(stepLlmAttempted)

                        val analysisResult = repository.analyzeVideoKnowledge(
                            video = stepOcrComplete,
                            frames = frames,
                            userNotes = stepOcrComplete.manualTranscript,
                            settings = dbSettings
                        )

                        _currentVideoProgress.value = 85
                        _currentStepName.value = "Saving structured model analysis..."
                        
                        // If it used fallback, mark fallback generated
                        val llmSourceStep = if (analysisResult.source != "fallback") "llm_attempted" else "fallback_generated"
                        val stepLlmSource = stepOcrComplete.copy(lastCompletedStep = llmSourceStep)
                        persistProcessingState(stepLlmSource)

                        repository.analysisDao.insertAnalysis(analysisResult)

                        // Final Step: Result Saved successfully!
                        persistProcessingState(stepLlmSource.copy(
                            status = "processed",
                            processedAt = System.currentTimeMillis(),
                            lastCompletedStep = "result_saved"
                        ))

                        _currentVideoProgress.value = 100
                        delay(600)
                    } catch (ce: CancellationException) {
                        withContext(NonCancellable) {
                            val latest = repository.videoDao.getVideoByIdDirect(video.id)
                            if (latest?.status == "processing") {
                                persistProcessingState(latest.copy(status = "queued", errorMessage = null))
                            }
                        }
                        throw ce
                    } catch (e: Exception) {
                        val friendlyError = if (e.localizedMessage?.contains("timeout", ignoreCase = true) == true) {
                            "LLM timed out. Check Ollama host connection or increase timeout limit."
                        } else if (e.localizedMessage?.contains("failed to connect", ignoreCase = true) == true || e.localizedMessage?.contains("connection refused", ignoreCase = true) == true) {
                            "Could not connect to LLM. Verify network connection and Ollama host address."
                        } else {
                            "Processing interrupted. Try running the video again."
                        }
                        
                        persistProcessingState(itemToUpdate.copy(
                            status = "error",
                            errorMessage = friendlyError
                        ))
                        _currentStepName.value = friendlyError
                        delay(1200)
                    }
                }
                
                if (_processorState.value == State.PROCESSING) {
                    _processorState.value = State.FINISHED
                }
            } catch (ce: CancellationException) {
                _processorState.value = State.PAUSED
            } finally {
                _activeVideoId.value = null
                _currentStepName.value = ""
                _currentVideoProgress.value = 0
                if (_processorState.value != State.FINISHED && _processorState.value != State.PAUSED) {
                    _processorState.value = State.IDLE
                }
            }
        }
    }

    private suspend fun persistProcessingState(item: VideoItem) {
        repository.videoDao.updateProcessingState(item.id, item.status, item.errorMessage, item.processedAt, item.lastCompletedStep)
    }

    fun pauseBatch() {
        if (_processorState.value == State.PROCESSING) {
            _processorState.value = State.PAUSED
            activeJob?.cancel()
        }
    }

    fun resumeBatch(videosToProcess: List<VideoItem>) {
        startBatch(videosToProcess)
    }

    fun processSingleVideo(video: VideoItem) {
        startBatch(listOf(video))
    }
}
