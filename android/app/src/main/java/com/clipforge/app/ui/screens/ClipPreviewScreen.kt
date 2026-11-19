package com.clipforge.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.data.ClipRow
import com.clipforge.app.data.JobRow
import com.clipforge.app.ui.KeyValue
import com.clipforge.app.ui.Nav
import com.clipforge.app.ui.Screen
import com.clipforge.app.ui.SectionCard
import com.clipforge.app.ui.fmtBytes
import com.clipforge.app.ui.fmtDate
import com.clipforge.app.ui.fmtDuration
import com.clipforge.app.ui.fmtTime
import com.clipforge.app.ui.rememberQuery
import com.clipforge.app.ui.shareFile
import com.clipforge.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PreviewData(val clip: ClipRow, val job: JobRow?, val siblings: List<ClipRow>)

@Composable
fun ClipPreviewScreen(app: ClipforgeApp, nav: Nav, clipId: String) {
    val engine = app.engine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by rememberQuery(engine, null as PreviewData?, clipId) {
        engine.db.getClip(clipId)?.let { c -> PreviewData(c, engine.db.getJob(c.jobId), engine.db.listClips(c.jobId)) }
    }
    val d = data ?: return
    val c = d.clip
    val file = remember(c.relpath) { engine.resolve(c.relpath) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("screen_preview"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VideoPlayer(Uri.fromFile(file), c.width.toFloat() / c.height.coerceAtLeast(1))

        val pos = d.siblings.indexOfFirst { it.id == c.id }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = pos > 0, onClick = { nav.replace(Screen.Preview(d.siblings[pos - 1].id)) }) {
                Text("Previous")
            }
            OutlinedButton(enabled = pos >= 0 && pos < d.siblings.size - 1,
                onClick = { nav.replace(Screen.Preview(d.siblings[pos + 1].id)) }) { Text("Next") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { shareFile(context, file, "video/mp4", "Share clip") }) { Text("Share") }
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.exporter.saveVideoToGallery(file, "${safe(d.job?.name)}_${c.filename}")
                        }
                        toast(context, "Saved to Movies/CLIPFORGE")
                    } catch (e: Exception) {
                        toast(context, e.message ?: "Could not save")
                    }
                }
            }) { Text("Save to gallery") }
        }

        SectionCard(title = "Clip ${c.idx} of ${d.siblings.size}") {
            KeyValue("Project", d.job?.name ?: "")
            KeyValue("Source range", "${fmtTime(c.start)} → ${fmtTime(c.end)}")
            KeyValue("Duration", fmtDuration(c.duration))
            KeyValue("Cut at start", c.startKind)
            KeyValue("Cut at end", c.endKind)
            KeyValue("Size", "${c.width}×${c.height} · ${fmtBytes(c.sizeBytes)}")
            KeyValue("Audio", if (c.hasAudio) "AAC" else "none (source has no audio)")
            KeyValue("Created", fmtDate(c.createdAt))
            KeyValue("Asset ID", c.id, mono = true)
            KeyValue("SHA-256", c.sha256, mono = true)
        }
    }
}

@Composable
fun VideoPlayer(uri: Uri, aspect: Float) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { useController = true } },
        update = { it.player = player },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .background(Color.Black)
            .testTag("player"),
    )
}
