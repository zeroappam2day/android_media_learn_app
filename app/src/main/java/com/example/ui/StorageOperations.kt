package com.example.ui

import android.app.PendingIntent
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

/** Platform operations boundary. Default calls retain the existing Android behavior. */
open class StorageOperations(private val resolver: ContentResolver) {
    open fun request(trash: Boolean, uris: List<Uri>): PendingIntent =
        if (trash) MediaStore.createTrashRequest(resolver, uris, true)
        else MediaStore.createDeleteRequest(resolver, uris)

    open fun query(uri: Uri, columns: Array<String>): Cursor? =
        resolver.query(uri, columns, null, null, null)
}
