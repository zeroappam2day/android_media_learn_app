package com.example.data

import java.util.Locale

data class MediaSourceClassification(
    val category: String?,
    val subcategory: String?,
    val categoryEvidenceKind: String?,
    val subcategoryEvidenceKind: String?,
    val categoryConfidence: String?,
    val subcategoryConfidence: String?,
    val ruleVersion: Int = MediaSourceClassifier.RULE_VERSION
)

object MediaSourceClassifier {
    const val RULE_VERSION = 1

    private val standardRoots = setOf("android", "media", "dcim", "pictures", "movies", "download", "downloads")

    fun classify(relativePath: String?, bucketDisplayName: String?, displayName: String): MediaSourceClassification {
        val pathSegments = relativePath
            ?.replace('\\', '/')
            ?.split('/')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val evidenceSegments = (pathSegments + listOfNotNull(bucketDisplayName))
            .map { it.lowercase(Locale.ROOT).replace('_', ' ').trim() }

        val category = knownCategory(evidenceSegments)
            ?: remainingFolderLabel(pathSegments, bucketDisplayName)
        val categoryEvidence = when {
            category == null -> null
            !relativePath.isNullOrBlank() -> "relative_path"
            !bucketDisplayName.isNullOrBlank() -> "bucket_display_name"
            else -> null
        }

        val subcategory = if (category == "Screenshots") capturedAppFromScreenshotName(displayName) else null

        return MediaSourceClassification(
            category = category,
            subcategory = subcategory,
            categoryEvidenceKind = categoryEvidence,
            subcategoryEvidenceKind = if (subcategory != null) "filename_token" else null,
            categoryConfidence = if (categoryEvidence != null) "DIRECT" else null,
            subcategoryConfidence = if (subcategory != null) "INFERRED" else null
        )
    }

    private fun knownCategory(segments: List<String>): String? = when {
        segments.any { it == "whatsapp business" || it.startsWith("whatsapp business ") } -> "WhatsApp Business"
        segments.any { it == "whatsapp" || it.startsWith("whatsapp ") } -> "WhatsApp"
        segments.any { it == "screen recordings" } -> "Screen recordings"
        segments.any { it == "screenshots" || it == "screenshot" } -> "Screenshots"
        segments.any { it == "camera" } -> "Camera"
        segments.any { it == "download" || it == "downloads" } -> "Downloads"
        segments.any { it == "messenger" } -> "Messenger"
        segments.any { it == "dji" || it.startsWith("dji ") } -> "DJI"
        segments.any { it == "facebook" } -> "Facebook"
        segments.any { it == "skype" } -> "Skype"
        segments.any { it == "zalo" } -> "Zalo"
        segments.any { it == "twitter" } -> "Twitter"
        else -> null
    }

    private fun remainingFolderLabel(pathSegments: List<String>, bucketDisplayName: String?): String? {
        val meaningful = pathSegments.firstOrNull {
            val normalized = it.lowercase(Locale.ROOT)
            normalized !in standardRoots && !normalized.matches(Regex("^(com|org|net)\\..+"))
        }
        return meaningful?.takeIf { it.isNotBlank() }
            ?: bucketDisplayName?.takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) !in standardRoots }
    }

    private fun capturedAppFromScreenshotName(displayName: String): String? {
        val tokens = displayName.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
        return when {
            "facebook" in tokens -> "Facebook"
            "instagram" in tokens -> "Instagram"
            else -> null
        }
    }
}
