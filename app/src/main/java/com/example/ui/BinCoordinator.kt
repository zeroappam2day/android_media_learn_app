package com.example.ui

import android.app.PendingIntent
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.data.BinDao
import com.example.data.VideoItemDao

object DeletionResultPolicy {
    fun shouldReconcile(confirmed: Boolean, candidateIds: List<Long>): Boolean = confirmed && candidateIds.isNotEmpty()
}

class BinCoordinator(
    private val resolver: ContentResolver,
    private val videoDao: VideoItemDao,
    private val binDao: BinDao,
    private val uriExists: ((Uri) -> Boolean)? = null
) {
    fun createDeleteRequest(uriStrings: List<String>): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = uriStrings.distinct().map(Uri::parse)
        if (uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(resolver, uris)
    }

    suspend fun recover(mediaId: Long) {
        val entry = binDao.entry(mediaId) ?: return
        val media = videoDao.getVideoByIdDirect(mediaId) ?: return
        if (!media.isCurrentlyAvailable || media.isPending || media.isTrashed) return
        videoDao.updateReviewDecision(mediaId, entry.priorReviewDecision)
        binDao.remove(mediaId)
    }

    /** Removes local rows only after the provider proves their URI is absent. */
    suspend fun reconcile(candidateIds: List<Long>): List<Long> {
        val absent = candidateIds.mapNotNull { id ->
            val media = videoDao.getVideoByIdDirect(id) ?: return@mapNotNull id
            if (exists(Uri.parse(media.uriString))) null else id
        }
        absent.forEach { videoDao.markUnavailable(it) }
        if (absent.isNotEmpty()) binDao.removeAll(absent)
        return absent
    }

    private fun exists(uri: Uri): Boolean {
        uriExists?.let { return it(uri) }
        return try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
        } catch (_: Exception) {
            true
        }
    }
}
