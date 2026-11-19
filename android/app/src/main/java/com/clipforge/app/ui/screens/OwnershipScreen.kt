package com.clipforge.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.core.DeviceKeyStore
import com.clipforge.app.core.Json
import com.clipforge.app.core.Provenance
import com.clipforge.app.data.JobRow
import com.clipforge.app.ui.ChoiceRow
import com.clipforge.app.ui.Danger
import com.clipforge.app.ui.KeyValue
import com.clipforge.app.ui.Muted
import com.clipforge.app.ui.Nav
import com.clipforge.app.ui.Ok
import com.clipforge.app.ui.SectionCard
import com.clipforge.app.ui.Warn
import com.clipforge.app.ui.rememberQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OwnershipScreen(app: ClipforgeApp, nav: Nav) {
    val engine = app.engine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyInfo by remember { mutableStateOf<DeviceKeyStore.Info?>(null) }
    var report by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var reportTitle by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manifestText by remember { mutableStateOf<Pair<String, String>?>(null) } // (name, text)
    val jobs by rememberQuery(engine, emptyList<JobRow>()) { engine.db.listJobs(status = "completed") }
    var chosenJob by remember { mutableStateOf<JobRow?>(null) }

    LaunchedEffect(Unit) {
        keyInfo = withContext(Dispatchers.IO) { try { engine.keys.info() } catch (e: Exception) { null } }
    }

    fun verify(title: String, block: () -> Map<String, Any?>) {
        working = true
        error = null
        scope.launch {
            try {
                report = withContext(Dispatchers.IO) { block() }
                reportTitle = title
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                report = null
            } finally {
                working = false
            }
        }
    }

    fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
    } catch (e: Exception) { uri.lastPathSegment ?: "file" }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) verify("ZIP: " + displayName(uri)) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not open the file")
            input.use { app.exporter.verifyZip(it) }
        }
    }
    val pickManifest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?.takeIf { it.size <= 16 * 1024 * 1024 }?.toString(Charsets.UTF_8)
                            ?: throw IllegalStateException("Could not read the manifest (missing or larger than 16 MB)")
                    }
                    manifestText = displayName(uri) to text
                    verify("Manifest: " + displayName(uri)) { app.exporter.verifyManifestText(text, emptyList()) }
                } catch (e: Exception) {
                    error = e.message
                }
            }
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val m = manifestText ?: return@rememberLauncherForActivityResult
        if (uris.isNotEmpty()) verify("Manifest + ${uris.size} file(s)") {
            val hashed = uris.map { u ->
                val input = context.contentResolver.openInputStream(u) ?: throw IllegalStateException("Could not open a file")
                displayName(u) to input.use { Provenance.sha256Stream(it).first }
            }
            app.exporter.verifyManifestText(m.second, hashed)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("screen_ownership"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "This phone's signing key") {
            val k = keyInfo
            if (k == null) {
                Text("Loading…", color = Muted)
            } else {
                KeyValue("Algorithm", "Ed25519")
                KeyValue("Key ID", k.keyId, mono = true)
                KeyValue("Fingerprint", k.fingerprint, mono = true)
                KeyValue("Created", k.createdAt)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(k.publicKeyPem.trim(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted,
                        modifier = Modifier.testTag("public_key_pem"))
                }
                Spacer(Modifier.height(6.dp))
                Text("The private key is sealed with an Android Keystore key and never leaves the app. " +
                    "Uninstalling the app or clearing its data destroys it.", color = Muted, fontSize = 12.sp)
            }
        }

        SectionCard(title = "Verify a project on this phone") {
            if (jobs.isEmpty()) {
                Text("No finished projects.", color = Muted, fontSize = 13.sp)
            } else {
                ChoiceRow(jobs, chosenJob ?: jobs.first(), { it.name.take(24) }) { chosenJob = it }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = !working, onClick = {
                    val j = chosenJob ?: jobs.first()
                    verify("Project: " + j.name) { engine.verifyOnDisk(j) }
                }, modifier = Modifier.testTag("verify_project")) { Text("Re-hash and verify") }
            }
        }

        SectionCard(title = "Verify files from anywhere") {
            Text("Works with exports from this phone and from the CLIPFORGE desktop app.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !working, onClick = {
                    pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }) { Text("Check a ZIP") }
                OutlinedButton(enabled = !working, onClick = {
                    pickManifest.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }) { Text("Check a manifest") }
            }
            val m = manifestText
            if (m != null) {
                Spacer(Modifier.height(6.dp))
                Text("Manifest loaded: ${m.first}", fontSize = 13.sp)
                TextButton(enabled = !working, onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                    Text("Add clip files to check against it")
                }
            }
        }

        if (working) CircularProgressIndicator()
        error?.let { Text(it, color = Danger) }
        report?.let { ReportView(reportTitle, it) }

        Text(
            "A valid signature proves the manifest was signed by the holder of that key and that matching " +
                "files are byte-identical. It does not prevent copying and does not by itself establish " +
                "copyright ownership.",
            color = Muted, fontSize = 12.sp,
        )
    }
}

@Composable
fun ReportView(title: String, report: Map<String, Any?>) {
    val summary = Json.obj(report["summary"]) ?: emptyMap()
    val sig = Json.obj(report["signature"]) ?: emptyMap()
    val ok = summary["ok"] == true
    SectionCard(Modifier.testTag("report"), title = title) {
        Text(
            if (ok) "VERIFIED" else "NOT VERIFIED",
            color = if (ok) Ok else Danger, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.testTag(if (ok) "report_ok" else "report_fail"),
        )
        KeyValue("Signature", if (sig["valid"] == true) "valid" else "invalid")
        KeyValue("Signed by", if (sig["trusted_key"] == true) "this phone's key" else "key ${sig["key_id"] ?: "?"}")
        (sig["error"] as? String)?.let { KeyValue("Problem", it) }
        KeyValue("Files matched", "${summary["files_matched"]} of ${summary["files_checked"]}")
        if (num(summary["files_modified"]) > 0) KeyValue("Modified", "${summary["files_modified"]}")
        if (num(summary["files_unknown"]) > 0) KeyValue("Unknown", "${summary["files_unknown"]}")
        if (num(summary["files_missing"]) > 0) KeyValue("Missing", "${summary["files_missing"]}")
        val files = Json.arr(report["files"]) ?: emptyList()
        if (files.isNotEmpty()) Spacer(Modifier.height(6.dp))
        Column {
            for (f in files.take(200)) {
                val fm = Json.obj(f) ?: continue
                val st = fm["status"] as? String ?: ""
                Text(
                    "${fm["name"]}: $st",
                    color = when (st) { "match" -> Ok; "modified" -> Danger; else -> Warn },
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun num(v: Any?): Int = (v as? Number)?.toInt() ?: 0
