package com.clipforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.data.ExportRow
import com.clipforge.app.data.JobRow
import com.clipforge.app.ui.Danger
import com.clipforge.app.ui.Muted
import com.clipforge.app.ui.Nav
import com.clipforge.app.ui.Ok
import com.clipforge.app.ui.SectionCard
import com.clipforge.app.ui.fmtBytes
import com.clipforge.app.ui.fmtDate
import com.clipforge.app.ui.rememberQuery
import com.clipforge.app.ui.shareFile
import com.clipforge.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ExportData(val exports: List<ExportRow>, val projects: List<JobRow>)

@Composable
fun ExportsScreen(app: ClipforgeApp, nav: Nav) {
    val engine = app.engine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by rememberQuery(engine, ExportData(emptyList(), emptyList())) {
        ExportData(engine.db.listExports(), engine.db.listJobs(status = "completed"))
    }
    var busy by remember { mutableStateOf(false) }

    fun io(label: String, block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val msg = withContext(Dispatchers.IO) { block() }
                toast(context, msg)
            } catch (e: Exception) {
                toast(context, "$label failed: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("screen_exports"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Export history") {
                if (data.exports.isEmpty()) {
                    Text("No exports yet. Open a project's clips and choose Export.", color = Muted, fontSize = 13.sp)
                }
                for (e in data.exports) {
                    Spacer(Modifier.height(6.dp))
                    Text(e.filename, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp)
                    val count = try { com.clipforge.app.core.Json.arr(com.clipforge.app.core.Json.parse(e.clipIdsJson))?.size ?: 0 } catch (_: Exception) { 0 }
                    Text("${e.jobName} · $count clips · ${fmtBytes(e.sizeBytes)} · ${fmtDate(e.createdAt)}",
                        color = Muted, fontSize = 12.sp)
                    Text(
                        if (e.verificationOk) "Verification passed when built" else "Verification failed when built",
                        color = if (e.verificationOk) Ok else Danger, fontSize = 12.sp,
                    )
                    if (e.fileDeleted) {
                        Text("ZIP removed (only the newest 20 are kept)", color = Muted, fontSize = 12.sp)
                    } else {
                        val zip = engine.resolve(e.relpath)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(enabled = !busy, onClick = {
                                io("Saving") {
                                    app.exporter.saveToDownloads(zip, e.filename, "application/zip")
                                    "Saved to Downloads/CLIPFORGE"
                                }
                            }, modifier = Modifier.testTag("save_${e.id}")) { Text("Save to Downloads") }
                            TextButton(onClick = { shareFile(context, zip, "application/zip", "Share ZIP") }) { Text("Share") }
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "Provenance files") {
                Text("Each project's signed manifest and this phone's public key, for checking clips elsewhere " +
                    "(for example on the CLIPFORGE desktop app's Ownership page).", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = !busy, onClick = {
                    io("Saving key") {
                        val tmp = File(context.cacheDir, "share").apply { mkdirs() }
                        val f = File(tmp, "clipforge_android_public_key.pem")
                        f.writeText(engine.keys.info().publicKeyPem)
                        app.exporter.saveToDownloads(f, f.name, "application/octet-stream")
                        "Public key saved to Downloads/CLIPFORGE"
                    }
                }) { Text("Save public key") }
            }
        }
        items(data.projects, key = { it.id }) { j ->
            SectionCard {
                Text(j.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${j.clipCount} clips · ${fmtDate(j.createdAt)}", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        io("Export") {
                            val built = app.exporter.buildExport(j.id, null)
                            if (built.row.verificationOk) "ZIP ready (verification passed)" else "ZIP ready — verification FAILED"
                        }
                    }) { Text("Export all") }
                    TextButton(enabled = !busy, onClick = {
                        io("Saving manifest") {
                            app.exporter.saveToDownloads(engine.manifestFile(j.id), "${safe(j.name)}_manifest.json",
                                "application/json")
                            "Manifest saved to Downloads/CLIPFORGE"
                        }
                    }) { Text("Save manifest") }
                }
            }
        }
    }
}
