package com.example.data

data class RulePreview(val mediaIds: List<Long>, val itemCount: Int, val totalBytes: Long)
data class DuplicateGroup(val survivorId: Long, val duplicateIds: List<Long>)

object DuplicateEvaluator {
    fun groups(media: List<VideoItem>): List<DuplicateGroup> = media
        .asSequence()
        .filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed && it.displayName.isNotEmpty() && it.sizeBytes != null }
        .groupBy { it.displayName to it.sizeBytes }
        .values
        .filter { it.size > 1 }
        .map { candidates ->
            val ordered = candidates.sortedWith(compareBy<VideoItem>({ it.dateAdded ?: Long.MAX_VALUE }, { it.mediaStoreId }))
            DuplicateGroup(ordered.first().id, ordered.drop(1).map { it.id })
        }
        .sortedBy { it.survivorId }
        .toList()
}

class MediaRuleEngine {
    fun preview(definition: MediaRuleDefinition, media: List<VideoItem>): RulePreview {
        val ids = when (definition.predicate) {
            MediaPredicate.ExactDuplicate -> DuplicateEvaluator.groups(media).flatMap { it.duplicateIds }
            is MediaPredicate.Obscured -> emptyList()
        }
        val byId = media.associateBy { it.id }
        return RulePreview(ids, ids.size, ids.sumOf { byId[it]?.sizeBytes ?: 0L })
    }
}

