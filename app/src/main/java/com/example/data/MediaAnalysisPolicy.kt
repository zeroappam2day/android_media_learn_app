package com.example.data

object MediaAnalysisPolicy {
    fun canAnalyze(reviewDecision: String): Boolean = reviewDecision == "keep"

    fun canAnalyze(item: VideoItem): Boolean =
        canAnalyze(item.reviewDecision) && item.isCurrentlyAvailable && !item.isPending && !item.isTrashed

    fun eligible(items: Iterable<VideoItem>): List<VideoItem> =
        items.filter { canAnalyze(it) }

    fun emitEligibleIdsInAttemptOrder(items: Iterable<VideoItem>, emit: (Long) -> Unit) {
        for (item in items) if (canAnalyze(item)) emit(item.id)
    }
}
