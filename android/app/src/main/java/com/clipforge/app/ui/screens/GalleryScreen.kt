package com.clipforge.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.data.ClipRow
import com.clipforge.app.data.JobRow
import com.clipforge.app.ui.Lime
import com.clipforge.app.ui.Muted
import com.clipforge.app.ui.Nav
import com.clipforge.app.ui.Screen
import com.clipforge.app.ui.SectionCard
import com.clipforge.app.ui.Thumbnail
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

/** Completed projects; tap one to see its clips. */
@Composable
fun ProjectsScreen(app: ClipforgeApp, nav: Nav) {
    val engine = app.engine
    val jobs by rememberQuery(engine, emptyList<Pair<JobRow, ClipRow?>>()) {
        engine.db.listJobs(status = "completed").map { it to engine.db.listClips(it.id).firstOrNull() }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("screen_projects"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (jobs.isEmpty()) {
            item { SectionCard { Text("No finished projects yet.", color = Muted) } }
        }
        items(jobs, key = { it.first.id }) { (j, first) ->
            SectionCard(Modifier.clickable { nav.go(Screen.Gallery(j.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumbnail(first?.thumbRelpath?.let { engine.resolve(it) }, Modifier.size(56.dp, 72.dp))
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(j.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${j.clipCount} clips · ${fmtDate(j.createdAt)}", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun GalleryScreen(app: ClipforgeApp, nav: Nav, jobId: String) {
    val engine = app.engine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by rememberQuery(engine, null as JobRow?, jobId) { engine.db.getJob(jobId) }
    val clips by rememberQuery(engine, emptyList<ClipRow>(), jobId) { engine.db.listClips(jobId) }
    val selected = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf<String?>(null) }

    fun export(ids: List<String>?) {
        busy = "Building ZIP…"
        scope.launch {
            try {
                val built = withContext(Dispatchers.IO) { app.exporter.buildExport(jobId, ids) }
                toast(context, if (built.row.verificationOk) "ZIP ready — verification passed" else "ZIP ready — verification FAILED, see report")
                nav.tab(Screen.Exports)
            } catch (e: Exception) {
                toast(context, e.message ?: "Export failed")
            } finally {
                busy = null
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().testTag("screen_gallery"),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionCard {
                Text(job?.name ?: "", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${clips.size} clips · ${fmtBytes(clips.sumOf { it.sizeBytes })}", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                val b = busy
                if (b != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(b)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { export(null) }, enabled = clips.isNotEmpty(),
                            modifier = Modifier.testTag("export_all")) { Text("Export all (ZIP)") }
                        OutlinedButton(onClick = { export(selected.toList()) }, enabled = selected.isNotEmpty()) {
                            Text("Export ${selected.size}")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selected.clear(); selected.addAll(clips.map { it.id }) }) { Text("Select all") }
                        TextButton(onClick = { selected.clear() }, enabled = selected.isNotEmpty()) { Text("Clear") }
                        TextButton(enabled = selected.isNotEmpty(), onClick = {
                            val chosen = clips.filter { it.id in selected }
                            busy = "Saving to gallery…"
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        for (c in chosen) {
                                            app.exporter.saveVideoToGallery(engine.resolve(c.relpath),
                                                "${safe(job?.name)}_${c.filename}")
                                        }
                                    }
                                    toast(context, "Saved ${chosen.size} clip(s) to Movies/CLIPFORGE")
                                } catch (e: Exception) {
                                    toast(context, e.message ?: "Could not save")
                                } finally {
                                    busy = null
                                }
                            }
                        }) { Text("Save to gallery") }
                    }
                }
            }
        }
        items(clips, key = { it.id }) { c ->
            val isSel = c.id in selected
            Column(
                Modifier
                    .border(if (isSel) 2.dp else 0.dp, if (isSel) Lime else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { nav.go(Screen.Preview(c.id)) }
                    .padding(4.dp)
                    .testTag("clip_${c.idx}"),
            ) {
                Box {
                    Thumbnail(
                        c.thumbRelpath?.let { engine.resolve(it) },
                        Modifier.fillMaxWidth().aspectRatio(c.width.toFloat() / c.height.coerceAtLeast(1)),
                    )
                    Checkbox(
                        checked = isSel,
                        onCheckedChange = { on -> if (on) selected.add(c.id) else selected.remove(c.id) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                Text("Clip ${c.idx}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text("${fmtTime(c.start)} → ${fmtTime(c.end)}", color = Muted, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtDuration(c.duration) + " · " + fmtBytes(c.sizeBytes), color = Muted, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        shareFile(context, engine.resolve(c.relpath), "video/mp4", "Share clip")
                    }) { Text("Share", fontSize = 12.sp) }
                }
            }
        }
    }
}

fun safe(name: String?): String =
    (name ?: "clip").map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(60)
