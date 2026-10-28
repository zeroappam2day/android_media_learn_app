package com.example

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only proof for Android's confirmation boundary; it never reads or targets user media. */
class MediaDeleteEvidenceActivity : ComponentActivity() {
    private var testUri: Uri? = null
    private var status by mutableStateOf("Create")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                lifecycleScope.launch {
                    val exists = withContext(Dispatchers.IO) { testUri?.let(::exists) == true }
                    status = when (result.resultCode) {
                        Activity.RESULT_OK -> "OK absent=${!exists}"
                        else -> "Cancel kept=$exists"
                    }
                }
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Test file only")
                        Text(status)
                        Button(onClick = {
                            lifecycleScope.launch {
                                val uri = withContext(Dispatchers.IO) { createTestImage() }
                                testUri = uri
                                if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                                    status = "Create failed"
                                } else {
                                    status = "Request one"
                                    val pending = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                                    launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                }
                            }
                        }) { Text("Start") }
                        Button(onClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                testUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                                testUri = null
                                withContext(Dispatchers.Main) { status = "Clean" }
                            }
                        }) { Text("Clean") }
                    }
                }
            }
        }
    }

    private fun createTestImage(): Uri? {
        cleanupStale()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MediaRuleTest")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val wrote = runCatching {
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
        }.getOrDefault(false)
        bitmap.recycle()
        if (!wrote) {
            contentResolver.delete(uri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    private fun exists(uri: Uri): Boolean =
        contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.moveToFirst() } == true

    private fun cleanupStale() {
        contentResolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(FILE_NAME)
        )
    }

    companion object { private const val FILE_NAME = "codex_rules_delete_test.png" }
}
