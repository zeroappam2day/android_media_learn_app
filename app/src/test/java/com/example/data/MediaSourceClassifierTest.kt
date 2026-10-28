package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSourceClassifierTest {
    @Test
    fun whatsappMediaTypeDoesNotBecomeSubcategory() {
        val result = MediaSourceClassifier.classify(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
            "WhatsApp Images",
            "IMG-20260806-WA0001.jpg"
        )

        assertEquals("WhatsApp", result.category)
        assertNull(result.subcategory)
        assertEquals("DIRECT", result.categoryConfidence)
    }

    @Test
    fun screenshotExactFilenameTokenProducesInferredCapturedApp() {
        val result = MediaSourceClassifier.classify(
            "DCIM/Screenshots/",
            "Screenshots",
            "Screenshot_20260806_facebook_post.png"
        )

        assertEquals("Screenshots", result.category)
        assertEquals("Facebook", result.subcategory)
        assertEquals("filename_token", result.subcategoryEvidenceKind)
        assertEquals("INFERRED", result.subcategoryConfidence)
    }

    @Test
    fun substringInsideAnotherWordDoesNotInferCapturedApp() {
        val result = MediaSourceClassifier.classify(
            "DCIM/Screenshots/",
            "Screenshots",
            "Screenshot_myfacebookarchive.png"
        )

        assertNull(result.subcategory)
    }

    @Test
    fun unrecognizedNestedFolderUsesFolderLabelWithoutAppAttribution() {
        val result = MediaSourceClassifier.classify(
            "Pictures/Hik-Connect/thumbnail/",
            "thumbnail",
            "image.jpg"
        )

        assertEquals("Hik-Connect", result.category)
        assertEquals("relative_path", result.categoryEvidenceKind)
    }

    @Test
    fun missingFolderEvidenceLeavesCategoryNullable() {
        val result = MediaSourceClassifier.classify("/", null, "image.jpg")

        assertNull(result.category)
        assertNull(result.categoryEvidenceKind)
    }

    @Test
    fun looseDownloadSubstringDoesNotCreateFalseDownloadsCategory() {
        val result = MediaSourceClassifier.classify("Pictures/DownloaderCache/", "DownloaderCache", "image.jpg")

        assertEquals("DownloaderCache", result.category)
    }
}
