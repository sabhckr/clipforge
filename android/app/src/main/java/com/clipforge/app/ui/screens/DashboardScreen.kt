package com.clipforge.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.data.Database
import com.clipforge.app.data.JobRow
import com.clipforge.app.engine.ProcessingService
import com.clipforge.app.ui.Muted
import com.clipforge.app.ui.Nav
import com.clipforge.app.ui.Screen
import com.clipforge.app.ui.SectionCard
import com.clipforge.app.ui.StatusChip
import com.clipforge.app.ui.fmtBytes
import com.clipforge.app.ui.fmtDate
import com.clipforge.app.ui.rememberQuery

private data class DashData(
    val totals: Database.Totals,
    val freeBytes: Long,
    val active: List<JobRow>,
    val recent: List<JobRow>,
)

@Composable
fun DashboardScreen(app: ClipforgeApp, nav: Nav) {
    val engine = app.engine
    val data by rememberQuery(engine, null as DashData?) {
        val jobs = engine.db.listJobs(limit = 50)
        DashData(engine.db.totals(), engine.freeBytes(), jobs.filter { it.isActive }, jobs.take(8))
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("screen_dashboard"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Text("Turn one long video into short clips, on this phone.", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Scene detection and fixed rules — no AI, no upload, no internet permission.",
                    color = Muted, fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { nav.tab(Screen.NewProject) }, modifier = Modifier.testTag("dash_new")) {
                    Text("New project")
                }
            }
        }
        val d = data
        if (d != null) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Videos", d.totals.videos.toString(), Modifier.weight(1f))
                    Stat("Clips", d.totals.clips.toString(), Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Storage used", fmtBytes(d.totals.clipBytes + d.totals.sourceBytes), Modifier.weight(1f))
                    Stat("Free space", fmtBytes(d.freeBytes), Modifier.weight(1f))
                }
            }
            if (d.active.isNotEmpty()) {
                item {
                    SectionCard(title = "Active") {
                        for (j in d.active) {
                            JobLine(j) { nav.tab(Screen.Queue) }
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Recent projects") {
                    if (d.recent.isEmpty()) {
                        Text("No projects yet.", color = Muted)
                    }
                    for (j in d.recent) {
                        JobLine(j) {
                            if (j.status == "completed") nav.go(Screen.Gallery(j.id)) else nav.tab(Screen.Queue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    SectionCard(modifier) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun JobLine(j: JobRow, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium)
            StatusChip(j.status)
        }
        val sub = when (j.status) {
            "running" -> ProcessingService.stageLabel(j.stage) + " · " + j.progress.toInt() + "%"
            "completed" -> "${j.clipCount} clips · " + fmtDate(j.createdAt)
            else -> fmtDate(j.createdAt)
        }
        Text(sub, color = Muted, fontSize = 12.sp)
        if (j.status == "running") {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { (j.progress / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
}
