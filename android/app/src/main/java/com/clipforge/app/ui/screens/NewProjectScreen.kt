package com.clipforge.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.core.ProcessingSettings
import com.clipforge.app.media.CancelledException
import com.clipforge.app.ui.Danger
import com.clipforge.app.ui.Muted
import com.clipforge.app.ui.Nav
import com.clipforge.app.ui.Screen
import com.clipforge.app.ui.SectionCard
import com.clipforge.app.ui.SettingsForm
import com.clipforge.app.ui.fmtBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private data class Picked(val uri: Uri, val name: String, val size: Long, val mime: String?)

@Composable
fun NewProjectScreen(app: ClipforgeApp, nav: Nav, sharedVideo: Uri?, onSharedVideoConsumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Picked?>(null) }
    var name by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf<ProcessingSettings?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0.0) }
    var error by remember { mutableStateOf<String?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        settings = withContext(Dispatchers.IO) { app.engine.processingDefaults() }
    }

    fun select(uri: Uri) {
        error = null
        scope.launch {
            val p = withContext(Dispatchers.IO) {
                var n = "video"
                var size = -1L
                try {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            if (!c.isNull(0)) n = c.getString(0)
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                } catch (_: Exception) {}
                Picked(uri, n, size, try { context.contentResolver.getType(uri) } catch (_: Exception) { null })
            }
            if (p.mime != null && !p.mime.startsWith("video/")) {
                error = "That file is not a video (${p.mime})."
                return@launch
            }
            picked = p
            if (name.isBlank()) name = p.name.substringBeforeLast('.')
        }
    }

    LaunchedEffect(sharedVideo) {
        if (sharedVideo != null) {
            select(sharedVideo)
            onSharedVideoConsumed()
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) select(uri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) select(uri)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("screen_new"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "1. Choose a video") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !importing,
                    onClick = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                ) { Text("From gallery") }
                OutlinedButton(enabled = !importing, onClick = { pickFile.launch(arrayOf("video/*")) }) {
                    Text("Browse files")
                }
            }
            val p = picked
            Spacer(Modifier.height(8.dp))
            if (p == null) {
                Text("MP4, MOV, MKV, WebM or 3GP. HEVC phone recordings are fine.", color = Muted, fontSize = 13.sp)
            } else {
                Text(p.name, fontWeight = FontWeight.Medium)
                Text(
                    (if (p.size >= 0) fmtBytes(p.size) else "size unknown") + (p.mime?.let { " · $it" } ?: ""),
                    color = Muted, fontSize = 13.sp,
                )
                Text("The file is checked by content when you start.", color = Muted, fontSize = 12.sp)
            }
        }

        SectionCard(title = "2. Name") {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(120) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("new_name"), placeholder = { Text("Project name") },
            )
        }

        val s = settings
        if (s != null) {
            SectionCard(title = "3. Settings for this project") {
                SettingsForm(s) { settings = it }
            }
        }

        error?.let { Text(it, color = Danger) }

        if (importing) {
            SectionCard {
                Text("Copying and hashing the video…")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { importProgress.toFloat() }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { cancelFlag.set(true) }) { Text("Cancel") }
            }
        } else {
            Button(
                modifier = Modifier.fillMaxWidth().testTag("new_start"),
                enabled = picked != null && s != null && s.validate().isEmpty(),
                onClick = {
                    val p = picked ?: return@Button
                    val chosen = settings ?: return@Button
                    importing = true
                    importProgress = 0.0
                    error = null
                    cancelFlag.set(false)
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                app.engine.importVideo(p.uri, name, chosen, { cancelFlag.get() }) { f ->
                                    importProgress = f
                                }
                            }
                            picked = null
                            name = ""
                            nav.tab(Screen.Queue)
                        } catch (e: CancelledException) {
                            error = "Import cancelled."
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            importing = false
                        }
                    }
                },
            ) { Text("Start processing") }
        }
        Text(
            "Everything runs on this device. The video is copied into the app's private storage, " +
                "hashed (SHA-256) and processed in the background; you can leave the app.",
            color = Muted, fontSize = 12.sp,
        )
    }
}
