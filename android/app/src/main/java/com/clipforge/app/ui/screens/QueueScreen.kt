package com.clipforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.core.Json
import com.clipforge.app.data.JobRow
import com.clipforge.app.engine.ProcessingService
import com.clipforge.app.ui.Danger
import com.clipforge.app.ui.Muted
import com.clipforge.app.ui.Nav
import com.clipforge.app.ui.Screen
import com.clipforge.app.ui.SectionCard
import com.clipforge.app.ui.StatusChip
import com.clipforge.app.ui.fmtBytes
import com.clipforge.app.ui.fmtDate
import com.clipforge.app.ui.fmtDuration
import com.clipforge.app.ui.rememberQuery
import com.clipforge.app.ui.toast

@Composable
fun QueueScreen(app: ClipforgeApp, nav: Nav) {
    val engine = app.engine
    val jobs by rememberQuery(engine, emptyList<JobRow>()) { engine.db.listJobs() }
    var confirmDelete by remember { mutableStateOf<JobRow?>(null) }
    val context = LocalContext.current

    LazyColumn(
        Modifier.fillMaxSize().testTag("screen_queue"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (jobs.isEmpty()) {
            item { SectionCard { Text("The queue is empty. Start a project from New.", color = Muted) } }
        }
        items(jobs, key = { it.id }) { j -> JobCard(j, app, nav, onDelete = { confirmDelete = j }) }
    }

    confirmDelete?.let { j ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete project?") },
            text = { Text("\"${j.name}\", its clips, manifest and imported copy of the source will be removed from the app. " +
                "Files you saved to Downloads or the gallery are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    Thread { if (!engine.delete(j.id)) context.mainExecutor.execute { toast(context, "Cancel the job first") } }.start()
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun JobCard(j: JobRow, app: ClipforgeApp, nav: Nav, onDelete: () -> Unit) {
    val engine = app.engine
    var showDetail by remember { mutableStateOf(false) }
    val duration = remember(j.mediaJson) {
        try { Json.num(Json.parseObject(j.mediaJson)["duration"]) } catch (e: Exception) { null }
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium)
            StatusChip(j.status)
        }
        Text(
            listOfNotNull(
                duration?.let { fmtDuration(it) }, fmtBytes(j.sourceSize), fmtDate(j.createdAt),
            ).joinToString(" · "),
            color = Muted, fontSize = 12.sp,
        )
        if (j.status == "running" || j.status == "queued") {
            Spacer(Modifier.height(8.dp))
            Text(ProcessingService.stageLabel(j.stage) + if (j.status == "running") " · ${j.progress.toInt()}%" else "",
                fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            if (j.status == "running") {
                LinearProgressIndicator(progress = { (j.progress / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (j.status == "completed") {
            Text("${j.clipCount} clips · encoder: ${j.encoderUsed ?: "unknown"}", fontSize = 13.sp)
        }
        j.error?.let { err ->
            Spacer(Modifier.height(6.dp))
            Text(err, color = if (j.status == "failed") Danger else Muted, fontSize = 13.sp)
            if (j.errorDetail != null) {
                TextButton(onClick = { showDetail = !showDetail }) {
                    Text(if (showDetail) "Hide error output" else "Show error output")
                }
                if (showDetail) {
                    Text(j.errorDetail, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (j.status) {
                "queued", "running" -> OutlinedButton(onClick = { Thread { engine.cancel(j.id) }.start() },
                    modifier = Modifier.testTag("cancel_${j.id}")) { Text("Cancel") }
                "failed", "cancelled" -> OutlinedButton(onClick = { Thread { engine.retry(j.id) }.start() }) { Text("Retry") }
                "completed" -> OutlinedButton(onClick = { nav.go(Screen.Gallery(j.id)) }) { Text("Open clips") }
            }
            if (j.status != "running") {
                TextButton(onClick = onDelete) { Text("Delete", color = Danger) }
            }
        }
    }
}
